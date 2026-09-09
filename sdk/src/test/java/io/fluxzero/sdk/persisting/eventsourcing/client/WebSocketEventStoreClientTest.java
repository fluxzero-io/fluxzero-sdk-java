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

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.eventsourcing.EventBatch;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.common.api.eventsourcing.GetEventsResult;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketEventStoreClientTest {

    @Test
    void explicitMaxSizeIsAppliedAcrossCountBoundedPages() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(8, 1);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, 5, 2, 0L, recordingStore(history, requests, false));

        assertEquals(5, result.count());
        assertEquals(Optional.of(4L), result.getLastSequenceNumber());
        assertEquals(List.of(2, 2, 1), requests.stream().map(GetEvents::getBatchSize).toList());
    }

    @Test
    void byteBoundedShortPagesContinueUntilTheRuntimeReturnsEmpty() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(3, 3);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 4, 5L, recordingStore(history, requests, true));

        assertEquals(3, result.count());
        assertEquals(Optional.of(2L), result.getLastSequenceNumber());
        assertEquals(List.of(-1L, 0L, 1L, 2L),
                     requests.stream().map(GetEvents::getLastSequenceNumber).toList());
        assertTrue(requests.stream().allMatch(request -> request.getMaxBytes() == 5L));
    }

    @Test
    void byteBoundedClientRemainsCompatibleWithCountOnlyRuntime() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(3, 1);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 2, 5L, recordingStore(history, requests, false));

        assertEquals(3, result.count());
        assertEquals(3, requests.size(), "An old Runtime needs one final empty request when byte paging is active");
        assertEquals(List.of(-1L, 1L, 2L), requests.stream().map(GetEvents::getLastSequenceNumber).toList());
    }

    @Test
    void countOnlyClientRetainsLegacyShortPageTermination() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(3, 1);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 2, 0L, recordingStore(history, requests, false));

        assertEquals(3, result.count());
        assertEquals(2, requests.size());
    }

    @Test
    void individuallyOversizedEventDoesNotStopPagination() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = List.of(event(8), event(2));

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 4, 5L, recordingStore(history, requests, true));

        assertEquals(history, result.toList());
        assertEquals(Optional.of(1L), result.getLastSequenceNumber());
        assertEquals(3, requests.size());
    }

    @Test
    void rejectsNonAdvancingNonEmptyPage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                                                    () -> WebSocketEventStoreClient.getEvents(
                                                            "aggregate", 5L, -1, 2, 10L,
                                                            request -> new GetEventsResult(
                                                                    request.getRequestId(), new EventBatch(
                                                                    "aggregate", List.of(event(1)), false), 5L)));
        assertTrue(error.getMessage().contains("did not advance"));
    }

    @Test
    void rejectsPageThatExceedsRequestedCount() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                                                    () -> WebSocketEventStoreClient.getEvents(
                                                            "aggregate", -1L, 1, 2, 0L,
                                                            request -> new GetEventsResult(
                                                                    request.getRequestId(), new EventBatch(
                                                                    "aggregate", events(2, 1), false), 1L)));
        assertTrue(error.getMessage().contains("at most 1"));
    }

    private static Function<GetEvents, GetEventsResult> recordingStore(
            List<SerializedMessage> history, List<GetEvents> requests, boolean enforceBytes) {
        return request -> {
            requests.add(request);
            int from = Math.min(history.size(), Math.toIntExact(request.getLastSequenceNumber() + 1L));
            int to = Math.min(history.size(), from + request.getBatchSize());
            List<SerializedMessage> page = new ArrayList<>();
            long bytes = 0L;
            for (SerializedMessage event : history.subList(from, to)) {
                if (enforceBytes && request.getMaxBytes() > 0 && !page.isEmpty()
                    && bytes + event.getBytes() > request.getMaxBytes()) {
                    break;
                }
                page.add(event);
                bytes += event.getBytes();
            }
            long lastSequenceNumber = page.isEmpty() ? -1L : from + page.size() - 1L;
            return new GetEventsResult(request.getRequestId(),
                                       new EventBatch(request.getAggregateId(), page, false), lastSequenceNumber);
        };
    }

    private static List<SerializedMessage> events(int count, int bytes) {
        List<SerializedMessage> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(event(bytes));
        }
        return result;
    }

    private static SerializedMessage event(int bytes) {
        return new SerializedMessage(new Data<>(new byte[bytes], "event", 0), Metadata.empty(), null, 0L);
    }
}
