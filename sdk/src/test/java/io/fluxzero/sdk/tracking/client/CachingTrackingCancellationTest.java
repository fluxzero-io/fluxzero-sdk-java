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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CachingTrackingCancellationTest {
    @Test
    void terminalReleaseStopsAnIdleLocalCacheWait() throws Exception {
        var delegate = delegate();
        var config = ConsumerConfiguration.builder().name("consumer").maxFetchSize(1).build();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(CompletableFuture.completedFuture(claim(10)));
        try (var client = new ObservingClient(delegate)) {
            client.cacheNewMessages(List.of(message(10)));
            var reading = client.read("tracker", 10L, config);
            Thread waiter = client.waiters.poll(2, TimeUnit.SECONDS);
            assertNotNull(waiter);
            try {
                client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED)
                        .get(1, TimeUnit.SECONDS);
                assertTrue(reading.isDone());
                assertTrue(waiter.join(Duration.ofSeconds(1)), "The abandoned local cache worker must stop");
            } finally {
                waiter.interrupt();
                waiter.join(Duration.ofSeconds(2));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void terminalReleaseWaitsForLateClaimWithoutStartingAnotherWaitOrFallback(boolean cachedPosition) throws Exception {
        var delegate = delegate();
        var config = ConsumerConfiguration.builder().name("consumer").build();
        var claim = new CompletableFuture<ClaimSegmentResult>();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(claim);
        try (var client = new ObservingClient(delegate)) {
            client.cacheNewMessages(List.of(message(10)));
            var reading = client.read("tracker", 10L, config);
            var cancellation = client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            assertFalse(cancellation.isDone());
            assertFalse(claim.isCancelled(), "Remote acquisition must finish before final release");
            verify(delegate).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            claim.complete(cachedPosition ? claim(10)
                    : new ClaimSegmentResult(1, Position.newPosition(), new int[]{0, 128}));
            cancellation.get(1, TimeUnit.SECONDS);
            assertTrue(reading.isCompletedExceptionally());
            assertTrue(client.waiters.isEmpty());
            verify(delegate, never()).read(anyString(), any(), any());
            verify(delegate, times(2)).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            // Completed reads are no longer retained as pending acquisitions.
            client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED).get(1, TimeUnit.SECONDS);
            verify(delegate, times(3)).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
        }
    }

    @Test
    void cancellationDoesNotAffectOtherTrackersOrLaterReads() throws Exception {
        var delegate = delegate();
        var config = ConsumerConfiguration.builder().name("consumer").maxFetchSize(1).build();
        when(delegate.claimSegment(anyString(), eq(10L), eq(config)))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(claim(10)));
        try (var client = new ObservingClient(delegate)) {
            client.cacheNewMessages(List.of(message(10)));
            var abandoned = client.read("abandoned", 10L, config);
            Thread abandonedWaiter = client.waiters.poll(2, TimeUnit.SECONDS);
            assertNotNull(abandonedWaiter);
            var active = client.read("active", 10L, config);
            Thread activeWaiter = client.waiters.poll(2, TimeUnit.SECONDS);
            assertNotNull(activeWaiter);
            client.disconnectTerminatedTracker("consumer", "abandoned", Guarantee.STORED).get(1, TimeUnit.SECONDS);
            assertTrue(abandoned.isCompletedExceptionally());
            assertFalse(active.isDone());
            var next = message(11);
            client.cacheNewMessages(List.of(next));
            assertEquals(List.of(next), active.get(1, TimeUnit.SECONDS).getMessages());
            assertEquals(List.of(next), client.read("abandoned", 10L, config)
                    .get(1, TimeUnit.SECONDS).getMessages());
            assertTrue(abandonedWaiter.join(Duration.ofSeconds(1)));
            assertTrue(activeWaiter.join(Duration.ofSeconds(1)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closingClientOrCancelingReadStopsItsLocalWorker(boolean closeClient) throws Exception {
        var delegate = delegate();
        var config = ConsumerConfiguration.builder().name("consumer").build();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(CompletableFuture.completedFuture(claim(10)));
        try (var client = new ObservingClient(delegate)) {
            client.cacheNewMessages(List.of(message(10)));
            var reading = client.read("tracker", 10L, config);
            Thread worker = client.waiters.poll(2, TimeUnit.SECONDS);
            assertNotNull(worker);
            try {
                if (closeClient) {
                    client.close();
                } else {
                    reading.cancel(true);
                }
                assertTrue(reading.isCompletedExceptionally());
                assertTrue(worker.join(Duration.ofSeconds(1)));
            } finally {
                worker.interrupt();
            }
        }
    }

    @Test
    void terminalCancellationDuringProtectedWaitHookCancelsItsReturnedWait() throws Exception {
        var delegate = delegate();
        var config = ConsumerConfiguration.builder().name("consumer").build();
        var claim = new CompletableFuture<ClaimSegmentResult>();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(claim);
        var hookEntered = new CountDownLatch(1);
        var returnWait = new CountDownLatch(1);
        var localWait = new CompletableFuture<MessageBatch>();
        try (var client = new CachingTrackingClient(delegate, 10) {
            @Override
            protected CompletableFuture<MessageBatch> waitForCachedBatch(
                    ConsumerConfiguration config, long minIndex, ClaimSegmentResult claim, Instant deadline) {
                hookEntered.countDown();
                try {
                    assertTrue(returnWait.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return localWait;
            }
        }) {
            client.cacheNewMessages(List.of(message(10)));
            var reading = client.read("tracker", 10L, config);
            try (var completing = new io.fluxzero.common.TestTask(() -> claim.complete(claim(10)),
                                                                 returnWait::countDown)) {
                assertTrue(hookEntered.await(2, TimeUnit.SECONDS));
                var cancellation = client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
                assertFalse(cancellation.isDone());
                returnWait.countDown();
                completing.awaitCompletion(Duration.ofSeconds(2));
                cancellation.get(1, TimeUnit.SECONDS);
                assertTrue(localWait.isCancelled());
                assertTrue(reading.isCompletedExceptionally());
                verify(delegate, times(2)).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            }
        }
    }

    @Test
    void closeDuringLocalWaitPublicationStillStopsTheWorker() throws Exception {
        var delegate = delegate();
        var config = ConsumerConfiguration.builder().name("consumer").build();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(CompletableFuture.completedFuture(claim(10)));
        var publishWait = new CountDownLatch(1);
        try (var client = new ObservingClient(delegate) {
            @Override
            protected CompletableFuture<MessageBatch> waitForCachedBatch(
                    ConsumerConfiguration config, long minIndex, ClaimSegmentResult claim, Instant deadline) {
                var result = super.waitForCachedBatch(config, minIndex, claim, deadline);
                try {
                    assertTrue(publishWait.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return result;
            }
        }) {
            client.cacheNewMessages(List.of(message(10)));
            var result = new AtomicReference<CompletableFuture<MessageBatch>>();
            try (var reading = new io.fluxzero.common.TestTask(
                    () -> result.set(client.read("tracker", 10L, config)), publishWait::countDown)) {
                Thread worker = client.waiters.poll(2, TimeUnit.SECONDS);
                assertNotNull(worker);
                try {
                    client.close();
                    publishWait.countDown();
                    reading.awaitCompletion(Duration.ofSeconds(2));
                    assertTrue(result.get().isCompletedExceptionally());
                    assertTrue(worker.join(Duration.ofSeconds(1)));
                } finally {
                    worker.interrupt();
                }
            }
        }
    }

    @Test
    void closeCancelsCacheFillerRegistrationPublishedAfterShutdown() throws Exception {
        var delegate = delegate();
        var enteringStart = new CountDownLatch(1);
        var finishStart = new CountDownLatch(1);
        var registration = mock(Registration.class);
        var config = ConsumerConfiguration.builder().name("consumer").build();
        when(delegate.read("tracker", null, config)).thenReturn(CompletableFuture.completedFuture(
                new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true)));
        try (var client = new CachingTrackingClient(delegate, 10);
             var reading = new io.fluxzero.common.TestTask(() -> {
                 try (var trackers = mockStatic(DefaultTracker.class)) {
                     trackers.when(() -> DefaultTracker.start(any(), any(ConsumerConfiguration.class), same(delegate)))
                             .thenAnswer(ignored -> {
                                 enteringStart.countDown();
                                 assertTrue(finishStart.await(2, TimeUnit.SECONDS));
                                 return registration;
                             });
                     client.read("tracker", null, config);
                 }
             }, finishStart::countDown)) {
            assertTrue(enteringStart.await(2, TimeUnit.SECONDS));
            client.close();
            verify(registration, never()).cancel();
            finishStart.countDown();
            reading.awaitCompletion(Duration.ofSeconds(2));
            verify(registration).cancel();
        }
    }

    private static TrackingClient delegate() {
        var delegate = mock(TrackingClient.class);
        when(delegate.getMessageType()).thenReturn(MessageType.EVENT);
        when(delegate.readAndWait(anyString(), any(), any())).thenAnswer(invocation -> new CompletableFuture<>().get());
        when(delegate.disconnectTerminatedTracker(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return delegate;
    }

    private static ClaimSegmentResult claim(long index) {
        return new ClaimSegmentResult(1, new Position(new int[]{0, 128}, index), new int[]{0, 128});
    }

    private static SerializedMessage message(long index) {
        var result = mock(SerializedMessage.class);
        when(result.getIndex()).thenReturn(index);
        when(result.getSegment()).thenReturn(0);
        return result;
    }

    private static class ObservingClient extends CachingTrackingClient {
        final LinkedBlockingQueue<Thread> waiters = new LinkedBlockingQueue<>();
        ObservingClient(TrackingClient delegate) { super(delegate, 10); }
        @Override
        protected MessageBatch doWaitForCachedBatch(ConsumerConfiguration config, long minIndex,
                                                    ClaimSegmentResult claim, Instant deadline) throws InterruptedException {
            waiters.add(Thread.currentThread());
            return super.doWaitForCachedBatch(config, minIndex, claim, deadline);
        }
    }

    @Test
    void terminalReleaseWaitsForAnAlreadyStartedFallbackRead() throws Exception {
        var delegate = mock(TrackingClient.class);
        when(delegate.getMessageType()).thenReturn(MessageType.EVENT);
        when(delegate.readAndWait(anyString(), any(), any())).thenAnswer(invocation -> new CompletableFuture<>().get());
        var claim = new CompletableFuture<ClaimSegmentResult>();
        var fallback = new CompletableFuture<MessageBatch>();
        var config = ConsumerConfiguration.builder().name("consumer").build();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(claim);
        when(delegate.read("tracker", 10L, config)).thenReturn(fallback);
        when(delegate.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED))
                .thenReturn(CompletableFuture.completedFuture(null));
        try (var client = new CachingTrackingClient(delegate, 10)) {
            var cached = mock(SerializedMessage.class);
            when(cached.getIndex()).thenReturn(10L);
            when(cached.getSegment()).thenReturn(0);
            client.cacheNewMessages(List.of(cached));
            var reading = client.read("tracker", 10L, config);
            claim.complete(new ClaimSegmentResult(1, Position.newPosition(), new int[]{0, 128}));
            verify(delegate).read("tracker", 10L, config);
            var cancellation = client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            verify(delegate).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            assertFalse(cancellation.isDone());
            assertFalse(fallback.isCancelled());
            var empty = new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true);
            try (var closing = new io.fluxzero.common.TestTask(client::close, () -> fallback.complete(empty))) {
                closing.awaitBlockedIn(io.fluxzero.sdk.common.ClientUtils.class, "waitForResults", java.time.Duration.ofSeconds(1));
                verify(delegate, never()).close();
                fallback.complete(empty);
                closing.awaitCompletion(java.time.Duration.ofSeconds(2));
                verify(delegate).close();
            }
            assertTrue(reading.isDone());
            assertTrue(cancellation.isDone());
            verify(delegate, times(2)).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
        }
    }
}
