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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.modeling.CascadedModelDeletion;
import io.fluxzero.sdk.modeling.DirectModelUpdate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelCommitContext;
import io.fluxzero.sdk.modeling.ModelExecutionPlan;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.modeling.ModelTargetResolver;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.InMemoryEventStore;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.EVENT;

/**
 * Owns bounded model-stream reads and the reconstruction sessions that consume them.
 * <p>
 * Transport pages are released synchronously while a session retains only reusable model views, checkpoints and
 * compiled replay plans. This keeps paging, boundary pinning and replay state under one lifecycle owner.
 */
final class ModelReplayCursor {

    static final Settings DEFAULT_SETTINGS =
            new Settings(32_768, 131_072, 128, 64L * 1_024L * 1_024L);

    private final EventStoreClient eventStoreClient;
    private final ReadBatcher requestBatcher;
    private final Settings settings;
    private final Serializer serializer;
    private final EntityHelper entityHelper;
    private final ModelExecutionPlan.Compiler modelExecution;
    private final Cache modelCache;
    private final ModelSnapshotStore snapshotStore;
    private final AncestorReader ancestorReader;
    private final Map<HandlerKey, ModelExecutionPlan> replayPlans =
            new ConcurrentHashMap<>();

    ModelReplayCursor(EventStoreClient eventStoreClient) {
        this(eventStoreClient, DEFAULT_SETTINGS);
    }

    ModelReplayCursor(EventStoreClient eventStoreClient, Settings settings) {
        this(eventStoreClient, settings, null, null, null, null, null, null);
    }

    ModelReplayCursor(
            EventStoreClient eventStoreClient,
            Serializer serializer,
            EntityHelper entityHelper,
            ModelExecutionPlan.Compiler modelExecution,
            Cache modelCache,
            ModelSnapshotStore snapshotStore,
            AncestorReader ancestorReader) {
        this(eventStoreClient, DEFAULT_SETTINGS, serializer, entityHelper, modelExecution,
             modelCache, snapshotStore, ancestorReader);
    }

