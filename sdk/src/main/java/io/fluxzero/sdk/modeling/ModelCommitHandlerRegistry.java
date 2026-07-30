/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.LocalHandlerResult;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Fallback command registry for payloads that declare independent-model applies or target model receiver handlers.
 * <p>
 * Regular {@code @HandleCommand} handlers remain first in the command registry. This handler therefore activates only
 * when normal command handling did not select a handler.
 */
public final class ModelCommitHandlerRegistry implements HandlerRegistry, HandlerFactory {
    private static final boolean BATCH_DIAGNOSTICS =
            Boolean.getBoolean(
                    "fluxzero.modelCommitBatchGateDiagnostics");
    private static final boolean DISABLE_BATCH_GATES =
            Boolean.getBoolean(
                    "fluxzero.disableModelCommitBatchGates");
    private final DefaultModelRepository repository;
    private final ModelCommitEngine engine;
    private final ModelCommitter committer;
    private final Handler<DeserializingMessage> decoratedHandler;
    private final HandlerDecorator handlerDecorator;
    private final ModelConflictPolicy conflictPolicy;
    private final ModelConflictResolver conflictResolver;
    private final int maxConflictRetries;
    private final AutomaticModelHandling automaticHandling;
    private final GraphProjectionCompletion graphProjectionCompletion;
    private final ModelCommitCoordinator commitCoordinator =
            new ModelCommitCoordinator();
    private final Serializer serializer;
    private final EventStoreClient eventStoreClient;
    private final List<Class<?>> registeredModelTypes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Class<?>, CompletableFuture<ModelGraphProjectionStatus>>
            graphProjectionRegistrations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, CommitPlan> commitPlans =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TargetPlanKey, ModelTargetResolver.TargetPlan> targetPlans =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, List<ProjectionRoot>> projectionPlans =
            new ConcurrentHashMap<>();

    /**
     * Returns the repository shared by automatic command handling and public model loads.
     */
    public DefaultModelRepository repository() {
        return repository;
    }

