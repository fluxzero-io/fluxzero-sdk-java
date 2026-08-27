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

package io.fluxzero.common.serialization;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerializedMessagePackCodecTest {

    @Test
    void decodesTheSharedBinaryMessageRepresentation() {
        List<SerializedMessage> messages = List.of(message(1L, "one"), message(2L, "two"));

        List<SerializedMessage> decoded = SerializedMessagePackCodec.decode(
                BinaryWire.encodeEnvelopes(messages));

        assertEquals(List.of(1L, 2L), decoded.stream().map(SerializedMessage::getIndex).toList());
        assertEquals("two", decoded.getLast().getMetadataValue("tenant"));
    }

    @Test
    void decodesConsecutivePersistedMessagePackMessages() throws Exception {
        byte[] bytes;
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            pack(packer, message(1L, "one"));
            pack(packer, message(2L, "two"));
            bytes = packer.toByteArray();
        }

        List<SerializedMessage> decoded = SerializedMessagePackCodec.decode(bytes);

        assertEquals(List.of(1L, 2L), decoded.stream().map(SerializedMessage::getIndex).toList());
        assertEquals(List.of("one", "two"), decoded.stream().map(SerializedMessage::getSource).toList());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.getLast().getData().getValue());
        assertEquals("two", decoded.getLast().getMetadataValue("tenant"));
    }

    @Test
    void rejectsUnsupportedPersistedVersions() throws Exception {
        byte[] bytes;
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(-3);
            bytes = packer.toByteArray();
        }

        assertThrows(IllegalArgumentException.class, () -> SerializedMessagePackCodec.decode(bytes));
    }

    private static void pack(MessagePacker packer, SerializedMessage message) throws IOException {
        packer.packInt(-2);
        byte[] payload = message.getData().getValue();
        packer.packInt(payload.length).addPayload(payload);
        packString(packer, message.getData().getType());
        packer.packInt(message.getData().getRevision());
        packString(packer, message.getData().getFormat());
        byte[] metadata = message.getMetadata().toData().getValue();
        packer.packInt(metadata.length).addPayload(metadata);
        packNullableInt(packer, message.getSegment());
        packNullableLong(packer, message.getIndex());
        packString(packer, message.getSource());
        packString(packer, message.getTarget());
        packNullableInt(packer, message.getRequestId());
        packNullableLong(packer, message.getTimestamp());
        packString(packer, message.getMessageId());
    }

    private static void packString(MessagePacker packer, String value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else {
            packer.packString(value);
        }
    }

    private static void packNullableInt(MessagePacker packer, Integer value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else {
            packer.packInt(value);
        }
    }

    private static void packNullableLong(MessagePacker packer, Long value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else {
            packer.packLong(value);
        }
    }

    private static SerializedMessage message(long index, String source) {
        return new SerializedMessage(
                new Data<>(new byte[]{1, 2, 3}, "type", 1, "application/json"),
                Metadata.of("tenant", source), 3, index, source, "target", null, 123L,
                "message-" + source, null);
    }
}
