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

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Allocation-bounded primitive I/O shared by compact transports and persisted messages. */
public final class BinaryWire {

    private static final int ENVELOPE_MAGIC = 0x465A4D45; // FZME
    private static final int ENVELOPE_VERSION = 1;
    private static final int ENVELOPE_HEADER_SIZE = 72;
    private static final int FLAGS_OFFSET = 5;
    private static final int TOTAL_LENGTH_OFFSET = 8;
    private static final int SEGMENT_OFFSET = 12;
    private static final int REQUEST_ID_OFFSET = 16;
    private static final int INDEX_OFFSET = 20;
    private static final int TIMESTAMP_OFFSET = 28;
    private static final int REVISION_OFFSET = 36;
    private static final int TYPE_LENGTH_OFFSET = 40;
    private static final int FORMAT_LENGTH_OFFSET = 44;
    private static final int SOURCE_LENGTH_OFFSET = 48;
    private static final int TARGET_LENGTH_OFFSET = 52;
    private static final int MESSAGE_ID_LENGTH_OFFSET = 56;
    private static final int PAYLOAD_LENGTH_OFFSET = 60;
    private static final int METADATA_LENGTH_OFFSET = 64;
    private static final int ORIGINAL_REVISION_OFFSET = 68;
    private static final int HAS_SEGMENT = 1;
    private static final int HAS_REQUEST_ID = 1 << 1;
    private static final int HAS_INDEX = 1 << 2;
    private static final int HAS_TIMESTAMP = 1 << 3;
    private static final int SUPPORTED_FLAGS = HAS_SEGMENT | HAS_REQUEST_ID | HAS_INDEX | HAS_TIMESTAMP;
    private static final int MAXIMUM_ENVELOPE_SIZE = 512 * 1024 * 1024;
    private static final int MAXIMUM_CACHED_HEADER_CHARS = 256;
    private static final ThreadLocal<EnvelopeHeaderCache> ENVELOPE_HEADER_CACHE =
            ThreadLocal.withInitial(EnvelopeHeaderCache::new);
    private static final Data<byte[]> DEFERRED_DATA = new Data<>(
            () -> { throw new IllegalStateException("Deferred binary message data was accessed directly"); },
            null, 0, null);

    private BinaryWire() {
    }

