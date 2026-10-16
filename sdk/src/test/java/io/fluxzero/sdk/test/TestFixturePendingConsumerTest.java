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

package io.fluxzero.sdk.test;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.TestTask;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixturePendingConsumerTest {

    @Test
    void excludedWireInputCannotClearWorkInAnotherNamespaceWithTheSameMessageId() throws Exception {
        var fixture = TestFixture.createAsync();
        var serializer = fixture.getFluxzero().serializer();
        String namespace = fixture.getFluxzero().client().namespace();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var blueprint = new ConsumerBlueprint(serializer, () -> ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT,
                List.of(new ConsumerFilter.PayloadType(Accepted.class.getName(), null)), null, List.of()),
                new AtomicLong()::incrementAndGet)) {
            var config = ConsumerConfiguration.builder().name("pending").consumerBlueprint(blueprint).build();
            var tracker = mock(Tracker.class);
            when(tracker.getMessageType()).thenReturn(MessageType.EVENT);
            when(tracker.getConfiguration()).thenReturn(config);
            var interceptor = new TestFixture.GivenWhenThenInterceptor(fixture);
            var consumer = interceptor.intercept(batch -> {
                entered.countDown();
                await(release);
            }, tracker);
            var relevant = new Message(new Accepted(), Metadata.empty(), "same-id", Instant.now()).serialize(serializer);
            relevant.setIndex(1L);
            relevant.setSegment(64);
            interceptor.interceptClientDispatch(MessageType.EVENT, null, namespace, List.of(relevant));
            try (var handling = new TestTask(() -> consumer.accept(new MessageBatch(
                    new int[]{0, 128}, List.of(relevant), 1L, Position.newPosition(), true)), release::countDown)) {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                var excluded = new Message(new Ignored(), Metadata.empty(), "same-id", Instant.now()).serialize(serializer);
                interceptor.interceptClientDispatch(MessageType.EVENT, null, "isolated", List.of(excluded));
                assertFalse(fixture.checkConsumers());
                release.countDown();
                handling.awaitCompletion(Duration.ofSeconds(1));
            }
            assertTrue(fixture.checkConsumers());
        }
    }

    @Test
    void filteringOffSwitchRetainsWaitingForBroadBatchInterceptors() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = TestFixture.createAsync(DefaultFluxzero.builder()
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                        "fluxzero.tracking.consumerFiltering", "false")))
                .addBatchInterceptor((consumer, tracker) -> batch -> {
                    entered.countDown();
                    await(release);
                    consumer.accept(batch);
                }, MessageType.EVENT), new AcceptedHandler());
        try (var execution = new TestTask(() -> fixture.whenEvent(new Ignored()).expectNoErrors(), release::countDown)) {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            execution.awaitBlockedIn(TestFixture.class, "waitForConsumers", Duration.ofSeconds(1));
            assertFalse(fixture.checkConsumers());
            release.countDown();
            execution.awaitCompletion(Duration.ofSeconds(1));
        }
    }

    private static void await(CountDownLatch release) {
        try {
            assertTrue(release.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) { throw new AssertionError(e); }
    }

    private record Accepted() {}
    private record Ignored() {}
    private static class AcceptedHandler {
        @HandleEvent void on(Accepted event) {}
    }

    @Test
    void rejectsMessageObservedAfterItsPositionWasCompleted() {
        TestFixture.PendingConsumer pending = new TestFixture.PendingConsumer(List.of());

        pending.complete(batch("event-1", new int[]{0, 128}, 42L));
        pending.add(message("event-1"), 64, 42L);

        assertTrue(pending.isEmpty());
    }

    @Test
    void removesUnpositionedMessageWhenItsStoredPositionWasAlreadyCompleted() {
        TestFixture.PendingConsumer pending = new TestFixture.PendingConsumer(List.of());
        pending.add(message("event-1"), null, null);

        pending.complete(batch("other-event", new int[]{0, 128}, 42L));
        pending.recordStoredPosition("event-1", 64, 42L);

        assertTrue(pending.isEmpty());
    }

    @Test
    void preservesMessageFromAnotherSegment() {
        TestFixture.PendingConsumer pending = new TestFixture.PendingConsumer(List.of());
        pending.add(message("event-1"), 96, 42L);

        pending.complete(batch("other-event", new int[]{0, 64}, 42L));

        assertFalse(pending.isEmpty());
    }

    @Test
    void removesConsumedMessageWithoutAStoredPositionByIdentity() {
        TestFixture.PendingConsumer pending = new TestFixture.PendingConsumer(List.of());
        pending.add(message("event-1"), null, null);

        pending.complete(batch("event-1", new int[]{0, 128}, 42L));

        assertTrue(pending.isEmpty());
    }

    private MessageBatch batch(String messageId, int[] segment, long lastIndex) {
        SerializedMessage stored = mock(SerializedMessage.class);
        when(stored.getMessageId()).thenReturn(messageId);
        return new MessageBatch(segment, List.of(stored), lastIndex, Position.newPosition(), true);
    }

    private Message message(String messageId) {
        return new Message(messageId, Metadata.empty(), messageId, null);
    }
}
