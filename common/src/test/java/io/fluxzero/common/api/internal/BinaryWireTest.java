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

package io.fluxzero.common.api.internal;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryWireTest {

    @Test
    void roundTripsPrimitivesStringsArraysAndEnvelopeWithoutCopyingTheEnvelope() throws Exception {
        BinaryWire.Writer writer = new BinaryWire.Writer(1, 4096);
        SerializedMessage message = new SerializedMessage(
                new Data<>(new byte[]{1, 2, 3}, "type", 1, "json"),
                Metadata.of("key", "value"), "message", 1L);
        writer.writeBoolean(true);
        writer.writeInt(42);
        writer.writeLong(43L);
        writer.writeNullableInt(null);
        writer.writeNullableLong(44L);
        writer.writeString("hé 😀");
        writer.writeBytes(new byte[]{5, 6});
        writer.writeLongs(new long[]{7L, 8L});
        writer.writeEnvelope(message);

        byte[] encoded = writer.toByteArray();
        BinaryWire.Reader reader = new BinaryWire.Reader(encoded, 4096);
        assertTrue(reader.readBoolean());
        assertEquals(42, reader.readInt());
        assertEquals(43L, reader.readLong());
        assertNull(reader.readNullableInt());
        assertEquals(44L, reader.readNullableLong());
        assertEquals("hé 😀", reader.readString());
        assertArrayEquals(new byte[]{5, 6}, reader.readBytes());
        assertArrayEquals(new long[]{7L, 8L}, reader.readLongs(2));
        SerializedMessage decoded = reader.readEnvelope();
        assertSame(encoded, decoded.getData().byteArrayView().array());
        assertArrayEquals(message.getData().getValue(), decoded.getData().getValue());
        assertEquals(0, reader.available());
    }

    @Test
    void validatesExactSizesBooleanMarkersAndTruncation() throws Exception {
        BinaryWire.Writer exact = new BinaryWire.Writer(Integer.BYTES, 16);
        exact.writeInt(1);
        assertEquals(Integer.BYTES, exact.toExactByteArray().length);

        BinaryWire.Writer shortWriter = new BinaryWire.Writer(2, 16);
        shortWriter.writeByte(2);
        assertThrows(IllegalStateException.class, shortWriter::toExactByteArray);

        assertThrows(IOException.class,
                     () -> new BinaryWire.Reader(new byte[]{2}, 1).readBoolean());
        assertThrows(EOFException.class,
                     () -> new BinaryWire.Reader(new byte[]{0, 0, 0}, 4).readInt());
        assertThrows(IOException.class,
                     () -> new BinaryWire.Reader(new byte[]{0, 0, 0, 2}, 1).readBytes());
    }

    @Test
    void sizingPrimitivesExactlyMatchTheirWireEncoding() {
        for (String value : new String[]{null, "plain", "hé 😀", Character.toString((char) 0xd800)}) {
            BinaryWire.Writer writer = new BinaryWire.Writer(BinaryWire.stringSize(value), 128);
            writer.writeString(value);
            assertEquals(BinaryWire.stringSize(value), writer.toExactByteArray().length);
        }

        int expectedSize =
                BinaryWire.nullableLongSize(42L)
                + BinaryWire.nullableLongSize(null)
                + BinaryWire.bytesSize(new byte[]{1, 2, 3})
                + BinaryWire.bytesSize(null)
                + BinaryWire.longsSize(new long[]{4L, 5L})
                + BinaryWire.longsSize(null);
        BinaryWire.Writer writer = new BinaryWire.Writer(expectedSize, 128);
        writer.writeNullableLong(42L);
        writer.writeNullableLong(null);
        writer.writeBytes(new byte[]{1, 2, 3});
        writer.writeBytes(null);
        writer.writeLongs(new long[]{4L, 5L});
        writer.writeLongs(null);
        assertEquals(expectedSize, writer.toExactByteArray().length);
    }
}