    public static int peekInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
               | (bytes[offset + 1] & 0xff) << 16
               | (bytes[offset + 2] & 0xff) << 8
               | bytes[offset + 3] & 0xff;
    }

    public static int utf8Length(String value) {
        if (value == null) {
            return -1;
        }
        int result = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                result = Math.addExact(result, 1);
            } else if (current <= 0x7ff) {
                result = Math.addExact(result, 2);
            } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                result = Math.addExact(result, 4);
                index++;
            } else {
                result = Math.addExact(result, Character.isSurrogate(current) ? 1 : 3);
            }
        }
        return result;
    }

    /** Returns the complete encoded size of one native message envelope. */
    public static int envelopeSize(SerializedMessage message) {
        if (message instanceof EncodedMessage encoded && encoded.matchesVariables()) {
            return encoded.length;
        }
        Data<byte[]> data = message.getData();
        Data.ByteArrayView payload = data.byteArrayView();
        byte[] payloadBytes = payload == null ? data.getValue() : payload.array();
        int payloadLength = payload == null
                ? length(payloadBytes) : payload.length();
        Metadata metadata = message.getMetadata();
        Data<byte[]> encodedMetadata = (metadata == null ? Metadata.empty() : metadata).toData();
        Data.ByteArrayView metadataView = encodedMetadata.byteArrayView();
        int metadataLength = metadataView == null
                ? length(encodedMetadata.getValue()) : metadataView.length();
        long result = ENVELOPE_HEADER_SIZE
                      + positiveLength(utf8Length(data.getType()))
                      + positiveLength(utf8Length(data.getFormat()))
                      + positiveLength(utf8Length(message.getSource()))
                      + positiveLength(utf8Length(message.getTarget()))
                      + positiveLength(utf8Length(message.getMessageId()))
                      + positiveLength(payloadLength) + positiveLength(metadataLength);
        if (result > MAXIMUM_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Binary message envelope exceeds maximum size");
        }
        return Math.toIntExact(result);
    }

    /** Returns the size of a native envelope when embedded as one length-delimited wire value. */
    public static int nestedEnvelopeSize(SerializedMessage message) {
        return Math.addExact(Integer.BYTES, envelopeSize(message));
    }

    /** Prepares a message once for reuse by an enclosing transport codec. */
    public static SerializedMessage prepareEnvelope(SerializedMessage message) {
        if (message instanceof EncodedMessage encoded && encoded.matchesVariables()) {
            return message;
        }
        Writer writer = new Writer(estimatedEnvelopeSize(message), MAXIMUM_ENVELOPE_SIZE);
        writer.writeRawEnvelopeOnePass(message);
        byte[] bytes = writer.toByteArray();
        try {
            validateEnvelopeComponents(bytes, 0, bytes.length, MAXIMUM_ENVELOPE_SIZE);
            return new EncodedMessage(message, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect encoded message envelope", e);
        }
    }

    /** Prepares a message sequence, retaining the original list when every envelope is reusable. */
    public static List<SerializedMessage> prepareEnvelopes(List<SerializedMessage> messages) {
        for (SerializedMessage message : messages) {
            if (!(message instanceof EncodedMessage encoded && encoded.matchesVariables())) {
                List<SerializedMessage> result = new ArrayList<>(messages.size());
                messages.forEach(value -> result.add(prepareEnvelope(value)));
                return result;
            }
        }
        return messages;
    }

    private static int estimatedEnvelopeSize(SerializedMessage message) {
        Data<byte[]> data = message.getData();
        Data.ByteArrayView payload = data.byteArrayView();
        int payloadLength = payload == null ? length(data.getValue()) : payload.length();
        Metadata metadata = message.getMetadata();
        Data<byte[]> metadataData = (metadata == null ? Metadata.empty() : metadata).toData();
        Data.ByteArrayView metadataView = metadataData.byteArrayView();
        int metadataLength = metadataView == null ? length(metadataData.getValue()) : metadataView.length();
        long estimate = ENVELOPE_HEADER_SIZE
                        + Math.max(0, payloadLength) + Math.max(0, metadataLength)
                        + characterCount(data.getType()) + characterCount(data.getFormat())
                        + characterCount(message.getSource()) + characterCount(message.getTarget())
                        + characterCount(message.getMessageId());
        return (int) Math.min(MAXIMUM_ENVELOPE_SIZE, Math.max(128L, estimate));
    }

    private static int characterCount(String value) {
        return value == null ? 0 : value.length();
    }

    /** Encodes a sequence of concatenated native message envelopes. */
    public static byte[] encodeEnvelopes(List<SerializedMessage> messages) {
        int size = 0;
        for (SerializedMessage message : messages) {
            size = Math.addExact(size, envelopeSize(message));
        }
        Writer writer = new Writer(size, size);
        messages.forEach(writer::writeRawEnvelope);
        return writer.toExactByteArray();
    }

    /** Encodes one native message envelope. */
    public static byte[] encodeEnvelope(SerializedMessage message) {
        int size = envelopeSize(message);
        Writer writer = new Writer(size, size);
        writer.writeRawEnvelope(message);
        return writer.toExactByteArray();
    }

    /** Decodes exactly one native message envelope while retaining zero-copy payload and metadata views. */
    public static SerializedMessage decodeEnvelope(byte[] bytes, int maximumValueSize) throws IOException {
        if (validateEnvelopeComponents(bytes, 0, bytes.length, maximumValueSize) != bytes.length) {
            throw new IOException("Unexpected trailing binary message envelope");
        }
        return new EncodedMessage(bytes, 0, bytes.length);
    }

    /** Decodes a sequence while retaining zero-copy payload and metadata views. */
    public static List<SerializedMessage> decodeEnvelopes(byte[] bytes, int maximumValueSize) throws IOException {
        return decodeEnvelopes(bytes, 0, bytes.length, maximumValueSize);
    }

    /** Decodes a native-envelope sequence from a byte range. */
    public static List<SerializedMessage> decodeEnvelopes(
            byte[] bytes, int offset, int length, int maximumValueSize) throws IOException {
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IOException("Invalid binary message range");
        }
        if (length == 0) {
            return List.of();
        }
        List<SerializedMessage> result = new ArrayList<>();
        int end = offset + length;
        while (offset < end) {
            int envelopeSize = readEnvelopeSize(bytes, offset, end - offset, maximumValueSize);
            validateEnvelopeComponents(bytes, offset, envelopeSize, maximumValueSize);
            result.add(new EncodedMessage(bytes, offset, envelopeSize));
            offset = Math.addExact(offset, envelopeSize);
        }
        return result;
    }

    /** Returns whether the byte sequence starts with this codec's native-envelope signature. */
    public static boolean isEnvelopeSequence(byte[] bytes) {
        return bytes != null && isEnvelopeSequence(bytes, 0, bytes.length);
    }

    /** Returns whether a byte range starts with this codec's native-envelope signature. */
    public static boolean isEnvelopeSequence(byte[] bytes, int offset, int length) {
        return bytes != null && offset >= 0 && length >= ENVELOPE_HEADER_SIZE
               && offset <= bytes.length - length
               && peekInt(bytes, offset) == ENVELOPE_MAGIC
               && peekInt(bytes, offset + TOTAL_LENGTH_OFFSET) >= ENVELOPE_HEADER_SIZE
               && peekInt(bytes, offset + TOTAL_LENGTH_OFFSET) <= length;
    }

    /** Returns the complete size of the next native message envelope in a byte range. */
    public static int readEnvelopeSize(byte[] bytes, int offset, int length, int maximumValueSize) throws IOException {
        if (bytes == null || offset < 0 || length < ENVELOPE_HEADER_SIZE || offset > bytes.length - length) {
            throw new EOFException();
        }
        if (peekInt(bytes, offset) != ENVELOPE_MAGIC) {
            throw new IOException("Unsupported binary wire message envelope");
        }
        int version = bytes[offset + 4] & 0xff;
        if (version != ENVELOPE_VERSION) {
            throw new IOException("Unsupported binary wire message envelope version " + version);
        }
        int flags = bytes[offset + FLAGS_OFFSET] & 0xff;
        if ((flags & ~SUPPORTED_FLAGS) != 0) {
            throw new IOException("Unsupported binary wire message envelope flags " + flags);
        }
        int envelopeSize = peekInt(bytes, offset + TOTAL_LENGTH_OFFSET);
        if (envelopeSize < ENVELOPE_HEADER_SIZE || envelopeSize > maximumValueSize || envelopeSize > length) {
            throw new IOException("Invalid binary wire message envelope size " + envelopeSize);
        }
        return envelopeSize;
    }

    /** Reads only the indexed routing field from one native message without materializing application data. */
    public static Long readEnvelopeIndex(byte[] bytes, int offset, int length, int maximumValueSize)
            throws IOException {
        readEnvelopeSize(bytes, offset, length, maximumValueSize);
        return (bytes[offset + FLAGS_OFFSET] & HAS_INDEX) == 0
                ? null : peekLong(bytes, offset + INDEX_OFFSET);
    }

    private static int validateEnvelopeComponents(
            byte[] bytes, int offset, int length, int maximumValueSize) throws IOException {
        int envelopeSize = readEnvelopeSize(bytes, offset, length, maximumValueSize);
        int typeLength = readNullableLength(bytes, offset + TYPE_LENGTH_OFFSET, "type");
        int formatLength = readNullableLength(bytes, offset + FORMAT_LENGTH_OFFSET, "format");
        int sourceLength = readNullableLength(bytes, offset + SOURCE_LENGTH_OFFSET, "source");
        int targetLength = readNullableLength(bytes, offset + TARGET_LENGTH_OFFSET, "target");
        int messageIdLength = readNullableLength(bytes, offset + MESSAGE_ID_LENGTH_OFFSET, "message id");
        int payloadLength = readNullableLength(bytes, offset + PAYLOAD_LENGTH_OFFSET, "payload");
        int metadataLength = peekInt(bytes, offset + METADATA_LENGTH_OFFSET);
        if (metadataLength < 0) {
            throw new IOException("Invalid binary wire metadata length " + metadataLength);
        }
        int cursor = offset + ENVELOPE_HEADER_SIZE;
        try {
            cursor = Math.addExact(cursor, positiveLength(typeLength));
            cursor = Math.addExact(cursor, positiveLength(formatLength));
            cursor = Math.addExact(cursor, positiveLength(sourceLength));
            cursor = Math.addExact(cursor, positiveLength(targetLength));
            cursor = Math.addExact(cursor, positiveLength(messageIdLength));
            cursor = Math.addExact(cursor, positiveLength(payloadLength));
            cursor = Math.addExact(cursor, metadataLength);
        } catch (ArithmeticException e) {
            throw new IOException("Binary wire message envelope components overflow", e);
        }
        if (cursor != offset + envelopeSize) {
            throw new IOException("Invalid binary wire message envelope components");
        }
        return envelopeSize;
    }

    private static int readNullableLength(byte[] bytes, int offset, String label) throws IOException {
        int result = peekInt(bytes, offset);
        if (result < -1) {
            throw new IOException("Invalid binary wire " + label + " length " + result);
        }
        return result;
    }

    private static long peekLong(byte[] bytes, int offset) {
        return (long) (bytes[offset] & 0xff) << 56
               | (long) (bytes[offset + 1] & 0xff) << 48
               | (long) (bytes[offset + 2] & 0xff) << 40
               | (long) (bytes[offset + 3] & 0xff) << 32
               | (long) (bytes[offset + 4] & 0xff) << 24
               | (long) (bytes[offset + 5] & 0xff) << 16
               | (long) (bytes[offset + 6] & 0xff) << 8
               | bytes[offset + 7] & 0xffL;
    }

    private static int length(byte[] value) {
        return value == null ? -1 : value.length;
    }

    private static int positiveLength(int value) {
        return Math.max(0, value);
    }

    /** Unchecked cursor for validated embedded values whose public API does not expose I/O failures. */
    public static final class Cursor {
        private final byte[] bytes;
        private final int limit;
        private int position;

        public Cursor(byte[] bytes, int offset, int length, int maximumSize) {
            if (bytes == null || offset < 0 || length < 0 || length > maximumSize
                    || offset > bytes.length - length) {
                throw new IllegalArgumentException("Invalid binary wire range");
            }
            this.bytes = bytes;
            this.position = offset;
            this.limit = offset + length;
        }

        public byte[] bytes() {
            return bytes;
        }

        public int position() {
            return position;
        }

        public int remaining() {
            return limit - position;
        }

        public int readInt() {
            require(Integer.BYTES);
            int result = peekInt(bytes, position);
            position += Integer.BYTES;
            return result;
        }

        public int readSize(int maximum, String label) {
            int value = readInt();
            if (value < 0 || value > maximum) {
                throw new IllegalArgumentException("Invalid binary wire " + label + " size " + value);
            }
            return value;
        }

        public int readStringLength(int maximum) {
            int length = readSize(maximum, "string");
            require(length);
            return length;
        }

        public String readString(int maximum) {
            int length = readStringLength(maximum);
            String result = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return result;
        }

        public boolean readStringEquals(String value, int maximum) {
            int length = readStringLength(maximum);
            boolean result = utf8Equals(bytes, position, length, value);
            position += length;
            return result;
        }

        public void skipString(int maximum) {
            int length = readStringLength(maximum);
            position += length;
        }

        public void skip(int length) {
            require(length);
            position += length;
        }

        public void requireComplete() {
            if (position != limit) {
                throw new IllegalArgumentException("Unexpected trailing binary wire bytes");
            }
        }

        private void require(int length) {
            if (length < 0 || length > limit - position) {
                throw new IllegalArgumentException("Truncated binary wire value");
            }
        }
    }

    public static boolean utf8Equals(byte[] bytes, int offset, int byteLength, String value) {
        int byteIndex = 0;
        for (int charIndex = 0; charIndex < value.length(); charIndex++) {
            char current = value.charAt(charIndex);
            if (current <= 0x7f) {
                if (!matches(bytes, offset, byteIndex++, byteLength, current)) return false;
            } else if (current <= 0x7ff) {
                if (!matches(bytes, offset, byteIndex++, byteLength, 0xc0 | current >>> 6)
                        || !matches(bytes, offset, byteIndex++, byteLength, 0x80 | current & 0x3f)) return false;
            } else if (Character.isHighSurrogate(current) && charIndex + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(charIndex + 1))) {
                int codePoint = Character.toCodePoint(current, value.charAt(++charIndex));
                if (!matches(bytes, offset, byteIndex++, byteLength, 0xf0 | codePoint >>> 18)
                        || !matches(bytes, offset, byteIndex++, byteLength, 0x80 | codePoint >>> 12 & 0x3f)
                        || !matches(bytes, offset, byteIndex++, byteLength, 0x80 | codePoint >>> 6 & 0x3f)
                        || !matches(bytes, offset, byteIndex++, byteLength, 0x80 | codePoint & 0x3f)) return false;
            } else if (Character.isSurrogate(current)) {
                if (!matches(bytes, offset, byteIndex++, byteLength, '?')) return false;
            } else if (!matches(bytes, offset, byteIndex++, byteLength, 0xe0 | current >>> 12)
                    || !matches(bytes, offset, byteIndex++, byteLength, 0x80 | current >>> 6 & 0x3f)
                    || !matches(bytes, offset, byteIndex++, byteLength, 0x80 | current & 0x3f)) return false;
        }
        return byteIndex == byteLength;
    }

    private static boolean matches(byte[] bytes, int offset, int index, int length, int expected) {
        return index < length && (bytes[offset + index] & 0xff) == expected;
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
            int frameOffset = position;
            writeInt(0);
            int envelopeOffset = position;
            writeRawEnvelope(message);
            patchInt(frameOffset, position - envelopeOffset);
        }

        private void writeRawEnvelope(SerializedMessage message) {
            if (message instanceof EncodedMessage encoded && encoded.matchesVariables()) {
                int start = position;
                writeRaw(encoded.bytes, encoded.offset, encoded.length);
                patchEnvelopeHeader(start, message);
                return;
            }
            writeRawEnvelopeOnePass(message);
        }

        private void writeRawEnvelopeOnePass(SerializedMessage message) {
            int bodyOffset = position;
            ensure(ENVELOPE_HEADER_SIZE);
            position += ENVELOPE_HEADER_SIZE;
            Data<byte[]> data = message.getData();
            EnvelopeHeaderCache headers = ENVELOPE_HEADER_CACHE.get();
            int typeLength = writeCachedUtf8Raw(headers, 0, data.getType());
            int formatLength = writeCachedUtf8Raw(headers, 1, data.getFormat());
            int sourceLength = writeCachedUtf8Raw(headers, 2, message.getSource());
            int targetLength = writeCachedUtf8Raw(headers, 3, message.getTarget());
            int messageIdLength = writeUtf8Raw(message.getMessageId());
            int payloadLength = writeDataRaw(data);
            Metadata metadata = message.getMetadata();
            int metadataLength = writeDataRaw((metadata == null ? Metadata.empty() : metadata).toData());
            int bodyLength = position - bodyOffset;
            patchInt(bodyOffset, ENVELOPE_MAGIC);
            bytes[bodyOffset + 4] = ENVELOPE_VERSION;
            bytes[bodyOffset + 6] = 0;
            bytes[bodyOffset + 7] = 0;
            patchInt(bodyOffset + TOTAL_LENGTH_OFFSET, bodyLength);
            patchEnvelopeHeader(bodyOffset, message);
            patchInt(bodyOffset + REVISION_OFFSET, data.getRevision());
            patchInt(bodyOffset + TYPE_LENGTH_OFFSET, typeLength);
            patchInt(bodyOffset + FORMAT_LENGTH_OFFSET, formatLength);
            patchInt(bodyOffset + SOURCE_LENGTH_OFFSET, sourceLength);
            patchInt(bodyOffset + TARGET_LENGTH_OFFSET, targetLength);
            patchInt(bodyOffset + MESSAGE_ID_LENGTH_OFFSET, messageIdLength);
            patchInt(bodyOffset + PAYLOAD_LENGTH_OFFSET, payloadLength);
            patchInt(bodyOffset + METADATA_LENGTH_OFFSET, metadataLength);
            patchInt(bodyOffset + ORIGINAL_REVISION_OFFSET, message.getOriginalRevision());
        }

        private int writeCachedUtf8Raw(EnvelopeHeaderCache headers, int slot, String value) {
            byte[] encoded = headers.encoded(slot, value);
            return encoded == null ? writeUtf8Raw(value) : writeUtf8Raw(encoded);
        }

        private int writeUtf8Raw(String value) {
            if (value == null) {
                return -1;
            }
            int start = position;
            ensure(value.length());
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current > 0x7f) {
                    position = start;
                    writeRaw(value.getBytes(StandardCharsets.UTF_8));
                    return position - start;
                }
                bytes[position++] = (byte) current;
            }
            return position - start;
        }

        private int writeUtf8Raw(byte[] value) {
            if (value == null) {
                return -1;
            }
            writeRaw(value);
            return value.length;
        }

        private int writeDataRaw(Data<byte[]> data) {
            Data.ByteArrayView view = data.byteArrayView();
            if (view != null) {
                writeRaw(view.array(), view.offset(), view.length());
                return view.length();
            }
            byte[] value = data.getValue();
            if (value == null) {
                return -1;
            }
            writeRaw(value);
            return value.length;
        }

        private void patchEnvelopeHeader(int offset, SerializedMessage message) {
            bytes[offset + FLAGS_OFFSET] = flags(message);
            patchInt(offset + SEGMENT_OFFSET, valueOrZero(message.getSegment()));
            patchInt(offset + REQUEST_ID_OFFSET, valueOrZero(message.getRequestId()));
            patchLong(offset + INDEX_OFFSET, valueOrZero(message.getIndex()));
            patchLong(offset + TIMESTAMP_OFFSET, valueOrZero(message.getTimestamp()));
        }

        private static byte flags(SerializedMessage message) {
            int result = message.getSegment() == null ? 0 : HAS_SEGMENT;
            result |= message.getRequestId() == null ? 0 : HAS_REQUEST_ID;
            result |= message.getIndex() == null ? 0 : HAS_INDEX;
            result |= message.getTimestamp() == null ? 0 : HAS_TIMESTAMP;
            return (byte) result;
        }

        private static int valueOrZero(Integer value) {
            return value == null ? 0 : value;
        }

        private static long valueOrZero(Long value) {
            return value == null ? 0L : value;
        }

        private void patchInt(int offset, int value) {
            bytes[offset] = (byte) (value >>> 24);
            bytes[offset + 1] = (byte) (value >>> 16);
            bytes[offset + 2] = (byte) (value >>> 8);
            bytes[offset + 3] = (byte) value;
        }

        private void patchLong(int offset, long value) {
            bytes[offset] = (byte) (value >>> 56);
            bytes[offset + 1] = (byte) (value >>> 48);
            bytes[offset + 2] = (byte) (value >>> 40);
            bytes[offset + 3] = (byte) (value >>> 32);
            bytes[offset + 4] = (byte) (value >>> 24);
            bytes[offset + 5] = (byte) (value >>> 16);
            bytes[offset + 6] = (byte) (value >>> 8);
            bytes[offset + 7] = (byte) value;
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

    private static final class EnvelopeHeaderCache {
        private final String[] values = new String[4];
        private final byte[][] encoded = new byte[4][];

        private byte[] encoded(int slot, String value) {
            if (value == null) {
                return null;
            }
            if (value.equals(values[slot])) {
                byte[] result = encoded[slot];
                if (result == null) {
                    encoded[slot] = result = value.getBytes(StandardCharsets.UTF_8);
                }
                return result;
            }
            encoded[slot] = null;
            if (value.length() > MAXIMUM_CACHED_HEADER_CHARS) {
                values[slot] = null;
                return null;
            }
            values[slot] = value;
            if (slot >= 2) {
                return null;
            }
            return encoded[slot] = value.getBytes(StandardCharsets.UTF_8);
        }
    }

    public static final class Reader {
        private final byte[] bytes;
        private final int maximumValueSize;
        private final int limit;
        private int position;

        public Reader(byte[] bytes, int maximumValueSize) {
            this(bytes, 0, bytes.length, maximumValueSize);
        }

        private Reader(byte[] bytes, int offset, int length, int maximumValueSize) {
            this.bytes = bytes;
            this.maximumValueSize = maximumValueSize;
            this.position = offset;
            this.limit = offset + length;
        }

        public int available() {
            return limit - position;
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
            Data.ByteArrayView view = readByteView();
            return view == null ? null : view.get();
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
            int envelopeSize = readInt();
            if (envelopeSize < ENVELOPE_HEADER_SIZE || envelopeSize > maximumValueSize) {
                throw new IOException("Invalid nested binary message envelope size " + envelopeSize);
            }
            require(envelopeSize);
            int envelopeOffset = position;
            if (readEnvelopeSize(bytes, envelopeOffset, envelopeSize, maximumValueSize) != envelopeSize) {
                throw new IOException("Nested binary message envelope length mismatch");
            }
            validateEnvelopeComponents(bytes, envelopeOffset, envelopeSize, maximumValueSize);
            position += envelopeSize;
            return new EncodedMessage(bytes, envelopeOffset, envelopeSize);
        }

        private Data.ByteArrayView readByteView() throws IOException {
            int length = readInt();
            if (length == -1) {
                return null;
            }
            validateSize(length, maximumValueSize, "byte value");
            require(length);
            Data.ByteArrayView result = new ByteSlice(bytes, position, length);
            position += length;
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
            if (length < 0 || position > limit - length) {
                throw new EOFException();
            }
        }

        private static void validateSize(int size, int maximum, String label) throws IOException {
            if (size < 0 || size > maximum) {
                throw new IOException("Invalid binary wire " + label + " size " + size);
            }
        }
    }

    private static final class EncodedMessage extends SerializedMessage {
        private final byte[] bytes;
        private final int offset;
        private final int length;
        private final int typeLength;
        private final int formatLength;
        private final int sourceLength;
        private final int targetLength;
        private final int messageIdLength;
        private final int payloadLength;
        private final int metadataLength;
        private final int payloadOffset;
        private final int metadataOffset;
        private volatile Integer currentOriginalRevision;
        private final int dataRevision;
        private volatile String dataType;
        private volatile String dataFormat;
        private volatile String source;
        private volatile String target;
        private volatile String messageId;
        private volatile boolean dataTypeDecoded;
        private volatile boolean dataFormatDecoded;
        private volatile boolean sourceDecoded;
        private volatile boolean targetDecoded;
        private volatile boolean messageIdDecoded;
        private volatile Data<byte[]> decodedData;
        private volatile Metadata decodedMetadata;
        private volatile boolean metadataDecoded;
        private volatile boolean dataChanged;
        private volatile boolean metadataChanged;
        private volatile boolean sourceChanged;
        private volatile boolean targetChanged;
        private volatile boolean messageIdChanged;
        private volatile boolean originalRevisionChanged;

        private EncodedMessage(byte[] bytes, int offset, int length) {
            super(DEFERRED_DATA, null,
                  nullableInt(bytes, offset, HAS_SEGMENT, SEGMENT_OFFSET),
                  nullableLong(bytes, offset, HAS_INDEX, INDEX_OFFSET),
                  null, null,
                  nullableInt(bytes, offset, HAS_REQUEST_ID, REQUEST_ID_OFFSET),
                  nullableLong(bytes, offset, HAS_TIMESTAMP, TIMESTAMP_OFFSET),
                  null, peekInt(bytes, offset + ORIGINAL_REVISION_OFFSET));
            this.bytes = bytes;
            this.offset = offset;
            this.length = length;
            typeLength = peekInt(bytes, offset + TYPE_LENGTH_OFFSET);
            formatLength = peekInt(bytes, offset + FORMAT_LENGTH_OFFSET);
            sourceLength = peekInt(bytes, offset + SOURCE_LENGTH_OFFSET);
            targetLength = peekInt(bytes, offset + TARGET_LENGTH_OFFSET);
            messageIdLength = peekInt(bytes, offset + MESSAGE_ID_LENGTH_OFFSET);
            payloadLength = peekInt(bytes, offset + PAYLOAD_LENGTH_OFFSET);
            metadataLength = peekInt(bytes, offset + METADATA_LENGTH_OFFSET);
            payloadOffset = messageIdOffset() + positiveLength(messageIdLength);
            metadataOffset = payloadOffset + positiveLength(payloadLength);
            currentOriginalRevision = peekInt(bytes, offset + ORIGINAL_REVISION_OFFSET);
            dataRevision = peekInt(bytes, offset + REVISION_OFFSET);
        }

        private EncodedMessage(SerializedMessage source, byte[] bytes, int offset, int length) {
            super(source.getData(), normalizedMetadata(source), source.getSegment(), source.getIndex(),
                  source.getSource(), source.getTarget(), source.getRequestId(), source.getTimestamp(),
                  source.getMessageId(), source.getOriginalRevision());
            this.bytes = bytes;
            this.offset = offset;
            this.length = length;
            typeLength = peekInt(bytes, offset + TYPE_LENGTH_OFFSET);
            formatLength = peekInt(bytes, offset + FORMAT_LENGTH_OFFSET);
            sourceLength = peekInt(bytes, offset + SOURCE_LENGTH_OFFSET);
            targetLength = peekInt(bytes, offset + TARGET_LENGTH_OFFSET);
            messageIdLength = peekInt(bytes, offset + MESSAGE_ID_LENGTH_OFFSET);
            payloadLength = peekInt(bytes, offset + PAYLOAD_LENGTH_OFFSET);
            metadataLength = peekInt(bytes, offset + METADATA_LENGTH_OFFSET);
            payloadOffset = messageIdOffset() + positiveLength(messageIdLength);
            metadataOffset = payloadOffset + positiveLength(payloadLength);
            currentOriginalRevision = source.getOriginalRevision();
            decodedData = source.getData();
            decodedMetadata = super.getMetadata();
            dataRevision = decodedData.getRevision();
            dataType = decodedData.getType();
            dataFormat = decodedData.getFormat();
            this.source = source.getSource();
            target = source.getTarget();
            messageId = source.getMessageId();
            dataTypeDecoded = true;
            dataFormatDecoded = true;
            sourceDecoded = true;
            targetDecoded = true;
            messageIdDecoded = true;
            metadataDecoded = true;
        }

        private static Integer nullableInt(byte[] bytes, int body, int flag, int valueOffset) {
            return (bytes[body + FLAGS_OFFSET] & flag) == 0 ? null : peekInt(bytes, body + valueOffset);
        }

        private static Long nullableLong(byte[] bytes, int body, int flag, int valueOffset) {
            return (bytes[body + FLAGS_OFFSET] & flag) == 0 ? null : peekLong(bytes, body + valueOffset);
        }

        private static Metadata normalizedMetadata(SerializedMessage source) {
            Metadata metadata = source.getMetadata();
            return metadata == null ? Metadata.empty() : metadata;
        }

        private boolean matchesVariables() {
            return !dataChanged && !metadataChanged && !sourceChanged && !targetChanged
                   && !messageIdChanged && !originalRevisionChanged;
        }

        @Override
        public Data<byte[]> getData() {
            if (dataChanged) {
                return super.getData();
            }
            Data<byte[]> result = decodedData;
            if (result == null) {
                Data.ByteArrayView payload = payloadLength < 0 ? null
                        : new ByteSlice(bytes, payloadOffset, payloadLength);
                decodedData = result = new Data<>(payload == null ? () -> null : payload,
                                                  dataType(), dataRevision, dataFormat());
            }
            return result;
        }

        @Override
        public void setData(Data<byte[]> data) {
            super.setData(data);
            dataChanged = true;
        }

        @Override
        public Data<byte[]> data() {
            return getData();
        }

        @Override
        public int getRevision() {
            return dataChanged ? super.getRevision() : dataRevision;
        }

        @Override
        public String getType() {
            return dataChanged ? super.getType() : dataType();
        }

        @Override
        public boolean typeEquals(String candidate) {
            if (dataChanged || dataTypeDecoded) {
                return Objects.equals(getType(), candidate);
            }
            return encodedEquals(candidate, typeOffset(), typeLength);
        }

        @Override
        public Metadata getMetadata() {
            if (metadataChanged) {
                return super.getMetadata();
            }
            if (!metadataDecoded) {
                synchronized (this) {
                    if (!metadataDecoded) {
                        Data.ByteArrayView metadata = new ByteSlice(
                                bytes, metadataOffset, metadataLength);
                        decodedMetadata = Metadata.fromData(new Data<>(
                                metadata, Metadata.DATA_TYPE, 0, Metadata.DATA_FORMAT));
                        metadataDecoded = true;
                    }
                }
            }
            return decodedMetadata;
        }

        @Override
        public void setMetadata(Metadata metadata) {
            super.setMetadata(metadata);
            metadataChanged = true;
        }

        @Override
        public boolean metadataContainsKey(String key) {
            Objects.requireNonNull(key, "key");
            return metadataChanged || metadataDecoded
                    ? super.metadataContainsKey(key)
                    : encodedMetadataContainsKey(bytes, metadataOffset, metadataLength, key);
        }

        @Override
        public String getMetadataValue(String key) {
            Objects.requireNonNull(key, "key");
            return metadataChanged || metadataDecoded
                    ? super.getMetadataValue(key)
                    : encodedMetadataValue(bytes, metadataOffset, metadataLength, key);
        }

        @Override
        public long getMetadataLongValue(String key, long defaultValue) {
            Objects.requireNonNull(key, "key");
            return metadataChanged || metadataDecoded
                    ? super.getMetadataLongValue(key, defaultValue)
                    : encodedMetadataLongValue(bytes, metadataOffset, metadataLength, key, defaultValue);
        }

        @Override
        public void setSource(String source) {
            super.setSource(source);
            this.source = source;
            sourceDecoded = true;
            sourceChanged = true;
        }

        @Override
        public String getSource() {
            return sourceChanged ? super.getSource() : source();
        }

        @Override
        public void setTarget(String target) {
            super.setTarget(target);
            this.target = target;
            targetDecoded = true;
            targetChanged = true;
        }

        @Override
        public String getTarget() {
            return targetChanged ? super.getTarget() : target();
        }

        @Override
        public boolean targetEquals(String candidate) {
            if (targetChanged || targetDecoded) {
                return Objects.equals(getTarget(), candidate);
            }
            return encodedEquals(candidate, targetOffset(), targetLength);
        }

        @Override
        public void setMessageId(String messageId) {
            super.setMessageId(messageId);
            this.messageId = messageId;
            messageIdDecoded = true;
            messageIdChanged = true;
        }

        @Override
        public String getMessageId() {
            return messageIdChanged ? super.getMessageId() : messageId();
        }

        @Override
        public void setOriginalRevision(Integer originalRevision) {
            super.setOriginalRevision(originalRevision);
            currentOriginalRevision = originalRevision;
            originalRevisionChanged = true;
        }

        @Override
        public int getOriginalRevision() {
            Integer result = currentOriginalRevision;
            return result == null ? getRevision() : result;
        }

        private String dataType() {
            if (!dataTypeDecoded) {
                synchronized (this) {
                    if (!dataTypeDecoded) {
                        dataType = decode(typeOffset(), typeLength);
                        dataTypeDecoded = true;
                    }
                }
            }
            return dataType;
        }

        private String dataFormat() {
            if (!dataFormatDecoded) {
                synchronized (this) {
                    if (!dataFormatDecoded) {
                        dataFormat = decode(formatOffset(), formatLength);
                        dataFormatDecoded = true;
                    }
                }
            }
            return dataFormat;
        }

        private String source() {
            if (!sourceDecoded) {
                synchronized (this) {
                    if (!sourceDecoded) {
                        source = decode(sourceOffset(), sourceLength);
                        sourceDecoded = true;
                    }
                }
            }
            return source;
        }

        private String target() {
            if (!targetDecoded) {
                synchronized (this) {
                    if (!targetDecoded) {
                        target = decode(targetOffset(), targetLength);
                        targetDecoded = true;
                    }
                }
            }
            return target;
        }

        private String messageId() {
            if (!messageIdDecoded) {
                synchronized (this) {
                    if (!messageIdDecoded) {
                        messageId = decode(messageIdOffset(), messageIdLength);
                        messageIdDecoded = true;
                    }
                }
            }
            return messageId;
        }

        private int typeOffset() {
            return offset + ENVELOPE_HEADER_SIZE;
        }

        private int formatOffset() {
            return typeOffset() + positiveLength(typeLength);
        }

        private int sourceOffset() {
            return formatOffset() + positiveLength(formatLength);
        }

        private int targetOffset() {
            return sourceOffset() + positiveLength(sourceLength);
        }

        private int messageIdOffset() {
            return targetOffset() + positiveLength(targetLength);
        }

        private String decode(int stringOffset, int stringLength) {
            return stringLength < 0 ? null
                    : new String(bytes, stringOffset, stringLength, StandardCharsets.UTF_8);
        }

        private boolean encodedEquals(String candidate, int stringOffset, int stringLength) {
            return candidate == null ? stringLength < 0
                    : stringLength >= 0 && utf8Equals(bytes, stringOffset, stringLength, candidate);
        }

        @Override
        public SerializedMessage withData(Data<byte[]> data) {
            return getData() == data ? this : copy(data, getMetadata(), getSegment());
        }

        @Override
        public SerializedMessage withMetadata(Metadata metadata) {
            return getMetadata() == metadata ? this : copy(getData(), metadata, getSegment());
        }

        @Override
        public SerializedMessage withSegment(Integer segment) {
            return Objects.equals(getSegment(), segment) ? this : copy(getData(), getMetadata(), segment);
        }

        private SerializedMessage copy(Data<byte[]> data, Metadata metadata, Integer segment) {
            return new SerializedMessage(data, metadata, segment, getIndex(), getSource(), getTarget(),
                                         getRequestId(), getTimestamp(), getMessageId(), getOriginalRevision());
        }
    }

    private static final class ByteSlice implements Data.ByteArrayView {
        private final byte[] bytes;
        private final int offset;
        private final int length;
        private volatile byte[] materialized;

        private ByteSlice(byte[] bytes, int offset, int length) {
            this.bytes = bytes;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public byte[] array() {
            return bytes;
        }

        @Override
        public int offset() {
            return offset;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public byte[] get() {
            byte[] result = materialized;
            if (result == null) {
                materialized = result = Arrays.copyOfRange(bytes, offset, offset + length);
            }
            return result;
        }
    }
}
