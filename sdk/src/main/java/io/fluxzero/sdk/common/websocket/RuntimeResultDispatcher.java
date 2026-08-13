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

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Bounded client-wide dispatcher for completing runtime results.
 *
 * <p>One submitted work group represents one retained runtime transport message. Groups are scheduled round-robin
 * between sessions and within each session, while at most {@code maxConcurrency} result callbacks are submitted to the
 * backing executor. A large result batch therefore cannot create an executor task for every result up front.</p>
 */
final class RuntimeResultDispatcher implements AutoCloseable {
    private final Executor executor;
    private final int maxConcurrency;
    private final boolean diagnosticsEnabled;
    private final Map<Object, SessionQueue> sessionQueues = new HashMap<>();
    private final ArrayDeque<Object> readySessions = new ArrayDeque<>();
    private final ArrayDeque<ResultTask> availableTasks = new ArrayDeque<>();
    private final Set<WorkGroup> workGroups = new LinkedHashSet<>();
    private final AtomicInteger schedulingWork = new AtomicInteger();
    private int createdTaskCount;
    private int activeTasks;
    private int pendingResults;
    private int maxObservedWorkGroups;
    private int maxObservedActiveResults;
    private int maxObservedPendingResults;
    private long maxQueueDwellMillis;
    private long maxCompletionDurationMillis;
    private boolean closed;

    RuntimeResultDispatcher(Executor executor, int maxConcurrency) {
        this(executor, maxConcurrency, false);
    }

    RuntimeResultDispatcher(Executor executor, int maxConcurrency, boolean diagnosticsEnabled) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Runtime result completion concurrency must be at least 1");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        this.maxConcurrency = maxConcurrency;
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    CompletableFuture<Void> submit(Object sessionKey, Runnable result) {
        return submit(new WorkGroup(sessionKey, result));
    }

