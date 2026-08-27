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
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelCommitWireCodec;
import io.fluxzero.common.api.modeling.ModelEventPageDecoder;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
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
import io.fluxzero.common.jfr.FluxzeroJfr;
import io.fluxzero.common.websocket.WebSocketPayloadCodec;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
        implements EventStoreClient, ModelCommitBatchingClient {

    private static final int READY_MODEL_COMMIT_BATCH_SIZE = Math.max(
            1, Integer.getInteger("fluxzero.readyModelCommitBatchSize", 256));

    private final int fetchBatchSize;
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
        if (results.isEmpty()) {
            return "RESULT";
        }
        if (results.stream().allMatch(result -> result != null
                && (result.getClass() == CommitModelsResult.class
                || ModelCommitWireCodec.requiresRequestContext(result)))) {
            return "MODEL_COMMIT";
        }
        return results.stream().allMatch(TrackModelUpdatesResult.class::isInstance)
                ? "MODEL_UPDATE" : "RESULT";
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
            return add(commit, null);
        }

        @Override
        public synchronized CompletableFuture<CommitModelsResult> add(
                int slot,
                CommitModels commit,
                ModelCommitCompletion completion) {
            return add(commit, completion);
        }

        private CompletableFuture<CommitModelsResult> add(
                CommitModels commit,
                ModelCommitCompletion completion) {
            if (completed) {
                return completion == null
                        ? commitModels(commit)
                        : send(commit, completion);
            }
            PreparedRequest<CommitModelsResult> request =
                    prepareRequest(commit, completion);
            pending.add(request);
            if (pending.size() == READY_MODEL_COMMIT_BATCH_SIZE) {
                sendPending();
            }
            return request.result();
        }

        @Override
        public void skip(int slot) {
            // Ready batches do not reserve slots.
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
        private static final Object SKIPPED = new Object();
        private final AtomicReferenceArray<Object> requests;
        private final AtomicInteger remaining;
        private final AtomicBoolean completed = new AtomicBoolean();

        private WebSocketModelCommitBatch(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "Model commit batch capacity must not be negative");
            }
            requests = new AtomicReferenceArray<>(capacity);
            remaining = new AtomicInteger(capacity);
        }

        @Override
        public CompletableFuture<CommitModelsResult> add(
                int slot, CommitModels commit) {
            return add(slot, commit, null);
        }

        @Override
        public CompletableFuture<CommitModelsResult> add(
                int slot,
                CommitModels commit,
                ModelCommitCompletion completion) {
            validateSlot(slot);
            if (completed.get()) {
                return completion == null
                        ? commitModels(commit)
                        : send(commit, completion);
            }
            PreparedRequest<CommitModelsResult> request =
                    prepareRequest(commit, completion);
            if (!requests.compareAndSet(slot, null, request)) {
                if (completed.get()) {
                    return completion == null
                            ? commitModels(commit)
                            : send(commit, completion);
                }
                throw new IllegalStateException(
                        "Model commit batch slot %d is already settled"
                                .formatted(slot));
            }
            slotSettled();
            return request.result();
        }

        @Override
        public void skip(int slot) {
            validateSlot(slot);
            if (!completed.get()
                && requests.compareAndSet(slot, null, SKIPPED)) {
                slotSettled();
            }
        }

        private void validateSlot(int slot) {
            if (slot < 0 || slot >= requests.length()) {
                throw new IllegalArgumentException(
                        "Model commit batch slot %d is outside capacity %d"
                                .formatted(slot, requests.length()));
            }
        }

        private void slotSettled() {
            if (remaining.decrementAndGet() == 0) {
                flush();
            }
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
                    new ArrayList<>(requests.length());
            for (int slot = 0; slot < requests.length(); slot++) {
                Object candidate = requests.getAndSet(slot, SKIPPED);
                if (candidate != null && candidate != SKIPPED) {
                    result.add(
                            (PreparedRequest<CommitModelsResult>) candidate);
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    protected CompletableFuture<Void> prepareResults(
            List<RequestResult> results,
            List<Object> requestContexts) {
        Map<ModelCommitResultProcessor, ModelCommitResultGroup> groups =
                new IdentityHashMap<>();
        for (int index = 0; index < results.size(); index++) {
            if (results.get(index) instanceof CommitModelsResult result
                && requestContexts.get(index)
                instanceof ModelCommitCompletion completion) {
                groups.computeIfAbsent(
                                completion.processor(),
                                ignored -> new ModelCommitResultGroup())
                        .add(result, completion.value());
            }
        }
        if (groups.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(groups.entrySet().stream()
                .map(entry -> Objects.requireNonNull(
                        entry.getKey().process(
                                entry.getValue().results,
                                entry.getValue().contexts),
                        "Model commit result processor returned null"))
                .toArray(CompletableFuture[]::new));
    }

    private static final class ModelCommitResultGroup {
        private final List<CommitModelsResult> results = new ArrayList<>();
        private final List<Object> contexts = new ArrayList<>();

        private void add(CommitModelsResult result, Object context) {
            results.add(result);
            contexts.add(context);
        }
    }

    @Override
    protected List<RequestResult> restoreResultContext(
            List<RequestResult> results) {
        List<RequestResult> restored = null;
        for (int index = 0; index < results.size(); index++) {
            RequestResult candidate = results.get(index);
            if (!ModelCommitWireCodec.requiresRequestContext(candidate)) {
                continue;
            }
            Request outstanding = outstandingRequest(candidate.getRequestId());
            RequestResult value = ModelCommitWireCodec.restoreResultContext(
                    candidate,
                    outstanding instanceof CommitModels commit ? commit : null);
            if (value != candidate) {
                if (restored == null) {
                    restored = new ArrayList<>(results);
                }
                restored.set(index, value);
            }
        }
        return restored == null ? results : restored;
    }

    @Override
    public GetModelEventsResult getModelEvents(GetModelEvents request) {
        return ModelEventPageDecoder.expand(request, sendAndWait(request));
    }

    @Override
    public CompletableFuture<TrackModelUpdatesResult> trackModelUpdates(
            TrackModelUpdates request) {
        return send(request);
    }

    @Override
    public GetModelGraphResult getModelGraph(GetModelGraph request) {
        return expandGraphResult(request, sendAndWait(request));
    }

    private static GetModelGraphResult expandGraphResult(
            GetModelGraph request, GetModelGraphResult result) {
        return expandGraphResult(
                request.getMaxEventsPerModel(), request.getMaxBytes(), result);
    }

    private static GetModelGraphResult expandGraphResult(
            int maxEventsPerModel, long maxBytes, GetModelGraphResult result) {
        GetModelEventsResult events = result.getEvents();
        GetModelEvents pageRequest = new GetModelEvents(
                events.getStreams().stream()
                        .map(stream -> new ModelEventStreamRequest(
                                stream.getModelId(), -1L, maxEventsPerModel))
                        .toList(),
                ModelReadBoundary.state(events.getStateIndex(), false), maxBytes);
        GetModelEventsResult expanded = ModelEventPageDecoder.expand(pageRequest, events);
        if (expanded == events) {
            return result;
        }
        GetModelGraphResult expandedResult = new GetModelGraphResult(
                result.getRequestId(), result.getEdges(), expanded);
        expandedResult.setRequestReceivedTimestamp(
                result.getRequestReceivedTimestamp());
        expandedResult.setResponseQueuedTimestamp(
                result.getResponseQueuedTimestamp());
        expandedResult.setResponseSendStartTimestamp(
                result.getResponseSendStartTimestamp());
        return expandedResult;
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
