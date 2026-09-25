/*
 * MessagePack for Java — selected tests adapted for Fluxzero MessagePackIO.
 * Original tests: msgpack/msgpack-java, v0.9.12, ca3fa54c8dc3a020ab554a7289af0927bbbf1f62.
 * Sources: MessagePackTest.scala, MessagePackerTest.scala, MessageUnpackerTest.scala.
 * Adaptations: JUnit, deterministic data, comparisons of both codecs instead of self-round-trips.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package io.fluxzero.common.serialization;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MessagePackUpstreamCompatibilityTest {
    @Test
    void upstreamIntegerSamplesAndAllShortsMatchAcrossLongIntAndByte() throws Exception {
        long[] samples = {Integer.MIN_VALUE - 10L, -65535, -8191, -1024, -255, -127, -63, -31,
                -15, -7, -3, -1, 0, 2, 4, 8, 16, 32, 64, 128, 256, 1024, 8192, 65536,
                Integer.MAX_VALUE + 10L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long sample : samples) compareInteger(sample);
        for (long sample = Short.MIN_VALUE; sample <= Short.MAX_VALUE; sample++) compareInteger(sample);
    }

    private static void compareInteger(long value) throws Exception {
        try (var old = MessagePack.newDefaultBufferPacker(); var current = new MessagePackIO.Writer()) {
            old.packLong(value);
            current.packLong(value);
            assertArrayEquals(old.toByteArray(), current.toByteArray());
            byte[] bytes = old.toByteArray();
            assertEquals(value, new MessagePackIO.Reader(bytes).unpackLong());
            compare(bytes, MessageUnpacker::unpackInt, MessagePackIO.Reader::unpackInt);
            compare(bytes, MessageUnpacker::unpackByte, MessagePackIO.Reader::unpackByte);
        }
    }

    @Test
    void allIntegerWidthsIncludingNonMinimalEncodingsHaveSameAcceptance() throws Exception {
        Random random = new Random(3110);
        int[] tags = {0xcc, 0xcd, 0xce, 0xcf, 0xd0, 0xd1, 0xd2, 0xd3};
        int[] widths = {1, 2, 4, 8, 1, 2, 4, 8};
        for (int t = 0; t < tags.length; t++) {
            for (int sample = 0; sample < 2048; sample++) {
                byte[] bytes = new byte[widths[t] + 1];
                random.nextBytes(bytes);
                bytes[0] = (byte) tags[t];
                if (sample < 4) Arrays.fill(bytes, 1, bytes.length, (byte) new int[]{0, 127, 128, 255}[sample]);
                compare(bytes, MessageUnpacker::unpackLong, MessagePackIO.Reader::unpackLong);
                compare(bytes, MessageUnpacker::unpackInt, MessagePackIO.Reader::unpackInt);
                compare(bytes, MessageUnpacker::unpackByte, MessagePackIO.Reader::unpackByte);
                for (int end = 0; end < bytes.length; end++) {
                    compare(Arrays.copyOf(bytes, end), MessageUnpacker::unpackLong, MessagePackIO.Reader::unpackLong);
                }
            }
        }
    }

    @Test
    void upstreamNilPeekCasesDoNotAdvanceNonNilInput() throws Exception {
        for (byte[] bytes : new byte[][]{{}, {(byte) 0xc0}, {(byte) 0xa3, 'v', 'a', 'l'},
                {(byte) 0xc0, (byte) 0xa3, 'v', 'a', 'l'}}) {
            compare(bytes, r -> {
                boolean nil = r.tryUnpackNil();
                return nil + ":" + (r.hasNext() ? r.unpackString() : "end");
            }, r -> {
                boolean nil = r.tryUnpackNil();
                return nil + ":" + (r.hasNext() ? r.unpackString() : "end");
            });
        }
    }

    @Test
    void upstreamLargeArraysAndMultibyteCharactersCrossBufferBoundaries() throws Exception {
        for (int length : new int[]{8191, 8192, 8193, 16383, 16384, 16385}) {
            try (var old = MessagePack.newDefaultBufferPacker(); var current = new MessagePackIO.Writer()) {
                old.packArrayHeader(2).packString("l".repeat(length)).packInt(1);
                current.packArrayHeader(2).packString("l".repeat(length)).packInt(1);
                assertArrayEquals(old.toByteArray(), current.toByteArray());
                compare(old.toByteArray(), r -> r.unpackArrayHeader() + r.unpackString() + r.unpackInt(),
                        r -> r.unpackArrayHeader() + r.unpackString() + r.unpackInt());
            }
        }
        String[] strings = {"\u3042", "a\u3042", "\u3042a", "\u3042\u3044\u3046\u3048\u304A\u304B\u304D\u304F\u3051\u3053\u3055\u3057\u3059\u305B\u305D"};
        for (int padding : new int[]{8185, 8186, 8187, 8188, 16377, 16378, 16379, 16380}) {
            for (String value : strings) {
                try (var old = MessagePack.newDefaultBufferPacker(); var current = new MessagePackIO.Writer()) {
                    byte[] pad = new byte[padding];
                    old.packBinaryHeader(padding).writePayload(pad).packString(value).packInt(1);
                    current.packBinaryHeader(padding).writePayload(pad).packString(value).packInt(1);
                    assertArrayEquals(old.toByteArray(), current.toByteArray());
                    compare(old.toByteArray(), r -> {
                        r.readPayload(r.unpackBinaryHeader());
                        return r.unpackString() + r.unpackInt();
                    }, r -> {
                        r.readPayload(r.unpackBinaryHeader());
                        return r.unpackString() + r.unpackInt();
                    });
                }
            }
        }
        try (var old = MessagePack.newDefaultBufferPacker(); var current = new MessagePackIO.Writer()) {
            for (int i = 0; i < 1170; i++) {
                old.packLong(0x0011223344556677L).packString("hello world");
                current.packLong(0x0011223344556677L).packString("hello world");
            }
            assertArrayEquals(old.toByteArray(), current.toByteArray());
            try (var r = new MessagePackIO.Reader(old.toByteArray())) {
                for (int i = 0; i < 1170; i++) {
                    assertEquals(0x0011223344556677L, r.unpackLong());
                    assertEquals("hello world", r.unpackString());
                }
                assertFalse(r.hasNext());
            }
        }
    }

    @Test
    void readsHistoricalStr16Str32AndBinFormsRegardlessOfMinimalWidth() throws Exception {
        for (int tag : new int[]{0xda, 0xdb, 0xc5, 0xc6}) {
            var bytes = ByteBuffer.allocate((tag == 0xda || tag == 0xc5 ? 3 : 5) + 5);
            bytes.put((byte) tag);
            if (tag == 0xda || tag == 0xc5) bytes.putShort((short) 5); else bytes.putInt(5);
            bytes.put(new byte[]{'h', 'e', 'l', 'l', 'o'});
            compare(bytes.array(), MessageUnpacker::unpackString, MessagePackIO.Reader::unpackString);
        }
        for (String value : new String[]{"small string", "Hello. This is a string longer than 32 characters!"}) {
            try (var old = new MessagePack.PackerConfig().withStr8FormatSupport(false)
                    .withSmallStringOptimizationThreshold(0).newBufferPacker()) {
                old.packString(value);
                assertEquals(value, new MessagePackIO.Reader(old.toByteArray()).unpackString());
            }
        }
    }

    @Test
    void generatedUtf16WritesAndArbitraryUtf8ReadsMatchUpstreamDefaults() throws Exception {
        Random random = new Random(3111);
        for (int sample = 0; sample < 4096; sample++) {
            int length = sample < 12 ? new int[]{0, 31, 32, 255, 256, 511, 512, 8191, 8192, 65535, 65536, 100000}[sample]
                    : random.nextInt(1024);
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) chars[i] = (char) random.nextInt(65536);
            String value = new String(chars);
            try (var old = MessagePack.newDefaultBufferPacker(); var current = new MessagePackIO.Writer()) {
                old.packString(value);
                current.packString(value);
                assertArrayEquals(old.toByteArray(), current.toByteArray(), "UTF-16 seed 3111, sample " + sample);
            }
            byte[] raw = new byte[length];
            random.nextBytes(raw);
            try (var old = MessagePack.newDefaultBufferPacker()) {
                old.packRawStringHeader(length).writePayload(raw);
                compare(old.toByteArray(), MessageUnpacker::unpackString, MessagePackIO.Reader::unpackString);
            }
        }
    }

    private static void compare(byte[] bytes, OldRead old, NewRead current) throws Exception {
        Object expected;
        try (var r = MessagePack.newDefaultUnpacker(bytes)) {
            try {
                expected = old.read(r);
            } catch (IOException | org.msgpack.core.MessagePackException e) {
                try (var n = new MessagePackIO.Reader(bytes)) {
                    assertThrows(IOException.class, () -> current.read(n), java.util.HexFormat.of().formatHex(bytes));
                }
                return;
            }
            try (var n = new MessagePackIO.Reader(bytes)) {
                assertEquals(expected, current.read(n));
                assertEquals(r.hasNext(), n.hasNext());
            }
        }
    }

    private interface OldRead { Object read(MessageUnpacker reader) throws Exception; }
    private interface NewRead { Object read(MessagePackIO.Reader reader) throws Exception; }
}
