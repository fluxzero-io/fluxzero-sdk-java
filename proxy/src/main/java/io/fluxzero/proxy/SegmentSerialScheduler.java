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
import java.util.LinkedHashSet;
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

        default Object origin() {
            return null;
        }

        CompletableFuture<Void> completion();

        void admitted();

        void start();

        void fail(Throwable error);
    }

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityAvailable = lock.newCondition();
    private final int maxConcurrent;
    private final int maxOutstanding;
    private final int maxConcurrentPerOrigin;
    private final Map<Object, Integer> activeOrigins = new HashMap<>();
    private final Map<Object, LinkedHashSet<Integer>> blockedOrigins = new HashMap<>();
    private final Map<Integer, Object> blockedSegments = new HashMap<>();
    private final Map<Integer, ArrayDeque<T>> segmentQueues = new HashMap<>();
    private final ArrayDeque<Integer> readySegments = new ArrayDeque<>();
    private final Set<Integer> readySegmentSet = new HashSet<>();
    private final Map<Integer, T> activeTasks = new HashMap<>();
    private final ConcurrentLinkedQueue<T> starts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger startDrain = new AtomicInteger();

    private int outstandingCount;
    private boolean dispatching = true;

    SegmentSerialScheduler(int maxConcurrent, int maxOutstanding) {
        this(maxConcurrent, maxOutstanding, 0);
    }

    SegmentSerialScheduler(int maxConcurrent, int maxOutstanding, int maxConcurrentPerOrigin) {
        if (maxConcurrentPerOrigin < 0) {
            throw new IllegalArgumentException("maxConcurrentPerOrigin must be >= 0");
        }
        this.maxConcurrentPerOrigin = maxConcurrentPerOrigin;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        if (maxOutstanding < maxConcurrent) {
            throw new IllegalArgumentException("maxOutstanding must be >= maxConcurrent");
        }
        this.maxConcurrent = maxConcurrent;
        this.maxOutstanding = maxOutstanding;
    }

    boolean originLimited() {
        return maxConcurrentPerOrigin > 0;
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
            blockedOrigins.clear();
            blockedSegments.clear();
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
            if (queue == null) {
                return;
            }
            boolean wasHead = queue.peekFirst() == task;
            if (!queue.remove(task)) {
                return;
            }
            if (activeTasks.remove(task.segment(), task) && maxConcurrentPerOrigin > 0 && task.origin() != null) {
                activeOrigins.computeIfPresent(task.origin(), (origin, count) -> count == 1 ? null : count - 1);
            }
            if (maxConcurrentPerOrigin > 0 && wasHead) {
                removeBlocked(task.segment());
            }
            outstandingCount--;
            capacityAvailable.signal();
            if (queue.isEmpty()) {
                segmentQueues.remove(task.segment());
                readySegmentSet.remove(task.segment());
                readySegments.remove(task.segment());
            }
            if (maxConcurrentPerOrigin > 0 && wasHead && task.origin() != null) {
                // A ready head may be cancelled before consuming its origin's newly available slot.
                wakeOrigin(task.origin());
            }
            if (!queue.isEmpty()) {
                markReady(task.segment());
            }
            collectStarts();
        } finally {
            lock.unlock();
        }
        drainStarts();
    }

    private void markReady(int segment) {
        if (!dispatching || activeTasks.containsKey(segment) || readySegmentSet.contains(segment)
            || (maxConcurrentPerOrigin > 0 && blockedSegments.containsKey(segment))) {
            return;
        }
        if (maxConcurrentPerOrigin > 0) {
            T head = segmentQueues.get(segment).getFirst();
            if (head.origin() != null && activeOrigins.getOrDefault(head.origin(), 0) >= maxConcurrentPerOrigin) {
                blockedOrigins.computeIfAbsent(head.origin(), ignored -> new LinkedHashSet<>()).add(segment);
                blockedSegments.put(segment, head.origin());
                return;
            }
        }
        readySegmentSet.add(segment);
        readySegments.addLast(segment);
    }

    private void removeBlocked(int segment) {
        Object origin = blockedSegments.remove(segment);
        if (origin != null) {
            Set<Integer> blocked = blockedOrigins.get(origin);
            if (blocked != null && blocked.remove(segment) && blocked.isEmpty()) {
                blockedOrigins.remove(origin);
            }
        }
    }

    private void wakeOrigin(Object origin) {
        Set<Integer> blocked = blockedOrigins.get(origin);
        if (blocked == null || !dispatching) {
            return;
        }
        int available = maxConcurrentPerOrigin - activeOrigins.getOrDefault(origin, 0);
        var iterator = blocked.iterator();
        while (available > 0 && iterator.hasNext()) {
            int segment = iterator.next();
            iterator.remove();
            blockedSegments.remove(segment);
            if (segmentQueues.containsKey(segment) && !activeTasks.containsKey(segment)
                && readySegmentSet.add(segment)) {
                readySegments.addLast(segment);
                available--;
            }
        }
        if (blocked.isEmpty()) {
            blockedOrigins.remove(origin);
        }
    }

    private void collectStarts() {
        int candidates = readySegments.size();
        while (dispatching && activeTasks.size() < maxConcurrent && candidates-- > 0 && !readySegments.isEmpty()) {
            int segment = readySegments.removeFirst();
            readySegmentSet.remove(segment);
            ArrayDeque<T> queue = segmentQueues.get(segment);
            if (queue == null || queue.isEmpty() || activeTasks.containsKey(segment)) {
                continue;
            }
            T task = queue.getFirst();
            if (maxConcurrentPerOrigin > 0 && task.origin() != null) {
                int active = activeOrigins.getOrDefault(task.origin(), 0);
                if (active >= maxConcurrentPerOrigin) {
                    markReady(segment);
                    continue;
                }
                activeOrigins.put(task.origin(), active + 1);
            }
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
