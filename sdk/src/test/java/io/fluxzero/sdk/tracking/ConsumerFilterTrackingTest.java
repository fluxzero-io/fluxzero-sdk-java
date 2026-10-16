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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.MessageStoreBatch;
import io.fluxzero.common.tracking.WebSocketTracker;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerFilterTrackingTest {
    @Test
    void fullRefreshOvertakingTheFirstReadRetainsTheNewerSelection() throws Exception {
        var messages = new InMemoryMessageStore(MessageType.EVENT);
        var positions = new InMemoryPositionStore();
        try (var strategy = new DefaultTrackingStrategy(messages, positions)) {
            var before = positions.position("consumer");
            strategy.getBatch(tracker("a", "update-session", filter(2, "New"), 0)).join();
            assertEquals(before, positions.position("consumer"));
            var oldRead = strategy.getBatch(tracker("a", "delayed-session", filter(1, "Old"), 16));
            var expected = message("New", 0);
            messages.append(List.of(expected)).join();
            assertEquals(List.of(expected), oldRead.get(2, TimeUnit.SECONDS).getMessages());
        }
    }

    @Test
    void missingReferencesDoNotClaimOrScanAndFullRetryPreservesTheCursor() throws Exception {
        var messages = new InMemoryMessageStore(MessageType.EVENT);
        var positions = new InMemoryPositionStore();
        try (var strategy = new DefaultTrackingStrategy(messages, positions)) {
            var before = positions.position("consumer");
            var missing = strategy.getBatch(tracker("a", "first", filter(1, "A").asReference(), 16)).join();
            assertTrue(missing.isConsumerFilterRequired());
            assertEquals(before, positions.position("consumer"));
            var expected = message("A", 0);
            messages.append(List.of(expected)).join();
            assertEquals(List.of(expected), strategy.getBatch(tracker("a", "first", filter(1, "A"), 16))
                    .get(2, TimeUnit.SECONDS).getMessages());
            var reference = strategy.getBatch(tracker("a", "other-session", filter(1, "A").asReference(), 16)).join();
            assertFalse(reference.isConsumerFilterRequired());
            assertEquals(List.of(expected), reference.getMessages());
            var newer = strategy.getBatch(tracker("b", "other-session", filter(2, "B").asReference(), 16)).join();
            assertTrue(newer.isConsumerFilterRequired());
        }
    }

    @Test
    void refreshDuringAnOpenStorageScanAppliesBeforeLaterMessagesWithoutAdvancingPositions() throws Exception {
        var messages = new PausingStore();
        var positions = new InMemoryPositionStore();
        try (var strategy = new DefaultTrackingStrategy(messages, positions);
             var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> strategy.getBatch(tracker("tracker", "session-a", filter(1, "Old"), 16)));
            assertTrue(messages.entered.await(2, TimeUnit.SECONDS));
            var before = positions.position("consumer");
            strategy.getBatch(tracker("tracker", "session-b", filter(2, "New"), 0)).get(1, TimeUnit.SECONDS);
            assertEquals(before, positions.position("consumer"));
            var expected = message("New", 0);
            messages.append(List.of(expected)).join();
            messages.resume.countDown();
            assertEquals(List.of(expected), first.get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS).getMessages());
        } finally {
            messages.resume.countDown();
        }
    }

    @Test
    void subscriptionRefreshReachesAllTransportSessionsAndStaleSnapshotsCannotNarrowIt() throws Exception {
        var messages = new InMemoryMessageStore(MessageType.EVENT);
        var positions = new InMemoryPositionStore();
        try (var strategy = new DefaultTrackingStrategy(messages, positions)) {
            var a = strategy.getBatch(tracker("a", "session-a", filter(1, "Old"), 16));
            var b = strategy.getBatch(tracker("b", "session-b", filter(1, "Old"), 16));
            assertFalse(a.isDone());
            assertFalse(b.isDone());
            strategy.getBatch(tracker("a", "session-c", filter(2, "New"), 0)).join();
            // A late request carries its original projection, but shares the subscription's newer revision.
            var c = strategy.getBatch(tracker("c", "session-c", filter(1, "Old"), 16));
            var expected = message("New", 0);
            messages.append(List.of(expected)).join();
            assertEquals(List.of(expected), a.get(2, TimeUnit.SECONDS).getMessages());
            assertEquals(List.of(expected), b.get(2, TimeUnit.SECONDS).getMessages());
            assertEquals(List.of(expected), c.get(2, TimeUnit.SECONDS).getMessages());
        }
    }

    @Test
    void independentApplicationSubscriptionsRemainIsolatedAndLongReadsCancelImmediately() throws Exception {
        var messages = new InMemoryMessageStore(MessageType.EVENT);
        try (var strategy = new DefaultTrackingStrategy(messages, new InMemoryPositionStore())) {
            var a = strategy.getBatch(tracker("a", "session-a", filter(1, "A"), 16));
            var independent = ConsumerFilter.select("another-app", 1, 1, Data.JSON_FORMAT,
                    List.of(new ConsumerFilter.PayloadType("B", null)), null, List.of());
            var b = strategy.getBatch(tracker("b", "session-b", independent, 16));
            messages.append(List.of(message("A", 0))).join();
            assertEquals("A", a.get(2, TimeUnit.SECONDS).getMessages().getFirst().getType());
            assertFalse(b.isDone());
            strategy.disconnectTrackers(tracker -> true, false);
            assertTrue(b.isCancelled());
        }
    }

    private static ConsumerFilter filter(long revision, String type) {
        return ConsumerFilter.select("application-subscription", revision, 1, Data.JSON_FORMAT,
                List.of(new ConsumerFilter.PayloadType(type, null)), null, List.of());
    }

    private static WebSocketTracker tracker(String id, String session, ConsumerFilter filter, int size) {
        Read read = new Read(MessageType.EVENT, "consumer", id, size, 900_000L,
                             null, false, true, false, true, -1L, null);
        read.setConsumerFilter(filter);
        read.setMaxScanLookBack(60_000L);
        return new WebSocketTracker(read, MessageType.EVENT, "client", session);
    }

    private static SerializedMessage message(String type, int segment) {
        SerializedMessage result = new SerializedMessage(new Data<>(new byte[0], type, 0, Data.JSON_FORMAT),
                Metadata.empty(), java.util.UUID.randomUUID().toString(), System.currentTimeMillis());
        result.setSegment(segment);
        return result;
    }

    private static final class PausingStore extends InMemoryMessageStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        PausingStore() { super(MessageType.EVENT); }

        @Override
        public MessageStoreBatch scanBatch(Long minIndex, int maxSize, boolean inclusive, long maxBytes,
                                           Predicate<? super SerializedMessage> predicate) {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!resume.await(2, TimeUnit.SECONDS)) throw new AssertionError("scan was not resumed");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            return super.scanBatch(minIndex, maxSize, inclusive, maxBytes, predicate);
        }
    }
}
