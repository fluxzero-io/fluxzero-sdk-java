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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

        assertEquals(3, reads.get());
        assertEquals(3, executor.size());

        executor.runNext();

        assertEquals(35, reads.get(), "Worker reuse must not materialize the complete result batch");
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
}
