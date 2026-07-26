/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.ModelActionConflict;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionSubstepResult;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelActionTargetResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.fluxzero.common.MessageType.EVENT;
import static java.util.Collections.synchronizedMap;

/**
 * An implementation of the {@link EventStoreClient} interface that provides an in-memory event storage solution. This
 * class extends {@link InMemoryMessageStore} to inherit message store functionality and provides additional
 * capabilities for storing, retrieving, updating, and managing aggregate event streams and relationships in memory.
 * <p>
 * It is designed for use cases where events and relationships are stored and maintained in the application memory,
 * which makes it lightweight but volatile. The stored data will not persist beyond the lifetime of the application
 * process and is typically used in test scenarios or for development purposes.
 */
public class InMemoryEventStore extends InMemoryMessageStore implements EventStoreClient {

    private final Map<String, List<SerializedMessage>> appliedEvents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> relationships = new ConcurrentHashMap<>();
    private final Map<String, CommitModelActionResult> modelActions = new ConcurrentHashMap<>();
    private final Map<String, ModelStreamHead> modelHeads = new ConcurrentHashMap<>();
    private final Map<String, List<ModelStreamHead>> modelHeadHistory = new ConcurrentHashMap<>();
    private final Map<String, List<ModelStreamMembership>> modelStreams = new ConcurrentHashMap<>();
    private final Map<String, ModelGraphProjectionConfiguration> modelGraphProjections =
            new ConcurrentHashMap<>();
    private final List<MutableModelRelationship> modelRelationshipHistory = new ArrayList<>();
    private final Map<String, LinkedHashMap<ModelRelationship, MutableModelRelationship>> currentModelRelationships =
            new ConcurrentHashMap<>();
    private final Map<String, Long> modelRelationStateIndices = new ConcurrentHashMap<>();
    private long modelStateIndex = -1L;

    public InMemoryEventStore() {
        this(Duration.ofMinutes(2));
    }

    public InMemoryEventStore(Duration messageExpiration) {
        super(EVENT, messageExpiration);
    }

    @Override
    public CompletableFuture<Void> storeEvents(String aggregateId, List<SerializedMessage> events, boolean storeOnly,
                                               Guarantee guarantee) {
        appliedEvents.computeIfAbsent(aggregateId, id -> new CopyOnWriteArrayList<>()).addAll(events);
        if (storeOnly) {
            return CompletableFuture.completedFuture(null);
        }
        return super.append(events);
    }

