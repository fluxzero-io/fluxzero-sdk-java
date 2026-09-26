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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.tracking.Read;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackingHandoverTest {
    @Test
    void blockedScanCannotHoldShutdownIndefinitely() throws Exception {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(() -> {});
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(source.scanBatch(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(),
                              org.mockito.ArgumentMatchers.anyLong(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new MessageStoreBatch(List.of(), null, 0, false);
        });
        try (var strategy = new DefaultTrackingStrategy(source, new InMemoryPositionStore());
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> strategy.getBatch(tracker("owner", false)));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class,
                             () -> strategy.freezeForHandover(java.time.Duration.ZERO));
            } finally {
                release.countDown();
            }
            read.get(1, TimeUnit.SECONDS);
            strategy.freezeForHandover();
        }
    }

    @Test
    void successorKeepsReservationUntilOriginalTrackerRequestsAnotherBatch() throws Exception {
        var positions = new InMemoryPositionStore();
        try (var old = strategy(positions); var next = strategy(positions)) {
            var owner = tracker("owner", false);
            assertArrayEquals(new int[]{0, 128}, old.claimSegment(owner).get().getSegment());
            List<TrackerClaim> snapshot = old.freezeForHandover();
            old.disconnectTrackersOnClose(t -> true);
            assertThrows(java.util.concurrent.CancellationException.class,
                         () -> old.disconnectTrackers(t -> true, false));
            assertEquals(snapshot, old.freezeForHandover(), "Old socket cleanup must preserve the transfer");
            assertTrue(old.getBatch(tracker("late", false)).isCancelled());
            next.restoreClaims(snapshot);
            var waiting = next.claimSegment(tracker("other", false));
            assertFalse(waiting.isDone());
            positions.storePosition("consumer", new int[]{0, 128}, 100L).join();
            assertFalse(waiting.isDone(), "Manual position writes are not batch completion");
            next.claimSegment(owner);
            assertTrue(waiting.get(5, TimeUnit.SECONDS).getSegment()[1] > 0);
        }
    }

    @Test
    void freezingCancelsWaitingRequestsWithoutRemovingActiveOwner() {
        try (var old = strategy(new InMemoryPositionStore())) {
            old.claimSegment(tracker("owner", false)).join();
            var waiting = old.claimSegment(tracker("waiting", false));
            assertFalse(waiting.isDone());
            var snapshot = old.freezeForHandover();
            assertTrue(waiting.isCancelled());
            assertEquals(1, snapshot.size());
            assertEquals("owner", snapshot.getFirst().trackerId());
        }
    }

    @Test
    void unrelatedConsumerCanServeNestedWorkDuringHandover() throws Exception {
        try (var next = strategy(new InMemoryPositionStore())) {
            next.restoreClaims(List.of(new TrackerClaim("consumer", "owner", "client", 0, 128,
                                                         System.currentTimeMillis(), null, false)));
            var dependency = new WebSocketTracker(new Read(MessageType.COMMAND, "dependency", "nested", 1,
                                                            60000, null, false, false, false, false, 0L, null),
                                                    MessageType.COMMAND, "client", "new");
            assertArrayEquals(new int[]{0, 128}, next.claimSegment(dependency).get().getSegment());
        }
    }

    @Test
    void singleTrackerReservationExcludesWholeConsumer() {
        try (var next = strategy(new InMemoryPositionStore())) {
            next.restoreClaims(List.of(new TrackerClaim("consumer", "owner", "client", 0, 128,
                                                         System.currentTimeMillis(), null, true)));
            assertFalse(next.claimSegment(tracker("other", false)).isDone());
        }
    }

    @Test
    void singleTrackerWithClientSideFilteringStillTransfersExclusiveOwnership() {
        try (var old = strategy(new InMemoryPositionStore()); var next = strategy(new InMemoryPositionStore())) {
            var owner = new WebSocketTracker(new Read(MessageType.COMMAND, "consumer", "owner", 1, 60000,
                    null, false, false, true, true, 0L, null), MessageType.COMMAND, "client", "session");
            old.claimSegment(owner).join();
            next.restoreClaims(old.freezeForHandover());
            assertFalse(next.claimSegment(tracker("other", true)).isDone());
        }
    }

    @Test
    void reservationsCannotReplaceAnAlreadyActiveStrategy() {
        try (var next = strategy(new InMemoryPositionStore())) {
            next.claimSegment(tracker("owner", false)).join();
            assertThrows(IllegalStateException.class, () -> next.restoreClaims(List.of()));
        }
    }

    private static DefaultTrackingStrategy strategy(InMemoryPositionStore positions) {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(() -> {});
        return new DefaultTrackingStrategy(source, positions);
    }

    private static WebSocketTracker tracker(String id, boolean single) {
        return new WebSocketTracker(new Read(MessageType.COMMAND, "consumer", id, 1, 60000, null,
                                            false, false, single, false, 0L, null),
                                    MessageType.COMMAND, "client", "session");
    }
}
