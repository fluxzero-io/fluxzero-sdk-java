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

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Internal, bounds-checked MessagePack I/O for Fluxzero's existing storage and transport schemas.
 * Supports integers, strings, binary payloads, array headers, booleans and nil; this is not a general object mapper.
 * All multi-byte values use MessagePack's big-endian byte order. Instances are reusable but not thread-safe.
 */
public final class MessagePackIO {
    private static final byte[] EMPTY = new byte[0];

    private MessagePackIO() {
    }

    /** A reusable heap-array writer. Returned byte arrays are independent snapshots. */
    public static final class Writer implements AutoCloseable {
        private byte[] buffer = new byte[8192];
        private int position;
        private int chunkStart;
        private int completedSize;
        private List<Chunk> chunks;

        /** Writes the smallest signed or unsigned integer representation of a Java long. */
        public Writer packLong(long value) {
            if (value >= -32 && value <= 127) {
                return number((int) value, 0, 0);
            }
            if (value < -32) {
                return value >= Byte.MIN_VALUE ? number(0xd0, value, 1)
                        : value >= Short.MIN_VALUE ? number(0xd1, value, 2)
                        : value >= Integer.MIN_VALUE ? number(0xd2, value, 4) : number(0xd3, value, 8);
            }
            return value <= 0xffL ? number(0xcc, value, 1)
                    : value <= 0xffffL ? number(0xcd, value, 2)
                    : value <= 0xffffffffL ? number(0xce, value, 4) : number(0xcf, value, 8);
        }

        /** Writes a Java integer in its smallest MessagePack representation. */
        public Writer packInt(int value) {
            return packLong(value);
        }

        /** Writes a Java byte in its smallest MessagePack representation. */
        public Writer packByte(byte value) {
            return packLong(value);
        }

        /** Writes nil. */
        public Writer packNil() {
            return number(0xc0, 0, 0);
        }

        /** Writes a boolean. */
        public Writer packBoolean(boolean value) {
            return number(value ? 0xc3 : 0xc2, 0, 0);
        }

        /** Writes UTF-8, replacing malformed UTF-16 with '?' like the previous writer. */
        public Writer packString(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int length = bytes.length;
            if (length < 32) {
                number(0xa0 | length, 0, 0);
            } else if (length < 256) {
                number(0xd9, length, 1);
            } else if (length < 65536) {
                number(0xda, length, 2);
            } else {
                number(0xdb, length, 4);
            }
            return addPayload(bytes);
        }

        /** Writes an array header; callers write its elements separately. */
        public Writer packArrayHeader(int size) {
            checkSize(size);
            return size < 16 ? number(0x90 | size, 0, 0)
                    : size < 65536 ? number(0xdc, size, 2) : number(0xdd, size, 4);
        }

        /** Writes a binary header; callers write its payload separately. */
        public Writer packBinaryHeader(int size) {
            checkSize(size);
            return size < 256 ? number(0xc4, size, 1)
                    : size < 65536 ? number(0xc5, size, 2) : number(0xc6, size, 4);
        }

        /** Copies unframed payload bytes into this writer. */
        public Writer writePayload(byte[] bytes) {
            if (bytes.length >= 8192 || bytes.length > buffer.length - position) {
                return addPayload(bytes.clone());
            }
            ensure(bytes.length);
            System.arraycopy(bytes, 0, buffer, position, bytes.length);
            position += bytes.length;
            return this;
        }

        /**
         * Appends unframed bytes, retaining large arrays until clear/close. Callers must not mutate the array
         * before taking the output snapshot. This preserves the historical large-payload copy boundary.
         */
        public Writer addPayload(byte[] bytes) {
            if (bytes.length < 8192 && bytes.length <= buffer.length - position) {
                return writePayload(bytes);
            }
            finishChunk();
            append(bytes, 0, bytes.length);
            return this;
        }

        /** Returns an independent copy of all bytes written since the last clear. */
        public byte[] toByteArray() {
            if (completedSize == 0) {
                return Arrays.copyOfRange(buffer, chunkStart, position);
            }
            byte[] result = new byte[getBufferSize()];
            int offset = 0;
            for (Chunk chunk : chunks) {
                System.arraycopy(chunk.bytes, chunk.offset, result, offset, chunk.length);
                offset += chunk.length;
            }
            System.arraycopy(buffer, chunkStart, result, offset, position - chunkStart);
            return result;
        }

        /** Returns written bytes, for bounding writer pools after serialization. */
        public int getBufferSize() {
            return Math.addExact(completedSize, position - chunkStart);
        }

        /** Resets the writer and releases completed chunks, retaining only its current small buffer. */
        public void clear() {
            position = chunkStart = completedSize = 0;
            chunks = null;
        }

        @Override
        public void close() {
            clear();
            buffer = EMPTY;
        }

        private void finishChunk() {
            if (position > chunkStart) {
                append(buffer, chunkStart, position - chunkStart);
                chunkStart = position;
                // Keep the unused tail for subsequent headers around borrowed payloads.
                if (buffer.length - position <= 2048) {
                    buffer = EMPTY;
                    chunkStart = position = 0;
                }
            }
        }

        private void append(byte[] bytes, int offset, int length) {
            completedSize = Math.addExact(completedSize, length);
            if (chunks == null) {
                chunks = new ArrayList<>();
            }
            chunks.add(new Chunk(bytes, offset, length));
        }

        private record Chunk(byte[] bytes, int offset, int length) {
        }

