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

package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.fluxzero.common.ObjectUtils.memoize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectUtilsTest {
    @Test
    void testDeduplicateList() {
        List<Object> list = List.of("a", "b", "b", "c", "b", "a", "a");
        assertEquals(List.of("c", "b", "a"), ObjectUtils.deduplicate(list));
    }

    @Test
    void testDeduplicateListKeepFirst() {
        List<Object> list = List.of("a", "b", "b", "c", "b", "a", "a");
        assertEquals(List.of("a", "b", "c"), ObjectUtils.deduplicate(list, Function.identity(), true));
    }

    @SuppressWarnings("unchecked")
    @Test
    void memoizeAllowsNullKeys() {
        Function<Object, Object> mockFunction = mock(Function.class);
        when(mockFunction.apply(any())).thenReturn("foo");
        MemoizingFunction<Object, Object> memoizingFunction = memoize(mockFunction);
        assertEquals("foo", memoizingFunction.apply(null));
        assertTrue(memoizingFunction.isCached(null));
        memoizingFunction.apply(null);
        verify(mockFunction, times(1)).apply(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void memoizeAllowsNullValues() {
        Function<Object, Object> mockFunction = mock(Function.class);
        MemoizingFunction<Object, Object> memoizingFunction = memoize(mockFunction);
        assertNull(memoizingFunction.apply("foo"));
        assertTrue(memoizingFunction.isCached("foo"));
        memoizingFunction.apply("foo");
        verify(mockFunction, times(1)).apply(any());
        assertNull(memoizingFunction.apply(null));
        verify(mockFunction, times(2)).apply(any());
    }

    @Test
    void memoizeComputesConcurrentCacheMissOnce() throws Exception {
        CountDownLatch firstInvocationStarted = new CountDownLatch(1);
        CountDownLatch secondInvocationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstInvocation = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        MemoizingFunction<String, String> memoizingFunction = memoize(key -> {
            int call = invocations.incrementAndGet();
            if (call == 1) {
                firstInvocationStarted.countDown();
                assertTrue(await(releaseFirstInvocation, 5, TimeUnit.SECONDS));
            } else {
                secondInvocationStarted.countDown();
            }
            return "value";
        });

        try (ExecutorService executor = ObjectUtils.newWorkerPool("ObjectUtilsTest-worker-", 2)) {
            Future<String> first = executor.submit(() -> memoizingFunction.apply("foo"));
            assertTrue(await(firstInvocationStarted, 5, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> memoizingFunction.apply("foo"));

            assertFalse(await(secondInvocationStarted, 250, TimeUnit.MILLISECONDS));
            releaseFirstInvocation.countDown();

            assertEquals("value", first.get(5, TimeUnit.SECONDS));
            assertEquals("value", second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, invocations.get());
    }

    @Test
    void supportsVirtualThreadWorkersOnlyOnJava25AndNewer() {
        assertFalse(ObjectUtils.supportsVirtualThreadWorkers(24));
        assertTrue(ObjectUtils.supportsVirtualThreadWorkers(25));
    }

    @Test
    void newWorkerPoolUsesVirtualThreadsOnSupportedRuntimes() throws Exception {
        try (ExecutorService executor = ObjectUtils.newWorkerPool("ObjectUtilsTest-worker-", 2)) {
            Future<Boolean> isVirtual = executor.submit(() -> Thread.currentThread().isVirtual());
            assertEquals(ObjectUtils.supportsVirtualThreadWorkers(), isVirtual.get());
        }
    }

    private static boolean await(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
