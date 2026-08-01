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

package io.fluxzero.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.NonNull;

import java.beans.ConstructorProperties;
import java.beans.Transient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Represents a fully serialized message for transport or storage.
 *
 * <p>A message is either materialized from its ordinary {@link Data}, {@link Metadata} and routing fields, or backed
 * by one native envelope whose components are materialized independently on demand. The public constructors, getters,
 * setters and immutable-style {@code withX} methods are identical for both representations.</p>
 *
 * <p>The envelope keeps fixed-width runtime fields at the front and application-owned payload and metadata bytes at
 * the end. Fixed-width mutations patch an exclusively owned envelope in place. Variable-width mutations invalidate
 * it and cause the next native boundary to encode one replacement.</p>
 */
public class SerializedMessage implements SerializedObject<byte[]>, HasMetadata {
    public static final int MAGIC = 0x465A4D45; // FZME
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 72;

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
    private static final int MAX_VALUE_BYTES = 512 * 1024 * 1024;
    private static final int MATERIALIZED_STRING = Integer.MIN_VALUE;
    private static final int UNKNOWN_ENCODED_STRING = Integer.MIN_VALUE;
    private static final ThreadLocal<EncodedDataHeaderCache> ENCODED_DATA_HEADER_CACHE =
            ThreadLocal.withInitial(EncodedDataHeaderCache::new);
    private static final Data<byte[]> DEFERRED_DATA =
            new Data<>(() -> {
                throw new IllegalStateException("Deferred native message data was accessed directly");
            }, null, 0, null);

    @NonNull
    private Data<byte[]> data;
    private Metadata metadata;
    private Integer segment;
    private Long index;
    private String source;
    private String target;
    private Integer requestId;
    private Long timestamp;
    private String messageId;
    private transient Integer originalRevision;

    private final byte[] envelope;
    private final int envelopeOffset;
    private final int envelopeLength;
    private final int payloadLength;
    private final ByteSlice payloadSlice;
    private final ByteSlice metadataSlice;
    private final int dataTypeOffset;
    private final int dataFormatOffset;
    private final int sourceOffset;
    private final int targetOffset;
    private final int messageIdOffset;
    private volatile int dataRevision;
    private volatile String deferredDataType;
    private volatile int dataTypeLength;
    private volatile int dataFormatLength;
    private volatile int sourceLength;
    private volatile int targetLength;
    private volatile int messageIdLength;
    private byte[] encodedDataHeaders;
    private int encodedDataTypeOffset;
    private int encodedDataTypeLength = UNKNOWN_ENCODED_STRING;
    private int encodedDataFormatOffset;
    private int encodedDataFormatLength = UNKNOWN_ENCODED_STRING;
    private byte[] encodedMessageHeaders;
    private int encodedMessageIdOffset;
    private int encodedMessageIdLength = UNKNOWN_ENCODED_STRING;
    private volatile boolean metadataDeferred;
    private volatile boolean reusable;

    /**
     * Creates a materialized message with every existing public field.
     */
    @ConstructorProperties({
            "data", "metadata", "segment", "index", "source", "target", "requestId", "timestamp", "messageId",
            "originalRevision"
    })
    public SerializedMessage(
            @NonNull Data<byte[]> data, Metadata metadata, Integer segment, Long index,
            String source, String target, Integer requestId, Long timestamp,
            String messageId, Integer originalRevision) {
        this(null, 0, 0, Objects.requireNonNull(data, "data"), metadata,
             segment, index, source, target, requestId, timestamp, messageId,
             originalRevision, data.getRevision(), -1, null, null,
             0, MATERIALIZED_STRING, 0, MATERIALIZED_STRING,
             0, MATERIALIZED_STRING, 0, MATERIALIZED_STRING,
             0, MATERIALIZED_STRING);
    }

    /**
     * Creates a materialized message with payload, metadata, identity and timestamp.
     */
    public SerializedMessage(
            Data<byte[]> data, Metadata metadata,
            String messageId, Long timestamp) {
        this(data, metadata, null, null, null, null, null,
             timestamp, messageId, null);
    }

    /**
     * Creates an independent message around the serialized data of another message while retaining any already
     * encoded type, format and unchanged message-id headers for the next native boundary.
     *
     * <p>Routing, tracking and request fields are deliberately reset. Payload bytes remain shared through the
     * ordinary immutable {@link Data} value or byte-array view.</p>
     */
    public SerializedMessage(
            SerializedMessage dataSource,
            Metadata metadata,
            String messageId,
            Long timestamp) {
        this(Objects.requireNonNull(
                        dataSource, "dataSource")
                     .getData(),
             metadata, messageId, timestamp);
        copyEncodedDataHeadersFrom(dataSource);
        copyEncodedMessageIdFrom(
                dataSource, messageId);
    }

