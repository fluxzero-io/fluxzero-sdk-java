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
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.SearchUtils.parseTimeProperty;

/**
 * Converts a side-effect-free {@link ModelExecutionPlan} evaluation into one authoritative runtime commit package.
 * <p>
 * The original event payload is serialized once per substep. Per-target stream membership remains separate, while
 * global publication is the union of all targeted model publication policies. Optional direct documents and snapshots
 * travel with the same package. The runtime durably retains incomplete materialization work and reports completion
 * before a successful model commit returns, preserving immediate direct-search visibility across retries and restarts.
 */
final class ModelCommitProtocol {
    private final EventStoreClient eventStoreClient;
    private final Serializer serializer;
    private final Serializer snapshotSerializer;
    private final DocumentSerializer documentSerializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final String source;
    private final Function<List<CommittedCommit>, CompletableFuture<Void>> afterCommits;
    private final ModelCommitBatchingClient.ModelCommitResultProcessor resultProcessor =
            this::processCommitResults;

    ModelCommitProtocol(
            EventStoreClient eventStoreClient,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source) {
        this(eventStoreClient, serializer, documentSerializer,
             dispatchInterceptor, source, serializer,
             ignored -> CompletableFuture.completedFuture(null));
    }

    ModelCommitProtocol(
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
    }

    CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId, CommitAttempt evaluation) {
        return commit(commitId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    CompletableFuture<Optional<CommitModelsResult>> commit(
            String commitId,
            CommitAttempt evaluation,
            ModelConflictPolicy conflictPolicy) {
        return commitPrepared(
                prepare(commitId, evaluation, conflictPolicy),
                null, -1);
    }

    CompletableFuture<Optional<CommitModelsResult>>
            commitPrepared(
                    PreparedCommit prepared,
                    ModelCommitBatchingClient.ModelCommitBatch batch,
                    int batchSlot) {
        if (prepared.commit() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<CommitModelsResult> committed = batch == null
                ? eventStoreClient.commitModels(prepared.commit())
                : batch.add(
                        batchSlot,
                        prepared.commit(),
                        new ModelCommitBatchingClient.ModelCommitCompletion(
                                prepared,
                                resultProcessor));
        if (batch != null) {
            return committed.thenApply(result -> Optional.of(result));
        }
        return committed.thenCompose(result ->
                result.isAccepted()
                        ? processCommits(List.of(new CommittedCommit(
                                prepared, result)))
                                .thenApply(ignored -> Optional.of(result))
                        : CompletableFuture.completedFuture(
                                Optional.of(result)));
    }

    private CompletableFuture<Void> processCommitResults(
            List<CommitModelsResult> results,
            List<Object> contexts) {
        if (results.size() != contexts.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Model commit results and contexts must have equal sizes"));
        }
        List<CommittedCommit> committed = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            Object context = contexts.get(index);
            if (!(context instanceof PreparedCommit prepared)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Unexpected model commit completion context: "
                                + context.getClass().getName()));
            }
            CommitModelsResult result = results.get(index);
            if (result.isAccepted()) {
                committed.add(new CommittedCommit(
                        prepared, result));
            }
        }
        return committed.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : processCommits(committed);
    }

    ModelCommitBatchingClient.ModelCommitBatch beginBatch(int producers) {
        return eventStoreClient instanceof ModelCommitBatchingClient batching
                ? batching.beginModelCommitBatch(producers) : null;
    }

    ModelCommitBatchingClient.ModelCommitBatch beginReadyBatch() {
        return eventStoreClient instanceof ModelCommitBatchingClient batching
                ? batching.beginReadyModelCommitBatch() : null;
    }

    private CompletableFuture<Void> processCommits(
            List<CommittedCommit> committed) {
        return Objects.requireNonNull(
                afterCommits.apply(committed),
                "Model post-commit callback returned null");
    }

    PreparedCommit prepare(String commitId, CommitAttempt evaluation) {
        return prepare(commitId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    PreparedCommit prepare(
            String commitId,
            CommitAttempt evaluation,
            ModelConflictPolicy conflictPolicy) {
        return doPrepare(commitId, evaluation, conflictPolicy);
    }

    private PreparedCommit doPrepare(
            String commitId,
            CommitAttempt evaluation,
            ModelConflictPolicy conflictPolicy) {
        Objects.requireNonNull(commitId, "commitId");
        if (commitId.isBlank()) {
            throw new IllegalArgumentException("Model commit ID must not be blank");
        }
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");

        if (evaluation.stepCount() == 1
            && evaluation.stepChanges(0).size() == 1
            && !isGraphChange(evaluation.stepChanges(0).getFirst())) {
            return prepareSingle(
                    commitId, evaluation, conflictPolicy,
                    evaluation.stepMessage(0), evaluation.stepChanges(0));
        }

        List<DeserializingMessage> evaluatedMessages = new ArrayList<>(evaluation.stepCount());
        List<List<Change>> evaluatedChanges = new ArrayList<>(evaluation.stepCount());
        Map<String, List<Change>> graphPublications = new LinkedHashMap<>();
        Set<String> ordinaryEventIds = new java.util.HashSet<>();
        for (int step = 0; step < evaluation.stepCount(); step++) {
            DeserializingMessage message = evaluation.stepMessage(step);
            List<Change> transitions = evaluation.stepChanges(step).stream()
                    .peek(Change::validate)
                    .filter(Change::active)
                    .toList();
            evaluatedMessages.add(message);
            evaluatedChanges.add(transitions);
            if (transitions.isEmpty()) {
                continue;
            }
            boolean direct = directGraphGroup(transitions);
            if (!direct && transitions.stream().anyMatch(ModelCommitProtocol::isGraphChange)) {
                throw new IllegalStateException(
                        "Direct graph changes must occupy their own evaluated model substep");
            }
            String messageId = message.getMessageId();
            if (direct) {
                List<Change> published = message.getPayload() instanceof Graph<?>
                        ? List.of()
                        : transitions.stream().filter(Change::publishEvent).toList();
                if (!published.isEmpty()) {
                    graphPublications.computeIfAbsent(
                            messageId, ignored -> new ArrayList<>()).addAll(published);
                }
            } else {
                ordinaryEventIds.add(messageId);
            }
        }

        List<ModelCommitStep> protocolSteps = new ArrayList<>();
        List<DeserializingMessage> preparedMessages = new ArrayList<>();
        List<List<Change>> preparedChanges = new ArrayList<>();
        Map<String, Long> nextSequences = new LinkedHashMap<>();
        Set<String> cascadeRoots = evaluation.cascadeRootIds();
        for (int step = 0; step < evaluatedMessages.size(); step++) {
            DeserializingMessage message = evaluatedMessages.get(step);
            List<Change> transitions = evaluatedChanges.get(step);
            if (transitions.isEmpty()) {
                continue;
            }
            boolean direct = directGraphGroup(transitions);
            List<Change> committedTransitions = direct
                    ? transitions.stream().map(transition -> transition.withEffects(
                            transition.storeEvent(), false, transition.updateState())).toList()
                    : transitions;
            List<Change> graphPublished = graphPublications.getOrDefault(
                    message.getMessageId(), List.of());
            if (direct && !graphPublished.isEmpty()
                && !ordinaryEventIds.contains(message.getMessageId())) {
                SerializedMessage publication = serialize(
                        message, commitId, protocolSteps.size(), false);
                publication.setSource(source);
                applyEventRouting(publication, graphPublished);
                publication = SerializedMessage.encode(publication);
                Change anchor = graphPublished.getFirst();
                ModelCommitTarget publicationTarget = target(
                        anchor.withEffects(false, true, false), message,
                        anchor.beforeSequenceNumber(), false)
                        .toBuilder().expectedSequenceNumber(null).build();
                protocolSteps.add(new ModelCommitStep(
                        publication, true, List.of(publicationTarget)));
                preparedMessages.add(message);
                preparedChanges.add(List.of());
            }
            boolean publishEvent = !direct
                                   && (transitions.stream().anyMatch(Change::publishEvent)
                                       || !graphPublished.isEmpty());
            boolean eventRequired = publishEvent
                                    || committedTransitions.stream().anyMatch(Change::storeEvent);
            SerializedMessage event = !eventRequired ? null
                    : direct ? serializeDirectModelUpdate(
                            message, committedTransitions, commitId, protocolSteps.size())
                    : serialize(
                            message, commitId, protocolSteps.size(),
                            transitions.stream().anyMatch(Change::cascadedDeletion));
            if (event != null) {
                event.setSource(source);
                if (!direct) {
                    List<Change> routingTransitions =
                            new ArrayList<>(transitions.size() + graphPublished.size());
                    routingTransitions.addAll(transitions);
                    routingTransitions.addAll(graphPublished);
                    applyEventRouting(event, routingTransitions);
                }
                event = SerializedMessage.encode(event);
            }
            List<ModelCommitTarget> targets = new ArrayList<>(committedTransitions.size());
            for (Change transition : committedTransitions) {
                targets.add(target(
                        transition, message, nextSequences,
                        cascadeRoots.contains(transition.modelId())));
            }
            protocolSteps.add(new ModelCommitStep(event, publishEvent, List.copyOf(targets)));
            preparedMessages.add(message);
            preparedChanges.add(committedTransitions);
        }
        CommitAttempt prepared = preparedAttempt(evaluation, preparedMessages, preparedChanges);
        if (protocolSteps.isEmpty()) {
            return new PreparedCommit(null, prepared);
        }
        CommitModels commit = new CommitModels(
                commitId, evaluation.readStateIndex(), evaluation.readModelIds(),
                List.copyOf(protocolSteps), conflictPolicy, STORED,
                possibleDuplicate(evaluation, preparedChanges));
        return new PreparedCommit(commit, prepared);
    }

    private PreparedCommit prepareSingle(
            String commitId,
            CommitAttempt evaluation,
            ModelConflictPolicy conflictPolicy,
            DeserializingMessage message,
            List<Change> changes) {
        Change transition = changes.getFirst();
        transition.validate();
        CommitAttempt prepared = preparedAttempt(
                evaluation, List.of(message), List.of(changes));
        if (!transition.active()) {
            return new PreparedCommit(null, preparedAttempt(evaluation, List.of(), List.of()));
        }
        boolean eventRequired = transition.publishEvent() || transition.storeEvent();
        SerializedMessage event = eventRequired
                ? serialize(message, commitId, 0, transition.cascadedDeletion()) : null;
        if (event != null) {
            event.setSource(source);
            if (transition.publishEvent()
                && transition.eventRouting() == AggregateEventRouting.AGGREGATE_ID) {
                event.setSegment(ConsistentHashing.computeSegment(transition.modelId()));
            }
            event = SerializedMessage.encode(event);
        }
        long nextSequence = transition.beforeSequenceNumber()
                            + (transition.storeEvent() ? 1L : 0L);
        ModelCommitTarget target = target(
                transition, message, nextSequence,
                evaluation.cascadeRootIds().contains(transition.modelId()));
        ModelCommitStep step = new ModelCommitStep(
                event, transition.publishEvent(), List.of(target));
        CommitModels commit = new CommitModels(
                commitId, evaluation.readStateIndex(), evaluation.readModelIds(),
                List.of(step), conflictPolicy, STORED, possibleDuplicate(transition));
        return new PreparedCommit(commit, prepared);
    }

    private static CommitAttempt preparedAttempt(
            CommitAttempt evaluation,
            List<DeserializingMessage> messages,
            List<List<Change>> changes) {
        CommitAttempt result = CommitAttempt.detached();
        result.evaluated(
                evaluation.readStateIndex(), evaluation.readModelIds(),
                evaluation.readModelTypes(), messages, changes);
        result.cascadeRoots(evaluation.cascadeRootIds());
        return result;
    }

    PreparedCommit prepareRebased(
            String commitId,
            PreparedCommit original,
            CommitAttempt evaluation) {
        if (original.commit() == null) {
            throw new IllegalArgumentException(
                    "Cannot rebase an empty model commit");
        }
        PreparedCommit rebased = doPrepare(
                commitId, evaluation, ModelConflictPolicy.ACCEPT);
        requireSameShape(original, rebased);
        CommitModels candidate = rebased.commit();
        CommitModels commit = new CommitModels(
                candidate.getCommitId(), candidate.getReadStateIndex(),
                candidate.getReadModelIds(), candidate.getSubsteps(),
                candidate.getConflictPolicy(), original.commit().getGuarantee(),
                original.commit().getPossibleDuplicate());
        return new PreparedCommit(
                commit, rebased.attempt());
    }

    private static void requireSameShape(
            PreparedCommit original,
            PreparedCommit rebased) {
        if (rebased.commit() == null
            || original.attempt().stepCount()
               != rebased.attempt().stepCount()) {
            throw changedRebaseShape();
        }
        for (int substep = 0;
             substep < original.attempt().stepCount();
             substep++) {
            ModelCommitStep before = original.commit().getSubsteps().get(substep);
            ModelCommitStep after = rebased.commit().getSubsteps().get(substep);
            if (before.isPublishEvent() != after.isPublishEvent()
                || before.getTargets().size() != after.getTargets().size()) {
                throw changedRebaseShape();
            }
            for (int target = 0; target < before.getTargets().size(); target++) {
                ModelCommitTarget left = before.getTargets().get(target);
                ModelCommitTarget right = after.getTargets().get(target);
                if (!left.getModelId().equals(right.getModelId())
                    || !left.getModelType().equals(right.getModelType())
                    || left.isStoreEvent() != right.isStoreEvent()
                    || left.isUpdateState() != right.isUpdateState()) {
                    throw changedRebaseShape();
                }
            }
        }
    }

    private static IllegalStateException changedRebaseShape() {
        return new IllegalStateException(
                "Apply-only rebase changed the model commit shape");
    }

    private ModelCommitTarget target(
            Change transition,
            DeserializingMessage message,
            Map<String, Long> nextSequences,
            boolean cascadeDelete) {
        return target(
                transition,
                message,
                nextSequence(transition, nextSequences),
                cascadeDelete);
    }

    private ModelCommitTarget target(
            Change transition,
            DeserializingMessage message,
            long nextSequence,
            boolean cascadeDelete) {
        ModelDocumentMutation document = transition.updateState()
                ? directDocument(
                        transition,
                        message.getTimestamp(), message.getMetadata())
                : null;
        RelationshipUpdate relationships = transition.updateState()
                ? relationshipUpdate(transition)
                : RelationshipUpdate.UNCHANGED;
        ModelSnapshotMutation snapshot = snapshot(
                transition, nextSequence,
                message.getTimestamp());
        List<String> aliases = transition.updateState()
                ? transition.defaults().metadata().aliases(transition.after())
                : null;
        return new ModelCommitTarget(
                transition.modelId(),
                transition.modelType().getName(),
                expectedSequenceNumber(transition),
                transition.storeEvent(),
                transition.updateState(),
                transition.updateState()
                && transition.after() == null,
                cascadeDelete
                && transition.updateState()
                && transition.after() == null,
                document,
                snapshot,
                relationships.update(),
                relationships.relationships(),
                aliases);
    }

    private static Long expectedSequenceNumber(
            Change transition) {
        /*
         * A directly loaded document contains the model value but no stream head. For an existing document, -1 would
         * falsely claim that this is a create and force every update through conflict rebase. The commit-wide pinned
         * read boundary already protects the target through readModelIds, so omit only this redundant target-level
         * assertion. Retain explicit -1 for creates: it is both exact and enables the Runtime's missing-head fast path.
         */
        return !transition.defaults().model().eventSourced()
               && transition.before() != null
                ? null : transition.beforeSequenceNumber();
    }

    private SerializedMessage serialize(
            DeserializingMessage message,
            String commitId,
            int substep,
            boolean internalLifecycleEvent) {
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
        SerializedMessage candidate = new SerializedMessage(
                source,
                logicalMessage.getMetadata(),
                logicalMessage.getMessageId(),
                logicalMessage.getTimestamp().toEpochMilli());
        SerializedMessage serialized = internalLifecycleEvent
                ? candidate
                : dispatchInterceptor.modifySerializedMessage(
                        candidate, logicalMessage, EVENT, null);
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

    private SerializedMessage serializeDirectModelUpdate(
            DeserializingMessage sourceMessage,
            List<Change> transitions,
            String commitId,
            int substep) {
        List<DirectModelUpdate.Target> targets = transitions.stream()
                .filter(transition -> transition.storeEvent())
                .map(transition -> new DirectModelUpdate.Target(
                        transition.modelId(),
                        transition.after() == null
                                ? null
                                : serializer.serialize(
                                        transition.after())))
                .toList();
        if (targets.isEmpty()) {
            return null;
        }
        io.fluxzero.sdk.common.Message logical = sourceMessage.toMessage();
        DeserializingMessage direct = new DeserializingMessage(
                new io.fluxzero.sdk.common.Message(
                        new DirectModelUpdate(targets),
                        logical.getMetadata(),
                        logical.getMessageId() + "$direct-model-update",
                        logical.getTimestamp()),
                EVENT, null, serializer);
        return serialize(direct, commitId, substep, true);
    }

    private static boolean directGraphGroup(
            List<Change> transitions) {
        return !transitions.isEmpty()
               && transitions.stream().allMatch(
                       ModelCommitProtocol::isGraphChange);
    }

    private static boolean isGraphChange(
            Change transition) {
        return !transition.cascadedDeletion()
               && (transition.replay() != null
                   || transition.handler() == null
                      && transition.after() == null);
    }

    private static Boolean possibleDuplicate(
            CommitAttempt evaluation,
            List<List<Change>> changesByStep) {
        Long sourceIndex = DeserializingMessage.getOptionally()
                .map(DeserializingMessage::getIndex)
                .orElse(null);
        if (sourceIndex == null
            || changesByStep.stream()
                    .flatMap(List::stream)
                    .anyMatch(transition ->
                                      !transition.storeEvent()
                                      || !transition.publishEvent())) {
            return null;
        }
        return evaluation.transitions().stream()
                .map(Change::beforeLastEventIndex)
                .filter(Objects::nonNull)
                .anyMatch(index -> index >= sourceIndex);
    }

    private static Boolean possibleDuplicate(Change transition) {
        Long sourceIndex = DeserializingMessage.getOptionally()
                .map(DeserializingMessage::getIndex)
                .orElse(null);
        if (sourceIndex == null
            || !transition.storeEvent()
            || !transition.publishEvent()) {
            return null;
        }
        Long beforeLastEventIndex =
                transition.beforeLastEventIndex();
        return beforeLastEventIndex != null
               && beforeLastEventIndex >= sourceIndex;
    }

    private static void applyEventRouting(
            SerializedMessage event,
            List<Change> transitions) {
        List<Change> published = transitions.stream()
                .filter(transition -> transition.publishEvent()).toList();
        if (published.isEmpty()) {
            return;
        }
        boolean aggregateIdRouting = published.stream()
                .anyMatch(transition -> transition.eventRouting()
                        == AggregateEventRouting.AGGREGATE_ID);
        boolean messageRouting = published.stream()
                .anyMatch(transition -> transition.eventRouting()
                        == AggregateEventRouting.MESSAGE_ROUTING_KEY);
        if (aggregateIdRouting && (messageRouting || published.size() != 1)) {
            throw new IllegalStateException(
                    "One model event cannot use conflicting aggregate-ID routing for multiple published targets");
        }
        if (aggregateIdRouting) {
            event.setSegment(ConsistentHashing.computeSegment(
                    published.getFirst().modelId()));
        }
    }

    private static RelationshipUpdate relationshipUpdate(
            Change transition) {
        List<EntityMetadata.ParentReference> parents =
                transition.defaults().metadata().parentReferences();
        if (parents.isEmpty()) {
            return transition.after() == null
                    ? RelationshipUpdate.CLEARED
                    : RelationshipUpdate.UNCHANGED;
        }
        List<ModelRelationship> before =
                relationships(
                        transition.modelId(), transition.before(),
                        parents);
        List<ModelRelationship> after =
                relationships(
                        transition.modelId(), transition.after(),
                        parents);
        boolean update = transition.after() == null
                         || !before.equals(after);
        return new RelationshipUpdate(
                update, update ? after : List.of());
    }

    private static List<ModelRelationship> relationships(
            String modelId,
            Object model,
            List<EntityMetadata.ParentReference> parentReferences) {
        if (model == null) {
            return List.of();
        }
        LinkedHashMap<RelationshipKey, ModelRelationship> result = new LinkedHashMap<>();
        for (EntityMetadata.ParentReference parent : parentReferences) {
            Object parentId = parent.read(model);
            if (parentId == null) {
                continue;
            }
            String parentRepositoryId = parent.repositoryId(parentId);
            Class<?> parentModelType = parent.parentModelType(parentId);
            ModelRelationship relationship = ModelRelationship.builder()
                    .parentId(parentRepositoryId)
                    .parentType(parentModelType == null
                                        ? null : parentModelType.getName())
                    .path(parent.path().isEmpty() ? null : parent.path())
                    .deleteOnParentDeletion(parent.deleteOnParentDeletion())
                    .build();
            if (modelId.equals(relationship.getParentId())) {
                throw new IllegalStateException(
                        "Model '%s' cannot be its own parent".formatted(modelId));
            }
            RelationshipKey key = new RelationshipKey(
                    relationship.getParentId(), relationship.getParentType(), relationship.getPath());
            result.merge(
                    key, relationship,
                    (existing, duplicate) -> existing.isDeleteOnParentDeletion()
                            || !duplicate.isDeleteOnParentDeletion()
                            ? existing
                            : existing.toBuilder()
                                    .deleteOnParentDeletion(true)
                                    .build());
        }
        return List.copyOf(result.values());
    }

    private static long nextSequence(
            Change transition,
            Map<String, Long> nextSequences) {
        long previous = nextSequences.getOrDefault(
                transition.modelId(),
                transition.beforeSequenceNumber());
        long result = previous
                      + (transition.storeEvent()
                                 ? 1L : 0L);
        nextSequences.put(
                transition.modelId(), result);
        return result;
    }

    private ModelSnapshotMutation snapshot(
            Change transition,
            long nextSequence,
            Instant timestamp) {
        EntityMetadata.RootConfiguration model = transition.defaults().model();
        EntityMetadata.SnapshotSettings snapshotSettings = transition.defaults().snapshots();
        if (snapshotSerializer == null
            || !model.eventSourced()
            || !transition.storeEvent()
            || transition.after() == null
            || !snapshotSettings.due(nextSequence, 1)) {
            return null;
        }
        return new ModelSnapshotMutation(
                snapshotSerializer.serialize(
                        transition.after()),
                timestamp.toEpochMilli(),
                snapshotSettings.period(),
                snapshotSettings.maxCount());
    }

    private ModelDocumentMutation directDocument(
            Change transition,
            Instant eventTimestamp,
            Metadata metadata) {
        EntityMetadata.RootConfiguration model = transition.defaults().model();
        if (transition.defaults().collection() == null) {
            return null;
        }
        String collection = transition.defaults().collection();
        Object value = transition.after();
        if (value == null) {
            return new ModelDocumentMutation(collection, null);
        }
        Instant begin = parseTimeProperty(
                blankToNull(model.timestampPath()), value, false, () -> eventTimestamp);
        Instant end = parseTimeProperty(
                blankToNull(model.endPath()), value, true, () -> begin);
        return new ModelDocumentMutation(
                collection,
                documentSerializer.toDocument(
                        value, transition.modelId(), collection,
                        begin, end, metadata));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record PreparedCommit(
            CommitModels commit,
            CommitAttempt attempt) {
        List<DeserializingMessage> rebaseMessages() {
            boolean hasGraphChange = attempt.transitions().stream()
                    .anyMatch(ModelCommitProtocol::isGraphChange);
            if (!hasGraphChange) {
                return attempt.stepMessages();
            }
            List<DeserializingMessage> result = new ArrayList<>(
                    attempt.stepCount() + 1);
            for (int step = 0; step < attempt.stepCount(); step++) {
                List<Change> group = attempt.stepChanges(step);
                if (group.isEmpty()) {
                    continue;
                }
                boolean graphChange = group.stream()
                        .anyMatch(ModelCommitProtocol::isGraphChange);
                if (!graphChange) {
                    result.add(attempt.stepMessage(step));
                    continue;
                }
                DeserializingMessage eventMessage = attempt.stepMessage(step);
                if (group.stream().anyMatch(transition -> !isGraphChange(transition))) {
                    result.add(eventMessage);
                }
                group.stream()
                        .filter(ModelCommitProtocol::isGraphChange)
                        .map(transition -> ModelExecutionPlan.graphChangeReplay(
                                eventMessage,
                                transition.modelId(), transition.modelType(),
                                transition.replay() == null
                                        ? current -> current.update(ignored -> null)
                                        : transition.replay()))
                        .forEach(result::add);
            }
            return List.copyOf(result);
        }

        boolean hasCascadedDeletion() {
            return attempt.transitions().stream()
                    .anyMatch(Change::cascadedDeletion);
        }

        String singleEventMessageId() {
            if (commit == null || commit.getSubsteps().size() != 1) {
                return null;
            }
            SerializedMessage event = commit.getSubsteps().getFirst().getEvent();
            return event == null ? null : event.getMessageId();
        }
    }

    record CommittedCommit(
            PreparedCommit prepared,
            CommitModelsResult result) {
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