    @Override
    public synchronized CompletableFuture<CommitModelActionResult> commitModelAction(CommitModelAction action) {
        try {
            validate(action);
            CommitModelActionResult previous = modelActions.get(action.getActionId());
            if (previous != null) {
                return CompletableFuture.completedFuture(
                        previous.asDuplicateForRequest(
                                action.getRequestId()));
            }
            if (action.getReadStateIndex() > modelStateIndex) {
                throw new IllegalArgumentException(
                        "Model readStateIndex %d is newer than visible stateIndex %d"
                                .formatted(action.getReadStateIndex(), modelStateIndex));
            }
            ModelConflictPolicy conflictPolicy = ModelConflictPolicy.resolve(action.getConflictPolicy());
            CommitModelActionResult conflict = conflict(
                    action, conflictPolicy);
            if (conflict != null) {
                return CompletableFuture.completedFuture(
                        conflict);
            }

            List<SerializedMessage> publishedEvents = action.getSubsteps().stream()
                    .filter(ModelActionSubstep::isPublishEvent)
                    .map(ModelActionSubstep::getEvent)
                    .toList();
            if (!publishedEvents.isEmpty()) {
                append(publishedEvents).join();
            }

            List<ModelActionSubstepResult> substepResults = new ArrayList<>(action.getSubsteps().size());
            Map<String, Set<ModelRelationship>> actionRelationshipView = new HashMap<>();
            for (int substepNumber = 0; substepNumber < action.getSubsteps().size(); substepNumber++) {
                ModelActionSubstep substep = action.getSubsteps().get(substepNumber);
                long stateIndex = ++modelStateIndex;
                List<ModelActionTargetResult> targetResults = new ArrayList<>(substep.getTargets().size());
                for (ModelActionTarget target : substep.getTargets()) {
                    ModelStreamHead previousHead = modelHeads.getOrDefault(
                            target.getModelId(), new ModelStreamHead(-1L, true));
                    long sequenceNumber = previousHead.sequenceNumber() + (target.isStoreEvent() ? 1L : 0L);
                    boolean historyComplete = previousHead.historyComplete()
                                              && (!target.isUpdateState() || target.isStoreEvent());
                    String modelType = target.getModelType() == null
                            ? previousHead.modelType() : target.getModelType();
                    if (previousHead.modelType() != null
                        && target.getModelType() != null
                        && !previousHead.modelType().equals(target.getModelType())) {
                        throw new IllegalArgumentException(
                                "Model %s already has type %s instead of %s"
                                        .formatted(target.getModelId(), previousHead.modelType(),
                                                   target.getModelType()));
                    }
                    ModelStreamHead head = new ModelStreamHead(
                            modelType, sequenceNumber, stateIndex,
                            historyComplete, target.isDelete());
                    modelHeads.put(target.getModelId(), head);
                    modelHeadHistory.computeIfAbsent(
                            target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(head);
                    updateModelRelationships(
                            action, target, stateIndex, actionRelationshipView);
                    if (target.isStoreEvent()) {
                        appliedEvents.computeIfAbsent(
                                target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(substep.getEvent());
                        modelStreams.computeIfAbsent(
                                target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(
                                new ModelStreamMembership(
                                        sequenceNumber, stateIndex, action.getReadStateIndex(),
                                        action.getActionId(), substepNumber,
                                        substep.getEvent()));
                    }
                    targetResults.add(new ModelActionTargetResult(
                            target.getModelId(), sequenceNumber, historyComplete));
                }
                cascadeDeletedModelRelationships(
                        substep.getTargets().stream()
                                .filter(ModelActionTarget::isDelete)
                                .map(ModelActionTarget::getModelId)
                                .collect(Collectors.toUnmodifiableSet()),
                        stateIndex);
                substepResults.add(new ModelActionSubstepResult(
                        stateIndex,
                        substep.isPublishEvent() ? substep.getEvent().getIndex() : null,
                        List.copyOf(targetResults)));
            }
            CommitModelActionResult result = CommitModelActionResult.accepted(
                    action.getRequestId(), action.getActionId(), List.copyOf(substepResults));
            modelActions.put(action.getActionId(), result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized CompletableFuture<ModelGraphProjectionStatus>
            registerModelGraphProjection(
                    RegisterModelGraphProjection request) {
        ModelGraphProjectionConfiguration configuration =
                request.getConfiguration();
        ModelGraphProjectionConfiguration previous =
                modelGraphProjections.get(
                        configuration.getCollection());
        if (previous != null
            && (!previous.getRootModelType()
                    .equals(
                            configuration
                                    .getRootModelType())
                || !previous.getRootCollection()
                        .equals(
                                configuration
                                        .getRootCollection()))) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Graph projection collection '%s' is already registered for a different root"
                                    .formatted(
                                            configuration
                                                    .getCollection())));
        }
        modelGraphProjections.put(
                configuration.getCollection(),
                configuration);
        return CompletableFuture.completedFuture(
                modelGraphProjectionStatus(
                        request.getRequestId(),
                        configuration.getCollection()));
    }

    @Override
    public synchronized ModelGraphProjectionStatus
            getModelGraphProjectionStatus(
                    GetModelGraphProjectionStatus request) {
        return modelGraphProjectionStatus(
                request.getRequestId(),
                request.getCollection());
    }

    private ModelGraphProjectionStatus
            modelGraphProjectionStatus(
                    long requestId,
                    String collection) {
        if (!modelGraphProjections.containsKey(
                collection)) {
            throw new IllegalArgumentException(
                    "Unknown graph projection collection '%s'"
                            .formatted(collection));
        }
        /*
         * The SDK-only store has no asynchronous search materializer. It accepts definitions so model actions remain
         * fixture-compatible, but deliberately never reports a projection as caught up.
         */
        return new ModelGraphProjectionStatus(
                requestId, collection,
                modelStateIndex, -1L,
                0L, 0L, true);
    }

    private CommitModelActionResult conflict(
            CommitModelAction action, ModelConflictPolicy conflictPolicy) {
        LinkedHashMap<String, ModelActionConflict> conflicts = new LinkedHashMap<>();
        for (String modelId : action.getReadModelIds()) {
            ModelStreamHead head = modelHeads.get(modelId);
            long currentStateIndex = head == null ? -1L : head.stateIndex();
            if (currentStateIndex > action.getReadStateIndex()) {
                conflicts.put(modelId, new ModelActionConflict(
                        modelId, currentStateIndex,
                        modelRelationStateIndices.getOrDefault(modelId, -1L)));
            }
        }
        if (conflicts.isEmpty()) {
            return null;
        }
        boolean relationsUnchanged = true;
        if (conflictPolicy == ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED) {
            for (String modelId : action.getReadModelIds()) {
                long relationStateIndex = modelRelationStateIndices.getOrDefault(modelId, -1L);
                if (relationStateIndex > action.getReadStateIndex()) {
                    relationsUnchanged = false;
                    conflicts.computeIfAbsent(modelId, ignored -> {
                        ModelStreamHead head = modelHeads.get(modelId);
                        return new ModelActionConflict(
                                modelId, head == null ? -1L : head.stateIndex(), relationStateIndex);
                    });
                }
            }
        }
        if (conflictPolicy
            == ModelConflictPolicy.ACCEPT) {
            return CommitModelActionResult.rebase(
                    action.getRequestId(),
                    action.getActionId(),
                    List.copyOf(conflicts.values()),
                    modelStateIndex);
        }
        return CommitModelActionResult.conflict(
                action.getRequestId(), action.getActionId(), List.copyOf(conflicts.values()),
                conflictPolicy == ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED && relationsUnchanged);
    }

    private void updateModelRelationships(
            CommitModelAction action,
            ModelActionTarget target,
            long stateIndex,
            Map<String, Set<ModelRelationship>> actionRelationshipView) {
        if (!target.isDelete()
            && !target.isUpdateRelationships()) {
            return;
        }
        Set<ModelRelationship> desired = Set.copyOf(target.getRelationships());
        Set<ModelRelationship> expected = actionRelationshipView.computeIfAbsent(
                target.getModelId(),
                childId -> modelRelationshipHistory.stream()
                        .filter(relationship ->
                                        relationship.childId.equals(childId)
                                        && relationship.isValidAt(action.getReadStateIndex()))
                        .map(relationship -> relationship.relationship)
                        .collect(Collectors.toUnmodifiableSet()));
        actionRelationshipView.put(target.getModelId(), desired);
        if (expected.equals(desired)) {
            return;
        }

        LinkedHashMap<ModelRelationship, MutableModelRelationship> actual =
                currentModelRelationships.computeIfAbsent(
                        target.getModelId(), ignored -> new LinkedHashMap<>());
        List<ModelRelationship> removed = actual.keySet().stream()
                .filter(relationship -> !desired.contains(relationship))
                .toList();
        modelRelationStateIndices.put(target.getModelId(), stateIndex);
        for (ModelRelationship relationship : removed) {
            actual.remove(relationship).validUntil = stateIndex;
            modelRelationStateIndices.put(relationship.getParentId(), stateIndex);
        }
        for (ModelRelationship relationship : desired) {
            if (!actual.containsKey(relationship)) {
                MutableModelRelationship opened = new MutableModelRelationship(
                        target.getModelId(), relationship, stateIndex);
                actual.put(relationship, opened);
                modelRelationshipHistory.add(opened);
                modelRelationStateIndices.put(relationship.getParentId(), stateIndex);
            }
        }
        if (actual.isEmpty()) {
            currentModelRelationships.remove(target.getModelId());
        }
    }

    private void cascadeDeletedModelRelationships(
            Set<String> deletedParentIds,
            long stateIndex) {
        if (deletedParentIds.isEmpty()) {
            return;
        }
        List<String> emptyChildren = new ArrayList<>();
        currentModelRelationships.forEach((childId, relationships) -> {
            var iterator = relationships.entrySet().iterator();
            while (iterator.hasNext()) {
                MutableModelRelationship relationship =
                        iterator.next().getValue();
                if (!deletedParentIds.contains(
                        relationship.relationship.getParentId())) {
                    continue;
                }
                iterator.remove();
                relationship.validUntil = stateIndex;
                modelRelationStateIndices.put(childId, stateIndex);
                modelRelationStateIndices.put(
                        relationship.relationship.getParentId(),
                        stateIndex);
            }
            if (relationships.isEmpty()) {
                emptyChildren.add(childId);
            }
        });
        emptyChildren.forEach(currentModelRelationships::remove);
    }

    @Override
    public synchronized GetModelEventsResult getModelEvents(GetModelEvents request) {
        validate(request);
        long stateIndex = modelBoundary(
                request.getMaxStateIndex(),
                request.getBoundaryActionId(),
                request.getBoundarySubstep());
        if (stateIndex < -1L || stateIndex > modelStateIndex) {
            throw new IllegalArgumentException(
                    "Model maxStateIndex %d is outside visible range -1..%d"
                            .formatted(stateIndex, modelStateIndex));
        }
        LinkedHashMap<String, List<ModelStreamMembership>> streamCandidates = new LinkedHashMap<>();
        long firstExcludedStateIndex = Long.MAX_VALUE;
        for (var streamRequest : request.getRequests()) {
            List<ModelStreamMembership> candidates = streamRequest.getMaxSize() == 0
                    ? List.of()
                    : modelStreams.getOrDefault(streamRequest.getModelId(), List.of()).stream()
                            .filter(entry -> entry.sequenceNumber() > streamRequest.getLastSequenceNumber())
                            .filter(entry -> entry.stateIndex() <= stateIndex)
                            .limit((long) streamRequest.getMaxSize() + 1L)
                            .toList();
            if (candidates.size() > streamRequest.getMaxSize()) {
                firstExcludedStateIndex = Math.min(
                        firstExcludedStateIndex,
                        candidates.get(streamRequest.getMaxSize()).stateIndex());
                candidates = candidates.subList(0, streamRequest.getMaxSize());
            }
            streamCandidates.put(streamRequest.getModelId(), candidates);
        }

        TreeMap<Long, SerializedMessage> candidatePayloads = new TreeMap<>();
        LinkedHashMap<String, List<ModelEventMembership>> candidateMemberships = new LinkedHashMap<>();
        long stateIndexCutoff = firstExcludedStateIndex;
        streamCandidates.forEach((modelId, candidates) -> candidateMemberships.put(
                modelId, candidates.stream()
                        .filter(entry -> entry.stateIndex() < stateIndexCutoff)
                        .peek(entry -> candidatePayloads.putIfAbsent(entry.stateIndex(), entry.event()))
                        .map(entry -> new ModelEventMembership(
                                entry.sequenceNumber(), entry.stateIndex(), entry.readStateIndex(),
                                entry.actionId(), entry.substep()))
                        .toList()));
        LinkedHashMap<Long, SerializedMessage> payloads =
                selectPayloads(candidatePayloads, request.getMaxBytes());
        Set<Long> selectedStateIndices = payloads.keySet();
        List<ModelEventStream> streams = new ArrayList<>(request.getRequests().size());
        for (var streamRequest : request.getRequests()) {
            ModelStreamHead head = modelHeadHistory.getOrDefault(
                            streamRequest.getModelId(), List.of()).stream()
                    .filter(candidate -> candidate.stateIndex() <= stateIndex)
                    .reduce((first, second) -> second).orElse(null);
            streams.add(new ModelEventStream(
                    streamRequest.getModelId(),
                    head == null ? null : new ModelHeadState(
                            streamRequest.getModelId(), head.modelType(),
                            head.sequenceNumber(), head.stateIndex(),
                            head.historyComplete(), head.deleted()),
                    candidateMemberships.get(streamRequest.getModelId()).stream()
                            .filter(membership -> selectedStateIndices.contains(membership.getStateIndex()))
                            .toList()));
        }
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex,
                payloads.entrySet().stream()
                        .map(entry -> new ModelEventPayload(entry.getKey(), entry.getValue())).toList(),
                List.copyOf(streams));
    }

    @Override
    public synchronized GetModelGraphResult getModelGraph(GetModelGraph request) {
        validate(request);
        long boundary = modelBoundary(
                request.getMaxStateIndex(),
                request.getBoundaryActionId(),
                request.getBoundarySubstep());
        if (boundary < -1L || boundary > modelStateIndex) {
            throw new IllegalArgumentException(
                    "Model maxStateIndex %d is outside visible range -1..%d"
                            .formatted(boundary, modelStateIndex));
        }
        LinkedHashSet<String> modelIds = new LinkedHashSet<>();
        modelIds.add(request.getRootId());
        List<String> frontier = List.of(request.getRootId());
        List<ModelGraphEdge> edges = new ArrayList<>();
        for (int depth = 0; depth < request.getMaxDepth() && !frontier.isEmpty(); depth++) {
            Set<String> parents = Set.copyOf(frontier);
            List<String> next = new ArrayList<>();
            for (MutableModelRelationship relation : modelRelationshipHistory) {
                if (!parents.contains(relation.relationship.getParentId())
                    || !relation.isValidAt(boundary)
                    || request.isComposableOnly()
                       && relation.relationship.getPath() == null) {
                    continue;
                }
                edges.add(new ModelGraphEdge(
                        relation.childId, relation.relationship.getParentId(),
                        relation.relationship.getParentType(), relation.relationship.getPath(),
                        relation.validFrom, relation.validUntil));
                if (modelIds.add(relation.childId)) {
                    if (modelIds.size() > request.getMaxModels()) {
                        throw new IllegalArgumentException(
                                "Model graph exceeds maxModels " + request.getMaxModels());
                    }
                    next.add(relation.childId);
                }
            }
            frontier = next;
        }
        GetModelEventsResult events = getModelEvents(new GetModelEvents(
                modelIds.stream()
                        .map(id -> new ModelEventStreamRequest(
                                id, -1L, request.getMaxEventsPerModel()))
                        .toList(),
                boundary, request.getMaxBytes()));
        return new GetModelGraphResult(
                request.getRequestId(), boundary, List.copyOf(edges),
                events.getPayloads(), events.getStreams());
    }

    @Override
    public synchronized GetModelGraphResult getModelAncestors(
            GetModelAncestors request) {
        validate(request);
        long boundary = modelBoundary(
                request.getMaxStateIndex(),
                request.getBoundaryActionId(),
                request.getBoundarySubstep());
        if (boundary < -1L || boundary > modelStateIndex) {
            throw new IllegalArgumentException(
                    "Model maxStateIndex %d is outside visible range -1..%d"
                            .formatted(boundary, modelStateIndex));
        }
        LinkedHashSet<String> modelIds =
                new LinkedHashSet<>(request.getModelIds());
        List<String> frontier = List.copyOf(modelIds);
        List<ModelGraphEdge> edges = new ArrayList<>();
        for (int depth = 0;
             depth < request.getMaxDepth() && !frontier.isEmpty();
             depth++) {
            Set<String> children = Set.copyOf(frontier);
            List<String> next = new ArrayList<>();
            List<MutableModelRelationship> relationships =
                    modelRelationshipHistory.stream()
                            .filter(relation ->
                                            children.contains(
                                                    relation.childId)
                                            && relation.isValidAt(
                                                    boundary))
                            .sorted(Comparator
                                    .comparing(
                                            (MutableModelRelationship value) ->
                                                    value.childId)
                                    .thenComparing(value ->
                                                           value.relationship
                                                                   .getParentId())
                                    .thenComparing(
                                            value -> value.relationship
                                                    .getPath(),
                                            Comparator.nullsFirst(
                                                    Comparator
                                                            .naturalOrder())))
                            .toList();
            for (MutableModelRelationship relation :
                    relationships) {
                edges.add(new ModelGraphEdge(
                        relation.childId,
                        relation.relationship.getParentId(),
                        relation.relationship.getParentType(),
                        relation.relationship.getPath(),
                        relation.validFrom, relation.validUntil));
                if (modelIds.add(
                        relation.relationship.getParentId())) {
                    if (modelIds.size() > request.getMaxModels()) {
                        throw new IllegalArgumentException(
                                "Model ancestor graph exceeds maxModels "
                                + request.getMaxModels());
                    }
                    next.add(relation.relationship.getParentId());
                }
            }
            frontier = next;
        }
        if (!frontier.isEmpty()) {
            Set<String> truncatedChildren = Set.copyOf(frontier);
            boolean truncated = modelRelationshipHistory.stream()
                    .anyMatch(relation ->
                                      truncatedChildren.contains(
                                              relation.childId)
                                      && relation.isValidAt(boundary));
            if (truncated) {
                throw new IllegalArgumentException(
                        "Model ancestor graph exceeds maxDepth "
                        + request.getMaxDepth());
            }
        }
        GetModelEventsResult events = getModelEvents(new GetModelEvents(
                modelIds.stream()
                        .map(id -> new ModelEventStreamRequest(
                                id, -1L,
                                request.getMaxEventsPerModel()))
                        .toList(),
                boundary, request.getMaxBytes()));
        return new GetModelGraphResult(
                request.getRequestId(), boundary,
                List.copyOf(edges), events.getPayloads(),
                events.getStreams());
    }

    /**
     * Resolves target model IDs from related document matches at the current relationship boundary.
     */
    public synchronized Set<String> resolveRelatedModels(
            Set<String> relatedModelIds,
            ModelRelationConstraint constraint) {
        Objects.requireNonNull(
                relatedModelIds, "Related model IDs");
        Objects.requireNonNull(
                constraint, "Model relation constraint");
        if (relatedModelIds.size()
            > constraint.getMaxRelatedModels()) {
            throw new IllegalArgumentException(
                    "Related model IDs exceed maxRelatedModels "
                    + constraint.getMaxRelatedModels());
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        LinkedHashSet<String> uniqueModels =
                new LinkedHashSet<>(relatedModelIds);
        Set<TraversalState> traversalStates = new HashSet<>();
        List<String> frontier = List.copyOf(relatedModelIds);
        for (int depth = 1;
             depth <= constraint.getMaxDepth()
             && !frontier.isEmpty();
             depth++) {
            Set<String> frontierIds = Set.copyOf(frontier);
            List<MutableModelRelationship> relationshipBatch =
                    modelRelationshipHistory.stream()
                            .filter(relation ->
                                            relation.isValidAt(
                                                    modelStateIndex))
                            .filter(relation ->
                                            constraint.getPaths()
                                                    .isEmpty()
                                            || constraint.getPaths()
                                                    .contains(
                                                            relation.relationship
                                                                    .getPath()))
                            .filter(relation ->
                                            switch (constraint
                                                            .getDirection()) {
                                                case ANCESTOR ->
                                                        frontierIds.contains(
                                                                relation.relationship
                                                                        .getParentId());
                                                case DESCENDANT ->
                                                        frontierIds.contains(
                                                                relation.childId);
                                            })
                            .sorted(Comparator
                                    .comparing(
                                            (MutableModelRelationship value) ->
                                                    value.childId)
                                    .thenComparing(value ->
                                                           value.relationship
                                                                   .getParentId())
                                    .thenComparing(
                                            value -> value.relationship
                                                    .getPath(),
                                            Comparator.nullsFirst(
                                                    Comparator
                                                            .naturalOrder())))
                            .toList();
            LinkedHashSet<String> next = new LinkedHashSet<>();
            for (MutableModelRelationship relation :
                    relationshipBatch) {
                String modelId = switch (constraint
                        .getDirection()) {
                    case ANCESTOR -> relation.childId;
                    case DESCENDANT ->
                            relation.relationship.getParentId();
                };
                if (traversalStates.add(
                        new TraversalState(modelId, depth))) {
                    next.add(modelId);
                    uniqueModels.add(modelId);
                    if (traversalStates.size()
                        > constraint
                                .getMaxTraversedModels()
                        || uniqueModels.size()
                           > constraint
                                   .getMaxTraversedModels()) {
                        throw new IllegalArgumentException(
                                "Model relation traversal exceeds maxTraversedModels "
                                + constraint.getMaxTraversedModels()
                                + "; narrow the query or use a materialized graph projection");
                    }
                }
            }
            if (depth >= constraint.getMinDepth()) {
                result.addAll(next);
            }
            frontier = List.copyOf(next);
        }
        return Set.copyOf(result);
    }

    /**
     * Resolves explicitly placed current child edges for one page of root search results.
     */
    public synchronized List<ModelGraphEdge>
    resolveCurrentGraph(
            Set<String> rootModelIds,
            ModelGraphComposition composition) {
        Objects.requireNonNull(
                rootModelIds, "Model graph roots");
        Objects.requireNonNull(
                composition,
                "Model graph composition");
        LinkedHashSet<String> modelIds =
                new LinkedHashSet<>(
                        rootModelIds);
        if (modelIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Model graph roots are required");
        }
        if (modelIds.size()
            > composition.getMaxModels()) {
            throw new IllegalArgumentException(
                    "Model graph roots exceed maxModels "
                    + composition.getMaxModels());
        }
        List<String> frontier =
                List.copyOf(modelIds);
        List<ModelGraphEdge> edges =
                new ArrayList<>();
        for (int depth = 0;
             depth < composition.getMaxDepth()
             && !frontier.isEmpty();
             depth++) {
            Set<String> parents =
                    Set.copyOf(frontier);
            List<MutableModelRelationship> batch =
                    modelRelationshipHistory.stream()
                            .filter(relation ->
                                            parents.contains(
                                                    relation.relationship
                                                            .getParentId()))
                            .filter(relation ->
                                            relation.isValidAt(
                                                    modelStateIndex))
                            .filter(relation ->
                                            relation.relationship
                                                    .getPath()
                                            != null)
                            .sorted(Comparator
                                    .comparing(
                                            (MutableModelRelationship
                                                     relation) ->
                                                    relation.relationship
                                                            .getParentId())
                                    .thenComparing(
                                            relation ->
                                                    relation.relationship
                                                            .getPath())
                                    .thenComparing(
                                            relation ->
                                                    relation.childId))
                            .toList();
            List<String> next =
                    new ArrayList<>();
            for (MutableModelRelationship relation :
                    batch) {
                edges.add(new ModelGraphEdge(
                        relation.childId,
                        relation.relationship
                                .getParentId(),
                        relation.relationship
                                .getParentType(),
                        relation.relationship
                                .getPath(),
                        relation.validFrom,
                        relation.validUntil));
                if (modelIds.add(
                        relation.childId)) {
                    if (modelIds.size()
                        > composition
                                .getMaxModels()) {
                        throw new IllegalArgumentException(
                                "Model graph exceeds maxModels "
                                + composition
                                        .getMaxModels()
                                + "; narrow the result or use a materialized graph projection");
                    }
                    next.add(
                            relation.childId);
                }
            }
            frontier = next;
        }
        return List.copyOf(edges);
    }

    private long modelBoundary(
            Long maxStateIndex,
            String boundaryActionId,
            Integer boundarySubstep) {
        if (boundaryActionId != null) {
            CommitModelActionResult result =
                    modelActions.get(boundaryActionId);
            if (result == null
                || boundarySubstep >= result.getSubsteps().size()) {
                throw new IllegalArgumentException(
                        "Model action boundary %s[%d] is not visible"
                                .formatted(
                                        boundaryActionId,
                                        boundarySubstep));
            }
            return result.getSubsteps().get(
                    boundarySubstep).getStateIndex();
        }
        return maxStateIndex == null
                ? modelStateIndex : maxStateIndex;
    }

    private static LinkedHashMap<Long, SerializedMessage> selectPayloads(
            TreeMap<Long, SerializedMessage> candidates, long maxBytes) {
        LinkedHashMap<Long, SerializedMessage> result = new LinkedHashMap<>();
        long selectedBytes = 0L;
        for (Map.Entry<Long, SerializedMessage> entry : candidates.entrySet()) {
            long eventBytes = entry.getValue().getBytes();
            if (!result.isEmpty() && maxBytes > 0L && eventBytes > maxBytes - selectedBytes) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
            selectedBytes = eventBytes > Long.MAX_VALUE - selectedBytes
                    ? Long.MAX_VALUE : selectedBytes + eventBytes;
        }
        return result;
    }

    private static void validate(CommitModelAction action) {
        if (action == null) {
            throw new IllegalArgumentException("Model action is required");
        }
        if (action.getActionId() == null || action.getActionId().isBlank()) {
            throw new IllegalArgumentException("Model actionId must not be blank");
        }
        if (action.getReadStateIndex() < -1L) {
            throw new IllegalArgumentException("Model readStateIndex must be at least -1");
        }
        if (action.getGuarantee() == null) {
            throw new IllegalArgumentException("Model action guarantee is required");
        }
        if (action.getReadModelIds() == null) {
            throw new IllegalArgumentException("Model action read model IDs are required");
        }
        Set<String> readModelIds = new HashSet<>();
        for (String modelId : action.getReadModelIds()) {
            validateModelId(modelId);
            if (!readModelIds.add(modelId)) {
                throw new IllegalArgumentException("Duplicate read model ID " + modelId);
            }
        }
        if (action.getSubsteps() == null || action.getSubsteps().isEmpty()) {
            throw new IllegalArgumentException("Model action must contain at least one substep");
        }
        for (int substepNumber = 0; substepNumber < action.getSubsteps().size(); substepNumber++) {
            ModelActionSubstep substep = action.getSubsteps().get(substepNumber);
            if (substep == null || substep.getTargets() == null || substep.getTargets().isEmpty()) {
                throw new IllegalArgumentException(
                        "Model action substep %d has no targets".formatted(substepNumber));
            }
            boolean requiresEvent = substep.isPublishEvent()
                                    || substep.getTargets().stream().anyMatch(ModelActionTarget::isStoreEvent);
            if (requiresEvent && substep.getEvent() == null) {
                throw new IllegalArgumentException(
                        "Model action substep %d requires an event".formatted(substepNumber));
            }
            if (substep.getEvent() != null && substep.getEvent().getIndex() != null) {
                throw new IllegalArgumentException(
                        "Model action substep %d event already has an event index".formatted(substepNumber));
            }
            Set<String> targetIds = new HashSet<>();
            for (ModelActionTarget target : substep.getTargets()) {
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Model action substep %d has a null target".formatted(substepNumber));
                }
                validateModelId(target.getModelId());
                if (!targetIds.add(target.getModelId())) {
                    throw new IllegalArgumentException(
                            "Model action substep %d targets model %s more than once"
                                    .formatted(substepNumber, target.getModelId()));
                }
                if (!readModelIds.contains(target.getModelId())) {
                    throw new IllegalArgumentException(
                            "Target model %s is absent from readModelIds".formatted(target.getModelId()));
                }
                if (!target.isUpdateState()) {
                    throw new IllegalArgumentException(
                            "Target model %s does not update state".formatted(target.getModelId()));
                }
                if (target.getRelationships() == null) {
                    throw new IllegalArgumentException(
                            "Target model %s relationships are required".formatted(target.getModelId()));
                }
                if (target.isDelete() && !target.getRelationships().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Deleted target model %s must not retain parent relationships"
                                    .formatted(target.getModelId()));
                }
                if (target.isDelete() && !target.isUpdateRelationships()) {
                    throw new IllegalArgumentException(
                            "Deleted target model %s must update relationships"
                                    .formatted(target.getModelId()));
                }
                if (!target.isUpdateRelationships()
                    && !target.getRelationships().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Target model %s supplies relationships without update intent"
                                    .formatted(target.getModelId()));
                }
                Set<ModelRelationship> relationships = new HashSet<>();
                for (ModelRelationship relationship : target.getRelationships()) {
                    if (relationship == null
                        || relationship.getParentId() == null
                        || relationship.getParentId().isBlank()) {
                        throw new IllegalArgumentException(
                                "Target model %s has a blank parent relationship"
                                        .formatted(target.getModelId()));
                    }
                    if (!relationships.add(relationship)) {
                        throw new IllegalArgumentException(
                                "Target model %s contains a duplicate parent relationship"
                                        .formatted(target.getModelId()));
                    }
                    if (target.getModelId().equals(relationship.getParentId())) {
                        throw new IllegalArgumentException(
                                "Target model %s cannot be its own parent"
                                        .formatted(target.getModelId()));
                    }
                    if (relationship.getParentType() != null
                        && relationship.getParentType().isBlank()) {
                        throw new IllegalArgumentException(
                                "Target model %s has a blank parent type"
                                        .formatted(target.getModelId()));
                    }
                    if (relationship.getPath() != null && relationship.getPath().isBlank()) {
                        throw new IllegalArgumentException(
                                "Target model %s has a blank relationship path"
                                        .formatted(target.getModelId()));
                    }
                }
            }
        }
    }

