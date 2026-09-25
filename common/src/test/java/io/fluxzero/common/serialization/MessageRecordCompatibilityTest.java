/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.serialization;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageRecordCompatibilityTest {
    @Test
    void allSupportedPersistedVersionsCrossReadCompleteBatchesAgainstFrozenReader() throws Exception {
        for (int version = 0; version <= 2; version++) {
            for (int sample = 0; sample < 256; sample++) {
                byte[] bytes = batch(version, sample);
                assertEquals(LegacySerializedMessagePackCodec.decode(bytes), SerializedMessagePackCodec.decode(bytes),
                             "version " + version + ", sample " + sample);
                byte[] slice = new byte[bytes.length + 11];
                System.arraycopy(bytes, 0, slice, 5, bytes.length);
                assertEquals(LegacySerializedMessagePackCodec.decode(slice, 5, bytes.length),
                             SerializedMessagePackCodec.decode(slice, 5, bytes.length));
                assertArrayEquals(bytes, Arrays.copyOfRange(slice, 5, 5 + bytes.length));
            }
            // Every truncation of a complete record must preserve success/failure, including empty input.
            byte[] bytes = batch(version, 3);
            for (int length = 0; length < bytes.length; length++) {
                byte[] prefix = Arrays.copyOf(bytes, length);
                java.util.List<io.fluxzero.common.api.SerializedMessage> expected;
                try {
                    expected = LegacySerializedMessagePackCodec.decode(prefix);
                } catch (IllegalArgumentException ignored) {
                    assertThrows(IllegalArgumentException.class, () -> SerializedMessagePackCodec.decode(prefix));
                    continue;
                }
                assertEquals(expected, SerializedMessagePackCodec.decode(prefix));
            }
        }
    }

    private static byte[] batch(int version, int sample) throws Exception {
        Random random = new Random(3102L + sample);
        try (var w = MessagePack.newDefaultBufferPacker()) {
            for (int record = 0; record <= sample % 4; record++) {
                byte[] payload = new byte[sample % 31 == 0 ? 8193 : sample % 128];
                random.nextBytes(payload);
                if (version > 0) w.packInt(-version);
                w.packInt(payload.length).addPayload(payload);
                nullableString(w, sample, "type-漢");
                w.packInt(random.nextInt());
                if (version > 0) nullableString(w, sample + 1, "application/custom");
                if (version == 2) {
                    byte[] metadata = (sample % 2 == 0 ? io.fluxzero.common.api.Metadata.empty()
                            : io.fluxzero.common.api.Metadata.of("key", "é", "number", 42)).toData().getValue();
                    w.packInt(metadata.length).addPayload(metadata);
                } else {
                    w.packInt(2).packString("key").packString("é").packString("other").packString("42");
                }
                if (sample % 2 == 0) w.packNil(); else w.packInt(random.nextInt());
                if (version > 0) nullableLong(w, sample, random.nextLong());
                nullableString(w, sample + 2, "source");
                nullableString(w, sample + 3, "target");
                if (sample % 3 == 0) w.packNil(); else w.packInt(random.nextInt());
                nullableLong(w, sample + 1, random.nextLong());
                nullableString(w, sample + 4, "message-" + record);
            }
            return w.toByteArray();
        }
    }

    private static void nullableString(MessagePacker w, int sample, String value) throws IOException {
        if (sample % 3 == 0) w.packNil(); else w.packString(value);
    }

    private static void nullableLong(MessagePacker w, int sample, long value) throws IOException {
        if (sample % 3 == 0) w.packNil(); else w.packLong(value);
    }
}