    private SerializedMessage(
            byte[] envelope, int envelopeOffset, int envelopeLength,
            Data<byte[]> data, Metadata metadata, Integer segment, Long index,
            String source, String target, Integer requestId, Long timestamp,
            String messageId, Integer originalRevision, int dataRevision, int payloadLength,
            ByteSlice payloadSlice, ByteSlice metadataSlice,
            int dataTypeOffset, int dataTypeLength,
            int dataFormatOffset, int dataFormatLength,
            int sourceOffset, int sourceLength,
            int targetOffset, int targetLength,
            int messageIdOffset, int messageIdLength) {
        this.data = Objects.requireNonNull(data, "data");
        this.metadata = metadata;
        this.segment = segment;
        this.index = index;
        this.source = source;
        this.target = target;
        this.requestId = requestId;
        this.timestamp = timestamp;
        this.messageId = messageId;
        this.originalRevision = originalRevision;
        this.envelope = envelope;
        this.envelopeOffset = envelopeOffset;
        this.envelopeLength = envelopeLength;
        this.payloadLength = payloadLength;
        this.payloadSlice = payloadSlice;
        this.metadataSlice = metadataSlice;
        this.dataTypeOffset = dataTypeOffset;
        this.dataFormatOffset = dataFormatOffset;
        this.sourceOffset = sourceOffset;
        this.targetOffset = targetOffset;
        this.messageIdOffset = messageIdOffset;
        this.dataRevision = dataRevision;
        this.dataTypeLength = dataTypeLength;
        this.dataFormatLength = dataFormatLength;
        this.sourceLength = sourceLength;
        this.targetLength = targetLength;
        this.messageIdLength = messageIdLength;
        if (envelope != null) {
            encodedDataHeaders = envelope;
            encodedDataTypeOffset = dataTypeOffset;
            encodedDataTypeLength = dataTypeLength;
            encodedDataFormatOffset = dataFormatOffset;
            encodedDataFormatLength = dataFormatLength;
            encodedMessageHeaders = envelope;
            encodedMessageIdOffset = messageIdOffset;
            encodedMessageIdLength = messageIdLength;
        }
        this.metadataDeferred = metadata == null && metadataSlice != null;
        this.reusable = envelope != null;
    }

    /**
     * Returns an existing reusable envelope or encodes the supplied message once.
     */
    public static SerializedMessage encode(SerializedMessage message) {
        if (message.isReusable()) {
            return message;
        }

        Data<byte[]> data = message.getData();
        Data.ByteArrayView payloadView =
                data.byteArrayView();
        byte[] payload = payloadView == null
                ? data.getValue() : null;
        int payloadLength = payloadView == null
                ? length(payload)
                : payloadView.length();
        Metadata messageMetadata = message.getMetadata();
        Metadata normalizedMetadata = messageMetadata == null ? Metadata.empty() : messageMetadata;
        byte[] metadata = normalizedMetadata.toData().getValue();
        String type = data.getType();
        String format = data.getFormat();
        String source = message.getSource();
        String target = message.getTarget();
        String messageId = message.getMessageId();
        boolean reuseEncodedType = message.hasEncodedDataType();
        boolean reuseEncodedFormat = message.hasEncodedDataFormat();
        EncodedDataHeaderCache headerCache = reuseEncodedType && reuseEncodedFormat
                ? null : ENCODED_DATA_HEADER_CACHE.get();
        byte[] encodedType = reuseEncodedType ? null : headerCache.type(type);
        byte[] encodedFormat = reuseEncodedFormat ? null : headerCache.format(format);
        int typeLength = reuseEncodedType
                ? message.encodedDataTypeLength : length(encodedType);
        int formatLength = reuseEncodedFormat
                ? message.encodedDataFormatLength : length(encodedFormat);
        int sourceLength = utf8Length(source);
        int targetLength = utf8Length(target);
        int messageIdLength = message.encodedMessageIdLength(
                messageId);

        int length = HEADER_SIZE;
        length = addLength(length, typeLength);
        length = addLength(length, formatLength);
        length = addLength(length, sourceLength);
        length = addLength(length, targetLength);
        length = addLength(length, messageIdLength);
        length = addLength(length, payloadLength);
        length = addLength(length, metadata.length);
        if (length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Native message envelope exceeds maximum size");
        }

        byte[] result = new byte[length];
        writeInt(result, 0, MAGIC);
        result[4] = VERSION;
        result[FLAGS_OFFSET] = flags(message);
        writeInt(result, TOTAL_LENGTH_OFFSET, length);
        writeInt(result, SEGMENT_OFFSET, valueOrZero(message.getSegment()));
        writeInt(result, REQUEST_ID_OFFSET, valueOrZero(message.getRequestId()));
        writeLong(result, INDEX_OFFSET, valueOrZero(message.getIndex()));
        writeLong(result, TIMESTAMP_OFFSET, valueOrZero(message.getTimestamp()));
        writeInt(result, REVISION_OFFSET, data.getRevision());
        writeInt(result, TYPE_LENGTH_OFFSET, typeLength);
        writeInt(result, FORMAT_LENGTH_OFFSET, formatLength);
        writeInt(result, SOURCE_LENGTH_OFFSET, sourceLength);
        writeInt(result, TARGET_LENGTH_OFFSET, targetLength);
        writeInt(result, MESSAGE_ID_LENGTH_OFFSET, messageIdLength);
        writeInt(result, PAYLOAD_LENGTH_OFFSET, payloadLength);
        writeInt(result, METADATA_LENGTH_OFFSET, metadata.length);
        writeInt(result, ORIGINAL_REVISION_OFFSET, message.getOriginalRevision());

        int cursor = HEADER_SIZE;
        cursor = reuseEncodedType
                ? message.copyEncodedDataType(result, cursor)
                : copy(encodedType, result, cursor);
        cursor = reuseEncodedFormat
                ? message.copyEncodedDataFormat(result, cursor)
                : copy(encodedFormat, result, cursor);
        cursor = writeUtf8(source, result, cursor);
        cursor = writeUtf8(target, result, cursor);
        cursor = message.copyEncodedMessageId(
                messageId, result, cursor);
        cursor = payloadView == null
                ? copy(payload, result, cursor)
                : copy(
                        payloadView.array(),
                        payloadView.offset(),
                        payloadLength,
                        result, cursor);
        copy(metadata, result, cursor);

        SerializedMessage encoded = new SerializedMessage(
                result, 0, result.length, data, normalizedMetadata,
                message.getSegment(), message.getIndex(), message.getSource(), message.getTarget(),
                message.getRequestId(), message.getTimestamp(), message.getMessageId(),
                message.getOriginalRevision(), data.getRevision(), positiveLength(payloadLength), null, null,
                0, MATERIALIZED_STRING, 0, MATERIALIZED_STRING,
                0, MATERIALIZED_STRING, 0, MATERIALIZED_STRING, 0, MATERIALIZED_STRING);
        encoded.encodedDataHeaders = result;
        encoded.encodedDataTypeOffset = HEADER_SIZE;
        encoded.encodedDataTypeLength = typeLength;
        encoded.encodedDataFormatOffset = HEADER_SIZE + positiveLength(typeLength);
        encoded.encodedDataFormatLength = formatLength;
        encoded.encodedMessageHeaders = result;
        encoded.encodedMessageIdOffset =
                HEADER_SIZE
                + positiveLength(typeLength)
                + positiveLength(formatLength)
                + positiveLength(sourceLength)
                + positiveLength(targetLength);
        encoded.encodedMessageIdLength =
                messageIdLength;
        return encoded;
    }

