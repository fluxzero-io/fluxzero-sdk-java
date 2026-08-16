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
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.eventsourcing.AppendEvents;
import io.fluxzero.common.api.eventsourcing.DeleteEvents;
import io.fluxzero.common.api.eventsourcing.EventBatch;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.common.api.eventsourcing.GetEventsResult;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetAggregateIdsResult;
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphBefore;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventPayloadBlock;
import io.fluxzero.common.api.modeling.ModelEventDataBlock;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.GetRelationshipsResult;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.common.api.modeling.ModelWebSocketCodec;
import io.fluxzero.common.api.modeling.ModelStreamBatchDecoder;
import io.fluxzero.common.jfr.FluxzeroJfr;
import io.fluxzero.common.serialization.SerializedMessagePackCodec;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketPayloadCodec;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.iterate;

/**
 * WebSocket-based implementation of the {@link EventStoreClient}, enabling interaction with the Fluxzero Runtime's event
 * store via a persistent WebSocket connection.
 *
 * <p>This implementation supports:
 * <ul>
 *   <li>Storing events for event-sourced aggregates</li>
 *   <li>Efficient, paginated retrieval of aggregate event streams</li>
 *   <li>Deleting aggregate event streams</li>
 *   <li>Maintaining aggregate/entity relationships</li>
 * </ul>
 *
 * <p>The {@code fetchBatchSize} setting controls how many events are fetched per paginated request when loading
 * an aggregate's event history. This ensures efficient memory usage while still supporting large aggregates.
 *
 * <p>End users rarely interact with this client directly. Instead, they typically use higher-level abstractions
 * such as {@link io.fluxzero.sdk.persisting.eventsourcing.EventStore} or
 * {@link io.fluxzero.sdk.persisting.repository.AggregateRepository}.
 *
 * @see EventStoreClient
 * @see io.fluxzero.sdk.persisting.eventsourcing.EventStore
 * @see io.fluxzero.sdk.persisting.repository.AggregateRepository
 */
