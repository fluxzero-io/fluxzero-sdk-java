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
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphBefore;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.modeling.CascadedModelDeletion;
import io.fluxzero.sdk.modeling.DirectModelUpdate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.ImmutableRoot;
import io.fluxzero.sdk.modeling.ModelBatchScope;
import io.fluxzero.sdk.modeling.CommitAttempt;
import io.fluxzero.sdk.modeling.ModelExecutionPlan;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.modeling.ModelDefinition;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.InMemoryEventStore;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.EVENT;

/**
 * Owns bounded model-stream reads and the reconstruction sessions that consume them.
 * <p>
 * Transport pages are released synchronously while a session retains only reusable model views, checkpoints and
 * compiled replay definitions. This keeps paging, boundary pinning and replay state under one lifecycle owner.
 */
final class ModelReplayCursor {

    private static final int COMMIT_ANCESTOR_MAX_DEPTH = 64;
    private static final int COMMIT_ANCESTOR_MAX_MODELS = 10_000;
    static final Settings DEFAULT_SETTINGS =
            new Settings(32_768, 131_072, 128, 64L * 1_024L * 1_024L);

    private final EventStoreClient eventStoreClient;
    private final ReadBatcher requestBatcher;
    private final Settings settings;
    private final Serializer serializer;
    private final EntityHelper entityHelper;
    private final ModelDefinition.Compiler modelDefinitionCompiler;
    private final Cache modelCache;
    private final ModelSnapshotStore snapshotStore;
    private final DocumentReader documentReader;
    private final ModelRepository repository;
    private final Map<HandlerKey, ModelDefinition> replayPlans =
            new ConcurrentHashMap<>();

    ModelReplayCursor(EventStoreClient eventStoreClient) {
        this(eventStoreClient, DEFAULT_SETTINGS);
    }

    ModelReplayCursor(EventStoreClient eventStoreClient, Settings settings) {
        this(eventStoreClient, settings, null, null, null, null, null, null, null);
    }

    ModelReplayCursor(
            EventStoreClient eventStoreClient,
            Serializer serializer,
            EntityHelper entityHelper,
            ModelDefinition.Compiler modelDefinitionCompiler,
            Cache modelCache,
            ModelSnapshotStore snapshotStore,
            DocumentReader documentReader,
            ModelRepository repository) {
        this(eventStoreClient, DEFAULT_SETTINGS, serializer, entityHelper, modelDefinitionCompiler,
             modelCache, snapshotStore, documentReader, repository);
    }