    /**
     * Decodes one envelope into an independently owned envelope whose payload and metadata remain lazy slices.
     */
    public static SerializedMessage decode(byte[] bytes, int offset, int length) throws IOException {
        return decode(bytes, offset, length, true);
    }

    /**
     * Decodes one envelope as a zero-copy view of an exclusively owned transport or persistence buffer.
     *
     * <p>The returned message retains the supplied array and may patch fixed-width headers in its own envelope range.
     * Callers must therefore not reuse or modify the array and should keep all views bounded by the lifecycle of the
     * decoded batch. Use {@link #decode(byte[], int, int)} when independent per-message ownership is required.</p>
     */
    public static SerializedMessage decodeView(byte[] bytes, int offset, int length) throws IOException {
        return decode(bytes, offset, length, false);
    }

    private static SerializedMessage decode(byte[] bytes, int offset, int length, boolean copySlice)
            throws IOException {
        validateRange(bytes, offset, length);
        if (length > MAX_VALUE_BYTES) {
            throw new IOException("Native message envelope exceeds maximum size");
        }
        int declaredLength = readEnvelopeSize(bytes, offset, length);
        if (declaredLength != length) {
            throw new IOException("Invalid native message length " + declaredLength + ", expected " + length);
        }
        if (copySlice) {
            bytes = Arrays.copyOfRange(bytes, offset, offset + length);
            offset = 0;
        }

        int typeLength = readNullableLength(bytes, offset + TYPE_LENGTH_OFFSET, "type");
        int formatLength = readNullableLength(bytes, offset + FORMAT_LENGTH_OFFSET, "format");
        int sourceLength = readNullableLength(bytes, offset + SOURCE_LENGTH_OFFSET, "source");
        int targetLength = readNullableLength(bytes, offset + TARGET_LENGTH_OFFSET, "target");
        int messageIdLength = readNullableLength(bytes, offset + MESSAGE_ID_LENGTH_OFFSET, "message id");
        int payloadLength = readNullableLength(bytes, offset + PAYLOAD_LENGTH_OFFSET, "payload");
        int metadataLength = readRequiredLength(bytes, offset + METADATA_LENGTH_OFFSET, "metadata");

        long expected = HEADER_SIZE;
        expected += positiveLength(typeLength);
        expected += positiveLength(formatLength);
        expected += positiveLength(sourceLength);
        expected += positiveLength(targetLength);
        expected += positiveLength(messageIdLength);
        expected += positiveLength(payloadLength);
        expected += metadataLength;
        if (expected != length) {
            throw new IOException("Native message fields do not match envelope length");
        }

        int cursor = offset + HEADER_SIZE;
        int typeOffset = cursor;
        cursor += positiveLength(typeLength);
        int formatOffset = cursor;
        cursor += positiveLength(formatLength);
        int sourceOffset = cursor;
        cursor += positiveLength(sourceLength);
        int targetOffset = cursor;
        cursor += positiveLength(targetLength);
        int messageIdOffset = cursor;
        cursor += positiveLength(messageIdLength);
        int payloadOffset = cursor;
        cursor += positiveLength(payloadLength);
        int metadataOffset = cursor;

        ByteSlice payloadSlice = payloadLength < 0 ? null : new ByteSlice(bytes, payloadOffset, payloadLength);
        ByteSlice metadataSlice = new ByteSlice(bytes, metadataOffset, metadataLength);
        int flags = bytes[offset + FLAGS_OFFSET] & 0xff;
        return new SerializedMessage(
                bytes, offset, length, DEFERRED_DATA, null,
                (flags & HAS_SEGMENT) == 0 ? null : readInt(bytes, offset + SEGMENT_OFFSET),
                (flags & HAS_INDEX) == 0 ? null : readLong(bytes, offset + INDEX_OFFSET),
                null, null,
                (flags & HAS_REQUEST_ID) == 0 ? null : readInt(bytes, offset + REQUEST_ID_OFFSET),
                (flags & HAS_TIMESTAMP) == 0 ? null : readLong(bytes, offset + TIMESTAMP_OFFSET),
                null, readInt(bytes, offset + ORIGINAL_REVISION_OFFSET),
                readInt(bytes, offset + REVISION_OFFSET), positiveLength(payloadLength),
                payloadSlice, metadataSlice,
                typeOffset, typeLength, formatOffset, formatLength,
                sourceOffset, sourceLength, targetOffset, targetLength, messageIdOffset, messageIdLength);
    }

