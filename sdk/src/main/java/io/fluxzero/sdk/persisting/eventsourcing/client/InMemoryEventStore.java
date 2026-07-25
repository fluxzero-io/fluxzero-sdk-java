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
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionSubstepResult;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelActionTargetResult;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

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
                return CompletableFuture.completedFuture(new CommitModelActionResult(
                        action.getRequestId(), previous.getActionId(), previous.getSubsteps()));
            }
            if (action.getReadStateIndex() > modelStateIndex) {
                throw new IllegalArgumentException(
                        "Model readStateIndex %d is newer than visible stateIndex %d"
                                .formatted(action.getReadStateIndex(), modelStateIndex));
            }

            List<SerializedMessage> publishedEvents = action.getSubsteps().stream()
                    .filter(ModelActionSubstep::isPublishEvent)
                    .map(ModelActionSubstep::getEvent)
                    .toList();
            if (!publishedEvents.isEmpty()) {
                append(publishedEvents).join();
            }

            List<ModelActionSubstepResult> substepResults = new ArrayList<>(action.getSubsteps().size());
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
                    ModelStreamHead head = new ModelStreamHead(
                            sequenceNumber, stateIndex, historyComplete, target.isDelete());
                    modelHeads.put(target.getModelId(), head);
                    modelHeadHistory.computeIfAbsent(
                            target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(head);
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
                substepResults.add(new ModelActionSubstepResult(
                        stateIndex,
                        substep.isPublishEvent() ? substep.getEvent().getIndex() : null,
                        List.copyOf(targetResults)));
            }
            CommitModelActionResult result = new CommitModelActionResult(
                    action.getRequestId(), action.getActionId(), List.copyOf(substepResults));
            modelActions.put(action.getActionId(), result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized GetModelEventsResult getModelEvents(GetModelEvents request) {
        validate(request);
        long stateIndex = request.getMaxStateIndex() == null
                ? modelStateIndex : request.getMaxStateIndex();
        if (stateIndex < -1L || stateIndex > modelStateIndex) {
            throw new IllegalArgumentException(
                    "Model maxStateIndex %d is outside visible range -1..%d"
                            .formatted(stateIndex, modelStateIndex));
        }
        LinkedHashMap<Long, SerializedMessage> payloads = new LinkedHashMap<>();
        List<ModelEventStream> streams = new ArrayList<>(request.getRequests().size());
        for (var streamRequest : request.getRequests()) {
            ModelStreamHead head = modelHeadHistory.getOrDefault(
                            streamRequest.getModelId(), List.of()).stream()
                    .filter(candidate -> candidate.stateIndex() <= stateIndex)
                    .reduce((first, second) -> second).orElse(null);
            List<ModelEventMembership> memberships = streamRequest.getMaxSize() == 0
                    ? List.of()
                    : modelStreams.getOrDefault(streamRequest.getModelId(), List.of()).stream()
                            .filter(entry -> entry.sequenceNumber() > streamRequest.getLastSequenceNumber())
                            .filter(entry -> entry.stateIndex() <= stateIndex)
                            .limit(streamRequest.getMaxSize())
                            .peek(entry -> payloads.putIfAbsent(entry.stateIndex(), entry.event()))
                            .map(entry -> new ModelEventMembership(
                                    entry.sequenceNumber(), entry.stateIndex(), entry.readStateIndex(),
                                    entry.actionId(), entry.substep()))
                            .toList();
            streams.add(new ModelEventStream(
                    streamRequest.getModelId(),
                    head == null ? null : new ModelHeadState(
                            streamRequest.getModelId(), head.sequenceNumber(), head.stateIndex(),
                            head.historyComplete(), head.deleted()),
                    memberships));
        }
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex,
                payloads.entrySet().stream()
                        .map(entry -> new ModelEventPayload(entry.getKey(), entry.getValue())).toList(),
                List.copyOf(streams));
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
        if (request.getRequests() == null) {
            throw new IllegalArgumentException("Model stream requests are required");
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

    private static void validateModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Model ID must not be blank");
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
            long sequenceNumber, long stateIndex, boolean historyComplete, boolean deleted) {
        private ModelStreamHead(long sequenceNumber, boolean historyComplete) {
            this(sequenceNumber, -1L, historyComplete, false);
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
}
