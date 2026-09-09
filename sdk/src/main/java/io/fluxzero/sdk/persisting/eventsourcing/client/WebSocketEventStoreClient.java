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
import io.fluxzero.common.api.eventsourcing.AppendEvents;
import io.fluxzero.common.api.eventsourcing.DeleteEvents;
import io.fluxzero.common.api.eventsourcing.EventBatch;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.common.api.eventsourcing.GetEventsResult;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetAggregateIdsResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.GetRelationshipsResult;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
 * <p>The {@code fetchBatchSize} setting controls how many events are fetched per paginated request when loading an
 * aggregate's event history. The aggregate-history byte setting in {@link WebSocketClient.ClientConfig} independently
 * bounds the cumulative serialized event-payload bytes requested per page. An individually oversized first event is
 * still returned so paging can advance.
 *
 * <p>End users rarely interact with this client directly. Instead, they typically use higher-level abstractions
 * such as {@link io.fluxzero.sdk.persisting.eventsourcing.EventStore} or
 * {@link io.fluxzero.sdk.persisting.repository.AggregateRepository}.
 *
 * @see EventStoreClient
 * @see io.fluxzero.sdk.persisting.eventsourcing.EventStore
 * @see io.fluxzero.sdk.persisting.repository.AggregateRepository
 */
public class WebSocketEventStoreClient extends AbstractWebsocketClient implements EventStoreClient {

    private final int fetchBatchSize;
    private final long maxFetchBytes;

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
        this.maxFetchBytes = client.getClientConfig().getAggregateHistoryMaxFetchBytes();
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
     * Retrieves events for a specific aggregate starting after a given sequence number, optionally limiting the result
     * size.
     */
    @Override
    public AggregateEventStream<SerializedMessage> getEvents(String aggregateId, long lastSequenceNumber, int maxSize) {
        return getEvents(aggregateId, lastSequenceNumber, maxSize, fetchBatchSize, maxFetchBytes, this::sendAndWait);
    }

    static AggregateEventStream<SerializedMessage> getEvents(
            String aggregateId, long lastSequenceNumber, int maxSize, int fetchBatchSize,
            long maxFetchBytes,
            Function<GetEvents, GetEventsResult> fetchEvents) {
        AtomicReference<Long> highestSequenceNumber = new AtomicReference<>();
        int pageSize = Math.max(1, fetchBatchSize);
        int requestedTotal = maxSize > 0 ? maxSize : Integer.MAX_VALUE;
        EventPage firstPage = fetchPage(aggregateId, lastSequenceNumber, requestedTotal, pageSize, maxFetchBytes,
                                        fetchEvents);
        Stream<SerializedMessage> eventStream = iterate(
                firstPage,
                page -> fetchPage(aggregateId, page.result().getLastSequenceNumber(), page.remaining(), pageSize,
                                  maxFetchBytes, fetchEvents),
                EventPage::terminal)
                .flatMap(page -> {
                    GetEventsResult result = page.result();
                    if (!result.getEventBatch().isEmpty()) {
                        highestSequenceNumber.set(result.getLastSequenceNumber());
                    }
                    return result.getEventBatch().getEvents().stream();
                });
        return new AggregateEventStream<>(eventStream, aggregateId, highestSequenceNumber::get);
    }

    private static EventPage fetchPage(
            String aggregateId, long lastSequenceNumber, int remaining, int pageSize, long maxFetchBytes,
            Function<GetEvents, GetEventsResult> fetchEvents) {
        int requestedSize = Math.min(pageSize, remaining);
        GetEventsResult result = fetchEvents.apply(
                new GetEvents(aggregateId, lastSequenceNumber, requestedSize, maxFetchBytes));
        int resultSize = result.getEventBatch().getSize();
        if (resultSize > requestedSize) {
            throw new IllegalStateException(
                    "Runtime returned %d aggregate events while at most %d were requested"
                            .formatted(resultSize, requestedSize));
        }
        if (resultSize > 0 && result.getLastSequenceNumber() <= lastSequenceNumber) {
            throw new IllegalStateException(
                    "Aggregate event pagination did not advance beyond sequence %d"
                            .formatted(lastSequenceNumber));
        }
        int nextRemaining = remaining - resultSize;
        boolean terminal = resultSize == 0 || nextRemaining == 0
                           || (maxFetchBytes <= 0 && resultSize < requestedSize);
        return new EventPage(result, nextRemaining, terminal);
    }

    private record EventPage(GetEventsResult result, int remaining, boolean terminal) {
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