    private static void validate(GetModelEvents request) {
        if (request == null) {
            throw new IllegalArgumentException("Model event request is required");
        }
        if (request.getMaxStateIndex() != null && request.getMaxStateIndex() < -1L) {
            throw new IllegalArgumentException("Model state index must be at least -1");
        }
        validateEventBoundary(
                request.getMaxStateIndex(),
                request.getBoundaryActionId(),
                request.getBoundarySubstep());
        if (request.getRequests() == null) {
            throw new IllegalArgumentException("Model stream requests are required");
        }
        if (request.getMaxBytes() < 0L) {
            throw new IllegalArgumentException("Model event request maxBytes must not be negative");
        }
        Set<String> modelIds = new HashSet<>();
        for (var stream : request.getRequests()) {
            if (stream == null) {
                throw new IllegalArgumentException("Model stream request must not be null");
            }
            validateModelId(stream.getModelId());
            if (!modelIds.add(stream.getModelId())) {
                throw new IllegalArgumentException("Duplicate model stream request for " + stream.getModelId());
            }
            if (stream.getLastSequenceNumber() < -1L) {
                throw new IllegalArgumentException("Last model sequence number must be at least -1");
            }
            if (stream.getMaxSize() < 0) {
                throw new IllegalArgumentException("Model stream request maxSize must not be negative");
            }
        }
    }

