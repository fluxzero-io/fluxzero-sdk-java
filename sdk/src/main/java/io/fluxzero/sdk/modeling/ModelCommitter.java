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

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitStepResult;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.jfr.FluxzeroJfr;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitResultBatchSource;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;

import java.lang.reflect.Executable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.SearchUtils.parseTimeProperty;

/**
 * Converts a side-effect-free {@link ModelCommitEngine} evaluation into one authoritative runtime commit package.
 * <p>
 * The original event payload is serialized once per substep. Per-target stream membership remains separate, while
 * global publication is the union of all targeted model publication policies. Optional direct documents and snapshots
 * travel with the same package. The runtime durably retains incomplete materialization work and reports completion
 * before a successful model commit returns, preserving immediate direct-search visibility across retries and restarts.
 */
final class ModelCommitter {
    private static final int MAX_ACCEPT_REBASE_ATTEMPTS = 10;
    private static final boolean COMMIT_TIMING_DIAGNOSTICS =
            Boolean.getBoolean("fluxzero.modelCommitDetailedTimingDiagnostics");
    private static final AtomicLong PREPARED = new AtomicLong();
    private static final LongAdder PREPARE_NANOS = new LongAdder();
    private static final AtomicLong RESULT_BATCHES = new AtomicLong();
    private static final LongAdder RESULT_ITEMS = new LongAdder();
    private static final LongAdder RESULT_MATCH_NANOS = new LongAdder();
    private static final LongAdder RESULT_PROCESS_NANOS = new LongAdder();
    private static final LongAdder RESULT_REMOVE_NANOS = new LongAdder();

    private final EventStoreClient eventStoreClient;
    private final Serializer serializer;
    private final Serializer snapshotSerializer;
    private final DocumentSerializer documentSerializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final String source;
    private final Function<List<CommittedCommit>, CompletableFuture<Void>> afterCommits;
    private final PendingCommitIndex pendingCommits;
    private final Registration resultBatchRegistration;
    private final ConcurrentHashMap<Executable, ConcurrentHashMap<Class<?>, TransitionPlan>> transitionPlans =
            new ConcurrentHashMap<>();
    private volatile CachedTransitionPlan recentTransitionPlan;

    ModelCommitter(
            EventStoreClient eventStoreClient,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source) {
        this(eventStoreClient, serializer, documentSerializer,
             dispatchInterceptor, source, serializer,
             ignored -> CompletableFuture.completedFuture(null));
    }