    /**
     * Decodes a sequence of concatenated native envelopes.
     */
    public static List<SerializedMessage> decodeAll(byte[] bytes) throws IOException {
        return decodeAll(bytes, 0, bytes.length);
    }

    /**
     * Decodes a slice containing a sequence of concatenated native envelopes.
     */
    public static List<SerializedMessage> decodeAll(byte[] bytes, int start, int length) throws IOException {
        return decodeAll(bytes, start, length, true);
    }

    /**
     * Decodes concatenated envelopes as zero-copy views of one exclusively owned byte sequence.
     *
     * <p>Retaining any returned message retains the complete source sequence. This method is intended for bounded
     * transport, persistence and cache batches whose messages share the same lifecycle.</p>
     */
    public static List<SerializedMessage> decodeAllViews(byte[] bytes) throws IOException {
        return decodeAllViews(bytes, 0, bytes.length);
    }

    /**
     * Decodes a slice of concatenated envelopes as zero-copy views of one exclusively owned byte sequence.
     */
    public static List<SerializedMessage> decodeAllViews(byte[] bytes, int start, int length) throws IOException {
        return decodeAll(bytes, start, length, false);
    }

    private static List<SerializedMessage> decodeAll(byte[] bytes, int start, int length, boolean copySlices)
            throws IOException {
        validateRange(bytes, start, length);
        List<SerializedMessage> result = new ArrayList<>();
        int offset = start;
        int end = start + length;
        while (offset < end) {
            if (end - offset < HEADER_SIZE) {
                throw new IOException("Truncated native message sequence");
            }
            int envelopeLength = readInt(bytes, offset + TOTAL_LENGTH_OFFSET);
            if (envelopeLength > end - offset) {
                throw new IOException("Native message exceeds sequence boundary");
            }
            result.add(decode(bytes, offset, envelopeLength, copySlices));
            offset = Math.addExact(offset, envelopeLength);
        }
        return result;
    }

