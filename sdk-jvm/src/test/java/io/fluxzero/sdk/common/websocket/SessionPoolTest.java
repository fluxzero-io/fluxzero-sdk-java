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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        WebsocketSession second = sessionPool.get();
        WebsocketSession third = sessionPool.get();

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
    void constructorRejectsZeroSizedPool() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPool(0, () -> mock(WebsocketSession.class)));
    }

    @Test
    void constructorRejectsNegativeSizedPool() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPool(-1, () -> mock(WebsocketSession.class)));
    }

    private static WebsocketSession openSession() {
        return when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock();
    }
}
