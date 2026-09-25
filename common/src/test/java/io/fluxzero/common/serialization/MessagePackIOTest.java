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

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MessagePackIOTest {
    @Test
    void integerEncodingMatchesHistoricalWriterAtEveryWidthBoundary() throws Exception {
        long[] boundaries = {Long.MIN_VALUE, Integer.MIN_VALUE, Short.MIN_VALUE, Byte.MIN_VALUE,
                -33, -32, -1, 0, 127, 128, 255, 256, 65535, 65536,
                Integer.MAX_VALUE, 0xffffffffL, 0x100000000L, Long.MAX_VALUE};
        try (var expected = MessagePack.newDefaultBufferPacker(); var actual = new MessagePackIO.Writer()) {
            for (long value : boundaries) {
                expected.packLong(value);
                actual.packLong(value);
            }
            Random random = new Random(31);
            for (int i = 0; i < 10000; i++) {
                long value = random.nextLong();
                expected.packLong(value);
                actual.packLong(value);
            }
            assertArrayEquals(expected.toByteArray(), actual.toByteArray());
            try (var oldReader = MessagePack.newDefaultUnpacker(actual.toByteArray());
                 var reader = new MessagePackIO.Reader(expected.toByteArray())) {
                while (oldReader.hasNext()) {
                    assertEquals(oldReader.unpackLong(), reader.unpackLong());
                }
                assertFalse(reader.hasNext());
            }
        }
    }

    @Test
    void stringsBinaryBooleansNilAndArraysMatchHistoricalWriter() throws Exception {
        try (var old = MessagePack.newDefaultBufferPacker(); var writer = new MessagePackIO.Writer()) {
            for (int length : new int[]{0, 1, 15, 16, 31, 32, 127, 128, 255, 256, 511, 512, 8192, 65535, 65536}) {
                old.packArrayHeader(length);
                writer.packArrayHeader(length);
                for (String template : new String[]{"a", "é", "€", "😀", "a\ud800b\udc00c"}) {
                    String value = template.repeat(length);
                    old.packString(value);
                    writer.packString(value);
                }
                byte[] bytes = new byte[length];
                new Random(length).nextBytes(bytes);
                old.packBinaryHeader(length).writePayload(bytes);
                writer.packBinaryHeader(length).writePayload(bytes);
            }
            old.packBoolean(true).packBoolean(false).packNil();
            writer.packBoolean(true).packBoolean(false).packNil();
            assertArrayEquals(old.toByteArray(), writer.toByteArray());
            try (var reader = new MessagePackIO.Reader(old.toByteArray());
                 var reference = MessagePack.newDefaultUnpacker(writer.toByteArray())) {
                for (int length : new int[]{0, 1, 15, 16, 31, 32, 127, 128, 255, 256, 511, 512, 8192, 65535, 65536}) {
                    assertEquals(reference.unpackArrayHeader(), reader.unpackArrayHeader());
                    for (int i = 0; i < 5; i++) {
                        assertEquals(reference.unpackString(), reader.unpackString());
                    }
                    assertEquals(reference.unpackBinaryHeader(), reader.unpackBinaryHeader());
                    assertArrayEquals(reference.readPayload(length), reader.readPayload(length));
                }
                assertTrue(reader.unpackBoolean());
                assertFalse(reader.unpackBoolean());
                assertTrue(reader.tryUnpackNil());
                assertFalse(reader.hasNext());
            }
        }
    }

    @Test
    void acceptsNonMinimalIntegersAndHistoricalStringBinaryInterchange() throws Exception {
        byte[] bytes = {(byte) 0xd3, 0, 0, 0, 0, 0, 0, 0, 7, (byte) 0xc4, 1, 65, (byte) 0xa1, 66};
        try (var reader = new MessagePackIO.Reader(bytes)) {
            assertEquals(7, reader.unpackByte());
            assertEquals("A", reader.unpackString());
            assertEquals(1, reader.unpackBinaryHeader());
            assertArrayEquals(new byte[]{66}, reader.readPayload(1));
        }
    }

    @Test
    void malformedUtf8UsesHistoricalReplacement() throws Exception {
        for (byte[] value : new byte[][]{{(byte) 0xff}, {(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf0, (byte) 0x9f}}) {
            try (var writer = MessagePack.newDefaultBufferPacker()) {
                writer.packRawStringHeader(value.length).writePayload(value);
                try (var reference = MessagePack.newDefaultUnpacker(writer.toByteArray());
                     var reader = new MessagePackIO.Reader(writer.toByteArray())) {
                    assertEquals(reference.unpackString(), reader.unpackString());
                }
            }
        }
    }

    @Test
    void truncatedInputsCannotReadPastTheirRangeOrAllocateDeclaredPayload() throws Exception {
        try (var writer = new MessagePackIO.Writer()) {
            writer.packLong(Long.MAX_VALUE);
            byte[] bytes = writer.toByteArray();
            for (int length = 0; length < bytes.length; length++) {
                try (var reader = new MessagePackIO.Reader(bytes, 0, length)) {
                    assertThrows(IOException.class, reader::unpackLong);
                }
            }
        }
        for (byte tag : new byte[]{(byte) 0xc6, (byte) 0xdb}) {
            try (var reader = new MessagePackIO.Reader(new byte[]{tag, 0x7f, -1, -1, -1})) {
                assertThrows(IOException.class, reader::unpackString);
            }
        }
        try (var reader = new MessagePackIO.Reader(new byte[]{1})) {
            assertThrows(IOException.class, () -> reader.readPayload(-1));
            assertThrows(IOException.class, () -> reader.readPayload(Integer.MAX_VALUE));
        }
    }

    @Test
    void rejectsOverflowsAndWrongTypes() {
        for (byte[] bytes : new byte[][]{{(byte) 0xcf, -1, -1, -1, -1, -1, -1, -1, -1}, {(byte) 0xc0}, {(byte) 0xca, 0, 0, 0, 0}}) {
            assertThrows(IOException.class, () -> new MessagePackIO.Reader(bytes).unpackLong());
        }
        assertThrows(IOException.class, () -> new MessagePackIO.Reader(new byte[]{(byte) 0xcc, (byte) 128}).unpackByte());
        assertThrows(IOException.class, () -> new MessagePackIO.Reader(new byte[]{(byte) 0xce, -1, -1, -1, -1}).unpackInt());
        assertThrows(IOException.class, () -> new MessagePackIO.Reader(new byte[]{(byte) 0xdd, -1, -1, -1, -1}).unpackArrayHeader());
        assertThrows(IOException.class, () -> new MessagePackIO.Reader(new byte[]{1}).unpackBoolean());
        assertThrows(IOException.class, () -> new MessagePackIO.Reader(new byte[]{1}).unpackArrayHeader());
    }

    @Test
    void chunkedPayloadsPreserveCopiesAndReleaseBorrowedArrays() throws Exception {
        byte[] borrowed = new byte[8193];
        byte[] copied = new byte[8193];
        try (var writer = new MessagePackIO.Writer()) {
            writer.packInt(1).addPayload(borrowed).packInt(2).writePayload(copied).packInt(3);
            borrowed[0] = 42;
            copied[0] = 43;
            byte[] result = writer.toByteArray();
            assertEquals(2 * 8193 + 3, writer.getBufferSize());
            try (var reader = new MessagePackIO.Reader(result)) {
                assertEquals(1, reader.unpackInt());
                assertEquals(42, reader.readPayload(8193)[0]);
                assertEquals(2, reader.unpackInt());
                assertEquals(0, reader.readPayload(8193)[0]);
                assertEquals(3, reader.unpackInt());
            }
            writer.clear();
            writer.packInt(7);
            borrowed[0] = 44;
            assertEquals(42, result[1]);
            assertArrayEquals(new byte[]{7}, writer.toByteArray());
            assertEquals(1, writer.getBufferSize());
        }
    }

    @Test
    void manyBorrowedPayloadsAndHeadersKeepOrderAcrossChunkBoundaries() throws Exception {
        byte[] payload = new byte[8191];
        try (var writer = new MessagePackIO.Writer(); var reference = MessagePack.newDefaultBufferPacker()) {
            for (int i = 0; i < 3000; i++) {
                writer.packInt(i).addPayload(payload);
                reference.packInt(i).addPayload(payload);
            }
            assertArrayEquals(reference.toByteArray(), writer.toByteArray());
            writer.clear();
            writer.packString("reuse");
            assertEquals("reuse", new MessagePackIO.Reader(writer.toByteArray()).unpackString());
        }
    }

    @Test
    void rangeResetClearAndSnapshotsHaveIndependentOwnership() throws Exception {
        try (var writer = new MessagePackIO.Writer()) {
            writer.packInt(42);
            byte[] first = writer.toByteArray();
            writer.clear();
            writer.packInt(7);
            assertArrayEquals(new byte[]{42}, first);
            try (var reader = new MessagePackIO.Reader(new byte[]{-1, 42, -1}, 1, 1)) {
                assertFalse(reader.tryUnpackNil());
                assertEquals(42, reader.unpackInt());
                assertFalse(reader.hasNext());
                reader.reset(writer.toByteArray(), 0, 1);
                assertEquals(7, reader.unpackInt());
                reader.close();
                assertFalse(reader.hasNext());
            }
            assertThrows(IndexOutOfBoundsException.class, () -> new MessagePackIO.Reader(first, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> new MessagePackIO.Reader(first, 1, Integer.MAX_VALUE));
        }
    }
}
