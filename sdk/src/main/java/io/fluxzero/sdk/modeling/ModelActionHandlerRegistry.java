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
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.persisting.search.DocumentStore;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Fallback command registry for payloads that declare independent-model applies or target model receiver handlers.
 * <p>
 * Regular {@code @HandleCommand} handlers remain first in the command registry. This handler therefore activates only
 * when normal command handling did not select a handler.
 */
public final class ModelActionHandlerRegistry implements HandlerRegistry, HandlerFactory {
    private final DefaultModelRepository repository;
    private final ModelActionEngine engine;
    private final ModelActionCommitter committer;
    private final Handler<DeserializingMessage> decoratedHandler;
    private final HandlerDecorator handlerDecorator;
    private final ModelConflictPolicy conflictPolicy;
    private final ModelConflictResolver conflictResolver;
    private final int maxConflictRetries;
    private final AutomaticModelHandling automaticHandling;
    private final GraphProjectionCompletion graphProjectionCompletion;
    private final Serializer serializer;
    private final EventStoreClient eventStoreClient;
    private final List<Class<?>> registeredModelTypes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Class<?>, CompletableFuture<ModelGraphProjectionStatus>>
            graphProjectionRegistrations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, List<ModelMetadata.HandlerMethod>> handlerPlans =
            new ConcurrentHashMap<>();

    /**
     * Returns the repository shared by automatic command handling and public model loads.
     */
    public DefaultModelRepository repository() {
        return repository;
    }