    private static void validate(GetModelGraph request) {
        if (request == null) {
            throw new IllegalArgumentException("Model graph request is required");
        }
        validateModelId(request.getRootId());
        if (request.getMaxStateIndex() != null && request.getMaxStateIndex() < -1L) {
            throw new IllegalArgumentException("Model state index must be at least -1");
        }
        validateEventBoundary(
                request.getMaxStateIndex(),
                request.getBoundaryActionId(),
                request.getBoundarySubstep());
        if (request.getMaxDepth() < 0 || request.getMaxDepth() > 1_024) {
            throw new IllegalArgumentException("Model graph maxDepth must be between 0 and 1024");
        }
        if (request.getMaxModels() < 1 || request.getMaxModels() > 100_000) {
            throw new IllegalArgumentException("Model graph maxModels must be between 1 and 100000");
        }
        if (request.getMaxEventsPerModel() < 0 || request.getMaxEventsPerModel() > 8_192) {
            throw new IllegalArgumentException(
                    "Model graph maxEventsPerModel must be between 0 and 8192");
        }
        if (request.getMaxBytes() < 0L) {
            throw new IllegalArgumentException("Model graph maxBytes must not be negative");
        }
    }

    private static void validate(GetModelAncestors request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Model ancestor request is required");
        }
        if (request.getModelIds() == null
            || request.getModelIds().isEmpty()) {
            throw new IllegalArgumentException(
                    "Model ancestor roots are required");
        }
        Set<String> roots = new HashSet<>();
        for (String modelId : request.getModelIds()) {
            validateModelId(modelId);
            if (!roots.add(modelId)) {
                throw new IllegalArgumentException(
                        "Duplicate model ancestor root " + modelId);
            }
        }
        if (request.getMaxStateIndex() != null
            && request.getMaxStateIndex() < -1L) {
            throw new IllegalArgumentException(
                    "Model state index must be at least -1");
        }
        validateEventBoundary(
                request.getMaxStateIndex(),
                request.getBoundaryActionId(),
                request.getBoundarySubstep());
        if (request.getMaxDepth() < 1
            || request.getMaxDepth() > 1_024) {
            throw new IllegalArgumentException(
                    "Model ancestor maxDepth must be between 1 and 1024");
        }
        if (request.getMaxModels() < roots.size()
            || request.getMaxModels() > 100_000) {
            throw new IllegalArgumentException(
                    "Model ancestor maxModels must be between root count and 100000");
        }
        if (request.getMaxEventsPerModel() < 0
            || request.getMaxEventsPerModel() > 8_192) {
            throw new IllegalArgumentException(
                    "Model ancestor maxEventsPerModel must be between 0 and 8192");
        }
        if (request.getMaxBytes() < 0L) {
            throw new IllegalArgumentException(
                    "Model ancestor maxBytes must not be negative");
        }
    }

    private static void validateModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Model ID must not be blank");
        }
    }

    private static void validateEventBoundary(
            Long stateIndex,
            String actionId,
            Integer substep) {
        if (stateIndex != null && actionId != null) {
            throw new IllegalArgumentException(
                    "Specify either maxStateIndex or an action boundary, not both");
        }
        if ((actionId == null) != (substep == null)) {
            throw new IllegalArgumentException(
                    "Model action boundary requires both actionId and substep");
        }
        if (actionId != null
            && (actionId.isBlank() || substep < 0)) {
            throw new IllegalArgumentException(
                    "Model action boundary must be non-blank with a non-negative substep");
        }
    }

    @Override
    public CompletableFuture<Void> updateRelationships(UpdateRelationships request) {
        Function<Relationship, Map<String, String>> computeIfAbsent = r -> relationships.computeIfAbsent(
                r.getEntityId(), entityId -> synchronizedMap(new LinkedHashMap<>()));
        request.getDissociations().forEach(r -> computeIfAbsent.apply(r).remove(r.getAggregateId()));
        request.getAssociations().forEach(r -> computeIfAbsent.apply(r).put(r.getAggregateId(), r.getAggregateType()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> repairRelationships(RepairRelationships request) {
        relationships.values().forEach(mapping -> mapping.remove(request.getAggregateId()));
        relationships.values().removeIf(Map::isEmpty);
        request.getEntityIds().forEach(e -> relationships.computeIfAbsent(e, entityId -> synchronizedMap(
                new LinkedHashMap<>())).put(request.getAggregateId(), request.getAggregateType()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public AggregateEventStream<SerializedMessage> getEvents(String aggregateId, long lastSequenceNumber, int maxSize) {
        List<SerializedMessage> allEvents = appliedEvents.getOrDefault(aggregateId, Collections.emptyList());
        var section = allEvents.subList(Math.min(1 + (int) lastSequenceNumber, allEvents.size()), allEvents.size());
        if (maxSize > 0) {
            section = section.stream().limit(maxSize).toList();
        }
        long maxSequenceNumber = lastSequenceNumber + section.size();
        return new AggregateEventStream<>(section.stream(), aggregateId, () -> maxSequenceNumber);
    }

    @Override
    public CompletableFuture<Void> deleteEvents(String aggregateId, Guarantee guarantee) {
        appliedEvents.remove(aggregateId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Map<String, String> getAggregateIds(GetAggregateIds request) {
        return Map.copyOf(relationships.getOrDefault(request.getEntityId(), Collections.emptyMap()));
    }

    @Override
    public List<Relationship> getRelationships(GetRelationships request) {
        return relationships.getOrDefault(request.getEntityId(), Collections.emptyMap()).entrySet().stream()
                .map(e -> Relationship.builder().entityId(request.getEntityId()).aggregateId(e.getKey())
                        .aggregateType(e.getValue()).build()).toList();
    }

    @Override
    public String toString() {
        return "InMemoryEventStore";
    }

    private record ModelStreamHead(
            String modelType, long sequenceNumber, long stateIndex,
            boolean historyComplete, boolean deleted) {
        private ModelStreamHead(long sequenceNumber, boolean historyComplete) {
            this(null, sequenceNumber, -1L, historyComplete, false);
        }
    }

    private record ModelStreamMembership(
            long sequenceNumber,
            long stateIndex,
            long readStateIndex,
            String actionId,
            int substep,
            SerializedMessage event) {
    }

    private record TraversalState(
            String modelId, int depth) {
    }

    private static final class MutableModelRelationship {
        private final String childId;
        private final ModelRelationship relationship;
        private final long validFrom;
        private Long validUntil;

        private MutableModelRelationship(
                String childId, ModelRelationship relationship, long validFrom) {
            this.childId = childId;
            this.relationship = relationship;
            this.validFrom = validFrom;
        }

        private boolean isValidAt(long stateIndex) {
            return validFrom <= stateIndex
                   && (validUntil == null || stateIndex < validUntil);
        }
    }
}