    /** Creates the automatic model-commit registry. */
    public ModelCommitHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            Serializer serializer,
            Serializer snapshotSerializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerDecorator handlerDecorator,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxConflictRetries,
            AutomaticModelHandling automaticHandling,
            GraphProjectionCompletion graphProjectionCompletion) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.eventStoreClient =
                Objects.requireNonNull(eventStoreClient, "eventStoreClient");
        this.committer = new ModelCommitter(
                eventStoreClient, serializer, documentSerializer,
                eventDispatchInterceptor, source, snapshotSerializer,
                this::afterCommit);
        this.engine = new ModelCommitEngine(parameterResolvers);
        this.conflictPolicy = ModelConflictPolicy.resolve(conflictPolicy);
        this.conflictResolver = Objects.requireNonNull(
                conflictResolver, "conflictResolver");
        if (maxConflictRetries < 0) {
            throw new IllegalArgumentException(
                    "Maximum model conflict retries must not be negative");
        }
        this.maxConflictRetries = maxConflictRetries;
        this.automaticHandling =
                Objects.requireNonNull(
                        automaticHandling,
                        "automaticHandling");
        this.graphProjectionCompletion =
                graphProjectionCompletion == GraphProjectionCompletion.DEFAULT
                        ? GraphProjectionCompletion.ASYNC
                        : Objects.requireNonNull(
                                graphProjectionCompletion,
                                "graphProjectionCompletion");
        this.handlerDecorator = Objects.requireNonNull(
                handlerDecorator, "handlerDecorator");
        this.decoratedHandler = handlerDecorator.wrap(new CommitHandler(null));
    }

    /**
     * Executes an update directly through model assertions, apply interceptors, applies, and commit handling.
     * Regular command handlers and command handler decorators are deliberately bypassed.
     *
     * @param update model update message
     * @return completion of the durable model commit
     */
    public CompletableFuture<Void> assertAndApply(Message update) {
        try {
            Objects.requireNonNull(update, "update");
            DeserializingMessage message =
                    new DeserializingMessage(update, MessageType.COMMAND, serializer);
            if (!hasModelApplies(
                    message.getPayloadClass())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "No model @Apply handler found for "
                        + message.getPayloadClass().getName()));
            }
            return execute(message).thenApply(ignored -> null);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
        HandlerInvoker invoker = decoratedHandler.getInvokerOrNull(message);
        if (invoker == null) {
            return Optional.empty();
        }
        try {
            Object result = invoker.invoke();
            if (result instanceof CompletableFuture<?> future) {
                return Optional.of(future.thenApply(value -> value));
            }
            return Optional.of(CompletableFuture.completedFuture(result));
        } catch (Throwable failure) {
            return Optional.of(CompletableFuture.failedFuture(failure));
        }
    }

    @Override
    public LocalHandlerResult handleResult(DeserializingMessage message) {
        Optional<CompletableFuture<Object>> result = handle(message);
        return result.map(LocalHandlerResult::asynchronous)
                .orElseGet(LocalHandlerResult::notHandled);
    }

    @Override
    public boolean canHandle(DeserializingMessage message) {
        return message.getMessageType() == MessageType.COMMAND
               && hasModelApplies(
                       message.getPayloadClass())
               && automaticHandlingEnabled(
                       message.getPayloadClass(),
                       new LinkedHashSet<>());
    }

    private boolean hasModelApplies(
            Class<?> payloadType) {
        return declaresModelCommit(payloadType, new LinkedHashSet<>());
    }

    private boolean automaticHandlingEnabled(
            Class<?> payloadType,
            Set<Class<?>> visiting) {
        if (!visiting.add(payloadType)) {
            return true;
        }
        try {
            for (ModelMetadata.HandlerMethod handler :
                    planFor(payloadType).handlers()) {
                if (handler.kind()
                    == ModelMetadata.HandlerKind.APPLY
                    && !handler.targetModelTypes()
                            .isEmpty()
                    && !automaticHandlingEnabled(
                            handler)) {
                    return false;
                }
                if (handler.kind()
                    == ModelMetadata.HandlerKind.INTERCEPT_APPLY) {
                    for (Class<?> emitted :
                            handler.emittedPayloadTypes()) {
                        if (!automaticHandlingEnabled(
                                emitted, visiting)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } finally {
            visiting.remove(payloadType);
        }
    }

    private boolean automaticHandlingEnabled(
            ModelMetadata.HandlerMethod handler) {
        Apply apply =
                handler.executable()
                        .getAnnotation(
                                Apply.class);
        AutomaticModelHandling policy =
                apply == null
                        ? AutomaticModelHandling.DEFAULT
                        : apply.automaticHandling();
        if (policy == AutomaticModelHandling.DEFAULT) {
            policy = handler.targetModelTypes()
                    .stream()
                    .map(type -> type.getAnnotation(
                            Model.class))
                    .filter(Objects::nonNull)
                    .map(Model::automaticHandling)
                    .filter(value ->
                                    value
                                    != AutomaticModelHandling.DEFAULT)
                    .findFirst()
                    .orElse(
                            AutomaticModelHandling.DEFAULT);
        }
        if (policy == AutomaticModelHandling.DEFAULT) {
            policy = automaticHandling;
        }
        return policy
               != AutomaticModelHandling.DISABLED;
    }

    @Override
    public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (!ModelMetadata.of(targetType).isModel()) {
            return Registration.noOp();
        }
        registeredModelTypes.add(targetType);
        projectionRoots(targetType).forEach(this::registerGraphProjection);
        clearPlans();
        return () -> {
            registeredModelTypes.remove(targetType);
            clearPlans();
        };
    }

    @Override
    public List<?> trackingTargets(Object target, HandlerFilter handlerFilter) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (!ModelMetadata.of(targetType).isModel()) {
            return List.of(target);
        }
        LinkedHashSet<Class<?>> payloadTypes = ModelMetadata.of(targetType)
                .handlerMethods().stream()
                .filter(handler -> handler.kind()
                        == ModelMetadata.HandlerKind.APPLY)
                .filter(handler -> handlerFilter.test(
                        handler.executable().getDeclaringClass(),
                        handler.executable()))
                .flatMap(handler -> commandPayloadTypes(handler).stream())
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        return payloadTypes.isEmpty()
                ? List.of(target)
                : List.copyOf(payloadTypes);
    }

    private static List<Class<?>> commandPayloadTypes(
            ModelMetadata.HandlerMethod handler) {
        return Stream.of(handler.executable().getParameters())
                .filter(parameter -> handler.modelParameters().stream()
                        .noneMatch(model ->
                                model.parameter().equals(parameter)))
                .map(Parameter::getType)
                .filter(type -> !isFrameworkParameter(type))
                .toList();
    }

    /**
     * Creates one tracked command handler for a registered model receiver or a payload type that declares model
     * applies. The handler remains scoped to that registration so ordinary command handlers retain precedence.
     */
    @Override
    public Optional<Handler<DeserializingMessage>> createHandler(
            Object target,
            HandlerFilter handlerFilter,
            List<HandlerInterceptor> extraInterceptors) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        boolean modelReceiver = ModelMetadata.of(targetType).isModel();
        boolean payloadCommit = declaresModelCommit(
                targetType, new LinkedHashSet<>())
                                && planFor(targetType).handlers().stream()
                                        .anyMatch(handler ->
                                                handlerFilter.test(
                                                        handler.executable()
                                                                .getDeclaringClass(),
                                                        handler.executable()));
        if (!modelReceiver && !payloadCommit) {
            return Optional.empty();
        }
        if (modelReceiver) {
            return Optional.empty();
        }
        HandlerDecorator decorator = Stream.concat(
                        extraInterceptors.stream(),
                        Stream.of(handlerDecorator))
                .reduce(HandlerDecorator::andThen)
                .orElseThrow();
        return Optional.of(decorator.wrap(
                new CommitHandler(targetType)));
    }

    @Override
    public boolean hasLocalHandlers() {
        return true;
    }

    @Override
    public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
        // Model commits are selected from @Model and @Apply metadata, independent of local handler ownership.
    }

    private CompletableFuture<Object> execute(DeserializingMessage message) {
        return execute(message, null);
    }

    private CompletableFuture<Object> execute(
            DeserializingMessage message,
            BatchCommitTicket batchTicket) {
        CompletableFuture<?> registrations =
                CompletableFuture.allOf(
                        graphProjectionRegistrations
                                .values()
                                .toArray(
                                        CompletableFuture[]::new));
        CompletableFuture<Object> result =
                registrations.thenCompose(
                        ignored -> executeRegistered(
                                message, batchTicket));
        if (batchTicket != null) {
            result.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    batchTicket.exclude();
                }
            });
        }
        return result;
    }

    private CompletableFuture<Object> executeRegistered(
            DeserializingMessage message,
            BatchCommitTicket batchTicket) {
        ModelCommitEngine.CommitEvaluation initialEvaluation =
                evaluate(message);
        if (batchTicket != null) {
            batchTicket.assign(
                    initialEvaluation.readModelIds());
        }
        if (ModelConflictPolicies.resolve(
                initialEvaluation,
                conflictPolicy)
            != ModelConflictPolicy.ACCEPT) {
            return executeBatched(
                    message, initialEvaluation,
                    batchTicket);
        }
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return commitCoordinator.coordinate(
                initialEvaluation.readModelIds(),
                contended -> {
                    if (!contended) {
                        return context.supply(
                                () -> executeBatched(
                                        message,
                                        initialEvaluation,
                                        batchTicket));
                    }
                    /*
                     * The predecessor commonly completes on the websocket result callback. A fresh evaluation may
                     * synchronously load a model, so it must not make that callback wait for a response that the same
                     * callback has to dispatch.
                     */
                    return CompletableFuture
                            .supplyAsync(
                                    context.wrap(
                                            () -> evaluate(
                                                    message)))
                            .thenCompose(
                                    context.wrap(
                                            evaluation ->
                                                    executeBatched(
                                                            message,
                                                            evaluation,
                                                            batchTicket)));
                });
    }

    private CompletableFuture<Object> executeBatched(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation evaluation,
            BatchCommitTicket batchTicket) {
        if (batchTicket == null) {
            return executeEvaluation(
                    message, evaluation);
        }
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return batchTicket.awaitRelease()
                .thenCompose(ignored ->
                                     context.supply(
                                             () -> executeEvaluation(
                                                     message,
                                                     evaluation)));
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation evaluation) {
        ModelConflictPolicy effectiveConflictPolicy =
                ModelConflictPolicies.resolve(
                        evaluation,
                        conflictPolicy);
        Map<String, Set<String>> awaitedGraphProjections =
                awaitedGraphProjectionTargets(
                        evaluation);
        return ensureGraphProjections(evaluation)
                .thenCompose(ignored -> {
                    Runnable localCommitComplete =
                            repository.beginLocalCommit(
                                    evaluation.transitions()
                                            .stream()
                                            .map(
                                                    ModelCommitEngine
                                                            .Transition
                                                            ::modelId)
                                            .distinct()
                                            .toList());
                    try {
                        CompletableFuture<Optional<CommitModelsResult>> result =
                                effectiveConflictPolicy
                                == ModelConflictPolicy.ACCEPT
                                        ? committer.commitAcceptingRebase(
                                                message.getMessageId(),
                                                evaluation,
                                                (messages, stateIndex) -> {
                                                    try {
                                                        return CompletableFuture
                                                                .completedFuture(
                                                                        rebase(
                                                                                messages,
                                                                                stateIndex));
                                                    } catch (Throwable failure) {
                                                        return CompletableFuture
                                                                .failedFuture(
                                                                        failure);
                                                    }
                                                })
                                        : committer.commit(
                                                message.getMessageId(),
                                                evaluation,
                                                effectiveConflictPolicy,
                                                conflictResolver,
                                                maxConflictRetries,
                                                () -> reload(
                                                        message,
                                                        evaluation
                                                                .readModelIds()));
                        return result.whenComplete(
                                        (commitResult, failure) ->
                                                localCommitComplete
                                                        .run())
                                .thenCompose(commitResult ->
                                        awaitGraphProjections(
                                                commitResult,
                                                awaitedGraphProjections))
                                .handle(
                                (commitResult, failure) -> {
                                    if (failure != null) {
                                        if (effectiveConflictPolicy
                                            != ModelConflictPolicy.ACCEPT) {
                                            repository.invalidateModels(
                                                    evaluation
                                                            .readModelIds());
                                        }
                                        if (failure
                                            instanceof java.util.concurrent.CompletionException completion
                                            && completion.getCause()
                                               != null) {
                                            throw completion;
                                        }
                                        throw new java.util.concurrent.CompletionException(
                                                failure);
                                    }
                                    return null;
                                });
                    } catch (Throwable failure) {
                        localCommitComplete.run();
                        throw failure;
                    }
                });
    }

    private CompletableFuture<Optional<CommitModelsResult>>
            awaitGraphProjections(
                    Optional<CommitModelsResult> result,
                    Map<String, Set<String>> collections) {
        if (result.isEmpty()
            || collections.isEmpty()
            || result.get().getSubsteps().isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }
        long stateIndex =
                result.get().getSubsteps().getLast()
                        .getStateIndex();
        long firstStateIndex =
                result.get().getSubsteps().getFirst()
                        .getStateIndex();
        return CompletableFuture.allOf(
                        collections.entrySet().stream()
                                .map(entry ->
                                             eventStoreClient
                                                     .awaitModelGraphProjection(
                                                             new AwaitModelGraphProjection(
                                                                     entry.getKey(),
                                                                     stateIndex,
                                                                     firstStateIndex,
                                                                     entry.getValue())))
                                .toArray(
                                        CompletableFuture[]::new))
                .thenApply(ignored -> result);
    }

    Set<String> awaitedGraphProjections(
            ModelCommitEngine.CommitEvaluation evaluation) {
        return awaitedGraphProjectionTargets(
                evaluation).keySet();
    }

    Map<String, Set<String>> awaitedGraphProjectionTargets(
            ModelCommitEngine.CommitEvaluation evaluation) {
        GraphProjectionCompletion consumer =
                Tracker.current()
                        .map(Tracker::getConfiguration)
                        .map(configuration ->
                                     configuration
                                             .getGraphProjectionCompletion())
                        .orElse(
                                GraphProjectionCompletion.DEFAULT);
        LinkedHashMap<String, LinkedHashSet<String>> result =
                new LinkedHashMap<>();
        for (ModelCommitEngine.Transition transition :
                evaluation.transitions()) {
            Apply apply =
                    transition.handler()
                            .getAnnotation(
                                    Apply.class);
            GraphProjectionCompletion applyPolicy =
                    apply == null
                            ? GraphProjectionCompletion.DEFAULT
                            : apply.graphProjectionCompletion();
            projectionRoots(transition.modelType())
                    .forEach(root -> {
                        GraphProjectionCompletion policy =
                                resolveProjectionCompletion(
                                        applyPolicy,
                                        consumer,
                                        root.projection()
                                                .completion());
                        if (policy
                            == GraphProjectionCompletion.AWAIT) {
                            result.computeIfAbsent(
                                            root.collection(),
                                            ignored ->
                                                    new LinkedHashSet<>())
                                    .add(
                                            transition.modelId());
                        }
                    });
        }
        return result.entrySet().stream()
                .collect(
                        java.util.stream.Collectors
                                .toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        entry ->
                                                Set.copyOf(
                                                        entry.getValue())));
    }

    private GraphProjectionCompletion resolveProjectionCompletion(
            GraphProjectionCompletion apply,
            GraphProjectionCompletion consumer,
            GraphProjectionCompletion root) {
        if (apply != GraphProjectionCompletion.DEFAULT) {
            return apply;
        }
        if (consumer != GraphProjectionCompletion.DEFAULT) {
            return consumer;
        }
        if (root != GraphProjectionCompletion.DEFAULT) {
            return root;
        }
        return graphProjectionCompletion;
    }

    private List<ProjectionRoot> projectionRoots(
            Class<?> modelType) {
        return projectionPlans.computeIfAbsent(modelType, this::inspectProjectionRoots);
    }

    private List<ProjectionRoot> inspectProjectionRoots(Class<?> modelType) {
        return inspectProjectionRoots(modelType, new LinkedHashSet<>());
    }

    private List<ProjectionRoot> inspectProjectionRoots(
            Class<?> modelType, Set<Class<?>> visited) {
        if (!visited.add(modelType)) {
            return List.of();
        }
        List<ProjectionRoot> result = new ArrayList<>();
        ModelMetadata metadata = ModelMetadata.of(modelType);
        metadata.model()
                .flatMap(model -> ModelGraphProjections.configuration(modelType)
                        .map(configuration -> new ProjectionRoot(
                                modelType, configuration, model.graphProjection())))
                .ifPresent(result::add);
        metadata.parentReferences().stream()
                .map(ModelMetadata.ParentReference::parentModelType)
                .filter(Objects::nonNull)
                .forEach(parent ->
                        result.addAll(inspectProjectionRoots(parent, visited)));
        return List.copyOf(result);
    }

    private CompletableFuture<Void> ensureGraphProjections(
            ModelCommitEngine.CommitEvaluation evaluation) {
        LinkedHashSet<ProjectionRoot> roots = evaluation.substeps().stream()
                .flatMap(substep -> substep.transitions().stream())
                .map(ModelCommitEngine.Transition::modelType)
                .flatMap(type -> projectionRoots(type).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        roots.forEach(this::registerGraphProjection);
        return CompletableFuture.allOf(
                roots.stream()
                        .map(ProjectionRoot::modelType)
                        .distinct()
                        .map(graphProjectionRegistrations::get)
                        .filter(Objects::nonNull)
                        .toArray(CompletableFuture[]::new));
    }

    private void registerGraphProjection(
            ProjectionRoot root) {
        CompletableFuture<ModelGraphProjectionStatus> registration =
                graphProjectionRegistrations.computeIfAbsent(
                        root.modelType(),
                        ignored -> eventStoreClient.registerModelGraphProjection(
                                new RegisterModelGraphProjection(root.configuration(), false)));
        registration.whenComplete((result, failure) -> {
            if (failure != null) {
                graphProjectionRegistrations.remove(root.modelType(), registration);
            }
        });
    }

    private CompletableFuture<ModelCommitEngine.CommitEvaluation> reload(
            DeserializingMessage message, List<String> staleModelIds) {
        repository.invalidateModels(staleModelIds);
        try {
            return CompletableFuture.completedFuture(evaluate(message));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void afterCommit(
            ModelCommitter.CommittedCommit committed) {
        if (committed.prepared().transitionGroups().size()
            != committed.result().getSubsteps().size()) {
            throw new IllegalStateException(
                    "Model commit returned a different number of substeps than requested");
        }
        LinkedHashMap<String, DefaultModelRepository.CommittedModel> finalStates =
                new LinkedHashMap<>();
        for (int substep = 0;
             substep < committed.prepared().transitionGroups().size();
             substep++) {
            List<ModelCommitter.EffectiveTransition> transitions =
                    committed.prepared().transitionGroups().get(substep);
            var substepResult = committed.result().getSubsteps().get(substep);
            var commitStep = committed.prepared().commit().getSubsteps().get(substep);
            if (transitions.size() != substepResult.getTargets().size()) {
                throw new IllegalStateException(
                        "Model commit returned a different number of targets than requested");
            }
            Instant timestamp = commitStep.getEvent() == null
                    ? Instant.now()
                    : Instant.ofEpochMilli(commitStep.getEvent().getTimestamp());
            for (int targetIndex = 0;
                 targetIndex < transitions.size();
                 targetIndex++) {
                ModelCommitter.EffectiveTransition effective =
                        transitions.get(targetIndex);
                ModelCommitEngine.Transition transition = effective.transition();
                if (!effective.updateState()) {
                    continue;
                }
                var targetResult = substepResult.getTargets().get(targetIndex);
                DefaultModelRepository.CommittedModel previous =
                        finalStates.get(transition.modelId());
                List<DefaultModelRepository.CommittedRevision> revisions =
                        previous == null
                                ? new ArrayList<>()
                                : new ArrayList<>(
                                        previous.revisions());
                revisions.add(
                        new DefaultModelRepository.CommittedRevision(
                                transition.after(),
                                targetResult.getSequenceNumber(),
                                substepResult.getStateIndex(),
                                commitStep.getEvent() == null
                                        ? null : commitStep.getEvent().getMessageId(),
                                substepResult.getEventIndex(),
                                timestamp));
                finalStates.put(
                        transition.modelId(),
                        new DefaultModelRepository.CommittedModel(
                                transition.modelId(), transition.modelType(),
                                targetResult.isHistoryComplete(),
                                revisions));
            }
        }
        repository.updateAfterCommit(
                List.copyOf(finalStates.values()));
    }

    private ModelCommitEngine.CommitEvaluation evaluate(DeserializingMessage initialMessage) {
        return engine.evaluate(initialMessage, new CommitLoader(null));
    }

    private ModelCommitEngine.CommitEvaluation rebase(
            List<DeserializingMessage> messages,
            long stateIndex) {
        return engine.rebase(messages, new CommitLoader(stateIndex));
    }

    private final class CommitLoader implements ModelCommitEngine.SubstepResolver {
        private final Long pinnedStateIndex;
        private final Map<String, Entity<?>> commitEntities = new LinkedHashMap<>();
        private final Map<AncestorPlanKey, List<ModelTargetResolver.ResolvedModel>> ancestorPlans =
                new LinkedHashMap<>();

        private CommitLoader(Long pinnedStateIndex) {
            this.pinnedStateIndex = pinnedStateIndex;
        }

        @Override
        public ModelCommitEngine.ResolvedSubstep resolve(
                DeserializingMessage substep,
                Long requestedStateIndex,
                Map<String, Object> stagedValues) {
            Long boundary = requestedStateIndex == null ? pinnedStateIndex : requestedStateIndex;
            if (pinnedStateIndex != null && !pinnedStateIndex.equals(boundary)) {
                throw new IllegalStateException(
                        "Apply-only rebase moved from state index %d to %d"
                                .formatted(pinnedStateIndex, boundary));
            }
            CommitPlan plan = planFor(substep.getPayloadClass());
            List<ModelMetadata.HandlerMethod> handlers =
                    pinnedStateIndex == null ? plan.handlers() : plan.applies();
            ModelTargetResolver.Resolution resolution =
                    targetPlan(substep.getPayloadClass(), plan, pinnedStateIndex != null)
                            .resolve(substep.getPayload());
            AncestorPlanKey planKey = resolution.hasAncestorDependencies()
                    ? ancestorPlanKey(resolution, stagedValues) : null;
            List<ModelTargetResolver.ResolvedModel> effectiveTargets = planKey == null
                    ? resolution.models() : ancestorPlans.get(planKey);
            List<ModelTargetResolver.ResolvedModel> missing = effectiveTargets == null ? List.of()
                    : effectiveTargets.stream()
                            .filter(target -> !commitEntities.containsKey(target.modelId()))
                            .toList();

            long stateIndex = boundary == null ? -1L : boundary;
            if (effectiveTargets == null) {
                ModelCommitContext loaded = load(resolution, boundary, stagedValues);
                stateIndex = loaded.readStateIndex();
                effectiveTargets = targets(loaded);
                ancestorPlans.put(planKey, effectiveTargets);
            } else if ((pinnedStateIndex == null && requestedStateIndex == null)
                       || !missing.isEmpty()) {
                ModelTargetResolver.Resolution loadResolution =
                        pinnedStateIndex == null && requestedStateIndex == null
                                ? planKey == null ? resolution
                                        : resolution.withResolvedModels(effectiveTargets)
                                : new ModelTargetResolver.Resolution(missing, List.of());
                stateIndex = load(loadResolution, boundary, stagedValues).readStateIndex();
            }

            ModelTargetResolver.Resolution effectiveResolution = planKey == null
                    ? resolution : resolution.withResolvedModels(effectiveTargets);
            LinkedHashMap<String, Entity<?>> selected = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : effectiveTargets) {
                selected.put(target.modelId(), Objects.requireNonNull(
                        commitEntities.get(target.modelId()),
                        "Missing commit-scoped model " + target.modelId()));
            }
            return new ModelCommitEngine.ResolvedSubstep(
                    ModelCommitContext.create(stateIndex, effectiveResolution, selected), handlers);
        }

        @Override
        public void prefetch(
                List<DeserializingMessage> messages,
                long readStateIndex,
                Map<String, Object> stagedValues) {
            if (pinnedStateIndex != null) {
                return;
            }
            LinkedHashMap<String, ModelTargetResolver.ResolvedModel> missing = new LinkedHashMap<>();
            for (DeserializingMessage message : messages) {
                Object payload = message.getPayload();
                CommitPlan plan = planFor(payload.getClass());
                ModelTargetResolver.Resolution resolution =
                        targetPlan(payload.getClass(), plan, false).resolve(payload);
                if (resolution.hasAncestorDependencies()) {
                    AncestorPlanKey key = ancestorPlanKey(resolution, stagedValues);
                    if (!ancestorPlans.containsKey(key)) {
                        ancestorPlans.put(
                                key, targets(load(resolution, readStateIndex, stagedValues)));
                    }
                    continue;
                }
                resolution.models().stream()
                        .filter(target -> !commitEntities.containsKey(target.modelId()))
                        .forEach(target -> missing.putIfAbsent(target.modelId(), target));
            }
            if (!missing.isEmpty()) {
                load(new ModelTargetResolver.Resolution(
                        List.copyOf(missing.values()), List.of()), readStateIndex, stagedValues);
            }
        }

        private ModelCommitContext load(
                ModelTargetResolver.Resolution resolution,
                Long boundary,
                Map<String, Object> stagedValues) {
            ModelCommitContext loaded = repository.loadContext(resolution, boundary, stagedValues);
            if (boundary != null && loaded.readStateIndex() != boundary) {
                throw new IllegalStateException(
                        "Model commit requested state index %d but loaded %d"
                                .formatted(boundary, loaded.readStateIndex()));
            }
            loaded.entries().forEach(entry ->
                    commitEntities.put(entry.target().modelId(), entry.entity()));
            return loaded;
        }

        private static List<ModelTargetResolver.ResolvedModel> targets(ModelCommitContext context) {
            return context.entries().stream().map(ModelCommitContext.Entry::target).toList();
        }
    }

    private CommitPlan planFor(Class<?> payloadType) {
        return commitPlans.computeIfAbsent(payloadType, type -> {
            List<ModelMetadata.HandlerMethod> handlers = inspectHandlers(type);
            List<ModelMetadata.HandlerMethod> applies = handlers.stream()
                    .filter(handler -> handler.kind() == ModelMetadata.HandlerKind.APPLY)
                    .toList();
            return new CommitPlan(handlers, applies);
        });
    }

    private ModelTargetResolver.TargetPlan targetPlan(
            Class<?> payloadType, CommitPlan plan, boolean appliesOnly) {
        return targetPlans.computeIfAbsent(
                new TargetPlanKey(payloadType, appliesOnly),
                ignored -> ModelTargetResolver.plan(
                        payloadType, appliesOnly ? plan.applies() : plan.handlers()));
    }

    private void clearPlans() {
        commitPlans.clear();
        targetPlans.clear();
    }

    private static AncestorPlanKey ancestorPlanKey(
            ModelTargetResolver.Resolution resolution,
            Map<String, Object> stagedValues) {
        List<StagedRelationships> relationships =
                new ArrayList<>(stagedValues.size());
        stagedValues.forEach((modelId, value) -> {
            List<ParentRelationship> parents = new ArrayList<>();
            if (value != null) {
                for (ModelMetadata.ParentReference parent :
                        ModelMetadata.validate(
                                value.getClass()).parentReferences()) {
                    Object parentId = parent.read(value);
                    if (parentId != null) {
                        parents.add(new ParentRelationship(
                                Objects.requireNonNull(
                                        parentId.toString(),
                                        "Parent ID string"),
                                parent.parentModelType(),
                                parent.path()));
                    }
                }
            }
            relationships.add(new StagedRelationships(
                    modelId, List.copyOf(parents)));
        });
        return new AncestorPlanKey(
                resolution, List.copyOf(relationships));
    }

    private List<ModelMetadata.HandlerMethod> inspectHandlers(Class<?> payloadType) {
        List<ModelMetadata.HandlerMethod> payloadHandlers =
                ModelMetadata.of(payloadType).handlerMethods();
        LinkedHashSet<ModelMetadata.HandlerMethod> result =
                new LinkedHashSet<>(payloadHandlers);
        LinkedHashSet<Class<?>> receiverTypes = new LinkedHashSet<>(
                ModelTargetResolver.referencedModelTypes(payloadType));
        receiverTypes.addAll(registeredModelTypes);
        for (Class<?> receiverType : receiverTypes) {
            ModelMetadata.of(receiverType).handlerMethods().stream()
                    .filter(handler -> ModelMetadata.acceptsPayload(handler, payloadType))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private boolean declaresModelCommit(
            Class<?> payloadType,
            LinkedHashSet<Class<?>> visiting) {
        if (!visiting.add(payloadType)) {
            return false;
        }
        try {
            List<ModelMetadata.HandlerMethod> handlers = planFor(payloadType).handlers();
            if (handlers.stream().anyMatch(handler ->
                    handler.kind() == ModelMetadata.HandlerKind.APPLY
                    && !handler.targetModelTypes().isEmpty())) {
                return true;
            }
            return handlers.stream()
                    .filter(handler -> handler.kind()
                            == ModelMetadata.HandlerKind.INTERCEPT_APPLY)
                    .flatMap(handler ->
                            handler.emittedPayloadTypes().stream())
                    .anyMatch(emitted ->
                            declaresModelCommit(emitted, visiting));
        } finally {
            visiting.remove(payloadType);
        }
    }

    private static boolean isFrameworkParameter(Class<?> parameterType) {
        return parameterType.equals(Instant.class)
               || parameterType.equals(io.fluxzero.common.api.Metadata.class)
               || parameterType.equals(Message.class)
               || parameterType.equals(DeserializingMessage.class);
    }

    private boolean ownsRegisteredModelCommit(
            Class<?> receiverType, Class<?> payloadType) {
        return registeredModelTypes.stream()
                .distinct()
                .filter(type -> ModelMetadata.of(type).handlerMethods().stream()
                        .filter(handler -> handler.kind()
                                == ModelMetadata.HandlerKind.APPLY)
                        .anyMatch(handler -> ModelMetadata.acceptsPayload(
                                handler, payloadType)))
                .min(java.util.Comparator.comparing(Class::getName))
                .map(receiverType::equals)
                .orElse(false);
    }

    private record AncestorPlanKey(
            ModelTargetResolver.Resolution resolution,
            List<StagedRelationships> stagedRelationships) {
    }

    private record StagedRelationships(
            String modelId,
            List<ParentRelationship> parents) {
    }

    private record ParentRelationship(
            String parentId,
            Class<?> parentType,
            String path) {
    }

    private record CommitPlan(
            List<ModelMetadata.HandlerMethod> handlers,
            List<ModelMetadata.HandlerMethod> applies) {
    }

    private record TargetPlanKey(
            Class<?> payloadType, boolean appliesOnly) {
    }

    private record ProjectionRoot(
            Class<?> modelType,
            ModelGraphProjectionConfiguration configuration,
            GraphProjection projection) {
        private String collection() {
            return configuration.getCollection();
        }
    }

    private final class CommitHandler
            implements Handler<DeserializingMessage> {
        private final Class<?> trackingTarget;

        private CommitHandler(Class<?> trackingTarget) {
            this.trackingTarget = trackingTarget;
        }

        @Override
        public Class<?> getTargetClass() {
            return trackingTarget == null
                    ? ModelCommitHandlerRegistry.class : trackingTarget;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return Optional.ofNullable(getInvokerOrNull(message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
            boolean selected = trackingTarget == null
                    || ModelMetadata.of(trackingTarget).isModel()
                       && ownsRegisteredModelCommit(
                               trackingTarget, message.getPayloadClass())
                    || !ModelMetadata.of(trackingTarget).isModel()
                       && trackingTarget.isAssignableFrom(
                               message.getPayloadClass());
            if (!selected || !canHandle(message)) {
                return null;
            }
            BatchCommitTicket batchTicket =
                    DISABLE_BATCH_GATES
                            ? null
                            : batchCommitTicket();
            return new HandlerInvoker.DelegatingHandlerInvoker(
                    HandlerInvoker.call(
                            () -> execute(
                                    message, batchTicket))) {
                @Override
                public boolean requiresBatchSegmentOrder() {
                    /*
                     * The generic tracker segment is deliberately coarse and may collide for unrelated models. Exact
                     * read-set coordination and batch waves below own ordering for automatic model commits.
                     */
                    return false;
                }

                @Override
                public Object invoke(
                        java.util.function.BiFunction<Object, Object, Object> resultCombiner) {
                    return delegate.invoke(resultCombiner);
                }
            };
        }
    }

    private BatchCommitTicket batchCommitTicket() {
        BatchCommitGates gates =
                DeserializingMessage.computeForBatchIfAbsent(
                        this, ignored -> {
                            BatchCommitGates created =
                                    new BatchCommitGates();
                            DeserializingMessage.whenBatchCompletes(
                                    created::close);
                            return created;
                        });
        return gates.register();
    }

    private static final class BatchCommitGates {
        private final Map<String, Integer> modelOccurrences =
                new HashMap<>();
        private final Map<Integer, BatchCommitGate> waves =
                new HashMap<>();
        private int registered;
        private int resolved;
        private boolean closed;
        private Throwable failure;

        synchronized BatchCommitTicket register() {
            if (closed) {
                return BatchCommitTicket.released();
            }
            registered++;
            return new BatchCommitTicket(this);
        }

        synchronized BatchCommitGate assign(
                Collection<String> modelIds) {
            List<String> keys =
                    modelIds.stream()
                            .distinct()
                            .toList();
            int wave =
                    keys.stream()
                            .mapToInt(key ->
                                              modelOccurrences
                                                      .getOrDefault(
                                                              key, 0))
                            .max()
                            .orElse(0);
            int nextWave = wave + 1;
            keys.forEach(key ->
                                 modelOccurrences.put(
                                         key, nextWave));
            BatchCommitGate gate =
                    waves.computeIfAbsent(
                            wave,
                            ignored ->
                                    new BatchCommitGate());
            gate.register();
            resolved++;
            if (failure != null) {
                gate.close(failure);
            }
            closeWavesIfResolved();
            return gate;
        }

        synchronized void exclude() {
            resolved++;
            closeWavesIfResolved();
        }

        synchronized void close(Throwable failure) {
            closed = true;
            this.failure = failure;
            if (BATCH_DIAGNOSTICS) {
                System.out.printf(
                        "SDK model commit gates close: registered=%d resolved=%d waves=%d failure=%s%n",
                        registered, resolved, waves.size(),
                        failure == null
                                ? "none"
                                : failure.getClass().getSimpleName());
            }
            if (failure != null) {
                waves.values()
                        .forEach(gate ->
                                         gate.close(failure));
                return;
            }
            closeWavesIfResolved();
        }

        private void closeWavesIfResolved() {
            if (!closed
                || failure != null
                || resolved != registered) {
                return;
            }
            waves.values()
                    .forEach(gate ->
                                     gate.close(null));
        }
    }

    private static final class BatchCommitGate {
        private final CompletableFuture<Void> release =
                new CompletableFuture<>();
        private int expected;
        private int arrived;
        private boolean closed;

        synchronized void register() {
            expected++;
        }

        synchronized CompletableFuture<Void> arrive() {
            arrived++;
            tryRelease();
            return release;
        }

        synchronized void close(Throwable failure) {
            closed = true;
            if (failure != null) {
                release.completeExceptionally(failure);
                return;
            }
            tryRelease();
            if (BATCH_DIAGNOSTICS && !release.isDone()) {
                System.out.printf(
                        "SDK model commit gate waiting: expected=%d arrived=%d%n",
                        expected, arrived);
            }
        }

        private void tryRelease() {
            if (closed
                && arrived == expected) {
                if (BATCH_DIAGNOSTICS) {
                    System.out.printf(
                            "SDK model commit gate: expected=%d%n",
                            expected);
                }
                release.complete(null);
            }
        }
    }

    private static final class BatchCommitTicket {
        private static final BatchCommitTicket RELEASED =
                new BatchCommitTicket(null);

        private final BatchCommitGates gates;
        private final AtomicBoolean resolved =
                new AtomicBoolean();
        private final AtomicBoolean arrived =
                new AtomicBoolean();
        private volatile BatchCommitGate gate;

        private BatchCommitTicket(
                BatchCommitGates gates) {
            this.gates = gates;
        }

        static BatchCommitTicket released() {
            return RELEASED;
        }

        void assign(
                Collection<String> modelIds) {
            if (gates != null
                && resolved.compareAndSet(
                        false, true)) {
                gate = gates.assign(
                        modelIds);
            }
        }

        void exclude() {
            if (gates != null
                && resolved.compareAndSet(
                        false, true)) {
                gates.exclude();
            }
        }

        CompletableFuture<Void> awaitRelease() {
            if (gates == null) {
                return CompletableFuture.completedFuture(
                        null);
            }
            BatchCommitGate assigned =
                    gate;
            if (assigned == null) {
                throw new IllegalStateException(
                        "Model commit batch ticket was awaited before target assignment");
            }
            if (!arrived.compareAndSet(
                    false, true)) {
                throw new IllegalStateException(
                        "Model commit batch ticket was awaited more than once");
            }
            return assigned.arrive();
        }
    }
}
