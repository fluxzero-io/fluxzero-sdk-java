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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.CUSTOM;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTrackingClientTest {

    private static final int[] FULL_SEGMENT = new int[]{0, MAX_SEGMENT};

    @Test
    @Timeout(10)
    void truncateClearsMessagesPositionsAndAllowsTrackingToContinue() throws Exception {
        try (LocalTrackingClient client = new LocalTrackingClient(CUSTOM, "orders", Duration.ofMinutes(5))) {
            client.append(STORED, message("before-1"), message("before-2")).join();

            MessageBatch beforeTruncate = read(client, "consumer");
            assertEquals(2, beforeTruncate.getSize());
            client.storePosition("consumer", beforeTruncate.getSegment(), beforeTruncate.getLastIndex()).join();
            assertFalse(client.getPosition("consumer").isNew(FULL_SEGMENT));

            client.truncate(STORED).join();

            assertTrue(client.readFromIndex(0, 10).isEmpty());
            assertTrue(client.getPosition("consumer").isNew(FULL_SEGMENT));

            client.append(STORED, message("after")).join();

            MessageBatch afterTruncate = read(client, "consumer");
            assertEquals(1, afterTruncate.getSize());
            client.storePosition("consumer", afterTruncate.getSegment(), afterTruncate.getLastIndex()).join();
            assertFalse(client.getPosition("consumer").isNew(FULL_SEGMENT));
        }
    }

    @Test
    @Timeout(10)
    void truncateDisconnectsActiveWaitingTracker() throws Exception {
        try (LocalTrackingClient client = new LocalTrackingClient(CUSTOM, "live", Duration.ofMinutes(5))) {
            CompletableFuture<MessageBatch> waitingBatch = client.read(
                    "tracker", null, config("consumer").toBuilder()
                            .maxWaitDuration(Duration.ofSeconds(30)).build());
            Thread.sleep(100L);
            assertFalse(waitingBatch.isDone());

            client.truncate(STORED).join();

            MessageBatch finalBatch = waitingBatch.get(2, TimeUnit.SECONDS);
            assertEquals(0, finalBatch.getSize());
            assertArrayEquals(new int[]{0, 0}, finalBatch.getSegment());
            assertTrue(finalBatch.isCaughtUp());

            client.append(STORED, message("after")).join();
            assertEquals(1, read(client, "consumer").getSize());
        }
    }

    @Test
    @Timeout(10)
    void truncateWorksForStandardMessageTypes() throws Exception {
        try (LocalTrackingClient client = new LocalTrackingClient(EVENT, null, Duration.ofMinutes(5))) {
            client.append(STORED, message("before")).join();
            MessageBatch beforeTruncate = read(client, "consumer");
            assertEquals(1, beforeTruncate.getSize());
            client.storePosition("consumer", beforeTruncate.getSegment(), beforeTruncate.getLastIndex()).join();

            client.truncate(STORED).join();

            assertTrue(client.readFromIndex(0, 10).isEmpty());
            assertTrue(client.getPosition("consumer").isNew(FULL_SEGMENT));
        }
    }

    @Test
    @Timeout(10)
    void cachedTrackerWaitsUntilMaxWaitWhenAnchorIsCached() throws Exception {
        try (CountingLocalTrackingClient delegate = new CountingLocalTrackingClient(
                CUSTOM, "cached", Duration.ofMinutes(5))) {
            try (TestCachingTrackingClient client = new TestCachingTrackingClient(delegate)) {
                SerializedMessage anchor = message("anchor");
                delegate.append(STORED, anchor).join();
                client.cache(anchor);

                CompletableFuture<MessageBatch> waitingBatch = client.read(
                        "cached-tracker", anchor.getIndex(), config("cached-consumer").toBuilder()
                                .maxWaitDuration(Duration.ofMillis(250)).build());
                Thread.sleep(100L);
                assertFalse(waitingBatch.isDone());

                MessageBatch finalBatch = waitingBatch.get(2, TimeUnit.SECONDS);
                assertEquals(0, finalBatch.getSize());
                assertTrue(finalBatch.isCaughtUp());
                assertEquals(0, delegate.cachedTrackerReadCalls.get());
            }
        }
    }

    @Test
    @Timeout(10)
    void cachedTrackerReturnsImmediatelyWithoutDelegateReadWhenMaxWaitIsZero() throws Exception {
        try (CountingLocalTrackingClient delegate = new CountingLocalTrackingClient(
                CUSTOM, "cached-zero", Duration.ofMinutes(5))) {
            try (TestCachingTrackingClient client = new TestCachingTrackingClient(delegate)) {
                SerializedMessage anchor = message("anchor");
                delegate.append(STORED, anchor).join();
                client.cache(anchor);

                MessageBatch batch = client.read(
                        "cached-tracker", anchor.getIndex(), config("cached-zero-consumer").toBuilder()
                                .maxWaitDuration(Duration.ZERO).build()).get(2, TimeUnit.SECONDS);

                assertEquals(0, batch.getSize());
                assertTrue(batch.isCaughtUp());
                assertEquals(0, delegate.cachedTrackerReadCalls.get());
            }
        }
    }

    @Test
    @Timeout(10)
    void cachedTrackerReturnsImmediatelyWhenZeroMaxWaitClaimHasNoSegment() throws Exception {
        try (CountingLocalTrackingClient delegate = new CountingLocalTrackingClient(
                CUSTOM, "cached-zero-claim", Duration.ofMinutes(5))) {
            try (TestCachingTrackingClient client = new TestCachingTrackingClient(delegate)) {
                SerializedMessage anchor = message("anchor");
                delegate.append(STORED, anchor).join();
                client.cache(anchor);
                delegate.claimSegment("segment-owner", anchor.getIndex(),
                                      config("cached-zero-consumer")).get(2, TimeUnit.SECONDS);

                MessageBatch batch = client.read(
                        "cached-tracker", anchor.getIndex(), config("cached-zero-consumer").toBuilder()
                                .maxWaitDuration(Duration.ZERO).build()).get(2, TimeUnit.SECONDS);

                assertEquals(0, batch.getSize());
                assertTrue(batch.isCaughtUp());
                assertArrayEquals(new int[]{0, 0}, batch.getSegment());
                assertEquals(0, delegate.cachedTrackerReadCalls.get());
            }
        }
    }

    @Test
    @Timeout(10)
    void cachedTrackerWaitsForCacheUpdateBeforeDelegateFallback() throws Exception {
        try (CountingLocalTrackingClient delegate = new CountingLocalTrackingClient(
                CUSTOM, "cached-update", Duration.ofMinutes(5))) {
            try (TestCachingTrackingClient client = new TestCachingTrackingClient(delegate)) {
                SerializedMessage anchor = message("anchor");
                delegate.append(STORED, anchor).join();
                client.cache(anchor);

                CompletableFuture<MessageBatch> waitingBatch = client.read(
                        "cached-tracker", anchor.getIndex(), config("cached-update-consumer").toBuilder()
                                .maxWaitDuration(Duration.ofSeconds(5)).build());

                SerializedMessage next = message("next");
                Thread.sleep(100L);
                delegate.append(STORED, next).join();
                client.cache(next);

                MessageBatch batch = waitingBatch.get(2, TimeUnit.SECONDS);
                assertEquals(List.of(next), batch.getMessages());
                assertEquals(0, delegate.cachedTrackerReadCalls.get());
            }
        }
    }

    @Test
    @Timeout(10)
    void cachedTrackerUsesDelegateWhenAnchorIsNotCachedYet() throws Exception {
        try (CountingLocalTrackingClient delegate = new CountingLocalTrackingClient(
                CUSTOM, "cached-anchor-update", Duration.ofMinutes(5))) {
            try (TestCachingTrackingClient client = new TestCachingTrackingClient(delegate)) {
                SerializedMessage anchor = message("anchor");
                delegate.append(STORED, anchor).join();

                CompletableFuture<MessageBatch> waitingBatch = client.read(
                        "cached-tracker", anchor.getIndex(), config("cached-anchor-update-consumer").toBuilder()
                                .maxWaitDuration(Duration.ofSeconds(5)).build());

                SerializedMessage next = message("next");
                Thread.sleep(100L);
                delegate.append(STORED, next).join();

                MessageBatch batch = waitingBatch.get(2, TimeUnit.SECONDS);
                assertEquals(List.of(next), batch.getMessages());
                assertEquals(1, delegate.cachedTrackerReadCalls.get());
            }
        }
    }

    @Test
    @Timeout(10)
    void cachedTrackerDeliversAllCachedMessagesAcrossBatches() throws Exception {
        try (LocalTrackingClient delegate = new LocalTrackingClient(CUSTOM, "cached-many", Duration.ofMinutes(5))) {
            try (TestCachingTrackingClient client = new TestCachingTrackingClient(delegate, 10_000)) {
                SerializedMessage[] messages = java.util.stream.IntStream.range(0, 5_000)
                        .mapToObj(i -> message("message-" + i))
                        .toArray(SerializedMessage[]::new);
                delegate.append(STORED, messages).join();
                client.cache(messages);

                ConsumerConfiguration config = config("cached-many-consumer").toBuilder()
                        .maxFetchSize(256)
                        .maxWaitDuration(Duration.ZERO)
                        .build();
                String trackerId = "cached-many-tracker";
                Long lastIndex = null;
                int consumed = 0;
                for (int i = 0; i < 100 && consumed < messages.length; i++) {
                    MessageBatch batch = client.read(trackerId, lastIndex, config).get(2, TimeUnit.SECONDS);
                    if (!batch.isEmpty()) {
                        consumed += batch.getSize();
                        lastIndex = batch.getLastIndex();
                        client.storePosition(config.getName(), batch.getSegment(), batch.getLastIndex()).join();
                    }
                }

                assertEquals(messages.length, consumed);
            }
        }
    }

    @Test
    @Timeout(15)
    void cachedTrackerCompletesManyLiveConsumers() throws Exception {
        int consumerCount = 20;
        int messageCount = 2_000;
        CountDownLatch latch = new CountDownLatch(consumerCount * messageCount);
        try (LocalTrackingClient delegate = new LocalTrackingClient(CUSTOM, "cached-live", Duration.ofMinutes(5))) {
            try (CachingTrackingClient client = new CachingTrackingClient(delegate, 20_000)) {
                List<io.fluxzero.common.Registration> registrations = java.util.stream.IntStream.range(
                                0, consumerCount)
                        .mapToObj(i -> DefaultTracker.start(
                                messages -> countDown(latch, messages.size()),
                                config("cached-live-consumer-" + i).toBuilder()
                                        .maxFetchSize(128)
                                        .maxWaitDuration(Duration.ofSeconds(2))
                                        .exclusive(false)
                                        .minIndex(-1L)
                                        .build(),
                                client))
                        .toList();
                try {
                    Thread.sleep(100L);
                    SerializedMessage[] messages = java.util.stream.IntStream.range(0, messageCount)
                            .mapToObj(i -> message("live-message-" + i))
                            .toArray(SerializedMessage[]::new);
                    delegate.append(STORED, messages).join();

                    assertTrue(latch.await(10, TimeUnit.SECONDS),
                               "Timed out with " + latch.getCount() + " messages remaining");
                } finally {
                    registrations.forEach(io.fluxzero.common.Registration::cancel);
                }
            }
        }
    }

    private static MessageBatch read(LocalTrackingClient client, String consumer) {
        return client.readAndWait("tracker-" + UUID.randomUUID(), null, config(consumer));
    }

    private static ConsumerConfiguration config(String consumer) {
        return ConsumerConfiguration.builder().name(consumer).build();
    }

    private static SerializedMessage message(String value) {
        return new SerializedMessage(new Data<>(value.getBytes(UTF_8), String.class.getName(), 0, "text/plain"),
                                     Metadata.empty(), value + "-" + UUID.randomUUID(),
                                     Instant.now().toEpochMilli());
    }

    private static void countDown(CountDownLatch latch, int count) {
        for (int i = 0; i < count; i++) {
            latch.countDown();
        }
    }

    private static class TestCachingTrackingClient extends CachingTrackingClient {
        private TestCachingTrackingClient(TrackingClient delegate) {
            super(delegate, 10);
        }

        private TestCachingTrackingClient(TrackingClient delegate, int maxCacheSize) {
            super(delegate, maxCacheSize);
        }

        private void cache(SerializedMessage... messages) {
            cacheNewMessages(List.of(messages));
        }
    }

    private static class CountingLocalTrackingClient extends LocalTrackingClient {
        private final AtomicInteger cachedTrackerReadCalls = new AtomicInteger();

        CountingLocalTrackingClient(io.fluxzero.common.MessageType messageType, String topic,
                                    Duration messageExpiration) {
            super(messageType, topic, messageExpiration);
        }

        @Override
        public CompletableFuture<MessageBatch> read(String trackerId, Long lastIndex, ConsumerConfiguration config) {
            if ("cached-tracker".equals(trackerId)) {
                cachedTrackerReadCalls.incrementAndGet();
            }
            return super.read(trackerId, lastIndex, config);
        }
    }
}