    ModelCommitter(
            EventStoreClient eventStoreClient,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source,
            Serializer snapshotSerializer,
            Function<List<CommittedCommit>, CompletableFuture<Void>> afterCommits) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient);
        this.serializer = Objects.requireNonNull(serializer);
        this.snapshotSerializer = snapshotSerializer;
        this.documentSerializer = Objects.requireNonNull(documentSerializer);
        this.dispatchInterceptor = Objects.requireNonNull(dispatchInterceptor);
        this.source = source;
        this.afterCommits = Objects.requireNonNull(afterCommits);
        if (!Boolean.getBoolean(
                "fluxzero.disableModelCommitResultBatching")
            && eventStoreClient
            instanceof ModelCommitResultBatchSource batchSource) {
            pendingCommits = new PendingCommitIndex();
            resultBatchRegistration =
                    batchSource.registerModelCommitResultProcessor(
                            this::processCommitResults);
        } else {
            pendingCommits = null;
            resultBatchRegistration = Registration.noOp();
        }
    }

    CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId, ModelCommitEngine.CommitEvaluation evaluation) {
        return commit(commitId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        return commitPrepared(
                evaluation,
                prepare(commitId, evaluation, conflictPolicy),
                null, -1);
    }

    CompletableFuture<Optional<CommitModelsResult>> commitAcceptingRebase(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            RebaseEvaluator rebaseEvaluator) {
        return commitAcceptingRebase(
                commitId, evaluation, rebaseEvaluator,
                null, -1);
    }

    CompletableFuture<Optional<CommitModelsResult>> commitAcceptingRebase(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            RebaseEvaluator rebaseEvaluator,
            CommitBatch batch,
            int batchSlot) {
        Objects.requireNonNull(
                rebaseEvaluator, "rebaseEvaluator");
        PreparedCommit original = prepare(
                commitId, evaluation,
                ModelConflictPolicy.ACCEPT);
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return commitAcceptingRebase(
                commitId, evaluation, original, original,
                rebaseEvaluator, context, 0,
                batch, batchSlot);
    }

    private CompletableFuture<Optional<CommitModelsResult>>
            commitAcceptingRebase(
                    String commitId,
                    ModelCommitEngine.CommitEvaluation evaluation,
                    PreparedCommit original,
                    PreparedCommit prepared,
                    RebaseEvaluator rebaseEvaluator,
                    ThreadLocalContext.Snapshot context,
                    int attempts,
                    CommitBatch batch,
                    int batchSlot) {
        return commitPrepared(
                evaluation, prepared,
                batch, batchSlot)
                .thenCompose(optional -> {
                    if (optional.isEmpty()
                        || !optional.get().isRebaseRequired()) {
                        return CompletableFuture.completedFuture(
                                optional);
                    }
                    if (attempts
                        >= MAX_ACCEPT_REBASE_ATTEMPTS) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Model commit '%s' remained stale after %d apply-only rebases"
                                                .formatted(
                                                        commitId,
                                                        MAX_ACCEPT_REBASE_ATTEMPTS)));
                    }
                    long boundary = optional.get()
                            .getRebaseStateIndex();
                    /*
                     * A websocket client may complete the commit future on its serialized result callback.
                     * Reconstructing models can issue another request through that same client, so invoking the
                     * evaluator inline would make the callback wait for a result that it must dispatch itself.
                     * Only the stale path is offloaded; the normal accepted fast path remains allocation-free here.
                     */
                    return invokeAsync(
                            context,
                            () -> rebaseEvaluator.rebase(
                                    original.messages(),
                                    boundary),
                            "Model commit rebase returned null")
                            .thenCompose(next -> {
                        if (next.readStateIndex()
                            != boundary) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException(
                                            "Model commit '%s' rebase loaded state index %d instead of requested %d"
                                                    .formatted(
                                                            commitId,
                                                            next.readStateIndex(),
                                                            boundary)));
                        }
                        PreparedCommit nextPrepared =
                                prepareRebased(
                                        commitId, original, next);
                        return commitAcceptingRebase(
                                commitId, next, original,
                                nextPrepared, rebaseEvaluator,
                                context,
                                attempts + 1,
                                null, -1);
                    });
                });
    }

    private CompletableFuture<Optional<CommitModelsResult>>
            commitPrepared(
                    ModelCommitEngine.CommitEvaluation evaluation,
                    PreparedCommit prepared,
                    CommitBatch batch,
                    int batchSlot) {
        if (prepared.commit() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        PendingCommit pending = pendingCommits == null
                ? null : new PendingCommit(
                        prepared.commit().getRequestId(),
                        evaluation, prepared);
        if (pending != null) {
            pendingCommits.put(
                    prepared.commit().getRequestId(), pending);
        }
        CompletableFuture<CommitModelsResult> committed;
        recordCommitStage(prepared, "model-commit-dispatch-start");
        try {
            committed = batch == null
                    ? eventStoreClient.commitModels(prepared.commit())
                    : batch.add(
                            batchSlot, prepared.commit());
        } catch (Throwable failure) {
            if (pending != null) {
                pendingCommits.remove(
                        prepared.commit().getRequestId(), pending);
            }
            throw failure;
        }
        recordCommitStage(prepared, "model-commit-dispatched");
        if (pending != null) {
            return committed.handle((result, failure) -> {
                if (failure != null) {
                    pendingCommits.remove(
                            prepared.commit().getRequestId(), pending);
                    if (failure instanceof java.util.concurrent.CompletionException completion) {
                        throw completion;
                    }
                    throw new java.util.concurrent.CompletionException(failure);
                }
                if (!result.isAccepted()) {
                    pendingCommits.remove(
                            prepared.commit().getRequestId(), pending);
                }
                return Optional.of(result);
            });
        }
        return committed
                .thenApply(result -> {
                    recordCommitStage(prepared, "model-commit-response-received");
                    if (result.isAccepted()) {
                        processCommits(
                                List.of(
                                        new CommittedCommit(
                                                evaluation,
                                                prepared,
                                                result)))
                                .join();
                    }
                    return Optional.of(result);
                });
    }

    private CompletableFuture<Void> processCommitResults(
            List<CommitModelsResult> results) {
        if (pendingCommits == null) {
            return CompletableFuture.completedFuture(null);
        }
        long started = COMMIT_TIMING_DIAGNOSTICS ? System.nanoTime() : 0L;
        List<CommittedCommit> committed = new ArrayList<>(
                results.size());
        for (CommitModelsResult result : results) {
            PendingCommit pending = pendingCommits.get(
                    result.getRequestId());
            if (pending == null) {
                continue;
            }
            recordCommitStage(
                    pending.prepared(), "model-commit-response-received");
            /*
             * A decoded result owns this pending entry from here on. Removing it before post-commit processing avoids
             * retaining and boxing every request id in a second list, and also prevents duplicate transport delivery
             * from applying the same cache transition twice while this batch is still being prepared.
             */
            pendingCommits.remove(
                    result.getRequestId(), pending);
            recordCommitStage(
                    pending.prepared(), "model-result-matched");
            if (result.isAccepted()) {
                committed.add(
                        new CommittedCommit(
                                pending.evaluation(),
                                pending.prepared(),
                                result));
            }
        }
        long matchedAt = COMMIT_TIMING_DIAGNOSTICS ? System.nanoTime() : 0L;
        CompletableFuture<Void> processed = committed.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : processCommits(committed);
        long processedAt = COMMIT_TIMING_DIAGNOSTICS ? System.nanoTime() : 0L;
        if (!COMMIT_TIMING_DIAGNOSTICS) {
            return processed;
        }
        return processed.whenComplete((ignored, failure) -> {
            long completed = System.nanoTime();
            RESULT_ITEMS.add(results.size());
            RESULT_MATCH_NANOS.add(matchedAt - started);
            RESULT_PROCESS_NANOS.add(processedAt - matchedAt);
            RESULT_REMOVE_NANOS.add(completed - processedAt);
            long batches = RESULT_BATCHES.incrementAndGet();
            if ((batches & 63L) == 0L) {
                long items = RESULT_ITEMS.sum();
                System.out.printf(
                        "SDK model result batches: batches=%d items=%d average=%.1f match=%.3f us/item process=%.3f us/item remove=%.3f us/item%n",
                        batches, items, items / (double) batches,
                        RESULT_MATCH_NANOS.sum() / 1_000.0 / items,
                        RESULT_PROCESS_NANOS.sum() / 1_000.0 / items,
                        RESULT_REMOVE_NANOS.sum() / 1_000.0 / items);
            }
        });
    }

    CommitBatch beginBatch(int producers) {
        return new CommitBatch(
                !Boolean.getBoolean(
                        "fluxzero.disableModelCommitTransportBatching")
                && eventStoreClient instanceof ModelCommitBatchingClient batching
                        ? batching.beginModelCommitBatch(producers)
                        : null,
                producers);
    }

    CommitBatch beginReadyBatch() {
        ModelCommitBatchingClient.ModelCommitBatch delegate =
                !Boolean.getBoolean(
                        "fluxzero.disableReadyModelCommitTransportBatching")
                && eventStoreClient instanceof ModelCommitBatchingClient batching
                        ? batching.beginReadyModelCommitBatch()
                        : null;
        return delegate == null ? null : new CommitBatch(delegate);
    }

    private CompletableFuture<Void> processCommits(
            List<CommittedCommit> committed) {
        if (FluxzeroJfr.requestStageEnabled()) {
            committed.forEach(value ->
                                      recordCommitStage(
                                              value.prepared(), "model-post-commit-start"));
        }
        CompletableFuture<Void> result = Objects.requireNonNull(
                afterCommits.apply(committed),
                "Model post-commit callback returned null");
        if (!FluxzeroJfr.requestStageEnabled()) {
            return result;
        }
        return result.whenComplete((ignored, failure) -> {
            if (failure == null) {
                committed.forEach(value ->
                                          recordCommitStage(
                                                  value.prepared(), "model-post-commit-complete"));
            }
        });
    }

    void close() {
        resultBatchRegistration.cancel();
    }

    CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelCommitEngine.CommitEvaluation>> reload) {
        return commit(
                commitId, evaluation, conflictPolicy,
                conflictResolver, maxRetries, reload,
                null, -1);
    }

    CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelCommitEngine.CommitEvaluation>> reload,
            CommitBatch batch,
            int batchSlot) {
        Objects.requireNonNull(conflictResolver, "conflictResolver");
        Objects.requireNonNull(reload, "reload");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Maximum model conflict retries must not be negative");
        }
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return commit(
                commitId, evaluation, conflictPolicy, conflictResolver,
                maxRetries, reload, context, 0,
                batch, batchSlot);
    }

    private CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelCommitEngine.CommitEvaluation>> reload,
            ThreadLocalContext.Snapshot context,
            int retries,
            CommitBatch batch,
            int batchSlot) {
        return commitPrepared(
                evaluation,
                prepare(commitId, evaluation, conflictPolicy),
                batch, batchSlot).thenCompose(optional -> {
            if (optional.isEmpty() || optional.get().isAccepted()) {
                return CompletableFuture.completedFuture(optional);
            }
            CommitModelsResult conflict = optional.get();
            return CompletableFuture.supplyAsync(
                            context.wrap(
                                    () -> Objects.requireNonNull(
                                    conflictResolver.resolve(
                                            new ModelConflictResolver.Context(
                                                    conflict,
                                                    retries,
                                                    maxRetries)),
                                            "Model conflict resolver returned null")))
                    .thenCompose(resolution -> {
                        if (resolution
                            != ModelConflictResolver.Resolution.RETRY
                            || !conflict.isRetryAllowed()
                            || retries >= maxRetries) {
                            return CompletableFuture.failedFuture(
                                    new ModelCommitConflictException(
                                            conflict));
                        }
                        return invokeAsync(
                                context,
                                reload,
                                "Model conflict reload returned null")
                                .thenCompose(next -> commit(
                                commitId, next,
                                conflictPolicy,
                                conflictResolver,
                                maxRetries,
                                reload,
                                context,
                                retries + 1,
                                null, -1));
                    });
        });
    }

    private static <T> CompletableFuture<T> invokeAsync(
            ThreadLocalContext.Snapshot context,
            Supplier<CompletableFuture<T>> operation,
            String nullMessage) {
        return CompletableFuture.supplyAsync(
                        context.wrap(
                                () -> Objects.requireNonNull(
                                        operation.get(), nullMessage)))
                .thenCompose(Function.identity());
    }

    PreparedCommit prepare(String commitId, ModelCommitEngine.CommitEvaluation evaluation) {
        return prepare(commitId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    PreparedCommit prepare(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        recordCommitStage(evaluation, "model-commit-prepare-start");
        long started = COMMIT_TIMING_DIAGNOSTICS
                ? System.nanoTime() : 0L;
        try {
            PreparedCommit result = doPrepare(commitId, evaluation, conflictPolicy);
            recordCommitStage(evaluation, "model-commit-prepare-complete");
            return result;
        } finally {
            if (COMMIT_TIMING_DIAGNOSTICS) {
                PREPARE_NANOS.add(System.nanoTime() - started);
                long count = PREPARED.incrementAndGet();
                if ((count & 65_535L) == 0L) {
                    System.out.printf(
                            "SDK model prepare cumulative: count=%d cpu=%.3f ms average=%.3f us%n",
                            count, PREPARE_NANOS.sum() / 1_000_000.0,
                            PREPARE_NANOS.sum() / 1_000.0 / count);
                }
            }
        }
    }

    private static void recordCommitStage(
            ModelCommitEngine.CommitEvaluation evaluation, String stage) {
        if (!FluxzeroJfr.requestStageEnabled() || evaluation == null || evaluation.substeps().isEmpty()) {
            return;
        }
        recordCommitStage(evaluation.substeps().getFirst().message(), stage);
    }

    private static void recordCommitStage(PreparedCommit prepared, String stage) {
        if (!FluxzeroJfr.requestStageEnabled() || prepared == null || prepared.messages().isEmpty()) {
            return;
        }
        recordCommitStage(prepared.messages().getFirst(), stage);
    }

    private static void recordCommitStage(DeserializingMessage message, String stage) {
        SerializedMessage serialized = message.getSerializedObject();
        Long index = serialized.getIndex();
        if (index != null) {
            FluxzeroJfr.registerTraceCorrelation(message.getMessageId(), index);
            FluxzeroJfr.requestStage(
                    index, "sdk.model-committer", stage, 1, index);
        }
    }

    private PreparedCommit doPrepare(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        Objects.requireNonNull(commitId, "commitId");
        if (commitId.isBlank()) {
            throw new IllegalArgumentException("Model commit ID must not be blank");
        }
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");

        if (evaluation.substeps().size() == 1
            && evaluation.substeps().getFirst().transitions().size() == 1) {
            return prepareSingle(
                    commitId, evaluation, conflictPolicy,
                    evaluation.substeps().getFirst());
        }

        List<ModelCommitStep> substeps = new ArrayList<>();
        List<List<EffectiveTransition>> transitionGroups = new ArrayList<>();
        List<DeserializingMessage> messages = new ArrayList<>();
        Map<String, Long> nextSequences =
                new LinkedHashMap<>();
        for (int evaluatedSubstep = 0;
             evaluatedSubstep < evaluation.substeps().size();
             evaluatedSubstep++) {
            ModelCommitEngine.AppliedSubstep appliedSubstep =
                    evaluation.substeps().get(evaluatedSubstep);
            List<EffectiveTransition> transitions = appliedSubstep.transitions().stream()
                    .map(this::effectiveTransition)
                    .flatMap(Optional::stream)
                    .toList();
            if (transitions.isEmpty()) {
                continue;
            }
            boolean publishEvent = transitions.stream().anyMatch(EffectiveTransition::publishEvent);
            boolean eventRequired = publishEvent
                                    || transitions.stream().anyMatch(EffectiveTransition::storeEvent);
            SerializedMessage event = eventRequired
                    ? serialize(appliedSubstep.message(), commitId, evaluatedSubstep)
                    : null;
            if (event != null) {
                event.setSource(source);
                applyEventRouting(event, transitions);
                event = SerializedMessage.encode(event);
            }

            List<ModelCommitTarget> targets = new ArrayList<>(transitions.size());
            for (EffectiveTransition transition : transitions) {
                targets.add(target(
                        transition, appliedSubstep.message(),
                        nextSequences));
            }
            substeps.add(new ModelCommitStep(
                    event, publishEvent,
                    List.copyOf(targets)));
            transitionGroups.add(transitions);
            messages.add(appliedSubstep.message());
        }
        if (substeps.isEmpty()) {
            return new PreparedCommit(
                    null, List.of(), List.of(), null);
        }
        CommitModels commit = new CommitModels(
                commitId, evaluation.readStateIndex(), evaluation.readModelIds(),
                List.copyOf(substeps), conflictPolicy, STORED,
                possibleDuplicate(evaluation, transitionGroups));
        return new PreparedCommit(
                commit, List.copyOf(transitionGroups),
                List.copyOf(messages), null);
    }

    private PreparedCommit prepareSingle(
            String commitId,
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelCommitEngine.AppliedSubstep appliedSubstep) {
        ModelCommitEngine.Transition transition =
                appliedSubstep.transitions().getFirst();
        Optional<EffectiveTransition> optional =
                effectiveTransition(transition);
        if (optional.isEmpty()) {
            return new PreparedCommit(
                    null, List.of(), List.of(), null);
        }
        EffectiveTransition effective = optional.get();
        boolean eventRequired = effective.publishEvent()
                                || effective.storeEvent();
        SerializedMessage event = eventRequired
                ? serialize(appliedSubstep.message(), commitId, 0) : null;
        String eventMessageId = null;
        if (event != null) {
            event.setSource(source);
            if (effective.publishEvent()
                && effective.eventRouting()
                   == AggregateEventRouting.AGGREGATE_ID) {
                event.setSegment(
                        ConsistentHashing.computeSegment(
                                transition.modelId()));
            }
            eventMessageId = event.getMessageId();
            event = SerializedMessage.encode(event);
        }
        long nextSequence = transition.beforeSequenceNumber()
                            + (effective.storeEvent() ? 1L : 0L);
        ModelCommitTarget target = target(
                effective,
                appliedSubstep.message(),
                nextSequence);
        ModelCommitStep step = new ModelCommitStep(
                event, effective.publishEvent(),
                List.of(target));
        CommitModels commit = new CommitModels(
                commitId,
                evaluation.readStateIndex(),
                evaluation.readModelIds(),
                List.of(step),
                conflictPolicy,
                STORED,
                possibleDuplicate(evaluation, effective));
        return new PreparedCommit(
                commit,
                List.of(List.of(effective)),
                List.of(appliedSubstep.message()),
                eventMessageId);
    }

    private PreparedCommit prepareRebased(
            String commitId,
            PreparedCommit original,
            ModelCommitEngine.CommitEvaluation evaluation) {
        if (original.commit() == null) {
            throw new IllegalArgumentException(
                    "Cannot rebase an empty model commit");
        }
        if (evaluation.substeps().size()
            != original.commit().getSubsteps().size()) {
            throw new IllegalStateException(
                    "Apply-only rebase changed the number of model commit substeps");
        }
        List<ModelCommitStep> substeps =
                new ArrayList<>(evaluation.substeps().size());
        List<List<EffectiveTransition>> transitionGroups =
                new ArrayList<>(evaluation.substeps().size());
        Map<String, Long> nextSequences =
                new LinkedHashMap<>();
        for (int substepIndex = 0;
             substepIndex < evaluation.substeps().size();
             substepIndex++) {
            ModelCommitEngine.AppliedSubstep rebased =
                    evaluation.substeps().get(substepIndex);
            ModelCommitStep source =
                    original.commit().getSubsteps().get(
                            substepIndex);
            LinkedHashMap<String, ModelCommitEngine.Transition>
                    transitionsById = new LinkedHashMap<>();
            for (ModelCommitEngine.Transition transition :
                    rebased.transitions()) {
                ModelCommitEngine.Transition previous =
                        transitionsById.putIfAbsent(
                                transition.modelId(),
                                transition);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Apply-only rebase produced duplicate target '%s'"
                                    .formatted(
                                            transition.modelId()));
                }
            }
            List<ModelCommitTarget> targets =
                    new ArrayList<>(source.getTargets().size());
            List<EffectiveTransition> effective =
                    new ArrayList<>(source.getTargets().size());
            for (ModelCommitTarget originalTarget :
                    source.getTargets()) {
                ModelCommitEngine.Transition transition =
                        transitionsById.remove(
                                originalTarget.getModelId());
                if (transition == null) {
                    throw new IllegalStateException(
                            "Apply-only rebase no longer returned original target '%s'"
                                    .formatted(
                                            originalTarget.getModelId()));
                }
                if (!Objects.equals(
                        originalTarget.getModelType(),
                        transition.modelType().getName())) {
                    throw new IllegalStateException(
                            "Apply-only rebase changed target '%s' from %s to %s"
                                    .formatted(
                                            originalTarget.getModelId(),
                                            originalTarget.getModelType(),
                                            transition.modelType()
                                                    .getName()));
                }
                EffectiveTransition effectiveTransition =
                        new EffectiveTransition(
                                transition,
                                originalTarget.isStoreEvent(),
                                source.isPublishEvent(),
                                originalTarget
                                        .isUpdateState(),
                                transitionPlan(transition));
                validateCompleteHistory(
                        effectiveTransition);
                targets.add(target(
                        effectiveTransition, rebased.message(),
                        nextSequences));
                effective.add(effectiveTransition);
            }
            if (!transitionsById.isEmpty()) {
                throw new IllegalStateException(
                        "Apply-only rebase introduced new targets "
                        + transitionsById.keySet());
            }
            substeps.add(new ModelCommitStep(
                    source.getEvent(),
                    source.isPublishEvent(),
                    List.copyOf(targets)));
            transitionGroups.add(
                    List.copyOf(effective));
        }
        CommitModels commit = new CommitModels(
                commitId, evaluation.readStateIndex(),
                evaluation.readModelIds(),
                List.copyOf(substeps),
                ModelConflictPolicy.ACCEPT,
                original.commit().getGuarantee(),
                original.commit().getPossibleDuplicate());
        return new PreparedCommit(
                commit,
                List.copyOf(transitionGroups),
                original.messages(),
                original.singleEventMessageId());
    }

    private ModelCommitTarget target(
            EffectiveTransition effective,
            DeserializingMessage message,
            Map<String, Long> nextSequences) {
        return target(
                effective,
                message,
                nextSequence(
                        effective.transition(), effective,
                        nextSequences));
    }

    private ModelCommitTarget target(
            EffectiveTransition effective,
            DeserializingMessage message,
            long nextSequence) {
        ModelCommitEngine.Transition transition = effective.transition();
        DirectDocumentCandidate documentCandidate = effective.updateState()
                ? directDocument(
                        transition, effective.plan(),
                        message.getTimestamp(), message.getMetadata())
                : null;
        DirectDocument document = documentCandidate == null
                ? null : serializeDocument(documentCandidate);
        RelationshipUpdate relationships = effective.updateState()
                ? relationshipUpdate(
                        transition, effective.plan())
                : RelationshipUpdate.UNCHANGED;
        ModelDocumentMutation documentMutation = document == null
                ? null
                : new ModelDocumentMutation(
                        document.collection(),
                        document.document());
        ModelSnapshotMutation snapshot = snapshot(
                transition, effective, effective.plan(),
                nextSequence,
                message.getTimestamp());
        return new ModelCommitTarget(
                transition.modelId(),
                effective.plan().modelTypeName(),
                transition.beforeSequenceNumber(),
                effective.storeEvent(),
                effective.updateState(),
                effective.updateState()
                && transition.after() == null,
                documentMutation,
                snapshot,
                relationships.update(),
                relationships.relationships());
    }

    private SerializedMessage serialize(
            DeserializingMessage message, String commitId, int substep) {
        SerializedMessage source = message.getSerializedObject(serializer);
        io.fluxzero.sdk.common.Message logicalMessage =
                message.toMessage();
        /*
         * The applied update is the event payload. Tracked commands already carry the
         * current, post-upcast serialized representation, while intercepted updates
         * lazily create that representation through getSerializedObject(). Reusing it
         * avoids serializing every automatic model update a second time. Only payload
         * data is shared: transport/tracking fields intentionally stay behind on the
         * command and event dispatch interceptors still receive an independent message.
         */
        SerializedMessage serialized = dispatchInterceptor.modifySerializedMessage(
                new SerializedMessage(
                        source,
                        logicalMessage.getMetadata(),
                        logicalMessage.getMessageId(),
                        logicalMessage.getTimestamp().toEpochMilli()),
                logicalMessage, EVENT, null);
        if (serialized == null) {
            throw new IllegalStateException(
                    "Serialized model event was suppressed after @Apply evaluation; "
                    + "logical event suppression must happen before model applies");
        }
        serialized.setMetadata(serialized.getMetadata().with(
                ModelEventMetadata.COMMIT_ID, commitId,
                ModelEventMetadata.SUBSTEP, substep));
        return serialized;
    }

    private static Boolean possibleDuplicate(
            ModelCommitEngine.CommitEvaluation evaluation,
            List<List<EffectiveTransition>> transitionGroups) {
        Long sourceIndex = DeserializingMessage.getOptionally()
                .map(DeserializingMessage::getIndex)
                .orElse(null);
        if (sourceIndex == null
            || transitionGroups.stream()
                    .flatMap(List::stream)
                    .anyMatch(transition ->
                                      !transition.storeEvent()
                                      || !transition.publishEvent())) {
            return null;
        }
        return evaluation.transitions().stream()
                .map(ModelCommitEngine.Transition::beforeLastEventIndex)
                .filter(Objects::nonNull)
                .anyMatch(index -> index >= sourceIndex);
    }

    private static Boolean possibleDuplicate(
            ModelCommitEngine.CommitEvaluation evaluation,
            EffectiveTransition transition) {
        Long sourceIndex = DeserializingMessage.getOptionally()
                .map(DeserializingMessage::getIndex)
                .orElse(null);
        if (sourceIndex == null
            || !transition.storeEvent()
            || !transition.publishEvent()) {
            return null;
        }
        Long beforeLastEventIndex =
                transition.transition().beforeLastEventIndex();
        return beforeLastEventIndex != null
               && beforeLastEventIndex >= sourceIndex;
    }

    private Optional<EffectiveTransition> effectiveTransition(ModelCommitEngine.Transition transition) {
        TransitionPlan plan = transitionPlan(transition);
        Publication publication = plan.publication();
        boolean compareState =
                publication.eventPublication()
                        != EventPublication.ALWAYS
                || publication.publicationStrategy()
                        == EventPublicationStrategy.PUBLISH_ONLY;
        boolean modified = !compareState
                || !Objects.equals(
                        transition.before(),
                        transition.after());
        if (publication.eventPublication()
            == EventPublication.IF_MODIFIED
            && !modified) {
            return Optional.empty();
        }
        if (publication.eventPublication() == EventPublication.NEVER) {
            if (!modified) {
                return Optional.empty();
            }
            EffectiveTransition result =
                    new EffectiveTransition(
                            transition, false, false,
                            true,
                            plan);
            validateCompleteHistory(result);
            return Optional.of(result);
        }
        EffectiveTransition result =
                switch (publication.publicationStrategy()) {
            case STORE_AND_PUBLISH ->
                    new EffectiveTransition(
                            transition, true, true,
                            true,
                            plan);
            case STORE_ONLY ->
                    new EffectiveTransition(
                            transition, true, false,
                            true,
                            plan);
            case PUBLISH_ONLY ->
                    new EffectiveTransition(
                            transition, false, true,
                            modified,
                            plan);
            case DEFAULT -> throw new IllegalStateException("Unresolved model publication strategy");
        };
        validateCompleteHistory(result);
        return Optional.of(result);
    }

    private static void validateCompleteHistory(
            EffectiveTransition transition) {
        ModelMetadata.RootConfiguration model =
                transition.plan().model();
        if (model.eventSourced()
            && transition.updateState()
            && !transition.storeEvent()) {
            throw new IllegalStateException(
                    "Event-sourced model %s cannot change through %s without storing its reconstructing event. "
                    .formatted(
                            transition.transition()
                                    .modelType()
                                    .getName(),
                            transition.transition()
                                    .handler()
                                    .toGenericString())
                    + "Use STORE_ONLY or STORE_AND_PUBLISH, make the model document-loaded, or publish a no-op event.");
        }
    }

    private TransitionPlan transitionPlan(
            ModelCommitEngine.Transition transition) {
        CachedTransitionPlan recent = recentTransitionPlan;
        if (recent != null
            && recent.handler() == transition.handler()
            && recent.modelType() == transition.modelType()) {
            return recent.plan();
        }
        TransitionPlan plan = transitionPlans.computeIfAbsent(
                        transition.handler(),
                        ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(
                        transition.modelType(),
                        modelType -> createTransitionPlan(
                                transition.handler(), modelType));
        recentTransitionPlan = new CachedTransitionPlan(
                transition.handler(), transition.modelType(), plan);
        return plan;
    }

    private static TransitionPlan createTransitionPlan(
            Executable handler,
            Class<?> modelType) {
        ModelMetadata metadata = ModelMetadata.of(modelType);
        ModelMetadata.RootConfiguration model = metadata
                .rootConfiguration().orElseThrow(() -> new IllegalStateException(
                        modelType.getName() + " is not an independent model"));
        Apply apply = handler.getAnnotation(Apply.class);
        EventPublication eventPublication =
                apply != null && apply.eventPublication() != EventPublication.DEFAULT
                        ? apply.eventPublication()
                        : model.eventPublication() == EventPublication.DEFAULT
                                ? EventPublication.ALWAYS : model.eventPublication();
        EventPublicationStrategy strategy =
                apply != null && apply.publicationStrategy() != EventPublicationStrategy.DEFAULT
                        ? apply.publicationStrategy()
                        : model.publicationStrategy() == EventPublicationStrategy.DEFAULT
                                ? EventPublicationStrategy.STORE_AND_PUBLISH : model.publicationStrategy();
        AggregateEventRouting routing =
                apply != null && apply.eventRouting() != AggregateEventRouting.DEFAULT
                        ? apply.eventRouting()
                        : model.eventRouting() == AggregateEventRouting.DEFAULT
                                ? AggregateEventRouting.MESSAGE_ROUTING_KEY : model.eventRouting();
        String directCollection = null;
        if (model.searchable()) {
            directCollection = Optional.of(model.collection())
                    .filter(value -> !value.isEmpty())
                    .map(ApplicationProperties::substituteProperties)
                    .orElse(modelType.getSimpleName());
        } else if (metadata.participatesInGraphComposition()) {
            directCollection = ModelDocumentMutation
                    .GRAPH_COMPONENT_COLLECTION;
        }
        return new TransitionPlan(
                modelType.getName(), model,
                metadata.entityId().orElseThrow(),
                metadata.parentReferences(), directCollection,
                new Publication(
                        eventPublication, strategy, routing));
    }

    private static void applyEventRouting(
            SerializedMessage event, List<EffectiveTransition> transitions) {
        List<EffectiveTransition> published = transitions.stream()
                .filter(EffectiveTransition::publishEvent).toList();
        if (published.isEmpty()) {
            return;
        }
        boolean aggregateIdRouting = published.stream()
                .anyMatch(transition -> transition.eventRouting() == AggregateEventRouting.AGGREGATE_ID);
        boolean messageRouting = published.stream()
                .anyMatch(transition -> transition.eventRouting() == AggregateEventRouting.MESSAGE_ROUTING_KEY);
        if (aggregateIdRouting && (messageRouting || published.size() != 1)) {
            throw new IllegalStateException(
                    "One model event cannot use conflicting aggregate-ID routing for multiple published targets");
        }
        if (aggregateIdRouting) {
            event.setSegment(ConsistentHashing.computeSegment(
                    published.getFirst().transition().modelId()));
        }
    }

    private static RelationshipUpdate relationshipUpdate(
            ModelCommitEngine.Transition transition,
            TransitionPlan plan) {
        if (plan.parentReferences().isEmpty()) {
            return transition.after() == null
                    ? RelationshipUpdate.CLEARED
                    : RelationshipUpdate.UNCHANGED;
        }
        List<ModelRelationship> before =
                relationships(
                        transition.modelId(), transition.before(),
                        plan.parentReferences());
        List<ModelRelationship> after =
                relationships(
                        transition.modelId(), transition.after(),
                        plan.parentReferences());
        boolean update = transition.after() == null
                         || !before.equals(after);
        return new RelationshipUpdate(
                update, update ? after : List.of());
    }

    private static List<ModelRelationship> relationships(
            String modelId,
            Object model,
            List<ModelMetadata.ParentReference> parentReferences) {
        if (model == null) {
            return List.of();
        }
        LinkedHashMap<RelationshipKey, ModelRelationship> result = new LinkedHashMap<>();
        for (ModelMetadata.ParentReference parent : parentReferences) {
            Object parentId = parent.read(model);
            if (parentId == null) {
                continue;
            }
            ModelRelationship relationship = ModelRelationship.builder()
                    .parentId(parentId.toString())
                    .parentType(parent.parentModelType() == null
                                        ? null : parent.parentModelType().getName())
                    .path(parent.path().isEmpty() ? null : parent.path())
                    .build();
            if (modelId.equals(relationship.getParentId())) {
                throw new IllegalStateException(
                        "Model '%s' cannot be its own parent".formatted(modelId));
            }
            result.putIfAbsent(new RelationshipKey(
                    relationship.getParentId(), relationship.getParentType(), relationship.getPath()), relationship);
        }
        return List.copyOf(result.values());
    }

    private static long nextSequence(
            ModelCommitEngine.Transition transition,
            EffectiveTransition effective,
            Map<String, Long> nextSequences) {
        long previous = nextSequences.getOrDefault(
                transition.modelId(),
                transition.beforeSequenceNumber());
        long result = previous
                      + (effective.storeEvent()
                                 ? 1L : 0L);
        nextSequences.put(
                transition.modelId(), result);
        return result;
    }

    private ModelSnapshotMutation snapshot(
            ModelCommitEngine.Transition transition,
            EffectiveTransition effective,
            TransitionPlan plan,
            long nextSequence,
            Instant timestamp) {
        ModelMetadata.RootConfiguration model = plan.model();
        if (snapshotSerializer == null
            || !model.eventSourced()
            || !effective.storeEvent()
            || transition.after() == null
            || model.snapshotPeriod() <= 0
            || Math.floorMod(
                    nextSequence + 1L,
                    model.snapshotPeriod()) != 0L) {
            return null;
        }
        return new ModelSnapshotMutation(
                snapshotSerializer.serialize(
                        transition.after()),
                timestamp.toEpochMilli(),
                model.snapshotPeriod(),
                Math.max(
                        1,
                        model.maxSnapshotCount()));
    }

    private static DirectDocumentCandidate directDocument(
            ModelCommitEngine.Transition transition,
            TransitionPlan plan,
            Instant eventTimestamp,
            Metadata metadata) {
        ModelMetadata.RootConfiguration model = plan.model();
        if (plan.directCollection() == null) {
            return null;
        }
        String collection = plan.directCollection();
        Object value = transition.after();
        if (value == null) {
            return new DirectDocumentCandidate(
                    transition.modelId(), collection,
                    null, null, null, metadata);
        }
        Instant begin = parseTimeProperty(
                blankToNull(model.timestampPath()), value, false, () -> eventTimestamp);
        Instant end = parseTimeProperty(
                blankToNull(model.endPath()), value, true, () -> begin);
        return new DirectDocumentCandidate(
                transition.modelId(), collection,
                value, begin, end, metadata);
    }

    private DirectDocument serializeDocument(DirectDocumentCandidate candidate) {
        SerializedDocument document = candidate.value() == null ? null : documentSerializer.toDocument(
                candidate.value(), candidate.modelId(), candidate.collection(),
                candidate.begin(), candidate.end(), candidate.metadata());
        return new DirectDocument(candidate.modelId(), candidate.collection(), document);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record PreparedCommit(
            CommitModels commit,
            List<List<EffectiveTransition>> transitionGroups,
            List<DeserializingMessage> messages,
            String singleEventMessageId) {
    }

    final class CommitBatch {
        private final ModelCommitBatchingClient.ModelCommitBatch delegate;
        private final AtomicInteger remainingProducers;
        private final AtomicBoolean completed = new AtomicBoolean();

        private CommitBatch(
                ModelCommitBatchingClient.ModelCommitBatch delegate,
                int producers) {
            this.delegate = delegate;
            this.remainingProducers = new AtomicInteger(producers);
        }

        private CommitBatch(
                ModelCommitBatchingClient.ModelCommitBatch delegate) {
            this.delegate = Objects.requireNonNull(delegate);
            this.remainingProducers = null;
        }

        private CompletableFuture<CommitModelsResult> add(
                int slot, CommitModels commit) {
            return delegate == null
                    ? eventStoreClient.commitModels(commit)
                    : delegate.add(slot, commit);
        }

        void producerDone() {
            if (remainingProducers != null
                && remainingProducers.decrementAndGet() == 0
                && delegate != null
                && completed.compareAndSet(false, true)) {
                delegate.flush();
            }
        }

        void flush() {
            if (delegate != null
                && completed.compareAndSet(false, true)) {
                delegate.flush();
            }
        }

        void fail(Throwable failure) {
            if (delegate != null
                && completed.compareAndSet(false, true)) {
                delegate.fail(failure);
            }
        }
    }

    @FunctionalInterface
    interface RebaseEvaluator {
        CompletableFuture<ModelCommitEngine.CommitEvaluation> rebase(
                List<DeserializingMessage> messages,
                long stateIndex);
    }

    record CommittedCommit(
            ModelCommitEngine.CommitEvaluation evaluation,
            PreparedCommit prepared,
            CommitModelsResult result) {
    }

    private record PendingCommit(
            long requestId,
            ModelCommitEngine.CommitEvaluation evaluation,
            PreparedCommit prepared) {
    }

    /**
     * Keeps the ordinary monotone request-id path out of a boxed concurrent map while retaining a
     * collision-safe fallback for unusually wide request windows and interleaved request types.
     */
    private static final class PendingCommitIndex {
        private static final int DEFAULT_CAPACITY = 131_072;

        private final AtomicReferenceArray<PendingCommit> direct;
        private final int mask;
        private final ConcurrentHashMap<Long, PendingCommit> collisions =
                new ConcurrentHashMap<>();

        private PendingCommitIndex() {
            int requested = Math.max(
                    1_024,
                    Integer.getInteger(
                            "fluxzero.modelCommitResultIndexCapacity",
                            DEFAULT_CAPACITY));
            int capacity = 1;
            while (capacity < requested) {
                capacity = Math.multiplyExact(capacity, 2);
            }
            direct = new AtomicReferenceArray<>(capacity);
            mask = capacity - 1;
        }

        private void put(long requestId, PendingCommit pending) {
            int slot = (int) requestId & mask;
            if (!direct.compareAndSet(slot, null, pending)) {
                PendingCommit previous = collisions.putIfAbsent(
                        requestId, pending);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate pending model commit request " + requestId);
                }
            }
        }

        private PendingCommit get(long requestId) {
            PendingCommit candidate = direct.get((int) requestId & mask);
            return candidate != null && candidate.requestId() == requestId
                    ? candidate
                    : collisions.get(requestId);
        }

        private boolean remove(long requestId, PendingCommit pending) {
            int slot = (int) requestId & mask;
            PendingCommit candidate = direct.get(slot);
            return candidate == pending
                    ? direct.compareAndSet(slot, pending, null)
                    : collisions.remove(requestId, pending);
        }
    }

    record DirectDocument(
            String modelId, String collection, SerializedDocument document) {
    }

    private record DirectDocumentCandidate(
            String modelId,
            String collection,
            Object value,
            Instant begin,
            Instant end,
            Metadata metadata) {
    }

    record EffectiveTransition(
            ModelCommitEngine.Transition transition,
            boolean storeEvent,
            boolean publishEvent,
            boolean updateState,
            TransitionPlan plan) {
        AggregateEventRouting eventRouting() {
            return plan.publication().eventRouting();
        }

        ModelMetadata.RootConfiguration model() {
            return plan.model();
        }

        ModelMetadata.Property entityId() {
            return plan.entityId();
        }
    }

    private record TransitionPlan(
            String modelTypeName,
            ModelMetadata.RootConfiguration model,
            ModelMetadata.Property entityId,
            List<ModelMetadata.ParentReference> parentReferences,
            String directCollection,
            Publication publication) {
    }

    private record CachedTransitionPlan(
            Executable handler,
            Class<?> modelType,
            TransitionPlan plan) {
    }

    private record Publication(
            EventPublication eventPublication,
            EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting) {
    }

    private record RelationshipKey(String parentId, String parentType, String path) {
    }

    private record RelationshipUpdate(
            boolean update,
            List<ModelRelationship> relationships) {
        private static final RelationshipUpdate UNCHANGED =
                new RelationshipUpdate(false, List.of());
        private static final RelationshipUpdate CLEARED =
                new RelationshipUpdate(true, List.of());
    }
}
