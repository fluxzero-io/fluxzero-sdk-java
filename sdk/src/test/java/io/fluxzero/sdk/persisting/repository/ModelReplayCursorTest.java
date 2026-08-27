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
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;
import io.fluxzero.sdk.persisting.search.Searchable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelReplayCursorTest {

    @Test
    void retriesCurrentGraphWhenADocumentAdvancesDuringReconstruction() {
        String modelId = "current-document";
        ModelHeadState firstHead = documentHead(modelId, 0L, 1L);
        ModelHeadState currentHead = documentHead(modelId, 1L, 2L);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, firstHead, 1L),
                graphResponse(modelId, currentHead, 2L));
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id",
                                new CurrentDocument(modelId, "current")),
                        currentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class));

        Graph<CurrentDocument> result = loader.graph(
                modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                ModelReadBoundary.current(), "test", Map.of());

        assertEquals(new CurrentDocument(modelId, "current"), result.get());
        assertEquals(2L, result.stateIndex());
        verify(client, times(2)).getModelGraph(any());
    }

    @Test
    void boundsCurrentGraphRetriesWhenADocumentKeepsAdvancing() {
        String modelId = "moving-document";
        ModelHeadState graphHead = documentHead(modelId, 0L, 1L);
        ModelHeadState currentHead = documentHead(modelId, 1L, 2L);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, graphHead, 1L));
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id",
                                new CurrentDocument(modelId, "current")),
                        currentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class));

        assertThrows(
                EventSourcingException.class,
                () -> loader.graph(
                        modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                        ModelReadBoundary.current(), "test", Map.of()));
        verify(client, times(8)).getModelGraph(any());
    }

    private static GetModelGraphResult graphResponse(
            String modelId,
            ModelHeadState head,
            long stateIndex) {
        return new GetModelGraphResult(
                0L, List.of(),
                new GetModelEventsResult(
                        0L, stateIndex, List.of(),
                        List.of(new ModelEventStream(
                                modelId, head, List.of()))));
    }

    private static ModelHeadState documentHead(
            String modelId,
            long sequenceNumber,
            long stateIndex) {
        return new ModelHeadState(
                modelId, CurrentDocument.class.getName(),
                sequenceNumber, stateIndex, true, false);
    }

    @Model(
            eventSourced = false,
            searchable = true,
            searchProjection = @Searchable(collection = "currentDocuments"))
    private record CurrentDocument(
            @EntityId String id,
            String value) {
    }

    @Test
    void chunksModelIdsAndPinsEveryLaterChunkToTheFirstResponse() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L));
        List<GetModelEventsResult> pages = new ArrayList<>();

        long stateIndex = loader.load(
                List.of("a", "b", "c"), null,
                page -> pages.add(page.response()));

        assertEquals(42L, stateIndex);
        assertEquals(2, requests.size());
        assertNull(requests.getFirst().getBoundary().stateIndex());
        assertEquals(42L, requests.getLast().getBoundary().stateIndex());
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
        ModelReplayCursor loader = new ModelReplayCursor(
                client,
                new ModelReplayCursor.Settings(
                        2, 8, 4, 1_024L));
        LinkedHashMap<String, Long> cursors =
                new LinkedHashMap<>();
        cursors.put("a", -1L);
        cursors.put("b", -1L);
        cursors.put("c", -1L);

        var result = loader.load(
                cursors,
                ModelReadBoundary.commit("commit-991", 3),
                ignored -> {
                });

        assertEquals(42L, result.stateIndex());
        assertEquals(
                "commit-991",
                requests.getFirst().getBoundary().commitId());
        assertEquals(
                3,
                requests.getFirst().getBoundary().substep());
        assertNull(requests.getFirst().getBoundary().stateIndex());
        assertNull(requests.getLast().getBoundary().commitId());
        assertNull(requests.getLast().getBoundary().substep());
        assertEquals(42L, requests.getLast().getBoundary().stateIndex());
    }

    @Test
    void retriesAnUnmappedLegacyBoundaryAfterMigrationCatchesUp() {
        EventStoreClient client = mock(EventStoreClient.class);
        AtomicInteger reads = new AtomicInteger();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            boolean exact = reads.getAndIncrement() > 0;
            return new GetModelEventsResult(
                    request.getRequestId(), exact ? 3L : 7L,
                    exact, List.of(), List.of());
        });
        List<Long> awaited = new ArrayList<>();
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L),
                eventIndex -> {
                    awaited.add(eventIndex);
                    return true;
                });

        var result = loader.load(
                Map.of(), ModelReadBoundary.eventOrCurrent(42L), ignored -> {
                });

        assertEquals(3L, result.stateIndex());
        assertEquals(2, reads.get());
        assertEquals(List.of(42L), awaited);
    }

    @Test
    void mappedLegacyBoundaryDoesNotConsultMigrationProgress() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 3L,
                    true, List.of(), List.of());
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L),
                eventIndex -> {
                    throw new AssertionError("Mapped reads must not query migration progress");
                });

        var result = loader.load(
                Map.of(), ModelReadBoundary.eventOrCurrent(42L), ignored -> {
                });

        assertEquals(3L, result.stateIndex());
    }

    @Test
    void failsWhenProcessedLegacyEventStillHasNoModelMapping() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 7L,
                    false, List.of(), List.of());
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L),
                eventIndex -> true);

        EventSourcingException failure = assertThrows(
                EventSourcingException.class,
                () -> loader.load(
                        Map.of(), ModelReadBoundary.eventOrCurrent(42L), ignored -> {
                        }));

        assertTrue(failure.getMessage().contains("legacy event 42"));
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
        ModelReplayCursor loader = new ModelReplayCursor(
                client,
                new ModelReplayCursor.Settings(
                        2, 8, 4, 1_024L));

        var result = loader.loadHeads(
                List.of("a", "b", "c"),
                ModelReadBoundary.commit("commit-991", 3));

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
                "commit-991",
                requests.getFirst().getBoundary().commitId());
        assertEquals(
                42L,
                requests.getLast().getBoundary().stateIndex());
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
                                    "commit-" + stateIndex, 0)))));
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));

        long stateIndex = loader.load(List.of("a"), null, ignored -> {
        });

        assertEquals(9L, stateIndex);
        assertEquals(List.of(-1L, 0L, 1L), requests.stream()
                .map(request -> request.getRequests().getFirst().getLastSequenceNumber()).toList());
        assertNull(requests.getFirst().getBoundary().stateIndex());
        assertEquals(List.of(9L, 9L), requests.subList(1, 3).stream()
                .map(request -> request.getBoundary().stateIndex()).toList());
        assertEquals(List.of(1, 1, 1), requests.stream()
                .map(request -> request.getRequests().getFirst().getMaxSize()).toList());
    }

    @Test
    void prefetchesExactlyOneFollowingPageWhileApplyingTheCurrentPage() {
        EventStoreClient client = mock(EventStoreClient.class);
        BlockingQueue<GetModelEvents> requested = new LinkedBlockingQueue<>();
        ModelHeadState head = new ModelHeadState(
                "a", "example.A", 2L, 2L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requested.add(request);
            return pageResponse(request, head);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));
        AtomicInteger applied = new AtomicInteger();

        loader.load(List.of("a"), null, ignored -> {
            switch (applied.getAndIncrement()) {
                case 0 -> {
                    assertEquals(-1L, requestCursor(awaitRequest(requested)));
                    assertEquals(0L, requestCursor(awaitRequest(requested)));
                    assertTrue(requested.isEmpty(), "More than one page was prefetched");
                }
                case 1 -> assertEquals(1L, requestCursor(awaitRequest(requested)));
                case 2 -> assertTrue(requested.isEmpty(), "A page was requested beyond the pinned head");
                default -> throw new AssertionError("Unexpected replay page");
            }
        });

        assertEquals(3, applied.get());
    }

    @Test
    void keepsLocalMultiPageReplayOnTheCallingThread() {
        LocalEventStoreClient client = mock(LocalEventStoreClient.class);
        Thread callingThread = Thread.currentThread();
        ModelHeadState head = new ModelHeadState(
                "a", "example.A", 1L, 1L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            assertSame(callingThread, Thread.currentThread());
            return pageResponse(invocation.getArgument(0), head);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));
        AtomicInteger applied = new AtomicInteger();

        loader.load(List.of("a"), null, ignored -> applied.incrementAndGet());

        assertEquals(2, applied.get());
    }

    @Test
    void cancelsPrefetchWhenApplyingTheCurrentPageFails() throws Exception {
        EventStoreClient client = mock(EventStoreClient.class);
        CountDownLatch prefetchStarted = new CountDownLatch(1);
        CountDownLatch prefetchInterrupted = new CountDownLatch(1);
        CountDownLatch blockPrefetch = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        ModelHeadState head = new ModelHeadState(
                "a", "example.A", 1L, 1L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            if (requests.getAndIncrement() == 0) {
                return pageResponse(request, head);
            }
            prefetchStarted.countDown();
            try {
                blockPrefetch.await();
                throw new AssertionError("Cancelled prefetch unexpectedly resumed");
            } catch (InterruptedException expected) {
                prefetchInterrupted.countDown();
                throw new EventSourcingException("Prefetch interrupted", expected);
            }
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));
        IllegalStateException expected = new IllegalStateException("Apply failed");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> loader.load(List.of("a"), null, ignored -> {
                    await(prefetchStarted);
                    throw expected;
                }));

        assertSame(expected, actual);
        assertTrue(prefetchInterrupted.await(2L, TimeUnit.SECONDS),
                   "The prefetched transport read was not interrupted");
        assertEquals(2, requests.get());
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
                                                    0L, 0L, -1L, "commit-a", 0))),
                                    new ModelEventStream("b", bHead, List.of()))
                            : List.of(new ModelEventStream(
                                    "b", bHead, List.of(new ModelEventMembership(
                                            0L, 1L, 0L, "commit-b", 0)))));
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 8, 8, 1L));

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
                            List.of(new ModelEventMembership(0L, 0L, -1L, "commit", 0)))));
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
                () -> new ModelReplayCursor(missingPayloadClient)
                        .load(List.of("a"), null, ignored -> {
                        }));
        assertThrows(
                EventSourcingException.class,
                () -> new ModelReplayCursor(incompleteClient)
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
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 3, 2, 1_024L));

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
                            List.of(new ModelEventMembership(0L, 1L, 0L, "commit", 0)))));
        });

        assertThrows(
                EventSourcingException.class,
                () -> new ModelReplayCursor(client)
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

    private static GetModelEventsResult pageResponse(
            GetModelEvents request,
            ModelHeadState head) {
        long sequenceNumber = requestCursor(request) + 1L;
        return new GetModelEventsResult(
                request.getRequestId(), 9L,
                List.of(new ModelEventPayload(
                        sequenceNumber, event("event-" + sequenceNumber))),
                List.of(new ModelEventStream(
                        "a", head,
                        List.of(new ModelEventMembership(
                                sequenceNumber, sequenceNumber,
                                sequenceNumber == 0L ? -1L : sequenceNumber - 1L,
                                "commit-" + sequenceNumber, 0)))));
    }

    private static long requestCursor(GetModelEvents request) {
        return request.getRequests().getFirst().getLastSequenceNumber();
    }

    private static GetModelEvents awaitRequest(
            BlockingQueue<GetModelEvents> requests) {
        try {
            GetModelEvents result = requests.poll(2L, TimeUnit.SECONDS);
            assertTrue(result != null, "Expected replay page was not requested");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting replay request", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2L, TimeUnit.SECONDS), "Expected prefetch did not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting prefetch", e);
        }
    }

    private static SerializedMessage event(String value) {
        return new SerializedMessage(
                new Data<>(value.getBytes(), "event", 0),
                Metadata.empty(), value, 1L);
    }
}
