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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketEventStoreClientTest {

    @Test
    void explicitMaxSizeStopsAfterPartialFirstBatch() {
        String aggregateId = "aggregate";
        int maxSize = 30_000;
        int resultSize = 20_785;
        long lastSequenceNumber = 21_061L;
        List<GetEvents> requests = new ArrayList<>();
        SerializedMessage event = new SerializedMessage(
                new Data<>(new byte[0], "event", 0), Metadata.empty(), "event-id", 0L);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                aggregateId, -1L, maxSize, 8192, request -> {
                    requests.add(request);
                    return new GetEventsResult(
                            request.getRequestId(),
                            new EventBatch(aggregateId, Collections.nCopies(resultSize, event), false),
                            lastSequenceNumber);
                });

        assertEquals(resultSize, result.count());
        assertEquals(Optional.of(lastSequenceNumber), result.getLastSequenceNumber());
        assertEquals(1, requests.size());
        assertEquals(aggregateId, requests.getFirst().getAggregateId());
        assertEquals(-1L, requests.getFirst().getLastSequenceNumber());
        assertEquals(maxSize, requests.getFirst().getBatchSize());
    }
}
