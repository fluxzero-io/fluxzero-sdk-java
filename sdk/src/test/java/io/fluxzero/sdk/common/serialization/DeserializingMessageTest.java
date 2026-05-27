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

package io.fluxzero.sdk.common.serialization;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeserializingMessageTest {

    @Test
    void returnsVoidPayloadClassWhenDelegatePayloadClassIsUnknown() {
        Serializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                new Data<>("Boom!".getBytes(), null, 0, "unknown"),
                null,
                "message-id",
                0L);

        DeserializingMessage message = new DeserializingMessage(
                new DeserializingObject<>(serializedMessage, type -> "Boom!"),
                MessageType.WEBREQUEST,
                null,
                serializer);

        message.getPayload();

        assertEquals(Void.class, message.getPayloadClass());
        assertDoesNotThrow(message::toString);
    }

    @Test
    void applySetsCurrentMessageAndCompletesBatch() {
        DeserializingMessage message = message("payload");
        List<Throwable> completions = new ArrayList<>();

        String result = message.apply(current -> {
            assertSame(message, DeserializingMessage.getCurrent());
            DeserializingMessage.whenBatchCompletes(completions::add);
            return current.getPayloadAs(String.class);
        });

        assertEquals("payload", result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(1, completions.size());
        assertNull(completions.getFirst());
    }

    @Test
    void nestedApplyCompletesWithOuterBatch() {
        DeserializingMessage outer = message("outer");
        DeserializingMessage inner = message("inner");
        List<Throwable> completions = new ArrayList<>();

        String result = outer.apply(current -> {
            DeserializingMessage.whenBatchCompletes(completions::add);
            String innerResult = inner.apply(nested -> {
                assertSame(inner, DeserializingMessage.getCurrent());
                DeserializingMessage.whenBatchCompletes(completions::add);
                return nested.getPayloadAs(String.class);
            });
            assertEquals("inner", innerResult);
            assertSame(outer, DeserializingMessage.getCurrent());
            assertEquals(List.of(), completions);
            return current.getPayloadAs(String.class);
        });

        assertEquals("outer", result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(2, completions.size());
        assertNull(completions.get(0));
        assertNull(completions.get(1));
    }

    @Test
    void applyCompletesBatchWithErrorWhenActionFails() {
        DeserializingMessage message = message("payload");
        List<Throwable> completions = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException result = assertThrows(IllegalStateException.class, () -> message.apply(current -> {
            DeserializingMessage.whenBatchCompletes(completions::add);
            throw failure;
        }));

        assertSame(failure, result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(List.of(failure), completions);
    }

    @Test
    void forEachInBatchSetsCurrentMessageAndCompletesBatchOnce() {
        DeserializingMessage first = message("first");
        DeserializingMessage second = message("second");
        List<String> payloads = new ArrayList<>();
        List<Throwable> completions = new ArrayList<>();

        DeserializingMessage.forEachInBatch(List.of(first, second), message -> {
            assertSame(message, DeserializingMessage.getCurrent());
            DeserializingMessage.whenBatchCompletes(completions::add);
            payloads.add(message.getPayloadAs(String.class));
            assertEquals(List.of(), completions);
        });

        assertEquals(List.of("first", "second"), payloads);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(2, completions.size());
        assertNull(completions.getFirst());
        assertNull(completions.get(1));
    }

    @Test
    void forEachInBatchCompletesBatchWithErrorWhenActionFails() {
        DeserializingMessage message = message("payload");
        List<Throwable> completions = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException result = assertThrows(IllegalStateException.class, () ->
                DeserializingMessage.forEachInBatch(List.of(message), current -> {
                    DeserializingMessage.whenBatchCompletes(completions::add);
                    throw failure;
                }));

        assertSame(failure, result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(List.of(failure), completions);
    }

    @Test
    void forEachInBatchRestoresCurrentBetweenCustomIterableItems() {
        DeserializingMessage first = message("first");
        DeserializingMessage second = message("second");
        Iterable<DeserializingMessage> iterable = () -> new java.util.Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                assertNull(DeserializingMessage.getCurrent());
                return index < 2;
            }

            @Override
            public DeserializingMessage next() {
                assertNull(DeserializingMessage.getCurrent());
                return index++ == 0 ? first : second;
            }
        };

        DeserializingMessage.forEachInBatch(iterable, message ->
                assertSame(message, DeserializingMessage.getCurrent()));

        assertNull(DeserializingMessage.getCurrent());
    }

    @Test
    void withMetadataSharesMemoizedPayload() {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize("serialized"), Metadata.empty(), "message-id", 0L);
        AtomicInteger calls = new AtomicInteger();
        DeserializingMessage message = new DeserializingMessage(
                new DeserializingObject<>(serializedMessage, type -> {
                    calls.incrementAndGet();
                    return "payload";
                }),
                MessageType.EVENT,
                null,
                serializer);

        DeserializingMessage withMetadata = message.withMetadata(Metadata.of("key", "value"));

        assertFalse(message.isDeserialized());
        assertFalse(withMetadata.isDeserialized());
        assertEquals("payload", message.getPayload());
        assertEquals("payload", withMetadata.getPayload());
        assertEquals(1, calls.get());
        assertTrue(message.isDeserialized());
        assertTrue(withMetadata.isDeserialized());
    }

    @Test
    void deserializingObjectCachesDefaultAndTypedPayloads() {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize("serialized"), Metadata.empty(), "message-id", 0L);
        AtomicInteger calls = new AtomicInteger();
        DeserializingObject<byte[], SerializedMessage> object = new DeserializingObject<>(
                serializedMessage, type -> "%s-%d".formatted(type, calls.incrementAndGet()));

        assertFalse(object.isDeserialized());
        assertEquals("class java.lang.Object-1", object.getPayload());
        assertEquals("class java.lang.Object-1", object.getPayload());
        assertTrue(object.isDeserialized());
        assertEquals("class java.lang.String-2", object.getPayloadAs(String.class));
        assertEquals("class java.lang.String-2", object.getPayloadAs(String.class));
        assertEquals(2, calls.get());
    }

    private static DeserializingMessage message(String payload) {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize(payload), Metadata.empty(), "message-id", 0L);
        return new DeserializingMessage(serializedMessage, m -> payload, MessageType.EVENT, null, serializer);
    }
}
