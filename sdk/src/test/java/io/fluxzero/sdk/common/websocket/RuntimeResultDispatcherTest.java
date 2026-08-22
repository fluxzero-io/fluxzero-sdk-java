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

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeResultDispatcherTest {

    @Test
    void stagedSubmissionIsAdmittedBeforeCallbackRunsOnExecutor() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        RuntimeIngressController.MessageDispatch submission = dispatcher.submitStaged(
                "session", () -> callbackThread.set(Thread.currentThread()));

        assertTrue(submission.admission().isDone());
        assertFalse(submission.completion().isDone());
        assertEquals(null, callbackThread.get());
        assertEquals(1, executor.size());

        executor.runNext();

        assertTrue(submission.completion().isDone());
        assertEquals(Thread.currentThread(), callbackThread.get());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void uncontendedSubmissionDoesNotTouchSessionFairnessState() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        AtomicInteger sessionHashCalls = new AtomicInteger();
        Object sessionKey = new Object() {
            @Override
            public int hashCode() {
                sessionHashCalls.incrementAndGet();
                return super.hashCode();
            }
        };

        RuntimeIngressController.MessageDispatch submission = dispatcher.submitStaged(sessionKey, () -> {});
        executor.runAll();

        assertTrue(submission.completion().isDone());
        assertEquals(0, sessionHashCalls.get(), "The uncontended path must not create per-session fairness state");
    }

    @Test
    void boundsWorkGroupAdmissionAndPromotesPendingSessionsFairly() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        List<String> completed = new ArrayList<>();
        RuntimeIngressController.MessageDispatch first = dispatcher.submitStaged("a", () -> completed.add("a1"));
        RuntimeIngressController.MessageDispatch second = dispatcher.submitStaged("a", () -> completed.add("a2"));
        RuntimeIngressController.MessageDispatch third = dispatcher.submitStaged("b", () -> completed.add("b1"));
        RuntimeIngressController.MessageDispatch fourth = dispatcher.submitStaged("a", () -> completed.add("a3"));

        assertTrue(first.admission().isDone());
        assertFalse(second.admission().isDone());
        assertFalse(third.admission().isDone());
        assertFalse(fourth.admission().isDone());
        assertEquals(new RuntimeResultDispatcher.State(1, 3, 1, 0, 1), dispatcher.state());

        executor.runAll();

        assertEquals(List.of("a1", "a2", "b1", "a3"), completed);
        assertTrue(second.admission().isDone());
        assertTrue(third.admission().isDone());
        assertTrue(fourth.admission().isDone());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void largeBatchIsSubmittedIncrementallyWithinConcurrencyBound() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 3);
        List<Integer> completed = new ArrayList<>();

        CompletableFuture<Void> completion = dispatcher.submit(
                "session", java.util.stream.IntStream.range(0, 100).boxed().toList(), completed::add);

        assertEquals(3, executor.size());
        assertEquals(new RuntimeResultDispatcher.State(1, 0, 3, 97, 3), dispatcher.state());
        assertFalse(completion.isDone());

        executor.runNext();

        assertEquals(3, executor.size());
        assertEquals(32, completed.size(), "A reused completion worker must yield after bounded work");
        assertFalse(completion.isDone());
        executor.runAll();
        assertTrue(completion.isDone());
        assertEquals(100, completed.size());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 3), dispatcher.state());
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

        assertEquals(0, reads.get(), "Submitting a batch must not read results before a completion worker runs");
        assertEquals(3, executor.size());

        executor.runNext();

        assertEquals(32, reads.get(), "Worker reuse must not materialize the complete result batch");
        assertTrue(reads.get() < results.size());
        assertEquals(3, executor.size());
        executor.runAll();
        assertTrue(completion.isDone());
        assertEquals(100, reads.get());
        assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(),
                     completed.stream().sorted().toList());
    }

    @Test
    void sessionsAndWorkGroupsAllMakeProgress() {
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
        assertEquals(5, order.size());
    }

    @Test
    void manyMaximumTrackingBatchesKeepMakingProgress() throws Exception {
        List<Integer> batch = java.util.stream.IntStream.range(0, 1_024).boxed().toList();
        AtomicInteger completed = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 32)) {
            CompletableFuture<?>[] completions = new CompletableFuture<?>[512];
            for (int i = 0; i < completions.length; i++) {
                completions[i] = dispatcher.submit("session-" + i % 4, batch, ignored -> completed.incrementAndGet());
            }

            CompletableFuture.allOf(completions).get(10, TimeUnit.SECONDS);

            assertEquals(512 * 1_024, completed.get());
            assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 32), dispatcher.state());
        }
    }

    @Test
    void fixedWorkersCompleteConcurrentBatchResultsExactlyOnceWithinBound() throws Exception {
        int resultCount = 10_000;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicIntegerArray completed = new AtomicIntegerArray(resultCount);
        try (ExecutorService executor = Executors.newFixedThreadPool(8);
             RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 3)) {
            CompletableFuture<Void> completion = dispatcher.submit(
                    "session", java.util.stream.IntStream.range(0, resultCount).boxed().toList(), result -> {
                        int currentActive = active.incrementAndGet();
                        maximumActive.accumulateAndGet(currentActive, Math::max);
                        completed.incrementAndGet(result);
                        Thread.onSpinWait();
                        active.decrementAndGet();
                    });

            completion.get(10, TimeUnit.SECONDS);

            assertTrue(maximumActive.get() <= 3);
            for (int i = 0; i < resultCount; i++) {
                assertEquals(1, completed.get(i), "Result callback " + i + " must run exactly once");
            }
            assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 3), dispatcher.state());
        }
    }

    @Test
    void concurrentCallbackFailureWaitsForActiveSiblingsAndPromotesPendingWork() throws Exception {
        CountDownLatch callbacksEntered = new CountDownLatch(3);
        CountDownLatch allowFailure = new CountDownLatch(1);
        CountDownLatch failureReleased = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3);
             RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 3)) {
            CompletableFuture<Void> failing = dispatcher.submit(
                    "a", java.util.stream.IntStream.range(0, 100).boxed().toList(), result -> {
                        callbacksEntered.countDown();
                        await(callbacksEntered);
                        if (result == 0) {
                            await(allowFailure);
                            failureReleased.countDown();
                            throw new IllegalStateException("failed");
                        }
                        await(failureReleased);
                    });
            RuntimeIngressController.MessageDispatch sameSession = dispatcher.submitStaged("a", () -> {});
            RuntimeIngressController.MessageDispatch otherSession = dispatcher.submitStaged("b", () -> {});
            RuntimeIngressController.MessageDispatch waiting = dispatcher.submitStaged("c", () -> {});

            assertTrue(callbacksEntered.await(10, TimeUnit.SECONDS));
            assertFalse(waiting.admission().isDone());
            allowFailure.countDown();
            assertThrows(Exception.class, () -> failing.get(10, TimeUnit.SECONDS));
            CompletableFuture.allOf(sameSession.completion(), otherSession.completion(), waiting.completion())
                    .get(10, TimeUnit.SECONDS);

            assertTrue(waiting.admission().isDone());
            assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 3), dispatcher.state());
        }
    }

    @Test
    void closeDuringConcurrentCallbacksDoesNotStartRetainedResults() throws Exception {
        CountDownLatch callbacksEntered = new CountDownLatch(3);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(3);
             RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 3)) {
            CompletableFuture<Void> completion = dispatcher.submit(
                    "session", java.util.stream.IntStream.range(0, 100).boxed().toList(), ignored -> {
                        executed.incrementAndGet();
                        callbacksEntered.countDown();
                        await(releaseCallbacks);
                    });

            assertTrue(callbacksEntered.await(10, TimeUnit.SECONDS));
            dispatcher.close();
            releaseCallbacks.countDown();
            assertThrows(Exception.class, () -> completion.get(10, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(3, executed.get());
            assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 3), dispatcher.state());
        }
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
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void callbackFailurePromotesTheNextWaitingWorkGroup() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        RuntimeIngressController.MessageDispatch failing = dispatcher.submitStaged(
                "a", () -> { throw new IllegalStateException("failed"); });
        AtomicBoolean completed = new AtomicBoolean();
        RuntimeIngressController.MessageDispatch waiting = dispatcher.submitStaged(
                "b", () -> completed.set(true));

        assertFalse(waiting.admission().isDone());
        executor.runAll();

        assertTrue(failing.completion().isCompletedExceptionally());
        assertTrue(waiting.admission().isDone());
        assertTrue(waiting.completion().isDone());
        assertTrue(completed.get());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 1), dispatcher.state());
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
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 1), dispatcher.state());
    }

    @Test
    void closeFailsWorkWaitingForAdmissionWithoutRunningIt() {
        ManualExecutor executor = new ManualExecutor();
        RuntimeResultDispatcher dispatcher = new RuntimeResultDispatcher(executor, 1);
        RuntimeIngressController.MessageDispatch admitted = dispatcher.submitStaged("a", () -> {});
        RuntimeIngressController.MessageDispatch waiting = dispatcher.submitStaged("b", () -> {});

        assertTrue(admitted.admission().isDone());
        assertFalse(waiting.admission().isDone());

        dispatcher.close();
        executor.runAll();

        assertTrue(admitted.completion().isCompletedExceptionally());
        assertTrue(waiting.admission().isCompletedExceptionally());
        assertTrue(waiting.completion().isCompletedExceptionally());
        assertEquals(new RuntimeResultDispatcher.State(0, 0, 0, 0, 1), dispatcher.state());
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out awaiting concurrent dispatcher test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted awaiting concurrent dispatcher test latch", e);
        }
    }
}
