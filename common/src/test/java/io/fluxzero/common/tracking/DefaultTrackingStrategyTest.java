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
 *
 */

package io.fluxzero.common.tracking;

import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static io.fluxzero.common.TestUtils.assertEqualMessages;
import static io.fluxzero.common.api.tracking.Position.newPosition;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTrackingStrategyTest {

    @Test
    void deliversAvailableMessagesImmediately() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(1L, 1, "accepted")))) {
            PositionStore positionStore = mockPositionStore();
            MessageBatch batch = subject.getBatch(tracker("consumer", "tracker"), positionStore).join();

            assertEqualMessages(List.of(subject.batchesServed.getFirst()), batch.getMessages());
            assertEquals(1L, batch.getLastIndex());
            assertTrue(batch.isCaughtUp());
        }
    }

    @Test
    void fetchesWaitingTrackerWhenUpdateArrives() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        SerializedMessage fetched = message(2L, 1, "accepted");
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(), List.of(fetched))) {
            PositionStore positionStore = mockPositionStore();
            CompletableFuture<MessageBatch> result = subject.getBatch(tracker("consumer", "tracker"), positionStore);
            assertFalse(result.isDone());

            subject.onUpdate(List.of(message(2L, 1, "update")));

            MessageBatch batch = result.get(5, TimeUnit.SECONDS);
            scheduler.awaitIdle();

            assertEquals(2, subject.batchCalls.get());
            assertEqualMessages(List.of(fetched), batch.getMessages());
        }
    }

    @Test
    void fetchesWaitingTrackerWhenEmptyUpdateArrives() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        SerializedMessage fetched = message(2L, 1, "accepted");
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(), List.of(fetched))) {
            CompletableFuture<MessageBatch> result = subject.getBatch(tracker("consumer", "tracker"),
                                                                      mockPositionStore());
            assertFalse(result.isDone());

            subject.onUpdate(List.of());

            MessageBatch batch = result.get(5, TimeUnit.SECONDS);
            scheduler.awaitIdle();

            assertEquals(2, subject.batchCalls.get());
            assertEqualMessages(List.of(fetched), batch.getMessages());
        }
    }

    @Test
    void coalescesUpdatesWhileWakeupIsRunning() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch firstFollowUpStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFollowUp = new CountDownLatch(1);
        CountDownLatch coalescedFollowUpStarted = new CountDownLatch(1);
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(), List.of(), List.of())
                .blockOnCall(2, firstFollowUpStarted, releaseFirstFollowUp)
                .signalOnCall(3, coalescedFollowUpStarted)) {
            subject.getBatch(tracker("consumer", "tracker"), mockPositionStore());

            subject.onUpdate(List.of(message(1L, 1, "update")));
            assertTrue(firstFollowUpStarted.await(5, TimeUnit.SECONDS));

            subject.onUpdate(List.of(message(2L, 1, "update")));
            subject.onUpdate(List.of(message(3L, 1, "update")));

            releaseFirstFollowUp.countDown();
            assertTrue(coalescedFollowUpStarted.await(5, TimeUnit.SECONDS));
            scheduler.awaitIdle();

            assertEquals(3, subject.batchCalls.get());
        }
    }

    @Test
    void sendsEmptyBatchWhenWaitDeadlineExpires() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler).withBatches(List.of())) {
            CompletableFuture<MessageBatch> result = subject.getBatch(tracker("consumer", "tracker"),
                                                                      mockPositionStore());
            assertFalse(result.isDone());

            scheduler.runNextScheduledTask();

            MessageBatch batch = result.join();
            assertTrue(batch.isEmpty());
            assertTrue(batch.isCaughtUp());
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, batch.getSegment());
        }
    }

    @Test
    void sendsEmptyBatchImmediatelyWhenReadHasZeroMaxTimeout() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler).withBatches(List.of())) {
            MessageBatch batch = subject.getBatch(tracker("consumer", "tracker")
                                                          .withDeadline(System.currentTimeMillis() + 6000L)
                                                          .withMaxTimeout(0L), mockPositionStore()).join();

            assertTrue(batch.isEmpty());
            assertTrue(batch.isCaughtUp());
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, batch.getSegment());
            assertEquals(0, scheduler.scheduledTaskCount());
        }
    }

    @Test
    void sendsEmptyClaimImmediatelyWhenZeroMaxTimeoutAndNoSegmentIsAvailable() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler);
        PositionStore positionStore = mockPositionStore();
        TestTracker first = tracker("consumer", "first");
        TestTracker second = tracker("consumer", "second")
                .withDeadline(System.currentTimeMillis() + 6000L)
                .withMaxTimeout(0L);
        try(subject) {
            ClaimResult firstClaim = subject.claimSegment(first, positionStore).join();
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, firstClaim.getSegment());

            ClaimResult secondClaim = subject.claimSegment(second, positionStore).join();

            assertArrayEquals(new int[]{0, 0}, secondClaim.getSegment());
            assertEquals(0, scheduler.scheduledTaskCount());
        }
    }

    @Test
    void storesPositionWhenFreshMessagesAreFilteredOut() {
        TestScheduler scheduler = new TestScheduler();
        long freshIndex = System.currentTimeMillis() << 16;
        PositionStore positionStore = mockPositionStore();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(freshIndex, 1, "ignored")), List.of())) {
            CompletableFuture<MessageBatch> result = subject.getBatch(
                    tracker("consumer", "tracker").withTypeFilter("accepted"::equals), positionStore);

            assertFalse(result.isDone());
            verify(positionStore).storePosition(eq("consumer"),
                                                argThat(segment -> segment[0] == 0 && segment[1] == MAX_SEGMENT),
                                                eq(freshIndex));
        }
    }

    @Test
    void limitsReturnedBatchByPayloadBytes() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(1L, 1, "accepted", 4),
                                     message(2L, 1, "accepted", 4),
                                     message(3L, 1, "accepted", 4)))) {
            MessageBatch batch = subject.getBatch(tracker("consumer", "tracker").withMaxBytes(8L),
                                                  mockPositionStore()).join();

            assertEquals(List.of(1L, 2L),
                         batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            assertEquals(8L, batch.getBytes());
            assertEquals(2L, batch.getLastIndex());
            assertFalse(batch.isCaughtUp());
        }
    }

    @Test
    void appliesPayloadByteLimitAfterStoreSideFilteringWithoutSkippingNextMatch() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(1L, 1, "ignored", 32),
                                     message(2L, 1, "accepted", 4),
                                     message(3L, 1, "accepted", 4),
                                     message(4L, 1, "accepted", 4)))) {
            MessageBatch batch = subject.getBatch(tracker("consumer", "tracker")
                                                          .withMaxBytes(8L)
                                                          .withTypeFilter("accepted"::equals),
                                                  mockPositionStore()).join();

            assertEquals(List.of(2L, 3L),
                         batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            assertEquals(3L, batch.getLastIndex());
            assertFalse(batch.isCaughtUp());
        }
    }

    @Test
    void returnsOversizedFirstMessageWhenPayloadByteLimitIsSmaller() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(1L, 1, "accepted", 12),
                                     message(2L, 1, "accepted", 4)))) {
            MessageBatch batch = subject.getBatch(tracker("consumer", "tracker").withMaxBytes(8L),
                                                  mockPositionStore()).join();

            assertEquals(List.of(1L),
                         batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            assertEquals(12L, batch.getBytes());
            assertEquals(1L, batch.getLastIndex());
            assertFalse(batch.isCaughtUp());
        }
    }

    @Test
    void wakesWaitingClaimWhenClusterChanges() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler);
        PositionStore positionStore = mockPositionStore();
        TestTracker first = tracker("consumer", "first");
        TestTracker second = tracker("consumer", "second");
        try(subject) {
            ClaimResult firstClaim = subject.claimSegment(first, positionStore).join();
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, firstClaim.getSegment());

            CompletableFuture<ClaimResult> secondClaim = subject.claimSegment(second, positionStore);
            assertFalse(secondClaim.isDone());

            subject.disconnectTrackers(first::equals, false);

            assertArrayEquals(new int[]{0, MAX_SEGMENT}, secondClaim.join().getSegment());
        }
    }

    @Test
    void sendsFinalEmptyBatchWhenDisconnectingWaitingTracker() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler).withBatches(List.of());
        TestTracker tracker = tracker("consumer", "tracker");
        try(subject) {
            CompletableFuture<MessageBatch> result = subject.getBatch(tracker, mockPositionStore());
            Set<Tracker> removed = subject.disconnectTrackers(tracker::equals, true);

            MessageBatch batch = result.join();
            assertEquals(Set.of(tracker), removed);
            assertTrue(batch.isEmpty());
            assertArrayEquals(new int[]{0, 0}, batch.getSegment());
        }
    }

    @Test
    void sendsFinalEmptyBatchWhenDisconnectingInFlightTrackerBeforeItWaits() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch batchStarted = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        try (BlockingBatchStrategy subject = new BlockingBatchStrategy(mockSource(), scheduler, batchStarted,
                                                                       releaseBatch)) {
            TestTracker tracker = tracker("consumer", "tracker");

            CompletableFuture<CompletableFuture<MessageBatch>> inFlight =
                    CompletableFuture.supplyAsync(() -> subject.getBatch(tracker, mockPositionStore()));
            assertTrue(batchStarted.await(5, TimeUnit.SECONDS));

            subject.disconnectTrackers(tracker::equals, true);

            releaseBatch.countDown();
            MessageBatch batch = inFlight.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);

            assertTrue(batch.isEmpty());
            assertArrayEquals(new int[]{0, 0}, batch.getSegment());
        }
    }

    @Test
    void finalDisconnectWinsWhenInFlightTrackerFindsMessagesAfterDisconnect() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch batchStarted = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(1L, 1, "accepted")))
                .blockOnCall(1, batchStarted, releaseBatch)) {
            TestTracker tracker = tracker("consumer", "tracker");

            CompletableFuture<CompletableFuture<MessageBatch>> inFlight =
                    CompletableFuture.supplyAsync(() -> subject.getBatch(tracker, mockPositionStore()));
            assertTrue(batchStarted.await(5, TimeUnit.SECONDS));

            subject.disconnectTrackers(tracker::equals, true);

            releaseBatch.countDown();
            MessageBatch batch = inFlight.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);

            assertTrue(batch.isEmpty());
            assertArrayEquals(new int[]{0, 0}, batch.getSegment());
        }
    }

    @Test
    void replacedInFlightTrackerDoesNotDisconnectReplacement() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch firstClaimStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstClaim = new CountDownLatch(1);
        try (TestStrategy subject = new BlockingClaimStrategy(mockSource(), scheduler, firstClaimStarted,
                                                              releaseFirstClaim)
                .withBatches(List.of())) {
            TestTracker tracker = tracker("consumer", "tracker");

            CompletableFuture<CompletableFuture<MessageBatch>> first =
                    CompletableFuture.supplyAsync(() -> subject.getBatch(tracker, mockPositionStore()));
            assertTrue(firstClaimStarted.await(5, TimeUnit.SECONDS));

            CompletableFuture<MessageBatch> replacement = subject.getBatch(tracker, mockPositionStore());
            assertFalse(replacement.isDone());

            releaseFirstClaim.countDown();
            MessageBatch staleBatch = first.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            assertArrayEquals(new int[]{0, 0}, staleBatch.getSegment());

            scheduler.runNextScheduledTask();

            MessageBatch replacementBatch = replacement.get(5, TimeUnit.SECONDS);
            assertTrue(replacementBatch.isEmpty());
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, replacementBatch.getSegment());
        }
    }

    @Test
    void disconnectedInFlightTrackerCannotBecomeActiveAgainAndLeaveReplacementWithNoSegment() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch batchStarted = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        try (BlockingBatchStrategy subject = new BlockingBatchStrategy(mockSource(), scheduler, batchStarted,
                                                                       releaseBatch)) {
            TestTracker stale = tracker("consumer", "stale").withDeadline(System.currentTimeMillis() + 50);

            // Start a long-running read so we can disconnect the tracker while getBatch() is still in flight.
            CompletableFuture<CompletableFuture<MessageBatch>> inFlight =
                    CompletableFuture.supplyAsync(() -> subject.getBatch(stale, mockPositionStore()));
            assertTrue(batchStarted.await(5, TimeUnit.SECONDS));

            // Disconnect the tracker, but the in-flight read may still complete later.
            subject.disconnectTrackers(stale::equals, false);

            releaseBatch.countDown();
            inFlight.get(5, TimeUnit.SECONDS);

            // The stale tracker must not re-enter the cluster through waitForUpdate() and then become active again.
            scheduler.runNextScheduledTask();

            int[] replacementSegment = subject.claimSegment(tracker("consumer", "replacement"), mockPositionStore())
                    .join().getSegment();
            // After the disconnect, the replacement tracker should own the full segment
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, replacementSegment);
        }
    }

    private static MessageStore mockSource() {
        MessageStore source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(() -> {
        });
        return source;
    }

    private static PositionStore mockPositionStore() {
        PositionStore positionStore = mock(PositionStore.class);
        when(positionStore.position(anyString())).thenReturn(newPosition());
        when(positionStore.storePosition(anyString(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(positionStore.resetPosition(anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return positionStore;
    }

    private static SerializedMessage message(long index, int segment, String type) {
        SerializedMessage result = TestUtils.createMessage();
        result.setIndex(index);
        result.setSegment(segment);
        result.setData(result.getData().withType(type));
        result.setMessageId("message-" + index + "-" + segment);
        return result;
    }

    private static SerializedMessage message(long index, int segment, String type, int bytes) {
        SerializedMessage result = message(index, segment, type);
        result.setData(new Data<>(new byte[bytes], type, 0, "application/octet-stream"));
        return result;
    }

    private static TestTracker tracker(String consumer, String trackerId) {
        return new TestTracker(consumer, trackerId, s -> true, 1024, 0L,
                               System.currentTimeMillis() + 6000, 6000, 0L);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static class TestStrategy extends DefaultTrackingStrategy {
        private final Queue<List<SerializedMessage>> batches = new ConcurrentLinkedQueue<>();
        private final List<SerializedMessage> batchesServed = new CopyOnWriteArrayList<>();
        private final AtomicInteger batchCalls = new AtomicInteger();
        private int blockedCall = -1;
        private CountDownLatch blockedCallStarted = new CountDownLatch(0);
        private CountDownLatch releaseBlockedCall = new CountDownLatch(0);
        private int signalledCall = -1;
        private CountDownLatch signalledCallStarted = new CountDownLatch(0);

        TestStrategy(MessageStore source, TaskScheduler scheduler) {
            super(source, scheduler);
        }

        @SafeVarargs
        final TestStrategy withBatches(List<SerializedMessage>... batches) {
            Collections.addAll(this.batches, batches);
            return this;
        }

        TestStrategy blockOnCall(int call, CountDownLatch started, CountDownLatch release) {
            blockedCall = call;
            blockedCallStarted = started;
            releaseBlockedCall = release;
            return this;
        }

        TestStrategy signalOnCall(int call, CountDownLatch started) {
            signalledCall = call;
            signalledCallStarted = started;
            return this;
        }

        @Override
        protected MessageStoreBatch scanBatch(int[] segment, Position position, int batchSize, long maxBytes,
                                              Predicate<? super SerializedMessage> filter) {
            int call = batchCalls.incrementAndGet();
            if (call == blockedCall) {
                blockedCallStarted.countDown();
                await(releaseBlockedCall);
            }
            if (call == signalledCall) {
                signalledCallStarted.countDown();
            }
            List<SerializedMessage> batch = Optional.ofNullable(batches.poll()).orElse(List.of());
            batchesServed.addAll(batch);
            return MessageStoreBatch.scan(batch, batchSize, maxBytes, filter);
        }

        @Override
        protected void purgeCeasedTrackers(Duration delay) {
        }
    }

    private static class BlockingBatchStrategy extends DefaultTrackingStrategy {
        private final CountDownLatch batchStarted;
        private final CountDownLatch releaseBatch;

        BlockingBatchStrategy(MessageStore source, TaskScheduler scheduler, CountDownLatch batchStarted,
                              CountDownLatch releaseBatch) {
            super(source, scheduler);
            this.batchStarted = batchStarted;
            this.releaseBatch = releaseBatch;
        }

        @Override
        protected MessageStoreBatch scanBatch(int[] segment, Position position, int batchSize, long maxBytes,
                                              Predicate<? super SerializedMessage> filter) {
            batchStarted.countDown();
            await(releaseBatch);
            return MessageStoreBatch.scan(List.of(), batchSize, maxBytes, filter);
        }

        @Override
        protected void purgeCeasedTrackers(Duration delay) {
        }
    }

    private static class BlockingClaimStrategy extends TestStrategy {
        private final CountDownLatch firstClaimStarted;
        private final CountDownLatch releaseFirstClaim;
        private final AtomicInteger claimCalls = new AtomicInteger();

        BlockingClaimStrategy(MessageStore source, TaskScheduler scheduler, CountDownLatch firstClaimStarted,
                              CountDownLatch releaseFirstClaim) {
            super(source, scheduler);
            this.firstClaimStarted = firstClaimStarted;
            this.releaseFirstClaim = releaseFirstClaim;
        }

        @Override
        protected int[] claimSegment(Tracker tracker) {
            int[] result = super.claimSegment(tracker);
            if (claimCalls.incrementAndGet() == 1) {
                firstClaimStarted.countDown();
                await(releaseFirstClaim);
            }
            return result;
        }
    }

    private static class TestScheduler implements TaskScheduler {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Queue<ThrowingRunnable> scheduledTasks = new ConcurrentLinkedQueue<>();

        @Override
        public void submit(ThrowingRunnable task) {
            executor.submit(() -> run(task));
        }

        @Override
        public Registration schedule(long deadline, ThrowingRunnable task) {
            scheduledTasks.add(task);
            return () -> scheduledTasks.remove(task);
        }

        @Override
        public Clock clock() {
            return Clock.systemUTC();
        }

        @Override
        public void executeExpiredTasks() {
        }

        @Override
        public void shutdown() {
            executor.shutdownNow();
        }

        void runNextScheduledTask() {
            ThrowingRunnable nextTask = scheduledTasks.poll();
            if (nextTask != null) {
                run(nextTask);
            }
        }

        void awaitIdle() throws Exception {
            CompletableFuture<Void> idle = new CompletableFuture<>();
            executor.submit(() -> idle.complete(null));
            idle.get(5, TimeUnit.SECONDS);
        }

        int scheduledTaskCount() {
            return scheduledTasks.size();
        }

        private static void run(ThrowingRunnable task) {
            try {
                task.run();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class TestTracker implements Tracker {
        private final String consumerName;
        private final String trackerId;
        private final Predicate<String> typeFilter;
        private final int maxSize;
        private final long maxBytes;
        private final long deadline;
        private final long maxTimeout;
        private final Long lastTrackerIndex;

        TestTracker(String consumerName, String trackerId, Predicate<String> typeFilter, int maxSize, long maxBytes,
                    long deadline, long maxTimeout, Long lastTrackerIndex) {
            this.consumerName = consumerName;
            this.trackerId = trackerId;
            this.typeFilter = typeFilter;
            this.maxSize = maxSize;
            this.maxBytes = maxBytes;
            this.deadline = deadline;
            this.maxTimeout = maxTimeout;
            this.lastTrackerIndex = lastTrackerIndex;
        }

        TestTracker withTypeFilter(Predicate<String> typeFilter) {
            return new TestTracker(consumerName, trackerId, typeFilter, maxSize, maxBytes, deadline,
                                   maxTimeout, lastTrackerIndex);
        }

        TestTracker withDeadline(long deadline) {
            return new TestTracker(consumerName, trackerId, typeFilter, maxSize, maxBytes, deadline,
                                   maxTimeout, lastTrackerIndex);
        }

        TestTracker withMaxTimeout(long maxTimeout) {
            return new TestTracker(consumerName, trackerId, typeFilter, maxSize, maxBytes, deadline,
                                   maxTimeout, lastTrackerIndex);
        }

        TestTracker withMaxBytes(long maxBytes) {
            return new TestTracker(consumerName, trackerId, typeFilter, maxSize, maxBytes, deadline,
                                   maxTimeout, lastTrackerIndex);
        }

        @Override
        public String getConsumerName() {
            return consumerName;
        }

        @Override
        public String getClientId() {
            return trackerId;
        }

        @Override
        public String getTrackerId() {
            return trackerId;
        }

        @Override
        public Long getLastTrackerIndex() {
            return lastTrackerIndex;
        }

        @Override
        public int getMaxSize() {
            return maxSize;
        }

        @Override
        public long getMaxBytes() {
            return maxBytes;
        }

        @Override
        public long getDeadline() {
            return deadline;
        }

        @Override
        public long maxTimeout() {
            return maxTimeout;
        }

        @Override
        public Long getPurgeDelay() {
            return null;
        }

        @Override
        public boolean ignoreSegment() {
            return false;
        }

        @Override
        public boolean clientControlledIndex() {
            return false;
        }

        @Override
        public Predicate<String> getTypeFilter() {
            return typeFilter;
        }

        @Override
        public Tracker withLastTrackerIndex(Long lastTrackerIndex) {
            return new TestTracker(consumerName, trackerId, typeFilter, maxSize, maxBytes, deadline,
                                   maxTimeout, lastTrackerIndex);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TestTracker that)) {
                return false;
            }
            return Objects.equals(consumerName, that.consumerName) && Objects.equals(trackerId, that.trackerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerName, trackerId);
        }
    }
}
