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
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository.Commit;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.Tracker;
import lombok.extern.slf4j.Slf4j;

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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single lifecycle owner for independent-model evaluation and commits.
 *
 * <p>Every automatic, explicit, graph, collection and retry request enters this pipeline. Payload-specific behavior is
 * supplied by immutable {@link MutationPlan model definitions}; batch-local ordering is delegated exclusively to
 * {@link ModelBatchScope}; and the repository owns wire preparation and authoritative commit completion.</p>
 */
@Slf4j
final class ModelPipeline {
    private static final CompletableFuture<Void> COMPLETED_VOID =
            CompletableFuture.completedFuture(null);

    private final DefaultModelRepository repository;
    private final Commit repositoryCommit;
    private final ModelConflictPolicy conflictPolicy;
    private final ModelConflictResolver conflictResolver;
    private final int maxConflictRetries;
    private final GraphProjectionCompletion graphProjectionCompletion;
    private final ModelBatchScope.BatchLifecycle batchLifecycle;
    private final boolean awaitAfterHandlerCommitsBeforeResults;
    private final Serializer serializer;
    private final EventStoreClient eventStoreClient;
    private final Function<Class<?>, MutationPlan> definitions;
    private final java.util.function.BooleanSupplier localHandlingEnabled;
    private final ConcurrentHashMap<Class<?>, CompletableFuture<ModelGraphProjectionStatus>>
            graphProjectionRegistrations = new ConcurrentHashMap<>();

