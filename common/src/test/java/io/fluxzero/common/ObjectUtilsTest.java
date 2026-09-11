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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static io.fluxzero.common.ObjectUtils.memoize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectUtilsTest {
    @Test
    void continuationWorkersAreIndependentAndDoNotInheritCallerState() throws Exception {
        InheritableThreadLocal<String> local = new InheritableThreadLocal<>();
        local.set("must-not-leak");
        var executor = ObjectUtils.newWorkerExecutor("isolated-continuation-");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> blocked = CompletableFuture.runAsync(() -> {
            entered.countDown();
            assertTrue(await(release, 5, TimeUnit.SECONDS));
        }, executor);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Thread> other = CompletableFuture.supplyAsync(() -> {
                assertNull(local.get());
                return Thread.currentThread();
            }, executor);
            Thread worker = other.get(5, TimeUnit.SECONDS);
            assertTrue(worker.isVirtual());
            assertTrue(worker.getName().startsWith("isolated-continuation-"));
            assertFalse(blocked.isDone());
        } finally {
            local.remove();
            release.countDown();
            blocked.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void parallelTerminalsUseOneOwnedPoolAndRetainOrderAndNestedProgress() throws Exception {
        try (ForkJoinPool callerPool = new ForkJoinPool(1)) {
            List<Integer> actual = callerPool.submit(() -> ObjectUtils.inParallel(() -> {
                var ownedPool = ForkJoinTask.getPool();
                assertNotSame(callerPool, ownedPool);
                assertNotSame(ForkJoinPool.commonPool(), ownedPool);
                return IntStream.range(0, 128).parallel().map(index -> {
                    assertSame(ownedPool, ForkJoinTask.getPool());
                    assertTrue(Thread.currentThread().getName().startsWith("fluxzero-cpu-"));
                    return ObjectUtils.inParallel(() -> {
                        assertSame(ownedPool, ForkJoinTask.getPool());
                        return index;
                    });
                }).boxed().toList();
            })).get(5, TimeUnit.SECONDS);
            assertEquals(IntStream.range(0, 128).boxed().toList(), actual);
        }
    }

    @Test
    void parallelTerminalPreservesFailureIdentityAndDoesNotInheritThreadLocals() {
        InheritableThreadLocal<String> local = new InheritableThreadLocal<>();
        local.set("must-not-leak");
        try {
            assertNull(ObjectUtils.inParallel(local::get));
            IllegalArgumentException failure = new IllegalArgumentException("same failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> ObjectUtils.inParallel(() -> { throw failure; })));
            var wrapped = new java.util.concurrent.CompletionException(failure);
            assertSame(wrapped, assertThrows(java.util.concurrent.CompletionException.class,
                    () -> ObjectUtils.inParallel(() -> { throw wrapped; })));
            AssertionError error = new AssertionError("same error");
            assertSame(error, assertThrows(AssertionError.class,
                    () -> ObjectUtils.runInParallel(() -> { throw error; })));
        } finally {
            local.remove();
        }
    }

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

    @Test
    void providesNullSafeStringUtilities() {
        assertTrue(ObjectUtils.isBlank(null));
        assertTrue(ObjectUtils.isBlank(" \t\n"));
        assertFalse(ObjectUtils.isBlank(" value "));
        assertFalse(ObjectUtils.isNumeric(""));
        assertTrue(ObjectUtils.isNumeric("１２３"));
        assertFalse(ObjectUtils.isNumeric("12a"));
        assertEquals("Value", ObjectUtils.capitalize("value"));
        assertEquals("\uD801\uDC00", ObjectUtils.capitalize("\uD801\uDC28"));
        assertNull(ObjectUtils.capitalize(null));
    }

    @Test
    void stripsAccentsAndCompatibilityCharacters() {
        assertEquals("Creme brulee", ObjectUtils.stripAccents("Crème brûlée"));
        assertEquals("Lodz", ObjectUtils.stripAccents("Łódź"));
        assertEquals("123", ObjectUtils.stripAccents("１２３"));
        assertEquals("Æsir", ObjectUtils.stripAccents("Æsir"));
    }

    @Test
    void rendersStackTraceIncludingCause() {
        IllegalStateException cause = new IllegalStateException("cause");
        String trace = ObjectUtils.stackTrace(new RuntimeException("failure", cause));

        assertTrue(trace.startsWith("java.lang.RuntimeException: failure"));
        assertTrue(trace.contains("Caused by: java.lang.IllegalStateException: cause"));
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
        CountDownLatch secondTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstInvocation = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        MemoizingFunction<String, String> memoizingFunction = memoize(key -> {
            int call = invocations.incrementAndGet();
            if (call == 1) {
                firstInvocationStarted.countDown();
                assertTrue(await(releaseFirstInvocation, 5, TimeUnit.SECONDS));
            }
            return "value";
        });

        try (ExecutorService executor = ObjectUtils.newWorkerPool("ObjectUtilsTest-worker-", 2)) {
            Future<String> first = executor.submit(() -> memoizingFunction.apply("foo"));
            assertTrue(await(firstInvocationStarted, 5, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> {
                secondTaskStarted.countDown();
                return memoizingFunction.apply("foo");
            });

            assertTrue(await(secondTaskStarted, 5, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            assertEquals(1, invocations.get());
            releaseFirstInvocation.countDown();

            assertEquals("value", first.get(5, TimeUnit.SECONDS));
            assertEquals("value", second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, invocations.get());
    }

    @Test
    @SuppressWarnings("deprecation")
    void virtualWorkersAreSupportedOnEverySupportedRuntime() {
        assertTrue(ObjectUtils.supportsVirtualThreadWorkers());
    }

    @Test
    void newWorkerPoolUsesIndependentVirtualThreadsAndRejectsTasksAfterClose() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = ObjectUtils.newWorkerPool("ObjectUtilsTest-worker-", 1);
        try {
            Future<Thread> first = executor.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                return Thread.currentThread();
            });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            Thread second = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            assertTrue(second.isVirtual());
            assertTrue(second.getName().startsWith("ObjectUtilsTest-worker-"));
            assertFalse(first.isDone());
            releaseFirst.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isVirtual());
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
        assertTrue(executor.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
    }

    @Test
    void workerFactoryCreatesNamedUnstartedVirtualThreads() throws Exception {
        InheritableThreadLocal<String> context = new InheritableThreadLocal<>();
        context.set("context");
        CompletableFuture<String> observed = new CompletableFuture<>();
        try {
            Thread worker = ObjectUtils.newWorkerThreadFactory("named-worker-")
                    .newThread(() -> observed.complete(context.get()));
            assertTrue(worker.isVirtual());
            assertTrue(worker.isDaemon());
            assertEquals(Thread.State.NEW, worker.getState());
            assertEquals("named-worker-0", worker.getName());
            worker.start();
            assertEquals("context", observed.get(5, TimeUnit.SECONDS));
            worker.join();
        } finally {
            context.remove();
        }
    }

    @Test
    void workerPoolStillRejectsInvalidSizingHints() {
        assertThrows(IllegalArgumentException.class, () -> ObjectUtils.newWorkerPool("invalid-", 0));
        assertThrows(IllegalArgumentException.class, () -> ObjectUtils.newWorkerPool("invalid-", -1));
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
