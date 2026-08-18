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
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
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
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Command handler factory for payloads that declare independent-model applies or target model receiver handlers.
 * <p>
 * Regular {@code @HandleCommand} handlers remain first in the command registry. Automatic model handlers are tracked
 * asynchronously and only activate for registered payload/model types whose local interceptor chain reaches a model
 * apply. The local registry surface exists solely for the synchronous {@code TestFixture}, which deliberately forces
 * all handlers onto one thread.
 */
@Slf4j
public final class ModelCommitHandlerRegistry implements HandlerRegistry, HandlerFactory, AutoCloseable {
    private static final CompletableFuture<Void> COMPLETED_VOID =
            CompletableFuture.completedFuture(null);
    private final DefaultModelRepository repository;
    private final ModelExecutionPlan.Compiler compiler;
    private final ModelCommitProtocol protocol;
    private final Handler<DeserializingMessage> decoratedHandler;
    private final HandlerDecorator handlerDecorator;
    private final ModelConflictPolicy conflictPolicy;
    private final ModelConflictResolver conflictResolver;
    private final int maxConflictRetries;
    private final AutomaticModelHandling automaticHandling;
    private final GraphProjectionCompletion graphProjectionCompletion;
    private final ModelBatchScope.BatchLifecycle batchLifecycle;
    private final boolean awaitAfterHandlerCommitsBeforeResults;
    private final Serializer serializer;
    private final EventStoreClient eventStoreClient;
    private final CopyOnWriteArrayList<Class<?>> registeredModelTypes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Class<?>> knownModelTypes = new CopyOnWriteArrayList<>();
    private volatile boolean registeredModelTypesDiscovered;
    private final ConcurrentHashMap<Class<?>, CompletableFuture<ModelGraphProjectionStatus>>
            graphProjectionRegistrations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, ModelExecutionPlan> commitPlans =
            new ConcurrentHashMap<>();
    private volatile CachedExecutionPlan recentCommitPlan;
    private volatile boolean localHandlingEnabled;

    /**
     * Returns the repository shared by automatic command handling and public model loads.
     */
    public DefaultModelRepository repository() {
        return repository;
    }

    /**
     * Returns a stable snapshot of model types currently registered with this application.
     */
    public List<Class<?>> registeredModelTypes() {
        return List.copyOf(registeredModelTypes);
    }

    /**
     * Returns model types that were either registered as handlers or observed as concrete model-commit targets.
     * Unlike {@link #registeredModelTypes()}, this list is structural metadata and must not be used to discover
     * handlers.
     */
    public List<Class<?>> knownModelTypes() {
        discoverRegisteredModelTypes();
        return List.copyOf(knownModelTypes);
    }

    private void discoverRegisteredModelTypes() {
        if (registeredModelTypesDiscovered) {
            return;
        }
        synchronized (knownModelTypes) {
            if (registeredModelTypesDiscovered) {
                return;
            }
            ReflectionUtils.getRegisteredTypes().stream()
                    .filter(type -> ReflectionUtils.getTypeMetadata(type).typeAnnotation(Model.class) != null)
                    .forEach(knownModelTypes::addIfAbsent);
            registeredModelTypesDiscovered = true;
        }
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
        this.protocol = new ModelCommitProtocol(
                eventStoreClient, serializer, documentSerializer,
                eventDispatchInterceptor, source, snapshotSerializer,
                this::afterCommitBatch);
        ModelExecutionPlan.Compiler sharedExecution = repository.modelExecution();
        this.compiler = sharedExecution == null
                ? new ModelExecutionPlan.Compiler(parameterResolvers)
                : sharedExecution;
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
        this.awaitAfterHandlerCommitsBeforeResults =
                io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty(
                        ModelCommitPolicy.AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY,
                        true);
        this.batchLifecycle = new ModelBatchScope.BatchLifecycle(
                protocol::beginReadyBatch, protocol::beginBatch,
                () -> awaitAfterHandlerCommitsBeforeResults);
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
        return assertAndApply(update, null, -1);
    }