public class WebSocketEventStoreClient extends AbstractWebsocketClient
        implements EventStoreClient, ModelCommitResultBatchSource,
        ModelCommitBatchingClient {

    private static final int READY_MODEL_COMMIT_BATCH_SIZE = Math.max(
            1, Integer.getInteger("fluxzero.readyModelCommitBatchSize", 256));

    private final int fetchBatchSize;
    private final List<Function<List<CommitModelsResult>, CompletableFuture<Void>>>
            modelCommitResultProcessors = new CopyOnWriteArrayList<>();

    @Override
    protected List<? extends WebSocketPayloadCodec> payloadCodecs() {
        return List.of(ModelWebSocketCodec.INSTANCE);
    }

    @Override
    protected int maxRequestBatchSize(List<Request> requests) {
        return requests.stream().allMatch(CommitModels.class::isInstance)
                ? Integer.MAX_VALUE
                : super.maxRequestBatchSize(requests);
    }

    @Override
    protected FluxzeroJfr.Batch startRequestBatchEvent(List<Request> requests) {
        return FluxzeroJfr.batchEnabled()
                && requests.stream().allMatch(CommitModels.class::isInstance)
                ? FluxzeroJfr.startBatch(
                        "sdk.websocket-request", "send", "commit-models",
                        requests.size(), 0L, 0L, 0L)
                : null;
    }

    @Override
    protected String jfrResultType(List<RequestResult> results) {
        if (results.isEmpty() || results.getFirst() == null) {
            return "RESULT";
        }
        Class<?> type = results.getFirst().getClass();
        String label = type == CommitModelsResult.class
                ? "MODEL_COMMIT"
                : type == TrackModelUpdatesResult.class ? "MODEL_UPDATE" : "RESULT";
        if ("RESULT".equals(label)) {
            return label;
        }
        return results.stream().allMatch(result -> result != null && result.getClass() == type)
                ? label : "RESULT";
    }

    @Override
    protected void recordRequestStages(List<Request> requests, String stage) {
        if (!FluxzeroJfr.requestStageEnabled()) {
            return;
        }
        int batchSize = requests.size();
        requests.stream().filter(CommitModels.class::isInstance)
                .map(CommitModels.class::cast)
                .forEach(commit -> recordCommitStage(
                        commit.getCommitId(), "sdk.model-transport", stage, batchSize));
    }

    @Override
    protected void recordResultStages(List<RequestResult> results, String stage) {
        if (!FluxzeroJfr.requestStageEnabled()) {
            return;
        }
        int batchSize = results.size();
        results.stream().filter(CommitModelsResult.class::isInstance)
                .map(CommitModelsResult.class::cast)
                .forEach(commit -> recordCommitStage(
                        commit.getCommitId(), "sdk.websocket-input.MODEL", stage, batchSize));
    }

    private static void recordCommitStage(
            String commitId, String component, String stage, int batchSize) {
        Long traceId = FluxzeroJfr.resolveTraceCorrelation(commitId);
        if (traceId != null) {
            FluxzeroJfr.requestStage(
                    traceId, component, stage, batchSize, traceId);
        }
    }

    /**
     * Creates a new {@code WebSocketEventStoreClient} with a default batch size of 8192.
     *
     * @param endPointUrl The URL to the Fluxzero Runtime event sourcing endpoint.
     * @param client      The WebSocket client instance.
     */
    public WebSocketEventStoreClient(String endPointUrl, WebSocketClient client) {
        this(URI.create(endPointUrl), 8192, client);
    }

    /**
     * Creates a new {@code WebSocketEventStoreClient} with a specified batch size.
     *
     * @param endPointUri    The URI to the event store endpoint.
     * @param fetchBatchSize Maximum number of events to retrieve per page.
     * @param client         The WebSocket client.
     */
    public WebSocketEventStoreClient(URI endPointUri, int fetchBatchSize, WebSocketClient client) {
        this(endPointUri, fetchBatchSize, client, true);
    }

    /**
     * Constructs the WebSocket client with full customization.
     *
     * @param endPointUri    URI of the event sourcing endpoint.
     * @param fetchBatchSize The size of event batches fetched from the server.
     * @param client         The WebSocket client.
     * @param sendMetrics    Whether to send metrics to the Fluxzero Runtime.
     */
    public WebSocketEventStoreClient(URI endPointUri, int fetchBatchSize, WebSocketClient client,
                                     boolean sendMetrics) {
        super(endPointUri, client, sendMetrics, client.getClientConfig().getEventSourcingSessions());
        this.fetchBatchSize = fetchBatchSize;
    }

    /**
     * Stores events for a specific aggregate, with control over store-only mode and delivery guarantee.
     */
    @Override
    public CompletableFuture<Void> storeEvents(String aggregateId, List<SerializedMessage> events, boolean storeOnly,
                                               Guarantee guarantee) {
        return sendCommand(new AppendEvents(List.of(new EventBatch(aggregateId, events, storeOnly)), guarantee));
    }

    /**
     * Commits an independent-model commit and retains the positions returned by the runtime.
     */
    @Override
    public CompletableFuture<CommitModelsResult> commitModels(CommitModels commit) {
        return send(commit);
    }

    @Override
    public ModelCommitBatch beginModelCommitBatch(int capacity) {
        return new WebSocketModelCommitBatch(capacity);
    }

    @Override
    public ModelCommitBatch beginReadyModelCommitBatch() {
        return new WebSocketReadyModelCommitBatch();
    }

    private final class WebSocketReadyModelCommitBatch
            implements ModelCommitBatch {
        private List<PreparedRequest<CommitModelsResult>> pending =
                new ArrayList<>(READY_MODEL_COMMIT_BATCH_SIZE);
        private boolean completed;

        @Override
        public synchronized CompletableFuture<CommitModelsResult> add(
                int slot, CommitModels commit) {
            if (completed) {
                return commitModels(commit);
            }
            PreparedRequest<CommitModelsResult> request =
                    prepareRequest(commit);
            pending.add(request);
            if (pending.size() == READY_MODEL_COMMIT_BATCH_SIZE) {
                sendPending();
            }
            return request.result();
        }

        @Override
        public synchronized void flush() {
            if (!completed) {
                completed = true;
                sendPending();
            }
        }

        @Override
        public synchronized void fail(Throwable failure) {
            if (!completed) {
                completed = true;
                pending.forEach(request -> request.fail(failure));
                pending = List.of();
            }
        }

        private void sendPending() {
            if (pending.isEmpty()) {
                return;
            }
            List<PreparedRequest<CommitModelsResult>> batch = pending;
            pending = new ArrayList<>(READY_MODEL_COMMIT_BATCH_SIZE);
            sendPreparedRequests(batch);
        }
    }

    private final class WebSocketModelCommitBatch
            implements ModelCommitBatch {
        private final Object[] requests;
        private final AtomicBoolean completed = new AtomicBoolean();

        private WebSocketModelCommitBatch(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "Model commit batch capacity must not be negative");
            }
            requests = new Object[capacity];
        }

        @Override
        public CompletableFuture<CommitModelsResult> add(
                int slot, CommitModels commit) {
            if (completed.get()) {
                return commitModels(commit);
            }
            if (slot < 0 || slot >= requests.length) {
                throw new IllegalArgumentException(
                        "Model commit batch slot %d is outside capacity %d"
                                .formatted(slot, requests.length));
            }
            PreparedRequest<CommitModelsResult> request =
                    prepareRequest(commit);
            requests[slot] = request;
            return request.result();
        }

        @Override
        public void flush() {
            List<PreparedRequest<CommitModelsResult>> batch = finish();
            if (batch != null) {
                sendPreparedRequests(batch);
            }
        }

        @Override
        public void fail(Throwable failure) {
            List<PreparedRequest<CommitModelsResult>> batch = finish();
            if (batch != null) {
                batch.forEach(request ->
                        request.fail(failure));
            }
        }

        @SuppressWarnings("unchecked")
        private List<PreparedRequest<CommitModelsResult>> finish() {
            if (!completed.compareAndSet(false, true)) {
                return null;
            }
            List<PreparedRequest<CommitModelsResult>> result =
                    new ArrayList<>(requests.length);
            for (Object candidate : requests) {
                if (candidate != null) {
                    result.add(
                            (PreparedRequest<CommitModelsResult>) candidate);
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    public Registration registerModelCommitResultProcessor(
            Function<List<CommitModelsResult>, CompletableFuture<Void>> processor) {
        modelCommitResultProcessors.add(processor);
        return () -> modelCommitResultProcessors.remove(processor);
    }

    @Override
    protected void restoreResultContext(
            RequestResult candidate, Request request) {
        if (!(candidate instanceof CommitModelsResult result)
                || result.getCommitId() != null
                || !(request instanceof CommitModels commit)
                || !result.hasSingleTargetResult()
                || commit.getSubsteps().size() != 1
                || commit.getSubsteps().getFirst().getTargets().size() != 1) {
            return;
        }
        result.restoreTransportIdentities(
                commit.getCommitId(),
                commit.getSubsteps().getFirst()
                        .getTargets().getFirst()
                        .getModelId());
    }

    @Override
    protected CompletableFuture<Void> prepareResults(
            List<io.fluxzero.common.api.RequestResult> results) {
        if (modelCommitResultProcessors.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CommitModelsResult> commits = commitResults(results);
        if (commits.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(
                modelCommitResultProcessors.stream()
                        .map(processor -> processor.apply(commits))
                        .toArray(CompletableFuture[]::new));
    }

    @SuppressWarnings("unchecked")
    private static List<CommitModelsResult> commitResults(
            List<io.fluxzero.common.api.RequestResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        boolean onlyCommits = true;
        for (int i = 0; i < results.size(); i++) {
            if (!(results.get(i)
                    instanceof CommitModelsResult)) {
                onlyCommits = false;
                break;
            }
        }
        if (onlyCommits) {
            return (List<CommitModelsResult>) (List<?>) results;
        }
        List<CommitModelsResult> commits =
                new ArrayList<>();
        for (io.fluxzero.common.api.RequestResult result :
                results) {
            if (result instanceof CommitModelsResult commit) {
                commits.add(commit);
            }
        }
        return commits;
    }

    @Override
    public GetModelEventsResult getModelEvents(GetModelEvents request) {
        return expandCompactResult(request, sendAndWait(request));
    }

    @Override
    public GetModelEventsResult getCompactModelEvents(GetModelEvents request) {
        GetModelEventsResult result = sendAndWait(request);
        return hasOnlyEmbeddedMemberships(result)
                ? result : expandCompactResult(request, result);
    }

    private static boolean hasOnlyEmbeddedMemberships(
            GetModelEventsResult result) {
        return (result.getPayloads() == null
                || result.getPayloads().isEmpty())
               && (result.getCompactPayloads() == null
                   || result.getCompactPayloads().length == 0)
               && (result.getCompactPayloadBlocks() == null
                   || result.getCompactPayloadBlocks().isEmpty())
               && result.getCompactMembershipBlocks() != null
               && !result.getCompactMembershipBlocks().isEmpty();
    }

    private static GetModelEventsResult expandCompactResult(
            GetModelEvents request, GetModelEventsResult result) {
        byte[] compactPayloads = result.getCompactPayloads();
        List<ModelEventPayloadBlock> compactBlocks =
                result.getCompactPayloadBlocks();
        List<ModelEventDataBlock> compactMembershipBlocks =
                result.getCompactMembershipBlocks();
        boolean hasPayloads =
                compactPayloads != null && compactPayloads.length > 0;
        boolean hasBlocks =
                compactBlocks != null && !compactBlocks.isEmpty();
        boolean hasMembershipBlocks =
                compactMembershipBlocks != null
                && !compactMembershipBlocks.isEmpty();
        if (!hasPayloads && !hasBlocks && !hasMembershipBlocks) {
            return result;
        }
        if (!hasPayloads && !hasBlocks && hasMembershipBlocks) {
            GetModelEventsResult embedded =
                    tryExpandEmbeddedMemberships(
                            request, result, compactMembershipBlocks);
            if (embedded != null) {
                return embedded;
            }
        }
        long[] stateIndices = result.getCompactPayloadStateIndices();
        if (stateIndices == null) {
            throw new IllegalStateException(
                    "Compact model-event payloads have no state-index mapping");
        }
        List<ModelEventPayload> expanded =
                new ArrayList<>(
                        result.getPayloads().size() + stateIndices.length);
        LongSet selectedStates =
                new LongSet(
                        result.getPayloads().size() + stateIndices.length);
        for (ModelEventPayload payload : result.getPayloads()) {
            if (!selectedStates.add(payload.getStateIndex())) {
                throw new IllegalStateException(
                        "Duplicate model-event payload at state index "
                        + payload.getStateIndex());
            }
            expanded.add(payload);
        }
        if (hasPayloads) {
            List<SerializedMessage> messages =
                    SerializedMessagePackCodec.decode(compactPayloads);
            if (messages.size() != stateIndices.length) {
                throw new IllegalStateException(
                        "Compact model-event response contains %d messages for %d state indices"
                                .formatted(messages.size(), stateIndices.length));
            }
            for (int i = 0; i < stateIndices.length; i++) {
                if (!selectedStates.add(stateIndices[i])) {
                    throw new IllegalStateException(
                            "Duplicate model-event payload at state index " + stateIndices[i]);
                }
                expanded.add(
                        new ModelEventPayload(
                                stateIndices[i], messages.get(i)));
            }
        }
        if (hasBlocks) {
            long[] eventIndices = result.getCompactPayloadEventIndices();
            if (eventIndices == null || eventIndices.length != stateIndices.length) {
                throw new IllegalStateException(
                        "Compact model-event blocks contain %d state indices and %d event indices"
                                .formatted(
                                        stateIndices.length,
                                        eventIndices == null ? 0 : eventIndices.length));
            }
            for (int i = 1; i < eventIndices.length; i++) {
                if (eventIndices[i] <= eventIndices[i - 1]) {
                    throw new IllegalStateException(
                            "Compact model-event indices are not strictly increasing");
                }
            }
            int selected = 0;
            List<DecodedPayloadBlock> decodedBlocks =
                    compactBlocks.size() < 8
                            ? compactBlocks.stream()
                                    .map(WebSocketEventStoreClient::decodePayloadBlock)
                                    .toList()
                            : compactBlocks.parallelStream()
                                    .map(WebSocketEventStoreClient::decodePayloadBlock)
                                    .toList();
            for (DecodedPayloadBlock decoded : decodedBlocks) {
                ModelEventPayloadBlock block = decoded.block();
                List<SerializedMessage> messages = decoded.messages();
                if (messages.size() != block.getMessageCount()) {
                    throw new IllegalStateException(
                            "Compact model-event block at %d contains %d messages instead of %d"
                                    .formatted(
                                            block.getFirstIndex(),
                                            messages.size(),
                                            block.getMessageCount()));
                }
                for (int ordinal = 0; ordinal < messages.size(); ordinal++) {
                    SerializedMessage message = messages.get(ordinal);
                    long eventIndex = message.getIndex() == null
                            ? block.getFirstIndex() + ordinal
                            : message.getIndex();
                    while (selected < eventIndices.length
                           && eventIndices[selected] < eventIndex) {
                        throw new IllegalStateException(
                                "Compact model-event blocks do not contain selected event "
                                + eventIndices[selected]);
                    }
                    if (selected < eventIndices.length
                        && eventIndices[selected] == eventIndex) {
                        long stateIndex = stateIndices[selected];
                        message.setIndex(eventIndex);
                        if (!selectedStates.add(stateIndex)) {
                            throw new IllegalStateException(
                                    "Duplicate model-event payload at state index " + stateIndex);
                        }
                        expanded.add(
                                new ModelEventPayload(
                                        stateIndex, message));
                        selected++;
                    }
                }
            }
            if (selected != eventIndices.length) {
                throw new IllegalStateException(
                        "Compact model-event blocks contain %d of %d selected events"
                                .formatted(selected, eventIndices.length));
            }
        }
        List<ModelEventStream> streams =
                expandCompactMemberships(
                        request, result, compactMembershipBlocks,
                        selectedStates);
        boolean ordered = true;
        for (int i = 1; i < expanded.size(); i++) {
            if (expanded.get(i - 1).getStateIndex()
                > expanded.get(i).getStateIndex()) {
                ordered = false;
                break;
            }
        }
        if (!ordered) {
            expanded.sort(
                    Comparator.comparingLong(
                            ModelEventPayload::getStateIndex));
        }
        GetModelEventsResult expandedResult =
                new GetModelEventsResult(
                result.getRequestId(),
                result.getStateIndex(),
                List.copyOf(expanded),
                streams);
        expandedResult.setRequestReceivedTimestamp(
                result.getRequestReceivedTimestamp());
        expandedResult.setResponseQueuedTimestamp(
                result.getResponseQueuedTimestamp());
        expandedResult.setResponseSendStartTimestamp(
                result.getResponseSendStartTimestamp());
        return expandedResult;
    }

    private static GetModelEventsResult tryExpandEmbeddedMemberships(
            GetModelEvents request,
            GetModelEventsResult result,
            List<ModelEventDataBlock> compactBlocks) {
        List<ModelStreamBatchDecoder.DecodedBlock> decodedBlocks =
                compactBlocks.size() < 8
                        ? compactBlocks.stream()
                                .map(ModelStreamBatchDecoder::decodeBlock)
                                .toList()
                        : compactBlocks.parallelStream()
                                .map(ModelStreamBatchDecoder::decodeBlock)
                                .toList();
        if (decodedBlocks.stream()
                .anyMatch(block -> block.embeddedPayloads() == null)) {
            return null;
        }
        Map<String, ModelEventStreamRequest> requestsByModel =
                new HashMap<>();
        request.getRequests().forEach(
                stream -> requestsByModel.put(stream.getModelId(), stream));
        Map<String, List<ModelEventMembership>> memberships =
                new HashMap<>();
        request.getRequests().forEach(
                stream -> memberships.put(stream.getModelId(), new ArrayList<>()));
        Map<Long, SerializedMessage> payloads =
                new LinkedHashMap<>();
        long lastStateIndex = -1L;
        for (ModelStreamBatchDecoder.DecodedBlock block : decodedBlocks) {
            List<ModelStreamBatchDecoder.Entry> entries =
                    block.entries();
            List<SerializedMessage> events =
                    SerializedMessagePackCodec.decode(
                            block.embeddedPayloads().data(),
                            block.embeddedPayloads().offset(),
                            block.embeddedPayloads().length());
            if (events.size() != entries.size()) {
                throw new IllegalStateException(
                        "Embedded model stream block contains %d events for %d memberships"
                                .formatted(events.size(), entries.size()));
            }
            for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
                ModelStreamBatchDecoder.Entry entry =
                        entries.get(ordinal);
                ModelEventStreamRequest stream =
                        requestsByModel.get(entry.modelId());
                if (stream == null
                    || stream.getMaxSize() <= 0
                    || entry.sequenceNumber()
                       <= stream.getLastSequenceNumber()
                    || entry.stateIndex()
                       > result.getStateIndex()) {
                    continue;
                }
                List<ModelEventMembership> selected =
                        memberships.get(entry.modelId());
                if (selected.size() >= stream.getMaxSize()) {
                    continue;
                }
                SerializedMessage event = events.get(ordinal);
                if (event.getIndex() != null
                    && event.getIndex() != entry.eventIndex()) {
                    throw new IllegalStateException(
                            "Embedded event index %d does not match model membership %d"
                                    .formatted(
                                            event.getIndex(),
                                            entry.eventIndex()));
                }
                event.setIndex(entry.eventIndex());
                SerializedMessage previous =
                        payloads.putIfAbsent(
                                entry.stateIndex(), event);
                if (previous != null && previous != event) {
                    throw new IllegalStateException(
                            "Duplicate embedded model payload at state index "
                            + entry.stateIndex());
                }
                if (previous == null) {
                    if (entry.stateIndex() <= lastStateIndex) {
                        throw new IllegalStateException(
                                "Embedded model payloads are not ordered by state index");
                    }
                    lastStateIndex = entry.stateIndex();
                }
                selected.add(
                        new ModelEventMembership(
                                entry.sequenceNumber(),
                                entry.stateIndex(),
                                entry.readStateIndex(),
                                entry.commitId(),
                                entry.substep()));
            }
        }
        List<ModelEventStream> streams =
                new ArrayList<>(result.getStreams().size());
        for (int ordinal = 0;
             ordinal < result.getStreams().size();
             ordinal++) {
            ModelEventStream existing =
                    result.getStreams().get(ordinal);
            ModelEventStreamRequest requested =
                    request.getRequests().get(ordinal);
            if (!existing.getModelId().equals(
                    requested.getModelId())) {
                throw new IllegalStateException(
                        "Embedded model response order does not match its request");
            }
            List<ModelEventMembership> selected =
                    memberships.get(existing.getModelId());
            streams.add(
                    new ModelEventStream(
                            existing.getModelId(),
                            existing.getHead(),
                            List.copyOf(selected)));
        }
        GetModelEventsResult expanded =
                new GetModelEventsResult(
                        result.getRequestId(),
                        result.getStateIndex(),
                        payloads.entrySet().stream()
                                .map(entry ->
                                             new ModelEventPayload(
                                                     entry.getKey(),
                                                     entry.getValue()))
                                .toList(),
                        List.copyOf(streams));
        expanded.setRequestReceivedTimestamp(
                result.getRequestReceivedTimestamp());
        expanded.setResponseQueuedTimestamp(
                result.getResponseQueuedTimestamp());
        expanded.setResponseSendStartTimestamp(
                result.getResponseSendStartTimestamp());
        return expanded;
    }

    private static List<ModelEventStream> expandCompactMemberships(
            GetModelEvents request,
            GetModelEventsResult result,
            List<ModelEventDataBlock> compactBlocks,
            LongSet selectedStateIndices) {
        if (compactBlocks == null || compactBlocks.isEmpty()) {
            return result.getStreams();
        }
        Map<String, ModelEventStreamRequest> requestsByModel =
                new HashMap<>();
        request.getRequests().forEach(
                stream -> requestsByModel.put(stream.getModelId(), stream));
        Map<String, List<ModelEventMembership>> memberships =
                new HashMap<>();
        for (ModelEventStream stream : result.getStreams()) {
            memberships.put(
                    stream.getModelId(),
                    new ArrayList<>(stream.getMemberships()));
        }
        List<List<ModelStreamBatchDecoder.Entry>> decodedBlocks =
                compactBlocks.size() < 8
                        ? compactBlocks.stream()
                                .map(ModelStreamBatchDecoder::decode)
                                .toList()
                        : compactBlocks.parallelStream()
                                .map(ModelStreamBatchDecoder::decode)
                                .toList();
        for (List<ModelStreamBatchDecoder.Entry> block : decodedBlocks) {
            for (ModelStreamBatchDecoder.Entry entry : block) {
                ModelEventStreamRequest stream =
                        requestsByModel.get(entry.modelId());
                if (stream == null
                    || stream.getMaxSize() <= 0
                    || entry.sequenceNumber()
                       <= stream.getLastSequenceNumber()
                    || entry.stateIndex() > result.getStateIndex()
                    || !selectedStateIndices.contains(
                            entry.stateIndex())) {
                    continue;
                }
                memberships.get(entry.modelId()).add(
                        new ModelEventMembership(
                                entry.sequenceNumber(),
                                entry.stateIndex(),
                                entry.readStateIndex(),
                                entry.commitId(),
                                entry.substep()));
            }
        }
        List<ModelEventStream> expanded =
                new ArrayList<>(result.getStreams().size());
        for (int ordinal = 0; ordinal < result.getStreams().size(); ordinal++) {
            ModelEventStream existing = result.getStreams().get(ordinal);
            ModelEventStreamRequest requested =
                    request.getRequests().get(ordinal);
            List<ModelEventMembership> selected =
                    memberships.get(existing.getModelId());
            selected.sort(
                    Comparator.comparingLong(
                                    ModelEventMembership::getSequenceNumber)
                            .thenComparingLong(
                                    ModelEventMembership::getStateIndex));
            if (selected.size() > requested.getMaxSize()) {
                selected = new ArrayList<>(
                        selected.subList(0, requested.getMaxSize()));
            }
            expanded.add(
                    new ModelEventStream(
                            existing.getModelId(),
                            existing.getHead(),
                            List.copyOf(selected)));
        }
        return List.copyOf(expanded);
    }

    private static DecodedPayloadBlock decodePayloadBlock(
            ModelEventPayloadBlock block) {
        byte[] data = block.isCompressed()
                ? CompressionAlgorithm.ZSTD.decompress(block.getData())
                : block.getData();
        return new DecodedPayloadBlock(
                block, SerializedMessagePackCodec.decode(data));
    }

    private record DecodedPayloadBlock(
            ModelEventPayloadBlock block,
            List<SerializedMessage> messages) {
    }

    private static final class LongSet {
        private static final long EMPTY = Long.MIN_VALUE;

        private final long[] values;
        private final int mask;

        private LongSet(int expectedSize) {
            int capacity = 1;
            int required = Math.max(2, (int) Math.ceil(expectedSize / 0.6d));
            while (capacity < required) {
                capacity = Math.multiplyExact(capacity, 2);
            }
            values = new long[capacity];
            java.util.Arrays.fill(values, EMPTY);
            mask = capacity - 1;
        }

        private boolean add(long value) {
            if (value == EMPTY) {
                throw new IllegalArgumentException(
                        "Long.MIN_VALUE is not a valid model state index");
            }
            int slot = mix(value) & mask;
            while (true) {
                long present = values[slot];
                if (present == EMPTY) {
                    values[slot] = value;
                    return true;
                }
                if (present == value) {
                    return false;
                }
                slot = slot + 1 & mask;
            }
        }

        private boolean contains(long value) {
            int slot = mix(value) & mask;
            while (true) {
                long present = values[slot];
                if (present == EMPTY) {
                    return false;
                }
                if (present == value) {
                    return true;
                }
                slot = slot + 1 & mask;
            }
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            return (int) (value ^ value >>> 32);
        }
    }

    @Override
    public CompletableFuture<TrackModelUpdatesResult> trackModelUpdates(
            TrackModelUpdates request) {
        return send(request);
    }

    @Override
    public GetModelGraphResult getModelGraph(GetModelGraph request) {
        return sendAndWait(request);
    }

    @Override
    public GetModelGraphResult getModelGraphBefore(
            GetModelGraphBefore request) {
        return sendAndWait(request);
    }

    @Override
    public GetModelGraphResult getModelAncestors(GetModelAncestors request) {
        return sendAndWait(request);
    }

    @Override
    public GetModelChangeResult getModelChange(GetModelChange request) {
        return sendAndWait(request);
    }

    @Override
    public CompletableFuture<ModelGraphProjectionStatus>
            registerModelGraphProjection(
                    RegisterModelGraphProjection request) {
        return send(request);
    }

    @Override
    public ModelGraphProjectionStatus
            getModelGraphProjectionStatus(
                    GetModelGraphProjectionStatus request) {
        return sendAndWait(request);
    }

    @Override
    public CompletableFuture<ModelGraphProjectionStatus>
            awaitModelGraphProjection(
                    AwaitModelGraphProjection request) {
        return send(request);
    }

    @Override
    public ModelDeletionPlan planModelDeletion(
            PlanModelDeletion request) {
        return sendAndWait(request);
    }

    @Override
    public CompletableFuture<ModelDeletionResult> deleteModel(
            DeleteModel request) {
        return send(request);
    }

    /**
     * Retrieves events for a specific aggregate starting after a given sequence number, optionally limiting the result
     * size.
     */
    @Override
    public AggregateEventStream<SerializedMessage> getEvents(String aggregateId, long lastSequenceNumber, int maxSize) {
        return getEvents(aggregateId, lastSequenceNumber, maxSize, fetchBatchSize, this::sendAndWait);
    }

    static AggregateEventStream<SerializedMessage> getEvents(
            String aggregateId, long lastSequenceNumber, int maxSize, int fetchBatchSize,
            Function<GetEvents, GetEventsResult> fetchEvents) {
        AtomicReference<Long> highestSequenceNumber = new AtomicReference<>();
        GetEventsResult firstBatch = fetchEvents.apply(new GetEvents(
                aggregateId, lastSequenceNumber, maxSize <= 0 ? fetchBatchSize : maxSize));
        Stream<SerializedMessage> eventStream = iterate(
                firstBatch,
                r -> fetchEvents.apply(new GetEvents(aggregateId, r.getLastSequenceNumber(), fetchBatchSize)),
                r -> maxSize > 0 || r.getEventBatch().getSize() < fetchBatchSize)
                .flatMap(r -> {
                    if (!r.getEventBatch().isEmpty()) {
                        highestSequenceNumber.set(r.getLastSequenceNumber());
                    }
                    return r.getEventBatch().getEvents().stream();
                });
        return new AggregateEventStream<>(eventStream, aggregateId, highestSequenceNumber::get);
    }

    /**
     * Sends a request to update the relationships of an entity or aggregate.
     */
    @Override
    public CompletableFuture<Void> updateRelationships(UpdateRelationships request) {
        return sendCommand(request);
    }

    /**
     * Sends a request to repair relationships for a specific entity.
     */
    @Override
    public CompletableFuture<Void> repairRelationships(RepairRelationships request) {
        return sendCommand(request);
    }

    /**
     * Retrieves a map of aggregate IDs associated with a given entity, using a {@link GetAggregateIds} request.
     */
    @Override
    public Map<String, String> getAggregateIds(GetAggregateIds request) {
        return this.<GetAggregateIdsResult>sendAndWait(request).getAggregateIds();
    }

    /**
     * Retrieves all relationships for a given entity, using a {@link GetRelationships} request.
     */
    @Override
    public List<Relationship> getRelationships(GetRelationships request) {
        return this.<GetRelationshipsResult>sendAndWait(request).getRelationships();
    }

    /**
     * Sends a delete command for the event stream of the specified aggregate.
     */
    @Override
    public CompletableFuture<Void> deleteEvents(String aggregateId, Guarantee guarantee) {
        return sendCommand(new DeleteEvents(aggregateId, guarantee));
    }

}