    private ModelReplayCursor(
            EventStoreClient eventStoreClient,
            Settings settings,
            Serializer serializer,
            EntityHelper entityHelper,
            ModelExecutionPlan.Compiler modelExecution,
            Cache modelCache,
            ModelSnapshotStore snapshotStore,
            AncestorReader ancestorReader) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient, "eventStoreClient");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestBatcher = new ReadBatcher(
                eventStoreClient, settings.maxStreamsPerRequest());
        this.serializer = serializer;
        this.entityHelper = entityHelper;
        this.modelExecution = modelExecution;
        this.modelCache = modelCache;
        this.snapshotStore = snapshotStore;
        this.ancestorReader = ancestorReader;
    }

    Session session() {
        if (serializer == null || entityHelper == null || modelExecution == null
            || modelCache == null || ancestorReader == null) {
            throw new EventSourcingException(
                    "Event-sourced model reconstruction requires a configured serializer and model entity helper");
        }
        return new Session();
    }

    /**
     * Loads complete stored stream histories in bounded pages.
     *
     * @param modelIds      exact persisted model identities in deterministic order
     * @param maxStateIndex inclusive historical boundary, or {@code null} to pin the current boundary
     * @param pageConsumer  synchronous consumer that must release each page before this method requests the next
     * @return the one state boundary shared by every delivered page
     */
    long load(
            List<String> modelIds,
            Long maxStateIndex,
            Consumer<GetModelEventsResult> pageConsumer) {
        LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
        validateIds(modelIds).forEach(modelId -> cursors.put(modelId, -1L));
        return load(cursors, maxStateIndex, pageConsumer).stateIndex();
    }

    /**
     * Loads stream suffixes after a caller-supplied sequence per model.
     * <p>
     * This is the cache/snapshot catch-up path: one request still pins all heads and suffixes to the same state
     * boundary, while already reconstructed prefixes are not transferred again.
     */
    LoadResult load(
            Map<String, Long> lastSequenceNumbers,
            Long maxStateIndex,
            Consumer<GetModelEventsResult> pageConsumer) {
        return load(lastSequenceNumbers, ModelReadBoundary.at(maxStateIndex), pageConsumer);
    }

    /**
     * Loads stream suffixes at either an explicit state boundary or the persisted state of one commit substep.
     * The commit boundary is resolved by the runtime in the first stream request and all following pages use the
     * returned state index.
     */
    LoadResult load(
            Map<String, Long> lastSequenceNumbers,
            ModelReadBoundary boundary,
            Consumer<GetModelEventsResult> pageConsumer) {
        Objects.requireNonNull(lastSequenceNumbers, "lastSequenceNumbers");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(pageConsumer, "pageConsumer");
        LinkedHashMap<String, Long> validatedCursors = validateCursors(lastSequenceNumbers);
        List<String> ids = List.copyOf(validatedCursors.keySet());
        if (ids.isEmpty()) {
            GetModelEventsResult response = eventStoreClient.getModelEvents(
                    new GetModelEvents(
                            List.of(), boundary.requestStateIndex(),
                            boundary.commitId(), boundary.substep(), boundary.eventIndex(),
                            settings.maxPayloadBytes(), true));
            validateBoundary(response, boundary.stateIndex());
            pageConsumer.accept(response);
            return new LoadResult(response.getStateIndex(), Map.of());
        }

        ModelReadBoundary pinned = boundary;
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        int maxStreamsPerChunk = Math.min(
                settings.maxStreamsPerRequest(), settings.maxMembershipsPerRequest());
        for (int offset = 0; offset < ids.size(); offset += maxStreamsPerChunk) {
            int until = Math.min(ids.size(), offset + maxStreamsPerChunk);
            List<String> chunkIds = ids.subList(offset, until);
            LinkedHashMap<String, Long> chunkCursors = new LinkedHashMap<>();
            chunkIds.forEach(modelId -> chunkCursors.put(modelId, validatedCursors.get(modelId)));
            LoadResult chunk = loadChunk(
                    chunkCursors, pinned,
                    pageConsumer);
            pinned = ModelReadBoundary.state(chunk.stateIndex(), false);
            heads.putAll(chunk.heads());
        }
        return new LoadResult(pinned.stateIndex(), heads);
    }

    /**
     * Loads only model heads while pinning the same boundary across every request chunk.
     * <p>
     * This is used to prove that a directly document-loaded dependency still has complete stored event history before
     * an event-sourced model is allowed to depend on it. No event membership or payload is transferred.
     */
    LoadResult loadHeads(
            List<String> modelIds,
            ModelReadBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        List<String> ids = validateIds(modelIds);
        if (ids.isEmpty()) {
            return load(Map.of(), boundary, ignored -> {
            });
        }
        ModelReadBoundary pinned = boundary;
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        for (int offset = 0;
             offset < ids.size();
             offset += settings.maxStreamsPerRequest()) {
            List<String> chunk = ids.subList(
                    offset, Math.min(ids.size(), offset + settings.maxStreamsPerRequest()));
            GetModelEventsResult response = requestBatcher.get(new GetModelEvents(
                    chunk.stream().map(id -> new ModelEventStreamRequest(id, -1L, 0)).toList(),
                    pinned.requestStateIndex(), pinned.commitId(), pinned.substep(), pinned.eventIndex(),
                    settings.maxPayloadBytes(), true));
            long stateIndex = validateBoundary(response, pinned.stateIndex());
            LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
            chunk.forEach(id -> cursors.put(id, -1L));
            validatePage(response, chunk, cursors, heads, 0, settings.maxPayloadBytes());
            pinned = ModelReadBoundary.state(stateIndex, false);
        }
        return new LoadResult(pinned.stateIndex(), heads);
    }

    private LoadResult loadChunk(
            LinkedHashMap<String, Long> initialCursors,
            ModelReadBoundary boundary,
            Consumer<GetModelEventsResult> pageConsumer) {
        LinkedHashMap<String, Long> cursors = new LinkedHashMap<>(initialCursors);
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        ModelReadBoundary pinned = boundary;

        while (true) {
            List<String> active = cursors.entrySet().stream()
                    .filter(entry -> {
                        ModelHeadState head = heads.get(entry.getKey());
                        return head == null
                               ? !heads.containsKey(entry.getKey())
                               : entry.getValue() < head.getSequenceNumber();
                    })
                    .map(Map.Entry::getKey)
                    .toList();
            if (active.isEmpty()) {
                return new LoadResult(
                        Objects.requireNonNull(pinned.stateIndex()), heads);
            }

            int perStreamLimit = Math.min(
                    settings.maxMembershipsPerStream(),
                    Math.max(1, settings.maxMembershipsPerRequest() / active.size()));
            List<ModelEventStreamRequest> requests = active.stream()
                    .map(modelId -> new ModelEventStreamRequest(
                            modelId, cursors.get(modelId), perStreamLimit))
                    .toList();
            GetModelEvents request =
                    new GetModelEvents(
                            requests, pinned.requestStateIndex(),
                            pinned.commitId(), pinned.substep(), pinned.eventIndex(),
                            settings.maxPayloadBytes(), true);
            GetModelEventsResult response = requestBatcher.get(request);
            long responseStateIndex = validateBoundary(
                    response, pinned.stateIndex());
            pinned = ModelReadBoundary.state(responseStateIndex, false);

            int advanced = validatePage(
                    response, active, cursors, heads,
                    perStreamLimit,
                    settings.maxPayloadBytes());
            pageConsumer.accept(response);
            if (advanced == 0 && hasIncompleteStream(cursors, heads)) {
                throw invalid(
                        "Model event page made no progress at state index "
                        + pinned.stateIndex());
            }
        }
    }

    private static List<String> validateIds(List<String> modelIds) {
        Objects.requireNonNull(modelIds, "modelIds");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String modelId : modelIds) {
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("Model ID must not be blank");
            }
            if (!unique.add(modelId)) {
                throw new IllegalArgumentException("Duplicate model ID " + modelId);
            }
        }
        return List.copyOf(unique);
    }

    private static LinkedHashMap<String, Long> validateCursors(
            Map<String, Long> lastSequenceNumbers) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : lastSequenceNumbers.entrySet()) {
            String modelId = entry.getKey();
            Long sequenceNumber = entry.getValue();
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("Model ID must not be blank");
            }
            if (sequenceNumber == null || sequenceNumber < -1L) {
                throw new IllegalArgumentException(
                        "Last model sequence number must be at least -1 for " + modelId);
            }
            result.put(modelId, sequenceNumber);
        }
        return result;
    }

    private static long validateBoundary(GetModelEventsResult response, Long expected) {
        if (response == null) {
            throw invalid("Model event store returned no response");
        }
        long stateIndex = response.getStateIndex();
        if (stateIndex < -1L) {
            throw invalid("Model event store returned invalid state index " + stateIndex);
        }
        if (expected != null && stateIndex != expected) {
            throw invalid(
                    "Model event store returned state index %d while reconstruction is pinned at %d"
                            .formatted(stateIndex, expected));
        }
        return stateIndex;
    }

    private static int validatePage(
            GetModelEventsResult response,
            List<String> requestedIds,
            Map<String, Long> cursors,
            Map<String, ModelHeadState> knownHeads,
            int perStreamLimit,
            long maxPayloadBytes) {
        List<ModelEventPayload> payloadList =
                Objects.requireNonNull(response.getPayloads(), "Model event payloads");
        long[] payloadStateIndices = new long[payloadList.size()];
        boolean sortedPayloads = true;
        long payloadBytes = 0L;
        for (int index = 0; index < payloadList.size(); index++) {
            ModelEventPayload payload = payloadList.get(index);
            if (payload == null || payload.getEvent() == null) {
                throw invalid("Model event response contains a null payload");
            }
            if (payload.getStateIndex() < 0L || payload.getStateIndex() > response.getStateIndex()) {
                throw invalid("Model event payload has invalid state index " + payload.getStateIndex());
            }
            payloadStateIndices[index] = payload.getStateIndex();
            if (index > 0
                && payloadStateIndices[index - 1] >= payloadStateIndices[index]) {
                sortedPayloads = false;
            }
            payloadBytes = addSaturated(payloadBytes, payload.getEvent().getBytes());
        }
        Map<Long, Integer> payloadOrdinals = null;
        if (!sortedPayloads) {
            payloadOrdinals = new HashMap<>(payloadList.size() * 4 / 3 + 1);
            for (int index = 0; index < payloadStateIndices.length; index++) {
                if (payloadOrdinals.putIfAbsent(
                        payloadStateIndices[index], index) != null) {
                    throw invalid(
                            "Duplicate model event payload at state index "
                            + payloadStateIndices[index]);
                }
            }
        }
        if (payloadList.size() > 1 && maxPayloadBytes > 0L && payloadBytes > maxPayloadBytes) {
            throw invalid(
                    "Model event response contains %d serialized event bytes, exceeding limit %d"
                            .formatted(payloadBytes, maxPayloadBytes));
        }

        List<ModelEventStream> streams =
                Objects.requireNonNull(response.getStreams(), "Model event streams");
        if (streams.size() != requestedIds.size()) {
            throw invalid(
                    "Model event response contains %d streams for %d requests"
                            .formatted(streams.size(), requestedIds.size()));
        }
        boolean[] referencedPayloads =
                new boolean[payloadStateIndices.length];
        int advanced = 0;
        for (int i = 0; i < streams.size(); i++) {
            String requestedId = requestedIds.get(i);
            ModelEventStream stream = streams.get(i);
            if (stream == null || !requestedId.equals(stream.getModelId())) {
                throw invalid(
                        "Model event stream %d should be '%s' but was '%s'"
                                .formatted(i, requestedId, stream == null ? null : stream.getModelId()));
            }
            ModelHeadState head = stream.getHead();
            long cursor = cursors.get(requestedId);
            boolean knownHead = knownHeads.containsKey(requestedId);
            ModelHeadState previousHead = knownHeads.get(requestedId);
            if (head != null) {
                if (knownHead && previousHead == null) {
                    throw invalid("Model head appeared while loading " + requestedId);
                }
                validateHead(requestedId, head, response.getStateIndex(), previousHead);
                if (cursor > head.getSequenceNumber()) {
                    throw invalid(
                            "Model stream '%s' starts after pinned head sequence %d"
                                    .formatted(requestedId, head.getSequenceNumber()));
                }
            } else if (knownHead && previousHead != null) {
                throw invalid("Model head disappeared while loading " + requestedId);
            }
            if (!knownHead) {
                knownHeads.put(requestedId, head);
            }

            List<ModelEventMembership> memberships =
                    Objects.requireNonNull(stream.getMemberships(), "Model event memberships");
            if (memberships.size() > perStreamLimit) {
                throw invalid(
                        "Model stream '%s' returned %d memberships, exceeding requested limit %d"
                                .formatted(requestedId, memberships.size(), perStreamLimit));
            }
            for (ModelEventMembership membership : memberships) {
                if (membership == null) {
                    throw invalid("Model stream '" + requestedId + "' contains a null membership");
                }
                if (membership.getSequenceNumber() != cursor + 1L) {
                    throw invalid(
                            "Model stream '%s' returned sequence %d after %d"
                                    .formatted(requestedId, membership.getSequenceNumber(), cursor));
                }
                if (membership.getStateIndex() < 0L
                    || membership.getStateIndex() > response.getStateIndex()) {
                    throw invalid(
                            "Model stream '%s' has invalid membership state index %d"
                                    .formatted(requestedId, membership.getStateIndex()));
                }
                if (head != null && membership.getStateIndex() > head.getStateIndex()) {
                    throw invalid(
                            "Model stream '%s' has membership state %d beyond head state %d"
                                    .formatted(requestedId, membership.getStateIndex(), head.getStateIndex()));
                }
                int payloadOrdinal = sortedPayloads
                        ? Arrays.binarySearch(
                                payloadStateIndices,
                                membership.getStateIndex())
                        : payloadOrdinals.getOrDefault(
                                membership.getStateIndex(), -1);
                if (payloadOrdinal < 0) {
                    throw invalid(
                            "Model stream '%s' references missing payload at state index %d"
                                    .formatted(requestedId, membership.getStateIndex()));
                }
                if (membership.getReadStateIndex() < -1L
                    || membership.getReadStateIndex() >= membership.getStateIndex()) {
                    throw invalid(
                            "Model stream '%s' has invalid read state index %d at state %d"
                                    .formatted(requestedId, membership.getReadStateIndex(),
                                               membership.getStateIndex()));
                }
                if (membership.getCommitId() == null || membership.getCommitId().isBlank()
                    || membership.getSubstep() < 0) {
                    throw invalid("Model stream '" + requestedId + "' has invalid commit membership");
                }
                referencedPayloads[payloadOrdinal] = true;
                cursor = membership.getSequenceNumber();
                advanced++;
            }
            if (head != null && cursor > head.getSequenceNumber()) {
                throw invalid(
                        "Model stream '%s' advanced beyond head sequence %d"
                                .formatted(requestedId, head.getSequenceNumber()));
            }
            cursors.put(requestedId, cursor);
        }
        for (int index = 0; index < referencedPayloads.length; index++) {
            if (!referencedPayloads[index]) {
                throw invalid(
                        "Model event response contains unreferenced payload "
                        + payloadStateIndices[index]);
            }
        }
        return advanced;
    }

    private static void validateHead(
            String requestedId,
            ModelHeadState head,
            long responseStateIndex,
            ModelHeadState previous) {
        if (head.getModelId() == null
            || head.getModelId().isBlank()) {
            throw invalid(
                    "Model head for '%s' reports a blank resolved ID"
                            .formatted(requestedId));
        }
        if (!head.isHistoryComplete()) {
            throw invalid(
                    "Model '%s' cannot be reconstructed at state index %d because its stored history is incomplete"
                            .formatted(requestedId, responseStateIndex));
        }
        if (head.getSequenceNumber() < 0L
            || head.getStateIndex() < 0L
            || head.getStateIndex() > responseStateIndex) {
            throw invalid("Model head for '" + requestedId + "' contains invalid positions");
        }
        if (previous != null && !previous.equals(head)) {
            throw invalid("Model head changed while loading " + requestedId);
        }
    }

    private static boolean hasIncompleteStream(
            Map<String, Long> cursors,
            Map<String, ModelHeadState> heads) {
        return cursors.entrySet().stream().anyMatch(entry -> {
            ModelHeadState head = heads.get(entry.getKey());
            return head != null && entry.getValue() < head.getSequenceNumber();
        });
    }

    private static long addSaturated(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static EventSourcingException invalid(String message) {
        return new EventSourcingException(message);
    }

    final class Session {
        private final Map<ViewKey, Entity<?>> reconstructed =
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<ViewKey, Entity<?>> eldest) {
                        return size() > 1_024;
                    }
                };
        private final Map<ModelKey, TreeMap<Long, Entity<?>>> checkpoints =
                new HashMap<>();
        private final ConcurrentMap<PayloadKey, List<DeserializingMessage>>
                deserializedEvents = new ConcurrentHashMap<>();
        private final Map<ReplayAncestorKey, ModelTargetResolver.Resolution>
                replayAncestorResolutions =
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<ReplayAncestorKey,
                                    ModelTargetResolver.Resolution> eldest) {
                        return size() > 1_024;
                    }
                };

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets,
                ModelReadBoundary boundary) {
            return reconstruct(
                    targets, boundary, !boundary.historical());
        }

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets,
            ModelReadBoundary boundary,
            boolean cacheAtBoundary) {
            if (targets.isEmpty()) {
                long stateBoundary = ModelReplayCursor.this.load(
                        Map.of(), boundary,
                        ignored -> {
                        }).stateIndex();
                return new ReconstructionBatch(stateBoundary, Map.of());
            }
            LinkedHashMap<String, MutableReconstruction> states =
                    new LinkedHashMap<>();
            LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                Entity<?> base = reconstructionBase(
                        target, boundary.stateIndex(),
                        boundary.commitId() == null
                        && boundary.eventIndex() == null);
                states.put(
                        target.modelId(),
                        new MutableReconstruction(target, base));
                cursors.put(
                        target.modelId(),
                        base == null ? -1L : base.sequenceNumber());
            }
            ModelReplayCursor.LoadResult loaded =
                    ModelReplayCursor.this.load(
                            cursors, boundary,
                            page -> applyPage(page, states));
            LinkedHashMap<String, Entity<?>> cacheCandidates =
                    new LinkedHashMap<>();
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ModelHeadState head = loaded.heads().get(target.modelId());
                MutableReconstruction state = states.get(target.modelId());
                Entity<?> entity = head == null ? empty(target) : withHead(state.current, head);
                ModelTargetResolver.ResolvedModel resolvedTarget = state.target;
                validateReconstruction(resolvedTarget, head, entity);
                boolean cacheable = cacheAtBoundary && head != null && head.isHistoryComplete()
                                    && ModelMetadata.of(resolvedTarget.modelType())
                                            .model().orElseThrow().cached();
                if (cacheable) {
                    cacheCandidates.put(resolvedTarget.modelId(), entity);
                } else if (head == null) {
                    modelCache.remove(target.modelId());
                }
                result.put(target.modelId(), entity);
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), loaded.stateIndex(),
                        null, Integer.MAX_VALUE, loaded.stateIndex()), entity);
            }
            modelCache.mergeAll(
                    cacheCandidates,
                    (current, candidate) ->
                            current != null
                            && stateIndex(current)
                               >= stateIndex(candidate)
                                    ? current
                                    : candidate);
            return new ReconstructionBatch(loaded.stateIndex(), result);
        }

        private Entity<?> reconstructionBase(
                ModelTargetResolver.ResolvedModel target,
                Long maxStateIndex,
                boolean allowCurrentCache) {
            Model model = ModelMetadata.of(target.modelType()).model().orElseThrow();
            if (allowCurrentCache
                && model.cached()) {
                Entity<?> cached =
                        modelCache.get(
                                target.modelId());
                if (cached != null
                    && (maxStateIndex == null
                        || stateIndex(cached)
                           <= maxStateIndex)) {
                    if (!target.modelType()
                            .equals(cached.type())) {
                        modelCache.remove(
                                target.modelId());
                        throw new EventSourcingException(
                                "Cached model '%s' has type %s instead of %s"
                                        .formatted(
                                                target.modelId(),
                                                cached.type()
                                                        .getName(),
                                                target.modelType()
                                                        .getName()));
                    }
                    return cached;
                }
            }
            Entity<?> result = null;
            if (model.snapshotPeriod() > 0 && snapshotStore != null
                && (maxStateIndex != null || allowCurrentCache)) {
                result = snapshotStore.getSnapshot(
                                target.modelId(), maxStateIndex)
                        .map(snapshot -> fromSnapshot(target, snapshot))
                        .orElse(null);
            }
            if (maxStateIndex != null) {
                TreeMap<Long, Entity<?>> known = checkpoints.get(
                        new ModelKey(target.modelId(), target.modelType()));
                if (known != null) {
                    Map.Entry<Long, Entity<?>> floor =
                            known.floorEntry(maxStateIndex);
                    if (floor != null
                        && (result == null
                            || stateIndex(result) < floor.getKey())) {
                        result = floor.getValue();
                    }
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private Entity<?> fromSnapshot(
                ModelTargetResolver.ResolvedModel target,
                ModelSnapshotStore.Snapshot snapshot) {
            if (snapshot.value() == null
                || !target.modelType().isInstance(snapshot.value())) {
                throw new EventSourcingException(
                        "Snapshot for model '%s' contains %s instead of %s"
                                .formatted(
                                        target.modelId(),
                                        snapshot.value() == null
                                                ? "null" : snapshot.value().getClass().getName(),
                                        target.modelType().getName()));
            }
            validateValueId(
                    target.modelId(), ModelMetadata.of(target.modelType()),
                    snapshot.value());
            return ImmutableModelRoot.<Object>builder()
                    .id(target.modelId())
                    .type((Class<Object>) target.modelType())
                    .idProperty(ModelMetadata.of(target.modelType())
                                        .entityId().orElseThrow().name())
                    .value(snapshot.value())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(snapshot.sequenceNumber())
                    .stateIndex(snapshot.stateIndex())
                    .timestamp(snapshot.timestamp())
                    .build();
        }

        private void applyPage(
                GetModelEventsResult page,
                Map<String, MutableReconstruction> states) {
            page.getStreams().forEach(
                    stream -> resolveTarget(
                            stream.getModelId(), stream.getHead(), states));
            PayloadLookup payloads =
                    PayloadLookup.from(page.getPayloads());
            boolean independent =
                    page.getStreams().size() >= 32
                    && page.getStreams().parallelStream()
                            .allMatch(stream -> stream.getMemberships().stream()
                                    .allMatch(membership -> directReplayPlan(
                                            payloads.getRequired(membership.getStateIndex()),
                                            states.get(stream.getModelId()).target.modelType()) != null));
            if (independent) {
                page.getStreams().parallelStream()
                        .forEach(stream ->
                                         applyStream(
                                                 stream,
                                                 states,
                                                 payloads));
            } else {
                page.getStreams().forEach(stream ->
                                                   applyStream(
                                                           stream,
                                                           states,
                                                           payloads));
            }
            if (deserializedEvents.size() > 1_024) {
                deserializedEvents.clear();
            }
        }

        private void resolveTarget(
                String requestedId,
                ModelHeadState head,
                Map<String, MutableReconstruction> states) {
            if (head == null
                || requestedId.equals(head.getModelId())) {
                return;
            }
            MutableReconstruction state = states.get(requestedId);
            if (state == null) {
                throw new EventSourcingException(
                        "Model alias response returned unrelated stream "
                        + requestedId);
            }
            state.resolve(head.getModelId());
        }

        private ModelExecutionPlan directReplayPlan(
                SerializedMessage event,
                Class<?> modelType) {
            Class<?> payloadType =
                    serializer.serializedClassWithoutUpcasting(
                            event);
            if (payloadType == null) {
                return null;
            }
            ModelExecutionPlan plan =
                    replayPlans.computeIfAbsent(
                            new HandlerKey(
                                    payloadType,
                                    modelType),
                            ignored ->
                                    replayPlan(
                                            payloadType,
                                            modelType));
            return plan.direct() ? plan : null;
        }

        private void applyStream(
                ModelEventStream stream,
                Map<String, MutableReconstruction> states,
                PayloadLookup payloads) {
            MutableReconstruction state = states.get(stream.getModelId());
            if (state == null) {
                throw new EventSourcingException(
                        "Model event store returned unrelated stream "
                        + stream.getModelId());
            }
            if (stream.getHead() != null
                && !stream.getHead().isHistoryComplete()) {
                throw incompleteHistory(stream.getModelId());
            }
            for (ModelEventMembership membership : stream.getMemberships()) {
                StoredEvent storedEvent = new StoredEvent(
                        membership,
                        payloads.getRequired(
                                membership.getStateIndex()));
                ModelExecutionPlan directPlan =
                        directReplayPlan(
                                storedEvent.event(),
                                state.target.modelType());
                if (directPlan == null) {
                    state.apply(storedEvent);
                } else {
                    state.applyCompiled(
                            storedEvent,
                            directPlan);
                }
            }
        }

        private Map<String, Entity<?>> reconstructAt(
                List<ModelTargetResolver.ResolvedModel> targets,
                long stateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelTargetResolver.ResolvedModel> missing = new ArrayList<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ViewKey viewKey = new ViewKey(
                        target.modelId(), target.modelType(), stateIndex,
                        null, Integer.MAX_VALUE, stateIndex);
                Entity<?> known = reconstructed.get(viewKey);
                if (known == null) {
                    missing.add(target);
                } else {
                    result.put(target.modelId(), known);
                }
            }
            if (missing.isEmpty()) {
                return result;
            }
            ReconstructionBatch loaded =
                    reconstruct(
                            missing,
                            ModelReadBoundary.at(stateIndex),
                            false);
            if (loaded.stateIndex() != stateIndex) {
                throw new EventSourcingException(
                        "Historical model load moved from state index %d to %d"
                                .formatted(stateIndex, loaded.stateIndex()));
            }
            result.putAll(loaded.entities());
            return ordered(targets, result);
        }

        private Entity<?> reconstructView(
                ModelTargetResolver.ResolvedModel target,
                long readStateIndex,
                String commitId,
                int substep,
                long commitStateIndex) {
            return reconstructViews(
                    List.of(target), readStateIndex, commitId,
                    substep, commitStateIndex).get(target.modelId());
        }

        private Map<String, Entity<?>> reconstructViews(
                List<ModelTargetResolver.ResolvedModel> targets,
                long readStateIndex,
                String commitId,
                int substep,
                long commitStateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelTargetResolver.ResolvedModel> missing =
                    new ArrayList<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ViewKey key = new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        commitId, substep, commitStateIndex);
                Entity<?> cached = reconstructed.get(key);
                if (cached == null) {
                    missing.add(target);
                } else {
                    result.put(target.modelId(), cached);
                }
            }
            if (missing.isEmpty()) {
                return result;
            }
            Map<String, Entity<?>> base =
                    reconstructAt(missing, readStateIndex);
            if (substep > 0) {
                LinkedHashMap<String, Long> cursors =
                        new LinkedHashMap<>();
                missing.forEach(target -> cursors.put(
                        target.modelId(),
                        base.get(target.modelId()).sequenceNumber()));
                ModelReplayCursor.this.load(
                        cursors,
                        ModelReadBoundary.commit(
                                commitId, substep - 1),
                        page -> applyCommitPrefix(
                                page, missing, base, readStateIndex,
                                commitId, substep));
            }
            for (ModelTargetResolver.ResolvedModel target : missing) {
                Entity<?> entity = base.get(target.modelId());
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        commitId, substep, commitStateIndex), entity);
                result.put(target.modelId(), entity);
            }
            return ordered(targets, result);
        }

        private Map<String, Entity<?>> ordered(
                List<ModelTargetResolver.ResolvedModel> targets,
                Map<String, Entity<?>> values) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            targets.forEach(target -> result.put(
                    target.modelId(), values.get(target.modelId())));
            return result;
        }

        private void applyCommitPrefix(
                GetModelEventsResult page,
                List<ModelTargetResolver.ResolvedModel> targets,
                Map<String, Entity<?>> current,
                long readStateIndex,
                String commitId,
                int substep) {
            Map<String, ModelTargetResolver.ResolvedModel> targetsById =
                    new HashMap<>();
            targets.forEach(target -> targetsById.put(
                    target.modelId(), target));
            Map<Long, io.fluxzero.common.api.SerializedMessage> payloads =
                    new HashMap<>();
            for (ModelEventPayload payload : page.getPayloads()) {
                payloads.put(payload.getStateIndex(), payload.getEvent());
            }
            for (ModelEventStream stream : page.getStreams()) {
                ModelTargetResolver.ResolvedModel target =
                        targetsById.get(stream.getModelId());
                if (target == null) {
                    throw new EventSourcingException(
                            "Model event store returned unrelated stream "
                            + stream.getModelId());
                }
                for (ModelEventMembership membership : stream.getMemberships()) {
                    if (membership.getStateIndex() > readStateIndex
                        && membership.getCommitId().equals(commitId)
                        && membership.getSubstep() < substep) {
                        current.put(
                                target.modelId(),
                                apply(
                                        target,
                                        current.get(target.modelId()),
                                        new StoredEvent(
                                                membership,
                                                Objects.requireNonNull(
                                                        payloads.get(membership
                                                                .getStateIndex())))));
                    }
                }
            }
        }

        private EventSourcingException incompleteHistory(String modelId) {
            return new EventSourcingException(
                    "Cannot reconstruct model '%s' because its stored event history is incomplete"
                            .formatted(modelId));
        }

        private final class MutableReconstruction {
            private ModelTargetResolver.ResolvedModel target;
            private final Entity<?> base;
            private Entity<?> current;
            private ModelEventMembership previous;

            private MutableReconstruction(
                    ModelTargetResolver.ResolvedModel target, Entity<?> base) {
                this.target = target;
                this.base = base;
                this.current = base == null ? empty(target) : base;
            }

            private void resolve(String modelId) {
                if (target.modelId().equals(modelId)) {
                    return;
                }
                if (previous != null
                    || base != null && base.isPresent()) {
                    throw new EventSourcingException(
                            "Model alias '%s' resolved after reconstruction started"
                                    .formatted(target.modelId()));
                }
                target = new ModelTargetResolver.ResolvedModel(
                        modelId,
                        target.modelType(),
                        target.access(),
                        target.sourceProperties());
                current = empty(target);
            }

            private void apply(StoredEvent storedEvent) {
                apply(
                        storedEvent,
                        (List<PreparedReplay>) null);
            }

            private void apply(
                    StoredEvent storedEvent,
                    List<PreparedReplay> prepared) {
                ModelEventMembership membership = storedEvent.membership();
                boolean followsCurrent = previous == null
                        ? base == null
                          || membership.getReadStateIndex() >= stateIndex(base)
                        : membership.getReadStateIndex() >= previous.getStateIndex()
                          || sameEarlierCommit(
                                  previous,
                                  membership);
                Entity<?> begin = followsCurrent
                        ? current
                        : reconstructView(
                                target, membership.getReadStateIndex(),
                                membership.getCommitId(), membership.getSubstep(),
                                membership.getStateIndex());
                current = prepared == null
                        ? Session.this.apply(
                                target, begin, storedEvent)
                        : Session.this.apply(
                                target, begin, storedEvent,
                                prepared);
                previous = membership;
                rememberCheckpoint(target, current);
            }

            private void applyCompiled(
                    StoredEvent storedEvent,
                    ModelExecutionPlan plan) {
                DeserializingMessage event =
                        serializer.deserializeFirstMessageOrNull(
                                storedEvent.event(),
                                EVENT,
                                null);
                if (event == null) {
                    throw new EventSourcingException(
                            "Stored model event at state %d was unexpectedly dropped"
                                    .formatted(
                                            storedEvent.membership()
                                                    .getStateIndex()));
                }
                apply(storedEvent, List.of(
                        new PreparedReplay(event, plan, null)));
            }

        }

        private void rememberCheckpoint(
                ModelTargetResolver.ResolvedModel target, Entity<?> entity) {
            Model model = ModelMetadata.of(target.modelType())
                    .model().orElseThrow();
            int period = model.checkpointPeriod();
            if (period <= 0 || entity.sequenceNumber() < 0
                || Math.floorMod(entity.sequenceNumber() + 1L, period) != 0L) {
                return;
            }
            TreeMap<Long, Entity<?>> known = checkpoints.computeIfAbsent(
                    new ModelKey(target.modelId(), target.modelType()),
                    ignored -> new TreeMap<>());
            known.put(stateIndex(entity), entity);
            while (known.size() > 1_024) {
                known.pollFirstEntry();
            }
        }

        private Entity<?> apply(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                StoredEvent storedEvent) {
            ModelEventMembership membership = storedEvent.membership();
            return apply(
                    target, begin, storedEvent,
                    prepareReplay(target, membership, storedEvent));
        }

        private Entity<?> apply(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                StoredEvent storedEvent,
                List<PreparedReplay> prepared) {
            ModelEventMembership membership =
                    storedEvent.membership();
            Entity<?> result = begin;
            for (PreparedReplay preparedReplay : prepared) {
                DeserializingMessage event = preparedReplay.event();
                Class<?> payloadType = event.getPayloadClass();
                if (event.getPayload() instanceof DirectModelUpdate update) {
                    Data<byte[]> state = update.target(target.modelId()).getState();
                    result = updateValue(
                            result,
                            state == null ? null : serializer.deserialize(state));
                    continue;
                }
                if (event.getPayload() instanceof CascadedModelDeletion) {
                    result = updateValue(result, null);
                    continue;
                }
                ModelExecutionPlan plan = preparedReplay.plan();
                if (plan.empty()) {
                    if (ModelMetadata.of(target.modelType()).model().orElseThrow()
                            .ignoreUnknownEvents()) {
                        continue;
                    }
                    throw new EventSourcingException(
                            "No replay apply found for %s on model %s"
                                    .formatted(payloadType.getName(), target.modelType().getName()));
                }
                if (plan.direct()) {
                    ModelCommitContext context = ModelCommitContext.createSingle(
                            stateIndex(result), target.modelId(), target.modelType(),
                            ModelTargetResolver.Access.READ_WRITE, List.of(), result);
                    result = updateValue(
                            result, replayValue(
                                    target, membership.getStateIndex(),
                                    event, context, plan));
                    continue;
                }
                ModelTargetResolver.Resolution resolution =
                        preparedReplay.resolution();
                if (resolution.hasAncestorDependencies()) {
                    long relationshipBoundary =
                            membership.getReadStateIndex();
                    if (relationshipBoundary < 0L) {
                        throw new EventSourcingException(
                                "Model event at state %d requires an ancestor before any model state was observed"
                                        .formatted(
                                                membership
                                                        .getStateIndex()));
                    }
                    ReplayAncestorKey key =
                            new ReplayAncestorKey(
                                    resolution,
                                    relationshipBoundary,
                                    membership.getCommitId(),
                                    membership.getSubstep());
                    ModelTargetResolver.Resolution directResolution =
                            resolution;
                    boolean firstSubstep =
                            membership.getSubstep() == 0;
                    resolution =
                            replayAncestorResolutions.computeIfAbsent(
                                    key, ignored -> {
                                        AncestorResult ancestors =
                                                ancestorReader.resolve(
                                                        directResolution,
                                                        firstSubstep
                                                                ? ModelReadBoundary.at(
                                                                        relationshipBoundary)
                                                                : ModelReadBoundary.commit(
                                                                        membership.getCommitId(),
                                                                        membership.getSubstep() - 1));
                                        boolean invalidBoundary =
                                                firstSubstep
                                                        ? ancestors
                                                                  .stateIndex()
                                                          != relationshipBoundary
                                                        : ancestors
                                                                  .stateIndex()
                                                          < relationshipBoundary
                                                          || ancestors
                                                                     .stateIndex()
                                                             >= membership
                                                                     .getStateIndex();
                                        if (invalidBoundary) {
                                            throw new EventSourcingException(
                                                    "Historical ancestor graph for commit %s substep %d "
                                                    + "resolved invalid boundary %d (read=%d, event=%d)"
                                                            .formatted(
                                                                    membership
                                                                            .getCommitId(),
                                                                    membership
                                                                            .getSubstep(),
                                                                    ancestors
                                                                            .stateIndex(),
                                                                    relationshipBoundary,
                                                                    membership
                                                                            .getStateIndex()));
                                        }
                                        return ancestors.resolution();
                                    });
                }
                List<ModelTargetResolver.ResolvedModel> dependencies =
                        resolution.models().stream()
                                .filter(dependency -> !dependency.modelId()
                                        .equals(target.modelId()))
                                .toList();
                Map<String, Entity<?>> dependencyViews = dependencies.isEmpty()
                        ? Map.of()
                        : reconstructViews(
                                dependencies,
                                membership.getReadStateIndex(),
                                membership.getCommitId(),
                                membership.getSubstep(),
                                membership.getStateIndex());
                Map<String, Entity<?>> loaded;
                if (dependencies.isEmpty()
                    && resolution.models().size() == 1
                    && resolution.models().getFirst().modelId()
                            .equals(target.modelId())) {
                    loaded = Map.of(target.modelId(), result);
                } else {
                    loaded = new LinkedHashMap<>();
                    for (ModelTargetResolver.ResolvedModel dependency :
                            resolution.models()) {
                        Entity<?> entity =
                                dependency.modelId().equals(target.modelId())
                                        ? result
                                        : dependencyViews.get(
                                                dependency.modelId());
                        loaded.put(dependency.modelId(), entity);
                    }
                }
                ModelCommitContext context = ModelCommitContext.create(
                        membership.getReadStateIndex(), resolution, loaded);
                result = updateValue(
                        result, replayValue(
                                target, membership.getStateIndex(),
                                event, context, plan));
            }
            return withMembership(result, storedEvent, begin);
        }

        private List<PreparedReplay> prepareReplay(
                ModelTargetResolver.ResolvedModel target,
                ModelEventMembership membership,
                StoredEvent storedEvent) {
            return deserialize(target.modelType(), membership, storedEvent).stream()
                    .map(event -> {
                        Class<?> payloadType =
                                event.getPayloadClass();
                        ModelExecutionPlan plan =
                                replayPlans.computeIfAbsent(
                                        new HandlerKey(
                                                payloadType,
                                                target.modelType()),
                                        ignored ->
                                                replayPlan(
                                                        payloadType,
                                                        target.modelType()));
                        return new PreparedReplay(
                                event,
                                plan,
                                plan.empty()
                                || plan.direct()
                                        ? null
                                        : plan.replayTargets()
                                                .resolve(
                                                        event.getPayload()));
                    })
                    .toList();
        }

        private Object replayValue(
                ModelTargetResolver.ResolvedModel target,
                long stateIndex,
                DeserializingMessage event,
                ModelCommitContext context,
                ModelExecutionPlan plan) {
            try {
                DeserializingMessage replayEvent = plan.direct()
                        ? event : new DeserializingMessage(
                                event.toMessage(), EVENT, null, serializer);
                return modelExecution.replay(
                        replayEvent, context, plan, target.modelId());
            } catch (Throwable failure) {
                throw new EventSourcingException(
                        "Failed to apply model event at state %d to %s"
                                .formatted(
                                        stateIndex,
                                        target.modelId()),
                        failure);
            }
        }

        private List<DeserializingMessage> deserialize(
                Class<?> modelType,
                ModelEventMembership membership,
                StoredEvent storedEvent) {
            boolean ignoreUnknown = ModelMetadata.of(modelType).model().orElseThrow()
                    .ignoreUnknownEvents();
            PayloadKey key = new PayloadKey(
                    membership.getStateIndex(), ignoreUnknown);
            return deserializedEvents.computeIfAbsent(key, ignored ->
                    serializer.deserializeMessages(
                                    Stream.of(storedEvent.event()), EVENT,
                                    ignoreUnknown ? UnknownTypeStrategy.IGNORE : UnknownTypeStrategy.FAIL)
                            .toList());
        }

        private ModelExecutionPlan replayPlan(
                Class<?> payloadType, Class<?> modelType) {
            LinkedHashSet<ModelMetadata.HandlerMethod> result = new LinkedHashSet<>();
            ModelMetadata.of(payloadType).applyMethods().stream()
                    .filter(handler -> handler.dynamicApplyResult()
                                       || handler.targetModelTypes().stream()
                                               .anyMatch(target -> compatible(target, modelType)))
                    .forEach(result::add);
            ModelMetadata.of(modelType).applyMethods().stream()
                    .filter(handler -> ModelMetadata.acceptsPayload(handler, payloadType))
                    .forEach(result::add);
            List<ModelMetadata.HandlerMethod> handlers = List.copyOf(result);
            return modelExecution.compileReplay(
                    payloadType, modelType, handlers);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> empty(ModelTargetResolver.ResolvedModel target) {
            ModelMetadata metadata = ModelMetadata.validate(target.modelType());
            return ImmutableModelRoot.<Object>builder()
                    .id(target.modelId())
                    .type((Class<Object>) target.modelType())
                    .idProperty(metadata.entityId().orElseThrow().name())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .build();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Entity<?> updateValue(Entity<?> entity, Object value) {
            return ((Entity) entity).update(ignored -> value);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withMembership(
                Entity<?> entity,
                StoredEvent storedEvent,
                Entity<?> previous) {
            ModelEventMembership membership = storedEvent.membership();
            return withMembership(
                    entity,
                    membership.getSequenceNumber(),
                    membership.getStateIndex(),
                    storedEvent.event(),
                    previous);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withMembership(
                Entity<?> entity,
                long sequenceNumber,
                long stateIndex,
                SerializedMessage event,
                Entity<?> previous) {
            return withMembershipValue(
                    entity,
                    entity.get(),
                    sequenceNumber,
                    stateIndex,
                    event,
                    previous);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withMembershipValue(
                Entity<?> entity,
                Object value,
                long sequenceNumber,
                long stateIndex,
                SerializedMessage event,
                Entity<?> previous) {
            Model model = ModelMetadata.of(entity.type())
                    .model().orElseThrow();
            return ImmutableModelRoot.<Object>builder()
                    .id(entity.id())
                    .type((Class<Object>) entity.type())
                    .idProperty(entity.idProperty())
                    .value(value)
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(sequenceNumber)
                    .stateIndex(stateIndex)
                    .lastEventId(event.getMessageId())
                    .lastEventIndex(event.getIndex())
                    .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
                    .previous(castPrevious(retainPrevious(
                            previous, model)))
                    .build();
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withHead(Entity<?> entity, ModelHeadState head) {
            if (head == null) {
                return entity;
            }
            if (entity instanceof ImmutableModelRoot<?> model
                && model.sequenceNumber() == head.getSequenceNumber()
                && model.stateIndex() == head.getStateIndex()) {
                return entity;
            }
            return ImmutableModelRoot.<Object>builder()
                    .id(entity.id())
                    .type((Class<Object>) entity.type())
                    .idProperty(entity.idProperty())
                    .value(entity.get())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(head.getSequenceNumber())
                    .stateIndex(head.getStateIndex())
                    .timestamp(entity.timestamp())
                    .previous(castPrevious(entity.previous()))
                    .build();
        }

        private void validateReconstruction(
                ModelTargetResolver.ResolvedModel target,
                ModelHeadState head,
                Entity<?> entity) {
            if (head == null) {
                if (entity.isPresent()) {
                    throw new EventSourcingException(
                            "Missing model head for reconstructed " + target.modelId());
                }
                return;
            }
            if (head.isDeleted() != entity.isEmpty()) {
                throw new EventSourcingException(
                        "Model '%s' reconstructed deletion=%s but its head reports deletion=%s"
                                .formatted(target.modelId(), entity.isEmpty(), head.isDeleted()));
            }
            validateValueId(target.modelId(), ModelMetadata.of(target.modelType()), entity.get());
        }
    }

    private static boolean compatible(Class<?> left, Class<?> right) {
        return left.isAssignableFrom(right) || right.isAssignableFrom(left);
    }

    private static long stateIndex(Entity<?> entity) {
        return entity instanceof ModelRoot<?> model ? model.stateIndex() : -1L;
    }

    private static boolean sameEarlierCommit(
            ModelEventMembership previous, ModelEventMembership current) {
        return previous.getCommitId().equals(current.getCommitId())
               && previous.getSubstep() < current.getSubstep();
    }

    private static void validateValueId(
            String modelId, ModelMetadata metadata, Object value) {
        if (value == null) {
            return;
        }
        Object storedId = metadata.entityId().orElseThrow().read(value);
        String repositoryId = storedId == null ? null
                : metadata.parentScopedEntityId()
                ? metadata.repositoryId(storedId, value)
                : metadata.repositoryId(storedId);
        if (!Objects.equals(modelId, repositoryId)) {
            throw new EventSourcingException(
                    "Stored model document '%s' reports @EntityId '%s'"
                            .formatted(modelId, storedId));
        }
    }

    private static Entity<?> retainPrevious(
            Entity<?> previous, Model model) {
        if (previous == null || !model.cached()
            || !model.eventSourced() || model.cachingDepth() == 0) {
            return null;
        }
        if (model.cachingDepth() < 0) {
            return previous;
        }
        return truncatePrevious(previous, model.cachingDepth() - 1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Entity<?> truncatePrevious(
            Entity<?> revision, int remainingDepth) {
        if (!(revision instanceof ImmutableModelRoot root)) {
            return revision;
        }
        Entity<?> previous = remainingDepth <= 0
                ? null : truncatePrevious(root.previous(), remainingDepth - 1);
        return root.previous() == previous
                ? root : root.withPrevious((Entity) previous);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> castPrevious(Entity<?> entity) {
        return (Entity<T>) entity;
    }

    record ReconstructionBatch(
            long stateIndex, Map<String, Entity<?>> entities) {
    }

    private record StoredEvent(
            ModelEventMembership membership,
            SerializedMessage event) {
    }

    private record ViewKey(
            String modelId,
            Class<?> modelType,
            long readStateIndex,
            String commitId,
            int substep,
            long commitStateIndex) {
    }

    private record ModelKey(String modelId, Class<?> modelType) {
    }

    private record PayloadKey(long stateIndex, boolean ignoreUnknown) {
    }

    private record HandlerKey(Class<?> payloadType, Class<?> modelType) {
    }

    private record PreparedReplay(
            DeserializingMessage event,
            ModelExecutionPlan plan,
            ModelTargetResolver.Resolution resolution) {
    }

    private record PayloadLookup(
            long[] stateIndices,
            SerializedMessage[] events,
            Map<Long, SerializedMessage> unordered) {

        private static PayloadLookup from(
                List<ModelEventPayload> payloads) {
            long[] stateIndices = new long[payloads.size()];
            SerializedMessage[] events =
                    new SerializedMessage[payloads.size()];
            boolean sorted = true;
            for (int index = 0; index < payloads.size(); index++) {
                ModelEventPayload payload = payloads.get(index);
                stateIndices[index] = payload.getStateIndex();
                events[index] = payload.getEvent();
                sorted &= index == 0
                          || stateIndices[index - 1] < stateIndices[index];
            }
            if (sorted) {
                return new PayloadLookup(stateIndices, events, null);
            }
            Map<Long, SerializedMessage> unordered =
                    new HashMap<>(payloads.size() * 4 / 3 + 1);
            for (int index = 0; index < stateIndices.length; index++) {
                unordered.put(stateIndices[index], events[index]);
            }
            return new PayloadLookup(stateIndices, events, unordered);
        }

        private SerializedMessage getRequired(long stateIndex) {
            SerializedMessage event;
            if (unordered == null) {
                int index = Arrays.binarySearch(stateIndices, stateIndex);
                event = index < 0 ? null : events[index];
            } else {
                event = unordered.get(stateIndex);
            }
            return Objects.requireNonNull(
                    event, "Missing validated model payload");
        }
    }

    private record ReplayAncestorKey(
            ModelTargetResolver.Resolution resolution,
            long relationshipBoundary,
            String commitId,
            int substep) {
    }

    record AncestorResult(
            long stateIndex,
            ModelTargetResolver.Resolution resolution) {
    }

    @FunctionalInterface
    interface AncestorReader {
        AncestorResult resolve(
                ModelTargetResolver.Resolution resolution,
                ModelReadBoundary boundary);
    }

    record Settings(
            int maxStreamsPerRequest,
            int maxMembershipsPerRequest,
            int maxMembershipsPerStream,
            long maxPayloadBytes) {
        Settings {
            if (maxStreamsPerRequest <= 0
                || maxMembershipsPerRequest <= 0
                || maxMembershipsPerStream <= 0
                || maxPayloadBytes <= 0L) {
                throw new IllegalArgumentException("Model event batch limits must be positive");
            }
        }
    }

    record LoadResult(long stateIndex, Map<String, ModelHeadState> heads) {
        LoadResult {
            heads = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(heads));
        }
    }

    /** Coalesces compatible concurrent current reads while leaving local, large and historical reads direct. */
    static final class ReadBatcher {
        private static final long COALESCING_DELAY_NANOS = 200_000L;
        private static final int DIRECT_REQUEST_SIZE = 1_024;

        private final EventStoreClient client;
        private final int maxStreams;
        private final long delayNanos;
        private final ConcurrentLinkedQueue<PendingRead> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean flushing = new AtomicBoolean();

        ReadBatcher(EventStoreClient client, int maxStreams) {
            this(client, maxStreams, COALESCING_DELAY_NANOS);
        }

        ReadBatcher(EventStoreClient client, int maxStreams, long delayNanos) {
            this.client = Objects.requireNonNull(client, "client");
            this.maxStreams = maxStreams;
            if (delayNanos < 0L) {
                throw new IllegalArgumentException("Coalescing delay must not be negative");
            }
            this.delayNanos = delayNanos;
        }

        GetModelEventsResult get(GetModelEvents request) {
            if (!current(request) || request.getRequests().isEmpty()
                || client instanceof LocalEventStoreClient || client instanceof InMemoryEventStore
                || request.getRequests().size() >= DIRECT_REQUEST_SIZE) {
                return client.getModelEvents(request);
            }
            PendingRead read = new PendingRead(request, new CompletableFuture<>());
            pending.add(read);
            if (flushing.compareAndSet(false, true)) {
                Thread.ofVirtual().name("fluxzero-model-read-batcher").start(this::flush);
            }
            return read.result().join();
        }

        private void flush() {
            while (true) {
                LockSupport.parkNanos(delayNanos);
                List<PendingRead> reads = new ArrayList<>(maxStreams);
                PendingRead read;
                while (reads.size() < maxStreams && (read = pending.poll()) != null) {
                    reads.add(read);
                }
                process(reads);
                if (pending.isEmpty()) {
                    flushing.set(false);
                    if (pending.isEmpty() || !flushing.compareAndSet(false, true)) {
                        return;
                    }
                }
            }
        }

        private void process(List<PendingRead> reads) {
            List<PendingRead> remaining = new ArrayList<>(reads);
            while (!remaining.isEmpty()) {
                ReadGroup group = new ReadGroup(maxStreams);
                for (int index = 0; index < remaining.size();) {
                    if (group.add(remaining.get(index))) {
                        remaining.remove(index);
                    } else {
                        index++;
                    }
                }
                group.execute(client);
            }
        }

        private static boolean current(GetModelEvents request) {
            return request.getMaxStateIndex() == null && request.getBoundaryCommitId() == null
                   && request.getBoundaryEventIndex() == null;
        }

        private record PendingRead(GetModelEvents request, CompletableFuture<GetModelEventsResult> result) {
        }

        private static final class ReadGroup {
            private final int limit;
            private final List<PendingRead> reads = new ArrayList<>();
            private final LinkedHashMap<String, ModelEventStreamRequest> streams = new LinkedHashMap<>();
            private long maxBytes;

            private ReadGroup(int limit) {
                this.limit = limit;
            }

            private boolean add(PendingRead read) {
                if (!reads.isEmpty() && reads.getFirst().request().isCompactPayloads()
                    != read.request().isCompactPayloads()) {
                    return false;
                }
                int additional = 0;
                for (ModelEventStreamRequest request : read.request().getRequests()) {
                    ModelEventStreamRequest existing = streams.get(request.getModelId());
                    if (existing == null) {
                        additional++;
                    } else if (!existing.equals(request)) {
                        return false;
                    }
                }
                if (!reads.isEmpty() && streams.size() + additional > limit) {
                    return false;
                }
                reads.add(read);
                read.request().getRequests().forEach(
                        request -> streams.putIfAbsent(request.getModelId(), request));
                maxBytes = Math.max(maxBytes, read.request().getMaxBytes());
                return true;
            }

            private void execute(EventStoreClient client) {
                try {
                    GetModelEventsResult response = client.getModelEvents(new GetModelEvents(
                            List.copyOf(streams.values()), null, maxBytes,
                            reads.getFirst().request().isCompactPayloads()));
                    if (reads.size() == 1) {
                        reads.getFirst().result().complete(response);
                        return;
                    }
                    Map<String, ModelEventStream> responseStreams = new HashMap<>();
                    response.getStreams().forEach(stream -> responseStreams.put(stream.getModelId(), stream));
                    for (PendingRead read : reads) {
                        GetModelEventsResult split = split(read.request(), response, responseStreams);
                        if (madeNoProgress(read.request(), split)) {
                            split = client.getModelEvents(read.request());
                        }
                        read.result().complete(split);
                    }
                } catch (Throwable failure) {
                    reads.forEach(read -> read.result().completeExceptionally(failure));
                }
            }

            private static GetModelEventsResult split(
                    GetModelEvents request, GetModelEventsResult response,
                    Map<String, ModelEventStream> responseStreams) {
                List<ModelEventStream> selected = request.getRequests().stream()
                        .map(ModelEventStreamRequest::getModelId).map(responseStreams::get).toList();
                Set<Long> payloads = new java.util.HashSet<>();
                selected.stream().filter(Objects::nonNull).flatMap(stream -> stream.getMemberships().stream())
                        .forEach(membership -> payloads.add(membership.getStateIndex()));
                return new GetModelEventsResult(
                        request.getRequestId(), response.getStateIndex(),
                        response.getPayloads().stream()
                                .filter(payload -> payloads.contains(payload.getStateIndex())).toList(), selected);
            }

            private static boolean madeNoProgress(GetModelEvents request, GetModelEventsResult response) {
                boolean incomplete = false;
                for (int index = 0; index < request.getRequests().size(); index++) {
                    ModelEventStreamRequest requested = request.getRequests().get(index);
                    ModelEventStream stream = response.getStreams().get(index);
                    if (stream != null && !stream.getMemberships().isEmpty()) {
                        return false;
                    }
                    incomplete |= requested.getMaxSize() > 0 && stream != null && stream.getHead() != null
                                  && requested.getLastSequenceNumber() < stream.getHead().getSequenceNumber();
                }
                return incomplete;
            }
        }
    }

}
