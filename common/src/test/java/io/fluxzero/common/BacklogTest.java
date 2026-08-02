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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacklogTest {

    @Test
    void capsInitialBatchCapacity() {
        assertEquals(8, Backlog.initialBatchCapacity(8));
        assertEquals(16, Backlog.initialBatchCapacity(512));
        assertEquals(16, Backlog.initialBatchCapacity(1024));
        assertEquals(16, Backlog.initialBatchCapacity(Integer.MAX_VALUE));
    }

    @Test
    void boundsSequentialBatchesByCountAndWeight() {
        List<List<String>> batches = new ArrayList<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            batches.add(List.copyOf(batch));
            return CompletableFuture.completedFuture(null);
        }, 3, String::length, 5L, 1);

        subject.add(List.of("aa", "bbb", "123456", "c", "dd", "e")).join();
        subject.shutDown();

        assertEquals(
                List.of(List.of("aa", "bbb"), List.of("123456"), List.of("c", "dd", "e")),
                batches);
    }

    @Test
    void invalidWeightFailsTheItemWithoutStoppingTheBacklog() {
        Backlog<String> subject = Backlog.forAsyncConsumer(
                batch -> CompletableFuture.completedFuture(null),
                3, value -> value.equals("invalid") ? -1L : value.length(), 5L, 1);

        assertThrows(Exception.class, () -> subject.add("invalid").join());
        subject.add("valid").join();
        subject.shutDown();
    }

    @Test
    void collectionDelayMicroBatchesItemsThatArriveAfterAnIdleStart() {
        List<List<String>> batches = new ArrayList<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            batches.add(List.copyOf(batch));
            return CompletableFuture.completedFuture(null);
        }, 10, String::length, 100L, 1, Duration.ofMillis(100));

        CompletableFuture<Void> first = subject.add("first");
        CompletableFuture<Void> second = subject.add("second");
        CompletableFuture.allOf(first, second).join();
        subject.shutDown();

        assertEquals(List.of(List.of("first", "second")), batches);
    }

    @Test
    void rejectsNegativeCollectionDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                Backlog.forAsyncConsumer(
                        batch -> CompletableFuture.completedFuture(null),
                        10, String::length, 100L, 1, Duration.ofNanos(-1)));
    }

    @Test
    void untrackedBatchLeavesCompletionOwnershipWithConsumerAndKeepsDraining() {
        List<List<String>> batches = new ArrayList<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            batches.add(List.copyOf(batch));
            return batch.contains("fail")
                    ? CompletableFuture.failedFuture(new IllegalStateException("expected"))
                    : CompletableFuture.completedFuture(null);
        }, 2, String::length, 100L, 1);

        subject.addAllUntracked(List.of("fail", "untracked"));
        subject.add("tracked").join();
        subject.shutDown();

        assertEquals(
                List.of(List.of("fail", "untracked"), List.of("tracked")),
                batches);
    }

    @Test
    void boundsInFlightBatchesAndRefillsEachAvailableSlot() {
        List<CompletableFuture<Void>> gates = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            CompletableFuture<Void> gate = new CompletableFuture<>();
            gate.whenComplete((ignored, failure) -> active.decrementAndGet());
            gates.add(gate);
            return gate;
        }, 1, 2);

        CompletableFuture<Void> result = subject.add(List.of("one", "two", "three", "four"));
        await(() -> gates.size() == 2);
        assertFalse(result.isDone());

        gates.get(1).complete(null);
        await(() -> gates.size() == 3);
        gates.get(2).complete(null);
        await(() -> gates.size() == 4);
        assertEquals(2, maximumActive.get());
        assertFalse(result.isDone());

        gates.get(3).complete(null);
        gates.get(0).complete(null);
        result.join();
        subject.shutDown();
    }

    @Test
    void completesIndependentSubmissionsWhenTheirOwnBatchCompletes() {
        Map<String, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            CompletableFuture<Void> gate = new CompletableFuture<>();
            gates.put(batch.getFirst(), gate);
            return gate;
        }, 1, 2);

        CompletableFuture<Void> first = subject.add("first");
        CompletableFuture<Void> second = subject.add("second");
        await(() -> gates.size() == 2);

        gates.get("second").complete(null);
        second.join();
        assertFalse(first.isDone());

        gates.get("first").complete(null);
        first.join();
        subject.shutDown();
    }

    @Test
    void waitsForEveryPartOfASplitSubmissionAndRetainsFailures() {
        Map<String, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            CompletableFuture<Void> gate = new CompletableFuture<>();
            gates.put(batch.getFirst(), gate);
            return gate;
        }, 1, 2);

        CompletableFuture<Void> result = subject.add(List.of("first", "second"));
        await(() -> gates.size() == 2);

        gates.get("first").completeExceptionally(new IllegalStateException("expected"));
        assertFalse(result.isDone());
        gates.get("second").complete(null);

        assertThrows(Exception.class, result::join);
        subject.shutDown();
    }

    @Test
    void oneInFlightBatchPreservesSequentialAsyncDispatch() {
        List<CompletableFuture<Void>> gates = new CopyOnWriteArrayList<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            CompletableFuture<Void> gate = new CompletableFuture<>();
            gates.add(gate);
            return gate;
        }, 1, 1);

        CompletableFuture<Void> result = subject.add(List.of("first", "second"));
        await(() -> gates.size() == 1);
        assertFalse(result.isDone());

        gates.getFirst().complete(null);
        await(() -> gates.size() == 2);
        assertFalse(result.isDone());

        gates.getLast().complete(null);
        result.join();
        subject.shutDown();
    }

    @Test
    void producerCompletionCallbacksDoNotKeepConsumerSlotsOccupied() throws Exception {
        List<CompletableFuture<Void>> gates = new CopyOnWriteArrayList<>();
        Backlog<String> subject = Backlog.forAsyncConsumer(batch -> {
            CompletableFuture<Void> gate = new CompletableFuture<>();
            gates.add(gate);
            return gate;
        }, 1, 1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);

        CompletableFuture<Void> first = subject.add("first");
        first.thenRun(() -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<Void> second = subject.add("second");
        await(() -> gates.size() == 1);

        CompletableFuture.runAsync(() -> gates.getFirst().complete(null));
        assertTrue(callbackStarted.await(2L, TimeUnit.SECONDS));
        await(() -> gates.size() == 2);

        releaseCallback.countDown();
        gates.getLast().complete(null);
        CompletableFuture.allOf(first, second).join();
        subject.shutDown();
    }

    @Test
    void rejectsNonPositiveInFlightBatchLimit() {
        assertThrows(IllegalArgumentException.class, () ->
                Backlog.forAsyncConsumer(batch -> CompletableFuture.completedFuture(null), 10, 0));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true before the deadline");
    }
}
