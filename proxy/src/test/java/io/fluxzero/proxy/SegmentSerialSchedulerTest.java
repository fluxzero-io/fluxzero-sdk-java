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

package io.fluxzero.proxy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SegmentSerialSchedulerTest {
    @Test
    void saturatedOriginDoesNotConsumeOtherGlobalSlotsAndSegmentOrderingIsPreserved() throws Exception {
        var scheduler = new SegmentSerialScheduler<Task>(3, 10, 1);
        List<String> started = new ArrayList<>();
        Task a1 = new Task(1, "a", "a1", started);
        Task a2 = new Task(2, "a", "a2", started);
        Task blockedB = new Task(2, "b", "b2", started);
        Task b1 = new Task(3, "b", "b1", started);
        for (Task task : List.of(a1, a2, blockedB, b1)) {
            assertTrue(scheduler.schedule(task));
        }
        assertEquals(List.of("a1", "b1"), started);
        a1.completion.complete(null);
        assertEquals(List.of("a1", "b1", "a2"), started);
        a2.completion.complete(null);
        assertEquals(List.of("a1", "b1", "a2"), started);
        b1.completion.complete(null);
        assertEquals(List.of("a1", "b1", "a2", "b2"), started);
        blockedB.completion.complete(null);
    }

    @Test
    void cancellingQueuedTaskDoesNotReleaseActiveOriginPermit() throws Exception {
        var scheduler = new SegmentSerialScheduler<Task>(3, 10, 1);
        List<String> started = new ArrayList<>();
        Task active = new Task(1, "a", "active", started);
        Task cancelled = new Task(2, "a", "cancelled", started);
        Task next = new Task(3, "a", "next", started);
        scheduler.schedule(active);
        scheduler.schedule(cancelled);
        scheduler.schedule(next);
        cancelled.completion.cancel(true);
        assertEquals(List.of("active"), started);
        active.completion.completeExceptionally(new IllegalStateException());
        assertEquals(List.of("active", "next"), started);
        next.completion.complete(null);
    }

    @Test
    void cancellingBlockedSegmentHeadMakesItsDifferentOriginSuccessorEligible() throws Exception {
        var scheduler = new SegmentSerialScheduler<Task>(3, 10, 1);
        List<String> started = new ArrayList<>();
        Task active = new Task(1, "a", "active", started);
        Task blocked = new Task(2, "a", "blocked", started);
        Task successor = new Task(2, "b", "successor", started);
        scheduler.schedule(active);
        scheduler.schedule(blocked);
        scheduler.schedule(successor);
        blocked.completion.cancel(true);
        assertEquals(List.of("active", "successor"), started);
        active.completion.complete(null);
        successor.completion.complete(null);
    }

    @Test
    void cancellingReadyOriginCandidateWakesNextBlockedCandidate() throws Exception {
        var scheduler = new SegmentSerialScheduler<Task>(1, 10, 1);
        List<String> started = new ArrayList<>();
        Task a1 = new Task(1, "a", "a1", started);
        Task b = new Task(2, "b", "b", started);
        Task a2 = new Task(3, "a", "a2", started);
        Task a3 = new Task(4, "a", "a3", started);
        for (Task task : List.of(a1, b, a2, a3)) { scheduler.schedule(task); }
        a1.completion.complete(null);
        assertEquals(List.of("a1", "b"), started);
        a2.completion.cancel(true);
        b.completion.complete(null);
        assertEquals(List.of("a1", "b", "a3"), started);
        a3.completion.complete(null);
    }

    @Test
    void addingTailToBlockedSegmentDoesNotDuplicateReadyEntries() throws Exception {
        var scheduler = new SegmentSerialScheduler<Task>(1, 10, 1);
        List<String> started = new ArrayList<>();
        Task a1 = new Task(1, "a", "a1", started);
        Task b = new Task(2, "b", "b", started);
        Task a2 = new Task(3, "a", "a2", started);
        Task a3 = new Task(4, "a", "a3", started);
        for (Task task : List.of(a1, b, a2, a3)) { scheduler.schedule(task); }
        a1.completion.complete(null);
        Task tail = new Task(4, "a", "tail", started);
        scheduler.schedule(tail);
        b.completion.complete(null);
        a2.completion.complete(null);
        assertEquals(List.of("a1", "b", "a2", "a3"), started);
        a3.completion.complete(null);
        assertEquals(List.of("a1", "b", "a2", "a3", "tail"), started);
        tail.completion.complete(null);
    }

    @Test
    void disabledLimitPreservesGlobalCapacityAndStopDoesNotDispatchQueuedTasks() throws Exception {
        var scheduler = new SegmentSerialScheduler<Task>(2, 10, 0);
        List<String> started = new ArrayList<>();
        Task first = new Task(1, "a", "first", started);
        Task second = new Task(2, "a", "second", started);
        Task queued = new Task(3, "a", "queued", started);
        scheduler.schedule(first);
        scheduler.schedule(second);
        scheduler.schedule(queued);
        assertEquals(List.of("first", "second"), started);
        scheduler.stopDispatching();
        first.completion.complete(null);
        second.completion.complete(null);
        assertEquals(List.of("first", "second"), started);
        queued.completion.cancel(true);
    }

    private record Task(int segment, Object origin, String name, List<String> started,
                        CompletableFuture<Void> completion) implements SegmentSerialScheduler.Task {
        Task(int segment, Object origin, String name, List<String> started) {
            this(segment, origin, name, started, new CompletableFuture<>());
        }
        public void admitted() {}
        public void start() { started.add(name); }
        public void fail(Throwable error) { completion.completeExceptionally(error); }
    }
}