    /** Executes an update against one explicitly selected persisted model. */
    public CompletableFuture<Void> assertAndApply(
            Message update, String modelId, Class<?> modelType) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelType, "modelType");
        DeserializingMessage message = new DeserializingMessage(
                Objects.requireNonNull(update, "update"), MessageType.COMMAND, serializer)
                .putContext(ExplicitModelTarget.class,
                            new ExplicitModelTarget(modelId, modelType));
        return assertAndApply(message, null, -1);
    }

    /**
     * Executes independent updates concurrently and batches only the transport of commits that become ready together.
     * Each update keeps its own commit, conflict handling, and durability completion.
     */
    public CompletableFuture<Void> assertAndApplyAll(List<Message> updates) {
        Objects.requireNonNull(updates, "updates");
        List<Message> messages = updates.stream()
                .map(update -> Objects.requireNonNull(update, "update"))
                .toList();
        if (messages.isEmpty()) {
            return COMPLETED_VOID;
        }
        ModelCommitBatchingClient.ModelCommitBatch transportBatch =
                protocol.beginReadyBatch();
        ThreadLocalContext.Snapshot context = ThreadLocalContext.capture();
        List<CompletableFuture<CompletableFuture<Void>>> starts =
                new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            Message update = messages.get(index);
            int slot = index;
            starts.add(CompletableFuture.supplyAsync(
                    context.wrap(() -> assertAndApply(
                            update, transportBatch, slot)),
                    task -> Thread.ofVirtual()
                            .name("Fluxzero-model-commit").start(task)));
        }
        return CompletableFuture.allOf(starts.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            if (transportBatch != null) {
                transportBatch.flush();
            }
            return CompletableFuture.allOf(starts.stream()
                    .map(CompletableFuture::join)
                    .toArray(CompletableFuture[]::new));
        });
    }

    private CompletableFuture<Void> assertAndApply(
            Message update,
            ModelCommitBatchingClient.ModelCommitBatch transportBatch,
            int transportSlot) {
        return assertAndApply(new DeserializingMessage(
                Objects.requireNonNull(update, "update"),
                MessageType.COMMAND, serializer), transportBatch, transportSlot);
    }

    private CompletableFuture<Void> assertAndApply(
            DeserializingMessage message,
            ModelCommitBatchingClient.ModelCommitBatch transportBatch,
            int transportSlot) {
        try {
            return evaluateExplicit(
                    message, Evaluation.APPLY,
                    transportBatch, transportSlot, true)
                    .thenApply(ignored -> null);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Runs apply interceptors and immediate model assertions without invoking applies or committing model changes.
     * Regular command handlers and command handler decorators are deliberately bypassed.
     *
     * @param update model assertion message
     * @return completion of the validation-only evaluation
     */
    public CompletableFuture<Void> assertLegal(Message update) {
        try {
            Objects.requireNonNull(update, "update");
            DeserializingMessage message =
                    new DeserializingMessage(update, MessageType.COMMAND, serializer);
            return evaluateExplicit(
                    message, Evaluation.ASSERT, null, -1, true)
                    .thenApply(ignored -> null);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Applies an already accepted event without re-running command assertions or apply interceptors.
     */
    public CompletableFuture<Void> applyStoredEvent(Message event) {
        try {
            Objects.requireNonNull(event, "event");
            DeserializingMessage message = new DeserializingMessage(event, MessageType.EVENT, serializer);
            return evaluateExplicit(
                    message, Evaluation.STORED,
                    null, -1, false).thenApply(ignored -> null);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
        if (!localHandlingEnabled) {
            return Optional.empty();
        }
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
        return localHandlingEnabled && canAutomaticallyHandle(message);
    }

    private boolean canAutomaticallyHandle(DeserializingMessage message) {
        return message.getMessageType() == MessageType.COMMAND
               && planFor(message.getPayloadClass()).automatic();
    }

    private boolean hasModelApplies(
            Class<?> payloadType) {
        return planFor(payloadType).commit();
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

    ModelCommitPolicy commitPolicyFor(Class<?> payloadType) {
        return planFor(payloadType).commitPolicy();
    }

    @Override
    public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (!ModelMetadata.of(targetType).isModel()) {
            return Registration.noOp();
        }
        registeredModelTypes.addIfAbsent(targetType);
        knownModelTypes.addIfAbsent(targetType);
        ModelGraphProjections.roots(targetType).forEach(this::registerGraphProjection);
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
                        != ModelMetadata.HandlerKind.ASSERT_LEGAL)
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
     * Creates one tracked command handler for a registered model receiver or a payload type whose interceptor chain
     * reaches a model apply. The handler remains scoped to that registration so ordinary command handlers retain
     * precedence.
     */
    @Override
    public Optional<Handler<DeserializingMessage>> createHandler(
            Object target,
            HandlerFilter handlerFilter,
            List<HandlerInterceptor> extraInterceptors) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (ModelMetadata.of(targetType).isModel()) {
            return Optional.empty();
        }
        ModelExecutionPlan executionPlan = planFor(targetType);
        boolean payloadCommit = executionPlan.automatic()
                                && executionPlan.handlers().methods().stream()
                                        .anyMatch(handler ->
                                                handlerFilter.test(
                                                        handler.executable()
                                                                .getDeclaringClass(),
                                                        handler.executable()));
        if (!payloadCommit) {
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
        return localHandlingEnabled;
    }

    @Override
    public boolean canSkipLocalHandling(
            MessageType messageType,
            Class<?> payloadType) {
        return !localHandlingEnabled;
    }

    @Override
    public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
        /*
         * Automatic model handlers are normally tracked asynchronously, like @TrackSelf handlers. The synchronous
         * TestFixture deliberately forces every handler onto its local path with the shared ALWAYS_HANDLE marker.
         */
        localHandlingEnabled = selfHandlerFilter == HandlerFilter.ALWAYS_HANDLE;
    }

    private CompletableFuture<Object> execute(DeserializingMessage message) {
        return execute(new ExecutionRequest(
                message, new ModelBatchScope.Operation(),
                Evaluation.AUTOMATIC, null, -1, false));
    }

    private CompletableFuture<Object> execute(
            DeserializingMessage message,
            ModelBatchScope.Operation operation) {
        return execute(new ExecutionRequest(
                message, operation, Evaluation.AUTOMATIC,
                null, -1, false));
    }

    private CompletableFuture<Object> evaluateExplicit(
            DeserializingMessage message,
            Evaluation evaluation,
            ModelCommitBatchingClient.ModelCommitBatch transport,
            int slot,
            boolean skipEmpty) {
        return execute(new ExecutionRequest(
                message, new ModelBatchScope.Operation(), evaluation,
                transport, slot, skipEmpty));
    }

    private CompletableFuture<Object> execute(ExecutionRequest request) {
        ThreadLocalContext.Snapshot context = request.message().captureContext();
        CompletableFuture<Object> result = evaluate(request, context);
        request.operation().bind(result);
        return request.operation();
    }

    private CompletableFuture<Object> evaluate(
            ExecutionRequest request,
            ThreadLocalContext.Snapshot context) {
        ModelBatchScope.Operation operation = request.operation();
        ModelExecutionPlan.CommitEvaluation initial;
        try {
            initial = context.supply(() -> ModelBatchScope.withDependency(
                    operation, () -> evaluate(request, false)));
            warnEmptyExplicitApply(request, initial);
            ModelBatchScope.stage(
                    currentConsumerNamespace(request.message()),
                    initial, operation);
            operation.initialize(initial.readModelIds());
        } catch (Throwable failure) {
            operation.fail(failure);
            return CompletableFuture.failedFuture(failure);
        }
        try {
            return submit(request, initial, context);
        } catch (Throwable failure) {
            operation.fail(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Object> submit(
            ExecutionRequest request,
            ModelExecutionPlan.CommitEvaluation initial,
            ThreadLocalContext.Snapshot context) {
        ModelBatchScope.Operation operation = request.operation();
        if (operation.hasBatchDependencies()) {
            if (operation.batched()
                && !operation.policy().commitAfterBatch()
                && operation.transportBatch() != null) {
                operation.flushTransport();
            }
            operation.detachTransport();
        }
        return operation.executeAfterRelease(dependent -> {
            CompletableFuture<ModelExecutionPlan.CommitEvaluation> ready = dependent
                    ? reevaluate(request, context)
                    : CompletableFuture.completedFuture(initial);
            return ready.thenCompose(context.wrap(evaluation ->
                    request.skipEmpty() && evaluation.transitions().isEmpty()
                            ? CompletableFuture.completedFuture(null)
                            : executeEvaluation(
                                    request.message(), evaluation,
                                    !operation.batched()
                                            ? request.transport()
                                            : operation.transportBatch(),
                                    !operation.batched()
                                            ? request.transportSlot()
                                            : operation.transportSlot())));
        });
    }

    private CompletableFuture<ModelExecutionPlan.CommitEvaluation> reevaluate(
            ExecutionRequest request,
            ThreadLocalContext.Snapshot context) {
        ModelBatchScope.Operation operation = request.operation();
        int dependencyCount = operation.dependencyCount();
        Function<Void, ModelExecutionPlan.CommitEvaluation> evaluation = ignored ->
                context.supply(() -> ModelBatchScope.withDependency(
                        operation, () -> evaluate(request, true)));
        CompletableFuture<ModelExecutionPlan.CommitEvaluation> result =
                operation.batched() && !localHandlingEnabled
                        ? operation.dependencyCompletion().thenCompose(ignored ->
                                CompletableFuture.supplyAsync(
                                        context.wrap(() -> evaluation.apply(null))))
                        : operation.dependencyCompletion().thenApply(evaluation);
        return result.thenCompose(value ->
                operation.dependencyCount() == dependencyCount
                        ? CompletableFuture.completedFuture(value)
                        : reevaluate(request, context));
    }

    private ModelExecutionPlan.CommitEvaluation evaluate(
            ExecutionRequest request, boolean retry) {
        return switch (request.evaluation()) {
            case ASSERT -> compiler.assertLegal(
                    request.message(), new CommitLoader(null));
            case STORED -> compiler.rebase(
                    List.of(request.message()), new CommitLoader(null, true));
            case APPLY -> DeserializingMessage.getMessageBatchIndex() < 0
                    ? evaluate(request.message()) : evaluate(request.message(), null);
            case AUTOMATIC -> retry && request.operation().batched()
                    || !retry && !request.operation().hasBatchDependencies()
                    ? evaluate(request.message()) : evaluate(request.message(), null);
        };
    }

    private void warnEmptyExplicitApply(
            ExecutionRequest request,
            ModelExecutionPlan.CommitEvaluation evaluation) {
        if (request.evaluation() == Evaluation.APPLY
            && !hasModelApplies(request.message().getPayloadClass())
            && evaluation.transitions().isEmpty()
            && !evaluation.substeps().isEmpty()) {
            log.warn(
                    "Fluxzero.assertAndApply({}) ran model interceptors and assertions, but this application has no "
                    + "locally reachable model @Apply handler. No model changes were committed.",
                    request.message().getPayloadClass().getName());
        }
    }

    private static String currentConsumerNamespace(
            DeserializingMessage message) {
        DeserializingMessage current = DeserializingMessage.getCurrent();
        return io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace(
                current == null ? message : current);
    }

    private record ExecutionRequest(
            DeserializingMessage message,
            ModelBatchScope.Operation operation,
            Evaluation evaluation,
            ModelCommitBatchingClient.ModelCommitBatch transport,
            int transportSlot,
            boolean skipEmpty) {
    }

    private enum Evaluation { AUTOMATIC, APPLY, ASSERT, STORED }

    record Retry(
            ModelConflictResolver resolver,
            int maxAttempts,
            RetryEvaluator evaluator) {
        static Retry accepting(RetryEvaluator evaluator) {
            return new Retry(
                    null, 10,
                    Objects.requireNonNull(evaluator));
        }

        static Retry conflicts(
                ModelConflictResolver resolver,
                int maxAttempts,
                RetryEvaluator evaluator) {
            if (maxAttempts < 0) {
                throw new IllegalArgumentException(
                        "Maximum model conflict retries must not be negative");
            }
            return new Retry(
                    Objects.requireNonNull(resolver),
                    maxAttempts,
                    Objects.requireNonNull(evaluator));
        }

        boolean accepting() {
            return resolver == null;
        }
    }

    @FunctionalInterface
    interface RetryEvaluator {
        CompletableFuture<ModelExecutionPlan.CommitEvaluation> reevaluate(
                CommitModelsResult result,
                ModelExecutionPlan.CommitEvaluation current,
                ModelCommitProtocol.PreparedCommit original);
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            ModelExecutionPlan.CommitEvaluation evaluation,
            ModelCommitBatchingClient.ModelCommitBatch transportBatch,
            int transportSlot) {
        ModelConflictPolicy effectiveConflictPolicy =
                evaluation.conflictPolicy(conflictPolicy);
        Map<String, Set<String>> awaitedGraphProjections =
                awaitedGraphProjectionTargets(
                        evaluation);
        CompletableFuture<Void> registrations =
                ensureGraphProjections(evaluation);
        if (registrations == COMPLETED_VOID) {
            return executeEvaluation(
                    message, evaluation,
                    effectiveConflictPolicy,
                    awaitedGraphProjections,
                    transportBatch,
                    transportSlot);
        }
        ThreadLocalContext.Snapshot context =
                message.captureContext();
        return registrations.thenCompose(
                context.wrap(ignored ->
                        executeEvaluation(
                                message, evaluation,
                                effectiveConflictPolicy,
                                awaitedGraphProjections,
                                transportBatch,
                                transportSlot)));
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            ModelExecutionPlan.CommitEvaluation evaluation,
            ModelConflictPolicy effectiveConflictPolicy,
            Map<String, Set<String>> awaitedGraphProjections,
            ModelCommitBatchingClient.ModelCommitBatch transportBatch,
            int transportSlot) {
                    Runnable localCommitComplete =
                            repository.beginLocalCommit(
                                    writtenModelIds(evaluation));
                    try {
                        Retry retry = effectiveConflictPolicy
                                == ModelConflictPolicy.ACCEPT
                                ? Retry.accepting(
                                        (result, current, original) -> {
                                            try {
                                                return CompletableFuture.completedFuture(
                                                        rebase(
                                                                original.rebaseMessages(),
                                                                result.getRebaseStateIndex()));
                                            } catch (Throwable failure) {
                                                return CompletableFuture.failedFuture(failure);
                                            }
                                        })
                                : Retry.conflicts(
                                        conflictResolver,
                                        maxConflictRetries,
                                        (conflict, current, original) ->
                                                reload(message, current, conflict));
                        CompletableFuture<Optional<CommitModelsResult>> result =
                                commit(
                                        protocol, message.getMessageId(), evaluation,
                                        effectiveConflictPolicy, retry,
                                        transportBatch, transportSlot);
                        CompletableFuture<Optional<CommitModelsResult>> committed =
                                result.whenComplete(
                                        (commitResult, failure) -> localCommitComplete.run());
                        CompletableFuture<Optional<CommitModelsResult>> completed =
                                awaitedGraphProjections.isEmpty()
                                        ? committed
                                        : committed.thenCompose(commitResult ->
                                                awaitGraphProjections(
                                                        commitResult,
                                                        awaitedGraphProjections));
                        return completed.handle((commitResult, failure) ->
                                    finishEvaluation(
                                        evaluation,
                                        effectiveConflictPolicy,
                                        failure));
                    } catch (Throwable failure) {
                        localCommitComplete.run();
                        throw failure;
                    }
    }

    static CompletableFuture<Optional<CommitModelsResult>> commit(
            ModelCommitProtocol protocol,
            String commitId,
            ModelExecutionPlan.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            Retry retry,
            ModelCommitBatchingClient.ModelCommitBatch batch,
            int batchSlot) {
        Objects.requireNonNull(retry, "retry");
        ModelCommitProtocol.PreparedCommit original = protocol.prepare(
                commitId, evaluation, conflictPolicy);
        return commit(
                protocol, commitId, evaluation, conflictPolicy,
                original, original, retry,
                ThreadLocalContext.capture(), 0, batch, batchSlot);
    }

    private static CompletableFuture<Optional<CommitModelsResult>> commit(
            ModelCommitProtocol protocol,
            String commitId,
            ModelExecutionPlan.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelCommitProtocol.PreparedCommit original,
            ModelCommitProtocol.PreparedCommit prepared,
            Retry retry,
            ThreadLocalContext.Snapshot context,
            int attempts,
            ModelCommitBatchingClient.ModelCommitBatch batch,
            int batchSlot) {
        return protocol.commitPrepared(prepared, batch, batchSlot)
                .thenCompose(optional -> {
                    if (optional.isEmpty()) {
                        return CompletableFuture.completedFuture(optional);
                    }
                    CommitModelsResult result = optional.get();
                    if (retry.accepting()) {
                        if (!result.isRebaseRequired()) {
                            return CompletableFuture.completedFuture(optional);
                        }
                        if (attempts >= retry.maxAttempts()) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Model commit '%s' remained stale after %d apply-only rebases"
                                            .formatted(commitId, retry.maxAttempts())));
                        }
                    } else if (result.isAccepted()) {
                        return CompletableFuture.completedFuture(optional);
                    }
                    return retryDecision(result, attempts, retry, context)
                            .thenCompose(ignored -> invokeAsync(
                                    context,
                                    () -> retry.evaluator().reevaluate(
                                            result, evaluation, original),
                                    "Model commit reevaluation returned null"))
                            .thenCompose(next -> {
                                if (retry.accepting()
                                    && next.readStateIndex() != result.getRebaseStateIndex()) {
                                    return CompletableFuture.failedFuture(new IllegalStateException(
                                            "Model commit '%s' rebase loaded state index %d instead of requested %d"
                                                    .formatted(
                                                            commitId, next.readStateIndex(),
                                                            result.getRebaseStateIndex())));
                                }
                                ModelCommitProtocol.PreparedCommit nextPrepared =
                                        retry.accepting()
                                        && !original.hasCascadedDeletion()
                                                ? protocol.prepareRebased(commitId, original, next)
                                                : protocol.prepare(commitId, next, conflictPolicy);
                                return commit(
                                        protocol, commitId, next, conflictPolicy,
                                        original, nextPrepared, retry,
                                        context, attempts + 1, null, -1);
                            });
                });
    }

    private static CompletableFuture<Void> retryDecision(
            CommitModelsResult result,
            int attempts,
            Retry retry,
            ThreadLocalContext.Snapshot context) {
        if (retry.accepting()) {
            return COMPLETED_VOID;
        }
        return CompletableFuture.supplyAsync(context.wrap(() ->
                        Objects.requireNonNull(
                                retry.resolver().resolve(
                                        new ModelConflictResolver.Context(
                                                result, attempts, retry.maxAttempts())),
                                "Model conflict resolver returned null")))
                .thenCompose(resolution ->
                        resolution == ModelConflictResolver.Resolution.RETRY
                        && result.isRetryAllowed()
                        && attempts < retry.maxAttempts()
                                ? COMPLETED_VOID
                                : CompletableFuture.failedFuture(
                                        new ModelCommitConflictException(result)));
    }

    private static <T> CompletableFuture<T> invokeAsync(
            ThreadLocalContext.Snapshot context,
            Supplier<CompletableFuture<T>> operation,
            String nullMessage) {
        return CompletableFuture.supplyAsync(context.wrap(() ->
                        Objects.requireNonNull(operation.get(), nullMessage)))
                .thenCompose(Function.identity());
    }

    private Object finishEvaluation(
            ModelExecutionPlan.CommitEvaluation evaluation,
            ModelConflictPolicy effectiveConflictPolicy,
            Throwable failure) {
        if (failure == null) {
            return null;
        }
        if (effectiveConflictPolicy
            != ModelConflictPolicy.ACCEPT) {
            repository.invalidateModels(
                    evaluation.readModelIds());
        }
        if (failure
            instanceof java.util.concurrent.CompletionException completion
            && completion.getCause() != null) {
            throw completion;
        }
        throw new java.util.concurrent.CompletionException(
                failure);
    }

    private static List<String> writtenModelIds(
            ModelExecutionPlan.CommitEvaluation evaluation) {
        List<ModelExecutionPlan.Transition> transitions =
                evaluation.transitions();
        if (transitions.size() == 1) {
            return List.of(
                    transitions.getFirst().modelId());
        }
        return transitions.stream()
                .map(ModelExecutionPlan.Transition::modelId)
                .distinct()
                .toList();
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
            ModelExecutionPlan.CommitEvaluation evaluation) {
        return awaitedGraphProjectionTargets(
                evaluation).keySet();
    }

    Map<String, Set<String>> awaitedGraphProjectionTargets(
            ModelExecutionPlan.CommitEvaluation evaluation) {
        GraphProjectionCompletion consumer = null;
        LinkedHashMap<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        for (ModelExecutionPlan.Transition transition :
                evaluation.transitions()) {
            List<ModelGraphProjections.Root> roots =
                    ModelGraphProjections.roots(transition.modelType());
            if (roots.isEmpty()) {
                continue;
            }
            if (consumer == null) {
                consumer = Tracker.current()
                        .map(Tracker::getConfiguration)
                        .map(configuration -> configuration.getGraphProjectionCompletion())
                        .orElse(GraphProjectionCompletion.DEFAULT);
            }
            Apply apply = transition.handler() == null
                    ? null
                    : transition.handler().getAnnotation(Apply.class);
            GraphProjectionCompletion applyPolicy =
                    apply == null
                            ? GraphProjectionCompletion.DEFAULT
                            : apply.graphProjectionCompletion();
            for (ModelGraphProjections.Root root : roots) {
                if (resolveProjectionCompletion(
                        applyPolicy, consumer,
                        root.projection().completion()) == GraphProjectionCompletion.AWAIT) {
                    result.computeIfAbsent(
                                    root.collection(), ignored -> new LinkedHashSet<>())
                            .add(transition.modelId());
                }
            }
        }
        if (result.isEmpty()) {
            return Map.of();
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

    private CompletableFuture<Void> ensureGraphProjections(
            ModelExecutionPlan.CommitEvaluation evaluation) {
        LinkedHashSet<ModelGraphProjections.Root> roots = null;
        for (ModelExecutionPlan.Transition transition :
                evaluation.transitions()) {
            List<ModelGraphProjections.Root> candidates =
                    ModelGraphProjections.roots(transition.modelType());
            if (!candidates.isEmpty()) {
                if (roots == null) {
                    roots = new LinkedHashSet<>();
                }
                roots.addAll(candidates);
            }
        }
        if (roots == null) {
            return COMPLETED_VOID;
        }
        roots.forEach(this::registerGraphProjection);
        return CompletableFuture.allOf(
                roots.stream()
                        .map(ModelGraphProjections.Root::modelType)
                        .distinct()
                        .map(graphProjectionRegistrations::get)
                        .filter(Objects::nonNull)
                        .toArray(CompletableFuture[]::new));
    }

    private void registerGraphProjection(
            ModelGraphProjections.Root root) {
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

    private CompletableFuture<ModelExecutionPlan.CommitEvaluation> reload(
            DeserializingMessage message,
            ModelExecutionPlan.CommitEvaluation staleEvaluation,
            CommitModelsResult conflict) {
        repository.invalidateModels(
                staleEvaluation.readModelIds());
        long retryStateIndex =
                retryStateIndex(
                        staleEvaluation,
                        conflict);
        try {
            return CompletableFuture.completedFuture(
                    ModelBatchScope.withMessageDependency(
                            message,
                            () -> expandCascadeDeletes(
                                    compiler.evaluate(
                                            message,
                                            new CommitLoader(
                                                    retryStateIndex)))));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static long retryStateIndex(
            ModelExecutionPlan.CommitEvaluation evaluation,
            CommitModelsResult conflict) {
        long result = evaluation.readStateIndex();
        for (var current : conflict.getConflicts()) {
            result = Math.max(
                    result,
                    Math.max(
                            current.getCurrentStateIndex(),
                            current.getCurrentRelationStateIndex()));
        }
        return result;
    }

    private CompletableFuture<Void> afterCommitBatch(
            List<ModelCommitProtocol.CommittedCommit> committed) {
        List<DefaultModelRepository.CommittedModel> committedModels =
                new ArrayList<>(committed.size());
        for (ModelCommitProtocol.CommittedCommit item : committed) {
            createCommittedModels(item, committedModels);
        }
        repository.updateAfterCommit(committedModels);
        return CompletableFuture.completedFuture(null);
    }

    private void createCommittedModels(
            ModelCommitProtocol.CommittedCommit committed,
            List<DefaultModelRepository.CommittedModel> target) {
        if (committed.prepared().substeps().size()
            != committed.result().getSubsteps().size()) {
            throw new IllegalStateException(
                    "Model commit returned a different number of substeps than requested");
        }
        if (committed.prepared().substeps().size() == 1
            && committed.prepared().substeps().getFirst().transitions().size() == 1) {
            ModelExecutionPlan.Transition transition =
                    committed.prepared().substeps().getFirst()
                            .transitions().getFirst();
            if (!committed.result().hasSingleTargetResult()) {
                throw new IllegalStateException(
                        "Model commit returned a different number of targets than requested");
            }
            if (!transition.effect().updateState()) {
                return;
            }
            var commitStep = committed.prepared().commit()
                    .getSubsteps().getFirst();
            Instant timestamp = commitStep.getEvent() == null
                    ? Instant.now()
                    : Instant.ofEpochMilli(
                            commitStep.getEvent().getTimestamp());
            target.add(
                    new DefaultModelRepository.CommittedModel(
                            transition.modelId(),
                            transition.modelType(),
                            transition.effect().model(),
                            transition.effect().metadata().entityId().orElseThrow(),
                            committed.result().isSingleTargetHistoryComplete(),
                            new DefaultModelRepository.CommittedRevision(
                                    transition.after(),
                                    committed.result().getSingleTargetSequenceNumber(),
                                    committed.result().getSingleTargetStateIndex(),
                                    committed.prepared().singleEventMessageId(),
                                    committed.result().getSingleTargetEventIndex(),
                                    timestamp)));
            return;
        }
        LinkedHashMap<String, DefaultModelRepository.CommittedModel> finalStates =
                new LinkedHashMap<>();
        for (int substep = 0;
             substep < committed.prepared().substeps().size();
             substep++) {
            List<ModelExecutionPlan.Transition> transitions =
                    committed.prepared().substeps().get(substep).transitions();
            var substepResult = committed.result().getSubsteps().get(substep);
            var commitStep = committed.prepared().commit().getSubsteps().get(substep);
            if (commitStep.getTargets().size()
                != substepResult.getTargets().size()) {
                throw new IllegalStateException(
                        "Model commit returned a different number of targets than requested");
            }
            if (transitions.isEmpty()) {
                continue;
            }
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
                ModelExecutionPlan.Transition transition = transitions.get(targetIndex);
                if (!transition.effect().updateState()) {
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
                                transition.effect().model(),
                                transition.effect().metadata().entityId().orElseThrow(),
                                targetResult.isHistoryComplete(),
                                revisions));
            }
        }
        target.addAll(finalStates.values());
    }

    private ModelExecutionPlan.CommitEvaluation evaluate(DeserializingMessage initialMessage) {
        PrefetchSlot cached = initialMessage.getContext(ExplicitModelTarget.class).isPresent()
                ? null : prefetch(initialMessage);
        if (cached != null) {
            if (repository.supplyCurrentModel(
                    cached.modelId(), cached.modelType(), cached)) {
                return evaluate(initialMessage, cached);
            }
        }
        return evaluate(initialMessage, null);
    }

    private ModelExecutionPlan.CommitEvaluation evaluate(
            DeserializingMessage initialMessage,
            PrefetchSlot prefetched) {
        if (prefetched != null
            && prefetched.entity != null
            && prefetched.directApply != null
            && prefetched.access.writes()) {
            ModelExecutionPlan plan = planFor(
                    initialMessage.getPayloadClass());
            ModelExecutionPlan.CommitEvaluation direct =
                    compiler.evaluateDirectSingleTarget(
                            initialMessage,
                            prefetched.stateIndex,
                            prefetched.modelId,
                            prefetched.modelType,
                            prefetched.entity,
                            plan.handlers(),
                            prefetched.directApply);
            if (direct != null) {
                return expandCascadeDeletes(direct);
            }
        }
        return expandCascadeDeletes(
                compiler.evaluate(initialMessage, new CommitLoader(
                        null, false, initialMessage, prefetched)));
    }

    private ModelExecutionPlan.CommitEvaluation rebase(
            List<DeserializingMessage> messages,
            long stateIndex) {
        return ModelBatchScope.withMessageDependency(
                messages.getFirst(),
                () -> expandCascadeDeletes(compiler.rebase(
                        messages.stream()
                                .filter(message -> !(message.getPayload()
                                        instanceof CascadedModelDeletion))
                                .toList(),
                        new CommitLoader(stateIndex, true))));
    }

    /**
     * Adds one internal, non-published delete substep for descendants owned through {@link ParentId} relationships.
     * The ordinary evaluation path only pays the single final-value scan below; graph reconstruction is exclusive to
     * actual logical deletions.
     */
    private ModelExecutionPlan.CommitEvaluation expandCascadeDeletes(
            ModelExecutionPlan.CommitEvaluation evaluation) {
        LinkedHashSet<String> explicitlyDeleted = null;
        LinkedHashMap<String, ModelExecutionPlan.Transition> latestTransitions =
                new LinkedHashMap<>();
        for (ModelExecutionPlan.AppliedSubstep substep : evaluation.substeps()) {
            for (ModelExecutionPlan.Transition transition : substep.transitions()) {
                latestTransitions.put(transition.modelId(), transition);
                if (transition.before() != null
                    && transition.after() == null
                    && evaluation.finalValues().get(transition.modelId()) == null) {
                    if (explicitlyDeleted == null) {
                        explicitlyDeleted = new LinkedHashSet<>();
                    }
                    explicitlyDeleted.add(transition.modelId());
                }
            }
        }
        if (explicitlyDeleted == null) {
            return evaluation;
        }
        LinkedHashMap<String, CascadeNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<String> deleted = new LinkedHashSet<>(explicitlyDeleted);
        LinkedHashSet<String> cascaded = new LinkedHashSet<>();

        for (String rootId : explicitlyDeleted) {
            Class<?> rootType = modelType(
                    rootId, evaluation, latestTransitions);
            if (rootType == null) {
                continue;
            }
            Graph<?> graph = repository.loadGraphAtIncludingMessageBatch(
                    rootId, rootType,
                    evaluation.readStateIndex(),
                    Graph.Options.DEFAULT);
            addCascadeNode(nodes, graph);
            graph.descendants(Object.class).forEach(
                    descendant -> addCascadeNode(nodes, descendant));
        }

        overlayFinalValues(
                evaluation, latestTransitions, nodes);
        boolean changed;
        do {
            changed = false;
            for (CascadeNode node : nodes.values()) {
                if (deleted.contains(node.modelId())
                    || node.value() == null) {
                    continue;
                }
                ModelMetadata metadata =
                        ModelMetadata.validate(node.modelType());
                boolean ownedByDeletedParent =
                        ownedByDeletedParent(
                                metadata, node.value(), deleted);
                if (ownedByDeletedParent && deleted.add(node.modelId())) {
                    cascaded.add(node.modelId());
                    changed = true;
                }
            }
        } while (changed);
        if (cascaded.isEmpty()) {
            return new ModelExecutionPlan.CommitEvaluation(
                    evaluation.readStateIndex(),
                    evaluation.readModelIds(),
                    evaluation.readModelTypes(),
                    evaluation.substeps(),
                    evaluation.finalValues(),
                    explicitlyDeleted);
        }

        List<ModelExecutionPlan.Transition> transitions = cascaded.stream()
                .map(nodes::get)
                .filter(Objects::nonNull)
                .map(node -> new ModelExecutionPlan.Transition(
                        node.modelId(), node.modelType(),
                        node.sequenceNumber(), node.lastEventIndex(),
                        node.value(), null, null, null, true))
                .toList();
        DeserializingMessage source =
                evaluation.substeps().getFirst().message();
        DeserializingMessage cascadeMessage = source.withMessage(
                new Message(
                        new CascadedModelDeletion(
                                List.copyOf(explicitlyDeleted)),
                        source.getMetadata(), null,
                        source.getTimestamp()));
        List<ModelExecutionPlan.AppliedSubstep> substeps =
                new ArrayList<>(evaluation.substeps());
        substeps.add(new ModelExecutionPlan.AppliedSubstep(
                cascadeMessage, transitions));
        LinkedHashSet<String> readModelIds =
                new LinkedHashSet<>(evaluation.readModelIds());
        Map<String, Class<?>> readModelTypes =
                new LinkedHashMap<>(evaluation.readModelTypes());
        Map<String, Object> finalValues =
                new LinkedHashMap<>(evaluation.finalValues());
        transitions.forEach(transition -> {
            readModelIds.add(transition.modelId());
            readModelTypes.putIfAbsent(
                    transition.modelId(), transition.modelType());
            finalValues.put(transition.modelId(), null);
        });
        return new ModelExecutionPlan.CommitEvaluation(
                evaluation.readStateIndex(),
                List.copyOf(readModelIds),
                readModelTypes,
                substeps,
                finalValues,
                explicitlyDeleted);
    }

    private static void addCascadeNode(
            Map<String, CascadeNode> nodes,
            Graph<?> graph) {
        nodes.putIfAbsent(
                graph.id().toString(),
                new CascadeNode(
                        graph.id().toString(), graph.type(), graph.get(),
                        graph.sequenceNumber(), graph.lastEventIndex()));
    }

    private static boolean ownedByDeletedParent(
            ModelMetadata metadata,
            Object value,
            Set<String> deleted) {
        for (ModelMetadata.ParentReference parent :
                metadata.parentReferences()) {
            if (!parent.deleteOnParentDeletion()) {
                continue;
            }
            Object parentId = parent.read(value);
            if (parentId != null
                && deleted.contains(
                        parent.repositoryId(parentId))) {
                return true;
            }
        }
        return false;
    }

    private static void overlayFinalValues(
            ModelExecutionPlan.CommitEvaluation evaluation,
            Map<String, ModelExecutionPlan.Transition> latestTransitions,
            Map<String, CascadeNode> nodes) {
        evaluation.finalValues().forEach((modelId, value) -> {
            if (value == null) {
                return;
            }
            ModelExecutionPlan.Transition transition =
                    latestTransitions.get(modelId);
            CascadeNode known = nodes.get(modelId);
            Class<?> type = transition == null
                    ? evaluation.readModelTypes().get(modelId)
                    : transition.modelType();
            if (type == null) {
                type = value.getClass();
            }
            nodes.put(
                    modelId,
                    new CascadeNode(
                            modelId, type, value,
                            known != null ? known.sequenceNumber()
                                    : transition == null ? -1L
                                    : transition.beforeSequenceNumber(),
                            known != null ? known.lastEventIndex()
                                    : transition == null ? null
                                    : transition.beforeLastEventIndex()));
        });
    }

    private static Class<?> modelType(
            String modelId,
            ModelExecutionPlan.CommitEvaluation evaluation,
            Map<String, ModelExecutionPlan.Transition> transitions) {
        ModelExecutionPlan.Transition transition =
                transitions.get(modelId);
        return transition == null
                ? evaluation.readModelTypes().get(modelId)
                : transition.modelType();
    }

    private record CascadeNode(
            String modelId,
            Class<?> modelType,
            Object value,
            long sequenceNumber,
            Long lastEventIndex) {
    }

    record ExplicitModelTarget(String modelId, Class<?> modelType) {
    }

    private final class CommitLoader implements ModelExecutionPlan.SubstepResolver {
        private final Long pinnedStateIndex;
        private final boolean applyOnly;
        private final DeserializingMessage directMessage;
        private final PrefetchSlot prefetched;
        private final Map<String, Entity<?>> commitEntities = new LinkedHashMap<>();
        private final Map<AncestorPlanKey, List<ModelTargetResolver.ResolvedModel>> ancestorPlans =
                new LinkedHashMap<>();

        private CommitLoader(Long pinnedStateIndex) {
            this(pinnedStateIndex, false, null, null);
        }

        private CommitLoader(Long pinnedStateIndex, boolean applyOnly) {
            this(pinnedStateIndex, applyOnly, null, null);
        }

        private CommitLoader(
                Long pinnedStateIndex,
                boolean applyOnly,
                DeserializingMessage directMessage,
                PrefetchSlot prefetched) {
            this.pinnedStateIndex = pinnedStateIndex;
            this.applyOnly = applyOnly;
            this.directMessage = directMessage;
            this.prefetched = prefetched;
        }

        @Override
        public ModelExecutionPlan.ResolvedSubstep resolve(
                DeserializingMessage substep,
                Long requestedStateIndex,
                Map<String, Object> stagedValues) {
            Long boundary = requestedStateIndex == null ? pinnedStateIndex : requestedStateIndex;
            if (pinnedStateIndex != null && !pinnedStateIndex.equals(boundary)) {
                throw new IllegalStateException(
                        "Pinned model evaluation moved from state index %d to %d"
                                .formatted(pinnedStateIndex, boundary));
            }
            ModelExecutionPlan plan = planFor(substep.getPayloadClass());
            ModelExecutionPlan.HandlerPlan handlers = plan.handlers();
            if (substep == directMessage
                && prefetched != null
                && prefetched.entity != null
                && requestedStateIndex == null
                && stagedValues.isEmpty()) {
                commitEntities.put(prefetched.modelId(), prefetched.entity);
                return new ModelExecutionPlan.ResolvedSubstep(
                        ModelCommitContext.createSingle(
                                prefetched.stateIndex,
                                prefetched.modelId(),
                                prefetched.modelType(),
                                prefetched.access,
                                prefetched.sourceProperties,
                                prefetched.entity),
                        handlers,
                        prefetched.access.writes()
                                ? prefetched.directApply : null);
            }
            ExplicitModelTarget explicitTarget = substep.getContext(
                    ExplicitModelTarget.class).orElse(null);
            ModelTargetResolver.Resolution resolution =
                    plan.targets().resolve(
                            substep.getPayload(),
                            explicitTarget == null ? null : explicitTarget.modelId(),
                            explicitTarget == null ? null : explicitTarget.modelType(),
                            applyOnly);
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
            } else if (pinnedStateIndex == null && requestedStateIndex == null
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
            effectiveTargets.forEach(target -> selected.put(
                    target.modelId(), Objects.requireNonNull(
                            commitEntities.get(target.modelId()),
                            "Missing commit-scoped model " + target.modelId())));
            return new ModelExecutionPlan.ResolvedSubstep(
                    ModelCommitContext.create(
                            stateIndex, effectiveResolution, selected), handlers);
        }

        @Override
        public void prefetch(
                List<DeserializingMessage> messages,
                long readStateIndex,
                Map<String, Object> stagedValues) {
            LinkedHashMap<String, ModelTargetResolver.ResolvedModel> targets =
                    new LinkedHashMap<>();
            for (DeserializingMessage message : messages) {
                if (message.getContext(ExplicitModelTarget.class).isPresent()) {
                    continue;
                }
                ModelTargetResolver.Resolution resolution = planFor(
                                message.getPayloadClass())
                        .targets().resolve(message.getPayload(), null, applyOnly);
                if (resolution.hasAncestorDependencies()) {
                    continue;
                }
                resolution.models().stream()
                        .filter(target -> !commitEntities.containsKey(target.modelId()))
                        .forEach(target -> ModelTargetResolver.merge(targets, target));
            }
            if (!targets.isEmpty()) {
                load(new ModelTargetResolver.Resolution(
                                List.copyOf(targets.values()), List.of()),
                     readStateIndex, stagedValues);
            }
        }

        @Override
        public ModelExecutionPlan.ResolvedSubstep resolveGraph(
                String modelId,
                Class<?> modelType,
                Long requestedStateIndex,
                Map<String, Object> stagedValues) {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelType, "modelType");
            ModelTargetResolver.Resolution resolution =
                    new ModelTargetResolver.Resolution(
                            List.of(new ModelTargetResolver.ResolvedModel(
                                    modelId, modelType,
                                    ModelTargetResolver.Access.READ_WRITE,
                                    List.of())),
                            List.of());
            ModelCommitContext loaded = load(
                    resolution,
                    requestedStateIndex == null
                            ? pinnedStateIndex : requestedStateIndex,
                    stagedValues);
            return new ModelExecutionPlan.ResolvedSubstep(
                    loaded, ModelExecutionPlan.HandlerPlan.EMPTY);
        }

        private ModelCommitContext load(
                ModelTargetResolver.Resolution resolution,
                Long boundary,
                Map<String, Object> stagedValues) {
            DeserializingMessage current =
                    DeserializingMessage.getCurrent();
            String namespace = current == null
                    ? null
                    : io.fluxzero.sdk.common.ClientUtils
                            .getConsumerNamespace(current);
            Map<String, Object> batchValues =
                    ModelBatchScope.currentValues(
                            namespace, resolution);
            Map<String, Object> effectiveStagedValues;
            if (batchValues.isEmpty()) {
                effectiveStagedValues = stagedValues;
            } else if (stagedValues.isEmpty()) {
                effectiveStagedValues = batchValues;
            } else {
                LinkedHashMap<String, Object> combined =
                        new LinkedHashMap<>(batchValues);
                combined.putAll(stagedValues);
                effectiveStagedValues = combined;
            }
            ModelCommitContext loaded = repository.loadContext(
                    resolution, boundary, effectiveStagedValues, false);
            Map<String, Object> loadedBatchValues =
                    ModelBatchScope.currentValues(
                            namespace, loaded);
            if (!loadedBatchValues.isEmpty()) {
                loaded = loaded.withValues(loadedBatchValues);
            }
            if (boundary != null && loaded.readStateIndex() != boundary) {
                throw new IllegalStateException(
                        "Model commit requested state index %d but loaded %d"
                                .formatted(boundary, loaded.readStateIndex()));
            }
            loaded.entries().forEach(entry -> commitEntities.put(
                    entry.target().modelId(), entry.entity()));
            return loaded;
        }

        private static List<ModelTargetResolver.ResolvedModel> targets(
                ModelCommitContext context) {
            return context.entries().stream()
                    .map(ModelCommitContext.Entry::target).toList();
        }
    }

    private ModelExecutionPlan planFor(Class<?> payloadType) {
        CachedExecutionPlan recent = recentCommitPlan;
        if (recent != null && recent.payloadType() == payloadType) {
            return recent.plan();
        }
        ModelExecutionPlan plan = commitPlans.computeIfAbsent(payloadType, type -> {
            List<ModelMetadata.HandlerMethod> handlers = inspectHandlers(type);
            List<ModelMetadata.HandlerMethod> applies = handlers.stream()
                    .filter(handler -> handler.kind() == ModelMetadata.HandlerKind.APPLY)
                    .toList();
            applies.stream().flatMap(handler -> handler.targetModelTypes().stream())
                    .forEach(knownModelTypes::addIfAbsent);
            ModelExecutionPlan.DirectSingleTargetApply directApply =
                    handlers.size() == 1 && applies.size() == 1
                            ? ModelExecutionPlan.Compiler.directSingleTargetApply(
                                    applies.getFirst(), type)
                            : null;
            PlanTraits traits = inspectPlanTraits(
                    type, new LinkedHashSet<>());
            ModelExecutionPlan.HandlerPlan compiledHandlers =
                    compiler.compileHandlers(handlers);
            return new ModelExecutionPlan(
                    compiledHandlers,
                    ModelTargetResolver.compile(type, handlers),
                    directApply,
                    ModelCommitPolicy.merge(traits.policies()),
                    traits.commit(),
                    traits.commit() && traits.automatic());
        });
        recentCommitPlan = new CachedExecutionPlan(payloadType, plan);
        return plan;
    }

    private void clearPlans() {
        commitPlans.clear();
        recentCommitPlan = null;
    }

    private static AncestorPlanKey ancestorPlanKey(
            ModelTargetResolver.Resolution resolution,
            Map<String, Object> stagedValues) {
        List<StagedRelationships> relationships = new ArrayList<>(stagedValues.size());
        stagedValues.forEach((modelId, value) -> {
            List<ParentRelationship> parents = new ArrayList<>();
            if (value != null) {
                ModelMetadata.validate(value.getClass()).parentReferences().forEach(parent -> {
                    Object parentId = parent.read(value);
                    if (parentId != null) {
                        parents.add(new ParentRelationship(
                                Objects.requireNonNull(
                                        parent.repositoryId(parentId), "Parent ID string"),
                                parent.parentModelType(parentId), parent.path()));
                    }
                });
            }
            relationships.add(new StagedRelationships(modelId, List.copyOf(parents)));
        });
        return new AncestorPlanKey(resolution, List.copyOf(relationships));
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

    private PlanTraits inspectPlanTraits(
            Class<?> payloadType,
            Set<Class<?>> visiting) {
        if (!visiting.add(payloadType)) {
            return PlanTraits.NEUTRAL;
        }
        try {
            List<ModelMetadata.HandlerMethod> handlers = inspectHandlers(payloadType);
            boolean commit = false;
            boolean automatic = true;
            LinkedHashSet<ModelCommitPolicy> policies = new LinkedHashSet<>();
            for (ModelMetadata.HandlerMethod handler : handlers) {
                if (handler.kind() == ModelMetadata.HandlerKind.APPLY) {
                    commit |= handler.hasApplyResult();
                    if (handler.hasApplyResult()) {
                        automatic &= automaticHandlingEnabled(handler);
                    }
                    if (handler.dynamicApplyResult()) {
                        policies.add(ModelCommitPolicy.SYNC_AFTER_HANDLER);
                    }
                    handler.targetModelTypes().stream()
                            .map(ModelMetadata::of)
                            .map(ModelMetadata::model)
                            .flatMap(Optional::stream)
                            .map(Model::commitPolicy)
                            .map(ModelCommitPolicy::resolve)
                            .forEach(policies::add);
                    continue;
                }
                if (handler.kind() != ModelMetadata.HandlerKind.INTERCEPT_APPLY) {
                    continue;
                }
                commit |= handler.emittedPayloadTypes().isEmpty();
                for (Class<?> emitted : handler.emittedPayloadTypes()) {
                    PlanTraits nested = inspectPlanTraits(emitted, visiting);
                    commit |= nested.commit();
                    automatic &= nested.automatic();
                    policies.addAll(nested.policies());
                }
            }
            return new PlanTraits(commit, automatic, policies);
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

    private record CachedExecutionPlan(
            Class<?> payloadType,
            ModelExecutionPlan plan) {
    }

    private record PlanTraits(
            boolean commit,
            boolean automatic,
            Set<ModelCommitPolicy> policies) {
        private static final PlanTraits NEUTRAL =
                new PlanTraits(false, true, Set.of());

        private PlanTraits {
            policies = Set.copyOf(policies);
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
                    || trackingTarget.isAssignableFrom(
                               message.getPayloadClass());
            if (!selected || !canAutomaticallyHandle(message)) {
                return null;
            }
            ModelCommitPolicy commitPolicy =
                    commitPolicyFor(message.getPayloadClass());
            ModelBatchScope.Operation batchTicket =
                    DeserializingMessage.getCurrent() == null
                    || !commitPolicy.commitAfterBatch()
                       && !commitPolicy.awaitAfterBatch()
                            ? null
                            : ModelBatchScope.register(
                                    this, message, commitPolicy,
                                    batchLifecycle);
            return new HandlerInvoker.DelegatingHandlerInvoker(
                    HandlerInvoker.call(
                            () -> {
                                return execute(
                                        message, commitPolicy,
                                        batchTicket);
                            })) {
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

    private Object execute(
            DeserializingMessage message,
            ModelCommitPolicy commitPolicy,
            ModelBatchScope.Operation batchTicket) {
        CompletableFuture<Object> completion = batchTicket == null
                ? execute(message)
                : execute(message, batchTicket);
        if (commitPolicy.awaitAfterBatch()) {
            return awaitAfterHandlerCommitsBeforeResults
                    ? completion : null;
        }
        /*
         * One automatic model handler produces one atomic commit. There are therefore no independent roots inside this
         * handler to run concurrently; both handler-completion policies must finish this commit before the handler
         * completion phase can finish.
         */
        return completion.join();
    }

    private PrefetchSlot prefetch(DeserializingMessage message) {
        ModelExecutionPlan plan = planFor(message.getPayloadClass());
        ModelTargetResolver.TargetPlan targetPlan =
                plan.targets();
        if (!targetPlan.isDirectSingleTarget()) {
            return null;
        }
        return new PrefetchSlot(
                targetPlan.resolveSingleModelId(
                        message.getPayload()),
                targetPlan.singleModelType(),
                targetPlan.singleAccess(),
                targetPlan.singleSourceProperties(),
                plan.directApply());
    }

    private static final class PrefetchSlot
            implements DefaultModelRepository.CurrentModelLookup {
        private final String modelId;
        private final Class<?> modelType;
        private final ModelTargetResolver.Access access;
        private final List<String> sourceProperties;
        private final ModelExecutionPlan.DirectSingleTargetApply directApply;
        private Entity<?> entity;
        private long stateIndex;

        private PrefetchSlot(
                String modelId,
                Class<?> modelType,
                ModelTargetResolver.Access access,
                List<String> sourceProperties,
                ModelExecutionPlan.DirectSingleTargetApply directApply) {
            this.modelId = modelId;
            this.modelType = modelType;
            this.access = access;
            this.sourceProperties = sourceProperties;
            this.directApply = directApply;
        }

        @Override
        public String modelId() {
            return modelId;
        }

        @Override
        public Class<?> modelType() {
            return modelType;
        }

        @Override
        public void accept(
                Entity<?> entity,
                long validThrough,
                long modelStateIndex) {
            this.entity = entity;
            this.stateIndex = validThrough;
        }
    }

    @Override
    public void close() {
    }
}
