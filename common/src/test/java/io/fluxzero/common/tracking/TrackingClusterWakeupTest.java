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

package io.fluxzero.common.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingClusterWakeupTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stableEmptyAssignmentsDoNotRescanOrReviveCanceledReads(boolean cancelJoiningRead) {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(Registration.noOp());
        var scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(anyLong(), any())).thenReturn(Registration.noOp());
        var firstScan = new AtomicReference<Runnable>();
        when(source.scanBatch(any(), anyInt(), anyBoolean(), anyLong(), any())).thenAnswer(invocation -> {
            Runnable action = firstScan.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return new MessageStoreBatch(List.of(), null, 0, false);
        });
        try (var strategy = new DefaultTrackingStrategy(source, new InMemoryPositionStore(), scheduler)) {
            var joining = new AtomicReference<CompletableFuture<MessageBatch>>();
            firstScan.set(() -> {
                joining.set(strategy.getBatch(tracker("b")));
                if (cancelJoiningRead) {
                    joining.get().cancel(false);
                }
            });
            CompletableFuture<MessageBatch> owner = strategy.getBatch(tracker("a"));
            assertFalse(owner.isDone());
            assertEquals(cancelJoiningRead, joining.get().isDone());
            // The first full-range scan and one scan for each final half; no polling or recursive empty rescans.
            verify(source, times(cancelJoiningRead ? 2 : 3))
                    .scanBatch(any(), anyInt(), anyBoolean(), anyLong(), any());
            strategy.disconnectTrackers(t -> true, false);
            assertTrue(owner.isCancelled());
            assertTrue(joining.get().isCancelled());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wakesAConcurrentJoinerWhenTheFirstTrackerReleasesItsReservation(boolean claimOnly) {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(Registration.noOp());
        var scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(anyLong(), any())).thenReturn(Registration.noOp());
        var firstScan = new AtomicReference<Runnable>();
        List<SerializedMessage> messages = List.of(message(1, 0), message(2, 127));
        when(source.scanBatch(any(), anyInt(), anyBoolean(), anyLong(), any())).thenAnswer(invocation -> {
            Runnable action = firstScan.getAndSet(null);
            if (action != null) {
                // The first scan has an empty snapshot while another tracker joins the still-reserved full range.
                action.run();
                return new MessageStoreBatch(List.of(), null, 0, false);
            }
            Predicate<SerializedMessage> filter = invocation.getArgument(4);
            return new MessageStoreBatch(messages.stream().filter(filter).toList(), 2L, 2, false);
        });
        try (var strategy = new DefaultTrackingStrategy(source, new InMemoryPositionStore(), scheduler)) {
            var joining = new AtomicReference<CompletableFuture<?>>();
            firstScan.set(() -> joining.set(claimOnly ? strategy.claimSegment(tracker("b"))
                                                     : strategy.getBatch(tracker("b"))));
            MessageBatch ownerBatch = strategy.getBatch(tracker("a")).join();
            assertArrayEquals(new int[]{0, 64}, ownerBatch.getSegment());
            assertEquals(List.of(1L), ownerBatch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            assertTrue(joining.get().isDone(), "A newly assigned range must not wait for another publication or deadline");
            if (claimOnly) {
                assertArrayEquals(new int[]{64, 128}, ((ClaimResult) joining.get().join()).getSegment());
            } else {
                MessageBatch batch = (MessageBatch) joining.get().join();
                assertArrayEquals(new int[]{64, 128}, batch.getSegment());
                assertEquals(List.of(2L), batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void observesRebalanceBeforeWaiterPublication(boolean claimOnly, boolean beforeWaitingRegistration) {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(Registration.noOp());
        List<SerializedMessage> messages = List.of(message(1, 0), message(2, 127));
        when(source.scanBatch(any(), anyInt(), anyBoolean(), anyLong(), any())).thenAnswer(invocation -> {
            Predicate<SerializedMessage> filter = invocation.getArgument(4);
            return new MessageStoreBatch(messages.stream().filter(filter).toList(), 2L, 2, false);
        });
        var scheduler = mock(TaskScheduler.class);
        var duringSchedule = new AtomicReference<Runnable>();
        when(scheduler.schedule(anyLong(), any())).thenAnswer(invocation -> {
            Runnable action = duringSchedule.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return Registration.noOp();
        });
        var beforeWaiting = new AtomicReference<Runnable>();
        try (var strategy = new DefaultTrackingStrategy(source, new InMemoryPositionStore(), scheduler) {
            @Override
            protected void waitForUpdate(Tracker tracker, MessageBatch emptyBatch, Runnable followUp,
                                         TrackerRequest<?> request) {
                Runnable action = beforeWaiting.getAndSet(null);
                if (action != null) {
                    action.run();
                }
                super.waitForUpdate(tracker, emptyBatch, followUp, request);
            }
        }) {
            Tracker owner = tracker("a");
            Tracker waiting = tracker("b");
            assertArrayEquals(new int[]{0, 128}, strategy.claimSegment(owner).join().getSegment());
            Runnable rebalance = () -> assertArrayEquals(new int[]{0, 64}, strategy.getBatch(owner).join().getSegment());
            // Run the owner's next read after the waiter claimed an empty range, either before it marks itself
            // waiting or after that transition but before its scheduled waiter is published. No message arrives.
            (beforeWaitingRegistration ? beforeWaiting : duringSchedule).set(rebalance);
            CompletableFuture<?> result = claimOnly ? strategy.claimSegment(waiting) : strategy.getBatch(waiting);

            assertTrue(result.isDone(), "A missed cluster notification must not defer available work to the long-poll deadline");
            if (claimOnly) {
                assertArrayEquals(new int[]{64, 128}, ((ClaimResult) result.join()).getSegment());
            } else {
                MessageBatch batch = (MessageBatch) result.join();
                assertArrayEquals(new int[]{64, 128}, batch.getSegment());
                assertEquals(List.of(2L), batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void wakesTrackerThatJoinsBetweenClusterSnapshotAndClaim(boolean ownerClaimOnly, boolean claimOnly) {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(Registration.noOp());
        var scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(anyLong(), any())).thenReturn(Registration.noOp());
        long firstIndex = System.currentTimeMillis() << 16;
        List<SerializedMessage> messages = List.of(message(firstIndex, 0), message(firstIndex + 1, 127));
        when(source.scanBatch(any(), anyInt(), anyBoolean(), anyLong(), any())).thenAnswer(invocation -> {
            Long lastIndex = invocation.getArgument(0);
            return MessageStoreBatch.scan(messages.stream()
                    .filter(message -> lastIndex == null || message.getIndex() > lastIndex).toList(),
                    invocation.getArgument(1), invocation.getArgument(3), invocation.getArgument(4));
        });
        var beforeClaim = new AtomicReference<Runnable>();
        var positions = new InMemoryPositionStore();
        try (var strategy = new DefaultTrackingStrategy(source, positions, scheduler) {
            @Override
            protected int[] claimSegmentRange(Tracker tracker) {
                Runnable action = beforeClaim.getAndSet(null);
                if (action != null) {
                    action.run();
                }
                return super.claimSegmentRange(tracker);
            }
        }) {
            Tracker owner = new WebSocketTracker(new Read(MessageType.EVENT, "consumer", "a", 1, 60_000,
                    null, false, false, false, false, 0L, null), MessageType.EVENT, "client", "session");
            MessageBatch first = strategy.getBatch(owner).join();
            assertArrayEquals(new int[]{0, 128}, first.getSegment());
            assertEquals(List.of(firstIndex), first.getMessages().stream().map(SerializedMessage::getIndex).toList());
            positions.storePosition("consumer", first.getSegment(), first.getLastIndex());

            var joining = new AtomicReference<CompletableFuture<?>>();
            beforeClaim.set(() -> {
                joining.set(claimOnly ? strategy.claimSegment(tracker("b")) : strategy.getBatch(tracker("b")));
                assertFalse(joining.get().isDone(), "The owner still reserves the entire segment range");
            });
            // The joiner is absent from the owner's initial cluster snapshot, but present when it reclaims.
            Tracker nextOwner = owner.withLastTrackerIndex(firstIndex);
            CompletableFuture<?> ownerRead = ownerClaimOnly ? strategy.claimSegment(nextOwner)
                                                           : strategy.getBatch(nextOwner);
            assertEquals(ownerClaimOnly, ownerRead.isDone());
            if (ownerClaimOnly) {
                assertArrayEquals(new int[]{0, 64}, ((ClaimResult) ownerRead.join()).getSegment());
            }
            assertTrue(joining.get().isDone(), "A newly assigned peer must wake even if it joined after the snapshot");
            if (claimOnly) {
                assertArrayEquals(new int[]{64, 128}, ((ClaimResult) joining.get().join()).getSegment());
            } else {
                MessageBatch batch = (MessageBatch) joining.get().join();
                assertArrayEquals(new int[]{64, 128}, batch.getSegment());
                assertEquals(List.of(firstIndex + 1),
                             batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            }
        }
    }

    private static Tracker tracker(String id) {
        return new WebSocketTracker(new Read(MessageType.EVENT, "consumer", id, 1024, 60_000,
                                             null, false, false, false, false, 0L, null),
                                    MessageType.EVENT, "client", "session");
    }

    private static SerializedMessage message(long index, int segment) {
        SerializedMessage result = TestUtils.createMessage();
        result.setIndex(index);
        result.setSegment(segment);
        return result;
    }
}