    public static boolean isEnvelope(byte[] bytes) {
        return isEnvelope(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    public static boolean isEnvelope(byte[] bytes, int offset, int length) {
        return bytes != null && offset >= 0 && length >= HEADER_SIZE
               && offset <= bytes.length - length && readInt(bytes, offset) == MAGIC;
    }

    /**
     * Reads and validates the size of the native envelope at {@code offset} without decoding its fields.
     */
    public static int readEnvelopeSize(byte[] bytes, int offset, int available) throws IOException {
        validateRange(bytes, offset, available);
        if (available < HEADER_SIZE || readInt(bytes, offset) != MAGIC) {
            throw new IOException("Not a native serialized message");
        }
        int version = bytes[offset + 4] & 0xff;
        if (version != VERSION) {
            throw new IOException("Unsupported native message version " + version);
        }
        int flags = bytes[offset + FLAGS_OFFSET] & 0xff;
        if ((flags & ~(HAS_SEGMENT | HAS_REQUEST_ID | HAS_INDEX | HAS_TIMESTAMP)) != 0) {
            throw new IOException("Unsupported native message flags " + flags);
        }
        int result = readInt(bytes, offset + TOTAL_LENGTH_OFFSET);
        if (result < HEADER_SIZE || result > available || result > MAX_VALUE_BYTES) {
            throw new IOException("Invalid native message length " + result);
        }
        return result;
    }

    /**
     * Reads the fixed-width log index without decoding payload, metadata or variable-width headers.
     */
    public static Long readIndex(byte[] bytes, int offset, int available) throws IOException {
        readEnvelopeSize(bytes, offset, available);
        int flags = bytes[offset + FLAGS_OFFSET] & 0xff;
        return (flags & HAS_INDEX) == 0 ? null : readLong(bytes, offset + INDEX_OFFSET);
    }

    @JsonIgnore
    public boolean isReusable() {
        return reusable;
    }

    boolean isPayloadMaterialized() {
        return payloadSlice == null || payloadSlice.isMaterialized();
    }

    boolean isMetadataMaterialized() {
        return metadataSlice == null || metadataSlice.isMaterialized();
    }

    boolean areDataHeadersMaterialized() {
        return dataTypeLength == MATERIALIZED_STRING && dataFormatLength == MATERIALIZED_STRING;
    }

    boolean isTypeMaterialized() {
        return dataTypeLength == MATERIALIZED_STRING;
    }

    boolean isTargetMaterialized() {
        return targetLength == MATERIALIZED_STRING;
    }

    public int envelopeSize() {
        return envelopeLength;
    }

    public void copyEnvelopeTo(byte[] target, int targetOffset) {
        if (!reusable) {
            throw new IllegalStateException("Native message envelope was invalidated by a variable-width mutation");
        }
        System.arraycopy(envelope, envelopeOffset, target, targetOffset, envelopeLength);
    }

    public byte[] copyEnvelope() {
        byte[] result = new byte[envelopeLength];
        copyEnvelopeTo(result, 0);
        return result;
    }

    @Transient
    public long getBytes() {
        if (reusable) {
            return payloadLength;
        }
        byte[] value = data.getValue();
        return value == null ? 0L : value.length;
    }

    public synchronized void setData(@NonNull Data<byte[]> data) {
        this.data = Objects.requireNonNull(data, "data");
        reusable = false;
        deferredDataType = null;
        dataTypeLength = MATERIALIZED_STRING;
        dataFormatLength = MATERIALIZED_STRING;
        encodedDataHeaders = null;
        encodedDataTypeLength = UNKNOWN_ENCODED_STRING;
        encodedDataFormatLength = UNKNOWN_ENCODED_STRING;
        dataRevision = data.getRevision();
    }

    public Data<byte[]> getData() {
        if (dataTypeLength == MATERIALIZED_STRING && dataFormatLength == MATERIALIZED_STRING) {
            return data;
        }
        synchronized (this) {
            if (dataTypeLength != MATERIALIZED_STRING || dataFormatLength != MATERIALIZED_STRING) {
                String dataType = dataTypeLength == MATERIALIZED_STRING
                        ? deferredDataType : string(dataTypeOffset, dataTypeLength);
                data = new Data<>(payloadSlice == null ? () -> null : payloadSlice,
                                  dataType, dataRevision,
                                  string(dataFormatOffset, dataFormatLength));
                deferredDataType = null;
                dataTypeLength = MATERIALIZED_STRING;
                dataFormatLength = MATERIALIZED_STRING;
            }
            return data;
        }
    }

    @Override
    public Data<byte[]> data() {
        return getData();
    }

    @Override
    public Metadata getMetadata() {
        if (!metadataDeferred) {
            return metadata;
        }
        synchronized (this) {
            if (metadataDeferred) {
                metadata = Metadata.fromData(new Data<>(
                        metadataSlice,
                        Metadata.DATA_TYPE, 0, Metadata.DATA_FORMAT));
                metadataDeferred = false;
            }
            return metadata;
        }
    }

    /**
     * Checks an opaque metadata key without materializing this message's {@link Metadata} wrapper.
     */
    public boolean metadataContainsKey(String key) {
        Objects.requireNonNull(key, "key");
        return metadataDeferred
                ? Metadata.containsKey(metadataSlice, key)
                : metadata != null && metadata.containsKey(key);
    }

    /**
     * Reads one opaque metadata value without materializing this message's complete {@link Metadata} wrapper.
     */
    public String getMetadataValue(String key) {
        Objects.requireNonNull(key, "key");
        return metadataDeferred
                ? Metadata.get(metadataSlice, key)
                : metadata == null ? null : metadata.get(key);
    }

    @Override
    public boolean chunked() {
        return metadataContainsKey(HasMetadata.FINAL_CHUNK);
    }

    @Override
    public boolean lastChunk() {
        String value = getMetadataValue(HasMetadata.FINAL_CHUNK);
        return value == null || "true".equalsIgnoreCase(value);
    }

    @Override
    public boolean firstChunk() {
        String value = getMetadataValue(HasMetadata.FIRST_CHUNK);
        return value == null || "true".equalsIgnoreCase(value);
    }

    @Override
    @Transient
    public int getRevision() {
        return dataRevision;
    }

    @Override
    @Transient
    public synchronized String getType() {
        if (dataTypeLength != MATERIALIZED_STRING) {
            deferredDataType = string(dataTypeOffset, dataTypeLength);
            dataTypeLength = MATERIALIZED_STRING;
        }
        Data<byte[]> data = this.data;
        return data == DEFERRED_DATA ? deferredDataType : data.getType();
    }

    public int getOriginalRevision() {
        Integer value = originalRevision;
        return value == null ? dataRevision : value;
    }

    @Override
    public SerializedMessage withData(@NonNull Data<byte[]> data) {
        java.util.Objects.requireNonNull(data, "data");
        return getData() == data ? this : copyWith(data, getMetadata(), getSegment());
    }

    public SerializedMessage withMetadata(Metadata metadata) {
        return getMetadata() == metadata ? this : copyWith(getData(), metadata, getSegment());
    }

    public SerializedMessage withSegment(Integer segment) {
        return getSegment() == segment ? this : copyWith(getData(), getMetadata(), segment);
    }

    private SerializedMessage copyWith(Data<byte[]> data, Metadata metadata, Integer segment) {
        SerializedMessage result = new SerializedMessage(
                data, metadata, segment, getIndex(), getSource(), getTarget(),
                getRequestId(), getTimestamp(), getMessageId(), getOriginalRevision());
        if (data == this.data) {
            result.copyEncodedDataHeadersFrom(this);
        }
        result.copyEncodedMessageIdFrom(
                this, result.messageId);
        return result;
    }

    public void setMetadata(Metadata metadata) {
        reusable = false;
        metadataDeferred = false;
        this.metadata = metadata;
    }

    public Integer getSegment() {
        return segment;
    }

    public void setSegment(Integer segment) {
        if (Objects.equals(this.segment, segment)) {
            return;
        }
        this.segment = segment;
        if (reusable) {
            patchFlag(HAS_SEGMENT, segment != null);
            writeInt(envelope, envelopeOffset + SEGMENT_OFFSET, valueOrZero(segment));
        }
    }

    public Long getIndex() {
        return index;
    }

    public void setIndex(Long index) {
        if (Objects.equals(this.index, index)) {
            return;
        }
        this.index = index;
        if (reusable) {
            patchFlag(HAS_INDEX, index != null);
            writeLong(envelope, envelopeOffset + INDEX_OFFSET, valueOrZero(index));
        }
    }

    public synchronized String getSource() {
        if (sourceLength != MATERIALIZED_STRING) {
            source = string(sourceOffset, sourceLength);
            sourceLength = MATERIALIZED_STRING;
        }
        return source;
    }

    public synchronized void setSource(String source) {
        reusable = false;
        sourceLength = MATERIALIZED_STRING;
        this.source = source;
    }

    public synchronized String getTarget() {
        if (targetLength != MATERIALIZED_STRING) {
            target = string(targetOffset, targetLength);
            targetLength = MATERIALIZED_STRING;
        }
        return target;
    }

    public synchronized boolean targetEquals(String candidate) {
        if (targetLength == MATERIALIZED_STRING) {
            return Objects.equals(target, candidate);
        }
        if (targetLength < 0) {
            return candidate == null;
        }
        if (candidate == null) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) > 0x7f) {
                return Objects.equals(getTarget(), candidate);
            }
        }
        if (candidate.length() != targetLength) {
            return false;
        }
        for (int i = 0; i < targetLength; i++) {
            byte encoded = envelope[targetOffset + i];
            if (encoded < 0) {
                return Objects.equals(getTarget(), candidate);
            }
            if (encoded != (byte) candidate.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares a type identifier directly with its encoded UTF-8 bytes when this message is envelope-backed.
     */
    public synchronized boolean typeEquals(String candidate) {
        if (dataTypeLength == MATERIALIZED_STRING) {
            return Objects.equals(getType(), candidate);
        }
        if (dataTypeLength < 0) {
            return candidate == null;
        }
        if (candidate == null) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) > 0x7f) {
                return Objects.equals(getType(), candidate);
            }
        }
        if (candidate.length() != dataTypeLength) {
            return false;
        }
        for (int i = 0; i < dataTypeLength; i++) {
            byte encoded = envelope[dataTypeOffset + i];
            if (encoded < 0) {
                return Objects.equals(getType(), candidate);
            }
            if (encoded != (byte) candidate.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public synchronized void setTarget(String target) {
        reusable = false;
        targetLength = MATERIALIZED_STRING;
        this.target = target;
    }

    public Integer getRequestId() {
        return requestId;
    }

    public void setRequestId(Integer requestId) {
        if (Objects.equals(this.requestId, requestId)) {
            return;
        }
        this.requestId = requestId;
        if (reusable) {
            patchFlag(HAS_REQUEST_ID, requestId != null);
            writeInt(envelope, envelopeOffset + REQUEST_ID_OFFSET, valueOrZero(requestId));
        }
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        if (Objects.equals(this.timestamp, timestamp)) {
            return;
        }
        this.timestamp = timestamp;
        if (reusable) {
            patchFlag(HAS_TIMESTAMP, timestamp != null);
            writeLong(envelope, envelopeOffset + TIMESTAMP_OFFSET, valueOrZero(timestamp));
        }
    }

    public synchronized String getMessageId() {
        if (messageIdLength != MATERIALIZED_STRING) {
            messageId = string(messageIdOffset, messageIdLength);
            messageIdLength = MATERIALIZED_STRING;
        }
        return messageId;
    }

    public synchronized void setMessageId(String messageId) {
        reusable = false;
        messageIdLength = MATERIALIZED_STRING;
        encodedMessageHeaders = null;
        encodedMessageIdLength =
                UNKNOWN_ENCODED_STRING;
        this.messageId = messageId;
    }

    public synchronized void setOriginalRevision(Integer originalRevision) {
        if (Objects.equals(this.originalRevision, originalRevision)) {
            return;
        }
        this.originalRevision = originalRevision;
        if (reusable) {
            writeInt(envelope, envelopeOffset + ORIGINAL_REVISION_OFFSET,
                     originalRevision == null ? dataRevision : originalRevision);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof SerializedMessage that) || !that.canEqual(this)) {
            return false;
        }
        return Objects.equals(getData(), that.getData())
               && Objects.equals(getMetadata(), that.getMetadata())
               && Objects.equals(getSegment(), that.getSegment())
               && Objects.equals(getIndex(), that.getIndex())
               && Objects.equals(getSource(), that.getSource())
               && Objects.equals(getTarget(), that.getTarget())
               && Objects.equals(getRequestId(), that.getRequestId())
               && Objects.equals(getTimestamp(), that.getTimestamp())
               && Objects.equals(getMessageId(), that.getMessageId());
    }

    protected boolean canEqual(Object other) {
        return other instanceof SerializedMessage;
    }

    @Override
    public int hashCode() {
        final int prime = 59;
        final int nullValue = 43;
        int result = 1;
        Object value = getData();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getMetadata();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getSegment();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getIndex();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getSource();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getTarget();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getRequestId();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getTimestamp();
        result = result * prime + (value == null ? nullValue : value.hashCode());
        value = getMessageId();
        return result * prime + (value == null ? nullValue : value.hashCode());
    }

    @Override
    public String toString() {
        return "SerializedMessage(data=%s, metadata=%s, segment=%s, index=%s, source=%s, target=%s, "
               + "requestId=%s, timestamp=%s, messageId=%s, originalRevision=%s)".formatted(
                getData(), getMetadata(), getSegment(), getIndex(), getSource(), getTarget(),
                getRequestId(), getTimestamp(), getMessageId(), getOriginalRevision());
    }

    private void patchFlag(int flag, boolean present) {
        int offset = envelopeOffset + FLAGS_OFFSET;
        envelope[offset] = (byte) (present ? envelope[offset] | flag : envelope[offset] & ~flag);
    }

    private static byte flags(SerializedMessage message) {
        int flags = message.getSegment() == null ? 0 : HAS_SEGMENT;
        flags |= message.getRequestId() == null ? 0 : HAS_REQUEST_ID;
        flags |= message.getIndex() == null ? 0 : HAS_INDEX;
        flags |= message.getTimestamp() == null ? 0 : HAS_TIMESTAMP;
        return (byte) flags;
    }

    private static int addLength(int current, int valueLength) {
        return valueLength < 0 ? current : Math.addExact(current, valueLength);
    }

    private static int copy(byte[] source, byte[] target, int offset) {
        if (source == null) {
            return offset;
        }
        System.arraycopy(source, 0, target, offset, source.length);
        return offset + source.length;
    }

    private static int copy(
            byte[] source, int sourceOffset, int length,
            byte[] target, int targetOffset) {
        System.arraycopy(
                source, sourceOffset,
                target, targetOffset,
                length);
        return targetOffset + length;
    }

    private boolean hasEncodedDataType() {
        return encodedDataHeaders != null
               && encodedDataTypeLength
                  != UNKNOWN_ENCODED_STRING;
    }

    private boolean hasEncodedDataFormat() {
        return encodedDataHeaders != null
               && encodedDataFormatLength
                  != UNKNOWN_ENCODED_STRING;
    }

    private int encodedMessageIdLength(
            String messageId) {
        return encodedMessageHeaders == null
               || encodedMessageIdLength
                  == UNKNOWN_ENCODED_STRING
                ? utf8Length(messageId)
                : encodedMessageIdLength;
    }

    private int copyEncodedDataType(
            byte[] target,
            int targetOffset) {
        return copy(
                encodedDataHeaders,
                encodedDataTypeOffset,
                positiveLength(
                        encodedDataTypeLength),
                target, targetOffset);
    }

    private int copyEncodedDataFormat(
            byte[] target,
            int targetOffset) {
        return copy(
                encodedDataHeaders,
                encodedDataFormatOffset,
                positiveLength(
                        encodedDataFormatLength),
                target, targetOffset);
    }

    private int copyEncodedMessageId(
            String messageId, byte[] target,
            int targetOffset) {
        if (encodedMessageHeaders == null
            || encodedMessageIdLength
               == UNKNOWN_ENCODED_STRING) {
            return writeUtf8(
                    messageId, target, targetOffset);
        }
        return copy(
                encodedMessageHeaders,
                encodedMessageIdOffset,
                positiveLength(
                        encodedMessageIdLength),
                target, targetOffset);
    }

    private void copyEncodedDataHeadersFrom(
            SerializedMessage source) {
        encodedDataHeaders = source.encodedDataHeaders;
        encodedDataTypeOffset =
                source.encodedDataTypeOffset;
        encodedDataTypeLength =
                source.encodedDataTypeLength;
        encodedDataFormatOffset =
                source.encodedDataFormatOffset;
        encodedDataFormatLength =
                source.encodedDataFormatLength;
    }

    private void copyEncodedMessageIdFrom(
            SerializedMessage source,
            String messageId) {
        if (source.messageIdLength
            == MATERIALIZED_STRING
            && Objects.equals(
                    source.messageId, messageId)) {
            encodedMessageHeaders =
                    source.encodedMessageHeaders;
            encodedMessageIdOffset =
                    source.encodedMessageIdOffset;
            encodedMessageIdLength =
                    source.encodedMessageIdLength;
        }
    }

    private static int utf8Length(String value) {
        if (value == null) {
            return -1;
        }
        int result = 0;
        int i = 0;
        while (i <= value.length() - Integer.BYTES) {
            char first = value.charAt(i);
            char second = value.charAt(i + 1);
            char third = value.charAt(i + 2);
            char fourth = value.charAt(i + 3);
            if ((first | second | third | fourth) > 0x7f) {
                break;
            }
            result = Math.addExact(result, Integer.BYTES);
            i += Integer.BYTES;
        }
        for (; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x7f) {
                result = Math.addExact(result, 1);
            } else if (character <= 0x7ff) {
                result = Math.addExact(result, 2);
            } else if (Character.isHighSurrogate(character)
                       && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                result = Math.addExact(result, 4);
                i++;
            } else if (Character.isSurrogate(character)) {
                result = Math.addExact(result, 1);
            } else {
                result = Math.addExact(result, 3);
            }
        }
        return result;
    }

