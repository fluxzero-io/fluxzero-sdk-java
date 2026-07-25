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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Delivers model-stream pages at one pinned state boundary without retaining a complete reconstruction in memory.
 * <p>
 * This is the transport boundary used by model reconstruction. It deliberately does not apply events: cross-model
 * historical dependencies and action-prefix overlays belong to the reconstruction context, not to transport paging.
 */
final class ModelEventBatchLoader {

    static final Settings DEFAULT_SETTINGS =
            new Settings(1_024, 8_192, 128, 8L * 1_024L * 1_024L);

    private final EventStoreClient eventStoreClient;
    private final Settings settings;

    ModelEventBatchLoader(EventStoreClient eventStoreClient) {
        this(eventStoreClient, DEFAULT_SETTINGS);
    }

    ModelEventBatchLoader(EventStoreClient eventStoreClient, Settings settings) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient, "eventStoreClient");
        this.settings = Objects.requireNonNull(settings, "settings");
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
        Objects.requireNonNull(modelIds, "modelIds");
        Objects.requireNonNull(pageConsumer, "pageConsumer");
        if (maxStateIndex != null && maxStateIndex < -1L) {
            throw new IllegalArgumentException("Model maxStateIndex must be at least -1");
        }
        List<String> ids = validateIds(modelIds);
        if (ids.isEmpty()) {
            GetModelEventsResult response = eventStoreClient.getModelEvents(
                    new GetModelEvents(List.of(), maxStateIndex, settings.maxPayloadBytes()));
            validateBoundary(response, maxStateIndex);
            pageConsumer.accept(response);
            return response.getStateIndex();
        }

        Long pinnedStateIndex = maxStateIndex;
        int maxStreamsPerChunk = Math.min(
                settings.maxStreamsPerRequest(), settings.maxMembershipsPerRequest());
        for (int offset = 0; offset < ids.size(); offset += maxStreamsPerChunk) {
            int until = Math.min(ids.size(), offset + maxStreamsPerChunk);
            pinnedStateIndex = loadChunk(
                    ids.subList(offset, until), pinnedStateIndex, pageConsumer);
        }
        return Objects.requireNonNull(pinnedStateIndex);
    }

    private long loadChunk(
            List<String> modelIds,
            Long requestedStateIndex,
            Consumer<GetModelEventsResult> pageConsumer) {
        LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
        LinkedHashMap<String, ModelHeadState> heads = new LinkedHashMap<>();
        modelIds.forEach(modelId -> cursors.put(modelId, -1L));
        Long pinnedStateIndex = requestedStateIndex;

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
                return Objects.requireNonNull(pinnedStateIndex);
            }

            int perStreamLimit = Math.min(
                    settings.maxMembershipsPerStream(),
                    Math.max(1, settings.maxMembershipsPerRequest() / active.size()));
            List<ModelEventStreamRequest> requests = active.stream()
                    .map(modelId -> new ModelEventStreamRequest(
                            modelId, cursors.get(modelId), perStreamLimit))
                    .toList();
            GetModelEventsResult response = eventStoreClient.getModelEvents(
                    new GetModelEvents(requests, pinnedStateIndex, settings.maxPayloadBytes()));
            long responseStateIndex = validateBoundary(response, pinnedStateIndex);
            if (pinnedStateIndex == null) {
                pinnedStateIndex = responseStateIndex;
            }

            int advanced = validatePage(
                    response, active, cursors, heads, perStreamLimit, settings.maxPayloadBytes());
            pageConsumer.accept(response);
            if (advanced == 0 && hasIncompleteStream(cursors, heads)) {
                throw invalid("Model event page made no progress at state index " + pinnedStateIndex);
            }
        }
    }

    private static List<String> validateIds(List<String> modelIds) {
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
        Map<Long, ModelEventPayload> payloads = new HashMap<>();
        long payloadBytes = 0L;
        for (ModelEventPayload payload : payloadList) {
            if (payload == null || payload.getEvent() == null) {
                throw invalid("Model event response contains a null payload");
            }
            if (payload.getStateIndex() < 0L || payload.getStateIndex() > response.getStateIndex()) {
                throw invalid("Model event payload has invalid state index " + payload.getStateIndex());
            }
            if (payloads.putIfAbsent(payload.getStateIndex(), payload) != null) {
                throw invalid("Duplicate model event payload at state index " + payload.getStateIndex());
            }
            payloadBytes = addSaturated(payloadBytes, payload.getEvent().getBytes());
        }
        if (payloadList.size() > 1 && maxPayloadBytes > 0L && payloadBytes > maxPayloadBytes) {
            throw invalid(
                    "Model event response contains %d payload bytes, exceeding limit %d"
                            .formatted(payloadBytes, maxPayloadBytes));
        }

        List<ModelEventStream> streams =
                Objects.requireNonNull(response.getStreams(), "Model event streams");
        if (streams.size() != requestedIds.size()) {
            throw invalid(
                    "Model event response contains %d streams for %d requests"
                            .formatted(streams.size(), requestedIds.size()));
        }
        Set<Long> referencedPayloads = new HashSet<>();
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
            boolean knownHead = knownHeads.containsKey(requestedId);
            ModelHeadState previousHead = knownHeads.get(requestedId);
            if (head != null) {
                if (knownHead && previousHead == null) {
                    throw invalid("Model head appeared while loading " + requestedId);
                }
                validateHead(requestedId, head, response.getStateIndex(), previousHead);
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
            long cursor = cursors.get(requestedId);
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
                if (!payloads.containsKey(membership.getStateIndex())) {
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
                if (membership.getActionId() == null || membership.getActionId().isBlank()
                    || membership.getSubstep() < 0) {
                    throw invalid("Model stream '" + requestedId + "' has invalid action membership");
                }
                referencedPayloads.add(membership.getStateIndex());
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
        if (!referencedPayloads.equals(payloads.keySet())) {
            Set<Long> unreferenced = new HashSet<>(payloads.keySet());
            unreferenced.removeAll(referencedPayloads);
            throw invalid("Model event response contains unreferenced payloads " + unreferenced);
        }
        return advanced;
    }

    private static void validateHead(
            String requestedId,
            ModelHeadState head,
            long responseStateIndex,
            ModelHeadState previous) {
        if (!requestedId.equals(head.getModelId())) {
            throw invalid(
                    "Model head for '%s' reports ID '%s'".formatted(requestedId, head.getModelId()));
        }
        if (head.getSequenceNumber() < 0L
            || head.getStateIndex() < 0L
            || head.getStateIndex() > responseStateIndex) {
            throw invalid("Model head for '" + requestedId + "' contains invalid positions");
        }
        if (!head.isHistoryComplete()) {
            throw invalid(
                    "Model '%s' cannot be reconstructed at state index %d because its stored history is incomplete"
                            .formatted(requestedId, responseStateIndex));
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
}
