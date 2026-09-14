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

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelReplayReadBatcherTest {

    @Test
    void executesLocalStoreReadsOnTheCallingThread() {
        LocalEventStoreClient client = mock(LocalEventStoreClient.class);
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            invocationThread.set(Thread.currentThread());
            return response(invocation.getArgument(0));
        });
        ModelReplayCursor.ReadBatcher subject =
                new ModelReplayCursor.ReadBatcher(client, 16);

        Thread callingThread = Thread.currentThread();
        subject.get(request("local"));

        assertSame(callingThread, invocationThread.get());
    }

    @Test
    void coalescesReadsQueuedDuringACallAtANewBoundary() throws Exception {
        try (GatedClient gate = new GatedClient()) {
            var subject = new ModelReplayCursor.ReadBatcher(gate.client, 16);
            var first = subject.getAsync(request("a"));
            Call inFlight = gate.next();
            var requests = List.of(request("a"), request("b"), request("c"), request("a"));
            var pending = requests.stream().map(subject::getAsync).toList();
            assertTrue(gate.calls.isEmpty(), "queued reads must not start another concurrent batch");
            assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));

            inFlight.complete(11L);
            assertEquals(11L, first.get(2, SECONDS).getStateIndex());
            Call next = gate.next();
            assertEquals(List.of("a", "b", "c"), ids(next.request));
            assertFalse(next.request.getBoundary().historical());
            next.complete(12L);
            for (int i = 0; i < requests.size(); i++) {
                var result = pending.get(i).get(2, SECONDS);
                assertEquals(requests.get(i).getRequestId(), result.getRequestId());
                assertEquals(12L, result.getStateIndex(), "late reads need their own fresh observation");
                assertEquals(ids(requests.get(i)), result.getStreams().stream()
                        .map(ModelEventStream::getModelId).toList());
            }
            assertEquals(1, gate.maxActive.get());
        }
    }

    @Test
    void boundsBatchesWhileDrainingWorkCollectedDuringACall() throws Exception {
        try (GatedClient gate = new GatedClient()) {
            var subject = new ModelReplayCursor.ReadBatcher(gate.client, 2);
            var first = subject.getAsync(request("first"));
            Call initial = gate.next();
            var pending = List.of("a", "b", "c", "d").stream()
                    .map(id -> subject.getAsync(request(id))).toList();
            initial.complete(11L);
            first.get(2, SECONDS);
            Call second = gate.next();
            assertEquals(List.of("a", "b"), ids(second.request));
            second.complete(12L);
            Call third = gate.next();
            assertEquals(List.of("c", "d"), ids(third.request));
            third.complete(13L);
            for (int i = 0; i < pending.size(); i++) {
                assertEquals(i < 2 ? 12L : 13L, pending.get(i).get(2, SECONDS).getStateIndex());
            }
            assertEquals(1, gate.maxActive.get());
        }
    }

    @Test
    void keepsIncompatibleCursorsInSeparateCalls() throws Exception {
        try (GatedClient gate = new GatedClient()) {
            var subject = new ModelReplayCursor.ReadBatcher(gate.client, 16);
            var first = subject.getAsync(request("first"));
            Call initial = gate.next();
            var a = subject.getAsync(request("same", 0L));
            var b = subject.getAsync(request("same", 1L));
            var c = subject.getAsync(request("other", 0L));
            initial.complete(11L);
            first.get(2, SECONDS);
            Call second = gate.next();
            assertEquals(List.of("same", "other"), ids(second.request));
            assertEquals(0L, second.request.getRequests().getFirst().getLastSequenceNumber());
            second.complete(12L);
            Call third = gate.next();
            assertEquals(List.of("same"), ids(third.request));
            assertEquals(1L, third.request.getRequests().getFirst().getLastSequenceNumber());
            third.complete(13L);
            assertEquals(12L, a.get(2, SECONDS).getStateIndex());
            assertEquals(13L, b.get(2, SECONDS).getStateIndex());
            assertEquals(12L, c.get(2, SECONDS).getStateIndex());
        }
    }

    @Test
    void cancellationDoesNotInterruptTheCallOrDiscardOtherReaders() throws Exception {
        try (GatedClient gate = new GatedClient()) {
            var subject = new ModelReplayCursor.ReadBatcher(gate.client, 16);
            var first = subject.getAsync(request("first"));
            Call initial = gate.next();
            var cancelled = subject.getAsync(request("cancelled"));
            var surviving = subject.getAsync(request("surviving"));
            assertTrue(first.cancel(true));
            assertTrue(cancelled.cancel(true));
            assertFalse(initial.response.isDone());
            initial.complete(11L);
            Call next = gate.next();
            assertEquals(List.of("surviving"), ids(next.request));
            next.complete(12L);
            assertEquals(12L, surviving.get(2, SECONDS).getStateIndex());
            assertTrue(first.isCancelled());
            assertTrue(cancelled.isCancelled());
            assertEquals(1, gate.maxActive.get());
        }
    }

    @Test
    void propagatesFailuresToTheAffectedReadersAndContinuesDraining() throws Exception {
        try (GatedClient gate = new GatedClient()) {
            var subject = new ModelReplayCursor.ReadBatcher(gate.client, 16);
            var first = subject.getAsync(request("first"));
            Call initial = gate.next();
            var a = subject.getAsync(request("a"));
            var b = subject.getAsync(request("b"));
            var failure = new IllegalStateException("read failed");
            initial.response.completeExceptionally(failure);
            assertSame(failure, assertThrows(ExecutionException.class, () -> first.get(2, SECONDS)).getCause());
            Call grouped = gate.next();
            assertEquals(List.of("a", "b"), ids(grouped.request));
            var later = subject.getAsync(request("later"));
            grouped.response.completeExceptionally(failure);
            assertSame(failure, assertThrows(ExecutionException.class, () -> a.get(2, SECONDS)).getCause());
            assertSame(failure, assertThrows(ExecutionException.class, () -> b.get(2, SECONDS)).getCause());
            Call next = gate.next();
            assertEquals(List.of("later"), ids(next.request));
            next.complete(13L);
            assertEquals(13L, later.get(2, SECONDS).getStateIndex());
        }
    }

    @Test
    void startsALoneReadAndDrainsWorkEnqueuedByItsCompletion() throws Exception {
        try (GatedClient gate = new GatedClient()) {
            var subject = new ModelReplayCursor.ReadBatcher(gate.client, 16);
            var first = subject.getAsync(request("first"));
            Call initial = gate.next();
            var chained = first.thenCompose(ignored -> subject.getAsync(request("next")));
            initial.complete(11L);
            Call next = gate.next();
            assertEquals(List.of("next"), ids(next.request));
            next.complete(12L);
            assertEquals(12L, chained.get(2, SECONDS).getStateIndex());
            assertEquals(1, gate.maxActive.get());
        }
    }

    @Test
    void keepsHistoricalAndEmptyReadsOnTheCallingThread() {
        EventStoreClient client = mock(EventStoreClient.class);
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            invocationThread.set(Thread.currentThread());
            return response(invocation.getArgument(0));
        });
        var subject = new ModelReplayCursor.ReadBatcher(client, 16);
        var historical = new GetModelEvents(request("historical").getRequests(),
                ModelReadBoundary.state(11L, false), 1_024L);
        var empty = new GetModelEvents(List.of(), ModelReadBoundary.current(), 1_024L);
        for (var request : List.of(historical, empty)) {
            assertEquals(11L, subject.get(request).getStateIndex());
            assertSame(Thread.currentThread(), invocationThread.get());
            verify(client).getModelEvents(request);
        }
    }

    @Test
    void executesLargeNativeRequestsDirectly() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(
                invocation -> response(invocation.getArgument(0)));
        ModelReplayCursor.ReadBatcher subject =
                new ModelReplayCursor.ReadBatcher(client, 4_096);
        List<ModelEventStreamRequest> streams = new ArrayList<>(1_024);
        for (int index = 0; index < 1_024; index++) {
            streams.add(new ModelEventStreamRequest("model-" + index, -1L, 16));
        }
        GetModelEvents request = new GetModelEvents(
                streams, ModelReadBoundary.current(), 1_024L);

        assertEquals(1_024, subject.get(request).getStreams().size());
        verify(client).getModelEvents(request);
    }

    private static List<String> ids(GetModelEvents request) {
        return request.getRequests().stream().map(ModelEventStreamRequest::getModelId).toList();
    }

    private static GetModelEvents request(String modelId) {
        return request(modelId, -1L);
    }

    private static GetModelEvents request(String modelId, long sequenceNumber) {
        return new GetModelEvents(
                List.of(new ModelEventStreamRequest(modelId, sequenceNumber, 16)),
                ModelReadBoundary.current(), 1_024L);
    }

    private static GetModelEventsResult response(GetModelEvents request) {
        return response(request, 11L);
    }

    private static GetModelEventsResult response(GetModelEvents request, long stateIndex) {
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex, List.of(),
                request.getRequests().stream()
                        .map(stream -> new ModelEventStream(stream.getModelId(), null, List.of()))
                        .toList());
    }

    private record Call(GetModelEvents request, CompletableFuture<GetModelEventsResult> response) {
        void complete(long stateIndex) {
            response.complete(ModelReplayReadBatcherTest.response(request, stateIndex));
        }
    }

    private static class GatedClient implements AutoCloseable {
        final EventStoreClient client = mock(EventStoreClient.class);
        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final List<Call> allCalls = new CopyOnWriteArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();

        GatedClient() {
            when(client.getModelEvents(any())).thenAnswer(invocation -> {
                Call call = new Call(invocation.getArgument(0), new CompletableFuture<>());
                allCalls.add(call);
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    if (closed.get()) {
                        throw new IllegalStateException("Test client closed");
                    }
                    calls.add(call);
                    return call.response.get(5, SECONDS);
                } catch (ExecutionException failure) {
                    throw failure.getCause();
                } finally {
                    active.decrementAndGet();
                }
            });
        }

        Call next() throws InterruptedException {
            Call call = calls.poll(2, SECONDS);
            assertNotNull(call, "expected a read without needing another submission");
            return call;
        }

        @Override
        public void close() {
            closed.set(true);
            allCalls.forEach(call -> call.response.completeExceptionally(new IllegalStateException("Test finished")));
        }
    }
}
