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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static DeserializingMessage message(String payload) {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize(payload), Metadata.empty(), "message-id", 0L);
        return new DeserializingMessage(serializedMessage, m -> payload, MessageType.EVENT, null, serializer);
    }
}
