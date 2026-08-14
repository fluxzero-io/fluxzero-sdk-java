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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bounded client-wide dispatcher for completing runtime results.
 *
 * <p>One submitted work group represents one retained runtime transport message. At most {@code maxConcurrency}
 * groups are admitted at once and at most the same number of result callbacks are active. Additional groups wait for
 * admission, fairly between sessions. Callbacks always run on the backing executor, so decode workers never execute
 * customer continuations. A large result batch is submitted incrementally and cannot create one task per result up
 * front.</p>
 */
final class RuntimeResultDispatcher implements AutoCloseable {
    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);
    private static final int MAX_SYNCHRONOUS_RESULTS_PER_TASK = 32;
    private final Executor executor;
    private final int maxConcurrency;
    private final Map<Object, SessionQueue> sessionQueues = new HashMap<>();
    private final ArrayDeque<Object> readySessions = new ArrayDeque<>();
    private final ArrayDeque<Object> admissionReadySessions = new ArrayDeque<>();
    private final ArrayDeque<ResultTask> availableTasks = new ArrayDeque<>();
    private final Set<WorkGroup> workGroups = new HashSet<>();
    private final Set<WorkGroup> pendingAdmissionWorkGroups = new HashSet<>();
    private final AtomicInteger schedulingWork = new AtomicInteger();
    private int createdTaskCount;
    private int activeTasks;
    private int pendingResults;
    private boolean closed;

    RuntimeResultDispatcher(Executor executor, int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Runtime result completion concurrency must be at least 1");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        this.maxConcurrency = maxConcurrency;
    }

    CompletableFuture<Void> submit(Object sessionKey, Runnable result) {
        return submitStaged(sessionKey, result).completion();
    }

    RuntimeIngressController.MessageDispatch submitStaged(Object sessionKey, Runnable result) {
        return submitStaged(new WorkGroup(sessionKey, result));
    }

    <T> CompletableFuture<Void> submit(Object sessionKey, List<T> results, Consumer<? super T> resultHandler) {
        return submitStaged(sessionKey, results, resultHandler).completion();
    }

    <T> RuntimeIngressController.MessageDispatch submitStaged(
            Object sessionKey, List<T> results, Consumer<? super T> resultHandler) {
        if (results.isEmpty()) {
            return RuntimeIngressController.MessageDispatch.admitted(COMPLETED);
        }
        return submitStaged(new WorkGroup(sessionKey, results, resultHandler));
    }

    private RuntimeIngressController.MessageDispatch submitStaged(WorkGroup workGroup) {
        boolean admitted;
        synchronized (this) {
            if (closed) {
                return RuntimeIngressController.MessageDispatch.failed(new ClosedChannelException());
            }
            admitted = workGroups.size() < maxConcurrency;
            if (admitted) {
                workGroup.admission = COMPLETED;
                admit(workGroup);
            } else {
                workGroup.admission = new CompletableFuture<>();
                queueForAdmission(workGroup);
            }
        }
        if (admitted) {
            scheduleAvailable();
        }
        return workGroup;
    }

    private void admit(WorkGroup workGroup) {
        workGroups.add(workGroup);
        pendingResults += workGroup.size();
        SessionQueue sessionQueue = sessionQueues.computeIfAbsent(
                workGroup.sessionKey, ignored -> new SessionQueue());
        sessionQueue.workGroups.addLast(workGroup);
        markReady(workGroup.sessionKey, sessionQueue);
    }

    private void queueForAdmission(WorkGroup workGroup) {
        pendingAdmissionWorkGroups.add(workGroup);
        SessionQueue sessionQueue = sessionQueues.computeIfAbsent(
                workGroup.sessionKey, ignored -> new SessionQueue());
        sessionQueue.pendingAdmissions.addLast(workGroup);
        markAdmissionReady(workGroup.sessionKey, sessionQueue);
    }

    private void markReady(Object sessionKey, SessionQueue sessionQueue) {
        if (!sessionQueue.ready && !sessionQueue.workGroups.isEmpty()) {
            sessionQueue.ready = true;
            readySessions.addLast(sessionKey);
        }
    }

    private void markAdmissionReady(Object sessionKey, SessionQueue sessionQueue) {
        if (!sessionQueue.admissionReady && !sessionQueue.pendingAdmissions.isEmpty()) {
            sessionQueue.admissionReady = true;
            admissionReadySessions.addLast(sessionKey);
        }
    }

    private WorkGroup admitNext() {
        while (!closed && workGroups.size() < maxConcurrency && !admissionReadySessions.isEmpty()) {
            Object sessionKey = admissionReadySessions.removeFirst();
            SessionQueue sessionQueue = sessionQueues.get(sessionKey);
            if (sessionQueue == null) {
                continue;
            }
            sessionQueue.admissionReady = false;
            WorkGroup workGroup = sessionQueue.pendingAdmissions.pollFirst();
            if (workGroup != null && pendingAdmissionWorkGroups.remove(workGroup)) {
                admit(workGroup);
                removeSessionIfIdle(sessionKey, sessionQueue);
                markAdmissionReady(sessionKey, sessionQueue);
                return workGroup;
            }
            removeSessionIfIdle(sessionKey, sessionQueue);
            markAdmissionReady(sessionKey, sessionQueue);
        }
        return null;
    }

    private ResultTask takeAvailable(ResultTask reusableTask) {
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
            ResultTask task = reusableTask == null ? pollAvailableTask() : reusableTask;
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
        if (sessionQueue.removeWhenIdle && !sessionQueue.ready && !sessionQueue.admissionReady
            && sessionQueue.workGroups.isEmpty() && sessionQueue.pendingAdmissions.isEmpty()) {
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
                    task = takeAvailable(null);
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
            complete(task, e, false);
        }
    }

    private void run(ResultTask task) {
        for (int processed = 1; ; processed++) {
            WorkGroup workGroup = task.workGroup;
            Runnable callback = task.callback;
            Object item = task.item;
            Consumer<Object> itemHandler = task.itemHandler;
            Throwable failure = null;
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
            if (!complete(task, failure, processed < MAX_SYNCHRONOUS_RESULTS_PER_TASK)) {
                return;
            }
        }
    }

    private synchronized boolean shouldRun(WorkGroup workGroup) {
        return !closed && workGroup.failure == null;
    }

    private boolean complete(ResultTask task, Throwable failure, boolean reuseWorker) {
        WorkGroup workGroup = task.workGroup;
        WorkGroup completed = null;
        WorkGroup admitted = null;
        synchronized (this) {
            activeTasks--;
            workGroup.activeTasks--;
            task.workGroup = null;
            task.callback = null;
            task.item = null;
            task.itemHandler = null;
            makeAvailable(task);
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
                completed = workGroup;
                admitted = admitNext();
            }
        }
        if (completed != null) {
            if (workGroup.failure == null) {
                completed.complete(null);
            } else {
                completed.completeExceptionally(workGroup.failure);
            }
        }
        if (admitted != null) {
            admitted.admission.complete(null);
        }
        if (reuseWorker && reuse(task)) {
            return true;
        }
        scheduleAvailable();
        return false;
    }

    private boolean reuse(ResultTask task) {
        synchronized (this) {
            if (!task.available) {
                return false;
            }
            task.available = false;
            if (takeAvailable(task) == null) {
                makeAvailable(task);
                return false;
            }
            return true;
        }
    }

    private ResultTask pollAvailableTask() {
        ResultTask task;
        while ((task = availableTasks.pollFirst()) != null) {
            task.queued = false;
            if (task.available) {
                task.available = false;
                return task;
            }
        }
        return null;
    }

    private void makeAvailable(ResultTask task) {
        task.available = true;
        if (!task.queued) {
            task.queued = true;
            availableTasks.addLast(task);
        }
    }

    synchronized State state() {
        return new State(workGroups.size(), pendingAdmissionWorkGroups.size(), activeTasks, pendingResults,
                         maxConcurrency);
    }

    @Override
    public void close() {
        List<WorkGroup> admitted;
        List<WorkGroup> pendingAdmission;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ClosedChannelException failure = new ClosedChannelException();
            workGroups.forEach(workGroup -> workGroup.failure = failure);
            pendingAdmissionWorkGroups.forEach(workGroup -> workGroup.failure = failure);
            admitted = List.copyOf(workGroups);
            pendingAdmission = List.copyOf(pendingAdmissionWorkGroups);
            workGroups.clear();
            pendingAdmissionWorkGroups.clear();
            sessionQueues.clear();
            readySessions.clear();
            admissionReadySessions.clear();
            pendingResults = 0;
        }
        admitted.forEach(workGroup -> workGroup.completeExceptionally(new ClosedChannelException()));
        pendingAdmission.forEach(workGroup -> {
            workGroup.admission.completeExceptionally(new ClosedChannelException());
            workGroup.completeExceptionally(new ClosedChannelException());
        });
    }

    record State(int workGroups, int pendingAdmissions, int activeResults, int pendingResults, int maxConcurrency) {
    }

    private final class SessionQueue {
        private final ArrayDeque<WorkGroup> workGroups = new ArrayDeque<>();
        private final ArrayDeque<WorkGroup> pendingAdmissions = new ArrayDeque<>();
        private boolean ready;
        private boolean admissionReady;
        private boolean removeWhenIdle;
    }

    private final class WorkGroup extends CompletableFuture<Void>
            implements RuntimeIngressController.MessageDispatch {
        private final Object sessionKey;
        private final Runnable singleCallback;
        private final List<?> items;
        private final Consumer<Object> itemHandler;
        private CompletableFuture<Void> admission;
        private int nextIndex;
        private int activeTasks;
        private Throwable failure;

        private WorkGroup(Object sessionKey, Runnable callback) {
            this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
            this.singleCallback = Objects.requireNonNull(callback, "callback");
            this.items = null;
            this.itemHandler = null;
        }

        @SuppressWarnings("unchecked")
        private <T> WorkGroup(Object sessionKey, List<T> items, Consumer<? super T> itemHandler) {
            this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
            this.singleCallback = null;
            this.items = Objects.requireNonNull(items, "items");
            this.itemHandler = (Consumer<Object>) Objects.requireNonNull(itemHandler, "itemHandler");
        }

        @Override
        public CompletableFuture<Void> admission() {
            return admission;
        }

        @Override
        public CompletableFuture<Void> completion() {
            return this;
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
        private boolean available;
        private boolean queued;

        @Override
        public void run() {
            RuntimeResultDispatcher.this.run(this);
        }
    }
}