    private ModelReplayCursor(
            EventStoreClient eventStoreClient,
            Settings settings,
            Serializer serializer,
            EntityHelper entityHelper,
            ModelDefinition.Compiler modelDefinitionCompiler,
            Cache modelCache,
            ModelSnapshotStore snapshotStore,
            DocumentReader documentReader,
            ModelRepository repository) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient, "eventStoreClient");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestBatcher = new ReadBatcher(
                eventStoreClient, settings.maxStreamsPerRequest());
        this.serializer = serializer;
        this.entityHelper = entityHelper;
        this.modelDefinitionCompiler = modelDefinitionCompiler;
        this.modelCache = modelCache;
        this.snapshotStore = snapshotStore;
        this.documentReader = documentReader;
        this.repository = repository;
    }

    Session session() {
        if (serializer == null || entityHelper == null || modelDefinitionCompiler == null
            || modelCache == null) {
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
     * This supports metadata and alias resolution without transferring event membership or payload.
     */
    LoadResult loadHeads(
            List<String> modelIds,
            ModelReadBoundary boundary) {
        return loadHeads(modelIds, boundary, false);
    }

    /**
     * Loads model heads and rejects streams whose stored history cannot support event replay.
     */
    LoadResult loadReplayableHeads(
            List<String> modelIds,
            ModelReadBoundary boundary) {
        return loadHeads(modelIds, boundary, true);
    }

    private LoadResult loadHeads(
            List<String> modelIds,
            ModelReadBoundary boundary,
            boolean requireCompleteHistory) {
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
            validatePage(
                    response, chunk, cursors, heads, 0,
                    settings.maxPayloadBytes(), requireCompleteHistory);
            pinned = ModelReadBoundary.state(stateIndex, false);
        }
        return new LoadResult(pinned.stateIndex(), heads);
    }

    FirstEvent firstEvent(String modelId, ModelReadBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        List<String> ids = validateIds(List.of(modelId));
        GetModelEventsResult response = requestBatcher.get(new GetModelEvents(
                List.of(new ModelEventStreamRequest(modelId, -1L, 1)),
                boundary.requestStateIndex(), boundary.commitId(), boundary.substep(), boundary.eventIndex(),
                settings.maxPayloadBytes(), true));
        long stateIndex = validateBoundary(response, boundary.stateIndex());
        LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
        cursors.put(modelId, -1L);
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        validatePage(response, ids, cursors, heads, 1, settings.maxPayloadBytes(), false);
        ModelEventStream stream = response.getStreams().getFirst();
        if (stream.getMemberships().isEmpty()) {
            return new FirstEvent(stateIndex, modelId, null);
        }
        long firstStateIndex = stream.getMemberships().getFirst().getStateIndex();
        SerializedMessage event = response.getPayloads().stream()
                .filter(payload -> payload.getStateIndex() == firstStateIndex)
                .map(ModelEventPayload::getEvent).findFirst().orElse(null);
        String resolvedId = stream.getHead() == null ? modelId : stream.getHead().getModelId();
        return new FirstEvent(stateIndex, resolvedId, event);
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
                    settings.maxPayloadBytes(), true);
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
            long maxPayloadBytes,
            boolean requireCompleteHistory) {
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
                validateHead(
                        requestedId, head, response.getStateIndex(), previousHead,
                        requireCompleteHistory);
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
            ModelHeadState previous,
            boolean requireCompleteHistory) {
        if (head.getModelId() == null
            || head.getModelId().isBlank()) {
            throw invalid(
                    "Model head for '%s' reports a blank resolved ID"
                            .formatted(requestedId));
        }
        if (requireCompleteHistory && !head.isHistoryComplete()) {
            throw invalid(
                    "Model '%s' cannot be reconstructed at state index %d because its stored history is incomplete"
                            .formatted(requestedId, responseStateIndex));
        }
        long minimumSequence = requireCompleteHistory ? 0L : -1L;
        if (head.getSequenceNumber() < minimumSequence
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

    /**
     * Resolves direct, collection and dependency targets at one replay boundary.
     *
     * <p>Event streams, documents, cache proofs, aliases and ancestor projections all converge here. The caller may
     * still choose whether the resulting durable context is overlaid with pending message-batch values; that is a
     * projection option and does not create a second load lifecycle.</p>
     */
    CommitAttempt context(
            ModelDefinition.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            String namespace,
            ModelCacheTracker cacheTracker) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(stagedValues, "stagedValues");
        boolean historicalBoundary = boundary.historical();
        if (!historicalBoundary
            && cacheTracker != null
            && resolution.models().stream().anyMatch(
                    target -> EntityMetadata.validate(target.modelType())
                            .rootConfiguration().orElseThrow().cached())) {
            cacheTracker.prepare();
        }

        Long ancestorStateIndex = null;
        if (resolution.hasAncestorDependencies()) {
            AncestorResult ancestors = resolveAncestors(
                    resolution, boundary, stagedValues, includeMessageBatch,
                    includeMessageBatch ? namespace : null);
            resolution = ancestors.resolution();
            ancestorStateIndex = ancestors.stateIndex();
            boundary = ModelReadBoundary.at(ancestorStateIndex);
        }

        List<ModelDefinition.ResolvedModel> eventTargets = new ArrayList<>();
        List<ModelDefinition.ResolvedModel> documentTargets = new ArrayList<>();
        for (ModelDefinition.ResolvedModel target : resolution.models()) {
            EntityMetadata.RootConfiguration configuration = EntityMetadata.validate(target.modelType())
                    .rootConfiguration().orElseThrow();
            (configuration.eventSourced() || historicalBoundary ? eventTargets : documentTargets).add(target);
        }

        Map<String, Entity<?>> loaded = new LinkedHashMap<>();
        long stateIndex;
        if (eventTargets.isEmpty()) {
            stateIndex = ancestorStateIndex == null
                    ? load(Map.of(), boundary, ignored -> {
                    }).stateIndex()
                    : ancestorStateIndex;
        } else {
            CurrentProjection current = !historicalBoundary && ancestorStateIndex == null
                    ? currentProjection(eventTargets, cacheTracker)
                    : null;
            if (current == null) {
                ReconstructionBatch batch = session().reconstruct(eventTargets, boundary);
                stateIndex = batch.stateIndex();
                loaded.putAll(batch.entities());
            } else {
                stateIndex = current.stateIndex();
                loaded.putAll(current.entities());
            }
        }

        boolean writesEventSourcedModel = resolution.models().stream().anyMatch(
                target -> target.access().writes()
                          && EntityMetadata.validate(target.modelType())
                                  .rootConfiguration().orElseThrow().eventSourced());
        List<String> documentDependencies = documentTargets.stream()
                .filter(target -> target.access().reads())
                .map(ModelDefinition.ResolvedModel::modelId)
                .toList();
        if (writesEventSourcedModel && !documentDependencies.isEmpty()) {
            loadReplayableHeads(documentDependencies, ModelReadBoundary.at(stateIndex));
        }

        Long documentCacheBoundary = !historicalBoundary
                                     && cacheTracker != null
                                     && documentTargets.stream().anyMatch(
                target -> EntityMetadata.validate(target.modelType())
                        .rootConfiguration().orElseThrow().cached())
                ? cacheTracker.safeDocumentBoundary()
                : null;
        for (ModelDefinition.ResolvedModel target : documentTargets) {
            Entity<?> entity = documentReader.load(target.modelId(), target.modelType());
            loaded.put(target.modelId(), entity);
            if (EntityMetadata.validate(target.modelType()).rootConfiguration().orElseThrow().cached()
                && documentCacheBoundary != null) {
                modelCache.put(target.modelId(), entity);
            }
        }

        LinkedHashMap<String, Entity<?>> canonicalLoaded = new LinkedHashMap<>(loaded.size());
        List<ModelDefinition.ResolvedModel> canonicalTargets =
                new ArrayList<>(resolution.models().size());
        boolean aliasesResolved = false;
        for (ModelDefinition.ResolvedModel target : resolution.models()) {
            Entity<?> entity = loaded.get(target.modelId());
            String resolvedId = entity != null && entity.isPresent() && entity.id() != null
                    ? entity.id().toString() : target.modelId();
            if (!resolvedId.equals(target.modelId())) {
                if (target.access().writes()) {
                    throw new EventSourcingException(
                            "Writable model target '%s' resolved through alias to '%s'"
                                    .formatted(target.modelId(), resolvedId));
                }
                aliasesResolved = true;
                target = new ModelDefinition.ResolvedModel(
                        resolvedId, target.modelType(), target.access(), target.sourceProperties());
            }
            if (canonicalLoaded.put(resolvedId, entity) != null) {
                throw new EventSourcingException(
                        "Multiple requested model IDs resolve to " + resolvedId);
            }
            canonicalTargets.add(target);
        }
        if (aliasesResolved) {
            resolution = resolution.withResolvedModels(canonicalTargets);
            loaded = canonicalLoaded;
        }

        if (!historicalBoundary && cacheTracker != null) {
            for (ModelDefinition.ResolvedModel target : resolution.models()) {
                EntityMetadata.RootConfiguration configuration = EntityMetadata.validate(target.modelType())
                        .rootConfiguration().orElseThrow();
                if (!configuration.cached()) {
                    continue;
                }
                if (configuration.eventSourced()) {
                    cacheTracker.loaded(target.modelId(), target.modelType(), stateIndex);
                } else if (documentCacheBoundary != null) {
                    cacheTracker.loaded(
                            target.modelId(), target.modelType(), documentCacheBoundary);
                }
            }
        }
        return CommitAttempt.create(stateIndex, resolution, loaded);
    }

    /** Resolves one typed entity as the direct projection strategy of this cursor. */
    EntityProjection entity(
            String modelId,
            Class<?> modelType,
            ModelReadBoundary boundary,
            ModelCacheTracker cacheTracker) {
        EntityMetadata metadata = EntityMetadata.validate(modelType);
        EntityMetadata.RootConfiguration configuration = metadata.rootConfiguration().orElseThrow();
        if (!boundary.historical() && cacheTracker != null && configuration.cached()) {
            ModelCacheTracker.CurrentModel current = cacheTracker.currentVersion(modelId, modelType);
            if (current != null
                && (current.entity().isPresent() || !metadata.hasAliases())) {
                return new EntityProjection(current.validThrough(), current.entity());
            }
        }
        if (!configuration.eventSourced() && !boundary.historical()) {
            Entity<?> loaded = documentReader.load(modelId, modelType);
            long stateIndex = -1L;
            if (loaded.isEmpty() && metadata.hasAliases()) {
                LoadResult alias = loadHeads(List.of(modelId), ModelReadBoundary.CURRENT);
                stateIndex = alias.stateIndex();
                ModelHeadState head = alias.heads().get(modelId);
                String resolvedId = head == null ? modelId : head.getModelId();
                if (!resolvedId.equals(modelId)) {
                    loaded = documentReader.load(resolvedId, modelType);
                }
            }
            if (configuration.cached() && cacheTracker != null) {
                Long safeBoundary = cacheTracker.safeDocumentBoundary();
                if (safeBoundary != null
                    && (loaded.isPresent() || !metadata.hasAliases())) {
                    String resolvedId = loaded.isPresent() ? loaded.id().toString() : modelId;
                    modelCache.put(resolvedId, loaded);
                    cacheTracker.loaded(resolvedId, modelType, safeBoundary);
                    stateIndex = safeBoundary;
                }
            }
            return new EntityProjection(stateIndex, loaded);
        }

        ModelDefinition.ResolvedModel target = new ModelDefinition.ResolvedModel(
                modelId, modelType, ModelDefinition.Access.READ_ONLY,
                List.of(metadata.entityId().orElseThrow().name()));
        CommitAttempt context = context(
                new ModelDefinition.Resolution(List.of(target), List.of()),
                boundary, Map.of(), false, null, cacheTracker);
        return new EntityProjection(
                context.readStateIndex(), context.entity(context.modelIds().getFirst()));
    }

    /** Advances cache projections through this cursor's current replay boundary. */
    ModelCacheTracker.RefreshedBatch refresh(
            Map<String, Class<?>> targets,
            long safeStateIndex) {
        List<ModelDefinition.ResolvedModel> eventTargets = new ArrayList<>();
        List<ModelDefinition.ResolvedModel> documentTargets = new ArrayList<>();
        targets.forEach((modelId, modelType) -> {
            EntityMetadata metadata = EntityMetadata.validate(modelType);
            ModelDefinition.ResolvedModel target = new ModelDefinition.ResolvedModel(
                    modelId, modelType, ModelDefinition.Access.READ_ONLY,
                    List.of(metadata.entityId().orElseThrow().name()));
            (metadata.rootConfiguration().orElseThrow().eventSourced()
                    ? eventTargets : documentTargets).add(target);
        });
        if (!eventTargets.isEmpty()) {
            long reconstructedStateIndex = session().reconstruct(
                    eventTargets, ModelReadBoundary.CURRENT).stateIndex();
            if (reconstructedStateIndex < safeStateIndex) {
                throw new EventSourcingException(
                        "Model reconstruction stopped at state index %d before safe cache boundary %d"
                                .formatted(reconstructedStateIndex, safeStateIndex));
            }
        }
        documentTargets.forEach(target -> modelCache.put(
                target.modelId(), documentReader.load(target.modelId(), target.modelType())));
        return new ModelCacheTracker.RefreshedBatch(safeStateIndex);
    }

    /** Returns a coherent current cache projection, or {@code null} when replay must establish the boundary. */
    private static CurrentProjection currentProjection(
            List<ModelDefinition.ResolvedModel> targets,
            ModelCacheTracker cacheTracker) {
        if (cacheTracker == null || targets.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Entity<?>> entities = new LinkedHashMap<>();
        long latestModelStateIndex = -1L;
        long sharedValidThrough = Long.MAX_VALUE;
        for (ModelDefinition.ResolvedModel target : targets) {
            ModelCacheTracker.CurrentModel current = cacheTracker.currentVersion(
                    target.modelId(), target.modelType());
            if (current == null
                || current.entity().isEmpty()
                   && EntityMetadata.validate(target.modelType()).hasAliases()) {
                return null;
            }
            entities.put(target.modelId(), current.entity());
            latestModelStateIndex = Math.max(
                    latestModelStateIndex, current.modelStateIndex());
            sharedValidThrough = Math.min(sharedValidThrough, current.validThrough());
        }
        return latestModelStateIndex > sharedValidThrough
                ? null
                : new CurrentProjection(sharedValidThrough, Map.copyOf(entities));
    }

    /** Resolves and reconstructs one Graph projection through this cursor and its pinned replay session. */
    <T> Graph<T> graph(
            String rootId,
            Class<T> rootType,
            Graph.Options options,
            ModelReadBoundary boundary,
            String namespace,
            Map<String, Entity<?>> staged,
            boolean historical) {
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(staged, "staged");
        if (documentReader == null || repository == null) {
            throw new EventSourcingException(
                    "Graph reconstruction requires a configured document projection and model repository");
        }
        GetModelGraph request = new GetModelGraph(
                rootId, boundary.requestStateIndex(), boundary.commitId(), boundary.substep(),
                boundary.eventIndex(), options.maxDepth(), options.maxModels(), 0, 0L, false);
        GetModelGraphResult response = boundary.before()
                ? eventStoreClient.getModelGraphBefore(new GetModelGraphBefore(request))
                : eventStoreClient.getModelGraph(request);
        long stateIndex = response.getStateIndex();
        List<ModelDefinition.ResolvedModel> targets = new ArrayList<>(response.getStreams().size());
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        for (ModelEventStream stream : response.getStreams()) {
            Class<?> modelType = graphModelType(stream, rootId, rootType);
            targets.add(new ModelDefinition.ResolvedModel(
                    stream.getModelId(), modelType, ModelDefinition.Access.READ_ONLY,
                    List.of(EntityMetadata.validate(modelType).entityId().orElseThrow().name())));
            heads.put(stream.getModelId(), stream.getHead());
        }
        LinkedHashMap<String, Entity<?>> models = reconstructProjection(
                targets, heads, stateIndex, !boundary.historical());
        Entity<?> stagedRoot = staged.get(rootId);
        if (stagedRoot instanceof io.fluxzero.sdk.modeling.PersistedRoot<?> persisted
            && persisted.sequenceNumber() < 0L && !models.containsKey(rootId)) {
            models.put(rootId, ImmutableModelRoot.builder()
                    .id(rootId).type((Class) stagedRoot.type())
                    .idProperty(EntityMetadata.validate(stagedRoot.type())
                                        .entityId().orElseThrow().name())
                    .value(null).build());
        }
        if (boundary.before()) {
            models.replaceAll((ignored, entity) -> beforeBoundary(entity, stateIndex));
        }
        return Graphs.compose(
                rootId, stateIndex, models, response.getEdges(), repository, historical,
                namespace, rootType, options, staged,
                candidate -> graph(
                        candidate.id().toString(), (Class) candidate.type(), Graph.Options.DEFAULT,
                        ModelReadBoundary.at(stateIndex), namespace, Map.of(), true));
    }

    private LinkedHashMap<String, Entity<?>> reconstructProjection(
            List<ModelDefinition.ResolvedModel> targets,
            Map<String, ModelHeadState> heads,
            long stateIndex,
            boolean cacheAtBoundary) {
        List<ModelDefinition.ResolvedModel> eventTargets = new ArrayList<>();
        List<ModelDefinition.ResolvedModel> documentTargets = new ArrayList<>();
        targets.forEach(target -> (EntityMetadata.validate(target.modelType())
                .rootConfiguration().orElseThrow().eventSourced()
                ? eventTargets : documentTargets).add(target));
        LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
        if (!eventTargets.isEmpty()) {
            ReconstructionBatch reconstructed = session().reconstruct(
                    eventTargets, ModelReadBoundary.at(stateIndex), cacheAtBoundary);
            if (reconstructed.stateIndex() != stateIndex) {
                throw new EventSourcingException(
                        "Model graph moved from state index %d to %d during reconstruction"
                                .formatted(stateIndex, reconstructed.stateIndex()));
            }
            result.putAll(reconstructed.entities());
        }
        for (ModelDefinition.ResolvedModel target : documentTargets) {
            Entity<?> entity = documentReader.load(target.modelId(), target.modelType());
            ModelHeadState expected = heads.get(target.modelId());
            if (expected == null) {
                if (entity.isPresent()) {
                    throw new EventSourcingException(
                            "Model graph has no head for document model " + target.modelId());
                }
            } else {
                entity = withDocumentHead(entity, expected);
            }
            result.put(target.modelId(), entity);
        }
        if (!documentTargets.isEmpty()) {
            Map<String, ModelHeadState> currentHeads = loadHeads(
                    documentTargets.stream().map(ModelDefinition.ResolvedModel::modelId).toList(),
                    ModelReadBoundary.CURRENT).heads();
            for (ModelDefinition.ResolvedModel target : documentTargets) {
                if (!Objects.equals(heads.get(target.modelId()), currentHeads.get(target.modelId()))) {
                    throw new EventSourcingException(
                            "Document model '%s' moved while reconstructing graph boundary"
                                    .formatted(target.modelId()));
                }
            }
        }
        return result;
    }

    private Class<?> graphModelType(
            ModelEventStream stream,
            String rootId,
            Class<?> rootType) {
        ModelHeadState head = stream.getHead();
        String storedType = head == null ? null : head.getModelType();
        if (storedType == null) {
            if (stream.getModelId().equals(rootId)) {
                return rootType;
            }
            throw new EventSourcingException(
                    "Graph child '%s' has no stored model type".formatted(stream.getModelId()));
        }
        Class<?> result;
        try {
            result = io.fluxzero.common.reflection.ReflectionUtils.classForName(
                    serializer.upcastType(storedType));
        } catch (Throwable failure) {
            throw new EventSourcingException(
                    "Could not resolve stored model type '%s' for %s"
                            .formatted(storedType, stream.getModelId()), failure);
        }
        if (stream.getModelId().equals(rootId) && !rootType.isAssignableFrom(result)) {
            throw new EventSourcingException(
                    "Graph root '%s' has stored type %s instead of %s"
                            .formatted(rootId, result.getName(), rootType.getName()));
        }
        EntityMetadata.validate(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Entity<?> withDocumentHead(Entity<?> entity, ModelHeadState head) {
        if (head.isDeleted() != entity.isEmpty()) {
            throw new EventSourcingException(
                    "Document model '%s' has document presence=%s but its head reports deletion=%s"
                            .formatted(head.getModelId(), entity.isPresent(), head.isDeleted()));
        }
        return ImmutableModelRoot.<Object>builder()
                .id(entity.id()).type((Class<Object>) entity.type()).idProperty(entity.idProperty())
                .value(entity.get()).entityHelper(entityHelper).serializer(serializer)
                .sequenceNumber(head.getSequenceNumber()).stateIndex(head.getStateIndex())
                .timestamp(entity.timestamp()).build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity<?> beforeBoundary(Entity<?> entity, long stateIndex) {
        if (!(entity instanceof ModelRoot<?> root) || root.stateIndex() != stateIndex) {
            return entity;
        }
        Entity<?> previous = root.previous();
        return previous != null ? previous : ImmutableModelRoot.builder()
                .id(entity.id()).type((Class) entity.type())
                .idProperty(EntityMetadata.validate(entity.type()).entityId().orElseThrow().name())
                .entityHelper(entityHelper).serializer(serializer).value(null).build();
    }

    AncestorResult resolveAncestors(
            ModelDefinition.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            String namespace) {
        return resolveAncestors(
                resolution, boundary, stagedValues, includeMessageBatch, namespace,
                true, false, false,
                COMMIT_ANCESTOR_MAX_DEPTH, COMMIT_ANCESTOR_MAX_MODELS);
    }

    AncestorResult resolveAncestors(
            ModelDefinition.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            String namespace,
            boolean requireAncestors,
            boolean closestAncestorsOnly,
            boolean allowMultipleAncestors,
            int maxDepth,
            int maxModels) {
        if (resolution.models().isEmpty()) {
            throw new IllegalStateException(
                    "Ancestor injection requires at least one direct model target from which to traverse");
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        resolution.models().forEach(target -> roots.add(target.modelId()));
        LinkedHashMap<String, Object> effectiveStagedValues = new LinkedHashMap<>(stagedValues);
        Map<String, Class<?>> stagedTypes;
        List<ModelGraphEdge> stagedEdges;
        GetModelGraphResult graph;
        for (int expansion = 0; ; expansion++) {
            if (maxDepth >= 0 && expansion > maxDepth) {
                throw new IllegalStateException(
                        "Message-batch ancestor overlay exceeds maximum depth " + maxDepth);
            }
            LinkedHashSet<String> requestRoots = new LinkedHashSet<>(roots);
            stagedTypes = new LinkedHashMap<>();
            stagedEdges = new ArrayList<>();
            for (Map.Entry<String, Object> entry : effectiveStagedValues.entrySet()) {
                requestRoots.add(entry.getKey());
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                EntityMetadata metadata = EntityMetadata.validate(value.getClass());
                stagedTypes.put(entry.getKey(), value.getClass());
                for (EntityMetadata.ParentReference parent : metadata.parentReferences()) {
                    Object parentId = parent.read(value);
                    if (parentId == null) {
                        continue;
                    }
                    String parentIdString = Objects.requireNonNull(
                            parent.repositoryId(parentId),
                            () -> "@ParentId " + parent.property().name() + " returned a null ID string");
                    requestRoots.add(parentIdString);
                    Class<?> parentType = parent.parentModelType(parentId);
                    stagedEdges.add(new ModelGraphEdge(
                            entry.getKey(), parentIdString,
                            parentType == null ? null : parentType.getName(),
                            parent.path().isEmpty() ? null : parent.path(), -1L, null));
                }
            }
            if (maxModels >= 0 && requestRoots.size() > maxModels) {
                throw new IllegalStateException(
                        "Model commit requires more than %d ancestor traversal roots".formatted(maxModels));
            }
            graph = eventStoreClient.getModelAncestors(new GetModelAncestors(
                    List.copyOf(requestRoots), boundary.stateIndex(), boundary.commitId(), boundary.substep(),
                    boundary.eventIndex(), maxDepth, maxModels, 0, 0L));
            if (!includeMessageBatch || !addPendingAncestorValues(
                    requestRoots, graph, effectiveStagedValues, namespace)) {
                break;
            }
        }
        List<ModelGraphEdge> edges = new ArrayList<>(graph.getEdges());
        if (!effectiveStagedValues.isEmpty()) {
            edges.removeIf(edge -> effectiveStagedValues.containsKey(edge.getChildId()));
            edges.addAll(stagedEdges);
        }
        List<Graphs.AncestorPlacement> reachable =
                Graphs.ancestors(roots, edges, maxDepth, maxModels);
        Map<String, Graphs.AncestorPlacement> reachableById = reachable.stream().collect(
                java.util.stream.Collectors.toMap(
                        Graphs.AncestorPlacement::id, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, ModelHeadState> heads = new LinkedHashMap<>();
        graph.getStreams().forEach(stream -> heads.put(stream.getModelId(), stream.getHead()));
        Map<String, Class<?>> knownTypes = new LinkedHashMap<>();
        resolution.models().forEach(target -> knownTypes.put(target.modelId(), target.modelType()));
        knownTypes.putAll(stagedTypes);
        for (Graphs.AncestorPlacement placement : reachable) {
            String modelId = placement.id();
            Class<?> storedType = resolveAncestorType(
                    modelId, heads.get(modelId), placement.incoming());
            if (storedType != null) {
                knownTypes.merge(modelId, storedType,
                                 (left, right) -> compatible(left, right)
                                         ? left.isAssignableFrom(right) ? right : left
                                         : incompatibleStoredTypes(modelId, left, right));
            }
        }
        LinkedHashMap<String, ModelDefinition.ResolvedModel> selected = new LinkedHashMap<>();
        resolution.models().forEach(target -> selected.put(target.modelId(), target));
        for (ModelDefinition.AncestorDependency dependency : resolution.ancestorDependencies()) {
            List<String> candidates = reachable.stream()
                    .map(Graphs.AncestorPlacement::id)
                    .filter(modelId -> {
                        Class<?> actualType = knownTypes.get(modelId);
                        return actualType == null || compatible(dependency.modelType(), actualType);
                    })
                    .filter(modelId -> dependency.association() == null
                                       || reachableById.get(modelId).incoming().stream().anyMatch(
                            edge -> dependency.association().equals(edge.getPath())))
                    .toList();
            if (closestAncestorsOnly && candidates.size() > 1) {
                int closestDepth = candidates.stream()
                        .mapToInt(candidate -> reachableById.get(candidate).depth()).min().orElseThrow();
                candidates = candidates.stream()
                        .filter(candidate -> reachableById.get(candidate).depth() == closestDepth).toList();
            }
            if (candidates.isEmpty()) {
                if (!requireAncestors) {
                    continue;
                }
                throw new IllegalStateException(
                        "No reachable ancestor of type %s%s was found for %s from model roots %s"
                                .formatted(
                                        dependency.modelType().getName(),
                                        dependency.association() == null ? ""
                                                : " at @ParentId path '" + dependency.association() + "'",
                                        dependency.handler(), roots));
            }
            if (candidates.size() > 1 && !allowMultipleAncestors) {
                throw new IllegalStateException(
                        "Multiple reachable ancestors of type %s%s were found for %s: %s. "
                                .formatted(
                                        dependency.modelType().getName(),
                                        dependency.association() == null ? ""
                                                : " at @ParentId path '" + dependency.association() + "'",
                                        dependency.handler(), candidates)
                        + "Qualify the parameter with @Association(\"parentPath\").");
            }
            for (String modelId : candidates) {
                Class<?> modelType = knownTypes.getOrDefault(modelId, dependency.modelType());
                EntityMetadata.validate(modelType);
                String sourceProperty = dependency.association() == null
                        ? EntityMetadata.validate(modelType).entityId().orElseThrow().name()
                        : dependency.association();
                ModelDefinition.merge(
                        selected, new ModelDefinition.ResolvedModel(
                                modelId, modelType, ModelDefinition.Access.READ_ONLY,
                                List.of(sourceProperty)));
            }
        }
        return new AncestorResult(
                graph.getStateIndex(),
                resolution.withResolvedModels(List.copyOf(selected.values())));
    }

    private boolean addPendingAncestorValues(
            Collection<String> requestRoots,
            GetModelGraphResult graph,
            Map<String, Object> stagedValues,
            String namespace) {
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>(requestRoots);
        graph.getStreams().forEach(stream -> candidateIds.add(stream.getModelId()));
        graph.getEdges().forEach(edge -> {
            candidateIds.add(edge.getChildId());
            candidateIds.add(edge.getParentId());
        });
        boolean changed = false;
        for (String modelId : candidateIds) {
            if (stagedValues.containsKey(modelId)) {
                continue;
            }
            Entity<?> pending = ModelBatchScope.currentValue(namespace, modelId);
            if (pending != null) {
                stagedValues.put(modelId, pending.get());
                changed = true;
            }
        }
        return changed;
    }

    private Class<?> resolveAncestorType(
            String modelId,
            ModelHeadState head,
            List<ModelGraphEdge> incoming) {
        LinkedHashSet<String> storedTypes = new LinkedHashSet<>();
        if (head != null && head.getModelType() != null) {
            storedTypes.add(head.getModelType());
        }
        incoming.stream().map(ModelGraphEdge::getParentType)
                .filter(Objects::nonNull).forEach(storedTypes::add);
        Class<?> result = null;
        for (String storedType : storedTypes) {
            Class<?> candidate;
            try {
                candidate = io.fluxzero.common.reflection.ReflectionUtils.classForName(
                        serializer.upcastType(storedType));
            } catch (Throwable failure) {
                throw new EventSourcingException(
                        "Could not resolve stored model type '%s' for ancestor %s"
                                .formatted(storedType, modelId), failure);
            }
            EntityMetadata.validate(candidate);
            result = result == null ? candidate
                    : compatible(result, candidate)
                            ? result.isAssignableFrom(candidate) ? candidate : result
                            : incompatibleStoredTypes(modelId, result, candidate);
        }
        return result;
    }

    private static Class<?> incompatibleStoredTypes(
            String modelId,
            Class<?> left,
            Class<?> right) {
        throw new EventSourcingException(
                "Model ancestor '%s' is described by incompatible types %s and %s"
                        .formatted(modelId, left.getName(), right.getName()));
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
        private final ConcurrentMap<ModelKey, NavigableMap<Long, Entity<?>>> checkpoints =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<PayloadKey, List<DeserializingMessage>>
                deserializedEvents = new ConcurrentHashMap<>();
        private final Map<ReplayAncestorKey, ModelDefinition.Resolution>
                replayAncestorResolutions =
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<ReplayAncestorKey,
                                    ModelDefinition.Resolution> eldest) {
                        return size() > 1_024;
                    }
                };

        ReconstructionBatch reconstruct(
                List<ModelDefinition.ResolvedModel> targets,
                ModelReadBoundary boundary) {
            return reconstruct(
                    targets, boundary, !boundary.historical());
        }

        ReconstructionBatch reconstruct(
                List<ModelDefinition.ResolvedModel> targets,
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
            for (ModelDefinition.ResolvedModel target : targets) {
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
            for (ModelDefinition.ResolvedModel target : targets) {
                ModelHeadState head = loaded.heads().get(target.modelId());
                MutableReconstruction state = states.get(target.modelId());
                Entity<?> entity = head == null ? empty(target) : withHead(state.current, head);
                ModelDefinition.ResolvedModel resolvedTarget = state.target;
                validateReconstruction(resolvedTarget, head, entity);
                boolean cacheable = cacheAtBoundary && head != null && head.isHistoryComplete()
                                    && EntityMetadata.of(resolvedTarget.modelType())
                                            .rootConfiguration().orElseThrow().cached();
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
                ModelDefinition.ResolvedModel target,
                Long maxStateIndex,
                boolean allowCurrentCache) {
            EntityMetadata.RootConfiguration configuration = EntityMetadata.of(target.modelType())
                    .rootConfiguration().orElseThrow();
            if (allowCurrentCache
                && configuration.cached()) {
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
            if (configuration.snapshotPeriod() > 0 && snapshotStore != null
                && (maxStateIndex != null || allowCurrentCache)) {
                result = snapshotStore.getSnapshot(
                                target.modelId(), maxStateIndex)
                        .map(snapshot -> fromSnapshot(target, snapshot))
                        .orElse(null);
            }
            if (maxStateIndex != null) {
                NavigableMap<Long, Entity<?>> known = checkpoints.get(
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
                ModelDefinition.ResolvedModel target,
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
                    target.modelId(), EntityMetadata.of(target.modelType()),
                    snapshot.value());
            return ImmutableModelRoot.<Object>builder()
                    .id(target.modelId())
                    .type((Class<Object>) target.modelType())
                    .idProperty(EntityMetadata.of(target.modelType())
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

        private ModelDefinition directReplayPlan(
                SerializedMessage event,
                Class<?> modelType) {
            Class<?> payloadType =
                    serializer.serializedClassWithoutUpcasting(
                            event);
            if (payloadType == null) {
                return null;
            }
            ModelDefinition definition =
                    replayPlans.computeIfAbsent(
                            new HandlerKey(
                                    payloadType,
                                    modelType),
                            ignored ->
                                    replayPlan(
                                            payloadType,
                                            modelType));
            return definition.direct() ? definition : null;
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
            ImmutableRoot.replay(
                    state, stream.getMemberships().iterator(),
                    (current, membership) -> {
                        StoredEvent storedEvent = new StoredEvent(
                                membership,
                                payloads.getRequired(membership.getStateIndex()));
                        ModelDefinition directDefinition = directReplayPlan(
                                storedEvent.event(), current.target.modelType());
                        if (directDefinition == null) {
                            current.apply(storedEvent);
                        } else {
                            current.applyCompiled(storedEvent, directDefinition);
                        }
                        return current;
                    },
                    (current, membership, error) -> error instanceof EventSourcingException replayFailure
                            ? replayFailure : new EventSourcingException(
                            "Failed to apply model event at state %d to %s"
                                    .formatted(membership.getStateIndex(), current.target.modelId()), error));
        }

        private Map<String, Entity<?>> reconstructAt(
                List<ModelDefinition.ResolvedModel> targets,
                long stateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelDefinition.ResolvedModel> missing = new ArrayList<>();
            for (ModelDefinition.ResolvedModel target : targets) {
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
                ModelDefinition.ResolvedModel target,
                long readStateIndex,
                String commitId,
                int substep,
                long commitStateIndex) {
            return reconstructViews(
                    List.of(target), readStateIndex, commitId,
                    substep, commitStateIndex).get(target.modelId());
        }

        private Map<String, Entity<?>> reconstructViews(
                List<ModelDefinition.ResolvedModel> targets,
                long readStateIndex,
                String commitId,
                int substep,
                long commitStateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelDefinition.ResolvedModel> missing =
                    new ArrayList<>();
            for (ModelDefinition.ResolvedModel target : targets) {
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
            for (ModelDefinition.ResolvedModel target : missing) {
                Entity<?> entity = base.get(target.modelId());
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        commitId, substep, commitStateIndex), entity);
                result.put(target.modelId(), entity);
            }
            return ordered(targets, result);
        }

        private Map<String, Entity<?>> ordered(
                List<ModelDefinition.ResolvedModel> targets,
                Map<String, Entity<?>> values) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            targets.forEach(target -> result.put(
                    target.modelId(), values.get(target.modelId())));
            return result;
        }

        private void applyCommitPrefix(
                GetModelEventsResult page,
                List<ModelDefinition.ResolvedModel> targets,
                Map<String, Entity<?>> current,
                long readStateIndex,
                String commitId,
                int substep) {
            Map<String, ModelDefinition.ResolvedModel> targetsById =
                    new HashMap<>();
            targets.forEach(target -> targetsById.put(
                    target.modelId(), target));
            Map<Long, io.fluxzero.common.api.SerializedMessage> payloads =
                    new HashMap<>();
            for (ModelEventPayload payload : page.getPayloads()) {
                payloads.put(payload.getStateIndex(), payload.getEvent());
            }
            for (ModelEventStream stream : page.getStreams()) {
                ModelDefinition.ResolvedModel target =
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
            private ModelDefinition.ResolvedModel target;
            private final Entity<?> base;
            private Entity<?> current;
            private ModelEventMembership previous;

            private MutableReconstruction(
                    ModelDefinition.ResolvedModel target, Entity<?> base) {
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
                target = new ModelDefinition.ResolvedModel(
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
                    ModelDefinition definition) {
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
                        new PreparedReplay(event, definition, null)));
            }

        }

        private void rememberCheckpoint(
                ModelDefinition.ResolvedModel target, Entity<?> entity) {
            EntityMetadata.RootConfiguration configuration = EntityMetadata.of(target.modelType())
                    .rootConfiguration().orElseThrow();
            int period = configuration.checkpointPeriod();
            if (period <= 0 || entity.sequenceNumber() < 0
                || Math.floorMod(entity.sequenceNumber() + 1L, period) != 0L) {
                return;
            }
            NavigableMap<Long, Entity<?>> known = checkpoints.computeIfAbsent(
                    new ModelKey(target.modelId(), target.modelType()),
                    ignored -> Collections.synchronizedNavigableMap(new TreeMap<>()));
            synchronized (known) {
                known.put(stateIndex(entity), entity);
                while (known.size() > 1_024) {
                    known.pollFirstEntry();
                }
            }
        }

        private Entity<?> apply(
                ModelDefinition.ResolvedModel target,
                Entity<?> begin,
                StoredEvent storedEvent) {
            ModelEventMembership membership = storedEvent.membership();
            return apply(
                    target, begin, storedEvent,
                    prepareReplay(target, membership, storedEvent));
        }

        private Entity<?> apply(
                ModelDefinition.ResolvedModel target,
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
                ModelDefinition definition = preparedReplay.definition();
                if (definition.empty()) {
                    if (EntityMetadata.of(target.modelType()).rootConfiguration().orElseThrow()
                            .ignoreUnknownEvents()) {
                        continue;
                    }
                    throw new EventSourcingException(
                            "No replay apply found for %s on model %s"
                                    .formatted(payloadType.getName(), target.modelType().getName()));
                }
                if (definition.direct()) {
                    CommitAttempt context = CommitAttempt.createSingle(
                            stateIndex(result), target.modelId(), target.modelType(),
                            ModelDefinition.Access.READ_WRITE, List.of(), result);
                    result = updateValue(
                            result, replayValue(
                                    target, membership.getStateIndex(),
                                    event, context, definition));
                    continue;
                }
                ModelDefinition.Resolution resolution =
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
                    ModelDefinition.Resolution directResolution =
                            resolution;
                    boolean firstSubstep =
                            membership.getSubstep() == 0;
                    resolution =
                            replayAncestorResolutions.computeIfAbsent(
                                    key, ignored -> {
                                        AncestorResult ancestors =
                                                resolveAncestors(
                                                        directResolution,
                                                        firstSubstep
                                                                ? ModelReadBoundary.at(
                                                                        relationshipBoundary)
                                                                : ModelReadBoundary.commit(
                                                                        membership.getCommitId(),
                                                                        membership.getSubstep() - 1),
                                                        Map.of(), false, null,
                                                        true, false, false,
                                                        COMMIT_ANCESTOR_MAX_DEPTH,
                                                        COMMIT_ANCESTOR_MAX_MODELS);
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
                List<ModelDefinition.ResolvedModel> dependencies =
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
                    for (ModelDefinition.ResolvedModel dependency :
                            resolution.models()) {
                        Entity<?> entity =
                                dependency.modelId().equals(target.modelId())
                                        ? result
                                        : dependencyViews.get(
                                                dependency.modelId());
                        loaded.put(dependency.modelId(), entity);
                    }
                }
                CommitAttempt context = CommitAttempt.create(
                        membership.getReadStateIndex(), resolution, loaded);
                result = updateValue(
                        result, replayValue(
                                target, membership.getStateIndex(),
                                event, context, definition));
            }
            return withMembership(result, storedEvent, begin);
        }

        private List<PreparedReplay> prepareReplay(
                ModelDefinition.ResolvedModel target,
                ModelEventMembership membership,
                StoredEvent storedEvent) {
            return deserialize(target.modelType(), membership, storedEvent).stream()
                    .map(event -> {
                        Class<?> payloadType =
                                event.getPayloadClass();
                        ModelDefinition definition =
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
                                definition,
                                definition.empty()
                                || definition.direct()
                                        ? null
                                        : definition.targets()
                                                .resolve(
                                                        event.getPayload()));
                    })
                    .toList();
        }

        private Object replayValue(
                ModelDefinition.ResolvedModel target,
                long stateIndex,
                DeserializingMessage event,
                CommitAttempt context,
                ModelDefinition definition) {
            try {
                DeserializingMessage replayEvent = definition.direct()
                        ? event : new DeserializingMessage(
                                event.toMessage(), EVENT, null, serializer);
                return definition.replay(
                        replayEvent, context, target.modelId());
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
            boolean ignoreUnknown = EntityMetadata.of(modelType).rootConfiguration().orElseThrow()
                    .ignoreUnknownEvents();
            PayloadKey key = new PayloadKey(
                    membership.getStateIndex(), ignoreUnknown);
            return deserializedEvents.computeIfAbsent(key, ignored ->
                    serializer.deserializeMessages(
                                    Stream.of(storedEvent.event()), EVENT,
                                    ignoreUnknown ? UnknownTypeStrategy.IGNORE : UnknownTypeStrategy.FAIL)
                            .toList());
        }

        private ModelDefinition replayPlan(
                Class<?> payloadType, Class<?> modelType) {
            LinkedHashSet<EntityMetadata.HandlerMethod> result = new LinkedHashSet<>();
            EntityMetadata.of(payloadType).applyMethods().stream()
                    .filter(handler -> handler.dynamicApplyResult()
                                       || handler.targetModelTypes().stream()
                                               .anyMatch(target -> compatible(target, modelType)))
                    .forEach(result::add);
            EntityMetadata.of(modelType).applyMethods().stream()
                    .filter(handler -> EntityMetadata.acceptsPayload(handler, payloadType))
                    .forEach(result::add);
            List<EntityMetadata.HandlerMethod> handlers = List.copyOf(result);
            return modelDefinitionCompiler.compileReplay(
                    payloadType, modelType, handlers);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> empty(ModelDefinition.ResolvedModel target) {
            EntityMetadata metadata = EntityMetadata.validate(target.modelType());
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
            EntityMetadata.RootConfiguration configuration = EntityMetadata.of(entity.type())
                    .rootConfiguration().orElseThrow();
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
                    .previous(castPrevious(ImmutableRoot.retainPrevious(
                            previous, configuration)))
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
                ModelDefinition.ResolvedModel target,
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
            validateValueId(target.modelId(), EntityMetadata.of(target.modelType()), entity.get());
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
            String modelId, EntityMetadata metadata, Object value) {
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
            ModelDefinition definition,
            ModelDefinition.Resolution resolution) {
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
            ModelDefinition.Resolution resolution,
            long relationshipBoundary,
            String commitId,
            int substep) {
    }

    record AncestorResult(
            long stateIndex,
            ModelDefinition.Resolution resolution) {
    }

    private record CurrentProjection(
            long stateIndex,
            Map<String, Entity<?>> entities) {
    }

    @FunctionalInterface
    interface DocumentReader {
        Entity<?> load(String modelId, Class<?> modelType);
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

    record FirstEvent(long stateIndex, String modelId, SerializedMessage event) {
    }

    record EntityProjection(long stateIndex, Entity<?> entity) {
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
