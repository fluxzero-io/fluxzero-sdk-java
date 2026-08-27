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
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.ModelCommitValidator;
import io.fluxzero.common.api.modeling.ModelChangeTarget;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.modeling.ModelCommitAssignment;
import io.fluxzero.common.modeling.ModelCommitConflicts;
import io.fluxzero.common.modeling.ModelRelationshipQueries;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
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

    private boolean deferModelCommitNotification;

    private final Map<String, List<SerializedMessage>> appliedEvents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> relationships = new ConcurrentHashMap<>();
    private final Map<String, CommitModelsResult> modelCommits = new ConcurrentHashMap<>();
    private final Map<Long, Long> modelStateIndicesByEventIndex =
            new ConcurrentHashMap<>();
    private final Map<String, PendingModelMaterialization>
            modelCommitMaterializations =
            new ConcurrentHashMap<>();
    private final List<ModelUpdate> modelUpdates = new ArrayList<>();
    private final Object modelUpdateMonitor = new Object();
    private final AtomicLong modelUpdateGeneration =
            new AtomicLong();
    private final Map<String, ModelStreamHead> modelHeads = new ConcurrentHashMap<>();
    private final Map<String, List<ModelStreamHead>> modelHeadHistory = new ConcurrentHashMap<>();
    private final Map<String, List<ModelStreamMembership>> modelStreams = new ConcurrentHashMap<>();
    private final Map<String, String> modelAliases = new ConcurrentHashMap<>();
    private final Map<String, ModelGraphProjectionConfiguration> modelGraphProjections =
            new ConcurrentHashMap<>();
    private final List<ModelGraphProjectionSignal>
            modelGraphProjectionSignals =
            new ArrayList<>();
    private final Set<String> modelGraphProjectionRebuilds =
            new LinkedHashSet<>();
    private final Map<String, Long>
            modelGraphProjectionPositions =
            new ConcurrentHashMap<>();
    private final Map<String, Throwable>
            modelGraphProjectionFailures =
            new ConcurrentHashMap<>();
    private final List<ModelGraphProjectionWaiter>
            modelGraphProjectionWaiters =
            new ArrayList<>();
    private final ArrayDeque<Runnable>
            modelMaterializationPublications =
            new ArrayDeque<>();
    private boolean modelGraphProjectionDrainActive;
    private boolean modelMaterializationPublicationActive;
    private ModelGraphProjectionMaterializer
            modelGraphProjectionMaterializer;
    private ModelCommitMaterializer modelCommitMaterializer;
    private final Map<String, ModelDeletionResult>
            modelDeletions =
            new ConcurrentHashMap<>();
    private final Set<String> erasedModelTokens =
            ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>>
            protectedModelDescendants =
            new ConcurrentHashMap<>();
    private final List<MutableModelRelationship> modelRelationshipHistory = new ArrayList<>();
    private final Map<String, LinkedHashMap<ModelRelationship, MutableModelRelationship>> currentModelRelationships =
            new ConcurrentHashMap<>();
    private final Map<String, Long> modelRelationStateIndices = new ConcurrentHashMap<>();
    private final LongSupplier modelStateTimeIndexSupplier;
    private long modelStateIndex = -1L;

    public InMemoryEventStore() {
        this(Duration.ofMinutes(2));
    }

    public InMemoryEventStore(Duration messageExpiration) {
        this(messageExpiration, IndexUtils::indexForCurrentTime);
    }

    InMemoryEventStore(
            Duration messageExpiration,
            LongSupplier modelStateTimeIndexSupplier) {
        super(EVENT, messageExpiration);
        this.modelStateTimeIndexSupplier =
                Objects.requireNonNull(
                        modelStateTimeIndexSupplier);
    }

    /**
     * Links the SDK-only event store to its in-memory search materializer.
     */
    public void setModelGraphProjectionMaterializer(
            ModelGraphProjectionMaterializer
                    materializer) {
        synchronized (this) {
            this.modelGraphProjectionMaterializer =
                    Objects.requireNonNull(
                            materializer);
        }
        drainModelGraphProjections();
    }

    /**
     * Links the SDK-only event store to the direct in-memory model materializer.
     */
    public void setModelCommitMaterializer(
            ModelCommitMaterializer materializer) {
        List<String> pending;
        synchronized (this) {
            this.modelCommitMaterializer =
                    Objects.requireNonNull(materializer);
            pending = List.copyOf(
                    modelCommitMaterializations.keySet());
        }
        pending
                .forEach(commitId -> {
                    try {
                        completeModelCommitMaterialization(commitId);
                    } catch (RuntimeException ignored) {
                        // A later duplicate commit retries the retained package.
                    }
                });
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
    public CompletableFuture<CommitModelsResult> commitModels(CommitModels commit) {
        try {
            ModelCommitOutcome outcome = commitModelsSynchronized(commit);
            completeModelCommitMaterialization(
                    commit.getCommitId());
            if (!outcome.publishedEvents().isEmpty()) {
                notifyMonitors(outcome.publishedEvents());
            }
            return CompletableFuture.completedFuture(outcome.result());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private synchronized ModelCommitOutcome commitModelsSynchronized(CommitModels commit) {
            ModelCommitValidator.validate(commit);
            CommitModelsResult previous = modelCommits.get(commit.getCommitId());
            if (previous != null) {
                validateExistingEventDuplicate(commit, previous);
                return new ModelCommitOutcome(
                        modelCommits.get(commit.getCommitId())
                                .asDuplicateForRequest(
                                commit.getRequestId()), List.of());
            }
            Map<Long, SerializedMessage> existingEvents = existingEvents(commit);
            commit.getSubsteps().stream()
                    .flatMap(substep ->
                                     substep.getTargets()
                                             .stream())
                    .map(ModelCommitTarget::getModelId)
                    .filter(modelId ->
                                    erasedModelTokens.contains(
                                            protectedToken(
                                                    modelId)))
                    .findFirst()
                    .ifPresent(modelId -> {
                        throw new IllegalStateException(
                                "Model '%s' was hard-deleted and cannot be recreated"
                                        .formatted(modelId));
                    });
            if (commit.getReadStateIndex() > modelStateIndex) {
                throw new IllegalArgumentException(
                        "Model readStateIndex %d is newer than visible stateIndex %d"
                                .formatted(commit.getReadStateIndex(), modelStateIndex));
            }
            CommitModelsResult conflict = ModelCommitConflicts.result(
                    commit,
                    ModelCommitConflicts.detect(
                            commit, modelHeads,
                            ModelStreamHead::sequenceNumber,
                            ModelStreamHead::stateIndex,
                            modelRelationStateIndices),
                    modelStateIndex);
            if (conflict != null) {
                return new ModelCommitOutcome(conflict, List.of());
            }
            ModelCommitAssignment.Description description =
                    ModelCommitAssignment.describe(commit);
            validateCommitRelationships(description);
            description.aliases().validate(modelAliases);
            List<ModelStreamHead> assignedHeads = new ArrayList<>(
                    commit.getReadModelIds().size());
            ModelCommitAssignment.Commit<ModelStreamHead> assignment =
                    ModelCommitAssignment.session(
                                    modelHeads::get,
                                    (modelId, priorHead, modelType, sequenceNumber,
                                     stateIndex, incomplete, deleted, collection) ->
                                            new ModelStreamHead(
                                                    modelType, sequenceNumber, stateIndex,
                                                    incomplete == null, deleted, collection),
                                    nextModelStateIndex())
                            .assign(description, (step, target, substep, head) ->
                                    assignedHeads.add(head));

            List<SerializedMessage> publishedEvents = commit.getSubsteps().stream()
                    .filter(ModelCommitStep::isPublishEvent)
                    .map(ModelCommitStep::getEvent)
                    .toList();
            if (!publishedEvents.isEmpty()) {
                deferModelCommitNotification = true;
                try {
                    append(publishedEvents).join();
                } finally {
                    deferModelCommitNotification = false;
                }
            }

            CommitModelsResult result = assignment.result();
            List<ModelUpdate> updates = result.getUpdates();
            Map<String, Set<ModelRelationship>> commitRelationshipView = new HashMap<>();
            int headIndex = 0;
            for (int substepNumber = 0; substepNumber < commit.getSubsteps().size(); substepNumber++) {
                ModelCommitStep substep = commit.getSubsteps().get(substepNumber);
                long stateIndex =
                        modelStateIndex =
                                updates.get(substepNumber)
                                        .getStateIndex();
                Long eventIndex = ModelCommitAssignment.eventIndex(substep);
                if (eventIndex != null) {
                    modelStateIndicesByEventIndex.put(
                            eventIndex,
                            stateIndex);
                }
                SerializedMessage storedEvent = eventIndex == null
                        ? substep.getEvent()
                        : existingEvents.getOrDefault(
                                eventIndex, substep.getEvent());
                for (ModelCommitTarget target : substep.getTargets()) {
                    ModelStreamHead head = assignedHeads.get(headIndex++);
                    modelHeads.put(target.getModelId(), head);
                    modelHeadHistory.computeIfAbsent(
                            target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(head);
                    if (target.isStoreEvent()) {
                        appliedEvents.computeIfAbsent(
                                target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(storedEvent);
                        modelStreams.computeIfAbsent(
                                target.getModelId(), ignored -> new CopyOnWriteArrayList<>()).add(
                                new ModelStreamMembership(
                                        head.sequenceNumber(), stateIndex,
                                        commit.getReadStateIndex(),
                                        commit.getCommitId(), substepNumber,
                                        storedEvent));
                    }
                }
                ModelCommitAssignment.RelationshipStep relationshipStep =
                        description.relationshipStep(substepNumber);
                for (ModelCommitAssignment.RelationshipChange change : relationshipStep.changes()) {
                    updateModelRelationships(
                            commit.getReadStateIndex(), change, stateIndex, commitRelationshipView);
                }
                cascadeDeletedModelRelationships(
                        relationshipStep.finalDeletedParentIds(),
                        stateIndex);
            }
            description.aliases().applyTo(modelAliases);
            if (assignment.hasMaterialization()) {
                modelCommitMaterializations.put(
                        commit.getCommitId(),
                        new PendingModelMaterialization(
                                commit, List.copyOf(updates),
                                Set.of()));
            }
            modelCommits.put(commit.getCommitId(), result);
            modelGraphProjectionSignals.add(
                    new ModelGraphProjectionSignal(
                            updates.getFirst()
                                    .getStateIndex(),
                            updates.getLast()
                                    .getStateIndex(),
                            description.targetIds()));
            modelUpdates.addAll(updates);
            modelUpdateGeneration.incrementAndGet();
            synchronized (modelUpdateMonitor) {
                modelUpdateMonitor.notifyAll();
            }
            return new ModelCommitOutcome(
                    modelCommits.get(commit.getCommitId()), publishedEvents);
    }

    private Map<Long, SerializedMessage> existingEvents(
            CommitModels commit) {
        LinkedHashMap<Long, SerializedMessage> result =
                new LinkedHashMap<>();
        for (ModelCommitStep step : commit.getSubsteps()) {
            Long eventIndex = ModelCommitAssignment.eventIndex(step);
            if (eventIndex == null || step.isPublishEvent()) {
                continue;
            }
            SerializedMessage existing = getMessage(eventIndex);
            if (existing == null) {
                throw new IllegalArgumentException(
                        "Existing global event %d is not available"
                                .formatted(eventIndex));
            }
            if (!Objects.equals(
                    existing.getMessageId(),
                    step.getEvent().getMessageId())) {
                throw new IllegalArgumentException(
                        "Existing global event %d has message ID %s instead of %s"
                                .formatted(
                                        eventIndex,
                                        existing.getMessageId(),
                                        step.getEvent().getMessageId()));
            }
            result.put(eventIndex, existing);
        }
        return result;
    }

    private static void validateExistingEventDuplicate(
            CommitModels request,
            CommitModelsResult stored) {
        for (int substep = 0;
             substep < request.getSubsteps().size();
             substep++) {
            ModelCommitStep requested =
                    request.getSubsteps().get(substep);
            Long eventIndex = ModelCommitAssignment.eventIndex(requested);
            if (eventIndex != null
                && !requested.isPublishEvent()
                && !Objects.equals(
                        eventIndex,
                        stored.getUpdates().get(substep)
                                .getEventIndex())) {
                throw new IllegalStateException(
                        "Duplicate model commit '%s' refers to global event %d instead of %s"
                                .formatted(
                                        request.getCommitId(),
                                        eventIndex,
                                        stored.getUpdates().get(substep)
                                                .getEventIndex()));
            }
        }
    }

    @Override
    protected void notifyMonitors(List<SerializedMessage> messages) {
        synchronized (this) {
            if (deferModelCommitNotification) {
                return;
            }
        }
        super.notifyMonitors(messages);
    }

    private record ModelCommitOutcome(
            CommitModelsResult result,
            List<SerializedMessage> publishedEvents) {
    }

    @Override
    public CompletableFuture<TrackModelUpdatesResult> trackModelUpdates(
            TrackModelUpdates request) {
        CompletableFuture<TrackModelUpdatesResult> result =
                new CompletableFuture<>();
        Thread worker = Thread.ofVirtual()
                .name("fluxzero-in-memory-model-updates")
                .unstarted(() -> {
                    long deadline = System.nanoTime()
                                    + Duration.ofMillis(
                                            request.getMaxWaitMillis())
                                            .toNanos();
                    try {
                        while (!result.isDone()) {
                            long generation =
                                    modelUpdateGeneration
                                            .get();
                            TrackModelUpdatesResult page =
                                    modelUpdates(request);
                            if (!page.getUpdates().isEmpty()
                                || request.getMaxWaitMillis() == 0L) {
                                result.complete(page);
                                return;
                            }
                            long remaining =
                                    deadline - System.nanoTime();
                            if (remaining <= 0L) {
                                result.complete(page);
                                return;
                            }
                            synchronized (modelUpdateMonitor) {
                                if (generation
                                    != modelUpdateGeneration
                                            .get()) {
                                    continue;
                                }
                                modelUpdateMonitor.wait(
                                        Math.max(
                                                1L,
                                                Duration.ofNanos(
                                                        remaining)
                                                        .toMillis()));
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread()
                                .interrupt();
                        result.completeExceptionally(e);
                    } catch (Throwable e) {
                        result.completeExceptionally(e);
                    }
                });
        result.whenComplete(
                (ignored, failure) -> {
                    if (result.isCancelled()) {
                        worker.interrupt();
                    }
                });
        worker.start();
        return result;
    }

    private void completeModelCommitMaterialization(
            String commitId) {
        PendingModelMaterialization pending =
                modelCommitMaterializations.get(commitId);
        if (pending == null) {
            return;
        }
        if (modelCommitMaterializer == null) {
            throw new IllegalStateException(
                    "No direct model materializer is connected to the in-memory event store");
        }
        Runnable publication = modelCommitMaterializer.materialize(
                pending.commit(), pending.assignedUpdates(),
                pending.excludedModelIds());
        if (!modelCommitMaterializations.remove(commitId, pending)) {
            return;
        }
        synchronized (this) {
            if (publication != null) {
                modelMaterializationPublications.add(publication);
            }
        }
        drainModelGraphProjections(false);
        publishModelMaterializationNotifications();
    }

    private synchronized TrackModelUpdatesResult modelUpdates(
            TrackModelUpdates request) {
        List<ModelUpdate> updates =
                modelUpdates.stream()
                        .filter(update ->
                                        update.getStateIndex()
                                        > request.getLastStateIndex())
                        .limit(request.getMaxSize())
                        .toList();
        if (request.getMaxBytes() > 0L
            && !updates.isEmpty()) {
            long bytes = 0L;
            int size = 0;
            for (ModelUpdate update : updates) {
                long updateBytes =
                        48L
                        + update.getCommitId()
                                .getBytes(
                                        StandardCharsets.UTF_8)
                                .length;
                for (ModelCommitTargetResult target :
                        update.getTargets()) {
                    updateBytes +=
                            32L
                            + target.getModelId()
                                    .getBytes(
                                            StandardCharsets.UTF_8)
                                    .length;
                }
                if (size > 0
                    && bytes + updateBytes
                       > request.getMaxBytes()) {
                    break;
                }
                bytes += updateBytes;
                size++;
            }
            updates =
                    List.copyOf(
                            updates.subList(
                                    0, size));
        }
        long lastStateIndex =
                updates.isEmpty()
                        ? request.getLastStateIndex()
                        : updates.getLast()
                                .getStateIndex();
        return new TrackModelUpdatesResult(
                request.getRequestId(),
                lastStateIndex,
                modelStateIndex,
                modelStateIndex,
                updates);
    }

    @Override
    public CompletableFuture<ModelGraphProjectionStatus>
            registerModelGraphProjection(
                    RegisterModelGraphProjection request) {
        ModelGraphProjectionConfiguration configuration =
                request.getConfiguration();
        synchronized (this) {
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
            if (previous == null
                || request.isRebuild()
                || !previous.equals(configuration)) {
                modelGraphProjectionRebuilds.add(
                        configuration.getCollection());
            }
        }
        drainModelGraphProjections();
        synchronized (this) {
            return CompletableFuture.completedFuture(
                    modelGraphProjectionStatus(
                            request.getRequestId(),
                            configuration.getCollection()));
        }
    }

    @Override
    public synchronized ModelGraphProjectionStatus
            getModelGraphProjectionStatus(
                    GetModelGraphProjectionStatus request) {
        return modelGraphProjectionStatus(
                request.getRequestId(),
                request.getCollection());
    }

    @Override
    public CompletableFuture<ModelGraphProjectionStatus>
            awaitModelGraphProjection(
                    AwaitModelGraphProjection request) {
        synchronized (this) {
            if (!modelGraphProjections.containsKey(
                    request.getCollection())) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Unknown model graph projection collection "
                                + request.getCollection()));
            }
        }
        drainModelGraphProjections();
        synchronized (this) {
            Throwable failure =
                    modelGraphProjectionFailures.get(
                            request.getCollection());
            if (failure != null) {
                return CompletableFuture.failedFuture(
                        failure);
            }
            if (modelGraphProjectionPositions
                        .getOrDefault(
                                request.getCollection(),
                                -1L)
                >= request.getStateIndex()) {
                return CompletableFuture.completedFuture(
                        modelGraphProjectionStatus(
                                request.getRequestId(),
                                request.getCollection()));
            }
            CompletableFuture<ModelGraphProjectionStatus>
                    result = new CompletableFuture<>();
            ModelGraphProjectionWaiter waiter =
                    new ModelGraphProjectionWaiter(
                            request, result);
            modelGraphProjectionWaiters.add(
                    waiter);
            result.whenComplete(
                    (ignored, ignoredFailure) -> {
                        if (result.isCancelled()) {
                            synchronized (this) {
                                modelGraphProjectionWaiters
                                        .remove(waiter);
                            }
                        }
                    });
            return result;
        }
    }

    @Override
    public synchronized ModelDeletionPlan planModelDeletion(
            PlanModelDeletion request) {
        ModelCommitValidator.validate(request);
        long boundary = modelStateIndex;
        Set<String> selected =
                request.getCascade()
                == ModelDeletionCascade.NONE
                        ? Set.of(
                                request.getModelId())
                        : resolveDeletionIds(
                                request.getModelId(),
                                boundary,
                                request.getMaxDepth(),
                                request.getMaxModels());
        List<String> ordered = selected.stream()
                .sorted()
                .toList();
        int externallyShared = (int) ordered.stream()
                .filter(modelId ->
                                !modelId.equals(
                                        request.getModelId()))
                .filter(modelId ->
                                modelRelationshipHistory.stream()
                                        .anyMatch(relation ->
                                                          relation.childId
                                                                  .equals(
                                                                          modelId)
                                                          && relation.isValidAt(
                                                                  boundary)
                                                          && !selected.contains(
                                                                  relation.relationship
                                                                          .getParentId())))
                .count();
        long memberships = ordered.stream()
                .map(modelStreams::get)
                .filter(Objects::nonNull)
                .mapToLong(List::size)
                .sum();
        long publishedEvents = ordered.stream()
                .map(modelStreams::get)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(membership -> {
                    CommitModelsResult result =
                            modelCommits.get(
                                    membership.commitId());
                    return result == null
                           || membership.substep()
                              >= result.getUpdates()
                                      .size()
                            ? null
                            : result.getUpdates()
                                    .get(
                                            membership.substep())
                                    .getEventIndex();
                })
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return new ModelDeletionPlan(
                request.getRequestId(),
                request.getModelId(),
                request.getCascade(),
                request.getMaxDepth(),
                request.getMaxModels(),
                boundary,
                deletionFingerprint(
                        request.getModelId(),
                        request.getCascade(),
                        ordered),
                ordered.size(),
                externallyShared,
                memberships,
                publishedEvents,
                ordered.stream()
                        .limit(request.getMaxSampleSize())
                        .toList());
    }

    @Override
    public synchronized CompletableFuture<ModelDeletionResult>
            deleteModel(DeleteModel request) {
        try {
            ModelCommitValidator.validate(request);
            ModelDeletionResult duplicate =
                    modelDeletions.get(
                            request.getDeletionId());
            if (duplicate != null) {
                return CompletableFuture
                        .completedFuture(
                                duplicate.forRequest(
                                        request.getRequestId(),
                                        true));
            }
            ModelDeletionPlan plan =
                    planModelDeletion(
                            new PlanModelDeletion(
                                    request.getModelId(),
                                    request.getCascade(),
                                    request.getMaxDepth(),
                                    request.getMaxModels(),
                                    0));
            if (request.getCascade()
                == ModelDeletionCascade.DESCENDANTS
                && !plan.getFingerprint()
                        .equals(
                                request.getPlanFingerprint())) {
                throw new IllegalStateException(
                        "Model deletion plan is stale; create and confirm a new plan");
            }
            Set<String> selected =
                    request.getCascade()
                    == ModelDeletionCascade.NONE
                            ? Set.of(
                                    request.getModelId())
                            : resolveDeletionIds(
                                    request.getModelId(),
                                    modelStateIndex,
                                    request.getMaxDepth(),
                                    request.getMaxModels());
            if (request.getCascade()
                == ModelDeletionCascade.NONE) {
                Set<String> children =
                        modelRelationshipHistory.stream()
                                .filter(relation ->
                                                relation.relationship
                                                        .getParentId()
                                                        .equals(
                                                                request.getModelId()))
                                .filter(relation ->
                                                relation.isValidAt(
                                                        modelStateIndex)
                                                || relation.parentDeleted)
                                .map(relation ->
                                             relation.childId)
                                .filter(childId ->
                                                !selected.contains(
                                                        childId))
                                .collect(
                                        Collectors
                                                .toUnmodifiableSet());
                if (!children.isEmpty()) {
                    protectedModelDescendants
                            .compute(
                                    protectedToken(
                                            request.getModelId()),
                                    (ignored, existing) -> {
                                        LinkedHashSet<String> merged =
                                                new LinkedHashSet<>();
                                        if (existing != null) {
                                            merged.addAll(
                                                    existing);
                                        }
                                        merged.addAll(children);
                                        return Set.copyOf(
                                                merged);
                                    });
                }
            }
            selected.stream()
                    .map(InMemoryEventStore
                                 ::protectedToken)
                    .forEach(erasedModelTokens::add);
            protectedModelDescendants
                    .replaceAll((ignored, children) ->
                                        children.stream()
                                                .filter(childId ->
                                                                !selected.contains(
                                                                        childId))
                                                .collect(
                                                        Collectors
                                                                .toUnmodifiableSet()));
            protectedModelDescendants
                    .entrySet()
                    .removeIf(entry ->
                                      (selected.stream()
                                               .map(InMemoryEventStore
                                                            ::protectedToken)
                                               .anyMatch(entry
                                                                 .getKey()
                                                                 ::equals)
                                       && request.getCascade()
                                          == ModelDeletionCascade.DESCENDANTS)
                                      || entry.getValue()
                                              .isEmpty());
            currentModelRelationships
                    .entrySet()
                    .removeIf(entry -> {
                        if (selected.contains(
                                entry.getKey())) {
                            return true;
                        }
                        entry.getValue()
                                .keySet()
                                .removeIf(
                                        relationship ->
                                                selected.contains(
                                                        relationship
                                                                .getParentId()));
                        return entry.getValue()
                                .isEmpty();
                    });
            modelRelationshipHistory
                    .removeIf(relation ->
                                      selected.contains(
                                              relation.childId)
                                      || selected.contains(
                                              relation.relationship
                                                      .getParentId()));
            selected.forEach(modelHeads::remove);
            selected.forEach(modelHeadHistory::remove);
            selected.forEach(modelStreams::remove);
            selected.forEach(modelRelationStateIndices::remove);
            selected.forEach(appliedEvents::remove);
            modelAliases.entrySet()
                    .removeIf(entry -> selected.contains(
                            entry.getValue()));
            sanitizeModelCommitResults(
                    selected);
            long deletionStateIndex =
                    modelStateIndex =
                            nextModelStateIndex();
            ModelDeletionResult result =
                    new ModelDeletionResult(
                            request.getRequestId(),
                            request.getDeletionId(),
                            request.getCascade(),
                            deletionStateIndex,
                            selected.size(),
                            plan.getStoredEventMembershipCount(),
                            plan.getPublishedEventCount(),
                            false);
            modelDeletions.put(
                    request.getDeletionId(),
                    result);
            modelUpdates.add(
                    new ModelUpdate(
                            ModelUpdateKind.HARD_DELETE,
                            request.getDeletionId(),
                            0,
                            deletionStateIndex,
                            null,
                            List.of()));
            modelUpdateGeneration.incrementAndGet();
            synchronized (modelUpdateMonitor) {
                modelUpdateMonitor.notifyAll();
            }
            return CompletableFuture
                    .completedFuture(result);
        } catch (Throwable failure) {
            return CompletableFuture
                    .failedFuture(failure);
        }
    }

    private long nextModelStateIndex() {
        if (modelStateIndex == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Model state index space is exhausted");
        }
        return Math.max(
                modelStateTimeIndexSupplier
                        .getAsLong(),
                modelStateIndex + 1L);
    }

    private Set<String> resolveDeletionIds(
            String rootId,
            long boundary,
            int maxDepth,
            int maxModels) {
        return ModelRelationshipQueries.descendantLineage(
                List.of(rootId), boundary, maxDepth, maxModels,
                frontier -> relationshipsByParents(frontier, boundary, false),
                frontier -> deletedRelationshipsByParents(frontier, boundary),
                frontier -> frontier.stream()
                        .map(InMemoryEventStore::protectedToken)
                        .map(protectedModelDescendants::get)
                        .filter(Objects::nonNull)
                        .flatMap(Set::stream)
                        .sorted()
                        .toList(),
                "Model deletion plan exceeds maxModels " + maxModels,
                "Model deletion plan exceeds maxDepth " + maxDepth);
    }

    private void sanitizeModelCommitResults(
            Set<String> selected) {
        modelCommits.replaceAll(
                (commitId, result) ->
                        new CommitModelsResult(
                                result.getRequestId(),
                                result.getCommitId(),
                                result.getUpdates()
                                        .stream()
                                        .map(update -> new ModelUpdate(
                                                update.getKind(), update.getCommitId(), update.getSubstep(),
                                                update.getStateIndex(), update.getEventIndex(),
                                                update.getTargets().stream()
                                                        .map(target -> selected.contains(target.getModelId())
                                                                ? new ModelCommitTargetResult(
                                                                        "erased:" + protectedToken(target.getModelId()),
                                                                        target.getSequenceNumber(),
                                                                        target.isHistoryComplete())
                                                                : target)
                                                        .toList()))
                                        .toList(),
                                result.getConflicts(),
                                result.isRetryAllowed(),
                                result.isDuplicate(),
                                result.getRebaseStateIndex()));
        modelCommitMaterializations.replaceAll(
                (commitId, materialization) ->
                        materialization.excluding(selected));
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
        return new ModelGraphProjectionStatus(
                requestId, collection,
                modelStateIndex,
                modelGraphProjectionPositions
                        .getOrDefault(
                                collection, -1L),
                modelGraphProjectionSignals
                        .size(),
                0L,
                modelGraphProjectionMaterializer
                == null
                || modelGraphProjectionRebuilds
                        .contains(collection));
    }

    private void drainModelGraphProjections() {
        drainModelGraphProjections(true);
    }

    private void drainModelGraphProjections(
            boolean publishNotifications) {
        while (true) {
            List<ModelGraphProjectionSignal> signals;
            List<ModelGraphProjectionWork> work =
                    new ArrayList<>();
            ModelGraphProjectionMaterializer materializer;
            long boundary;
            synchronized (this) {
                if (modelGraphProjectionDrainActive
                    || modelGraphProjectionMaterializer == null
                    || !modelCommitMaterializations.isEmpty()) {
                    return;
                }
                if (modelGraphProjections.isEmpty()) {
                    modelGraphProjectionSignals.clear();
                    return;
                }
                modelGraphProjectionDrainActive = true;
                materializer = modelGraphProjectionMaterializer;
                signals = List.copyOf(
                        modelGraphProjectionSignals);
                boundary = modelStateIndex;
                for (ModelGraphProjectionConfiguration configuration :
                        modelGraphProjections.values()) {
                    String collection =
                            configuration.getCollection();
                    boolean rebuild =
                            modelGraphProjectionRebuilds
                                    .contains(collection);
                    if (!rebuild
                        && signals.isEmpty()
                        && modelGraphProjectionPositions
                                   .getOrDefault(
                                           collection, -1L)
                           >= boundary) {
                        continue;
                    }
                    LinkedHashSet<String> roots =
                            rebuild
                                    ? new LinkedHashSet<>(
                                            currentProjectionRoots(
                                                    configuration))
                                    : new LinkedHashSet<>();
                    if (!rebuild) {
                        signals.forEach(signal ->
                                                roots.addAll(
                                                        affectedProjectionRoots(
                                                                configuration,
                                                                signal)));
                    }
                    work.add(new ModelGraphProjectionWork(
                            configuration, Set.copyOf(roots), rebuild));
                }
            }

            Map<String, Throwable> failures =
                    new LinkedHashMap<>();
            Map<String, Runnable> publications =
                    new LinkedHashMap<>();
            for (ModelGraphProjectionWork projection : work) {
                try {
                    publications.put(
                            projection.configuration()
                                    .getCollection(),
                            materializer.materialize(
                                    projection.configuration(),
                                    projection.roots(), boundary,
                                    projection.rebuild()));
                } catch (Throwable failure) {
                    failures.put(
                            projection.configuration()
                                    .getCollection(),
                            failure);
                }
            }

            boolean repeat;
            List<ModelGraphProjectionWaiterCompletion>
                    completed;
            synchronized (this) {
                for (ModelGraphProjectionWork projection : work) {
                    String collection = projection.configuration()
                            .getCollection();
                    Throwable failure = failures.get(collection);
                    if (failure == null) {
                        modelGraphProjectionPositions.put(
                                collection, boundary);
                        modelGraphProjectionFailures.remove(
                                collection);
                        modelGraphProjectionRebuilds.remove(
                                collection);
                        Runnable publication =
                                publications.get(collection);
                        if (publication != null) {
                            modelMaterializationPublications.add(
                                    publication);
                        }
                    } else {
                        modelGraphProjectionFailures.put(
                                collection, failure);
                    }
                }
                if (failures.isEmpty()) {
                    modelGraphProjectionSignals.removeAll(
                            signals);
                }
                modelGraphProjectionDrainActive = false;
                completed = takeCompletedModelGraphProjectionWaiters();
                repeat = failures.isEmpty()
                         && modelCommitMaterializations.isEmpty()
                         && !modelGraphProjectionSignals.isEmpty();
            }
            completeModelGraphProjectionWaiters(completed);
            if (publishNotifications) {
                publishModelMaterializationNotifications();
            }
            if (!repeat) {
                return;
            }
        }
    }

    private void publishModelMaterializationNotifications() {
        synchronized (this) {
            if (modelMaterializationPublicationActive
                || modelMaterializationPublications.isEmpty()) {
                return;
            }
            modelMaterializationPublicationActive = true;
        }
        Throwable firstFailure = null;
        while (true) {
            Runnable publication;
            synchronized (this) {
                publication = modelMaterializationPublications.poll();
                if (publication == null) {
                    modelMaterializationPublicationActive = false;
                    break;
                }
            }
            try {
                publication.run();
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        if (firstFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
        if (firstFailure != null) {
            throw new IllegalStateException(
                    "Failed to publish a materialized Model graph",
                    firstFailure);
        }
    }

    private record ModelGraphProjectionWork(
            ModelGraphProjectionConfiguration configuration,
            Set<String> roots, boolean rebuild) {
    }

    private Set<String> currentProjectionRoots(
            ModelGraphProjectionConfiguration
                    configuration) {
        return modelHeads.entrySet().stream()
                .filter(entry ->
                                !entry.getValue()
                                        .deleted())
                .filter(entry ->
                                configuration
                                        .getRootModelType()
                                        .equals(
                                                entry.getValue()
                                                        .modelType()))
                .map(Map.Entry::getKey)
                .collect(
                        Collectors.toCollection(
                                LinkedHashSet::new));
    }

    private Set<String> affectedProjectionRoots(
            ModelGraphProjectionConfiguration
                    configuration,
            ModelGraphProjectionSignal signal) {
        LinkedHashSet<String> candidates =
                new LinkedHashSet<>(
                        signal.modelIds());
        long before =
                signal.firstStateIndex() - 1L;
        candidates.addAll(
                modelAncestorsAt(
                        signal.modelIds(),
                        before,
                        configuration.getComposition()
                                .getMaxDepth()));
        candidates.addAll(
                modelAncestorsAt(
                        signal.modelIds(),
                        signal.lastStateIndex(),
                        configuration.getComposition()
                                .getMaxDepth()));
        LinkedHashSet<String> roots =
                new LinkedHashSet<>();
        candidates.forEach(modelId -> {
            ModelStreamHead current =
                    modelHeadAt(
                            modelId,
                            signal.lastStateIndex());
            ModelStreamHead previous =
                    modelHeadAt(
                            modelId, before);
            if ((current != null
                 && configuration.getRootModelType()
                         .equals(current.modelType()))
                || (previous != null
                    && configuration.getRootModelType()
                            .equals(previous.modelType()))) {
                roots.add(modelId);
            }
        });
        return Set.copyOf(roots);
    }

    private Set<String> modelAncestorsAt(
            List<String> modelIds,
            long stateIndex,
            int maxDepth) {
        return ModelRelationshipQueries.graphAncestors(
                modelIds, maxDepth,
                frontier -> relationshipsByChildren(frontier, stateIndex));
    }

    private ModelStreamHead modelHeadAt(
            String modelId,
            long stateIndex) {
        return modelHeadHistory.getOrDefault(
                        modelId, List.of())
                .stream()
                .filter(head ->
                                head.stateIndex()
                                <= stateIndex)
                .reduce((first, second) ->
                                second)
                .orElse(null);
    }

    private List<ModelGraphProjectionWaiterCompletion>
            takeCompletedModelGraphProjectionWaiters() {
        List<ModelGraphProjectionWaiter> completed =
                modelGraphProjectionWaiters.stream()
                        .filter(waiter ->
                                        modelGraphProjectionFailures
                                                .containsKey(
                                                        waiter.request()
                                                                .getCollection())
                                        || modelGraphProjectionPositions
                                                   .getOrDefault(
                                                           waiter.request()
                                                                   .getCollection(),
                                                           -1L)
                                           >= waiter.request()
                                                   .getStateIndex())
                        .toList();
        modelGraphProjectionWaiters.removeAll(
                completed);
        return completed.stream().map(waiter -> {
            Throwable failure =
                    modelGraphProjectionFailures.get(
                            waiter.request()
                                    .getCollection());
            ModelGraphProjectionStatus status = failure == null
                    ? modelGraphProjectionStatus(
                            waiter.request().getRequestId(),
                            waiter.request().getCollection())
                    : null;
            return new ModelGraphProjectionWaiterCompletion(
                    waiter, status, failure);
        }).toList();
    }

    private static void completeModelGraphProjectionWaiters(
            List<ModelGraphProjectionWaiterCompletion> completed) {
        completed.forEach(completion -> {
            if (completion.failure() == null) {
                completion.waiter().result()
                        .complete(completion.status());
            } else {
                completion.waiter().result()
                        .completeExceptionally(
                                completion.failure());
            }
        });
    }

    private record ModelGraphProjectionWaiterCompletion(
            ModelGraphProjectionWaiter waiter,
            ModelGraphProjectionStatus status,
            Throwable failure) {
    }

    private void validateCommitRelationships(
            ModelCommitAssignment.Description description) {
        ModelCommitAssignment.validateRelationships(
                List.of(description),
                children -> children.stream().collect(Collectors.toUnmodifiableMap(
                        child -> child, this::currentParentIds)),
                parents -> currentModelRelationships.entrySet().stream()
                        .filter(entry -> entry.getValue().keySet().stream()
                                .map(ModelRelationship::getParentId)
                                .anyMatch(parents::contains))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private Set<String> currentParentIds(String childId) {
        Map<ModelRelationship, MutableModelRelationship> relationships =
                currentModelRelationships.get(childId);
        return relationships == null
                ? Set.of()
                : relationships.keySet().stream()
                .map(ModelRelationship::getParentId)
                .collect(
                        Collectors.toUnmodifiableSet());
    }

    private void updateModelRelationships(
            long readStateIndex,
            ModelCommitAssignment.RelationshipChange change,
            long stateIndex,
            Map<String, Set<ModelRelationship>> commitRelationshipView) {
        Set<ModelRelationship> desired = change.desired();
        Set<ModelRelationship> expected = commitRelationshipView.computeIfAbsent(
                change.childId(),
                childId -> modelRelationshipHistory.stream()
                        .filter(relationship ->
                                        relationship.childId.equals(childId)
                                        && relationship.isValidAt(readStateIndex))
                        .map(relationship -> relationship.relationship)
                        .collect(Collectors.toUnmodifiableSet()));
        commitRelationshipView.put(change.childId(), desired);
        if (expected.equals(desired)) {
            return;
        }

        LinkedHashMap<ModelRelationship, MutableModelRelationship> actual =
                currentModelRelationships.computeIfAbsent(
                        change.childId(), ignored -> new LinkedHashMap<>());
        List<ModelRelationship> removed = actual.keySet().stream()
                .filter(relationship -> !desired.contains(relationship))
                .toList();
        modelRelationStateIndices.put(change.childId(), stateIndex);
        for (ModelRelationship relationship : removed) {
            actual.remove(relationship).validUntil = stateIndex;
            modelRelationStateIndices.put(relationship.getParentId(), stateIndex);
        }
        for (ModelRelationship relationship : desired) {
            if (!actual.containsKey(relationship)) {
                MutableModelRelationship opened = new MutableModelRelationship(
                        change.childId(), relationship, stateIndex);
                actual.put(relationship, opened);
                modelRelationshipHistory.add(opened);
                modelRelationStateIndices.put(relationship.getParentId(), stateIndex);
            }
        }
        if (actual.isEmpty()) {
            currentModelRelationships.remove(change.childId());
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
                relationship.parentDeleted = true;
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
        ModelCommitValidator.validate(request);
        ModelRelationshipQueries.ResolvedBoundary resolved =
                modelBoundary(request.getBoundary());
        long stateIndex = resolved.stateIndex();
        if (stateIndex < -1L || stateIndex > modelStateIndex) {
            throw new IllegalArgumentException(
                    "Model maxStateIndex %d is outside visible range -1..%d"
                            .formatted(stateIndex, modelStateIndex));
        }
        LinkedHashMap<String, String> resolvedModelIds = new LinkedHashMap<>();
        request.getRequests().forEach(streamRequest ->
                resolvedModelIds.put(
                        streamRequest.getModelId(),
                        resolveModelId(streamRequest.getModelId(), stateIndex)));
        LinkedHashMap<String, List<ModelStreamMembership>> streamCandidates = new LinkedHashMap<>();
        long firstExcludedStateIndex = Long.MAX_VALUE;
        for (var streamRequest : request.getRequests()) {
            String resolvedModelId = resolvedModelIds.get(
                    streamRequest.getModelId());
            List<ModelStreamMembership> candidates = streamRequest.getMaxSize() == 0
                    ? List.of()
                    : modelStreams.getOrDefault(resolvedModelId, List.of()).stream()
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
                                entry.commitId(), entry.substep()))
                        .toList()));
        LinkedHashMap<Long, SerializedMessage> payloads =
                selectPayloads(candidatePayloads, request.getMaxBytes());
        Set<Long> selectedStateIndices = payloads.keySet();
        List<ModelEventStream> streams = new ArrayList<>(request.getRequests().size());
        for (var streamRequest : request.getRequests()) {
            String resolvedModelId = resolvedModelIds.get(
                    streamRequest.getModelId());
            ModelStreamHead head = modelHeadHistory.getOrDefault(
                            resolvedModelId, List.of()).stream()
                    .filter(candidate -> candidate.stateIndex() <= stateIndex)
                    .reduce((first, second) -> second).orElse(null);
            streams.add(new ModelEventStream(
                    streamRequest.getModelId(),
                    head == null ? null : new ModelHeadState(
                            resolvedModelId, head.modelType(),
                            head.sequenceNumber(), head.stateIndex(),
                            head.historyComplete(), head.deleted()),
                    candidateMemberships.get(streamRequest.getModelId()).stream()
                            .filter(membership -> selectedStateIndices.contains(membership.getStateIndex()))
                            .toList()));
        }
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex, resolved.exact(),
                payloads.entrySet().stream()
                        .map(entry -> new ModelEventPayload(entry.getKey(), entry.getValue())).toList(),
                List.copyOf(streams));
    }

    private String resolveModelId(
            String requestedModelId,
            long stateIndex) {
        boolean primaryExists = modelHeadHistory
                .getOrDefault(requestedModelId, List.of())
                .stream()
                .anyMatch(head -> head.stateIndex() <= stateIndex);
        return primaryExists
                ? requestedModelId
                : modelAliases.getOrDefault(
                        requestedModelId, requestedModelId);
    }

    @Override
    public synchronized GetModelGraphResult getModelGraph(GetModelGraph request) {
        ModelCommitValidator.validate(request);
        ModelRelationshipQueries.ResolvedBoundary resolved =
                modelBoundary(request.getBoundary());
        long boundary = resolved.stateIndex();
        boolean before = request.getBoundary().before();
        long minimumBoundary = before ? 0L : -1L;
        if (boundary < minimumBoundary || boundary > modelStateIndex) {
            throw new IllegalArgumentException(
                    (before
                            ? "Model before-state boundary %d is outside visible range 0..%d"
                            : "Model maxStateIndex %d is outside visible range -1..%d")
                            .formatted(boundary, modelStateIndex));
        }
        return getModelGraph(request, boundary, resolved.exact(), before);
    }

    private GetModelGraphResult getModelGraph(
            GetModelGraph request,
            long boundary,
            boolean exactBoundary,
            boolean before) {
        return ModelRelationshipQueries.graph(
                request, boundary, exactBoundary,
                frontier -> request.getDirection() == GetModelGraph.TraversalDirection.ANCESTORS
                        ? relationshipsByChildren(frontier, boundary)
                        : relationshipsByParents(frontier, boundary, before),
                this::getModelEvents);
    }

    @Override
    public synchronized GetModelChangeResult getModelChange(
            GetModelChange request) {
        ModelCommitValidator.validate(request);
        CommitModelsResult commit = modelCommits.get(request.getCommitId());
        if (commit == null || request.getSubstep() >= commit.getUpdates().size()) {
            throw new IllegalArgumentException(
                    "Model commit boundary %s[%d] is not visible"
                            .formatted(request.getCommitId(), request.getSubstep()));
        }
        ModelUpdate step = commit.getUpdates().get(request.getSubstep());
        List<ModelChangeTarget> targets = step.getTargets().stream()
                .map(target -> {
                    ModelStreamHead head = modelHeadHistory
                            .getOrDefault(target.getModelId(), List.of()).stream()
                            .filter(candidate -> candidate.stateIndex() <= step.getStateIndex())
                            .reduce((first, second) -> second).orElse(null);
                    return new ModelChangeTarget(
                            target.getModelId(), head == null ? null : head.modelType());
                }).toList();
        return new GetModelChangeResult(
                request.getRequestId(), request.getCommitId(), request.getSubstep(),
                step.getStateIndex(), step.getEventIndex(), targets);
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
        return ModelRelationshipQueries.relatedModels(
                relatedModelIds, constraint,
                frontier -> relationshipsByParents(
                        frontier, modelStateIndex, false),
                frontier -> relationshipsByChildren(
                        frontier, modelStateIndex));
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
        return ModelRelationshipQueries.currentGraph(
                rootModelIds, composition.getMaxDepth(), composition.getMaxModels(),
                frontier -> relationshipsByParents(
                        frontier, modelStateIndex, false)).edges();
    }

    /**
     * Resolves the exact current-document collection for each requested model.
     */
    public synchronized Map<String, String>
    resolveModelDocumentCollections(
            Set<String> modelIds) {
        LinkedHashMap<String, String> result =
                new LinkedHashMap<>();
        modelIds.forEach(modelId -> {
            ModelStreamHead head =
                    modelHeads.get(modelId);
            if (head != null
                && !head.deleted()
                && head.documentCollection() != null) {
                result.put(
                        modelId,
                        head.documentCollection());
            }
        });
        return Map.copyOf(result);
    }

    private List<MutableModelRelationship> relationshipsByParents(
            Collection<String> parentIds,
            long stateIndex,
            boolean before) {
        Set<String> parents = Set.copyOf(parentIds);
        return modelRelationshipHistory.stream()
                .filter(relation -> parents.contains(relation.parentId()))
                .filter(relation -> before
                        ? relation.isValidBefore(stateIndex)
                        : relation.isValidAt(stateIndex))
                .toList();
    }

    private List<MutableModelRelationship> relationshipsByChildren(
            Collection<String> childIds,
            long stateIndex) {
        Set<String> children = Set.copyOf(childIds);
        return modelRelationshipHistory.stream()
                .filter(relation -> children.contains(relation.childId())
                                    && relation.isValidAt(stateIndex))
                .toList();
    }

    private List<MutableModelRelationship> deletedRelationshipsByParents(
            Collection<String> parentIds,
            long stateIndex) {
        Set<String> parents = Set.copyOf(parentIds);
        return modelRelationshipHistory.stream()
                .filter(relation -> parents.contains(relation.parentId()))
                .filter(relation -> relation.parentDeleted
                                    && relation.validUntil != null
                                    && relation.validUntil <= stateIndex)
                .sorted(Comparator.comparing(MutableModelRelationship::childId))
                .toList();
    }

    private ModelRelationshipQueries.ResolvedBoundary modelBoundary(ModelReadBoundary boundary) {
        return ModelRelationshipQueries.resolveBoundaryWithEvidence(
                boundary, false, () -> modelStateIndex,
                (commitId, substep) -> {
                    CommitModelsResult result = modelCommits.get(commitId);
                    return result == null || substep >= result.getUpdates().size()
                            ? null : result.getUpdates().get(substep).getStateIndex();
                }, modelStateIndicesByEventIndex::get);
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

    private static String deletionFingerprint(
            String rootId,
            ModelDeletionCascade cascade,
            List<String> orderedIds) {
        return ModelRelationshipQueries.deletionFingerprint(
                rootId, cascade, orderedIds);
    }

    private static String protectedToken(
            String modelId) {
        return deletionFingerprint(
                "model-erasure",
                ModelDeletionCascade.NONE,
                List.of(modelId));
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

    private record ModelStreamMembership(
            long sequenceNumber,
            long stateIndex,
            long readStateIndex,
            String commitId,
            int substep,
            SerializedMessage event) {
    }

    private record ModelGraphProjectionSignal(
            long firstStateIndex,
            long lastStateIndex,
            List<String> modelIds) {
    }

    private record ModelGraphProjectionWaiter(
            AwaitModelGraphProjection request,
            CompletableFuture<ModelGraphProjectionStatus>
                    result) {
    }

    private record PendingModelMaterialization(
            CommitModels commit,
            List<ModelUpdate> assignedUpdates,
            Set<String> excludedModelIds) {
        private PendingModelMaterialization excluding(
                Set<String> modelIds) {
            if (modelIds.isEmpty()) {
                return this;
            }
            LinkedHashSet<String> excluded =
                    new LinkedHashSet<>(
                            excludedModelIds);
            excluded.addAll(modelIds);
            return new PendingModelMaterialization(
                    commit, assignedUpdates,
                    Set.copyOf(excluded));
        }
    }

    private record ModelStreamHead(
            String modelType, long sequenceNumber, long stateIndex,
            boolean historyComplete, boolean deleted, String documentCollection)
            implements ModelCommitAssignment.Head {
    }

    /**
     * Applies direct model documents and due snapshots, returning their local-handler publication for execution after
     * the materialized state and graph-projection fences have advanced.
     */
    @FunctionalInterface
    public interface ModelCommitMaterializer {
        Runnable materialize(
                CommitModels commit,
                List<ModelUpdate> assignedUpdates,
                Set<String> excludedModelIds);
    }

    /**
     * Writes current materialized graph documents, returning their local-handler publication for execution after the
     * graph-projection fence has advanced.
     */
    @FunctionalInterface
    public interface ModelGraphProjectionMaterializer {
        Runnable materialize(
                ModelGraphProjectionConfiguration
                        configuration,
                Set<String> rootIds,
                long stateIndex,
                boolean rebuild);
    }

    private static final class MutableModelRelationship
            implements ModelRelationshipQueries.Relationship {
        private final String childId;
        private final ModelRelationship relationship;
        private final long validFrom;
        private Long validUntil;
        private boolean parentDeleted;

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

        private boolean isValidBefore(long stateIndex) {
            return validFrom < stateIndex
                   && (validUntil == null || stateIndex <= validUntil);
        }

        @Override
        public String childId() {
            return childId;
        }

        @Override
        public String parentId() {
            return relationship.getParentId();
        }

        @Override
        public String parentType() {
            return relationship.getParentType();
        }

        @Override
        public String path() {
            return relationship.getPath();
        }

        @Override
        public long validFrom() {
            return validFrom;
        }

        @Override
        public Long validUntil() {
            return validUntil;
        }

        @Override
        public boolean deleteOnParentDeletion() {
            return relationship.isDeleteOnParentDeletion();
        }
    }
}
