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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelEventBatchLoaderTest {

    @Test
    void chunksModelIdsAndPinsEveryLaterChunkToTheFirstResponse() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelEventBatchLoader loader = new ModelEventBatchLoader(
                client, new ModelEventBatchLoader.Settings(2, 8, 4, 1_024L));
        List<GetModelEventsResult> pages = new ArrayList<>();

        long stateIndex = loader.load(List.of("a", "b", "c"), null, pages::add);

        assertEquals(42L, stateIndex);
        assertEquals(2, requests.size());
        assertNull(requests.getFirst().getMaxStateIndex());
        assertEquals(42L, requests.getLast().getMaxStateIndex());
        assertEquals(List.of("a", "b"), requests.getFirst().getRequests().stream()
                .map(request -> request.getModelId()).toList());
        assertEquals(List.of("c"), requests.getLast().getRequests().stream()
                .map(request -> request.getModelId()).toList());
        assertEquals(1_024L, requests.getFirst().getMaxBytes());
        assertEquals(2, pages.size());
    }

    @Test
    void eventBoundaryIsResolvedOnlyByTheFirstChunk() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelEventBatchLoader loader = new ModelEventBatchLoader(
                client,
                new ModelEventBatchLoader.Settings(
                        2, 8, 4, 1_024L));
        LinkedHashMap<String, Long> cursors =
                new LinkedHashMap<>();
        cursors.put("a", -1L);
        cursors.put("b", -1L);
        cursors.put("c", -1L);

        var result = loader.load(
                cursors, null,
                "action-991", 3, ignored -> {
                });

        assertEquals(42L, result.stateIndex());
        assertEquals(
                "action-991",
                requests.getFirst().getBoundaryActionId());
        assertEquals(
                3,
                requests.getFirst().getBoundarySubstep());
        assertNull(requests.getFirst().getMaxStateIndex());
        assertNull(requests.getLast().getBoundaryActionId());
        assertNull(requests.getLast().getBoundarySubstep());
        assertEquals(42L, requests.getLast().getMaxStateIndex());
    }

    @Test
    void headOnlyLoadTransfersNoMembershipsAndPinsEveryChunk() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return new GetModelEventsResult(
                    request.getRequestId(), 42L, List.of(),
                    request.getRequests().stream()
                            .map(stream -> new ModelEventStream(
                                    stream.getModelId(),
                                    new ModelHeadState(
                                            stream.getModelId(),
                                            "example.Model",
                                            9L, 41L,
                                            true, false),
                                    List.of()))
                            .toList());
        });
        ModelEventBatchLoader loader = new ModelEventBatchLoader(
                client,
                new ModelEventBatchLoader.Settings(
                        2, 8, 4, 1_024L));

        var result = loader.loadHeads(
                List.of("a", "b", "c"),
                null, "action-991", 3);

        assertEquals(42L, result.stateIndex());
        assertEquals(List.of("a", "b", "c"),
                     List.copyOf(result.heads().keySet()));
        assertEquals(
                List.of(0, 0, 0),
                requests.stream()
                        .flatMap(request ->
                                         request.getRequests().stream())
                        .map(request -> request.getMaxSize())
                        .toList());
        assertEquals(
                "action-991",
                requests.getFirst().getBoundaryActionId());
        assertEquals(
                42L,
                requests.getLast().getMaxStateIndex());
    }

    @Test
    void pagesAStreamWithBoundedMembershipsAndKeepsThePinnedBoundary() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        ModelHeadState head = new ModelHeadState("a", "example.A", 2L, 2L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            long sequenceNumber = request.getRequests().getFirst().getLastSequenceNumber() + 1L;
            long stateIndex = sequenceNumber;
            return new GetModelEventsResult(
                    request.getRequestId(), 9L,
                    List.of(new ModelEventPayload(stateIndex, event("event-" + stateIndex))),
                    List.of(new ModelEventStream(
                            "a", head,
                            List.of(new ModelEventMembership(
                                    sequenceNumber, stateIndex,
                                    sequenceNumber == 0L
                                            ? -1L
                                            : sequenceNumber - 1L,
                                    "action-" + stateIndex, 0)))));
        });
        ModelEventBatchLoader loader = new ModelEventBatchLoader(
                client, new ModelEventBatchLoader.Settings(4, 1, 1, 16L));

        long stateIndex = loader.load(List.of("a"), null, ignored -> {
        });

        assertEquals(9L, stateIndex);
        assertEquals(List.of(-1L, 0L, 1L), requests.stream()
                .map(request -> request.getRequests().getFirst().getLastSequenceNumber()).toList());
        assertNull(requests.getFirst().getMaxStateIndex());
        assertEquals(List.of(9L, 9L), requests.subList(1, 3).stream()
                .map(GetModelEvents::getMaxStateIndex).toList());
        assertEquals(List.of(1, 1, 1), requests.stream()
                .map(request -> request.getRequests().getFirst().getMaxSize()).toList());
    }

    @Test
    void continuesWhenTheByteBoundAdvancesOnlyOneOfSeveralStreams() {
        EventStoreClient client = mock(EventStoreClient.class);
        AtomicInteger invocation = new AtomicInteger();
        ModelHeadState aHead = new ModelHeadState("a", "example.A", 0L, 0L, true, false);
        ModelHeadState bHead = new ModelHeadState("b", "example.B", 0L, 1L, true, false);
        when(client.getModelEvents(any())).thenAnswer(answer -> {
            GetModelEvents request = answer.getArgument(0);
            boolean first = invocation.getAndIncrement() == 0;
            ModelEventPayload payload = new ModelEventPayload(
                    first ? 0L : 1L, event(first ? "large-a" : "large-b"));
            return new GetModelEventsResult(
                    request.getRequestId(), 7L, List.of(payload),
                    first
                            ? List.of(
                                    new ModelEventStream(
                                            "a", aHead, List.of(new ModelEventMembership(
                                                    0L, 0L, -1L, "action-a", 0))),
                                    new ModelEventStream("b", bHead, List.of()))
                            : List.of(new ModelEventStream(
                                    "b", bHead, List.of(new ModelEventMembership(
                                            0L, 1L, 0L, "action-b", 0)))));
        });
        ModelEventBatchLoader loader = new ModelEventBatchLoader(
                client, new ModelEventBatchLoader.Settings(4, 8, 8, 1L));

        loader.load(List.of("a", "b"), null, ignored -> {
        });

        assertEquals(2, invocation.get());
    }

    @Test
    void rejectsMissingPayloadAndIncompleteHistory() {
        EventStoreClient missingPayloadClient = mock(EventStoreClient.class);
        when(missingPayloadClient.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 0L, List.of(),
                    List.of(new ModelEventStream(
                            "a", new ModelHeadState("a", "example.A", 0L, 0L, true, false),
                            List.of(new ModelEventMembership(0L, 0L, -1L, "action", 0)))));
        });
        EventStoreClient incompleteClient = mock(EventStoreClient.class);
        when(incompleteClient.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 0L, List.of(),
                    List.of(new ModelEventStream(
                            "a", new ModelHeadState("a", "example.A", 0L, 0L, false, false), List.of())));
        });

        assertThrows(
                EventSourcingException.class,
                () -> new ModelEventBatchLoader(missingPayloadClient)
                        .load(List.of("a"), null, ignored -> {
                        }));
        assertThrows(
                EventSourcingException.class,
                () -> new ModelEventBatchLoader(incompleteClient)
                        .load(List.of("a"), null, ignored -> {
                        }));
    }

    @Test
    void membershipBudgetAlsoBoundsTheNumberOfStreamsPerChunk() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelEventBatchLoader loader = new ModelEventBatchLoader(
                client, new ModelEventBatchLoader.Settings(4, 3, 2, 1_024L));

        loader.load(List.of("a", "b", "c", "d"), null, ignored -> {
        });

        assertEquals(List.of(3, 1), requests.stream()
                .map(request -> request.getRequests().size()).toList());
    }

    @Test
    void rejectsMembershipBeyondThePinnedHead() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 1L,
                    List.of(new ModelEventPayload(1L, event("event"))),
                    List.of(new ModelEventStream(
                            "a", new ModelHeadState("a", "example.A", 0L, 0L, true, false),
                            List.of(new ModelEventMembership(0L, 1L, 0L, "action", 0)))));
        });

        assertThrows(
                EventSourcingException.class,
                () -> new ModelEventBatchLoader(client)
                        .load(List.of("a"), null, ignored -> {
                        }));
    }

    private static GetModelEventsResult emptyResponse(GetModelEvents request, long stateIndex) {
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex, List.of(),
                request.getRequests().stream()
                        .map(stream -> new ModelEventStream(stream.getModelId(), null, List.of()))
                        .toList());
    }

    private static SerializedMessage event(String value) {
        return new SerializedMessage(
                new Data<>(value.getBytes(), "event", 0),
                Metadata.empty(), value, 1L);
    }
}
