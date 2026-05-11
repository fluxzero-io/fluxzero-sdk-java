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
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.fluxzero.common.TestUtils.assertEqualMessages;
import static io.fluxzero.common.api.tracking.Position.newPosition;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultTrackingStrategyTest {

    @Test
    void deliversAvailableMessagesImmediately() {
        TestScheduler scheduler = new TestScheduler();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(1L, 1, "accepted")))) {
            PositionStore positionStore = mockPositionStore();
            List<MessageBatch> received = new CopyOnWriteArrayList<>();
            subject.getBatch(tracker("consumer", "tracker", received::add), positionStore);

            assertEquals(1, received.size());
            assertEqualMessages(List.of(subject.batchesServed.getFirst()), received.getFirst().getMessages());
            assertEquals(1L, received.getFirst().getLastIndex());
            assertTrue(received.getFirst().isCaughtUp());
        }
    }

    @Test
    void fetchesWaitingTrackerWhenUpdateArrives() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        SerializedMessage fetched = message(2L, 1, "accepted");
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(), List.of(fetched))) {
            PositionStore positionStore = mockPositionStore();
            List<MessageBatch> received = new CopyOnWriteArrayList<>();
            CountDownLatch handled = new CountDownLatch(1);
            subject.getBatch(tracker("consumer", "tracker", batch -> {
                received.add(batch);
                handled.countDown();
            }), positionStore);
            assertTrue(received.isEmpty());

            subject.onUpdate(List.of(message(2L, 1, "update")));

            assertTrue(handled.await(5, TimeUnit.SECONDS));
            scheduler.awaitIdle();

            assertEquals(2, subject.batchCalls.get());
            assertEqualMessages(List.of(fetched), received.getFirst().getMessages());
        }
    }

    @Test
    void fetchesWaitingTrackerWhenEmptyUpdateArrives() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        SerializedMessage fetched = message(2L, 1, "accepted");
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(), List.of(fetched))) {
            List<MessageBatch> received = new CopyOnWriteArrayList<>();
            CountDownLatch handled = new CountDownLatch(1);
            subject.getBatch(tracker("consumer", "tracker", batch -> {
                received.add(batch);
                handled.countDown();
            }), mockPositionStore());
            assertTrue(received.isEmpty());

            subject.onUpdate(List.of());

            assertTrue(handled.await(5, TimeUnit.SECONDS));
            scheduler.awaitIdle();

            assertEquals(2, subject.batchCalls.get());
            assertEqualMessages(List.of(fetched), received.getFirst().getMessages());
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
            subject.getBatch(tracker("consumer", "tracker", batch -> {
            }), mockPositionStore());

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
            List<MessageBatch> received = new CopyOnWriteArrayList<>();
            subject.getBatch(tracker("consumer", "tracker", received::add), mockPositionStore());
            assertTrue(received.isEmpty());

            scheduler.runNextScheduledTask();

            assertEquals(1, received.size());
            assertTrue(received.getFirst().isEmpty());
            assertTrue(received.getFirst().isCaughtUp());
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, received.getFirst().getSegment());
        }
    }

    @Test
    void storesPositionWhenFreshMessagesAreFilteredOut() {
        TestScheduler scheduler = new TestScheduler();
        long freshIndex = System.currentTimeMillis() << 16;
        PositionStore positionStore = mockPositionStore();
        try (TestStrategy subject = new TestStrategy(mockSource(), scheduler)
                .withBatches(List.of(message(freshIndex, 1, "ignored")), List.of())) {
            List<MessageBatch> received = new CopyOnWriteArrayList<>();
            subject.getBatch(tracker("consumer", "tracker", received::add)
                                     .withTypeFilter("accepted"::equals), positionStore);

            assertTrue(received.isEmpty());
            verify(positionStore).storePosition(eq("consumer"),
                                                argThat(segment -> segment[0] == 0 && segment[1] == MAX_SEGMENT),
                                                eq(freshIndex));
        }
    }

    @Test
    void wakesWaitingClaimWhenClusterChanges() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler);
        PositionStore positionStore = mockPositionStore();
        List<MessageBatch> firstBatches = new CopyOnWriteArrayList<>();
        List<MessageBatch> secondBatches = new CopyOnWriteArrayList<>();
        TestTracker first = tracker("consumer", "first", firstBatches::add);
        TestTracker second = tracker("consumer", "second", secondBatches::add);
        try(subject) {
            subject.claimSegment(first, positionStore);
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, firstBatches.getFirst().getSegment());

            subject.claimSegment(second, positionStore);
            assertTrue(secondBatches.isEmpty());

            subject.disconnectTrackers(first::equals, false);

            assertEquals(1, secondBatches.size());
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, secondBatches.getFirst().getSegment());
        }
    }

    @Test
    void sendsFinalEmptyBatchWhenDisconnectingWaitingTracker() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler).withBatches(List.of());
        List<MessageBatch> received = new CopyOnWriteArrayList<>();
        TestTracker tracker = tracker("consumer", "tracker", received::add);
        try(subject) {
            subject.getBatch(tracker, mockPositionStore());
            Set<Tracker> removed = subject.disconnectTrackers(tracker::equals, true);

            assertEquals(Set.of(tracker), removed);
            assertEquals(1, received.size());
            assertTrue(received.getFirst().isEmpty());
            assertArrayEquals(new int[]{0, 0}, received.getFirst().getSegment());
        }
    }

    @Test
    void purgeCeasedTrackersLeavesActiveJavaSdkTrackerWithoutPurgeDelayInCluster() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler);
        try (subject) {
            TestTracker active = tracker("consumer", "active", batch -> {
            });
            subject.claimSegment(active);

            subject.schedulePurge(Duration.ZERO);
            scheduler.runNextScheduledTask();

            assertEquals(Set.of(active), subject.disconnectTrackers(active::equals, false));
        }
    }

    @Test
    void purgeCeasedTrackersRemovesActiveExternalTrackerAfterItsConfiguredPurgeDelay() {
        TestScheduler scheduler = new TestScheduler();
        TestStrategy subject = new TestStrategy(mockSource(), scheduler);
        try (subject) {
            TestTracker stale = tracker("consumer", "stale", batch -> {
            }).withPurgeDelay(-1L);
            subject.claimSegment(stale);

            subject.schedulePurge(Duration.ZERO);
            scheduler.runNextScheduledTask();

            assertEquals(Set.of(), subject.disconnectTrackers(stale::equals, false));
            int[] replacementSegment = subject.claimSegment(tracker("consumer", "replacement", batch -> {
            }));
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, replacementSegment);
        }
    }

    @Test
    void disconnectedInFlightTrackerCannotBecomeActiveAgainAndLeaveReplacementWithNoSegment() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch batchStarted = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        try (BlockingBatchStrategy subject = new BlockingBatchStrategy(mockSource(), scheduler, batchStarted,
                                                                       releaseBatch)) {
            TestTracker stale = tracker("consumer", "stale", batch -> {
            }).withDeadline(System.currentTimeMillis() + 50);

            // Start a long-running read so we can disconnect the tracker while getBatch() is still in flight.
            CompletableFuture<Void> inFlight = CompletableFuture.runAsync(
                    () -> subject.getBatch(stale, mockPositionStore()));
            assertTrue(batchStarted.await(5, TimeUnit.SECONDS));

            // Disconnect the tracker, but the in-flight read may still complete later.
            subject.disconnectTrackers(stale::equals, false);

            releaseBatch.countDown();
            inFlight.get(5, TimeUnit.SECONDS);

            // The stale tracker must not re-enter the cluster through waitForUpdate() and then become active again.
            scheduler.runNextScheduledTask();

            int[] replacementSegment = subject.claimSegment(tracker("consumer", "replacement", batch -> {
            }));
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

    private static TestTracker tracker(String consumer, String trackerId, Consumer<MessageBatch> consumerHandler) {
        return new TestTracker(consumer, trackerId, consumerHandler, s -> true, 1024,
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

        void schedulePurge(Duration delay) {
            super.purgeCeasedTrackers(delay);
        }

        @Override
        protected List<SerializedMessage> getBatch(int[] segment, Position position, int batchSize) {
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
            return batch;
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
        protected List<SerializedMessage> getBatch(int[] segment, Position position, int batchSize) {
            batchStarted.countDown();
            await(releaseBatch);
            return List.of();
        }

        @Override
        protected void purgeCeasedTrackers(Duration delay) {
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
        private final Consumer<MessageBatch> consumerHandler;
        private final Predicate<String> typeFilter;
        private final int maxSize;
        private final long deadline;
        private final long maxTimeout;
        private final Long lastTrackerIndex;
        private final Long purgeDelay;

        TestTracker(String consumerName, String trackerId, Consumer<MessageBatch> consumerHandler,
                    Predicate<String> typeFilter, int maxSize, long deadline, long maxTimeout,
                    Long lastTrackerIndex) {
            this(consumerName, trackerId, consumerHandler, typeFilter, maxSize, deadline, maxTimeout,
                 lastTrackerIndex, null);
        }

        TestTracker(String consumerName, String trackerId, Consumer<MessageBatch> consumerHandler,
                    Predicate<String> typeFilter, int maxSize, long deadline, long maxTimeout,
                    Long lastTrackerIndex, Long purgeDelay) {
            this.consumerName = consumerName;
            this.trackerId = trackerId;
            this.consumerHandler = consumerHandler;
            this.typeFilter = typeFilter;
            this.maxSize = maxSize;
            this.deadline = deadline;
            this.maxTimeout = maxTimeout;
            this.lastTrackerIndex = lastTrackerIndex;
            this.purgeDelay = purgeDelay;
        }

        TestTracker withTypeFilter(Predicate<String> typeFilter) {
            return new TestTracker(consumerName, trackerId, consumerHandler, typeFilter, maxSize, deadline,
                                   maxTimeout, lastTrackerIndex, purgeDelay);
        }

        TestTracker withDeadline(long deadline) {
            return new TestTracker(consumerName, trackerId, consumerHandler, typeFilter, maxSize, deadline,
                                   maxTimeout, lastTrackerIndex, purgeDelay);
        }

        TestTracker withPurgeDelay(Long purgeDelay) {
            return new TestTracker(consumerName, trackerId, consumerHandler, typeFilter, maxSize, deadline,
                                   maxTimeout, lastTrackerIndex, purgeDelay);
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
        public long getDeadline() {
            return deadline;
        }

        @Override
        public long maxTimeout() {
            return maxTimeout;
        }

        @Override
        public Long getPurgeDelay() {
            return purgeDelay;
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
        public void send(MessageBatch batch) {
            consumerHandler.accept(batch);
        }

        @Override
        public Tracker withLastTrackerIndex(Long lastTrackerIndex) {
            return new TestTracker(consumerName, trackerId, consumerHandler, typeFilter, maxSize, deadline,
                                   maxTimeout, lastTrackerIndex, purgeDelay);
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