    ModelPipeline(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            Serializer serializer,
            Serializer snapshotSerializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxConflictRetries,
            GraphProjectionCompletion graphProjectionCompletion,
            Function<Class<?>, MutationPlan> definitions,
            java.util.function.BooleanSupplier localHandlingEnabled) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient, "eventStoreClient");
        this.conflictPolicy = ModelConflictPolicy.resolve(conflictPolicy);
        this.conflictResolver = Objects.requireNonNull(conflictResolver, "conflictResolver");
        if (maxConflictRetries < 0) {
            throw new IllegalArgumentException("Maximum model conflict retries must not be negative");
        }
        this.maxConflictRetries = maxConflictRetries;
        this.graphProjectionCompletion =
                graphProjectionCompletion == GraphProjectionCompletion.DEFAULT
                        ? GraphProjectionCompletion.ASYNC
                        : Objects.requireNonNull(graphProjectionCompletion, "graphProjectionCompletion");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.localHandlingEnabled = Objects.requireNonNull(localHandlingEnabled, "localHandlingEnabled");
        this.awaitAfterHandlerCommitsBeforeResults =
                io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty(
                        ModelCommitPolicy.AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY, true);
        this.repositoryCommit = repository.new Commit(
                eventStoreClient, serializer, documentSerializer,
                eventDispatchInterceptor, source, snapshotSerializer);
        this.batchLifecycle = new ModelBatchScope.BatchLifecycle(
                repositoryCommit::beginReadyBatch, repositoryCommit::beginBatch,
                () -> awaitAfterHandlerCommitsBeforeResults);
    }

    Handler<DeserializingMessage> handler(Class<?> trackingTarget) {
        return new CommitHandler(trackingTarget);
    }

    void registerGraphProjection(ModelGraphProjections.Root root) {
        doRegisterGraphProjection(root);
    }

    private boolean canAutomaticallyHandle(DeserializingMessage message) {
        return message.getMessageType() == MessageType.COMMAND
               && definitionFor(message.getPayloadClass()).automatic();
    }

    private ModelCommitPolicy commitPolicyFor(Class<?> payloadType) {
        return definitionFor(payloadType).commitPolicy();
    }

    private boolean hasModelApplies(Class<?> payloadType) {
        return definitionFor(payloadType).commit();
    }

    private MutationPlan definitionFor(Class<?> payloadType) {
        return definitions.apply(payloadType);
    }

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
                repositoryCommit.beginReadyBatch();
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
                    message, transportBatch, transportSlot, true, true,
                    this::evaluateLive)
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
                    message, null, -1, true, false,
                    this::evaluateAssertions)
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
                    message, null, -1, false, false,
                    this::evaluateStored).thenApply(ignored -> null);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Object> evaluateExplicit(
            DeserializingMessage message,
            ModelCommitBatchingClient.ModelCommitBatch transport,
            int slot,
            boolean skipEmpty,
            boolean warnMissingApply,
            Evaluation evaluation) {
        return execute(new ExecutionRequest(
                message, transport, slot, skipEmpty, warnMissingApply, false),
                       null, evaluation);
    }

    private CompletableFuture<Object> execute(
            ExecutionRequest request,
            ModelCommitPolicy policy,
            Evaluation evaluator) {
        CommitAttempt attempt = ModelBatchScope.register(
                this, request.message(), policy, batchLifecycle);
        ThreadLocalContext.Snapshot context = request.message().captureContext();
        boolean asynchronousReevaluation = !localHandlingEnabled.getAsBoolean();
        CommitAttempt initial;
        try {
            initial = context.supply(() -> ModelBatchScope.withDependency(
                    attempt, () -> evaluator.evaluate(
                            request, attempt, false, attempt.batched())));
            if (initial != attempt) {
                throw new IllegalStateException("Model evaluation replaced its commit attempt");
            }
            warnEmptyExplicitApply(request, initial);
            ModelBatchScope.stage(
                    ModelBatchScope.namespace(request.message()), initial, true);
            attempt.initialize(initial.readModelIds());
        } catch (Throwable failure) {
            attempt.fail(failure);
            return attempt.completion();
        }
        try {
            if (attempt.hasDependencies()) {
                if (attempt.batched() && !attempt.policy().commitAfterBatch()
                    && attempt.transportBatch() != null) {
                    attempt.flushTransport();
                }
                attempt.detachTransport();
            }
            attempt.submitAfterRelease(dependent -> {
                CompletableFuture<CommitAttempt> ready = dependent
                        ? reevaluate(
                                attempt, context, request, evaluator,
                                asynchronousReevaluation)
                        : CompletableFuture.completedFuture(initial);
                return ready.thenCompose(context.wrap(evaluation -> {
                    if (request.skipEmpty() && evaluation.transitions().isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    ModelCommitBatchingClient.ModelCommitBatch batch =
                            attempt.transportBatch();
                    return executeEvaluation(
                            request.message(), evaluation,
                            batch == null ? request.transport() : batch,
                            batch == null ? request.transportSlot() : attempt.transportSlot());
                }));
            });
        } catch (Throwable failure) {
            attempt.fail(failure);
        }
        return attempt.completion();
    }

    private CompletableFuture<CommitAttempt> reevaluate(
            CommitAttempt attempt,
            ThreadLocalContext.Snapshot context,
            ExecutionRequest request,
            Evaluation evaluator,
            boolean asynchronous) {
        int dependencyCount = attempt.dependencyCount();
        Supplier<CommitAttempt> evaluation = () -> context.supply(() ->
                ModelBatchScope.withDependency(attempt, () ->
                        evaluator.evaluate(request, attempt, true, attempt.batched())));
        CompletableFuture<CommitAttempt> result =
                attempt.batched() && asynchronous
                        ? attempt.dependencyCompletion().thenCompose(ignored ->
                                CompletableFuture.supplyAsync(context.wrap(evaluation)))
                        : attempt.dependencyCompletion().thenApply(ignored -> evaluation.get());
        return result.thenCompose(value ->
                attempt.dependencyCount() == dependencyCount
                        ? CompletableFuture.completedFuture(value)
                        : reevaluate(
                                attempt, context, request, evaluator,
                                asynchronous));
    }

    private CommitAttempt evaluateLive(
            ExecutionRequest request, CommitAttempt attempt,
            boolean retry, boolean batched) {
        if (request.directLoadsInBatch()) {
            return !retry || batched
                    ? evaluate(attempt, request.message())
                    : evaluate(attempt, request.message(), null);
        }
        return DeserializingMessage.getMessageBatchIndex() < 0
                ? evaluate(attempt, request.message())
                : evaluate(attempt, request.message(), null);
    }

    private CommitAttempt evaluateAssertions(
            ExecutionRequest request, CommitAttempt attempt,
            boolean retry, boolean batched) {
        return ModelReducer.assertLegal(
                attempt, request.message(), new CommitLoader(null));
    }

    private CommitAttempt evaluateStored(
            ExecutionRequest request, CommitAttempt attempt,
            boolean retry, boolean batched) {
        return ModelReducer.reapply(
                attempt, List.of(request.message()), new CommitLoader(null, true));
    }

    private void warnEmptyExplicitApply(
            ExecutionRequest request,
            CommitAttempt evaluation) {
        if (request.warnMissingApply()
            && !hasModelApplies(request.message().getPayloadClass())
            && evaluation.transitions().isEmpty()
            && evaluation.stepCount() > 0) {
            log.warn(
                    "Fluxzero.assertAndApply({}) ran model interceptors and assertions, but this application has no "
                    + "locally reachable model @Apply handler. No model changes were committed.",
                    request.message().getPayloadClass().getName());
        }
    }

    private record ExecutionRequest(
            DeserializingMessage message,
            ModelCommitBatchingClient.ModelCommitBatch transport,
            int transportSlot,
            boolean skipEmpty,
            boolean warnMissingApply,
            boolean directLoadsInBatch) {
    }

    @FunctionalInterface
    private interface Evaluation {
        CommitAttempt evaluate(
                ExecutionRequest request, CommitAttempt attempt,
                boolean retry, boolean batched);
    }

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
        CompletableFuture<CommitAttempt> reevaluate(
                CommitModelsResult result,
                CommitAttempt current,
                Commit.Outcome original);
    }

    private CompletableFuture<Object> executeEvaluation(
            DeserializingMessage message,
            CommitAttempt evaluation,
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
            CommitAttempt evaluation,
            ModelConflictPolicy effectiveConflictPolicy,
            Map<String, Set<String>> awaitedGraphProjections,
            ModelCommitBatchingClient.ModelCommitBatch transportBatch,
            int transportSlot) {
        Retry retry = effectiveConflictPolicy == ModelConflictPolicy.ACCEPT
                ? Retry.accepting((result, current, original) -> {
                    try {
                        return CompletableFuture.completedFuture(
                                rebase(original.attempt().rebaseMessages(), result.getRebaseStateIndex()));
                    } catch (Throwable failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                })
                : Retry.conflicts(
                        conflictResolver, maxConflictRetries,
                        (conflict, current, original) -> reload(message, current, conflict));
        CompletableFuture<Optional<CommitModelsResult>> committed =
                repositoryCommit.trackLocalCommit(
                        evaluation,
                        () -> commit(
                                repositoryCommit, message.getMessageId(), evaluation,
                                effectiveConflictPolicy, retry,
                                transportBatch, transportSlot));
        CompletableFuture<Optional<CommitModelsResult>> completed =
                awaitedGraphProjections.isEmpty()
                        ? committed
                        : committed.thenCompose(commitResult ->
                                awaitGraphProjections(commitResult, awaitedGraphProjections));
        return completed.handle((commitResult, failure) ->
                finishEvaluation(evaluation, effectiveConflictPolicy, failure));
    }

    static CompletableFuture<Optional<CommitModelsResult>> commit(
            Commit repositoryCommit,
            String commitId,
            CommitAttempt evaluation,
            ModelConflictPolicy conflictPolicy,
            Retry retry,
            ModelCommitBatchingClient.ModelCommitBatch batch,
            int batchSlot) {
        Objects.requireNonNull(retry, "retry");
        Commit.Outcome original = repositoryCommit.prepare(
                commitId, evaluation, conflictPolicy);
        return commit(
                repositoryCommit, commitId, evaluation, conflictPolicy,
                original, original, retry,
                ThreadLocalContext.capture(), 0, batch, batchSlot);
    }

    private static CompletableFuture<Optional<CommitModelsResult>> commit(
            Commit repositoryCommit,
            String commitId,
            CommitAttempt evaluation,
            ModelConflictPolicy conflictPolicy,
            Commit.Outcome original,
            Commit.Outcome prepared,
            Retry retry,
            ThreadLocalContext.Snapshot context,
            int attempts,
            ModelCommitBatchingClient.ModelCommitBatch batch,
            int batchSlot) {
        return repositoryCommit.commitPrepared(prepared, batch, batchSlot)
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
                                Commit.Outcome nextPrepared =
                                        retry.accepting()
                                        && !original.attempt().hasCascadedDeletion()
                                                ? repositoryCommit.prepareRebased(commitId, original, next)
                                                : repositoryCommit.prepare(commitId, next, conflictPolicy);
                                return commit(
                                        repositoryCommit, commitId, next, conflictPolicy,
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
            CommitAttempt evaluation,
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
            CommitAttempt evaluation) {
        return awaitedGraphProjectionTargets(
                evaluation).keySet();
    }

    Map<String, Set<String>> awaitedGraphProjectionTargets(
            CommitAttempt evaluation) {
        GraphProjectionCompletion consumer = null;
        LinkedHashMap<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        for (Change transition :
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
            CommitAttempt evaluation) {
        LinkedHashSet<ModelGraphProjections.Root> roots = null;
        for (Change transition :
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
        roots.forEach(this::doRegisterGraphProjection);
        return CompletableFuture.allOf(
                roots.stream()
                        .map(ModelGraphProjections.Root::modelType)
                        .distinct()
                        .map(graphProjectionRegistrations::get)
                        .filter(Objects::nonNull)
                        .toArray(CompletableFuture[]::new));
    }

    private void doRegisterGraphProjection(
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

    private CompletableFuture<CommitAttempt> reload(
            DeserializingMessage message,
            CommitAttempt staleEvaluation,
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
                                    ModelReducer.apply(
                                            List.of(message),
                                            new CommitLoader(retryStateIndex)))));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static long retryStateIndex(
            CommitAttempt evaluation,
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

    private CommitAttempt evaluate(
            CommitAttempt attempt,
            DeserializingMessage initialMessage) {
        PrefetchSlot cached = initialMessage.getContext(ExplicitModelTarget.class).isPresent()
                ? null : prefetch(initialMessage);
        if (cached != null) {
            if (repository.supplyCurrentModel(
                    cached.modelId, cached.modelType, cached)) {
                return evaluate(attempt, initialMessage, cached);
            }
        }
        return evaluate(attempt, initialMessage, null);
    }

    private CommitAttempt evaluate(
            CommitAttempt attempt,
            DeserializingMessage initialMessage,
            PrefetchSlot prefetched) {
        return expandCascadeDeletes(
                ModelReducer.apply(
                        attempt, List.of(initialMessage),
                        new CommitLoader(null, false, initialMessage, prefetched)));
    }

    private CommitAttempt rebase(
            List<DeserializingMessage> messages,
            long stateIndex) {
        return ModelBatchScope.withMessageDependency(
                messages.getFirst(),
                () -> expandCascadeDeletes(ModelReducer.reapply(
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
    private CommitAttempt expandCascadeDeletes(
            CommitAttempt evaluation) {
        LinkedHashSet<String> explicitlyDeleted = null;
        LinkedHashMap<String, Change> latestTransitions =
                new LinkedHashMap<>();
        for (int step = 0; step < evaluation.stepCount(); step++) {
            for (Change transition : evaluation.stepChanges(step)) {
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
                EntityMetadata metadata =
                        EntityMetadata.validate(node.modelType());
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
            evaluation.cascadeRoots(explicitlyDeleted);
            return evaluation;
        }

        List<Change> transitions = cascaded.stream()
                .map(nodes::get)
                .filter(Objects::nonNull)
                .map(node -> Change.applied(
                        node.modelId(), node.modelType(),
                        node.sequenceNumber(), node.lastEventIndex(),
                        node.value(), null, null, null, true))
                .toList();
        DeserializingMessage source =
                evaluation.stepMessage(0);
        DeserializingMessage cascadeMessage = source.withMessage(
                new Message(
                        new CascadedModelDeletion(
                                List.copyOf(explicitlyDeleted)),
                        source.getMetadata(), null,
                        source.getTimestamp()));
        List<DeserializingMessage> messages =
                new ArrayList<>(evaluation.stepMessages());
        messages.add(cascadeMessage);
        List<List<Change>> changesByStep =
                new ArrayList<>(evaluation.changesByStep());
        changesByStep.add(transitions);
        LinkedHashSet<String> readModelIds =
                new LinkedHashSet<>(evaluation.readModelIds());
        Map<String, Class<?>> readModelTypes =
                new LinkedHashMap<>(evaluation.readModelTypes());
        transitions.forEach(transition -> {
            readModelIds.add(transition.modelId());
            readModelTypes.putIfAbsent(
                    transition.modelId(), transition.modelType());
        });
        evaluation.evaluated(
                evaluation.readStateIndex(), readModelIds,
                readModelTypes, messages, changesByStep);
        evaluation.cascadeRoots(explicitlyDeleted);
        return evaluation;
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
            EntityMetadata metadata,
            Object value,
            Set<String> deleted) {
        for (EntityMetadata.ParentReference parent :
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
            CommitAttempt evaluation,
            Map<String, Change> latestTransitions,
            Map<String, CascadeNode> nodes) {
        evaluation.finalValues().forEach((modelId, value) -> {
            if (value == null) {
                return;
            }
            Change transition =
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
            CommitAttempt evaluation,
            Map<String, Change> transitions) {
        Change transition =
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

    private final class CommitLoader implements ModelReducer.SubstepResolver {
        private final Long pinnedStateIndex;
        private final boolean applyOnly;
        private final DeserializingMessage directMessage;
        private final PrefetchSlot prefetched;
        private final Map<String, Entity<?>> commitEntities = new LinkedHashMap<>();
        private final Map<AncestorPlanKey, List<MutationPlan.ResolvedModel>> ancestorPlans =
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
        public ModelReducer.ResolvedSubstep resolve(
                DeserializingMessage substep,
                Long requestedStateIndex,
                Map<String, Object> stagedValues) {
            Long boundary = requestedStateIndex == null ? pinnedStateIndex : requestedStateIndex;
            if (pinnedStateIndex != null && !pinnedStateIndex.equals(boundary)) {
                throw new IllegalStateException(
                        "Pinned model evaluation moved from state index %d to %d"
                                .formatted(pinnedStateIndex, boundary));
            }
            MutationPlan definition = definitionFor(substep.getPayloadClass());
            if (substep == directMessage
                && prefetched != null
                && prefetched.entity != null
                && requestedStateIndex == null
                && stagedValues.isEmpty()) {
                commitEntities.put(prefetched.modelId, prefetched.entity);
                return new ModelReducer.ResolvedSubstep(
                        CommitAttempt.createSingle(
                                prefetched.stateIndex,
                                prefetched.modelId,
                                prefetched.modelType,
                                prefetched.access,
                                prefetched.sourceProperties,
                        prefetched.entity),
                        prefetched.access.writes()
                                ? prefetched.reducer : definition.genericReducer());
            }
            ExplicitModelTarget explicitTarget = substep.getContext(
                    ExplicitModelTarget.class).orElse(null);
            MutationPlan.Resolution resolution =
                    definition.targets().resolve(
                            substep.getPayload(),
                            explicitTarget == null ? null : explicitTarget.modelId(),
                            explicitTarget == null ? null : explicitTarget.modelType(),
                            applyOnly);
            AncestorPlanKey planKey = resolution.hasAncestorDependencies()
                    ? ancestorPlanKey(resolution, stagedValues) : null;
            List<MutationPlan.ResolvedModel> effectiveTargets = planKey == null
                    ? resolution.models() : ancestorPlans.get(planKey);
            List<MutationPlan.ResolvedModel> missing = effectiveTargets == null ? List.of()
                    : effectiveTargets.stream()
                            .filter(target -> !commitEntities.containsKey(target.modelId()))
                            .toList();
            long stateIndex = boundary == null ? -1L : boundary;
            if (effectiveTargets == null) {
                CommitAttempt loaded = load(resolution, boundary, stagedValues);
                stateIndex = loaded.readStateIndex();
                effectiveTargets = targets(loaded);
                ancestorPlans.put(planKey, effectiveTargets);
            } else if (pinnedStateIndex == null && requestedStateIndex == null
                       || !missing.isEmpty()) {
                MutationPlan.Resolution loadResolution =
                        pinnedStateIndex == null && requestedStateIndex == null
                                ? planKey == null ? resolution
                                        : resolution.withResolvedModels(effectiveTargets)
                                : new MutationPlan.Resolution(missing, List.of());
                stateIndex = load(loadResolution, boundary, stagedValues).readStateIndex();
            }
            MutationPlan.Resolution effectiveResolution = planKey == null
                    ? resolution : resolution.withResolvedModels(effectiveTargets);
            LinkedHashMap<String, Entity<?>> selected = new LinkedHashMap<>();
            effectiveTargets.forEach(target -> selected.put(
                    target.modelId(), Objects.requireNonNull(
                            commitEntities.get(target.modelId()),
                            "Missing commit-scoped model " + target.modelId())));
            return new ModelReducer.ResolvedSubstep(
                    CommitAttempt.create(
                            stateIndex, effectiveResolution, selected),
                    definition.genericReducer());
        }

        @Override
        public void prefetch(
                List<DeserializingMessage> messages,
                long readStateIndex,
                Map<String, Object> stagedValues) {
            LinkedHashMap<String, MutationPlan.ResolvedModel> targets =
                    new LinkedHashMap<>();
            for (DeserializingMessage message : messages) {
                if (message.getContext(ExplicitModelTarget.class).isPresent()) {
                    continue;
                }
                MutationPlan.Resolution resolution = definitionFor(
                                message.getPayloadClass())
                        .targets().resolve(message.getPayload(), null, applyOnly);
                if (resolution.hasAncestorDependencies()) {
                    continue;
                }
                resolution.models().stream()
                        .filter(target -> !commitEntities.containsKey(target.modelId()))
                        .forEach(target -> MutationPlan.merge(targets, target));
            }
            if (!targets.isEmpty()) {
                load(new MutationPlan.Resolution(
                                List.copyOf(targets.values()), List.of()),
                     readStateIndex, stagedValues);
            }
        }

        @Override
        public ModelReducer.ResolvedSubstep resolveGraph(
                String modelId,
                Class<?> modelType,
                Long requestedStateIndex,
                Map<String, Object> stagedValues) {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelType, "modelType");
            MutationPlan.Resolution resolution =
                    new MutationPlan.Resolution(
                            List.of(new MutationPlan.ResolvedModel(
                                    modelId, modelType,
                                    MutationPlan.Access.READ_WRITE,
                                    List.of())),
                            List.of());
            CommitAttempt loaded = load(
                    resolution,
                    requestedStateIndex == null
                            ? pinnedStateIndex : requestedStateIndex,
                    stagedValues);
            return new ModelReducer.ResolvedSubstep(
                    loaded, ModelReducer.EMPTY);
        }

        private CommitAttempt load(
                MutationPlan.Resolution resolution,
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
            CommitAttempt loaded = repository.loadContext(
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
            for (String modelId : loaded.modelIds()) {
                commitEntities.put(modelId, loaded.entity(modelId));
            }
            return loaded;
        }

        private static List<MutationPlan.ResolvedModel> targets(
                CommitAttempt context) {
            return context.targets();
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
                    ? ModelPipeline.class : trackingTarget;
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
            return new HandlerInvoker.DelegatingHandlerInvoker(
                    HandlerInvoker.call(
                            () -> executeAutomatic(message, commitPolicy))) {
                @Override
                public boolean requiresBatchSegmentOrder() {
                    /*
                     * The generic tracker segment is deliberately coarse and may collide for unrelated models. Exact
                     * read-set coordination in the shared model batch scope owns automatic model ordering.
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

    private Object executeAutomatic(
            DeserializingMessage message,
            ModelCommitPolicy commitPolicy) {
        CompletableFuture<Object> completion = execute(
                new ExecutionRequest(
                        message, null, -1, false, false, true),
                commitPolicy, this::evaluateLive);
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
        MutationPlan definition = definitionFor(message.getPayloadClass());
        MutationPlan.TargetPlan targets = definition.targets();
        if (!targets.isDirectSingleTarget()) {
            return null;
        }
        return new PrefetchSlot(
                targets.resolveSingleModelId(message.getPayload()),
                targets.singleModelType(), targets.singleAccess(), targets.singleSourceProperties(),
                definition.reducer());
    }

    private static final class PrefetchSlot
            implements DefaultModelRepository.CurrentModelSink {
        private final String modelId;
        private final Class<?> modelType;
        private final MutationPlan.Access access;
        private final List<String> sourceProperties;
        private final ModelReducer reducer;
        private Entity<?> entity;
        private long stateIndex;

        private PrefetchSlot(
                String modelId,
                Class<?> modelType,
                MutationPlan.Access access,
                List<String> sourceProperties,
                ModelReducer reducer) {
            this.modelId = modelId;
            this.modelType = modelType;
            this.access = access;
            this.sourceProperties = sourceProperties;
            this.reducer = reducer;
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

    private static AncestorPlanKey ancestorPlanKey(
            MutationPlan.Resolution resolution,
            Map<String, Object> stagedValues) {
        List<StagedRelationships> relationships = new ArrayList<>(stagedValues.size());
        stagedValues.forEach((modelId, value) -> {
            List<ParentRelationship> parents = new ArrayList<>();
            if (value != null) {
                EntityMetadata.validate(value.getClass()).parentReferences().forEach(parent -> {
                    Object parentId = parent.read(value);
                    if (parentId != null) {
                        parents.add(new ParentRelationship(
                                Objects.requireNonNull(parent.repositoryId(parentId), "Parent ID string"),
                                parent.parentModelType(parentId), parent.path()));
                    }
                });
            }
            relationships.add(new StagedRelationships(modelId, List.copyOf(parents)));
        });
        return new AncestorPlanKey(resolution, List.copyOf(relationships));
    }

    private record AncestorPlanKey(
            MutationPlan.Resolution resolution,
            List<StagedRelationships> stagedRelationships) {
    }

    private record StagedRelationships(String modelId, List<ParentRelationship> parents) {
    }

    private record ParentRelationship(String parentId, Class<?> parentType, String path) {
    }
}
