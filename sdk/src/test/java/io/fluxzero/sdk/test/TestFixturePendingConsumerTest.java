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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixturePendingConsumerTest {

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