    <T> CompletableFuture<Void> submit(Object sessionKey, List<T> results, Consumer<? super T> resultHandler) {
        if (results.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return submit(new WorkGroup(sessionKey, results, resultHandler));
    }

    private CompletableFuture<Void> submit(WorkGroup workGroup) {
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new ClosedChannelException());
            }
            workGroups.add(workGroup);
            pendingResults += workGroup.size();
            SessionQueue sessionQueue = sessionQueues.computeIfAbsent(
                    workGroup.sessionKey, ignored -> new SessionQueue());
            sessionQueue.workGroups.addLast(workGroup);
            markReady(workGroup.sessionKey, sessionQueue);
            if (diagnosticsEnabled) {
                maxObservedWorkGroups = Math.max(maxObservedWorkGroups, workGroups.size());
            }
        }
        scheduleAvailable();
        return workGroup.completion;
    }

    private void markReady(Object sessionKey, SessionQueue sessionQueue) {
        if (!sessionQueue.ready && !sessionQueue.workGroups.isEmpty()) {
            sessionQueue.ready = true;
            readySessions.addLast(sessionKey);
        }
    }

    private ResultTask takeAvailable() {
        while (!closed && activeTasks < maxConcurrency && !readySessions.isEmpty()) {
            Object sessionKey = readySessions.removeFirst();
            SessionQueue sessionQueue = sessionQueues.get(sessionKey);
            if (sessionQueue == null) {
                continue;
            }
            sessionQueue.ready = false;
            WorkGroup workGroup = sessionQueue.workGroups.pollFirst();
            if (workGroup == null || workGroup.failure != null) {
                removeSessionIfIdle(sessionKey, sessionQueue);
                markReady(sessionKey, sessionQueue);
                continue;
            }
            pendingResults--;
            workGroup.activeTasks++;
            activeTasks++;
            ResultTask task = availableTasks.pollFirst();
            if (task == null) {
                if (createdTaskCount >= maxConcurrency) {
                    throw new IllegalStateException("Missing reusable runtime result task");
                }
                task = new ResultTask();
                createdTaskCount++;
            }
            task.workGroup = workGroup;
            workGroup.assignNext(task);
            if (workGroup.hasUnscheduled()) {
                sessionQueue.workGroups.addLast(workGroup);
            }
            removeSessionIfIdle(sessionKey, sessionQueue);
            markReady(sessionKey, sessionQueue);
            return task;
        }
        return null;
    }

    private void removeSessionIfIdle(Object sessionKey, SessionQueue sessionQueue) {
        if (sessionQueue.removeWhenIdle && !sessionQueue.ready && sessionQueue.workGroups.isEmpty()) {
            sessionQueues.remove(sessionKey, sessionQueue);
        }
    }

    synchronized void releaseSession(Object sessionKey) {
        SessionQueue sessionQueue = sessionQueues.get(sessionKey);
        if (sessionQueue != null) {
            sessionQueue.removeWhenIdle = true;
            removeSessionIfIdle(sessionKey, sessionQueue);
        }
    }

    private void scheduleAvailable() {
        if (schedulingWork.getAndIncrement() != 0) {
            return;
        }
        int completedSchedulingRounds = 1;
        do {
            ResultTask task;
            while (true) {
                synchronized (this) {
                    task = takeAvailable();
                    if (task == null) {
                        updateHighWatermarks();
                    }
                }
                if (task == null) {
                    break;
                }
                execute(task);
            }
            completedSchedulingRounds = schedulingWork.addAndGet(-completedSchedulingRounds);
        } while (completedSchedulingRounds != 0);
    }

    private void execute(ResultTask task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            complete(task, e);
        }
    }

    private void run(ResultTask task) {
        WorkGroup workGroup = task.workGroup;
        Runnable callback = task.callback;
        Object item = task.item;
        Consumer<Object> itemHandler = task.itemHandler;
        Throwable failure = null;
        long startedNanos = diagnosticsEnabled ? System.nanoTime() : 0L;
        if (diagnosticsEnabled) {
            synchronized (this) {
                maxQueueDwellMillis = Math.max(
                        maxQueueDwellMillis, NANOSECONDS.toMillis(startedNanos - workGroup.createdNanos));
            }
        }
        try {
            if (shouldRun(workGroup)) {
                if (callback == null) {
                    itemHandler.accept(item);
                } else {
                    callback.run();
                }
            }
        } catch (Throwable e) {
            failure = e;
        }
        if (diagnosticsEnabled) {
            synchronized (this) {
                maxCompletionDurationMillis = Math.max(
                        maxCompletionDurationMillis,
                        NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            }
        }
        complete(task, failure);
    }

    private synchronized boolean shouldRun(WorkGroup workGroup) {
        return !closed && workGroup.failure == null;
    }

    private void complete(ResultTask task, Throwable failure) {
        WorkGroup workGroup = task.workGroup;
        CompletableFuture<Void> completed = null;
        synchronized (this) {
            activeTasks--;
            workGroup.activeTasks--;
            task.workGroup = null;
            task.callback = null;
            task.item = null;
            task.itemHandler = null;
            availableTasks.addLast(task);
            if (failure != null && workGroup.failure == null) {
                workGroup.failure = failure;
                pendingResults -= workGroup.remaining();
                SessionQueue sessionQueue = sessionQueues.get(workGroup.sessionKey);
                if (sessionQueue != null) {
                    sessionQueue.workGroups.removeIf(candidate -> candidate == workGroup);
                    removeSessionIfIdle(workGroup.sessionKey, sessionQueue);
                }
            }
            if (workGroup.activeTasks == 0 && (workGroup.failure != null || !workGroup.hasUnscheduled())) {
                workGroups.remove(workGroup);
                completed = workGroup.completion;
            }
            updateHighWatermarks();
        }
        if (completed != null) {
            if (workGroup.failure == null) {
                completed.complete(null);
            } else {
                completed.completeExceptionally(workGroup.failure);
            }
        }
        scheduleAvailable();
    }

    synchronized State state() {
        long oldestWorkGroupAgeMillis = !diagnosticsEnabled || workGroups.isEmpty() ? 0L
                : NANOSECONDS.toMillis(Math.max(0L, System.nanoTime()
                        - workGroups.stream().mapToLong(workGroup -> workGroup.createdNanos).min().orElse(0L)));
        return new State(workGroups.size(), activeTasks, pendingResults, maxConcurrency,
                         oldestWorkGroupAgeMillis, maxQueueDwellMillis, maxCompletionDurationMillis,
                         maxObservedWorkGroups, maxObservedActiveResults, maxObservedPendingResults);
    }

    private void updateHighWatermarks() {
        if (diagnosticsEnabled) {
            maxObservedWorkGroups = Math.max(maxObservedWorkGroups, workGroups.size());
            maxObservedActiveResults = Math.max(maxObservedActiveResults, activeTasks);
            maxObservedPendingResults = Math.max(maxObservedPendingResults, pendingResults);
        }
    }

    @Override
    public void close() {
        List<CompletableFuture<Void>> completions;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ClosedChannelException failure = new ClosedChannelException();
            workGroups.forEach(workGroup -> workGroup.failure = failure);
            completions = workGroups.stream().map(workGroup -> workGroup.completion).toList();
            workGroups.clear();
            sessionQueues.clear();
            readySessions.clear();
            pendingResults = 0;
        }
        completions.forEach(completion -> completion.completeExceptionally(new ClosedChannelException()));
    }

    record State(int workGroups, int activeResults, int pendingResults, int maxConcurrency,
                 long oldestWorkGroupAgeMillis, long maxQueueDwellMillis, long maxCompletionDurationMillis,
                 int maxObservedWorkGroups, int maxObservedActiveResults, int maxObservedPendingResults) {
        State(int workGroups, int activeResults, int pendingResults, int maxConcurrency) {
            this(workGroups, activeResults, pendingResults, maxConcurrency,
                 0L, 0L, 0L, 0, 0, 0);
        }
    }

    private final class SessionQueue {
        private final ArrayDeque<WorkGroup> workGroups = new ArrayDeque<>();
        private boolean ready;
        private boolean removeWhenIdle;
    }

    private final class WorkGroup {
        private final Object sessionKey;
        private final Runnable singleCallback;
        private final List<?> items;
        private final Consumer<Object> itemHandler;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private int nextIndex;
        private int activeTasks;
        private Throwable failure;
        private final long createdNanos;

        private WorkGroup(Object sessionKey, Runnable callback) {
            this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
            this.singleCallback = Objects.requireNonNull(callback, "callback");
            this.items = null;
            this.itemHandler = null;
            this.createdNanos = diagnosticsEnabled ? System.nanoTime() : 0L;
        }

        @SuppressWarnings("unchecked")
        private <T> WorkGroup(Object sessionKey, List<T> items, Consumer<? super T> itemHandler) {
            this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
            this.singleCallback = null;
            this.items = Objects.requireNonNull(items, "items");
            this.itemHandler = (Consumer<Object>) Objects.requireNonNull(itemHandler, "itemHandler");
            this.createdNanos = diagnosticsEnabled ? System.nanoTime() : 0L;
        }

        private void assignNext(ResultTask task) {
            if (singleCallback != null) {
                if (nextIndex++ != 0) {
                    throw new IllegalStateException("Single runtime result callback already scheduled");
                }
                task.callback = singleCallback;
            } else {
                task.item = items.get(nextIndex++);
                task.itemHandler = itemHandler;
            }
        }

        private boolean hasUnscheduled() {
            return nextIndex < size();
        }

        private int remaining() {
            return size() - nextIndex;
        }

        private int size() {
            return singleCallback == null ? items.size() : 1;
        }
    }

    private final class ResultTask implements Runnable {
        private WorkGroup workGroup;
        private Runnable callback;
        private Object item;
        private Consumer<Object> itemHandler;

        @Override
        public void run() {
            RuntimeResultDispatcher.this.run(this);
        }
    }
}
