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
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.ModelEventPageDecoder;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.TypeInspection;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit dev/CI inspection of a bounded first page of Model streams or a Graph. No payload deserialization,
 * structural upcasting, replay, writes, imports or handler activation occurs. Existing transport may transfer payload
 * bytes and packed blocks; its byte limit permits one oversized event. Reports retain only identifiers/type metadata.
 * This is a catalog comparison, never a guarantee of complete history, valid upcasters or correct application logic.
 */
public final class ModelDiagnostics {
    private final EventStoreClient client;
    private final ModelTypeResolver models;
    private final Serializer serializer;

    /** Uses the same namespace, local Model catalog and serializer as the application being qualified. */
    public ModelDiagnostics(EventStoreClient client, ModelTypeResolver models, Serializer serializer) {
        this.client = Objects.requireNonNull(client);
        this.models = Objects.requireNonNull(models);
        this.serializer = Objects.requireNonNull(serializer);
    }

    /**
     * Bounds one inspection; no automatic pagination or further Graph expansion occurs.
     * @param maxModels at most 256 Models
     * @param maxEventsPerModel at most 1,024 memberships per Model and 4,096 across the request
     * @param maxBytes positive transport byte budget, at most 16 MiB; one oversized event may exceed it
     * @param maxDepth descendant depth, from 0 through 64
     */
    public record Limits(int maxModels, int maxEventsPerModel, long maxBytes, int maxDepth) {
        /** A small first-page sample suitable for explicit development checks. */
        public static final Limits DEFAULT = new Limits(64, 16, 1024 * 1024, 4);
        /** Rejects unbounded or excessively large diagnostic requests before touching storage. */
        public Limits {
            if (maxModels < 1 || maxModels > 256 || maxEventsPerModel < 1 || maxEventsPerModel > 1024
                || (long) maxModels * maxEventsPerModel > 4096 || maxBytes < 1 || maxBytes > 16 * 1024 * 1024
                || maxDepth < 0 || maxDepth > 64) {
                throw new IllegalArgumentException("Diagnostic limits require bounded Models, events, bytes and depth");
            }
        }
    }

    /**
     * One observed representation, before structural upcasting.
     * @param serializedType stored identifier
     * @param revision stored revision
     * @param format stored serialization format
     * @param localType identifier lookup and local revision; matching revisions do not prove compatibility
     */
    public record ObservedType(String serializedType, int revision, String format, TypeInspection localType) { }

    /**
     * One sampled stream. A null localModelType means that no local Model contract was found.
     * @param requestedId stream identity requested from storage (may be an alias)
     * @param modelId canonical identity, or requestedId for an absent head
     * @param modelType stored logical Model type, null for an absent head
     * @param localModelType local class name, or null when absent/unknown
     * @param headPresent distinguishes an absent Model from an unknown local type
     * @param historyComplete storage's retained-history declaration
     * @param eventsComplete whether the sample contains every sequence from zero through this head
     * @param observedTypes distinct serialized type/revision/format tuples encountered in this first page
     */
    public record ModelContract(String requestedId, String modelId, String modelType, String localModelType,
                                boolean headPresent, boolean historyComplete, boolean eventsComplete,
                                List<ObservedType> observedTypes) {
        /** Copies the bounded report metadata; no payload objects are retained. */
        public ModelContract { observedTypes = List.copyOf(observedTypes); }
    }

    /**
     * A bounded observation, not an application compatibility verdict.
     * @param stateIndex namespace boundary shared by all sampled heads/events
     * @param exactBoundary whether storage resolved the requested selector exactly
     * @param graphSample true for a depth/node-limited Graph sample; never proves a complete Graph closure
     * @param models inspected Model contracts
     */
    public record Report(long stateIndex, boolean exactBoundary, boolean graphSample, List<ModelContract> models) {
        /** Copies the bounded Model list. */
        public Report { models = List.copyOf(models); }
    }

    /** Inspects one bounded first page for each explicitly selected stream at a shared boundary. */
    public Report inspectStreams(List<String> modelIds, ModelReadBoundary boundary, Limits limits) {
        List<String> ids = checkedIds(modelIds, limits);
        var request = new GetModelEvents(ids.stream().map(id -> new ModelEventStreamRequest(
                id, -1L, limits.maxEventsPerModel())).toList(), Objects.requireNonNull(boundary), limits.maxBytes());
        return report(request, client.getModelEvents(request), limits, false);
    }

    /** Inspects a bounded descendant Graph sample; unknown Model contracts remain visible as metadata. */
    public Report inspectGraph(String rootId, ModelReadBoundary boundary, Limits limits) {
        checkedIds(List.of(rootId), limits);
        var request = new GetModelGraph(rootId, Objects.requireNonNull(boundary), limits.maxDepth(), limits.maxModels(),
                                        limits.maxEventsPerModel(), limits.maxBytes(), false);
        var response = client.getModelGraph(request);
        if (response == null || response.getEvents() == null) { throw invalid("Missing Graph event response"); }
        var events = response.getEvents();
        checkSize(events, limits);
        var eventRequest = new GetModelEvents(events.getStreams().stream().map(stream -> new ModelEventStreamRequest(
                stream.getModelId(), -1L, limits.maxEventsPerModel())).toList(), request.getBoundary(), limits.maxBytes());
        return report(eventRequest, events, limits, true);
    }

