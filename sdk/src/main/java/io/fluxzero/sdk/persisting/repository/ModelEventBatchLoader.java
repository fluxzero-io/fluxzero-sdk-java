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

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Delivers model-stream pages at one pinned state boundary without retaining a complete reconstruction in memory.
 * <p>
 * This is the transport boundary used by model reconstruction. It deliberately does not apply events: cross-model
 * historical dependencies and commit-prefix overlays belong to the reconstruction context, not to transport paging.
 */
final class ModelEventBatchLoader {

    static final Settings DEFAULT_SETTINGS =
            new Settings(32_768, 131_072, 128, 64L * 1_024L * 1_024L);

    private final EventStoreClient eventStoreClient;
    private final ModelEventRequestBatcher requestBatcher;
    private final Settings settings;

    ModelEventBatchLoader(EventStoreClient eventStoreClient) {
        this(eventStoreClient, DEFAULT_SETTINGS);
    }

    ModelEventBatchLoader(EventStoreClient eventStoreClient, Settings settings) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient, "eventStoreClient");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestBatcher = new ModelEventRequestBatcher(
                eventStoreClient, settings.maxStreamsPerRequest());
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
        return load(lastSequenceNumbers, Boundary.at(maxStateIndex), pageConsumer);
    }

    /**
     * Loads stream suffixes at either an explicit state boundary or the persisted state of one commit substep.
     * The commit boundary is resolved by the runtime in the first stream request and all following pages use the
     * returned state index.
     */
    LoadResult load(
            Map<String, Long> lastSequenceNumbers,
            Boundary boundary,
            Consumer<GetModelEventsResult> pageConsumer) {
        Objects.requireNonNull(lastSequenceNumbers, "lastSequenceNumbers");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(pageConsumer, "pageConsumer");
        LinkedHashMap<String, Long> validatedCursors = validateCursors(lastSequenceNumbers);
        List<String> ids = List.copyOf(validatedCursors.keySet());
        if (ids.isEmpty()) {
            GetModelEventsResult response = eventStoreClient.getModelEvents(
                    new GetModelEvents(
                            List.of(), boundary.stateIndex(),
                            boundary.commitId(), boundary.substep(), boundary.eventIndex(),
                            settings.maxPayloadBytes(), true));
            validateBoundary(response, boundary.stateIndex());
            pageConsumer.accept(response);
            return new LoadResult(response.getStateIndex(), Map.of());
        }

        Boundary pinned = boundary;
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
            pinned = Boundary.at(chunk.stateIndex());
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
            Boundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        List<String> ids = validateIds(modelIds);
        if (ids.isEmpty()) {
            return load(Map.of(), boundary, ignored -> {
            });
        }
        Boundary pinned = boundary;
        LinkedHashMap<String, ModelHeadState> heads =
                new LinkedHashMap<>();
        for (int offset = 0;
             offset < ids.size();
             offset += settings.maxStreamsPerRequest()) {
            int until = Math.min(
                    ids.size(),
                    offset + settings.maxStreamsPerRequest());
            List<String> chunkIds =
                    ids.subList(offset, until);
            GetModelEventsResult response =
                    requestBatcher.get(
                            new GetModelEvents(
                                    chunkIds.stream()
                                            .map(modelId ->
                                                         new ModelEventStreamRequest(
                                                                 modelId, -1L, 0))
                                            .toList(),
                                    pinned.stateIndex(),
                                    pinned.commitId(),
                                    pinned.substep(),
                                    pinned.eventIndex(),
                                    settings.maxPayloadBytes(),
                                    true));
            long responseStateIndex =
                    validateBoundary(response, pinned.stateIndex());
            validateHeadPage(response, chunkIds, heads);
            pinned = Boundary.at(responseStateIndex);
        }
        return new LoadResult(pinned.stateIndex(), heads);
    }

    private static void validateHeadPage(
            GetModelEventsResult response,
            List<String> requestedIds,
            Map<String, ModelHeadState> heads) {
        if (!Objects.requireNonNull(
                response.getPayloads(),
                "Model event payloads").isEmpty()) {
            throw invalid(
                    "Head-only model response contains event payloads");
        }
        List<ModelEventStream> streams =
                Objects.requireNonNull(
                        response.getStreams(),
                        "Model event streams");
        if (streams.size() != requestedIds.size()) {
            throw invalid(
                    "Model head response contains %d streams for %d requests"
                            .formatted(
                                    streams.size(),
                                    requestedIds.size()));
        }
        for (int i = 0; i < streams.size(); i++) {
            String requestedId = requestedIds.get(i);
            ModelEventStream stream = streams.get(i);
            if (stream == null
                || !requestedId.equals(stream.getModelId())) {
                throw invalid(
                        "Model head stream %d should be '%s' but was '%s'"
                                .formatted(
                                        i, requestedId,
                                        stream == null
                                                ? null
                                                : stream.getModelId()));
            }
            if (!Objects.requireNonNull(
                    stream.getMemberships(),
                    "Model event memberships").isEmpty()) {
                throw invalid(
                        "Head-only model response contains memberships for "
                        + requestedId);
            }
            ModelHeadState head = stream.getHead();
            if (head != null) {
                validateHead(
                        requestedId, head,
                        response.getStateIndex(), null);
            }
            heads.put(requestedId, head);
        }
    }

    private LoadResult loadChunk(
            LinkedHashMap<String, Long> initialCursors,
            Boundary boundary,
            Consumer<GetModelEventsResult> pageConsumer) {
        LinkedHashMap<String, Long> cursors = new LinkedHashMap<>(initialCursors);
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        Boundary pinned = boundary;

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
                            requests, pinned.stateIndex(),
                            pinned.commitId(), pinned.substep(), pinned.eventIndex(),
                            settings.maxPayloadBytes(), true);
            GetModelEventsResult response = requestBatcher.get(request);
            long responseStateIndex = validateBoundary(
                    response, pinned.stateIndex());
            pinned = Boundary.at(responseStateIndex);

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

    record Boundary(Long stateIndex, String commitId, Integer substep, Long eventIndex) {
        static final Boundary CURRENT = new Boundary(null, null, null, null);

        Boundary {
            if (stateIndex != null && stateIndex < -1L) {
                throw new IllegalArgumentException(
                        "Model maxStateIndex must be at least -1");
            }
            int specified = (stateIndex == null ? 0 : 1)
                            + (commitId == null ? 0 : 1)
                            + (eventIndex == null ? 0 : 1);
            if (specified > 1) {
                throw new IllegalArgumentException(
                        "Specify one model state, commit, or event boundary");
            }
            if ((commitId == null) != (substep == null)
                || commitId != null && (commitId.isBlank() || substep < 0)) {
                throw new IllegalArgumentException(
                        "Model commit boundary requires a non-blank commitId and non-negative substep");
            }
            if (eventIndex != null && eventIndex < 0L) {
                throw new IllegalArgumentException(
                        "Model event boundary must have a non-negative event index");
            }
        }

        static Boundary at(Long stateIndex) {
            return stateIndex == null ? CURRENT : new Boundary(stateIndex, null, null, null);
        }

        static Boundary commit(String commitId, int substep) {
            return new Boundary(null, commitId, substep, null);
        }

        static Boundary event(long eventIndex) {
            return new Boundary(null, null, null, eventIndex);
        }

        boolean historical() {
            return stateIndex != null || commitId != null || eventIndex != null;
        }
    }
}
