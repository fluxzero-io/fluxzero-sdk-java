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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.CompleteModelActionMaterialization;
import io.fluxzero.common.api.modeling.GetModelActionMaterialization;
import io.fluxzero.common.api.modeling.GetModelActionMaterializationResult;
import io.fluxzero.common.api.modeling.MaterializeModelAction;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionSubstepResult;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelActionTargetResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDocumentMaterialization;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.modeling.ModelSnapshotMaterialization;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.SearchUtils.parseTimeProperty;

/**
 * Converts a side-effect-free {@link ModelActionEngine} evaluation into one authoritative runtime action package.
 * <p>
 * The original event payload is serialized once per substep. Per-target stream membership remains separate, while
 * global publication is the union of all targeted model publication policies. Optional direct documents and snapshots
 * travel with the same package. The runtime durably retains incomplete materialization work and reports completion
 * before a successful model action returns, preserving immediate direct-search visibility across retries and restarts.
 */
final class ModelActionCommitter {
    private static final int MAX_PENDING_REPAIRS = 10_000;
    private static final int MAX_ACCEPT_REBASE_ATTEMPTS = 10;

    private final EventStoreClient eventStoreClient;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final Serializer snapshotSerializer;
    private final DocumentSerializer documentSerializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final String source;
    private final Function<CommittedAction, CompletableFuture<Void>> afterCommit;
    private final Map<String, PendingCommit> pendingRepairs =
            new ConcurrentHashMap<>();
    private final Semaphore pendingRepairCapacity =
            new Semaphore(MAX_PENDING_REPAIRS);

    ModelActionCommitter(
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source) {
        this(eventStoreClient, documentStore, serializer, documentSerializer,
             dispatchInterceptor, source, serializer,
             ignored -> CompletableFuture.completedFuture(null));
    }

    ModelActionCommitter(
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source,
            Function<CommittedAction, CompletableFuture<Void>> afterCommit) {
        this(eventStoreClient, documentStore, serializer,
             documentSerializer, dispatchInterceptor, source,
             serializer, afterCommit);
    }