    private Report report(GetModelEvents request, GetModelEventsResult raw, Limits limits, boolean graph) {
        checkSize(raw, limits);
        if (raw.getPayloads().size() + (long) raw.getPayloadStateIndices().length
            > (long) request.getRequests().size() * limits.maxEventsPerModel()) {
            throw invalid("Diagnostic payload count exceeds the requested event count");
        }
        GetModelEventsResult response = ModelEventPageDecoder.expand(request, raw);
        checkSize(response, limits);
        if (response.getStateIndex() < -1 || request.getBoundary().stateIndex() != null
                && request.getBoundary().stateIndex() != response.getStateIndex()) {
            throw invalid("Diagnostic response changed the requested state boundary");
        }
        if (response.getStreams().size() != request.getRequests().size()) {
            throw invalid("Diagnostic response changed the requested stream count");
        }
        Map<Long, Data<byte[]>> payloads = new LinkedHashMap<>();
        response.getPayloads().forEach(payload -> {
            if (payload == null || payload.getEvent() == null
                || payloads.putIfAbsent(payload.getStateIndex(), payload.getEvent().getData()) != null) {
                throw invalid("Invalid or duplicate diagnostic event payload");
            }
        });
        Map<TypeKey, ObservedType> inspectedTypes = new LinkedHashMap<>();
        List<ModelContract> result = new ArrayList<>();
        for (int index = 0; index < response.getStreams().size(); index++) {
            var stream = response.getStreams().get(index);
            if (!request.getRequests().get(index).getModelId().equals(stream.getModelId())) {
                throw invalid("Diagnostic response changed a requested stream identity");
            }
            var head = stream.getHead();
            if (head != null && (head.getModelType() == null || head.getModelType().isBlank()
                    || head.getModelId() == null || head.getModelId().isBlank() || head.getSequenceNumber() < -1
                    || head.getStateIndex() < 0 || head.getStateIndex() > response.getStateIndex())
                || head == null && !stream.getMemberships().isEmpty()) {
                throw invalid("Diagnostic Model head has invalid type, identity or boundary metadata");
            }
            Class<?> local = head == null ? null : models.knownModelType(head.getModelType(), head.getModelId()).orElse(null);
            LinkedHashSet<ObservedType> types = new LinkedHashSet<>();
            long previous = -1L;
            boolean complete = head == null || head.isHistoryComplete();
            for (var membership : stream.getMemberships()) {
                if (membership == null || membership.getSequenceNumber() <= previous
                    || membership.getSequenceNumber() > head.getSequenceNumber()
                    || membership.getStateIndex() < 0 || membership.getStateIndex() > head.getStateIndex()) {
                    throw invalid("Diagnostic memberships are unordered");
                }
                complete &= membership.getSequenceNumber() == previous + 1L;
                previous = membership.getSequenceNumber();
                Data<?> data = payloads.get(membership.getStateIndex());
                if (data == null) { throw invalid("Diagnostic membership has no payload metadata"); }
                TypeKey key = new TypeKey(data.getType(), data.getRevision(), data.getFormat());
                types.add(inspectedTypes.computeIfAbsent(key, ignored -> new ObservedType(
                        key.type(), key.revision(), key.format(), serializer.inspectType(key.type()))));
            }
            complete &= head == null ? stream.getMemberships().isEmpty() : previous == head.getSequenceNumber();
            result.add(new ModelContract(stream.getModelId(), head == null ? stream.getModelId() : head.getModelId(),
                    head == null ? null : head.getModelType(), local == null ? null : local.getName(),
                    head != null, head == null || head.isHistoryComplete(), complete, List.copyOf(types)));
        }
        return new Report(response.getStateIndex(), response.isExactBoundary(), graph, result);
    }

    private record TypeKey(String type, int revision, String format) { }

    private static void checkSize(GetModelEventsResult result, Limits limits) {
        if (result == null || result.getStreams().size() > limits.maxModels()
            || result.getPayloads().size() + (long) result.getPayloadStateIndices().length > 4096
            || result.getStreams().stream().anyMatch(stream -> stream == null
                || stream.getMemberships().size() > limits.maxEventsPerModel())) {
            throw invalid("Diagnostic response exceeds the requested bounds");
        }
    }

    private static List<String> checkedIds(List<String> ids, Limits limits) {
        Objects.requireNonNull(limits);
        if (ids.isEmpty() || ids.size() > limits.maxModels() || ids.stream().anyMatch(id -> id == null || id.isBlank())
            || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Inspect a nonempty bounded list of unique Model identities");
        }
        return List.copyOf(ids);
    }

    private static ModelReadException invalid(String message) {
        return ModelReadException.failure(ModelReadException.Kind.INVALID_DATA,
                ModelReadException.Operation.INSPECT_CONTRACTS, null, null, null, null, null,
                new IllegalStateException(message));
    }
}
