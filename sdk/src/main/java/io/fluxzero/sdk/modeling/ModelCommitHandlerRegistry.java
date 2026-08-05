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

import io.fluxzero.common.Backlog;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.jfr.FluxzeroJfr;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.AsyncCompletionScope;
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
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Duration;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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
    private static final boolean BATCH_DIAGNOSTICS =
            Boolean.getBoolean(
                    "fluxzero.modelCommitBatchGateDiagnostics");
    private static final boolean BATCH_TIMING_DIAGNOSTICS =
            Boolean.getBoolean(
                    "fluxzero.modelCommitBatchTimingDiagnostics");
    private static final boolean DETAILED_TIMING_DIAGNOSTICS =
            Boolean.getBoolean(
                    "fluxzero.modelCommitDetailedTimingDiagnostics");
    private static final AtomicLong AFTER_COMMITTED =
            new AtomicLong();
    private static final LongAdder AFTER_COMMIT_NANOS =
            new LongAdder();
    private static final LongAdder AFTER_COMMIT_ASSEMBLY_NANOS =
            new LongAdder();
    private static final LongAdder AFTER_COMMIT_REPOSITORY_NANOS =
            new LongAdder();
    private static final AtomicLong POST_COMMIT_BATCHES =
            new AtomicLong();
    private static final boolean PIPELINE_DIAGNOSTICS =
            Boolean.getBoolean("fluxzero.modelCommitPipelineDiagnostics");
    private static final AtomicLong SELECTED_COMMANDS = new AtomicLong();
    private static final AtomicLong STARTED_COMMANDS = new AtomicLong();
    private static final AtomicLong EVALUATED_COMMANDS = new AtomicLong();
    private static final AtomicLong COMPLETED_COMMANDS = new AtomicLong();
    private static final LongAdder POST_COMMIT_ITEMS =
            new LongAdder();
    private static final boolean DISABLE_BATCH_GATES =
            Boolean.getBoolean(
                    "fluxzero.disableModelCommitBatchGates");
    private static final int MAX_COMMIT_BATCH_SIZE = Math.max(
            1, Integer.getInteger("fluxzero.modelCommitBatchSize", 65_536));
    private static final int MAX_COLD_PREFETCH_SIZE = Math.max(
            1, Integer.getInteger("fluxzero.modelColdPrefetchSize", 1_024));
    private static final long COMMIT_BATCH_COLLECTION_NANOS = Math.max(
            0L, Long.getLong("fluxzero.modelCommitBatchCollectionNanos", 1_000_000L));
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
    private final Backlog<BatchCommitTicket> commitBacklog;
    private final Object handlerCommitBatchKey = new Object();
    private final boolean awaitAfterHandlerCommitsBeforeResults;
    private final Serializer serializer;
    private final EventStoreClient eventStoreClient;
    private final List<Class<?>> registeredModelTypes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Class<?>, CompletableFuture<ModelGraphProjectionStatus>>
            graphProjectionRegistrations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, CommitPlan> commitPlans =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Boolean> automaticPayloads =
            new ConcurrentHashMap<>();
    private volatile CachedCommitPlan recentCommitPlan;
    private volatile CachedAutomaticPayload recentAutomaticPayload;
    private final ConcurrentHashMap<Class<?>, List<ProjectionRoot>> projectionPlans =
            new ConcurrentHashMap<>();
    private volatile CachedProjectionRoots recentProjectionRoots;
    private volatile boolean localHandlingEnabled;

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
                this::afterCommitBatch);
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
        this.awaitAfterHandlerCommitsBeforeResults =
                io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty(
                        ModelCommitPolicy.AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY,
                        true);
        this.decoratedHandler = handlerDecorator.wrap(new CommitHandler(null));
        this.commitBacklog = Backlog.forAsyncConsumer(
                this::startBatch,
                MAX_COMMIT_BATCH_SIZE,
                ignored -> 1L,
                MAX_COMMIT_BATCH_SIZE,
                1,
                Duration.ofNanos(COMMIT_BATCH_COLLECTION_NANOS));
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
                ModelCommitEngine.CommitEvaluation evaluation =
                        evaluate(message);
                if (evaluation.transitions().isEmpty()) {
                    log.warn(
                            "Fluxzero.assertAndApply({}) ran model interceptors and assertions, but this application has "
                            + "no locally reachable model @Apply handler. No model changes were committed.",
                            message.getPayloadClass().getName());
                    return COMPLETED_VOID;
                }
                return executeRegistered(
                        message, evaluation,
                        null, null, -1)
                        .thenApply(ignored -> null);
            }
            return execute(message).thenApply(ignored -> null);
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
            engine.assertLegal(message, new CommitLoader(null));
            return COMPLETED_VOID;
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
            ModelCommitEngine.CommitEvaluation evaluation =
                    engine.rebase(List.of(message), new CommitLoader(null, true));
            return executeRegistered(message, evaluation, null, null, -1).thenApply(ignored -> null);
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
        if (message.getMessageType() != MessageType.COMMAND) {
            return false;
        }
        Class<?> payloadType = message.getPayloadClass();
        CachedAutomaticPayload recent = recentAutomaticPayload;
        if (recent != null && recent.payloadType() == payloadType) {
            return recent.automatic();
        }
        boolean automatic = automaticPayloads.computeIfAbsent(
                       payloadType,
                       type -> hasModelApplies(type)
                                      && automaticHandlingEnabled(
                                              type,
                                              new LinkedHashSet<>()));
        recentAutomaticPayload = new CachedAutomaticPayload(
                payloadType, automatic);
        return automatic;
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

    ModelCommitPolicy commitPolicyFor(Class<?> payloadType) {
        CommitPlan plan = planFor(payloadType);
        ModelCommitPolicy cached = plan.commitPolicy();
        if (cached != null) {
            return cached;
        }
        synchronized (plan) {
            cached = plan.commitPolicy();
            if (cached == null) {
                LinkedHashSet<ModelCommitPolicy> policies = new LinkedHashSet<>();
                collectCommitPolicies(payloadType, new LinkedHashSet<>(), policies);
                cached = mergeCommitPolicies(policies);
                plan.commitPolicy(cached);
            }
        }
        return cached;
    }

    private void collectCommitPolicies(
            Class<?> payloadType,
            Set<Class<?>> visiting,
            Set<ModelCommitPolicy> policies) {
        if (!visiting.add(payloadType)) {
            return;
        }
        try {
            for (ModelMetadata.HandlerMethod handler : planFor(payloadType).handlers()) {
                if (handler.kind() == ModelMetadata.HandlerKind.APPLY) {
                    handler.targetModelTypes().stream()
                            .map(ModelMetadata::of)
                            .map(ModelMetadata::model)
                            .flatMap(Optional::stream)
                            .map(Model::commitPolicy)
                            .map(ModelCommitPolicy::resolve)
                            .forEach(policies::add);
                } else if (handler.kind() == ModelMetadata.HandlerKind.INTERCEPT_APPLY) {
                    handler.emittedPayloadTypes().forEach(
                            emitted -> collectCommitPolicies(emitted, visiting, policies));
                }
            }
        } finally {
            visiting.remove(payloadType);
        }
    }

    private static ModelCommitPolicy mergeCommitPolicies(
            Set<ModelCommitPolicy> policies) {
        return ModelCommitPolicy.merge(policies);
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
        boolean modelReceiver = ModelMetadata.of(targetType).isModel();
        boolean payloadCommit = declaresModelCommit(
                targetType, new LinkedHashSet<>())
                                && automaticHandlingEnabled(
                                        targetType,
                                        new LinkedHashSet<>())
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
        return execute(message, null);
    }

    private CompletableFuture<Object> execute(
            DeserializingMessage message,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
        return execute(
                message, null, null,
                transportBatch, transportSlot);
    }

    private CompletableFuture<Object> execute(
            DeserializingMessage message,
            BatchCommitTicket batchTicket) {
        return execute(
                message, batchTicket, null);
    }

    private CompletableFuture<Object> execute(
            DeserializingMessage message,
            BatchCommitTicket batchTicket,
            BatchPrefetch prefetch) {
        return execute(
                message, batchTicket, prefetch,
                null, -1);
    }

    private CompletableFuture<Object> execute(
            DeserializingMessage message,
            BatchCommitTicket batchTicket,
            BatchPrefetch prefetch,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
        CompletableFuture<Object> result;
        if (graphProjectionRegistrations.isEmpty()) {
            result = executeRegistered(
                    message,
                    batchTicket,
                    prefetch,
                    transportBatch,
                    transportSlot);
        } else {
            CompletableFuture<?> registrations =
                    CompletableFuture.allOf(
                            graphProjectionRegistrations
                                    .values()
                                    .toArray(
                                            CompletableFuture[]::new));
            result = registrations.thenCompose(
                    ignored -> executeRegistered(
                            message,
                            batchTicket,
                            prefetch,
                            transportBatch,
                            transportSlot));
        }
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
            BatchCommitTicket batchTicket,
            BatchPrefetch prefetch,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
        recordModelRequestStage(message, "model-evaluation-start", 1);
        ModelCommitEngine.CommitEvaluation initialEvaluation =
                batchTicket != null
                && batchTicket.hasBatchModelView()
                        ? evaluateGeneric(message, batchTicket)
                        : prefetch == null
                        ? batchTicket != null
                          && batchTicket.gates() == null
                                ? evaluateKnown(
                                        message,
                                        batchTicket.prefetchInput())
                                : evaluate(message)
                        : evaluatePrefetched(
                                message, prefetch, batchTicket);
        recordModelRequestStage(message, "model-evaluation-complete", 1);
        pipelineDiagnostic("evaluated", EVALUATED_COMMANDS);
        if (batchTicket != null) {
            batchTicket.stage(initialEvaluation);
            batchTicket.assign(
                    initialEvaluation.readModelIds());
        }
        CompletableFuture<Object> result = executeRegistered(
                message, initialEvaluation,
                batchTicket,
                transportBatch,
                transportSlot);
        if (batchTicket != null) {
            batchTicket.completeInitialization();
        }
        return result;
    }

    private CompletableFuture<Object> executeRegistered(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation initialEvaluation,
            BatchCommitTicket batchTicket,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
        boolean batchDependent = batchTicket != null
                                 && batchTicket.hasBatchDependencies();
        if (!batchDependent
            && ModelConflictPolicies.resolve(
                    initialEvaluation,
                    conflictPolicy)
               != ModelConflictPolicy.ACCEPT) {
            return executeBatched(
                    message, initialEvaluation,
                    batchTicket,
                    transportBatch,
                    transportSlot);
        }
        if (batchDependent) {
            /*
             * A dependent command cannot remain a producer in the shared transport batch: that batch only flushes
             * after every producer is ready, while this command intentionally waits for an earlier producer to be
             * durably complete. Detach before entering the coordinator so the predecessor can actually be sent.
             */
            batchTicket.detachTransport();
        }
        return commitCoordinator.coordinate(
                initialEvaluation.readModelIds(),
                contended -> {
                    if (batchDependent) {
                        ThreadLocalContext.Snapshot context =
                                executionContext(
                                        message,
                                        batchTicket);
                        return executeBatchDependent(
                                message, batchTicket,
                                context);
                    }
                    if (!contended) {
                        return executeBatched(
                                message,
                                initialEvaluation,
                                batchTicket,
                                transportBatch,
                                transportSlot);
                    }
                    ThreadLocalContext.Snapshot context =
                            executionContext(
                                    message,
                                    batchTicket);
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
                                                            batchTicket,
                                                            transportBatch,
                                                            transportSlot)));
                });
    }

    private CompletableFuture<Object> executeBatchDependent(
            DeserializingMessage message,
            BatchCommitTicket batchTicket,
            ThreadLocalContext.Snapshot context) {
        return batchTicket.executeAfterRelease(
                () -> batchTicket.dependencyCompletion()
                        .thenCompose(ignored ->
                                CompletableFuture.supplyAsync(
                                        context.wrap(
                                                () -> evaluate(message))))
                        .thenCompose(context.wrap(
                                evaluation -> executeEvaluation(
                                        message, evaluation))));
    }

    private CompletableFuture<Object> executeBatched(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation evaluation,
            BatchCommitTicket batchTicket,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
        if (batchTicket == null) {
            return executeEvaluation(
                    message, evaluation,
                    transportBatch, transportSlot);
        }
        ThreadLocalContext.Snapshot context =
                executionContext(
                        message,
                        batchTicket);
        return batchTicket.executeAfterRelease(
                () -> context.supply(
                        () -> executeEvaluation(
                                message,
                                evaluation,
                                batchTicket.transportBatch(),
                                batchTicket.transportSlot())));
    }

    private static ThreadLocalContext.Snapshot executionContext(
            DeserializingMessage message,
            BatchCommitTicket batchTicket) {
        return batchTicket == null
                ? message.captureContext()
                : batchTicket.context();
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation evaluation) {
        return executeEvaluation(
                message, evaluation,
                null, -1);
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
        ModelConflictPolicy effectiveConflictPolicy =
                ModelConflictPolicies.resolve(
                        evaluation,
                        conflictPolicy);
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
        return registrations.thenCompose(ignored ->
                executeEvaluation(
                        message, evaluation,
                        effectiveConflictPolicy,
                        awaitedGraphProjections,
                        transportBatch,
                        transportSlot));
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy effectiveConflictPolicy,
            Map<String, Set<String>> awaitedGraphProjections,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot) {
                    Runnable localCommitComplete =
                            repository.beginLocalCommit(
                                    writtenModelIds(evaluation));
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
                                                },
                                                transportBatch,
                                                transportSlot)
                                        : committer.commit(
                                                message.getMessageId(),
                                                evaluation,
                                                effectiveConflictPolicy,
                                                conflictResolver,
                                                maxConflictRetries,
                                                () -> reload(
                                                        message,
                                                        evaluation
                                                                .readModelIds()),
                                                transportBatch,
                                                transportSlot);
                        if (awaitedGraphProjections.isEmpty()) {
                            return result.handle(
                                    (commitResult, failure) -> {
                                        localCommitComplete.run();
                                        return finishEvaluation(
                                                evaluation,
                                                effectiveConflictPolicy,
                                                failure);
                                    });
                        }
                        return result.whenComplete(
                                             (commitResult, failure) ->
                                                     localCommitComplete.run())
                                .thenCompose(commitResult ->
                                                     awaitGraphProjections(
                                                             commitResult,
                                                             awaitedGraphProjections))
                                .handle(
                                (commitResult, failure) -> {
                                    return finishEvaluation(
                                            evaluation,
                                            effectiveConflictPolicy,
                                            failure);
                                });
                    } catch (Throwable failure) {
                        localCommitComplete.run();
                        throw failure;
                    }
    }

    private Object finishEvaluation(
            ModelCommitEngine.CommitEvaluation evaluation,
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
            ModelCommitEngine.CommitEvaluation evaluation) {
        List<ModelCommitEngine.Transition> transitions =
                evaluation.transitions();
        if (transitions.size() == 1) {
            return List.of(
                    transitions.getFirst().modelId());
        }
        return transitions.stream()
                .map(ModelCommitEngine.Transition::modelId)
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
            ModelCommitEngine.CommitEvaluation evaluation) {
        return awaitedGraphProjectionTargets(
                evaluation).keySet();
    }

    Map<String, Set<String>> awaitedGraphProjectionTargets(
            ModelCommitEngine.CommitEvaluation evaluation) {
        GraphProjectionCompletion consumer = null;
        LinkedHashMap<String, LinkedHashSet<String>> result =
                null;
        for (ModelCommitEngine.Transition transition :
                evaluation.transitions()) {
            List<ProjectionRoot> roots =
                    projectionRoots(
                            transition.modelType());
            if (roots.isEmpty()) {
                continue;
            }
            if (consumer == null) {
                consumer = Tracker.current()
                        .map(Tracker::getConfiguration)
                        .map(configuration ->
                                     configuration
                                             .getGraphProjectionCompletion())
                        .orElse(
                                GraphProjectionCompletion.DEFAULT);
            }
            Apply apply =
                    transition.handler()
                            .getAnnotation(
                                    Apply.class);
            GraphProjectionCompletion applyPolicy =
                    apply == null
                            ? GraphProjectionCompletion.DEFAULT
                            : apply.graphProjectionCompletion();
            if (result == null) {
                result = new LinkedHashMap<>();
            }
            LinkedHashMap<String, LinkedHashSet<String>> targets =
                    result;
            GraphProjectionCompletion consumerPolicy =
                    consumer;
            roots.forEach(root -> {
                        GraphProjectionCompletion policy =
                                resolveProjectionCompletion(
                                        applyPolicy,
                                        consumerPolicy,
                                        root.projection()
                                                .completion());
                        if (policy
                            == GraphProjectionCompletion.AWAIT) {
                            targets.computeIfAbsent(
                                            root.collection(),
                                            ignored ->
                                                    new LinkedHashSet<>())
                                    .add(
                                            transition.modelId());
                        }
                    });
        }
        if (result == null || result.isEmpty()) {
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

    private List<ProjectionRoot> projectionRoots(
            Class<?> modelType) {
        CachedProjectionRoots recent = recentProjectionRoots;
        if (recent != null
            && recent.modelType() == modelType) {
            return recent.roots();
        }
        List<ProjectionRoot> roots =
                projectionPlans.computeIfAbsent(
                        modelType,
                        this::inspectProjectionRoots);
        recentProjectionRoots =
                new CachedProjectionRoots(
                        modelType, roots);
        return roots;
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
        LinkedHashSet<ProjectionRoot> roots = null;
        for (ModelCommitEngine.Transition transition :
                evaluation.transitions()) {
            List<ProjectionRoot> candidates =
                    projectionRoots(transition.modelType());
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

    private CompletableFuture<Void> afterCommitBatch(
            List<ModelCommitter.CommittedCommit> committed) {
        long repositoryStarted = DETAILED_TIMING_DIAGNOSTICS
                ? System.nanoTime() : 0L;
        List<DefaultModelRepository.CommittedModel> committedModels =
                new ArrayList<>(committed.size());
        for (ModelCommitter.CommittedCommit item : committed) {
            appendCommittedModels(
                    item, committedModels);
        }
        repository.updateAfterCommit(committedModels);
        if (DETAILED_TIMING_DIAGNOSTICS) {
            AFTER_COMMIT_REPOSITORY_NANOS.add(
                    System.nanoTime() - repositoryStarted);
            POST_COMMIT_ITEMS.add(committed.size());
            long batches = POST_COMMIT_BATCHES.incrementAndGet();
            if ((batches & 63L) == 0L) {
                System.out.printf(
                        "SDK model post-commit batches: batches=%d items=%d average=%.1f repository=%.3f ms%n",
                        batches,
                        POST_COMMIT_ITEMS.sum(),
                        POST_COMMIT_ITEMS.sum() / (double) batches,
                        AFTER_COMMIT_REPOSITORY_NANOS.sum()
                        / 1_000_000.0);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void appendCommittedModels(
            ModelCommitter.CommittedCommit committed,
            List<DefaultModelRepository.CommittedModel> target) {
        long started = DETAILED_TIMING_DIAGNOSTICS
                ? System.nanoTime() : 0L;
        try {
            createCommittedModels(committed, target);
        } finally {
            if (DETAILED_TIMING_DIAGNOSTICS) {
                long elapsed = System.nanoTime() - started;
                AFTER_COMMIT_NANOS.add(elapsed);
                AFTER_COMMIT_ASSEMBLY_NANOS.add(elapsed);
                long count = AFTER_COMMITTED.incrementAndGet();
                if ((count & 65_535L) == 0L) {
                    System.out.printf(
                            "SDK model afterCommit cumulative: count=%d cpu=%.3f ms average=%.3f us assembly=%.3f ms repository=%.3f ms%n",
                            count,
                            AFTER_COMMIT_NANOS.sum() / 1_000_000.0,
                            AFTER_COMMIT_NANOS.sum() / 1_000.0 / count,
                            AFTER_COMMIT_ASSEMBLY_NANOS.sum() / 1_000_000.0,
                            AFTER_COMMIT_REPOSITORY_NANOS.sum() / 1_000_000.0);
                }
            }
        }
    }

    private void createCommittedModels(
            ModelCommitter.CommittedCommit committed,
            List<DefaultModelRepository.CommittedModel> target) {
        if (committed.prepared().transitionGroups().size()
            != committed.result().getSubsteps().size()) {
            throw new IllegalStateException(
                    "Model commit returned a different number of substeps than requested");
        }
        if (committed.prepared().transitionGroups().size() == 1
            && committed.prepared().transitionGroups().getFirst().size() == 1) {
            ModelCommitter.EffectiveTransition effective =
                    committed.prepared().transitionGroups()
                            .getFirst().getFirst();
            if (!committed.result().hasSingleTargetResult()) {
                throw new IllegalStateException(
                        "Model commit returned a different number of targets than requested");
            }
            if (!effective.updateState()) {
                return;
            }
            ModelCommitEngine.Transition transition =
                    effective.transition();
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
                            effective.model(),
                            effective.entityId(),
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
                                effective.model(), effective.entityId(),
                                targetResult.isHistoryComplete(),
                                revisions));
            }
        }
        target.addAll(finalStates.values());
    }

    private ModelCommitEngine.CommitEvaluation evaluate(DeserializingMessage initialMessage) {
        return evaluateKnown(
                initialMessage,
                prefetchInput(initialMessage));
    }

    private ModelCommitEngine.CommitEvaluation evaluateKnown(
            DeserializingMessage initialMessage,
            PrefetchInput input) {
        if (input != null) {
            PrefetchSlot cached = new PrefetchSlot(
                    input.modelId(),
                    input.modelType());
            if (repository.supplyCurrentModel(
                    input.modelId(), input.modelType(), cached)) {
                return evaluateSingleTarget(initialMessage, input, cached);
            }
        }
        return evaluateGeneric(initialMessage);
    }

    private ModelCommitEngine.CommitEvaluation evaluateGeneric(
            DeserializingMessage initialMessage) {
        return engine.evaluate(initialMessage, new CommitLoader(null));
    }

    private ModelCommitEngine.CommitEvaluation evaluateGeneric(
            DeserializingMessage initialMessage,
            BatchCommitTicket batchTicket) {
        return engine.evaluate(
                initialMessage,
                new CommitLoader(null, false, batchTicket));
    }

    private ModelCommitEngine.CommitEvaluation evaluatePrefetched(
            DeserializingMessage message,
            BatchPrefetch prefetch,
            BatchCommitTicket ticket) {
        PrefetchInput input = ticket.prefetchInput();
        if (input == null) {
            return evaluate(message);
        }
        PrefetchSlot prefetched =
                prefetch.models().get(input.modelId());
        if (prefetched == null || prefetched.entity == null) {
            return evaluate(message);
        }
        return evaluateSingleTarget(message, input, prefetched);
    }

    private ModelCommitEngine.CommitEvaluation evaluateSingleTarget(
            DeserializingMessage message,
            PrefetchInput input,
            PrefetchSlot prefetched) {
        ModelMetadata.HandlerMethod handler = input.handler();
        Entity<?> entity = prefetched.entity;
        ModelCommitEngine.SingleTargetEvaluation applied =
                input.directApply() != null
                && input.access().writes()
                        ? engine.evaluateDirectSingleTarget(
                                message,
                                entity,
                                handler,
                                input.modelId(),
                                input.directApply())
                        : engine.evaluateSingleTarget(
                                message,
                                ModelCommitContext.createSingle(
                                        prefetched.stateIndex,
                                        input.modelId(),
                                        input.modelType(),
                                        input.access(),
                                        input.sourceProperties(),
                                        entity),
                                handler,
                                input.modelId(),
                                input.directApply());
        if (!applied.applied()) {
            return evaluateGeneric(message);
        }
        Object before = entity.get();
        Object after = applied.value();
        ModelCommitEngine.Transition transition =
                new ModelCommitEngine.Transition(
                        input.modelId(),
                        input.modelType(),
                        entity instanceof ModelRoot<?> root
                                ? root.sequenceNumber()
                                : -1L,
                        entity instanceof ModelRoot<?> root
                                ? root.lastEventIndex()
                                : null,
                        before,
                        after,
                        handler.executable());
        LinkedHashMap<String, Object> finalValues =
                new LinkedHashMap<>(1);
        finalValues.put(
                input.modelId(), after);
        return new ModelCommitEngine.CommitEvaluation(
                prefetched.stateIndex,
                List.of(input.modelId()),
                Map.of(
                        input.modelId(),
                        input.modelType()),
                List.of(
                        new ModelCommitEngine.AppliedSubstep(
                                message,
                                List.of(transition))),
                finalValues);
    }

    private ModelCommitEngine.CommitEvaluation rebase(
            List<DeserializingMessage> messages,
            long stateIndex) {
        return engine.rebase(messages, new CommitLoader(stateIndex));
    }

    private final class CommitLoader implements ModelCommitEngine.SubstepResolver {
        private final Long pinnedStateIndex;
        private final boolean applyOnly;
        private final BatchCommitTicket batchTicket;
        private final Map<String, Entity<?>> commitEntities = new LinkedHashMap<>();
        private final Map<AncestorPlanKey, List<ModelTargetResolver.ResolvedModel>> ancestorPlans =
                new LinkedHashMap<>();

        private CommitLoader(Long pinnedStateIndex) {
            this(pinnedStateIndex, false, null);
        }

        private CommitLoader(Long pinnedStateIndex, boolean applyOnly) {
            this(pinnedStateIndex, applyOnly, null);
        }

        private CommitLoader(
                Long pinnedStateIndex,
                boolean applyOnly,
                BatchCommitTicket batchTicket) {
            this.pinnedStateIndex = pinnedStateIndex;
            this.applyOnly = applyOnly;
            this.batchTicket = batchTicket;
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
                    applyOnly || pinnedStateIndex != null ? plan.applies() : plan.handlers();
            ModelTargetResolver.Resolution resolution =
                    targetPlan(substep.getPayloadClass(), plan, pinnedStateIndex != null)
                            .resolve(substep.getPayload());
            AncestorPlanKey planKey = resolution.hasAncestorDependencies()
                    ? ancestorPlanKey(resolution, stagedValues) : null;
            List<ModelTargetResolver.ResolvedModel> effectiveTargets = planKey == null
                    ? resolution.models() : ancestorPlans.get(planKey);
            List<ModelTargetResolver.ResolvedModel> missing = effectiveTargets == null ? List.of()
                    : effectiveTargets.stream()
                            .filter(target -> !containsEntity(
                                    target.modelId()))
                            .toList();

            long stateIndex = boundary == null ? -1L : boundary;
            if (effectiveTargets == null) {
                ModelCommitContext loaded = load(resolution, boundary, stagedValues);
                stateIndex = loaded.readStateIndex();
                effectiveTargets = targets(loaded);
                ancestorPlans.put(planKey, effectiveTargets);
            } else if ((pinnedStateIndex == null
                        && requestedStateIndex == null)
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
                        entity(target.modelId()),
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
                        .filter(target -> !containsEntity(
                                target.modelId()))
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
            Map<String, Object> batchValues =
                    batchTicket == null
                            ? Map.of()
                            : batchTicket.batchValues(resolution);
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
                    resolution, boundary, effectiveStagedValues);
            if (batchTicket != null) {
                Map<String, Object> loadedBatchValues =
                        batchTicket.batchValues(loaded);
                if (!loadedBatchValues.isEmpty()) {
                    loaded = loaded.withValues(loadedBatchValues);
                }
            }
            if (boundary != null && loaded.readStateIndex() != boundary) {
                throw new IllegalStateException(
                        "Model commit requested state index %d but loaded %d"
                                .formatted(boundary, loaded.readStateIndex()));
            }
            loaded.entries().forEach(entry ->
                    commitEntities.put(entry.target().modelId(), entry.entity()));
            return loaded;
        }

        private boolean containsEntity(
                String modelId) {
            return commitEntities.containsKey(modelId);
        }

        private Entity<?> entity(
                String modelId) {
            return commitEntities.get(modelId);
        }

        private static List<ModelTargetResolver.ResolvedModel> targets(ModelCommitContext context) {
            return context.entries().stream().map(ModelCommitContext.Entry::target).toList();
        }
    }

    private CommitPlan planFor(Class<?> payloadType) {
        CachedCommitPlan recent = recentCommitPlan;
        if (recent != null && recent.payloadType() == payloadType) {
            return recent.plan();
        }
        CommitPlan plan = commitPlans.computeIfAbsent(payloadType, type -> {
            List<ModelMetadata.HandlerMethod> handlers = inspectHandlers(type);
            List<ModelMetadata.HandlerMethod> applies = handlers.stream()
                    .filter(handler -> handler.kind() == ModelMetadata.HandlerKind.APPLY)
                    .toList();
            ModelCommitEngine.DirectSingleTargetApply directApply =
                    handlers.size() == 1 && applies.size() == 1
                            ? ModelCommitEngine.directSingleTargetApply(
                                    applies.getFirst(), type)
                            : null;
            return new CommitPlan(
                    handlers, applies, directApply);
        });
        recentCommitPlan = new CachedCommitPlan(payloadType, plan);
        return plan;
    }

    private ModelTargetResolver.TargetPlan targetPlan(
            Class<?> payloadType, CommitPlan plan, boolean appliesOnly) {
        return plan.targetPlan(payloadType, appliesOnly);
    }

    private void clearPlans() {
        commitPlans.clear();
        automaticPayloads.clear();
        recentCommitPlan = null;
        recentAutomaticPayload = null;
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
            List<ModelMetadata.HandlerMethod> interceptors = handlers.stream()
                    .filter(handler -> handler.kind()
                            == ModelMetadata.HandlerKind.INTERCEPT_APPLY)
                    .toList();
            if (interceptors.stream().anyMatch(handler -> handler.emittedPayloadTypes().isEmpty())) {
                return !interceptors.isEmpty();
            }
            return interceptors.stream()
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

    private static final class CommitPlan {
        private final List<ModelMetadata.HandlerMethod> handlers;
        private final List<ModelMetadata.HandlerMethod> applies;
        private final ModelCommitEngine.DirectSingleTargetApply directApply;
        private volatile ModelTargetResolver.TargetPlan targetPlan;
        private volatile ModelTargetResolver.TargetPlan applyTargetPlan;
        private volatile ModelCommitPolicy commitPolicy;

        private CommitPlan(
                List<ModelMetadata.HandlerMethod> handlers,
                List<ModelMetadata.HandlerMethod> applies,
                ModelCommitEngine.DirectSingleTargetApply directApply) {
            this.handlers = handlers;
            this.applies = applies;
            this.directApply = directApply;
        }

        private List<ModelMetadata.HandlerMethod> handlers() {
            return handlers;
        }

        private List<ModelMetadata.HandlerMethod> applies() {
            return applies;
        }

        private ModelCommitEngine.DirectSingleTargetApply directApply() {
            return directApply;
        }

        private ModelCommitPolicy commitPolicy() {
            return commitPolicy;
        }

        private void commitPolicy(ModelCommitPolicy commitPolicy) {
            this.commitPolicy = commitPolicy;
        }

        private ModelTargetResolver.TargetPlan targetPlan(
                Class<?> payloadType,
                boolean appliesOnly) {
            ModelTargetResolver.TargetPlan current = appliesOnly
                    ? applyTargetPlan : targetPlan;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = appliesOnly ? applyTargetPlan : targetPlan;
                if (current == null) {
                    current = ModelTargetResolver.plan(
                            payloadType,
                            appliesOnly ? applies : handlers);
                    if (appliesOnly) {
                        applyTargetPlan = current;
                    } else {
                        targetPlan = current;
                    }
                }
                return current;
            }
        }

    }

    private record CachedCommitPlan(
            Class<?> payloadType,
            CommitPlan plan) {
    }

    private record CachedAutomaticPayload(
            Class<?> payloadType,
            boolean automatic) {
    }

    private record ProjectionRoot(
            Class<?> modelType,
            ModelGraphProjectionConfiguration configuration,
            GraphProjection projection) {
        private String collection() {
            return configuration.getCollection();
        }
    }

    private record CachedProjectionRoots(
            Class<?> modelType,
            List<ProjectionRoot> roots) {
    }

    private final class CommitHandler
            implements Handler<DeserializingMessage> {
        private final Class<?> trackingTarget;
        private final boolean modelReceiver;

        private CommitHandler(Class<?> trackingTarget) {
            this.trackingTarget = trackingTarget;
            this.modelReceiver = trackingTarget != null
                                 && ModelMetadata.of(trackingTarget).isModel();
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
                    || modelReceiver
                       && ownsRegisteredModelCommit(
                               trackingTarget, message.getPayloadClass())
                    || !modelReceiver
                       && trackingTarget.isAssignableFrom(
                               message.getPayloadClass());
            if (!selected || !canAutomaticallyHandle(message)) {
                return null;
            }
            ModelCommitPolicy commitPolicy =
                    commitPolicyFor(message.getPayloadClass());
            BatchCommitTicket batchTicket =
                    DISABLE_BATCH_GATES
                    || !commitPolicy.commitAfterBatch()
                    || DeserializingMessage.getCurrent() == null
                            ? null
                            : batchCommitTicket(message, commitPolicy);
            HandlerCommitTicket handlerTicket =
                    commitPolicy.awaitAfterBatch()
                    && !commitPolicy.commitAfterBatch()
                    && DeserializingMessage.getCurrent() != null
                            ? handlerCommitTicket(message)
                            : null;
            pipelineDiagnostic("selected", SELECTED_COMMANDS);
            return new HandlerInvoker.DelegatingHandlerInvoker(
                    HandlerInvoker.call(
                            () -> {
                                pipelineDiagnostic("started", STARTED_COMMANDS);
                                Object result = execute(
                                        message, commitPolicy,
                                        batchTicket, handlerTicket);
                                if (PIPELINE_DIAGNOSTICS) {
                                    if (result instanceof CompletableFuture<?> future) {
                                        future.whenComplete((ignored, failure) ->
                                                pipelineDiagnostic("completed", COMPLETED_COMMANDS));
                                    } else {
                                        pipelineDiagnostic("completed", COMPLETED_COMMANDS);
                                    }
                                }
                                return result;
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
            BatchCommitTicket batchTicket,
            HandlerCommitTicket handlerTicket) {
        if (batchTicket != null) {
            return batchTicket.execute();
        }
        CompletableFuture<Object> completion = handlerTicket == null
                ? execute(message)
                : handlerTicket.start(() ->
                        executeHandlerBatch(
                                message, handlerTicket));
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

    private CompletableFuture<Object> executeHandlerBatch(
            DeserializingMessage message,
            HandlerCommitTicket handlerTicket) {
        BatchCommitTicket modelTicket =
                handlerTicket.modelTicket();
        if (modelTicket == null) {
            return execute(
                    message,
                    handlerTicket.transportBatch(),
                    handlerTicket.transportSlot());
        }
        if (!modelTicket.hasBatchDependencies()) {
            return execute(message, modelTicket);
        }
        CompletableFuture<Object> result =
                modelTicket.initializationPrerequisite()
                        .thenComposeAsync(ignored ->
                                modelTicket.context().supply(
                                        () -> execute(
                                                message,
                                                modelTicket)));
        result.whenComplete((ignored, failure) -> {
            if (failure != null
                && !modelTicket.initializationDone()) {
                modelTicket.failInitialization(failure);
            }
        });
        return result;
    }

    private static void pipelineDiagnostic(String phase, AtomicLong counter) {
        if (!PIPELINE_DIAGNOSTICS) {
            return;
        }
        long count = counter.incrementAndGet();
        if ((count & 8_191L) == 0L) {
            System.out.printf("SDK model pipeline %s: %,d%n", phase, count);
        }
    }

    private BatchCommitTicket batchCommitTicket(
            DeserializingMessage message,
            ModelCommitPolicy commitPolicy) {
        BatchCommitGates gates =
                DeserializingMessage.computeForBatchIfAbsent(
                        this, ignored -> {
                            BatchCommitGates created =
                                    new BatchCommitGates();
                            DeserializingMessage.whenBatchCompletes(
                                    created::close);
                            return created;
                        });
        return gates.register(message, commitPolicy);
    }

    private HandlerCommitTicket handlerCommitTicket(
            DeserializingMessage message) {
        HandlerCommitBatch batch =
                DeserializingMessage.computeForBatchIfAbsent(
                        handlerCommitBatchKey, ignored -> {
                            HandlerCommitBatch created = new HandlerCommitBatch();
                            DeserializingMessage.whenBatchCompletes(created::close);
                            return created;
                        });
        HandlerCommitTicket ticket = batch.register(message);
        if (awaitAfterHandlerCommitsBeforeResults) {
            io.fluxzero.sdk.tracking.handling.Invocation.awaitBeforeResultPublication(
                    message, ticket.execution());
        }
        return ticket;
    }

    private CompletableFuture<Void> startBatch(
            List<BatchCommitTicket> batch) {
        long started = System.nanoTime();
        if (BATCH_TIMING_DIAGNOSTICS
            || FluxzeroJfr.batchEnabled()) {
            batch.stream()
                    .map(BatchCommitTicket::gates)
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(gates -> gates.markBacklogStart(started));
        }
        if (BATCH_DIAGNOSTICS) {
            System.out.printf(
                    "SDK model commit backlog flush: tickets=%d%n",
                    batch.size());
        }
        boolean batchModelView = batch.stream()
                .map(BatchCommitTicket::gates)
                .filter(Objects::nonNull)
                .distinct()
                .map(BatchCommitGates::prepareModelView)
                .reduce(false, Boolean::logicalOr);
        BatchPrefetch prefetch;
        try {
            prefetch = batchModelView ? null : prefetchBatch(batch);
        } catch (Throwable batchFailure) {
            batch.forEach(ticket -> {
                ticket.exclude();
                ticket.fail(batchFailure);
            });
            return CompletableFuture.completedFuture(null);
        }
        boolean concurrent = batch.stream()
                .allMatch(ticket -> ticket.commitPolicy().async());
        ModelCommitter.CommitBatch transportBatch = concurrent
                ? committer.beginBatch(batch.size()) : null;
        for (int index = 0; index < batch.size(); index++) {
            batch.get(index).transport(
                    transportBatch, index);
        }
        if (batchModelView) {
            batch.forEach(ticket -> startAfterDependencies(
                    ticket, prefetch));
            return CompletableFuture.allOf(
                            batch.stream()
                                    .map(BatchCommitTicket::initialization)
                                    .toArray(CompletableFuture[]::new))
                    .handle((ignored, failure) -> {
                        releaseGates(batch);
                        markBatchStarted(batch, started);
                        return null;
                    });
        }
        if (batch.size() < 256) {
            batch.forEach(ticket -> start(ticket, prefetch));
            releaseGates(batch);
            markBatchStarted(batch, started);
            return CompletableFuture.completedFuture(null);
        }
        int workers = Math.min(
                Runtime.getRuntime().availableProcessors(),
                (batch.size() + 255) / 256);
        int chunkSize = (batch.size() + workers - 1) / workers;
        CompletableFuture<?>[] starts =
                new CompletableFuture<?>[workers];
        for (int worker = 0; worker < workers; worker++) {
            int from = worker * chunkSize;
            int until = Math.min(batch.size(), from + chunkSize);
            starts[worker] = CompletableFuture.runAsync(() -> {
                for (int index = from; index < until; index++) {
                    start(batch.get(index), prefetch);
                }
            });
        }
        return CompletableFuture.allOf(starts)
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        releaseGates(batch);
                        markBatchStarted(batch, started);
                    } else {
                        if (transportBatch != null) {
                            transportBatch.fail(failure);
                        }
                        batch.forEach(ticket ->
                                              ticket.fail(failure));
                    }
                });
    }

    private static void markBatchStarted(
            List<BatchCommitTicket> batch,
            long started) {
        if (!BATCH_TIMING_DIAGNOSTICS
            && !FluxzeroJfr.batchEnabled()) {
            return;
        }
        long completed = System.nanoTime();
        batch.stream()
                .map(BatchCommitTicket::gates)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(gates -> gates.markCommitsStarted(
                        completed, completed - started));
    }

    private static void recordModelRequestStage(
            DeserializingMessage message, String stage, int batchSize) {
        if (!FluxzeroJfr.requestStageEnabled()) {
            return;
        }
        try {
            var serialized = message.getSerializedObject();
            Long index = serialized.getIndex();
            if (index != null) {
                FluxzeroJfr.requestStage(
                        index, "sdk.model-handler", stage, batchSize, index);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must not affect handler execution for custom message implementations.
        }
    }

    private static void releaseGates(
            List<BatchCommitTicket> batch) {
        batch.stream()
                .map(BatchCommitTicket::gates)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(BatchCommitGates::release);
    }

    private void start(
            BatchCommitTicket ticket,
            BatchPrefetch prefetch) {
        CompletableFuture<Object> execution;
        try {
            execution = ticket.context().supply(() -> prefetch == null
                    ? execute(ticket.message(), ticket)
                    : execute(ticket.message(), ticket, prefetch));
        } catch (Throwable executionFailure) {
            ticket.exclude();
            ticket.failInitialization(executionFailure);
            ticket.fail(executionFailure);
            return;
        }
        execution.whenComplete(ticket.context().wrap(
                (result, executionFailure) -> {
                    if (executionFailure == null) {
                        ticket.complete(result);
                    } else {
                        ticket.fail(executionFailure);
                    }
                }));
    }

    private void startAfterDependencies(
            BatchCommitTicket ticket,
            BatchPrefetch prefetch) {
        ticket.initializationPrerequisite()
                .whenComplete((ignored, prerequisiteFailure) -> {
                    if (prerequisiteFailure != null) {
                        ticket.exclude();
                        ticket.failInitialization(
                                prerequisiteFailure);
                        ticket.fail(prerequisiteFailure);
                        return;
                    }
                    CompletableFuture.runAsync(
                                    ticket.context().wrap(
                                            () -> start(
                                                    ticket,
                                                    prefetch)))
                            .exceptionally(failure -> {
                                ticket.exclude();
                                ticket.failInitialization(failure);
                                ticket.fail(failure);
                                return null;
                            });
                });
    }

    private BatchPrefetch prefetchBatch(
            List<BatchCommitTicket> tickets) {
        if (tickets.size() < 2) {
            return null;
        }
        LinkedHashMap<String, PrefetchSlot> models =
                new LinkedHashMap<>();
        PrefetchInput[] resolved = new PrefetchInput[tickets.size()];
        for (int i = 0; i < tickets.size(); i++) {
            resolved[i] = prefetchInput(tickets.get(i).message());
            if (resolved[i] == null) {
                return null;
            }
        }
        for (int i = 0; i < tickets.size(); i++) {
            PrefetchInput input = resolved[i];
            BatchCommitTicket ticket = tickets.get(i);
            ticket.prefetchInput(input);
            /*
             * This fast path has already proven that the command reads and writes one direct model. Assign its
             * ordering wave before parallel evaluation starts, avoiding thousands of contending synchronized target
             * registrations while retaining exact same-model ordering.
             */
            ticket.assignSingle(input.modelId());
            PrefetchSlot slot = models.get(input.modelId());
            if (slot == null) {
                models.put(
                        input.modelId(),
                        new PrefetchSlot(
                                input.modelId(),
                                input.modelType()));
            } else {
                slot.modelType = mergeTargetTypes(
                        slot.modelType,
                        input.modelType());
            }
        }
        if (models.isEmpty()) {
            return null;
        }
        repository.supplyCurrentModels(
                models.values());
        LinkedHashMap<String, ModelTargetResolver.ResolvedModel> misses = null;
        for (PrefetchInput input : resolved) {
            if (models.get(input.modelId()).entity == null) {
                if (misses == null) {
                    misses = new LinkedHashMap<>();
                }
                misses.putIfAbsent(
                        input.modelId(),
                        new ModelTargetResolver.ResolvedModel(
                                input.modelId(),
                                input.modelType(),
                                input.access(),
                                input.sourceProperties()));
            }
        }
        if (misses != null) {
            /*
             * Cache pressure can expose many unrelated cold targets in one tracking batch. Reconstructing all of
             * them in one request retains every decoded stream block and intermediate state until the last target is
             * complete. In a long-running application that turns an ordinary bounded cache eviction into an
             * unbounded recovery wave.
             *
             * This fast path contains independent single-target commands. They do not share one consistency
             * boundary, so load bounded groups and retain only the finalized entities between groups. A command still
             * records the exact state boundary at which its own model was loaded. Hot batches do not enter this loop.
             */
            List<ModelTargetResolver.ResolvedModel> coldTargets =
                    List.copyOf(misses.values());
            for (int offset = 0;
                 offset < coldTargets.size();
                 offset += MAX_COLD_PREFETCH_SIZE) {
                int until = Math.min(
                        coldTargets.size(),
                        offset + MAX_COLD_PREFETCH_SIZE);
                ModelCommitContext loaded =
                        repository.loadContext(
                                new ModelTargetResolver.Resolution(
                                        coldTargets.subList(offset, until),
                                        List.of()),
                                null,
                                Map.of());
                loaded.entries().forEach(entry ->
                        models.get(entry.target().modelId()).set(
                                entry.entity(),
                                loaded.readStateIndex()));
            }
        }
        return new BatchPrefetch(
                java.util.Collections.unmodifiableMap(models));
    }

    private PrefetchInput prefetchInput(DeserializingMessage message) {
        CommitPlan plan = planFor(message.getPayloadClass());
        if (plan.handlers().size() != 1
            || plan.applies().size() != 1
            || plan.applies().getFirst().targetModelTypes().size() != 1) {
            return null;
        }
        ModelTargetResolver.TargetPlan targetPlan =
                targetPlan(
                        message.getPayloadClass(),
                        plan, false);
        if (!targetPlan.isDirectSingleTarget()) {
            return null;
        }
        return new PrefetchInput(
                plan.applies().getFirst(),
                targetPlan.resolveSingleModelId(
                        message.getPayload()),
                targetPlan.singleModelType(),
                targetPlan.singleAccess(),
                targetPlan.singleSourceProperties(),
                plan.directApply());
    }

    private static Class<?> mergeTargetTypes(
            Class<?> firstType,
            Class<?> secondType) {
        if (!firstType.isAssignableFrom(secondType)
            && !secondType.isAssignableFrom(firstType)) {
            throw new IllegalStateException(
                    "One model ID is requested as incompatible types %s and %s"
                            .formatted(
                                    firstType.getName(),
                                    secondType.getName()));
        }
        return firstType.isAssignableFrom(secondType)
                ? secondType : firstType;
    }

    private static boolean planModelDependencies(
            BatchCommitTicket ticket,
            ModelTargetResolver.Resolution resolution,
            Map<String, BatchCommitTicket> lastWriters) {
        boolean dependent = false;
        if (resolution.hasAncestorDependencies()) {
            for (BatchCommitTicket predecessor :
                    new LinkedHashSet<>(lastWriters.values())) {
                if (predecessor.segment == ticket.segment) {
                    ticket.addInitialDependency(predecessor);
                    dependent = true;
                }
            }
        }
        for (ModelTargetResolver.ResolvedModel target :
                resolution.models()) {
            BatchCommitTicket predecessor =
                    lastWriters.get(target.modelId());
            if (predecessor != null) {
                ticket.addInitialDependency(predecessor);
                dependent = true;
            }
            if (target.access().writes()) {
                lastWriters.put(
                        target.modelId(), ticket);
            }
        }
        for (ModelTargetResolver.DeferredWriteTarget target :
                resolution.deferredWrites()) {
            for (String modelId :
                    target.candidateModelIds()) {
                BatchCommitTicket predecessor =
                        lastWriters.put(modelId, ticket);
                if (predecessor != null) {
                    ticket.addInitialDependency(predecessor);
                    dependent = true;
                }
            }
        }
        return dependent;
    }

    private final class HandlerCommitBatch {
        private final List<HandlerCommitTicket> tickets = new ArrayList<>();
        private final ModelCommitter.CommitBatch transportBatch =
                committer.beginReadyBatch();
        private final BatchModelView modelView = new BatchModelView();
        private final Map<String, BatchCommitTicket> lastWriters =
                new HashMap<>();
        private final FluxzeroJfr.Batch jfrEvent = FluxzeroJfr.startBatch(
                "sdk.model-handler", "commit-after-handler", MessageType.COMMAND.name(),
                0, 0L, 0L, 0L);
        private final long createdNanos = jfrEvent == null ? 0L : System.nanoTime();
        private boolean closed;

        synchronized HandlerCommitTicket register(DeserializingMessage message) {
            if (closed) {
                return HandlerCommitTicket.failed(
                        message,
                        new IllegalStateException(
                                "Model handler commit batch was already closed"));
            }
            int slot = tickets.size();
            HandlerCommitTicket ticket = new HandlerCommitTicket(
                    FluxzeroJfr.requestStageEnabled() ? message : null,
                    message,
                    transportBatch, slot,
                    commitPolicyFor(
                            message.getPayloadClass()),
                    modelView);
            planDependencies(ticket);
            tickets.add(ticket);
            recordModelRequestStage(
                    message, "model-commit-registered", tickets.size());
            return ticket;
        }

        private void planDependencies(
                BatchCommitTicket ticket) {
            PrefetchInput direct =
                    prefetchInput(ticket.message());
            if (direct != null) {
                ticket.prefetchInput(direct);
                BatchCommitTicket predecessor =
                        lastWriters.put(
                                direct.modelId(), ticket);
                if (predecessor != null) {
                    ticket.addInitialDependency(predecessor);
                }
                return;
            }
            DeserializingMessage message = ticket.message();
            CommitPlan plan = planFor(
                    message.getPayloadClass());
            ModelTargetResolver.Resolution resolution =
                    targetPlan(
                            message.getPayloadClass(),
                            plan, false)
                            .resolve(message.getPayload());
            planModelDependencies(
                    ticket, resolution,
                    lastWriters);
        }

        void close(Throwable failure) {
            List<HandlerCommitTicket> snapshot;
            synchronized (this) {
                closed = true;
                snapshot = List.copyOf(tickets);
            }
            if (failure != null) {
                if (transportBatch != null) {
                    transportBatch.fail(failure);
                }
                snapshot.forEach(ticket -> ticket.fail(failure));
                finishDiagnostics(snapshot, failure);
                return;
            }
            if (transportBatch != null) {
                transportBatch.flush();
            }
            CompletableFuture<Void> completion =
                    CompletableFuture.allOf(
                            snapshot.stream()
                                    .map(HandlerCommitTicket::execution)
                                    .toArray(CompletableFuture[]::new));
            AsyncCompletionScope.register(
                    completion);
            if (jfrEvent != null || FluxzeroJfr.requestStageEnabled()) {
                completion.whenComplete((ignored, completionFailure) ->
                        finishDiagnostics(snapshot, completionFailure));
            }
        }

        private void finishDiagnostics(
                List<HandlerCommitTicket> snapshot, Throwable failure) {
            if (jfrEvent != null) {
                jfrEvent.itemCount = snapshot.size();
                jfrEvent.outputItemCount = snapshot.size();
                jfrEvent.storageNanos = Math.max(0L, System.nanoTime() - createdNanos);
            }
            snapshot.forEach(ticket -> {
                if (ticket.diagnosticMessage != null) {
                    recordModelRequestStage(
                            ticket.diagnosticMessage, "model-commit-complete", snapshot.size());
                }
            });
            FluxzeroJfr.finish(jfrEvent, failure);
        }
    }

    private static final class HandlerCommitTicket
            extends BatchCommitTicket {
        private final DeserializingMessage diagnosticMessage;
        private final AtomicBoolean started = new AtomicBoolean();

        private HandlerCommitTicket(
                DeserializingMessage diagnosticMessage,
                DeserializingMessage message,
                ModelCommitter.CommitBatch transportBatch,
                int transportSlot,
                ModelCommitPolicy commitPolicy,
                BatchModelView modelView) {
            super(
                    null, message,
                    ThreadLocalContext.capture(),
                    commitPolicy, transportSlot);
            this.diagnosticMessage = diagnosticMessage;
            modelView(modelView);
            transport(transportBatch, transportSlot);
        }

        static HandlerCommitTicket failed(
                DeserializingMessage message, Throwable failure) {
            HandlerCommitTicket ticket = new HandlerCommitTicket(
                    FluxzeroJfr.requestStageEnabled() ? message : null,
                    message, null, -1,
                    ModelCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH,
                    null);
            ticket.fail(failure);
            return ticket;
        }

        CompletableFuture<Object> start(
                Supplier<CompletableFuture<Object>> operation) {
            if (!started.compareAndSet(false, true)) {
                return execution;
            }
            CompletableFuture<Object> startedExecution;
            try {
                startedExecution = Objects.requireNonNull(
                        operation.get(), "Model handler commit returned null");
            } catch (Throwable failure) {
                fail(failure);
                return execution;
            }
            startedExecution.whenComplete((result, failure) -> {
                if (failure == null) {
                    if (diagnosticMessage != null) {
                        recordModelRequestStage(
                                diagnosticMessage, "model-execution-complete", 1);
                    }
                    complete(result);
                } else {
                    fail(failure);
                }
            });
            return execution;
        }

        CompletableFuture<Object> execution() {
            return execution;
        }

        BatchCommitTicket modelTicket() {
            return this;
        }

        @Override
        void fail(Throwable failure) {
            super.fail(failure);
            failInitialization(failure);
        }
    }

    private final class BatchCommitGates {
        private final Map<String, Integer> modelOccurrences =
                new HashMap<>();
        private final Map<Integer, BatchCommitGate> waves =
                new HashMap<>();
        private final List<BatchCommitTicket> tickets =
                new ArrayList<>();
        private int registered;
        private int resolved;
        private boolean closed;
        private boolean released;
        private Throwable failure;
        private final long createdNanos;
        private final FluxzeroJfr.Batch jfrEvent;
        private final AtomicBoolean diagnosticsCompleted = new AtomicBoolean();
        private volatile long backlogStartNanos;
        private volatile long commitsStartedNanos;
        private BatchModelView modelView;

        private BatchCommitGates() {
            jfrEvent = FluxzeroJfr.startBatch(
                    "sdk.model-handler", "commit-wave", MessageType.COMMAND.name(),
                    0, 0L, 0L, 0L);
            createdNanos = timingEnabled() ? System.nanoTime() : 0L;
        }

        synchronized BatchCommitTicket register(
                DeserializingMessage message,
                ModelCommitPolicy commitPolicy) {
            if (closed) {
                return BatchCommitTicket.released(
                        message, commitPolicy);
            }
            registered++;
            BatchCommitTicket ticket =
                    new BatchCommitTicket(
                            this, message,
                            ThreadLocalContext.capture(),
                            commitPolicy,
                            registered - 1);
            tickets.add(ticket);
            recordModelRequestStage(
                    message, "model-commit-registered", registered);
            return ticket;
        }

        synchronized boolean prepareModelView() {
            if (modelView != null) {
                return true;
            }
            Map<String, BatchCommitTicket> lastWriters =
                    new HashMap<>();
            boolean required = false;
            for (BatchCommitTicket ticket : tickets) {
                DeserializingMessage message = ticket.message();
                CommitPlan plan = planFor(
                        message.getPayloadClass());
                ModelTargetResolver.Resolution resolution =
                        targetPlan(
                                message.getPayloadClass(),
                                plan, false)
                                .resolve(message.getPayload());
                required |= planModelDependencies(
                        ticket, resolution,
                        lastWriters);
            }
            if (!required) {
                return false;
            }
            modelView = new BatchModelView();
            tickets.forEach(ticket ->
                    ticket.modelView(modelView));
            return true;
        }

        synchronized BatchCommitGate assign(
                Collection<String> modelIds,
                BatchCommitTicket ticket) {
            List<String> keys = modelIds.stream()
                    .distinct()
                    .toList();
            int wave = keys.stream()
                    .mapToInt(key -> modelOccurrences.getOrDefault(key, 0))
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
            gate.register(ticket);
            resolved++;
            if (failure != null) {
                gate.close(failure);
            }
            closeWavesIfResolved();
            return gate;
        }

        synchronized BatchCommitGate assign(
                String modelId,
                BatchCommitTicket ticket) {
            int wave = modelOccurrences.getOrDefault(modelId, 0);
            modelOccurrences.put(modelId, wave + 1);
            BatchCommitGate gate = waves.computeIfAbsent(
                    wave, ignored -> new BatchCommitGate());
            gate.register(ticket);
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

        void close(Throwable failure) {
            List<BatchCommitTicket> batch;
            synchronized (this) {
                closed = true;
                this.failure = failure;
                batch = List.copyOf(tickets);
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
                }
                if (jfrEvent != null) {
                    jfrEvent.itemCount = registered;
                    jfrEvent.outputItemCount = resolved;
                    jfrEvent.subBatchCount = waves.size();
                }
            }
            if (failure != null) {
                batch.forEach(ticket ->
                                      ticket.fail(failure));
                completeDiagnostics(failure, 0);
                return;
            }
            if (batch.isEmpty()) {
                completeDiagnostics(null, 0);
                return;
            }
            commitBacklog.addAllUntracked(batch);
            AsyncCompletionScope.register(
                    CompletableFuture.allOf(
                            batch.stream()
                                    .map(ticket -> ticket.execution)
                                    .toArray(CompletableFuture[]::new)));
        }

        synchronized void release() {
            released = true;
            closeWavesIfResolved();
        }

        void markBacklogStart(long now) {
            if (!timingEnabled()) {
                return;
            }
            backlogStartNanos = now;
        }

        void markCommitsStarted(long now, long preparationNanos) {
            if (!timingEnabled()) {
                return;
            }
            commitsStartedNanos = now;
            CompletableFuture.allOf(
                            tickets.stream()
                                    .map(ticket -> ticket.execution)
                                    .toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> {
                        long completed = System.nanoTime();
                        if (BATCH_TIMING_DIAGNOSTICS) {
                            System.out.printf(
                                    "SDK model commit wave timing: tickets=%d trackingToBacklog=%.3f ms prepareAndApply=%.3f ms commitAndAfter=%.3f ms total=%.3f ms failure=%s%n",
                                    tickets.size(),
                                    (backlogStartNanos - createdNanos) / 1_000_000.0,
                                    preparationNanos / 1_000_000.0,
                                    (completed - commitsStartedNanos) / 1_000_000.0,
                                    (completed - createdNanos) / 1_000_000.0,
                                    failure == null ? "none" : failure.getClass().getSimpleName());
                        }
                        if (jfrEvent != null) {
                            jfrEvent.queueWaitNanos = Math.max(0L, backlogStartNanos - createdNanos);
                            jfrEvent.preparationNanos = preparationNanos;
                            jfrEvent.storageNanos = Math.max(0L, completed - commitsStartedNanos);
                        }
                        completeDiagnostics(failure, tickets.size());
                    });
        }

        private boolean timingEnabled() {
            return BATCH_TIMING_DIAGNOSTICS || jfrEvent != null;
        }

        private void completeDiagnostics(Throwable failure, int batchSize) {
            if (!diagnosticsCompleted.compareAndSet(false, true)) {
                return;
            }
            tickets.forEach(ticket ->
                    recordModelRequestStage(
                            ticket.message(), "model-commit-complete", batchSize));
            FluxzeroJfr.finish(jfrEvent, failure);
        }

        private synchronized void closeWavesIfResolved() {
            if (!closed
                || !released
                || failure != null
                || resolved != registered) {
                return;
            }
            List<BatchCommitGate> orderedWaves =
                    waves.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(Map.Entry::getValue)
                            .toList();
            closeWave(orderedWaves, 0);
        }

        private void closeWave(
                List<BatchCommitGate> orderedWaves,
                int index) {
            if (index >= orderedWaves.size()) {
                return;
            }
            orderedWaves.get(index).close(
                    null,
                    () -> closeWave(
                            orderedWaves, index + 1));
        }
    }

    private final class BatchCommitGate {
        private final List<BatchCommitTicket> tickets =
                new ArrayList<>();
        private final AtomicInteger arrived =
                new AtomicInteger();
        private final AtomicBoolean dispatched =
                new AtomicBoolean();
        private volatile boolean closed;
        private volatile Throwable failure;
        private volatile Runnable afterStart;

        void register(BatchCommitTicket ticket) {
            tickets.add(ticket);
        }

        <T> CompletableFuture<T> submit(
                BatchCommitTicket ticket,
                Supplier<CompletableFuture<T>> operation,
                ModelCommitter.CommitBatch transportBatch,
                int transportSlot) {
            PendingCommit<T> commit =
                    new PendingCommit<>(
                            operation,
                            transportBatch,
                            transportSlot,
                            ticket.commitPolicy().async());
            ticket.pendingCommit(commit);
            arrived.incrementAndGet();
            if (readyToDispatch()) {
                dispatch();
            }
            return commit.result();
        }

        void close(Throwable failure) {
            close(failure, () -> {
            });
        }

        void close(
                Throwable failure,
                Runnable afterStart) {
            this.failure = failure;
            this.afterStart = afterStart;
            closed = true;
            if (readyToDispatch()) {
                dispatch();
            } else if (BATCH_DIAGNOSTICS) {
                System.out.printf(
                        "SDK model commit gate waiting: expected=%d arrived=%d%n",
                        tickets.size(), arrived.get());
            }
        }

        private boolean readyToDispatch() {
            return closed
                   && arrived.get() == tickets.size()
                   && dispatched.compareAndSet(false, true);
        }

        private int expected() {
            return tickets.size();
        }

        private void dispatch() {
            List<PendingCommit<?>> batch =
                    new ArrayList<>(tickets.size());
            for (BatchCommitTicket ticket : tickets) {
                PendingCommit<?> pending =
                        ticket.pendingCommit();
                if (pending == null) {
                    throw new IllegalStateException(
                            "Model commit gate dispatched before every registered ticket arrived");
                }
                batch.add(pending);
            }
            Throwable batchFailure = failure;
            Runnable completion = afterStart;
            if (BATCH_DIAGNOSTICS) {
                System.out.printf(
                        "SDK model commit gate: expected=%d%n",
                        tickets.size());
            }
            if (batchFailure != null) {
                batch.forEach(commit -> {
                    commit.fail(batchFailure);
                    commit.producerDone();
                });
                completion.run();
                return;
            }
            if (batch.stream().anyMatch(commit -> !commit.concurrent())) {
                startSequentially(batch, 0, completion);
                return;
            }
            int workers = Math.min(
                    Runtime.getRuntime().availableProcessors(),
                    Math.max(1, (batch.size() + 255) / 256));
            if (workers == 1) {
                for (int index = 0;
                     index < batch.size(); index++) {
                    start(batch.get(index));
                }
                completion.run();
                return;
            }
            int chunkSize = (batch.size() + workers - 1) / workers;
            CompletableFuture<?>[] starts =
                    new CompletableFuture<?>[workers];
            for (int worker = 0; worker < workers; worker++) {
                int from = worker * chunkSize;
                int until = Math.min(
                        batch.size(), from + chunkSize);
                starts[worker] = CompletableFuture.runAsync(
                        () -> {
                            for (int index = from;
                                 index < until;
                                 index++) {
                                PendingCommit<?> commit =
                                        batch.get(index);
                                start(commit);
                            }
                        });
            }
            CompletableFuture.allOf(starts)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            batch.getFirst()
                                    .transportBatch()
                                    .fail(failure);
                            batch.forEach(commit ->
                                                  commit.fail(failure));
                        }
                        completion.run();
                    });
        }

        private void start(
                PendingCommit<?> commit) {
            try {
                commit.start();
            } finally {
                commit.producerDone();
            }
        }

        private void startSequentially(
                List<PendingCommit<?>> commits,
                int index,
                Runnable completion) {
            if (index >= commits.size()) {
                completion.run();
                return;
            }
            PendingCommit<?> commit = commits.get(index);
            start(commit);
            commit.result().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    startSequentially(commits, index + 1, completion);
                    return;
                }
                for (int remaining = index + 1; remaining < commits.size(); remaining++) {
                    PendingCommit<?> skipped = commits.get(remaining);
                    skipped.fail(failure);
                    skipped.producerDone();
                }
                completion.run();
            });
        }
    }

    private record PendingCommit<T>(
            Supplier<CompletableFuture<T>> operation,
            CompletableFuture<T> result,
            ModelCommitter.CommitBatch transportBatch,
            int transportSlot,
            boolean concurrent) {
        private PendingCommit(
                Supplier<CompletableFuture<T>> operation,
                ModelCommitter.CommitBatch transportBatch,
                int transportSlot,
                boolean concurrent) {
            this(operation, new CompletableFuture<>(),
                 transportBatch, transportSlot, concurrent);
        }

        private void producerDone() {
            if (transportBatch != null) {
                transportBatch.producerDone();
            }
        }

        private void start() {
            CompletableFuture<T> execution;
            try {
                execution = Objects.requireNonNull(
                        operation.get(),
                        "Batched model commit returned null");
            } catch (Throwable startFailure) {
                result.completeExceptionally(startFailure);
                return;
            }
            execution.whenComplete((value, executionFailure) -> {
                if (executionFailure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(executionFailure);
                }
            });
        }

        private void fail(
                Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private static class BatchCommitTicket {
        private final BatchCommitGates gates;
        private final DeserializingMessage message;
        private final ThreadLocalContext.Snapshot context;
        private final ModelCommitPolicy commitPolicy;
        private final int ordinal;
        private final int segment;
        protected final CompletableFuture<Object> execution =
                new CompletableFuture<>();
        private volatile CompletableFuture<Void> initialization;
        private List<BatchCommitTicket> initialDependencies;
        private volatile Set<BatchCommitTicket> batchDependencies;
        private boolean initializationFinished;
        private Throwable initializationFailure;
        private final AtomicBoolean resolved;
        private final AtomicBoolean arrived;
        private boolean transportProducerDone;
        private volatile BatchCommitGate gate;
        private volatile PendingCommit<?> pendingCommit;
        private volatile PrefetchInput prefetchInput;
        private volatile ModelCommitter.CommitBatch transportBatch;
        private volatile int transportSlot = -1;
        private volatile BatchModelView modelView;

        protected BatchCommitTicket(
                BatchCommitGates gates,
                DeserializingMessage message,
                ThreadLocalContext.Snapshot context,
                ModelCommitPolicy commitPolicy,
                int ordinal) {
            this.gates = gates;
            this.message = message;
            this.context = context;
            this.commitPolicy = commitPolicy;
            this.ordinal = ordinal;
            this.resolved = gates == null
                    ? null : new AtomicBoolean();
            this.arrived = gates == null
                    ? null : new AtomicBoolean();
            this.segment = Optional.ofNullable(
                            message.getSerializedObject()
                                    .getSegment())
                    .orElse(-1);
        }

        static BatchCommitTicket released(
                DeserializingMessage message,
                ModelCommitPolicy commitPolicy) {
            return new BatchCommitTicket(
                    null, message,
                    message.captureContext(),
                    commitPolicy, -1);
        }

        DeserializingMessage message() {
            return message;
        }

        BatchCommitGates gates() {
            return gates;
        }

        ThreadLocalContext.Snapshot context() {
            return context;
        }

        ModelCommitPolicy commitPolicy() {
            return commitPolicy;
        }

        PrefetchInput prefetchInput() {
            return prefetchInput;
        }

        void prefetchInput(PrefetchInput input) {
            this.prefetchInput = input;
        }

        void transport(
                ModelCommitter.CommitBatch batch,
                int slot) {
            transportBatch = batch;
            transportSlot = slot;
        }

        ModelCommitter.CommitBatch transportBatch() {
            return transportBatch;
        }

        int transportSlot() {
            return transportSlot;
        }

        void modelView(BatchModelView modelView) {
            this.modelView = modelView;
        }

        boolean hasBatchModelView() {
            return modelView != null
                   && hasBatchDependencies();
        }

        synchronized void addInitialDependency(
                BatchCommitTicket predecessor) {
            if (predecessor == this
                || initialDependencies != null
                   && initialDependencies.contains(predecessor)) {
                return;
            }
            if (initialDependencies == null) {
                initialDependencies = new ArrayList<>(1);
            }
            initialDependencies.add(predecessor);
            dependencies().add(predecessor);
        }

        void addBatchDependency(
                BatchCommitTicket predecessor) {
            if (predecessor != this) {
                dependencies().add(predecessor);
            }
        }

        boolean hasBatchDependencies() {
            Set<BatchCommitTicket> dependencies = batchDependencies;
            return dependencies != null
                   && !dependencies.isEmpty();
        }

        synchronized CompletableFuture<Void> initializationPrerequisite() {
            if (initialDependencies == null) {
                return COMPLETED_VOID;
            }
            return CompletableFuture.allOf(
                    initialDependencies.stream()
                            .map(BatchCommitTicket::initialization)
                            .toArray(CompletableFuture[]::new));
        }

        synchronized CompletableFuture<Void> initialization() {
            CompletableFuture<Void> future = initialization;
            if (future == null) {
                future = new CompletableFuture<>();
                initialization = future;
                if (initializationFinished) {
                    if (initializationFailure == null) {
                        future.complete(null);
                    } else {
                        future.completeExceptionally(
                                initializationFailure);
                    }
                }
            }
            return future;
        }

        synchronized boolean initializationDone() {
            return initializationFinished;
        }

        synchronized void completeInitialization() {
            finishInitialization(null);
        }

        synchronized void failInitialization(Throwable failure) {
            finishInitialization(failure);
        }

        CompletableFuture<Void> dependencyCompletion() {
            Set<BatchCommitTicket> dependencies = batchDependencies;
            if (dependencies == null
                || dependencies.isEmpty()) {
                return COMPLETED_VOID;
            }
            return CompletableFuture.allOf(
                    dependencies.stream()
                            .map(ticket -> ticket.execution)
                            .toArray(CompletableFuture[]::new));
        }

        private Set<BatchCommitTicket> dependencies() {
            Set<BatchCommitTicket> dependencies = batchDependencies;
            if (dependencies != null) {
                return dependencies;
            }
            synchronized (this) {
                dependencies = batchDependencies;
                if (dependencies == null) {
                    dependencies = ConcurrentHashMap.newKeySet();
                    batchDependencies = dependencies;
                }
                return dependencies;
            }
        }

        private void finishInitialization(Throwable failure) {
            if (initializationFinished) {
                return;
            }
            initializationFinished = true;
            initializationFailure = failure;
            CompletableFuture<Void> future = initialization;
            if (future != null) {
                if (failure == null) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(failure);
                }
            }
        }

        void stage(ModelCommitEngine.CommitEvaluation evaluation) {
            BatchModelView view = modelView;
            if (view != null) {
                view.stage(this, evaluation);
            }
        }

        Map<String, Object> batchValues(
                ModelTargetResolver.Resolution requestedResolution) {
            BatchModelView view = modelView;
            return view == null
                    ? Map.of()
                    : view.valuesFor(this, requestedResolution);
        }

        Map<String, Object> batchValues(
                ModelCommitContext context) {
            BatchModelView view = modelView;
            return view == null
                    ? Map.of()
                    : view.valuesFor(this, context);
        }

        CompletableFuture<Object> execute() {
            if (gates == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Model commit batch was already closed before handler invocation"));
            }
            return execution;
        }

        void complete(Object result) {
            execution.complete(result);
        }

        void fail(Throwable failure) {
            execution.completeExceptionally(failure);
        }

        void assign(
                Collection<String> modelIds) {
            if (gates != null
                && resolved.compareAndSet(
                        false, true)) {
                gate = gates.assign(
                        modelIds, this);
            }
        }

        void assignSingle(String modelId) {
            if (gates != null
                && resolved.compareAndSet(
                        false, true)) {
                gate = gates.assign(modelId, this);
            }
        }

        void exclude() {
            if (gates != null
                && resolved.compareAndSet(
                        false, true)) {
                gates.exclude();
                producerDone();
            }
        }

        <T> CompletableFuture<T> executeAfterRelease(
                Supplier<CompletableFuture<T>> operation) {
            if (gates == null) {
                try {
                    return operation.get();
                } catch (Throwable failure) {
                    return CompletableFuture.failedFuture(
                            failure);
                }
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
            return assigned.submit(
                    this,
                    operation,
                    transportBatch,
                    transportSlot);
        }

        void pendingCommit(PendingCommit<?> pendingCommit) {
            this.pendingCommit = Objects.requireNonNull(
                    pendingCommit);
        }

        PendingCommit<?> pendingCommit() {
            return pendingCommit;
        }

        private synchronized void producerDone() {
            ModelCommitter.CommitBatch batch = transportBatch;
            if (batch != null
                && !transportProducerDone) {
                transportProducerDone = true;
                batch.producerDone();
            }
        }

        void detachTransport() {
            producerDone();
            transportBatch = null;
            transportSlot = -1;
        }
    }

    /**
     * Speculative read-your-writes view for one tracking batch. Independent commands only publish their staged state;
     * lookups enter this view when dependency planning proves an overlap. Durable completion and conflict handling
     * remain owned by the regular coordinator.
     */
    private static final class BatchModelView {
        private final Map<String, StagedBatchModel> staged =
                new ConcurrentHashMap<>();

        void stage(
                BatchCommitTicket ticket,
                ModelCommitEngine.CommitEvaluation evaluation) {
            evaluation.finalValues().forEach((modelId, value) ->
            {
                Class<?> modelType =
                        evaluation.readModelTypes().get(modelId);
                if (modelType == null) {
                    modelType = value == null
                            ? Object.class
                            : value.getClass();
                }
                Class<?> stagedModelType = modelType;
                staged.compute(
                        modelId,
                        (ignored, previous) ->
                                new StagedBatchModel(
                                        ticket,
                                        stagedModelType,
                                        value,
                                        previous));
            });
        }

        Map<String, Object> valuesFor(
                BatchCommitTicket ticket,
                ModelTargetResolver.Resolution resolution) {
            if (staged.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Object> result =
                    new LinkedHashMap<>();
            List<String> pending = new ArrayList<>();
            resolution.models().forEach(target ->
                    pending.add(target.modelId()));
            if (resolution.hasAncestorDependencies()) {
                staged.forEach((modelId, candidate) -> {
                    StagedBatchModel visible =
                            visibleCandidate(
                                    ticket, candidate,
                                    ticket.segment);
                    if (visible != null
                        && matchesAncestorType(
                                visible.modelType(),
                                resolution)) {
                        result.put(modelId, visible.value());
                        ticket.addBatchDependency(visible.producer());
                    }
                });
            }
            for (int index = 0; index < pending.size(); index++) {
                String modelId = pending.get(index);
                StagedBatchModel candidate =
                        visibleCandidate(
                                ticket,
                                staged.get(modelId),
                                null);
                if (candidate == null
                    || result.containsKey(modelId)) {
                    continue;
                }
                result.put(modelId, candidate.value());
                ticket.addBatchDependency(candidate.producer());
                Object value = candidate.value();
                if (value == null) {
                    continue;
                }
                for (ModelMetadata.ParentReference parent :
                        ModelMetadata.validate(value.getClass())
                                .parentReferences()) {
                    Object parentId = parent.read(value);
                    if (parentId != null) {
                        pending.add(parentId.toString());
                    }
                }
            }
            return immutable(result);
        }

        Map<String, Object> valuesFor(
                BatchCommitTicket ticket,
                ModelCommitContext context) {
            if (staged.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Object> result =
                    new LinkedHashMap<>();
            context.entries().forEach(entry -> {
                String modelId = entry.target().modelId();
                StagedBatchModel candidate =
                        visibleCandidate(
                                ticket,
                                staged.get(modelId),
                                null);
                if (candidate != null) {
                    result.put(modelId, candidate.value());
                    ticket.addBatchDependency(candidate.producer());
                }
            });
            return immutable(result);
        }

        private static StagedBatchModel visibleCandidate(
                BatchCommitTicket ticket,
                StagedBatchModel candidate,
                Integer requiredSegment) {
            while (candidate != null) {
                BatchCommitTicket producer =
                        candidate.producer();
                if (producer.ordinal < ticket.ordinal
                    && (requiredSegment == null
                        || producer.segment == requiredSegment)) {
                    return candidate;
                }
                candidate = candidate.previous();
            }
            return null;
        }

        private static boolean matchesAncestorType(
                Class<?> modelType,
                ModelTargetResolver.Resolution resolution) {
            return resolution.ancestorDependencies().stream()
                    .map(ModelTargetResolver.AncestorDependency::modelType)
                    .anyMatch(required ->
                            required.isAssignableFrom(modelType)
                            || modelType.isAssignableFrom(required));
        }

        private static Map<String, Object> immutable(
                LinkedHashMap<String, Object> values) {
            return values.isEmpty()
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(values);
        }
    }

    private record StagedBatchModel(
            BatchCommitTicket producer,
            Class<?> modelType,
            Object value,
            StagedBatchModel previous) {
    }

    private record BatchPrefetch(
            Map<String, PrefetchSlot> models) {
    }

    private record PrefetchInput(
            ModelMetadata.HandlerMethod handler,
            String modelId,
            Class<?> modelType,
            ModelTargetResolver.Access access,
            List<String> sourceProperties,
            ModelCommitEngine.DirectSingleTargetApply directApply) {
    }

    private static final class PrefetchSlot
            implements DefaultModelRepository.CurrentModelLookup {
        private final String modelId;
        private Class<?> modelType;
        private Entity<?> entity;
        private long stateIndex;

        private PrefetchSlot(
                String modelId,
                Class<?> modelType) {
            this.modelId = modelId;
            this.modelType = modelType;
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
            set(entity, validThrough);
        }

        private void set(Entity<?> entity, long stateIndex) {
            this.entity = entity;
            this.stateIndex = stateIndex;
        }
    }

    @Override
    public void close() {
        commitBacklog.shutDown();
        committer.close();
    }
}
