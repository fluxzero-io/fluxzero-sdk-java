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

import io.fluxzero.common.api.SerializedMessage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Allocation-bounded primitive I/O shared by compact websocket protocols. */
public final class BinaryWire {

    private BinaryWire() {
    }

    public static int peekInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
               | (bytes[offset + 1] & 0xff) << 16
               | (bytes[offset + 2] & 0xff) << 8
               | bytes[offset + 3] & 0xff;
    }

    public static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static final class Writer {
        private final int maximumSize;
        private byte[] bytes;
        private int position;

        public Writer(int initialSize, int maximumSize) {
            if (initialSize < 0 || initialSize > maximumSize) {
                throw new IllegalArgumentException("Invalid binary wire buffer size " + initialSize);
            }
            this.maximumSize = maximumSize;
            this.bytes = new byte[initialSize];
        }

        public void writeByte(int value) {
            ensure(1);
            bytes[position++] = (byte) value;
        }

        public void writeBoolean(boolean value) {
            writeByte(value ? 1 : 0);
        }

        public void writeInt(int value) {
            ensure(Integer.BYTES);
            bytes[position++] = (byte) (value >>> 24);
            bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8);
            bytes[position++] = (byte) value;
        }

        public void writeLong(long value) {
            ensure(Long.BYTES);
            bytes[position++] = (byte) (value >>> 56);
            bytes[position++] = (byte) (value >>> 48);
            bytes[position++] = (byte) (value >>> 40);
            bytes[position++] = (byte) (value >>> 32);
            bytes[position++] = (byte) (value >>> 24);
            bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8);
            bytes[position++] = (byte) value;
        }

        public void writeNullableInt(Integer value) {
            writeBoolean(value != null);
            if (value != null) {
                writeInt(value);
            }
        }

        public void writeNullableLong(Long value) {
            writeBoolean(value != null);
            if (value != null) {
                writeLong(value);
            }
        }

        public void writeString(String value) {
            if (value == null) {
                writeInt(-1);
                return;
            }
            int start = position;
            writeInt(value.length());
            ensure(value.length());
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current > 0x7f) {
                    position = start;
                    writeBytes(value.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                bytes[position++] = (byte) current;
            }
        }

        public void writeBytes(byte[] value) {
            if (value == null) {
                writeInt(-1);
            } else {
                writeBytes(value, 0, value.length);
            }
        }

        public void writeBytes(byte[] value, int offset, int length) {
            writeInt(length);
            writeRaw(value, offset, length);
        }

        public void writeRaw(byte[] value) {
            writeRaw(value, 0, value.length);
        }

        public void writeRaw(byte[] value, int offset, int length) {
            ensure(length);
            System.arraycopy(value, offset, bytes, position, length);
            position += length;
        }

        public void writeLongs(long[] values) {
            if (values == null) {
                writeInt(-1);
                return;
            }
            writeInt(values.length);
            ensure(Math.multiplyExact(values.length, Long.BYTES));
            for (long value : values) {
                writeLong(value);
            }
        }

        public void writeEnvelope(SerializedMessage message) {
            SerializedMessage envelope = SerializedMessage.encode(message);
            writeInt(envelope.envelopeSize());
            ensure(envelope.envelopeSize());
            envelope.copyEnvelopeTo(bytes, position);
            position += envelope.envelopeSize();
        }

        public byte[] toByteArray() {
            return position == bytes.length ? bytes : Arrays.copyOf(bytes, position);
        }

        public byte[] toExactByteArray() {
            if (position != bytes.length) {
                throw new IllegalStateException(
                        "Binary wire size mismatch: expected " + bytes.length + ", wrote " + position);
            }
            return bytes;
        }

        private void ensure(int additional) {
            int required = Math.addExact(position, additional);
            if (required <= bytes.length) {
                return;
            }
            int grown = Math.max(required, bytes.length + (bytes.length >>> 1) + 1);
            if (grown < required || grown > maximumSize) {
                if (required > maximumSize) {
                    throw new IllegalArgumentException("Binary wire value exceeds maximum size");
                }
                grown = maximumSize;
            }
            bytes = Arrays.copyOf(bytes, grown);
        }
    }

    public static final class Reader {
        private final byte[] bytes;
        private final int maximumValueSize;
        private int position;

        public Reader(byte[] bytes, int maximumValueSize) {
            this.bytes = bytes;
            this.maximumValueSize = maximumValueSize;
        }

        public int available() {
            return bytes.length - position;
        }

        public int position() {
            return position;
        }

        public byte[] bytes() {
            return bytes;
        }

        public byte readByte() throws EOFException {
            require(1);
            return bytes[position++];
        }

        public int readUnsignedByte() throws EOFException {
            return readByte() & 0xff;
        }

        public boolean readBoolean() throws IOException {
            int value = readUnsignedByte();
            if (value > 1) {
                throw new IOException("Invalid binary wire boolean " + value);
            }
            return value == 1;
        }

        public int readInt() throws EOFException {
            require(Integer.BYTES);
            int result = peekInt(bytes, position);
            position += Integer.BYTES;
            return result;
        }

        public long readLong() throws EOFException {
            require(Long.BYTES);
            long result = (long) (bytes[position] & 0xff) << 56
                          | (long) (bytes[position + 1] & 0xff) << 48
                          | (long) (bytes[position + 2] & 0xff) << 40
                          | (long) (bytes[position + 3] & 0xff) << 32
                          | (long) (bytes[position + 4] & 0xff) << 24
                          | (long) (bytes[position + 5] & 0xff) << 16
                          | (long) (bytes[position + 6] & 0xff) << 8
                          | bytes[position + 7] & 0xffL;
            position += Long.BYTES;
            return result;
        }

        public Integer readNullableInt() throws IOException {
            return readBoolean() ? readInt() : null;
        }

        public Long readNullableLong() throws IOException {
            return readBoolean() ? readLong() : null;
        }

        public String readString() throws IOException {
            int length = readInt();
            if (length == -1) {
                return null;
            }
            validateSize(length, maximumValueSize, "string");
            require(length);
            String result = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return result;
        }

        public byte[] readBytes() throws IOException {
            int length = readInt();
            if (length == -1) {
                return null;
            }
            validateSize(length, maximumValueSize, "byte value");
            require(length);
            byte[] result = Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return result;
        }

        public long[] readLongs(int maximumElements) throws IOException {
            int size = readInt();
            if (size == -1) {
                return null;
            }
            validateSize(size, maximumElements, "long collection");
            require(Math.multiplyExact(size, Long.BYTES));
            long[] result = new long[size];
            for (int index = 0; index < size; index++) {
                result[index] = readLong();
            }
            return result;
        }

        public SerializedMessage readEnvelope() throws IOException {
            int size = readSize(maximumValueSize, "message envelope");
            if (size < SerializedMessage.HEADER_SIZE) {
                throw new IOException("Invalid message envelope size " + size);
            }
            require(size);
            SerializedMessage result = SerializedMessage.decodeView(bytes, position, size);
            position += size;
            return result;
        }

        public int readSize(int maximum, String label) throws IOException {
            int size = readInt();
            validateSize(size, maximum, label);
            return size;
        }

        public void skip(int length) throws EOFException {
            require(length);
            position += length;
        }

        public void require(int length) throws EOFException {
            if (length < 0 || position > bytes.length - length) {
                throw new EOFException();
            }
        }

        private static void validateSize(int size, int maximum, String label) throws IOException {
            if (size < 0 || size > maximum) {
                throw new IOException("Invalid binary wire " + label + " size " + size);
            }
        }
    }
}