        private Writer number(int tag, long value, int size) {
            ensure(size + 1);
            buffer[position++] = (byte) tag;
            for (int shift = (size - 1) * 8; shift >= 0; shift -= 8) {
                buffer[position++] = (byte) (value >>> shift);
            }
            return this;
        }

        private void ensure(int count) {
            Math.addExact(getBufferSize(), count);
            if (count > buffer.length - position) {
                finishChunk();
                buffer = new byte[Math.max(8192, count)];
                chunkStart = position = 0;
            }
        }

        private static void checkSize(int size) {
            if (size < 0) {
                throw new IllegalArgumentException("Negative MessagePack size");
            }
        }
    }

    /** A reusable, zero-copy reader of one heap-array range. Closing releases the referenced array. */
    public static final class Reader implements AutoCloseable {
        private byte[] bytes;
        private int position;
        private int end;

        public Reader(byte[] bytes) {
            this(bytes, 0, bytes.length);
        }

        public Reader(byte[] bytes, int offset, int length) {
            reset(bytes, offset, length);
        }

        /** Replaces the input without copying or retaining the previous array. */
        public Reader reset(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            this.bytes = bytes;
            position = offset;
            end = offset + length;
            return this;
        }

        /** Returns the absolute offset of the next unread byte in the input array. */
        public int position() {
            return position;
        }

        /** Indicates whether this range still contains bytes. */
        public boolean hasNext() {
            return position < end;
        }

        /** Consumes nil when present, otherwise leaves the input unchanged. */
        public boolean tryUnpackNil() throws IOException {
            require(1);
            if (bytes[position] != (byte) 0xc0) {
                return false;
            }
            position++;
            return true;
        }

        /** Reads any MessagePack integer representable as a Java long. */
        public long unpackLong() throws IOException {
            int tag = readByte();
            if (tag <= 0x7f || tag >= 0xe0) {
                return (byte) tag;
            }
            return switch (tag) {
                case 0xcc -> unsigned(1);
                case 0xcd -> unsigned(2);
                case 0xce -> unsigned(4);
                case 0xcf -> {
                    long value = unsigned(8);
                    if (value < 0) {
                        throw new IOException("MessagePack unsigned integer exceeds Java long");
                    }
                    yield value;
                }
                case 0xd0 -> (byte) unsigned(1);
                case 0xd1 -> (short) unsigned(2);
                case 0xd2 -> (int) unsigned(4);
                case 0xd3 -> unsigned(8);
                default -> throw unexpected(tag, "integer");
            };
        }

        /** Reads an integer, rejecting values outside the Java int range. */
        public int unpackInt() throws IOException {
            long value = unpackLong();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IOException("MessagePack integer exceeds Java int");
            }
            return (int) value;
        }

        /** Reads an integer, rejecting values outside the Java byte range. */
        public byte unpackByte() throws IOException {
            long value = unpackLong();
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new IOException("MessagePack integer exceeds Java byte");
            }
            return (byte) value;
        }

        /** Reads a boolean. */
        public boolean unpackBoolean() throws IOException {
            int tag = readByte();
            if (tag != 0xc2 && tag != 0xc3) {
                throw unexpected(tag, "boolean");
            }
            return tag == 0xc3;
        }

        /** Reads UTF-8, retaining the previous reader's malformed-input replacement and binary acceptance. */
        public String unpackString() throws IOException {
            int length = unpackBinaryHeader();
            require(length);
            String result = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return result;
        }

        /** Reads an array header, rejecting counts outside the Java collection size range. */
        public int unpackArrayHeader() throws IOException {
            int tag = readByte();
            return (tag & 0xf0) == 0x90 ? tag & 0xf
                    : tag == 0xdc ? size(2) : tag == 0xdd ? size(4)
                    : throwUnexpected(tag, "array");
        }

        /** Reads a binary or string header, matching historical MessagePack reader defaults. */
        public int unpackBinaryHeader() throws IOException {
            int tag = readByte();
            if ((tag & 0xe0) == 0xa0) {
                return tag & 0x1f;
            }
            return switch (tag) {
                case 0xc4, 0xd9 -> size(1);
                case 0xc5, 0xda -> size(2);
                case 0xc6, 0xdb -> size(4);
                default -> throw unexpected(tag, "binary or string");
            };
        }

        /** Copies exactly length unframed bytes, checking the input range before allocating. */
        public byte[] readPayload(int length) throws IOException {
            require(length);
            byte[] result = Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return result;
        }

        @Override
        public void close() {
            bytes = EMPTY;
            position = end = 0;
        }

        private int size(int width) throws IOException {
            long value = unsigned(width);
            if (value > Integer.MAX_VALUE) {
                throw new IOException("MessagePack size exceeds Java int");
            }
            return (int) value;
        }

        private int readByte() throws IOException {
            require(1);
            return bytes[position++] & 0xff;
        }

        private long unsigned(int width) throws IOException {
            require(width);
            long result = 0;
            for (int i = 0; i < width; i++) {
                result = (result << 8) | (bytes[position++] & 0xffL);
            }
            return result;
        }

        private void require(int length) throws IOException {
            if (length < 0 || length > end - position) {
                throw new EOFException("Truncated MessagePack input or invalid payload length");
            }
        }

        private static int throwUnexpected(int tag, String expected) throws IOException {
            throw unexpected(tag, expected);
        }

        private static IOException unexpected(int tag, String expected) {
            return new IOException("Expected MessagePack " + expected + ", got tag " + tag);
        }
    }
}
