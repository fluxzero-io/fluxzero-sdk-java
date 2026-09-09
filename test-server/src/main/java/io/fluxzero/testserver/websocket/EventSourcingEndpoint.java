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
 *
 */

package io.fluxzero.testserver.websocket;

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
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.fluxzero.common.ObjectUtils.limitByCumulativeWeight;

@Slf4j
@AllArgsConstructor
public class EventSourcingEndpoint extends WebsocketEndpoint {

    private final EventStoreClient eventStore;

    public EventSourcingEndpoint(EventStoreClient eventStore, CommandIdempotencyStore commandIdempotencyStore) {
        super(commandIdempotencyStore);
        this.eventStore = eventStore;
    }

    @Handle
    CompletableFuture<Void> handle(AppendEvents appendEvents) {
        return CompletableFuture.allOf(appendEvents.getEventBatches().stream().map(b -> eventStore
                .storeEvents(b.getAggregateId(), b.getEvents(), b.isStoreOnly(), appendEvents.getGuarantee()))
                                               .toArray(CompletableFuture[]::new));
    }

    @Handle
    CompletableFuture<Void> handle(DeleteEvents deleteEvents) {
        return eventStore.deleteEvents(deleteEvents.getAggregateId(), deleteEvents.getGuarantee());
    }

    @Handle
    GetEventsResult handle(GetEvents getEvents) {
        if (getEvents.getMaxBytes() < 0L) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        AggregateEventStream<SerializedMessage> stream = eventStore
                .getEvents(getEvents.getAggregateId(), getEvents.getLastSequenceNumber(), getEvents.getBatchSize());
        var completePage = stream.collect(Collectors.toList());
        var page = limitByCumulativeWeight(completePage, getEvents.getMaxBytes(), SerializedMessage::getBytes);
        long lastSequenceNumber = lastSequenceNumber(getEvents, stream, completePage, page);
        return new GetEventsResult(getEvents.getRequestId(), new EventBatch(
                getEvents.getAggregateId(), page, false), lastSequenceNumber);
    }

    private long lastSequenceNumber(GetEvents request, AggregateEventStream<SerializedMessage> completeStream,
                                    List<SerializedMessage> completePage, List<SerializedMessage> page) {
        if (page.isEmpty()) {
            return -1L;
        }
        if (page.size() == completePage.size()) {
            return completeStream.getLastSequenceNumber().orElse(-1L);
        }
        AggregateEventStream<SerializedMessage> prefixStream = eventStore.getEvents(
                request.getAggregateId(), request.getLastSequenceNumber(), page.size());
        List<SerializedMessage> exactPrefix = prefixStream.toList();
        if (!exactPrefix.equals(page)) {
            throw new IllegalStateException("Event store returned an inconsistent aggregate-history prefix");
        }
        return prefixStream.getLastSequenceNumber().orElseThrow(
                () -> new IllegalStateException("Event store did not report the sequence number of a non-empty page"));
    }

    @Handle
    CompletableFuture<Void> handle(UpdateRelationships request) {
        return eventStore.updateRelationships(request);
    }

    @Handle
    CompletableFuture<Void> handle(RepairRelationships request) {
        return eventStore.repairRelationships(request);
    }

    @Handle
    GetAggregateIdsResult handle(GetAggregateIds request) {
        return new GetAggregateIdsResult(request.getRequestId(), eventStore.getAggregateIds(request));
    }

    @Handle
    GetRelationshipsResult handle(GetRelationships request) {
        return new GetRelationshipsResult(request.getRequestId(), eventStore.getRelationships(request));
    }
}
