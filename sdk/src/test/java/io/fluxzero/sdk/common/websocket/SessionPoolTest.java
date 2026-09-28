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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionPoolTest {

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

    private static WebsocketSession openSession() {
        return when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock();
    }
}
