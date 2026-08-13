/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common.websocket;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeResultDispatcherTest {

    @Test
    void runtimeWorkerCompletesSingleResultWithoutExecutorHop() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        CompletableFuture<Void> completion = dispatcher.submitFromRuntimeWorker(
                "session", () -> callbackThread.set(Thread.currentThread()));

        assertTrue(completion.isDone());
        assertEquals(Thread.currentThread(), callbackThread.get());
        assertEquals(0, executor.size());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void runtimeWorkerQueuesBehindExistingCompletionWork() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        List<String> completed = new ArrayList<>();
        CompletableFuture<Void> first = dispatcher.submit("session", () -> completed.add("first"));

        CompletableFuture<Void> second = dispatcher.submitFromRuntimeWorker(
                "session", () -> completed.add("second"));

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(List.of(), completed);
        assertEquals(1, executor.size());

        executor.runAll();

        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertEquals(List.of("first", "second"), completed);
    }

    @Test
    void blockingRuntimeWorkerCountsAgainstCompletionConcurrency() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> firstCompletion = new AtomicReference<>();
        Thread runtimeWorker = Thread.ofPlatform().start(() -> firstCompletion.set(
                dispatcher.submitFromRuntimeWorker("a", () -> {
                    callbackStarted.countDown();
                    try {
                        assertTrue(releaseCallback.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                })));
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

        AtomicBoolean secondRan = new AtomicBoolean();
        CompletableFuture<Void> second = dispatcher.submitFromRuntimeWorker("b", () -> secondRan.set(true));

        assertFalse(second.isDone());
        assertFalse(secondRan.get());
        assertEquals(new RuntimeResultDispatcher.State(2, 1, 1, 1), dispatcher.state());

        releaseCallback.countDown();
        assertTrue(runtimeWorker.join(Duration.ofSeconds(1)));
        assertTrue(firstCompletion.get().isDone());
        assertEquals(1, executor.size());
        executor.runAll();
        assertTrue(second.isDone());
        assertTrue(secondRan.get());
    }

    @Test
    void failedInlineCompletionReleasesItsPermit() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);

        CompletableFuture<Void> failed = dispatcher.submitFromRuntimeWorker(
                "session", () -> { throw new IllegalStateException("failed"); });
        CompletableFuture<Void> recovered = dispatcher.submitFromRuntimeWorker("session", () -> {});

        assertTrue(failed.isCompletedExceptionally());
        assertTrue(recovered.isDone());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void inlineCompletionParticipatesInOptionalDiagnostics() {
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(Runnable::run, 1, true);
        AtomicReference<RuntimeResultDispatcher.State> activeState = new AtomicReference<>();

        dispatcher.submitFromRuntimeWorker("session", () -> activeState.set(dispatcher.state()));

        assertEquals(1, activeState.get().workGroups());
        assertEquals(1, activeState.get().activeResults());
        assertEquals(1, activeState.get().maxObservedWorkGroups());
        assertEquals(1, activeState.get().maxObservedActiveResults());
        RuntimeResultDispatcher.State completed = dispatcher.state();
        assertEquals(0, completed.workGroups());
        assertEquals(0, completed.activeResults());
        assertEquals(1, completed.maxObservedWorkGroups());
        assertEquals(1, completed.maxObservedActiveResults());
        assertTrue(completed.maxCompletionDurationMillis() >= 0L);
    }

    @Test
    void closeDuringInlineCompletionFailsItAndReleasesItsPermit() throws Exception {
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(Runnable::run, 1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> completion = new AtomicReference<>();
        Thread runtimeWorker = Thread.ofPlatform().start(() -> completion.set(
                dispatcher.submitFromRuntimeWorker("session", () -> {
                    callbackStarted.countDown();
                    try {
                        assertTrue(releaseCallback.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                })));
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

        dispatcher.close();
        releaseCallback.countDown();

        assertTrue(runtimeWorker.join(Duration.ofSeconds(1)));
        assertTrue(completion.get().isCompletedExceptionally());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void largeBatchIsSubmittedIncrementallyWithinConcurrencyBound() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 3);
        List<Integer> completed = new ArrayList<>();

        CompletableFuture<Void> completion = dispatcher.submit(
                "session", java.util.stream.IntStream.range(0, 100).boxed().toList(), completed::add);

        assertEquals(3, executor.size());
        assertEquals(new RuntimeResultDispatcher.State(1, 3, 97, 3), dispatcher.state());
        assertFalse(completion.isDone());

        executor.runNext();

        assertEquals(3, executor.size());
        assertEquals(1, completed.size());
        executor.runAll();
        assertTrue(completion.isDone());
        assertEquals(100, completed.size());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 3), dispatcher.state());
    }

    @Test
    void mappedBatchReadsOnlyResultsThatFitTheCurrentConcurrencyWindow() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 3);
        AtomicInteger reads = new AtomicInteger();
        List<Integer> results = new AbstractList<>() {
            @Override
            public Integer get(int index) {
                reads.incrementAndGet();
                return index;
            }

            @Override
            public int size() {
                return 100;
            }
        };
        List<Integer> completed = new ArrayList<>();

        CompletableFuture<Void> completion = dispatcher.submit("session", results, completed::add);

        assertEquals(3, reads.get());
        assertEquals(3, executor.size());

        executor.runNext();

        assertEquals(4, reads.get());
        assertEquals(3, executor.size());
        executor.runAll();
        assertTrue(completion.isDone());
        assertEquals(100, reads.get());
        assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), completed);
    }

    @Test
    void sessionsAndWorkGroupsBothMakeProgress() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        List<String> order = new ArrayList<>();

        CompletableFuture<Void> first = dispatcher.submit("a", List.of("a1", "a2", "a3"), order::add);
        CompletableFuture<Void> second = dispatcher.submit("a", List.of("a-other"), order::add);
        CompletableFuture<Void> third = dispatcher.submit("b", List.of("b1"), order::add);

        executor.runAll();

        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertTrue(third.isDone());
        assertTrue(order.indexOf("a-other") < order.indexOf("a3"));
        assertTrue(order.indexOf("b1") < order.indexOf("a3"));
    }

    @Test
    void rejectionFailsOnlyTheAffectedWorkGroupAndReleasesPermits() {
        AtomicBoolean reject = new AtomicBoolean(true);
        Executor executor = task -> {
            if (reject.getAndSet(false)) {
                throw new RejectedExecutionException("rejected");
            }
            task.run();
        };
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);

        CompletableFuture<Void> rejected = dispatcher.submit("a", () -> {});
        CompletableFuture<Void> accepted = dispatcher.submit("b", () -> {});

        assertTrue(rejected.isCompletedExceptionally());
        assertTrue(accepted.isDone());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void closeFailsPendingWorkWithoutRunningIt() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        CompletableFuture<Void> completion = dispatcher.submit("a", List.of(1, 2), ignored -> {});

        dispatcher.close();

        assertTrue(completion.isCompletedExceptionally());
        executor.runAll();
        assertThrows(Exception.class, completion::get);
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void optionalDiagnosticsCaptureSparseHighWatermarks() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 2, true);

        dispatcher.submit("a", List.of(1, 2, 3), ignored -> {});

        RuntimeResultDispatcher.State queued = dispatcher.state();
        assertEquals(1, queued.maxObservedWorkGroups());
        assertEquals(2, queued.maxObservedActiveResults());
        assertEquals(1, queued.maxObservedPendingResults());

        executor.runAll();

        RuntimeResultDispatcher.State completed = dispatcher.state();
        assertEquals(0, completed.workGroups());
        assertEquals(1, completed.maxObservedWorkGroups());
        assertEquals(2, completed.maxObservedActiveResults());
        assertEquals(1, completed.maxObservedPendingResults());
        assertTrue(completed.maxQueueDwellMillis() >= 0L);
        assertTrue(completed.maxCompletionDurationMillis() >= 0L);
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
