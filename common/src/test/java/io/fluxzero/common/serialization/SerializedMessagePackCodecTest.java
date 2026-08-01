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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedMessagePackCodecTest {

    @Test
    void decodesNativeEventPayloadSequences() {
        SerializedMessage first = SerializedMessage.encode(message(1L, "one"));
        SerializedMessage second = SerializedMessage.encode(message(2L, "two"));
        byte[] bytes = new byte[first.envelopeSize() + second.envelopeSize()];
        first.copyEnvelopeTo(bytes, 0);
        second.copyEnvelopeTo(bytes, first.envelopeSize());

        List<SerializedMessage> decoded = SerializedMessagePackCodec.decode(bytes);

        assertEquals(List.of(1L, 2L), decoded.stream().map(SerializedMessage::getIndex).toList());
        assertEquals(List.of("one", "two"), decoded.stream().map(SerializedMessage::getSource).toList());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.getLast().getData().getValue());
        assertTrue(decoded.getFirst().isReusable());
    }

    private static SerializedMessage message(long index, String source) {
        return new SerializedMessage(
                new Data<>(new byte[]{1, 2, 3}, "type", 1, "application/json"),
                Metadata.of("tenant", source), 3, index, source, "target", null, 123L,
                "message-" + source, null);
    }
}
