/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.proxy;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded work-conserving scheduler that runs at most one task per exact message segment.
 */
final class SegmentSerialScheduler<T extends SegmentSerialScheduler.Task> {

    interface Task {
        int segment();

        CompletableFuture<Void> completion();

        void admitted();

        void start();

        void fail(Throwable error);
    }

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityAvailable = lock.newCondition();
    private final int maxConcurrent;
    private final int maxOutstanding;
    private final Map<Integer, ArrayDeque<T>> segmentQueues = new HashMap<>();
    private final ArrayDeque<Integer> readySegments = new ArrayDeque<>();
    private final Set<Integer> readySegmentSet = new HashSet<>();
    private final Map<Integer, T> activeTasks = new HashMap<>();
    private final ConcurrentLinkedQueue<T> starts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger startDrain = new AtomicInteger();

    private int outstandingCount;
    private boolean dispatching = true;

    SegmentSerialScheduler(int maxConcurrent, int maxOutstanding) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        if (maxOutstanding < maxConcurrent) {
            throw new IllegalArgumentException("maxOutstanding must be >= maxConcurrent");
        }
        this.maxConcurrent = maxConcurrent;
        this.maxOutstanding = maxOutstanding;
    }

    boolean schedule(T task) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (dispatching && outstandingCount >= maxOutstanding) {
                capacityAvailable.await();
            }
            if (!dispatching) {
                return false;
            }
            outstandingCount++;
            segmentQueues.computeIfAbsent(task.segment(), ignored -> new ArrayDeque<>()).addLast(task);
            task.admitted();
            markReady(task.segment());
            task.completion().whenComplete((ignored, error) -> completed(task));
            collectStarts();
        } finally {
            lock.unlock();
        }
        drainStarts();
        return true;
    }

    void stopDispatching() {
        lock.lock();
        try {
            dispatching = false;
            readySegments.clear();
            readySegmentSet.clear();
            starts.clear();
            capacityAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void completed(T task) {
        lock.lock();
        try {
            ArrayDeque<T> queue = segmentQueues.get(task.segment());
            if (queue == null || !queue.remove(task)) {
                return;
            }
            activeTasks.remove(task.segment(), task);
            outstandingCount--;
            capacityAvailable.signal();
            if (queue.isEmpty()) {
                segmentQueues.remove(task.segment());
                readySegmentSet.remove(task.segment());
                readySegments.remove(task.segment());
            } else {
                markReady(task.segment());
            }
            collectStarts();
        } finally {
            lock.unlock();
        }
        drainStarts();
    }

    private void markReady(int segment) {
        if (dispatching && !activeTasks.containsKey(segment) && readySegmentSet.add(segment)) {
            readySegments.addLast(segment);
        }
    }

    private void collectStarts() {
        while (dispatching && activeTasks.size() < maxConcurrent && !readySegments.isEmpty()) {
            int segment = readySegments.removeFirst();
            readySegmentSet.remove(segment);
            ArrayDeque<T> queue = segmentQueues.get(segment);
            if (queue == null || queue.isEmpty() || activeTasks.containsKey(segment)) {
                continue;
            }
            T task = queue.getFirst();
            activeTasks.put(segment, task);
            starts.add(task);
        }
    }

    private void drainStarts() {
        if (startDrain.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        do {
            T task;
            while ((task = starts.poll()) != null) {
                try {
                    task.start();
                } catch (Throwable e) {
                    task.fail(e);
                }
            }
            missed = startDrain.addAndGet(-missed);
        } while (missed != 0);
    }
}