    ModelActionCommitter(
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source,
            Serializer snapshotSerializer,
            Function<CommittedAction, CompletableFuture<Void>> afterCommit) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient);
        this.documentStore = Objects.requireNonNull(documentStore);
        this.serializer = Objects.requireNonNull(serializer);
        this.snapshotSerializer = snapshotSerializer;
        this.documentSerializer = Objects.requireNonNull(documentSerializer);
        this.dispatchInterceptor = Objects.requireNonNull(dispatchInterceptor);
        this.source = source;
        this.afterCommit = Objects.requireNonNull(afterCommit);
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId, ModelActionEngine.ActionEvaluation evaluation) {
        return commit(actionId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        return commitPrepared(
                actionId, evaluation,
                prepare(actionId, evaluation, conflictPolicy));
    }

    CompletableFuture<Optional<CommitModelActionResult>> commitAcceptingRebase(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            RebaseEvaluator rebaseEvaluator) {
        Objects.requireNonNull(
                rebaseEvaluator, "rebaseEvaluator");
        PreparedCommit original = prepare(
                actionId, evaluation,
                ModelConflictPolicy.ACCEPT);
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return commitAcceptingRebase(
                actionId, evaluation, original, original,
                rebaseEvaluator, context, 0);
    }

    private CompletableFuture<Optional<CommitModelActionResult>>
            commitAcceptingRebase(
                    String actionId,
                    ModelActionEngine.ActionEvaluation evaluation,
                    PreparedCommit original,
                    PreparedCommit prepared,
                    RebaseEvaluator rebaseEvaluator,
                    ThreadLocalContext.Snapshot context,
                    int attempts) {
        return commitPrepared(
                actionId, evaluation, prepared)
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
                                        "Model action '%s' remained stale after %d apply-only rebases"
                                                .formatted(
                                                        actionId,
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
                            "Model action rebase returned null")
                            .thenCompose(next -> {
                        if (next.readStateIndex()
                            != boundary) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException(
                                            "Model action '%s' rebase loaded state index %d instead of requested %d"
                                                    .formatted(
                                                            actionId,
                                                            next.readStateIndex(),
                                                            boundary)));
                        }
                        PreparedCommit nextPrepared =
                                prepareRebased(
                                        actionId, original, next);
                        return commitAcceptingRebase(
                                actionId, next, original,
                                nextPrepared, rebaseEvaluator,
                                context,
                                attempts + 1);
                    });
                });
    }

    private CompletableFuture<Optional<CommitModelActionResult>>
            commitPrepared(
                    String actionId,
                    ModelActionEngine.ActionEvaluation evaluation,
                    PreparedCommit candidatePrepared) {
        PendingCommit pending = pendingRepairs.get(actionId);
        boolean retainedRepair = pending != null;
        if (pending == null) {
            PreparedCommit prepared =
                    candidatePrepared;
            if (prepared.action() == null) {
                return CompletableFuture.completedFuture(
                        Optional.empty());
            }
            if (!pendingRepairCapacity.tryAcquire()) {
                throw new RejectedExecutionException(
                        "Too many model actions are awaiting commit or direct-document repair");
            }
            PendingCommit candidate =
                    new PendingCommit(evaluation, prepared);
            PendingCommit known =
                    pendingRepairs.putIfAbsent(
                            actionId, candidate);
            if (known != null) {
                pendingRepairCapacity.release();
            }
            pending = known == null
                    ? candidate : known;
            retainedRepair = known != null;
        }
        PendingCommit retained = pending;
        PreparedCommit prepared = pending.prepared();
        boolean repairFromRetainedEvaluation = retainedRepair;
        return eventStoreClient.commitModelAction(prepared.action())
                .thenCompose(result -> {
                    if (!result.isAccepted()) {
                        clearPending(
                                actionId, retained);
                        return CompletableFuture.completedFuture(
                                Optional.of(result));
                    }
                    if (result.isDuplicate()
                        && !repairFromRetainedEvaluation) {
                        clearPending(actionId, retained);
                        return materializationComplete(
                                result)
                                ? CompletableFuture
                                        .completedFuture(
                                                Optional.of(
                                                        result))
                                : repairRetainedMaterialization(
                                        actionId, result)
                                        .thenApply(
                                                ignored ->
                                                        Optional.of(
                                                                result));
                    }
                    boolean sdkOwnsMaterialization =
                            requiresMaterialization(
                                    prepared.action(),
                                    result);
                    MaterializeModelAction materialization =
                            sdkOwnsMaterialization
                                    ? materialization(
                                            prepared.action(),
                                            result)
                                    : null;
                    CompletableFuture<Void> external =
                            materialization == null
                                    ? CompletableFuture
                                            .completedFuture(
                                                    null)
                                    : documentStore
                                            .materializeModelAction(
                                                    materialization);
                    CommitModelActionResult materializedResult =
                            sdkOwnsMaterialization
                                    ? result.withDocumentsApplied()
                                            .withSnapshotsApplied()
                                    : result;
                    return external.thenCompose(ignored ->
                                                         afterCommit.apply(
                                                                 new CommittedAction(
                                                                         retained.evaluation(),
                                                                         prepared,
                                                                         materializedResult)))
                            .thenCompose(ignored ->
                                                 sdkOwnsMaterialization
                                                         ? eventStoreClient
                                                                 .completeModelActionMaterialization(
                                                                         new CompleteModelActionMaterialization(
                                                                                 actionId,
                                                                                 result.getSubsteps()
                                                                                         .getLast()
                                                                                         .getStateIndex()))
                                                         : CompletableFuture
                                                                 .completedFuture(
                                                                         null))
                            .thenApply(ignored -> {
                                clearPending(
                                        actionId, retained);
                                return Optional.of(result);
                            });
                });
    }

    private static boolean materializationComplete(
            CommitModelActionResult result) {
        return result.isDocumentsApplied()
               && result.isSnapshotsApplied();
    }

    private static boolean requiresMaterialization(
            CommitModelAction action,
            CommitModelActionResult result) {
        boolean hasDocuments =
                action.getSubsteps().stream()
                        .flatMap(substep ->
                                         substep.getTargets()
                                                 .stream())
                        .anyMatch(target ->
                                          target.getDocument()
                                          != null);
        boolean hasSnapshots =
                action.getSubsteps().stream()
                        .flatMap(substep ->
                                         substep.getTargets()
                                                 .stream())
                        .anyMatch(target ->
                                          target.getSnapshot()
                                          != null);
        return hasDocuments
               && !result.isDocumentsApplied()
               || hasSnapshots
                  && !result.isSnapshotsApplied();
    }

    private CompletableFuture<Void>
            repairRetainedMaterialization(
                    String actionId,
                    CommitModelActionResult committed) {
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return CompletableFuture.supplyAsync(
                        context.wrap(
                                () -> eventStoreClient
                                        .getModelActionMaterialization(
                                                new GetModelActionMaterialization(
                                                        actionId))))
                .thenCompose(retained -> {
                    validateRetainedMaterialization(
                            retained, committed);
                    if (retained.isComplete()) {
                        return CompletableFuture
                                .completedFuture(null);
                    }
                    MaterializeModelAction materialization =
                            new MaterializeModelAction(
                                    retained.getActionId(),
                                    retained.getLastStateIndex(),
                                    retained.getDocuments(),
                                    retained.getSnapshots());
                    return documentStore
                            .materializeModelAction(
                                    materialization)
                            .thenCompose(ignored ->
                                                 eventStoreClient
                                                         .completeModelActionMaterialization(
                                                                 new CompleteModelActionMaterialization(
                                                                         actionId,
                                                                         retained.getLastStateIndex())));
                });
    }

    private static void validateRetainedMaterialization(
            GetModelActionMaterializationResult retained,
            CommitModelActionResult committed) {
        if (!committed.getActionId()
                .equals(retained.getActionId())) {
            throw new IllegalStateException(
                    "Model materialization repair returned action '%s' for '%s'"
                            .formatted(
                                    retained.getActionId(),
                                    committed.getActionId()));
        }
        long expected =
                committed.getSubsteps().getLast()
                        .getStateIndex();
        if (retained.getLastStateIndex()
            != expected) {
            throw new IllegalStateException(
                    "Model materialization repair for '%s' ends at state index %d instead of %d"
                            .formatted(
                                    committed.getActionId(),
                                    retained.getLastStateIndex(),
                                    expected));
        }
    }

    private static MaterializeModelAction materialization(
            CommitModelAction action,
            CommitModelActionResult result) {
        List<ModelDocumentMaterialization> documents =
                new ArrayList<>();
        List<ModelSnapshotMaterialization> snapshots =
                new ArrayList<>();
        for (int substep = 0;
             substep < action.getSubsteps().size();
             substep++) {
            ModelActionSubstep source =
                    action.getSubsteps().get(
                            substep);
            ModelActionSubstepResult assigned =
                    result.getSubsteps().get(
                            substep);
            for (int target = 0;
                 target < source.getTargets().size();
                 target++) {
                ModelActionTarget mutation =
                        source.getTargets().get(
                                target);
                ModelActionTargetResult position =
                        assigned.getTargets().get(
                                target);
                if (mutation.getDocument() != null) {
                    documents.add(
                            new ModelDocumentMaterialization(
                                    mutation.getModelId(),
                                    assigned.getStateIndex(),
                                    mutation.getDocument()));
                }
                if (mutation.getSnapshot() != null
                    && position.isHistoryComplete()) {
                    snapshots.add(
                            new ModelSnapshotMaterialization(
                                    mutation.getModelId(),
                                    position.getSequenceNumber(),
                                    assigned.getStateIndex(),
                                    mutation.getSnapshot()));
                }
            }
        }
        return new MaterializeModelAction(
                action.getActionId(),
                result.getSubsteps().getLast()
                        .getStateIndex(),
                List.copyOf(documents),
                List.copyOf(snapshots));
    }

    private void clearPending(
            String actionId, PendingCommit pending) {
        if (pendingRepairs.remove(
                actionId, pending)) {
            pendingRepairCapacity.release();
        }
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelActionEngine.ActionEvaluation>> reload) {
        Objects.requireNonNull(conflictResolver, "conflictResolver");
        Objects.requireNonNull(reload, "reload");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Maximum model conflict retries must not be negative");
        }
        ThreadLocalContext.Snapshot context =
                ThreadLocalContext.capture();
        return commit(
                actionId, evaluation, conflictPolicy, conflictResolver,
                maxRetries, reload, context, 0);
    }

    private CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelActionEngine.ActionEvaluation>> reload,
            ThreadLocalContext.Snapshot context,
            int retries) {
        return commit(actionId, evaluation, conflictPolicy).thenCompose(optional -> {
            if (optional.isEmpty() || optional.get().isAccepted()) {
                return CompletableFuture.completedFuture(optional);
            }
            CommitModelActionResult conflict = optional.get();
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
                                    new ModelActionConflictException(
                                            conflict));
                        }
                        return invokeAsync(
                                context,
                                reload,
                                "Model conflict reload returned null")
                                .thenCompose(next -> commit(
                                        actionId, next,
                                        conflictPolicy,
                                        conflictResolver,
                                        maxRetries,
                                        reload,
                                        context,
                                        retries + 1));
                    });
        });
    }

    private static <T> CompletableFuture<T> invokeAsync(
            ThreadLocalContext.Snapshot context,
            Supplier<CompletableFuture<T>> action,
            String nullMessage) {
        return CompletableFuture.supplyAsync(
                        context.wrap(
                                () -> Objects.requireNonNull(
                                        action.get(), nullMessage)))
                .thenCompose(Function.identity());
    }

    PreparedCommit prepare(String actionId, ModelActionEngine.ActionEvaluation evaluation) {
        return prepare(actionId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    PreparedCommit prepare(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        Objects.requireNonNull(actionId, "actionId");
        if (actionId.isBlank()) {
            throw new IllegalArgumentException("Model action ID must not be blank");
        }
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");

        List<ModelActionSubstep> substeps = new ArrayList<>();
        List<List<EffectiveTransition>> transitionGroups = new ArrayList<>();
        List<DeserializingMessage> messages = new ArrayList<>();
        Map<String, Long> nextSequences =
                new LinkedHashMap<>();
        for (int evaluatedSubstep = 0;
             evaluatedSubstep < evaluation.substeps().size();
             evaluatedSubstep++) {
            ModelActionEngine.AppliedSubstep appliedSubstep =
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
            SerializedMessage event = eventRequired ? serialize(appliedSubstep.message()) : null;
            if (event != null) {
                event.setSource(source);
                event.setMetadata(event.getMetadata().with(
                        ModelEventMetadata.ACTION_ID, actionId,
                        ModelEventMetadata.SUBSTEP, substeps.size()));
                applyEventRouting(event, transitions);
            }

            List<ModelActionTarget> targets = new ArrayList<>(transitions.size());
            for (EffectiveTransition transition : transitions) {
                ModelActionEngine.Transition sourceTransition = transition.transition();
                DirectDocument directDocument =
                        transition.updateState()
                                ? directDocument(
                                        sourceTransition,
                                        appliedSubstep
                                                .message()
                                                .getTimestamp(),
                                        appliedSubstep
                                                .message()
                                                .getMetadata())
                                        .map(this::serializeDocument)
                                        .orElse(null)
                                : null;
                long nextSequence = nextSequence(
                        sourceTransition, transition,
                        nextSequences);
                ModelSnapshotMutation snapshot =
                        snapshot(
                                sourceTransition,
                                transition,
                                nextSequence,
                                appliedSubstep.message()
                                        .getTimestamp());
                RelationshipUpdate relationshipUpdate =
                        transition.updateState()
                                ? relationshipUpdate(
                                        sourceTransition)
                                : new RelationshipUpdate(
                                        false, List.of());
                targets.add(ModelActionTarget.builder()
                                    .modelId(sourceTransition.modelId())
                                    .modelType(sourceTransition.modelType().getName())
                                    .storeEvent(transition.storeEvent())
                                    .updateState(
                                            transition
                                                    .updateState())
                                    .delete(
                                            transition
                                                    .updateState()
                                            && sourceTransition
                                                       .after()
                                               == null)
                                    .document(directDocument == null
                                                      ? null : new ModelDocumentMutation(
                                                              directDocument.collection(),
                                                              directDocument.document()))
                                    .snapshot(snapshot)
                                    .updateRelationships(
                                            relationshipUpdate.update())
                                    .relationships(
                                            relationshipUpdate.relationships())
                                    .build());
            }
            substeps.add(ModelActionSubstep.builder()
                                 .event(event)
                                 .publishEvent(publishEvent)
                                 .targets(List.copyOf(targets))
                                 .build());
            transitionGroups.add(transitions);
            messages.add(appliedSubstep.message());
        }
        if (substeps.isEmpty()) {
            return new PreparedCommit(
                    null, List.of(), List.of());
        }
        CommitModelAction action = new CommitModelAction(
                actionId, evaluation.readStateIndex(), evaluation.readModelIds(),
                List.copyOf(substeps), conflictPolicy, STORED);
        return new PreparedCommit(
                action, List.copyOf(transitionGroups),
                List.copyOf(messages));
    }

    private PreparedCommit prepareRebased(
            String actionId,
            PreparedCommit original,
            ModelActionEngine.ActionEvaluation evaluation) {
        if (original.action() == null) {
            throw new IllegalArgumentException(
                    "Cannot rebase an empty model action");
        }
        if (evaluation.substeps().size()
            != original.action().getSubsteps().size()) {
            throw new IllegalStateException(
                    "Apply-only rebase changed the number of model action substeps");
        }
        List<ModelActionSubstep> substeps =
                new ArrayList<>(evaluation.substeps().size());
        List<List<EffectiveTransition>> transitionGroups =
                new ArrayList<>(evaluation.substeps().size());
        Map<String, Long> nextSequences =
                new LinkedHashMap<>();
        for (int substepIndex = 0;
             substepIndex < evaluation.substeps().size();
             substepIndex++) {
            ModelActionEngine.AppliedSubstep rebased =
                    evaluation.substeps().get(substepIndex);
            ModelActionSubstep source =
                    original.action().getSubsteps().get(
                            substepIndex);
            LinkedHashMap<String, ModelActionEngine.Transition>
                    transitionsById = new LinkedHashMap<>();
            for (ModelActionEngine.Transition transition :
                    rebased.transitions()) {
                ModelActionEngine.Transition previous =
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
            List<ModelActionTarget> targets =
                    new ArrayList<>(source.getTargets().size());
            List<EffectiveTransition> effective =
                    new ArrayList<>(source.getTargets().size());
            for (ModelActionTarget originalTarget :
                    source.getTargets()) {
                ModelActionEngine.Transition transition =
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
                DirectDocument directDocument =
                        originalTarget.isUpdateState()
                                ? directDocument(
                                        transition,
                                        rebased.message()
                                                .getTimestamp(),
                                        rebased.message()
                                                .getMetadata())
                                        .map(this::serializeDocument)
                                        .orElse(null)
                                : null;
                EffectiveTransition effectiveTransition =
                        new EffectiveTransition(
                                transition,
                                originalTarget.isStoreEvent(),
                                source.isPublishEvent(),
                                originalTarget
                                        .isUpdateState(),
                                publication(transition)
                                        .eventRouting());
                validateCompleteHistory(
                        effectiveTransition);
                long nextSequence = nextSequence(
                        transition,
                        effectiveTransition,
                        nextSequences);
                RelationshipUpdate relationshipUpdate =
                        effectiveTransition
                                .updateState()
                                ? relationshipUpdate(
                                        transition)
                                : new RelationshipUpdate(
                                        false, List.of());
                targets.add(originalTarget.toBuilder()
                                    .delete(
                                            effectiveTransition
                                                    .updateState()
                                            && transition.after()
                                               == null)
                                    .document(
                                            directDocument == null
                                                    ? null
                                                    : new ModelDocumentMutation(
                                                            directDocument
                                                                    .collection(),
                                                            directDocument
                                                                    .document()))
                                    .snapshot(
                                            snapshot(
                                                    transition,
                                                    effectiveTransition,
                                                    nextSequence,
                                                    rebased.message()
                                                            .getTimestamp()))
                                    .updateRelationships(
                                            relationshipUpdate.update())
                                    .relationships(
                                            relationshipUpdate.relationships())
                                    .build());
                effective.add(effectiveTransition);
            }
            if (!transitionsById.isEmpty()) {
                throw new IllegalStateException(
                        "Apply-only rebase introduced new targets "
                        + transitionsById.keySet());
            }
            substeps.add(source.toBuilder()
                                 .targets(List.copyOf(targets))
                                 .build());
            transitionGroups.add(
                    List.copyOf(effective));
        }
        CommitModelAction action = new CommitModelAction(
                actionId, evaluation.readStateIndex(),
                evaluation.readModelIds(),
                List.copyOf(substeps),
                ModelConflictPolicy.ACCEPT,
                original.action().getGuarantee());
        return new PreparedCommit(
                action,
                List.copyOf(transitionGroups),
                original.messages());
    }

    private SerializedMessage serialize(DeserializingMessage message) {
        SerializedMessage serialized = dispatchInterceptor.modifySerializedMessage(
                message.toMessage().serialize(serializer), message.toMessage(), EVENT, null);
        if (serialized == null) {
            throw new IllegalStateException(
                    "Serialized model event was suppressed after @Apply evaluation; "
                    + "logical event suppression must happen before model applies");
        }
        return serialized;
    }

    private Optional<EffectiveTransition> effectiveTransition(ModelActionEngine.Transition transition) {
        Publication publication = publication(transition);
        boolean modified =
                !Objects.equals(
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
                            publication.eventRouting());
            validateCompleteHistory(result);
            return Optional.of(result);
        }
        EffectiveTransition result =
                switch (publication.publicationStrategy()) {
            case STORE_AND_PUBLISH ->
                    new EffectiveTransition(
                            transition, true, true,
                            true,
                            publication.eventRouting());
            case STORE_ONLY ->
                    new EffectiveTransition(
                            transition, true, false,
                            true,
                            publication.eventRouting());
            case PUBLISH_ONLY ->
                    new EffectiveTransition(
                            transition, false, true,
                            modified,
                            publication.eventRouting());
            case DEFAULT -> throw new IllegalStateException("Unresolved model publication strategy");
        };
        validateCompleteHistory(result);
        return Optional.of(result);
    }

    private static void validateCompleteHistory(
            EffectiveTransition transition) {
        ModelMetadata.RootConfiguration model =
                ModelMetadata.of(
                                transition.transition()
                                        .modelType())
                        .rootConfiguration()
                        .orElseThrow();
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

    private Publication publication(ModelActionEngine.Transition transition) {
        ModelMetadata.RootConfiguration model = ModelMetadata.of(transition.modelType())
                .rootConfiguration().orElseThrow(() -> new IllegalStateException(
                        transition.modelType().getName() + " is not an independent model"));
        Apply apply = transition.handler().getAnnotation(Apply.class);
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
        return new Publication(eventPublication, strategy, routing);
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
            ModelActionEngine.Transition transition) {
        List<ModelRelationship> before =
                relationships(transition.modelId(), transition.before());
        List<ModelRelationship> after =
                relationships(transition.modelId(), transition.after());
        boolean update = transition.after() == null
                         || !before.equals(after);
        return new RelationshipUpdate(
                update, update ? after : List.of());
    }

    private static List<ModelRelationship> relationships(
            String modelId, Object model) {
        if (model == null) {
            return List.of();
        }
        LinkedHashMap<RelationshipKey, ModelRelationship> result = new LinkedHashMap<>();
        for (ModelMetadata.ParentReference parent :
                ModelMetadata.of(model.getClass()).parentReferences()) {
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
            ModelActionEngine.Transition transition,
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
            ModelActionEngine.Transition transition,
            EffectiveTransition effective,
            long nextSequence,
            Instant timestamp) {
        ModelMetadata.RootConfiguration model =
                ModelMetadata.of(transition.modelType())
                        .rootConfiguration()
                        .orElseThrow();
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

    private static Optional<DirectDocumentCandidate> directDocument(
            ModelActionEngine.Transition transition, Instant eventTimestamp, Metadata metadata) {
        ModelMetadata.RootConfiguration model = ModelMetadata.of(transition.modelType())
                .rootConfiguration().orElseThrow();
        if (!model.searchable()) {
            return Optional.empty();
        }
        String collection = Optional.of(model.collection())
                .filter(value -> !value.isEmpty())
                .map(ApplicationProperties::substituteProperties)
                .orElse(transition.modelType().getSimpleName());
        Object value = transition.after();
        if (value == null) {
            return Optional.of(new DirectDocumentCandidate(
                    transition.modelId(), collection, null, null, null, metadata));
        }
        Instant begin = parseTimeProperty(
                blankToNull(model.timestampPath()), value, false, () -> eventTimestamp);
        Instant end = parseTimeProperty(
                blankToNull(model.endPath()), value, true, () -> begin);
        return Optional.of(new DirectDocumentCandidate(
                transition.modelId(), collection, value, begin, end, metadata));
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
            CommitModelAction action,
            List<List<EffectiveTransition>> transitionGroups,
            List<DeserializingMessage> messages) {
    }

    @FunctionalInterface
    interface RebaseEvaluator {
        CompletableFuture<ModelActionEngine.ActionEvaluation> rebase(
                List<DeserializingMessage> messages,
                long stateIndex);
    }

    record CommittedAction(
            ModelActionEngine.ActionEvaluation evaluation,
            PreparedCommit prepared,
            CommitModelActionResult result) {
    }

    private record PendingCommit(
            ModelActionEngine.ActionEvaluation evaluation,
            PreparedCommit prepared) {
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
            ModelActionEngine.Transition transition,
            boolean storeEvent,
            boolean publishEvent,
            boolean updateState,
            AggregateEventRouting eventRouting) {
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
    }
}
