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

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventSourcingEndpointTest {

    @Test
    void byteTruncatedPageRetainsExactSequenceNumberWhenSequencesHaveGaps() {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        SerializedMessage first = event(4);
        SerializedMessage second = event(4);
        when(eventStore.getEvents("aggregate", 2L, 10))
                .thenReturn(stream(List.of(first, second), 9L));
        when(eventStore.getEvents("aggregate", 2L, 1))
                .thenReturn(stream(List.of(first), 5L));
        EventSourcingEndpoint subject = new EventSourcingEndpoint(
                eventStore, mock(CommandIdempotencyStore.class));

        var result = subject.handle(new GetEvents("aggregate", 2L, 10, 5L));

        assertEquals(List.of(first), result.getEventBatch().getEvents());
        assertEquals(5L, result.getLastSequenceNumber());
        verify(eventStore).getEvents("aggregate", 2L, 1);
    }

    private static AggregateEventStream<SerializedMessage> stream(
            List<SerializedMessage> events, long lastSequenceNumber) {
        return new AggregateEventStream<>(events.stream(), "aggregate", () -> lastSequenceNumber);
    }

    private static SerializedMessage event(int bytes) {
        return new SerializedMessage(new Data<>(new byte[bytes], "event", 0), Metadata.empty(), null, 0L);
    }
}