    public ModelActionHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerDecorator handlerDecorator) {
        this(repository, eventStoreClient, documentStore, serializer,
             serializer, documentSerializer, eventDispatchInterceptor,
             source, parameterResolvers, handlerDecorator);
    }

    /**
     * Creates a model-action registry with a dedicated snapshot serializer.
     */
    public ModelActionHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            Serializer snapshotSerializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerDecorator handlerDecorator) {
        this(repository, eventStoreClient, documentStore, serializer,
             snapshotSerializer, documentSerializer,
             eventDispatchInterceptor, source, parameterResolvers, handlerDecorator,
             ModelConflictPolicy.ACCEPT, ModelConflictResolver.fail(), 0,
             AutomaticModelHandling.ENABLED,
             GraphProjectionCompletion.ASYNC);
    }

    /**
     * Creates a model-action registry with an explicit optional conflict policy.
     */
    public ModelActionHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerDecorator handlerDecorator,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxConflictRetries) {
        this(repository, eventStoreClient, documentStore, serializer,
             serializer, documentSerializer, eventDispatchInterceptor,
             source, parameterResolvers, handlerDecorator, conflictPolicy,
             conflictResolver, maxConflictRetries,
             AutomaticModelHandling.ENABLED,
             GraphProjectionCompletion.ASYNC);
    }

    /**
     * Creates a model-action registry with dedicated snapshot serialization and an explicit optional conflict policy.
     */
    public ModelActionHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            Serializer snapshotSerializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerDecorator handlerDecorator,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxConflictRetries) {
        this(repository, eventStoreClient, documentStore,
             serializer, snapshotSerializer,
             documentSerializer,
             eventDispatchInterceptor, source,
             parameterResolvers, handlerDecorator,
             conflictPolicy, conflictResolver,
             maxConflictRetries,
             AutomaticModelHandling.ENABLED,
             GraphProjectionCompletion.ASYNC);
    }

    /**
     * Creates a model-action registry with scoped automatic handling defaults.
     */
    public ModelActionHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
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
        this.committer = new ModelActionCommitter(
                eventStoreClient, documentStore, serializer, documentSerializer,
                eventDispatchInterceptor, source, snapshotSerializer,
                this::afterCommit);
        this.engine = new ModelActionEngine(parameterResolvers);
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
        this.decoratedHandler = handlerDecorator.wrap(new ActionHandler(null));
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
        return !handlersFor(payloadType)
                .isEmpty();
    }

    private boolean automaticHandlingEnabled(
            Class<?> payloadType,
            Set<Class<?>> visiting) {
        if (!visiting.add(payloadType)) {
            return true;
        }
        try {
            for (ModelMetadata.HandlerMethod handler :
                    handlersFor(payloadType)) {
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
        registerGraphProjection(targetType);
        handlerPlans.clear();
        return () -> {
            registeredModelTypes.remove(targetType);
            handlerPlans.clear();
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
        boolean payloadAction = declaresModelAction(
                targetType, new LinkedHashSet<>())
                                && handlersFor(targetType).stream()
                                        .anyMatch(handler ->
                                                handlerFilter.test(
                                                        handler.executable()
                                                                .getDeclaringClass(),
                                                        handler.executable()));
        if (!modelReceiver && !payloadAction) {
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
                new ActionHandler(targetType)));
    }

    @Override
    public boolean hasLocalHandlers() {
        return true;
    }

    @Override
    public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
        // Model actions are selected from @Model and @Apply metadata, independent of local handler ownership.
    }

    private CompletableFuture<Object> execute(DeserializingMessage message) {
        CompletableFuture<?> registrations =
                CompletableFuture.allOf(
                        graphProjectionRegistrations
                                .values()
                                .toArray(
                                        CompletableFuture[]::new));
        return registrations.thenCompose(
                ignored -> executeRegistered(message));
    }

    private CompletableFuture<Object> executeRegistered(
            DeserializingMessage message) {
        ModelActionEngine.ActionEvaluation evaluation =
                evaluate(message);
        ModelConflictPolicy effectiveConflictPolicy =
                ModelConflictPolicies.resolve(
                        evaluation,
                        conflictPolicy);
        Map<String, Set<String>> awaitedGraphProjections =
                awaitedGraphProjectionTargets(
                        evaluation);
        return ensureGraphProjections(evaluation)
                .thenCompose(ignored -> {
                    CompletableFuture<Optional<CommitModelActionResult>> result =
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
                    return result.thenCompose(commitResult ->
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
                });
    }

    private CompletableFuture<Optional<CommitModelActionResult>>
            awaitGraphProjections(
                    Optional<CommitModelActionResult> result,
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
            ModelActionEngine.ActionEvaluation evaluation) {
        return awaitedGraphProjectionTargets(
                evaluation).keySet();
    }

    Map<String, Set<String>> awaitedGraphProjectionTargets(
            ModelActionEngine.ActionEvaluation evaluation) {
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
        for (ModelActionEngine.Transition transition :
                evaluation.transitions()) {
            Apply apply =
                    transition.handler()
                            .getAnnotation(
                                    Apply.class);
            GraphProjectionCompletion applyPolicy =
                    apply == null
                            ? GraphProjectionCompletion.DEFAULT
                            : apply.graphProjectionCompletion();
            projectionRoots(
                    transition.modelType(),
                    new LinkedHashSet<>())
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
            Class<?> modelType,
            Set<Class<?>> visited) {
        if (!visited.add(modelType)) {
            return List.of();
        }
        List<ProjectionRoot> result =
                new ArrayList<>();
        ModelMetadata.of(modelType).model()
                .filter(model ->
                                !model.graphProjection()
                                        .collection().isEmpty())
                .flatMap(ignored ->
                                 ModelGraphProjections.configuration(
                                         modelType))
                .ifPresent(configuration ->
                                   result.add(
                                           new ProjectionRoot(
                                                   configuration
                                                           .getCollection(),
                                                   ModelMetadata.of(
                                                                   modelType)
                                                           .model()
                                                           .orElseThrow()
                                                           .graphProjection())));
        ModelMetadata.of(modelType).parentReferences()
                .stream()
                .map(ModelMetadata.ParentReference::parentModelType)
                .filter(Objects::nonNull)
                .forEach(parent ->
                                 result.addAll(
                                         projectionRoots(
                                                 parent,
                                                 visited)));
        return List.copyOf(result);
    }

    private CompletableFuture<Void> ensureGraphProjections(
            ModelActionEngine.ActionEvaluation evaluation) {
        LinkedHashSet<Class<?>> visited =
                new LinkedHashSet<>();
        evaluation.substeps().stream()
                .flatMap(substep ->
                                 substep.transitions()
                                         .stream())
                .map(ModelActionEngine.Transition::modelType)
                .forEach(type ->
                                 registerGraphProjections(
                                         type, visited));
        return CompletableFuture.allOf(
                visited.stream()
                        .map(graphProjectionRegistrations::get)
                        .filter(Objects::nonNull)
                        .toArray(
                                CompletableFuture[]::new));
    }

    private void registerGraphProjections(
            Class<?> modelType,
            Set<Class<?>> visited) {
        if (!visited.add(modelType)) {
            return;
        }
        registerGraphProjection(modelType);
        ModelMetadata.of(modelType)
                .parentReferences().stream()
                .map(ModelMetadata.ParentReference::parentModelType)
                .filter(Objects::nonNull)
                .forEach(parentType ->
                                 registerGraphProjections(
                                         parentType, visited));
    }

    private void registerGraphProjection(
            Class<?> modelType) {
        ModelGraphProjections.configuration(
                        modelType)
                .ifPresent(configuration -> {
                    CompletableFuture<ModelGraphProjectionStatus>
                            registration =
                            graphProjectionRegistrations
                                    .computeIfAbsent(
                                            modelType,
                                            ignored ->
                                                    eventStoreClient
                                                            .registerModelGraphProjection(
                                                                    new RegisterModelGraphProjection(
                                                                            configuration,
                                                                            false)));
                    registration.whenComplete(
                            (result, failure) -> {
                                if (failure != null) {
                                    graphProjectionRegistrations
                                            .remove(
                                                    modelType,
                                                    registration);
                                }
                            });
                });
    }

    private CompletableFuture<ModelActionEngine.ActionEvaluation> reload(
            DeserializingMessage message, List<String> staleModelIds) {
        repository.invalidateModels(staleModelIds);
        try {
            return CompletableFuture.completedFuture(evaluate(message));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Void> afterCommit(
            ModelActionCommitter.CommittedAction committed) {
        if (committed.prepared().transitionGroups().size()
            != committed.result().getSubsteps().size()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Model commit returned a different number of substeps than requested"));
        }
        LinkedHashMap<String, DefaultModelRepository.CommittedModel> finalStates =
                new LinkedHashMap<>();
        for (int substep = 0;
             substep < committed.prepared().transitionGroups().size();
             substep++) {
            List<ModelActionCommitter.EffectiveTransition> transitions =
                    committed.prepared().transitionGroups().get(substep);
            var substepResult = committed.result().getSubsteps().get(substep);
            var actionSubstep = committed.prepared().action().getSubsteps().get(substep);
            if (transitions.size() != substepResult.getTargets().size()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Model commit returned a different number of targets than requested"));
            }
            Instant timestamp = actionSubstep.getEvent() == null
                    ? Instant.now()
                    : Instant.ofEpochMilli(actionSubstep.getEvent().getTimestamp());
            for (int targetIndex = 0;
                 targetIndex < transitions.size();
                 targetIndex++) {
                ModelActionCommitter.EffectiveTransition effective =
                        transitions.get(targetIndex);
                ModelActionEngine.Transition transition = effective.transition();
                var targetResult = substepResult.getTargets().get(targetIndex);
                Model model = ModelMetadata.of(
                        transition.modelType()).model().orElseThrow();
                boolean snapshotDue = model.snapshotPeriod() > 0
                                      && Math.floorMod(
                                              targetResult.getSequenceNumber() + 1L,
                                              model.snapshotPeriod()) == 0L;
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
                                actionSubstep.getEvent() == null
                                        ? null : actionSubstep.getEvent().getMessageId(),
                                substepResult.getEventIndex(),
                                timestamp));
                finalStates.put(
                        transition.modelId(),
                        new DefaultModelRepository.CommittedModel(
                                transition.modelId(), transition.modelType(),
                                targetResult.isHistoryComplete(),
                                snapshotDue
                                && !committed.result()
                                        .isSnapshotsApplied()
                                || previous != null && previous.snapshotDue(),
                                revisions));
            }
        }
        return repository.updateAfterCommit(
                List.copyOf(finalStates.values()));
    }

    private ModelActionEngine.ActionEvaluation evaluate(DeserializingMessage initialMessage) {
        class ActionLoader implements ModelActionEngine.SubstepResolver {
            private final Map<String, Entity<?>> actionEntities =
                    new LinkedHashMap<>();
            private final Map<AncestorPlanKey,
                    List<ModelTargetResolver.ResolvedModel>> ancestorPlans =
                    new LinkedHashMap<>();

            @Override
            public ModelActionEngine.ResolvedSubstep resolve(
                    DeserializingMessage substep,
                    Long requestedStateIndex,
                    Map<String, Object> stagedValues) {
                Object payload = substep.getPayload();
                List<ModelMetadata.HandlerMethod> handlers =
                        handlersFor(payload.getClass());
                ModelTargetResolver.Resolution resolution =
                        ModelTargetResolver.resolve(payload, handlers);
                AncestorPlanKey ancestorPlanKey =
                        resolution.hasAncestorDependencies()
                                ? ancestorPlanKey(
                                        resolution, stagedValues)
                                : null;
                List<ModelTargetResolver.ResolvedModel> effectiveTargets =
                        ancestorPlanKey == null
                                ? resolution.models()
                                : ancestorPlans.get(ancestorPlanKey);
                List<ModelTargetResolver.ResolvedModel> missing =
                        effectiveTargets == null
                                ? List.of()
                                : effectiveTargets.stream()
                                .filter(target -> !actionEntities.containsKey(
                                        target.modelId()))
                                .toList();
                long stateIndex = requestedStateIndex == null
                        ? -1L : requestedStateIndex;
                if (effectiveTargets == null) {
                    ModelActionContext loaded = repository.loadContext(
                            resolution, requestedStateIndex,
                            stagedValues);
                    stateIndex = loaded.readStateIndex();
                    effectiveTargets = targets(loaded);
                    ancestorPlans.put(
                            ancestorPlanKey, effectiveTargets);
                    retain(loaded);
                } else if (requestedStateIndex == null
                           || !missing.isEmpty()) {
                    ModelTargetResolver.Resolution loadResolution =
                            requestedStateIndex == null
                                    ? ancestorPlanKey == null
                                            ? resolution
                                            : resolution.withResolvedModels(
                                                    effectiveTargets)
                                    : new ModelTargetResolver.Resolution(
                                            missing, List.of());
                    ModelActionContext loaded = repository.loadContext(
                            loadResolution, requestedStateIndex,
                            stagedValues);
                    stateIndex = loaded.readStateIndex();
                    retain(loaded);
                }
                ModelTargetResolver.Resolution effectiveResolution =
                        ancestorPlanKey == null
                                ? resolution
                                : resolution.withResolvedModels(
                                        effectiveTargets);
                LinkedHashMap<String, Entity<?>> selected =
                        new LinkedHashMap<>();
                for (ModelTargetResolver.ResolvedModel target :
                        effectiveTargets) {
                    selected.put(
                            target.modelId(),
                            Objects.requireNonNull(
                                    actionEntities.get(target.modelId()),
                                    "Missing action-scoped model "
                                    + target.modelId()));
                }
                return new ModelActionEngine.ResolvedSubstep(
                        ModelActionContext.create(
                                stateIndex, effectiveResolution,
                                selected),
                        handlers);
            }

            @Override
            public void prefetch(
                    List<DeserializingMessage> messages,
                    long readStateIndex,
                    Map<String, Object> stagedValues) {
                LinkedHashMap<String, ModelTargetResolver.ResolvedModel>
                        missing = new LinkedHashMap<>();
                for (DeserializingMessage message : messages) {
                    Object payload = message.getPayload();
                    List<ModelMetadata.HandlerMethod> handlers =
                            handlersFor(payload.getClass());
                    ModelTargetResolver.Resolution resolution =
                            ModelTargetResolver.resolve(
                                    payload, handlers);
                    if (resolution.hasAncestorDependencies()) {
                        AncestorPlanKey key = ancestorPlanKey(
                                resolution, stagedValues);
                        if (!ancestorPlans.containsKey(key)) {
                            ModelActionContext loaded =
                                    repository.loadContext(
                                            resolution,
                                            readStateIndex,
                                            stagedValues);
                            if (loaded.readStateIndex()
                                != readStateIndex) {
                                throw new IllegalStateException(
                                        "Ancestor prefetch requested state index %d but loaded %d"
                                                .formatted(
                                                        readStateIndex,
                                                        loaded.readStateIndex()));
                            }
                            ancestorPlans.put(key, targets(loaded));
                            retain(loaded);
                        }
                        continue;
                    }
                    resolution.models().stream()
                            .filter(target -> !actionEntities.containsKey(
                                    target.modelId()))
                            .forEach(target -> missing.putIfAbsent(
                                    target.modelId(), target));
                }
                if (!missing.isEmpty()) {
                    retain(repository.loadContext(
                            new ModelTargetResolver.Resolution(
                                    List.copyOf(missing.values()), List.of()),
                            readStateIndex));
                }
            }

            private void retain(ModelActionContext loaded) {
                loaded.entries().forEach(entry -> actionEntities.put(
                        entry.target().modelId(), entry.entity()));
            }

            private List<ModelTargetResolver.ResolvedModel> targets(
                    ModelActionContext context) {
                return context.entries().stream()
                        .map(ModelActionContext.Entry::target)
                        .toList();
            }
        }
        return engine.evaluate(initialMessage, new ActionLoader());
    }

    private ModelActionEngine.ActionEvaluation rebase(
            List<DeserializingMessage> messages,
            long stateIndex) {
        class RebaseLoader
                implements ModelActionEngine.SubstepResolver {
            private final Map<String, Entity<?>> actionEntities =
                    new LinkedHashMap<>();
            private final Map<AncestorPlanKey,
                    List<ModelTargetResolver.ResolvedModel>> ancestorPlans =
                    new LinkedHashMap<>();

            @Override
            public ModelActionEngine.ResolvedSubstep resolve(
                    DeserializingMessage substep,
                    Long requestedStateIndex,
                    Map<String, Object> stagedValues) {
                long boundary = requestedStateIndex == null
                        ? stateIndex : requestedStateIndex;
                if (boundary != stateIndex) {
                    throw new IllegalStateException(
                            "Apply-only rebase moved from state index %d to %d"
                                    .formatted(
                                            stateIndex, boundary));
                }
                List<ModelMetadata.HandlerMethod> handlers =
                        handlersFor(
                                substep.getPayloadClass()).stream()
                                .filter(handler -> handler.kind()
                                                   == ModelMetadata.HandlerKind.APPLY)
                                .toList();
                ModelTargetResolver.Resolution resolution =
                        ModelTargetResolver.resolve(
                                substep.getPayload(),
                                handlers);
                AncestorPlanKey ancestorPlanKey =
                        resolution.hasAncestorDependencies()
                                ? ancestorPlanKey(
                                        resolution, stagedValues)
                                : null;
                List<ModelTargetResolver.ResolvedModel> effectiveTargets =
                        ancestorPlanKey == null
                                ? resolution.models()
                                : ancestorPlans.get(ancestorPlanKey);
                List<ModelTargetResolver.ResolvedModel> missing =
                        effectiveTargets == null
                                ? List.of()
                                : effectiveTargets.stream()
                                .filter(target ->
                                                !actionEntities
                                                        .containsKey(
                                                                target.modelId()))
                                .toList();
                if (effectiveTargets == null) {
                    ModelActionContext loaded =
                            repository.loadContext(
                                    resolution, stateIndex,
                                    stagedValues);
                    if (loaded.readStateIndex()
                        != stateIndex) {
                        throw new IllegalStateException(
                                "Apply-only rebase requested state index %d but loaded %d"
                                        .formatted(
                                                stateIndex,
                                                loaded.readStateIndex()));
                    }
                    effectiveTargets = loaded.entries().stream()
                            .map(ModelActionContext.Entry::target)
                            .toList();
                    ancestorPlans.put(
                            ancestorPlanKey, effectiveTargets);
                    loaded.entries().forEach(entry ->
                                                     actionEntities.put(
                                                             entry.target()
                                                                     .modelId(),
                                                             entry.entity()));
                } else if (!missing.isEmpty()) {
                    ModelActionContext loaded =
                            repository.loadContext(
                                    new ModelTargetResolver.Resolution(
                                            missing, List.of()),
                                    stateIndex, stagedValues);
                    if (loaded.readStateIndex()
                        != stateIndex) {
                        throw new IllegalStateException(
                                "Apply-only rebase requested state index %d but loaded %d"
                                        .formatted(
                                                stateIndex,
                                                loaded.readStateIndex()));
                    }
                    loaded.entries().forEach(entry ->
                                                     actionEntities.put(
                                                             entry.target()
                                                                     .modelId(),
                                                             entry.entity()));
                }
                ModelTargetResolver.Resolution effectiveResolution =
                        ancestorPlanKey == null
                                ? resolution
                                : resolution.withResolvedModels(
                                        effectiveTargets);
                LinkedHashMap<String, Entity<?>> selected =
                        new LinkedHashMap<>();
                for (ModelTargetResolver.ResolvedModel target :
                        effectiveTargets) {
                    selected.put(
                            target.modelId(),
                            Objects.requireNonNull(
                                    actionEntities.get(
                                            target.modelId()),
                                    "Missing rebased model "
                                    + target.modelId()));
                }
                return new ModelActionEngine.ResolvedSubstep(
                        ModelActionContext.create(
                                stateIndex, effectiveResolution,
                                selected),
                        handlers);
            }
        }
        return engine.rebase(
                messages, new RebaseLoader());
    }

    private List<ModelMetadata.HandlerMethod> handlersFor(Class<?> payloadType) {
        return handlerPlans.computeIfAbsent(payloadType, this::inspectHandlers);
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
        boolean payloadModelAction = declaresModelAction(
                payloadType, new LinkedHashSet<>());
        LinkedHashSet<ModelMetadata.HandlerMethod> result =
                payloadModelAction
                        ? new LinkedHashSet<>(payloadHandlers)
                        : new LinkedHashSet<>();
        LinkedHashSet<Class<?>> receiverTypes = new LinkedHashSet<>(
                ModelTargetResolver.referencedModelTypes(payloadType));
        receiverTypes.addAll(registeredModelTypes);
        for (Class<?> receiverType : receiverTypes) {
            ModelMetadata.of(receiverType).handlerMethods().stream()
                    .filter(handler -> potentiallyAcceptsPayload(handler, payloadType))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private boolean declaresModelAction(
            Class<?> payloadType,
            LinkedHashSet<Class<?>> visiting) {
        if (!visiting.add(payloadType)) {
            return false;
        }
        try {
            List<ModelMetadata.HandlerMethod> handlers =
                    ModelMetadata.of(payloadType).handlerMethods();
            if (handlers.stream().anyMatch(handler ->
                    handler.kind() == ModelMetadata.HandlerKind.APPLY
                    && !handler.targetModelTypes().isEmpty())) {
                return true;
            }
            LinkedHashSet<Class<?>> receiverTypes = new LinkedHashSet<>(
                    ModelTargetResolver.referencedModelTypes(payloadType));
            receiverTypes.addAll(registeredModelTypes);
            if (receiverTypes.stream().anyMatch(receiverType ->
                    ModelMetadata.of(receiverType).handlerMethods().stream()
                            .filter(handler -> handler.kind()
                                    == ModelMetadata.HandlerKind.APPLY)
                            .anyMatch(handler -> potentiallyAcceptsPayload(
                                    handler, payloadType)))) {
                return true;
            }
            return handlers.stream()
                    .filter(handler -> handler.kind()
                            == ModelMetadata.HandlerKind.INTERCEPT_APPLY)
                    .flatMap(handler ->
                            handler.emittedPayloadTypes().stream())
                    .anyMatch(emitted ->
                            declaresModelAction(emitted, visiting));
        } finally {
            visiting.remove(payloadType);
        }
    }

    private static boolean potentiallyAcceptsPayload(
            ModelMetadata.HandlerMethod handler, Class<?> payloadType) {
        Executable executable = handler.executable();
        boolean hasUnmatchedDomainParameter = false;
        for (Parameter parameter : executable.getParameters()) {
            if (handler.modelParameters().stream()
                    .anyMatch(model -> model.parameter().equals(parameter))) {
                continue;
            }
            Class<?> parameterType = parameter.getType();
            if (parameterType.isAssignableFrom(payloadType)) {
                return true;
            }
            if (!isFrameworkParameter(parameterType)) {
                hasUnmatchedDomainParameter = true;
            }
        }
        return !hasUnmatchedDomainParameter;
    }

    private static boolean isFrameworkParameter(Class<?> parameterType) {
        return parameterType.equals(Instant.class)
               || parameterType.equals(io.fluxzero.common.api.Metadata.class)
               || parameterType.equals(Message.class)
               || parameterType.equals(DeserializingMessage.class);
    }

    private boolean ownsRegisteredModelAction(
            Class<?> receiverType, Class<?> payloadType) {
        return registeredModelTypes.stream()
                .distinct()
                .filter(type -> ModelMetadata.of(type).handlerMethods().stream()
                        .filter(handler -> handler.kind()
                                == ModelMetadata.HandlerKind.APPLY)
                        .anyMatch(handler -> potentiallyAcceptsPayload(
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

    private record ProjectionRoot(
            String collection,
            GraphProjection projection) {
    }

    private final class ActionHandler
            implements Handler<DeserializingMessage> {
        private final Class<?> trackingTarget;

        private ActionHandler(Class<?> trackingTarget) {
            this.trackingTarget = trackingTarget;
        }

        @Override
        public Class<?> getTargetClass() {
            return trackingTarget == null
                    ? ModelActionHandlerRegistry.class : trackingTarget;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return Optional.ofNullable(getInvokerOrNull(message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
            boolean selected = trackingTarget == null
                    || ModelMetadata.of(trackingTarget).isModel()
                       && ownsRegisteredModelAction(
                               trackingTarget, message.getPayloadClass())
                    || !ModelMetadata.of(trackingTarget).isModel()
                       && trackingTarget.isAssignableFrom(
                               message.getPayloadClass());
            return selected && canHandle(message)
                    ? HandlerInvoker.call(() -> execute(message)) : null;
        }
    }
}