    private static int writeUtf8(String value, byte[] target, int offset) {
        if (value == null) {
            return offset;
        }
        int i = 0;
        while (i <= value.length() - Integer.BYTES) {
            char first = value.charAt(i);
            char second = value.charAt(i + 1);
            char third = value.charAt(i + 2);
            char fourth = value.charAt(i + 3);
            if ((first | second | third | fourth) > 0x7f) {
                break;
            }
            target[offset++] = (byte) first;
            target[offset++] = (byte) second;
            target[offset++] = (byte) third;
            target[offset++] = (byte) fourth;
            i += Integer.BYTES;
        }
        for (; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x7f) {
                target[offset++] = (byte) character;
            } else if (character <= 0x7ff) {
                target[offset++] = (byte) (0xc0 | character >>> 6);
                target[offset++] = (byte) (0x80 | character & 0x3f);
            } else if (Character.isHighSurrogate(character)
                       && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(character, value.charAt(++i));
                target[offset++] = (byte) (0xf0 | codePoint >>> 18);
                target[offset++] = (byte) (0x80 | codePoint >>> 12 & 0x3f);
                target[offset++] = (byte) (0x80 | codePoint >>> 6 & 0x3f);
                target[offset++] = (byte) (0x80 | codePoint & 0x3f);
            } else if (Character.isSurrogate(character)) {
                target[offset++] = '?';
            } else {
                target[offset++] = (byte) (0xe0 | character >>> 12);
                target[offset++] = (byte) (0x80 | character >>> 6 & 0x3f);
                target[offset++] = (byte) (0x80 | character & 0x3f);
            }
        }
        return offset;
    }

    private static int length(byte[] value) {
        return value == null ? -1 : value.length;
    }

    private static int positiveLength(int value) {
        return Math.max(0, value);
    }

    private static final class EncodedDataHeaderCache {
        private String type;
        private byte[] encodedType;
        private String format;
        private byte[] encodedFormat;

        private byte[] type(String value) {
            if (value == null) {
                return null;
            }
            if (value != type && !value.equals(type)) {
                type = value;
                encodedType = value.getBytes(StandardCharsets.UTF_8);
            }
            return encodedType;
        }

        private byte[] format(String value) {
            if (value == null) {
                return null;
            }
            if (value != format && !value.equals(format)) {
                format = value;
                encodedFormat = value.getBytes(StandardCharsets.UTF_8);
            }
            return encodedFormat;
        }
    }

    private String string(int offset, int length) {
        return length < 0 ? null : new String(envelope, offset, length, StandardCharsets.UTF_8);
    }

    private static int readNullableLength(byte[] bytes, int offset, String label) throws IOException {
        int value = readInt(bytes, offset);
        if (value < -1 || value > MAX_VALUE_BYTES) {
            throw new IOException("Invalid native " + label + " length " + value);
        }
        return value;
    }

    private static int readRequiredLength(byte[] bytes, int offset, String label) throws IOException {
        int value = readInt(bytes, offset);
        if (value < 0 || value > MAX_VALUE_BYTES) {
            throw new IOException("Invalid native " + label + " length " + value);
        }
        return value;
    }

    private static void validateRange(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IOException("Invalid native message range");
        }
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static final class ByteSlice implements Data.ByteArrayView {
        private final byte[] source;
        private final int offset;
        private final int length;
        private volatile byte[] value;

        private ByteSlice(byte[] source, int offset, int length) {
            this.source = source;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public byte[] get() {
            byte[] current = value;
            if (current == null) {
                synchronized (this) {
                    current = value;
                    if (current == null) {
                        current = Arrays.copyOfRange(source, offset, offset + length);
                        value = current;
                    }
                }
            }
            return current;
        }

        @Override
        public byte[] array() {
            return source;
        }

        @Override
        public int offset() {
            return offset;
        }

        @Override
        public int length() {
            return length;
        }

        private boolean isMaterialized() {
            return value != null;
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
               | ((bytes[offset + 1] & 0xff) << 16)
               | ((bytes[offset + 2] & 0xff) << 8)
               | (bytes[offset + 3] & 0xff);
    }

    private static long readLong(byte[] bytes, int offset) {
        long result = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            result = (result << 8) | (bytes[offset + i] & 0xffL);
        }
        return result;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void writeLong(byte[] bytes, int offset, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            bytes[offset++] = (byte) (value >>> shift);
        }
    }
}
