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

package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionPoolTest {

    @Test
    void monitorReadsLocalStateBeforeCloseNotification() throws Exception {
        var open = new AtomicBoolean(true);
        var session = mock(WebsocketSession.class);
        when(session.isOpen()).thenAnswer(ignored -> open.get());
        try (var pool = new SessionPool(1, () -> session)) {
            pool.get();
            var monitor = pool.maintain(null, ignored -> {});
            assertTrue(monitor.isConnected());
            open.set(false);
            assertFalse(monitor.isConnected());
            open.set(true);
            monitor.cancel();
            assertFalse(monitor.isConnected());
            var active = pool.maintain(null, ignored -> {});
            pool.close();
            assertFalse(active.isConnected());
        }
    }

    @Test
    void testPoolCyclesSessions() {
        SessionPool sessionPool =
                new SessionPool(3, () -> when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock());
        WebsocketSession first = sessionPool.get();
        WebsocketSession second = sessionPool.get();
        WebsocketSession third = sessionPool.get();
        WebsocketSession fourth = sessionPool.get();

        assertNotSame(first, second);
        assertNotSame(first, third);
        assertNotSame(second, third);
        assertSame(first, fourth);
    }

    @Test
    void singleSessionPoolAlwaysReturnsTheSameSession() {
        SessionPool sessionPool =
                new SessionPool(1, () -> when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock());

        WebsocketSession first = sessionPool.get();
        WebsocketSession second = sessionPool.get("first-routing-key");
        WebsocketSession third = sessionPool.get("another-routing-key");

        assertSame(first, second);
        assertSame(first, third);
    }

    @Test
    void sessionCreationDoesNotHoldTheMapLock() throws Exception {
        CountDownLatch firstCreationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCreation = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        WebsocketSession secondSession = openSession();
        SessionPool sessionPool = new SessionPool(2, () -> {
            if (attempts.getAndIncrement() == 0) {
                firstCreationStarted.countDown();
                try {
                    assertTrue(releaseFirstCreation.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return openSession();
            }
            return secondSession;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WebsocketSession> first = executor.submit(() -> sessionPool.get(0));

            assertTrue(firstCreationStarted.await(5, TimeUnit.SECONDS));
            assertSame(secondSession, sessionPool.get(1));
            assertFalse(first.isDone());

            releaseFirstCreation.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isOpen());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replacementFactoryReceivesTheClosedSession() {
        WebsocketSession first = mock(WebsocketSession.class);
        when(first.isOpen()).thenReturn(true, false, false);
        WebsocketSession replacement = openSession();
        AtomicReference<WebsocketSession> replacedSession = new AtomicReference<>();
        SessionPool sessionPool = new SessionPool(1, previousSession -> {
            if (previousSession == null) {
                return first;
            }
            replacedSession.set(previousSession);
            return replacement;
        });

        assertSame(first, sessionPool.get());
        assertSame(replacement, sessionPool.get());
        assertSame(first, replacedSession.get());
    }

    @Test
    void constructorRejectsZeroSizedPool() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPool(0, () -> mock(WebsocketSession.class)));
    }

    @Test
    void constructorRejectsNegativeSizedPool() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPool(-1, () -> mock(WebsocketSession.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownClosesConnectionEstablishedAfterItsSnapshot(boolean replacing) throws Exception {
        CountDownLatch creationStarted = new CountDownLatch(1);
        CountDownLatch releaseCreation = new CountDownLatch(1);
        WebsocketSession previous = openSession();
        WebsocketSession created = openSession();
        AtomicInteger attempts = new AtomicInteger();
        SessionPool pool = new SessionPool(1, () -> {
            if (replacing && attempts.getAndIncrement() == 0) {
                return previous;
            }
            creationStarted.countDown();
            try {
                assertTrue(releaseCreation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return created;
        });
        if (replacing) {
            assertSame(previous, pool.get());
            when(previous.isOpen()).thenReturn(false);
        }
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<WebsocketSession> pending = executor.submit(() -> pool.get());
            try {
                assertTrue(creationStarted.await(5, TimeUnit.SECONDS));
                pool.close();
            } finally {
                releaseCreation.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                                                      () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(SessionPool.ClientClosedException.class, failure.getCause());
            verify(created).close();
            assertThrows(SessionPool.ClientClosedException.class, pool::get);
        }
    }

    @Test
    void shutdownRejectsPreviouslyCachedSessionsAndClosesOnlyOnce() throws Exception {
        WebsocketSession session = openSession();
        SessionPool pool = new SessionPool(1, () -> session);
        assertSame(session, pool.get());

        pool.close();
        pool.close();

        // A close implementation need not update isOpen synchronously.
        assertThrows(SessionPool.ClientClosedException.class, pool::get);
        assertThrows(SessionPool.ClientClosedException.class, () -> pool.get("routing-key"));
        verify(session, times(1)).close();
    }

    @Test
    void shutdownAbortsFailedCloseAndStillClosesOtherSessions() throws Exception {
        WebsocketSession failing = openSession();
        WebsocketSession other = openSession();
        doThrow(new IOException("close failed")).when(failing).close();
        doThrow(new IllegalStateException("abort failed")).when(failing).abort(any());
        AtomicInteger created = new AtomicInteger();
        SessionPool pool = new SessionPool(2, () -> created.getAndIncrement() == 0 ? failing : other);
        assertSame(failing, pool.get());
        assertSame(other, pool.get());

        pool.close();

        verify(failing).abort(any());
        verify(other).close();
        assertThrows(SessionPool.ClientClosedException.class, pool::get);
    }

    @Test
    void observersShareAKeyedConnectionAndIgnoreThePreviousSessionsLateClose() throws Exception {
        var first = openSession();
        var replacement = openSession();
        var attempts = new AtomicInteger();
        var release = new CountDownLatch(1);
        try (SessionPool pool = new SessionPool(2, () -> {
            if (attempts.getAndIncrement() == 0) { return first; }
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return replacement;
        })) {
            var events = new LinkedBlockingQueue<Boolean>();
            var other = new LinkedBlockingQueue<Boolean>();
            Registration a = pool.maintain("append", events::add);
            assertNext(events, false, true);
            Registration b = pool.maintain("append", other::add);
            assertNext(other, true);
            when(first.isOpen()).thenReturn(false);
            pool.disconnected(first);
            assertNext(events, false);
            assertNext(other, false);
            a.cancel();
            release.countDown();
            assertNext(other, true);
            assertSame(replacement, pool.get("append"));
            pool.disconnected(first);
            pool.close();
            assertNext(other, false);
            assertTrue(other.isEmpty());
            assertTrue(events.isEmpty());
            assertEquals(2, attempts.get());
            b.cancel();
        } finally {
            release.countDown();
        }
    }

    @Test
    void onlyTheLastCancellationInterruptsAnObserverOwnedHandshake() throws Exception {
        var started = new CompletableFuture<Thread>();
        var interrupted = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var release = new CountDownLatch(1);
        try (SessionPool pool = new SessionPool(1, () -> {
            attempts.incrementAndGet();
            started.complete(Thread.currentThread());
            try {
                release.await();
                return openSession();
            } catch (InterruptedException e) {
                interrupted.countDown();
                return null; // This factory consumes the interrupt and leaves the slot disconnected.
            }
        })) {
            Registration a = pool.maintain("a", value -> {});
            Registration b = pool.maintain("b", value -> {});
            Thread worker = started.get(3, TimeUnit.SECONDS);
            a.cancel();
            a.cancel();
            assertEquals(1L, interrupted.getCount());
            b.cancel();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(worker.join(Duration.ofSeconds(3)));
            assertEquals(1, attempts.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownWhileConnectingCannotPublishALateReadyState() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var session = openSession();
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        doAnswer(invocation -> { closed.countDown(); return null; }).when(session).close();
        try (SessionPool pool = new SessionPool(1, () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return session;
        })) {
            var events = new LinkedBlockingQueue<Boolean>();
            pool.maintain(null, events::add);
            assertNext(events, false);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            pool.close();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            assertTrue(events.isEmpty());
            assertThrows(SessionPool.ClientClosedException.class, () -> pool.maintain(null, events::add));
        } finally {
            release.countDown();
        }
    }

    @Test
    void roundRobinObservationRequiresEachSlotAndDoesNotConsumeATurn() throws Exception {
        var attempts = new AtomicInteger();
        try (SessionPool pool = new SessionPool(2, () -> { attempts.incrementAndGet(); return openSession(); })) {
            var events = new LinkedBlockingQueue<Boolean>();
            pool.maintain(null, events::add);
            assertNext(events, false, true);
            assertSame(pool.get(0), pool.get());
            assertSame(pool.get(1), pool.get());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void aTerminalConnectionFailureDoesNotSpawnAnotherAttempt() throws Exception {
        var failedWorker = new CompletableFuture<Thread>();
        var attempts = new AtomicInteger();
        try (SessionPool pool = new SessionPool(1, () -> {
            if (attempts.incrementAndGet() == 1) {
                failedWorker.complete(Thread.currentThread());
                throw new IllegalStateException("terminal handshake failure");
            }
            return openSession();
        })) {
            var changes = new LinkedBlockingQueue<Boolean>();
            var first = pool.maintain(null, changes::add);
            assertNext(changes, false);
            Thread worker = failedWorker.get(3, TimeUnit.SECONDS);
            assertTrue(worker.join(Duration.ofSeconds(3)));
            assertEquals(1, attempts.get());
            pool.maintain(null, ignored -> {});
            assertNext(changes, true);
            first.cancel();
        }
    }

    @Test
    void listenersCanRegisterAndCancelFromTheirCallback() throws Exception {
        try (SessionPool pool = new SessionPool(1, SessionPoolTest::openSession)) {
            var nested = new CompletableFuture<Boolean>();
            var initial = new CompletableFuture<Registration>();
            initial.complete(pool.maintain(null, ready -> {
                if (ready) {
                    initial.join().cancel();
                    pool.maintain(null, nested::complete);
                }
            }));
            assertTrue(nested.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void observersShareTheSameSnapshotWhenTheSocketReopensDuringNotification() throws Exception {
        var open = new AtomicBoolean(true);
        // Return the closed state once, as when another thread reopens the slot during dispatch.
        var observations = new SessionObservations(1, slot -> {}, slot -> open.getAndSet(true));
        try {
            var first = new LinkedBlockingQueue<Boolean>();
            var second = new LinkedBlockingQueue<Boolean>();
            observations.subscribe(0, 1, first::add);
            observations.subscribe(0, 1, second::add);
            assertNext(first, true);
            assertNext(second, true);
            open.set(false);
            observations.changed(0);
            observations.changed(0);
            assertNext(first, false, true);
            assertNext(second, false, true);
        } finally {
            observations.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aMultiSlotSnapshotCannotReportRecoveryWhileAnotherSlotIsDown(boolean subscribe) throws Exception {
        var slots = new boolean[]{true, true};
        var release = new CountDownLatch(1);
        var down = new CompletableFuture<Thread>();
        var events = new LinkedBlockingQueue<Boolean>();
        Runnable waitForRelease = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var observations = new SessionObservations(2, slot -> waitForRelease.run(), slot -> slots[slot]);
        try {
            observations.subscribe(0, 2, value -> {
                events.add(value);
                if (!value && down.complete(Thread.currentThread())) { waitForRelease.run(); }
            });
            assertNext(events, true);
            slots[0] = false;
            observations.changed(0);
            Thread delivery = down.get(3, TimeUnit.SECONDS);
            assertNext(events, false);

            // The first slot has recovered but the second slot's close callback is still pending.
            slots[1] = false;
            slots[0] = true;
            if (subscribe) {
                var added = new LinkedBlockingQueue<Boolean>();
                observations.subscribe(0, 2, added::add);
                assertNext(added, false);
            } else {
                observations.changed(0);
            }
            observations.changed(1); // The delayed close has now arrived.
            slots[1] = true;
            observations.changed(1);
            observations.close();
            release.countDown();
            assertTrue(delivery.join(Duration.ofSeconds(3)));
            assertNext(events, true, false);
            assertTrue(events.isEmpty(), "The continuously disconnected group must not report an interim recovery");
        } finally {
            observations.close();
            release.countDown();
        }
    }

    private static void assertNext(LinkedBlockingQueue<Boolean> events, boolean... expected) throws Exception {
        for (boolean value : expected) { assertEquals(value, events.poll(3, TimeUnit.SECONDS)); }
    }

    private static WebsocketSession openSession() {
        return when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock();
    }
}
