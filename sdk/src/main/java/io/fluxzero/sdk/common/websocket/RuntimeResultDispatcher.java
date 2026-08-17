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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final List<WorkGroup> workGroups;
    private final ArrayDeque<WorkGroup> readyWorkGroups = new ArrayDeque<>();
    private final ArrayDeque<ResultTask> availableTasks = new ArrayDeque<>();
    private final AtomicInteger schedulingWork = new AtomicInteger();
    private Map<Object, SessionQueue> admissionSessionQueues;
    private ArrayDeque<SessionQueue> admissionReadySessions;
    private int createdTaskCount;
    private int runningTasks;
    private int pendingAdmissions;
    private volatile boolean closed;

    RuntimeResultDispatcher(Executor executor, int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Runtime result completion concurrency must be at least 1");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        this.maxConcurrency = maxConcurrency;
        this.workGroups = new ArrayList<>(maxConcurrency);
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
        markReady(workGroup);
    }

    private void queueForAdmission(WorkGroup workGroup) {
        if (admissionSessionQueues == null) {
            admissionSessionQueues = new HashMap<>();
            admissionReadySessions = new ArrayDeque<>();
        }
        pendingAdmissions++;
        SessionQueue sessionQueue = admissionSessionQueues.computeIfAbsent(
                workGroup.sessionKey, SessionQueue::new);
        sessionQueue.pendingAdmissions.addLast(workGroup);
        markAdmissionReady(sessionQueue);
    }

    private void markReady(WorkGroup workGroup) {
        if (!workGroup.ready && workGroup.failure == null && workGroup.hasUnscheduled()) {
            workGroup.ready = true;
            readyWorkGroups.addLast(workGroup);
        }
    }

    private void markAdmissionReady(SessionQueue sessionQueue) {
        if (!sessionQueue.admissionReady && !sessionQueue.pendingAdmissions.isEmpty()) {
            sessionQueue.admissionReady = true;
            admissionReadySessions.addLast(sessionQueue);
        }
    }

    private WorkGroup admitNext() {
        while (!closed && workGroups.size() < maxConcurrency && pendingAdmissions > 0
               && admissionReadySessions != null && !admissionReadySessions.isEmpty()) {
            SessionQueue sessionQueue = admissionReadySessions.removeFirst();
            sessionQueue.admissionReady = false;
            WorkGroup workGroup = sessionQueue.pendingAdmissions.pollFirst();
            if (workGroup != null) {
                pendingAdmissions--;
                admit(workGroup);
                removeSessionIfIdle(sessionQueue);
                markAdmissionReady(sessionQueue);
                return workGroup;
            }
            removeSessionIfIdle(sessionQueue);
            markAdmissionReady(sessionQueue);
        }
        return null;
    }

    private ResultTask takeAvailable(ResultTask reusableTask) {
        while (!closed && runningTasks < maxConcurrency && !readyWorkGroups.isEmpty()) {
            WorkGroup workGroup = readyWorkGroups.removeFirst();
            workGroup.ready = false;
            if (workGroup.failure != null) {
                continue;
            }
            int resultIndex = workGroup.claimNext();
            if (resultIndex < 0) {
                continue;
            }
            workGroup.activeTasks++;
            runningTasks++;
            ResultTask task = reusableTask == null ? pollAvailableTask() : reusableTask;
            if (task == null) {
                if (createdTaskCount >= maxConcurrency) {
                    throw new IllegalStateException("Missing reusable runtime result task");
                }
                task = new ResultTask();
                createdTaskCount++;
            }
            task.workGroup = workGroup;
            task.resultIndex = resultIndex;
            markReady(workGroup);
            return task;
        }
        return null;
    }

    private void removeSessionIfIdle(SessionQueue sessionQueue) {
        if (!sessionQueue.admissionReady && sessionQueue.pendingAdmissions.isEmpty()) {
            admissionSessionQueues.remove(sessionQueue.sessionKey, sessionQueue);
            if (admissionSessionQueues.isEmpty()) {
                admissionSessionQueues = null;
                admissionReadySessions = null;
            }
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
        WorkGroup workGroup = task.workGroup;
        for (int processed = 1; ; processed++) {
            Throwable failure = null;
            try {
                if (shouldRun(workGroup)) {
                    workGroup.run(task.resultIndex);
                }
            } catch (Throwable e) {
                failure = e;
            }
            if (failure == null && processed < MAX_SYNCHRONOUS_RESULTS_PER_TASK && claimNext(task)) {
                continue;
            }
            if (!complete(task, failure, processed < MAX_SYNCHRONOUS_RESULTS_PER_TASK)) {
                return;
            }
            workGroup = task.workGroup;
        }
    }

    private boolean shouldRun(WorkGroup workGroup) {
        return !closed && workGroup.failure == null;
    }

    private boolean claimNext(ResultTask task) {
        WorkGroup workGroup = task.workGroup;
        if (!shouldRun(workGroup)) {
            return false;
        }
        int resultIndex = workGroup.claimNext();
        if (resultIndex < 0) {
            return false;
        }
        task.resultIndex = resultIndex;
        return true;
    }

    private boolean complete(ResultTask task, Throwable failure, boolean reuseWorker) {
        WorkGroup workGroup = task.workGroup;
        WorkGroup completed = null;
        WorkGroup admitted = null;
        boolean reused = false;
        synchronized (this) {
            runningTasks--;
            workGroup.activeTasks--;
            task.workGroup = null;
            task.resultIndex = -1;
            if (failure != null && workGroup.failure == null) {
                workGroup.failure = failure;
                workGroup.discardUnscheduled();
            }
            if (workGroup.activeTasks == 0 && (workGroup.failure != null || !workGroup.hasUnscheduled())) {
                workGroups.remove(workGroup);
                completed = workGroup;
                admitted = admitNext();
            }
            if (reuseWorker && takeAvailable(task) != null) {
                reused = true;
            } else {
                availableTasks.addLast(task);
            }
        }
        if (completed != null) {
            if (completed.failure == null) {
                completed.complete(null);
            } else {
                completed.completeExceptionally(completed.failure);
            }
        }
        if (admitted != null) {
            admitted.admission.complete(null);
        }
        if (reused) {
            return true;
        }
        scheduleAvailable();
        return false;
    }

    private ResultTask pollAvailableTask() {
        return availableTasks.pollFirst();
    }

    synchronized State state() {
        int unscheduledResults = 0;
        for (WorkGroup workGroup : workGroups) {
            unscheduledResults += workGroup.remainingUnscheduled();
        }
        return new State(workGroups.size(), pendingAdmissions, runningTasks, unscheduledResults, maxConcurrency);
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
            workGroups.forEach(workGroup -> {
                workGroup.failure = failure;
                workGroup.discardUnscheduled();
            });
            admitted = List.copyOf(workGroups);
            pendingAdmission = new ArrayList<>(pendingAdmissions);
            if (admissionSessionQueues != null) {
                admissionSessionQueues.values().forEach(
                        sessionQueue -> pendingAdmission.addAll(sessionQueue.pendingAdmissions));
                pendingAdmission.forEach(workGroup -> workGroup.failure = failure);
            }
            workGroups.clear();
            readyWorkGroups.clear();
            if (admissionSessionQueues != null) {
                admissionSessionQueues.clear();
            }
            if (admissionReadySessions != null) {
                admissionReadySessions.clear();
            }
            admissionSessionQueues = null;
            admissionReadySessions = null;
            pendingAdmissions = 0;
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
        private final Object sessionKey;
        private final ArrayDeque<WorkGroup> pendingAdmissions = new ArrayDeque<>();
        private boolean admissionReady;

        private SessionQueue(Object sessionKey) {
            this.sessionKey = sessionKey;
        }
    }

    private final class WorkGroup extends CompletableFuture<Void>
            implements RuntimeIngressController.MessageDispatch {
        private final Object sessionKey;
        private final Runnable singleCallback;
        private final List<?> items;
        private final Consumer<Object> itemHandler;
        private final AtomicInteger nextIndex = new AtomicInteger();
        private CompletableFuture<Void> admission;
        private int activeTasks;
        private boolean ready;
        private volatile Throwable failure;

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

        private int claimNext() {
            int index = nextIndex.getAndIncrement();
            return index < size() ? index : -1;
        }

        private boolean hasUnscheduled() {
            return nextIndex.get() < size();
        }

        private void discardUnscheduled() {
            nextIndex.set(size());
        }

        private int remainingUnscheduled() {
            return Math.max(0, size() - Math.min(nextIndex.get(), size()));
        }

        private int size() {
            return singleCallback == null ? items.size() : 1;
        }

        private void run(int resultIndex) {
            if (singleCallback == null) {
                itemHandler.accept(items.get(resultIndex));
            } else {
                singleCallback.run();
            }
        }
    }

    private final class ResultTask implements Runnable {
        private WorkGroup workGroup;
        private int resultIndex = -1;

        @Override
        public void run() {
            RuntimeResultDispatcher.this.run(this);
        }
    }
}
