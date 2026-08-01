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

package io.fluxzero.common.api.tracking;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.publishing.Append;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compact binary websocket representation for message publication and tracking.
 *
 * <p>The codec changes only the transport representation. Individual requests, messages, request identifiers,
 * positions and response timings remain intact. Unsupported or heterogeneous values are left to the regular
 * websocket transport codec.</p>
 */
public final class TrackingWireCodec {

    private static final int MAGIC = 0x465A5457; // FZTW
    private static final int VERSION = 1;
    private static final int NATIVE_VERSION = 2;
    private static final int DIRECT_APPEND = 1;
    private static final int APPEND_BATCH = 2;
    private static final int DIRECT_READ = 3;
    private static final int READ_BATCH = 4;
    private static final int DIRECT_READ_RESULT = 5;
    private static final int READ_RESULT_BATCH = 6;
    private static final int DIRECT_CLAIM_SEGMENT = 7;
    private static final int CLAIM_SEGMENT_BATCH = 8;
    private static final int MAX_BATCH_SIZE = 1_000_000;
    private static final int MAX_COLLECTION_SIZE = 2_000_000;
    private static final int MAX_VALUE_BYTES = 512 * 1024 * 1024;
    private static final MessageType[] MESSAGE_TYPES = MessageType.values();
    private static final Guarantee[] GUARANTEES = Guarantee.values();

    private TrackingWireCodec() {
    }

    /**
     * Encodes a supported tracking value, or returns {@code null} when the regular codec should be used.
     */
    public static byte[] tryEncode(JsonType value) throws IOException {
        if (value != null && value.getClass() == Append.class) {
            Append append = (Append) value;
            return encodeAppends(List.of(append), DIRECT_APPEND);
        }
        if (value instanceof RequestBatch<?> batch && allExactInstancesOf(batch.getRequests(), Append.class)) {
            @SuppressWarnings("unchecked")
            List<Append> appends = (List<Append>) (List<?>) batch.getRequests();
            return encodeAppends(appends, APPEND_BATCH);
        }
        if (value != null && value.getClass() == Read.class) {
            Read read = (Read) value;
            return encodeReads(List.of(read), DIRECT_READ);
        }
        if (value instanceof RequestBatch<?> batch && allExactInstancesOf(batch.getRequests(), Read.class)) {
            @SuppressWarnings("unchecked")
            List<Read> reads = (List<Read>) (List<?>) batch.getRequests();
            return encodeReads(reads, READ_BATCH);
        }
        if (value != null && value.getClass() == ReadResult.class) {
            ReadResult result = (ReadResult) value;
            return encodeReadResults(List.of(result), DIRECT_READ_RESULT);
        }
        if (value instanceof ResultBatch batch && allExactInstancesOf(batch.getResults(), ReadResult.class)) {
            @SuppressWarnings("unchecked")
            List<ReadResult> results = (List<ReadResult>) (List<?>) batch.getResults();
            return encodeReadResults(results, READ_RESULT_BATCH);
        }
        return null;
    }

    /**
     * Encodes tracking traffic with native per-message envelopes where applicable.
     *
     * <p>Message-free requests retain version one so their proven compact representation stays unchanged.</p>
     */
    public static byte[] tryEncodeNative(JsonType value) throws IOException {
        if (value != null && value.getClass() == Append.class) {
            Append append = (Append) value;
            return encodeNativeAppends(List.of(append), DIRECT_APPEND);
        }
        if (value instanceof RequestBatch<?> batch && allExactInstancesOf(batch.getRequests(), Append.class)) {
            @SuppressWarnings("unchecked")
            List<Append> appends = (List<Append>) (List<?>) batch.getRequests();
            return encodeNativeAppends(appends, APPEND_BATCH);
        }
        if (value != null && value.getClass() == ClaimSegment.class) {
            return encodeClaimSegments(List.of((ClaimSegment) value), DIRECT_CLAIM_SEGMENT);
        }
        if (value instanceof RequestBatch<?> batch
            && allExactInstancesOf(batch.getRequests(), ClaimSegment.class)) {
            @SuppressWarnings("unchecked")
            List<ClaimSegment> claims = (List<ClaimSegment>) (List<?>) batch.getRequests();
            return encodeClaimSegments(claims, CLAIM_SEGMENT_BATCH);
        }
        if (value != null && value.getClass() == ReadResult.class) {
            ReadResult result = (ReadResult) value;
            return encodeNativeReadResults(List.of(result), DIRECT_READ_RESULT);
        }
        if (value instanceof ResultBatch batch && allExactInstancesOf(batch.getResults(), ReadResult.class)) {
            @SuppressWarnings("unchecked")
            List<ReadResult> results = (List<ReadResult>) (List<?>) batch.getResults();
            return encodeNativeReadResults(results, READ_RESULT_BATCH);
        }
        return tryEncode(value);
    }

    /**
     * Decodes this codec's representation, or returns {@code null} for another transport representation.
     */
    public static JsonType tryDecode(byte[] bytes) throws IOException {
        return tryDecode(bytes, false);
    }

    /**
     * Decodes both native and legacy tracking representations.
     */
    public static JsonType tryDecodeNative(byte[] bytes) throws IOException {
        return tryDecode(bytes, true);
    }

    private static JsonType tryDecode(byte[] bytes, boolean nativeVersionAllowed) throws IOException {
        if (bytes.length < Integer.BYTES + 2 || readInt(bytes, 0) != MAGIC) {
            return null;
        }
        try {
            Reader input = new Reader(bytes);
            input.readInt();
            int version = input.readUnsignedByte();
            if (version != VERSION && (!nativeVersionAllowed || version != NATIVE_VERSION)) {
                throw new IOException("Unsupported tracking wire version " + version);
            }
            int kind = input.readUnsignedByte();
            JsonType result = version == NATIVE_VERSION
                    ? switch (kind) {
                        case DIRECT_APPEND -> decodeNativeAppends(input, true);
                        case APPEND_BATCH -> decodeNativeAppends(input, false);
                        case DIRECT_READ_RESULT -> decodeNativeReadResults(input, true);
                        case READ_RESULT_BATCH -> decodeNativeReadResults(input, false);
                        case DIRECT_CLAIM_SEGMENT -> decodeClaimSegments(input, true);
                        case CLAIM_SEGMENT_BATCH -> decodeClaimSegments(input, false);
                        default -> throw new IOException("Unknown native tracking wire value " + kind);
                    }
                    : switch (kind) {
                        case DIRECT_APPEND -> decodeAppends(input, true);
                        case APPEND_BATCH -> decodeAppends(input, false);
                        case DIRECT_READ -> decodeReads(input, true);
                        case READ_BATCH -> decodeReads(input, false);
                        case DIRECT_READ_RESULT -> decodeReadResults(input, true);
                        case READ_RESULT_BATCH -> decodeReadResults(input, false);
                        default -> throw new IOException("Unknown tracking wire value " + kind);
                    };
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing tracking wire bytes");
            }
            return result;
        } catch (EOFException e) {
            throw new IOException("Truncated tracking wire value", e);
        }
    }

    private static byte[] encodeNativeAppends(List<Append> appends, int kind) throws IOException {
        requireNonEmptyBatch(appends);
        Writer output = nativeWriter(kind, nativeAppendsWireSize(appends));
        output.writeInt(appends.size());
        for (Append append : appends) {
            output.writeLong(append.getRequestId());
            output.writeByte(append.getMessageType().ordinal());
            output.writeByte(append.getGuarantee().ordinal());
            output.writeInt(append.getMessages().size());
            for (SerializedMessage message : append.getMessages()) {
                writeNativeMessage(output, message);
            }
        }
        return output.toByteArray();
    }

    private static JsonType decodeNativeAppends(Reader input, boolean direct) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "append batch");
        if (direct && size != 1) {
            throw new IOException("Direct append representation should contain one request");
        }
        List<Append> appends = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            MessageType messageType = enumValue(MESSAGE_TYPES, input.readUnsignedByte(), "message type");
            Guarantee guarantee = enumValue(GUARANTEES, input.readUnsignedByte(), "guarantee");
            int messageCount = input.readSize(MAX_COLLECTION_SIZE, "messages");
            List<SerializedMessage> messages = new ArrayList<>(messageCount);
            for (int message = 0; message < messageCount; message++) {
                messages.add(input.readNativeMessage());
            }
            appends.add(new Append(requestId, messageType, messages, guarantee));
        }
        return direct ? appends.getFirst() : new RequestBatch<>(appends);
    }

    private static byte[] encodeAppends(List<Append> appends, int kind) throws IOException {
        requireNonEmptyBatch(appends);
        List<SerializedMessage> messages = appends.stream().flatMap(a -> a.getMessages().stream()).toList();
        MessageDescriptor descriptor = MessageDescriptor.of(messages);
        Writer output = writer(kind, messages);
        output.writeInt(appends.size());
        descriptor.write(output);
        for (Append append : appends) {
            output.writeLong(append.getRequestId());
            output.writeByte(append.getMessageType().ordinal());
            output.writeByte(append.getGuarantee().ordinal());
            output.writeInt(append.getMessages().size());
            for (SerializedMessage message : append.getMessages()) {
                writeMessage(output, message, descriptor);
            }
        }
        return output.toByteArray();
    }

    private static JsonType decodeAppends(Reader input, boolean direct) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "append batch");
        if (direct && size != 1) {
            throw new IOException("Direct append representation should contain one request");
        }
        MessageDescriptor descriptor = MessageDescriptor.read(input);
        List<Append> appends = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            MessageType messageType = enumValue(MESSAGE_TYPES, input.readUnsignedByte(), "message type");
            Guarantee guarantee = enumValue(GUARANTEES, input.readUnsignedByte(), "guarantee");
            int messageCount = input.readSize(MAX_COLLECTION_SIZE, "messages");
            List<SerializedMessage> messages = new ArrayList<>(messageCount);
            for (int message = 0; message < messageCount; message++) {
                messages.add(readMessage(input, descriptor));
            }
            appends.add(new Append(requestId, messageType, messages, guarantee));
        }
        return direct ? appends.getFirst() : new RequestBatch<>(appends);
    }

    private static byte[] encodeReads(List<Read> reads, int kind) throws IOException {
        requireNonEmptyBatch(reads);
        Writer output = writer(kind, List.of());
        output.writeInt(reads.size());
        for (Read read : reads) {
            output.writeLong(read.getRequestId());
            output.writeByte(read.getMessageType().ordinal());
            output.writeString(read.getConsumer());
            output.writeString(read.getTrackerId());
            output.writeInt(read.getMaxSize());
            output.writeLong(read.getMaxBytes());
            output.writeLong(read.getMaxTimeout());
            output.writeString(read.getTypeFilter());
            int flags = (read.isFilterMessageTarget() ? 1 : 0)
                        | (read.isIgnoreSegment() ? 1 << 1 : 0)
                        | (read.isSingleTracker() ? 1 << 2 : 0)
                        | (read.isClientControlledIndex() ? 1 << 3 : 0);
            output.writeByte(flags);
            output.writeNullableLong(read.getLastIndex());
            output.writeNullableLong(read.getPurgeTimeout());
        }
        return output.toByteArray();
    }

    private static byte[] encodeClaimSegments(List<ClaimSegment> claims, int kind) throws IOException {
        requireNonEmptyBatch(claims);
        Writer output = nativeWriter(kind, -1L);
        output.writeInt(claims.size());
        for (ClaimSegment claim : claims) {
            output.writeLong(claim.getRequestId());
            output.writeByte(claim.getMessageType().ordinal());
            output.writeString(claim.getConsumer());
            output.writeString(claim.getTrackerId());
            output.writeLong(claim.getMaxTimeout());
            output.writeString(claim.getTypeFilter());
            int flags = (claim.isFilterMessageTarget() ? 1 : 0)
                        | (claim.isClientControlledIndex() ? 1 << 1 : 0);
            output.writeByte(flags);
            output.writeNullableLong(claim.getLastIndex());
            output.writeNullableLong(claim.getPurgeTimeout());
        }
        return output.toByteArray();
    }

    private static JsonType decodeClaimSegments(Reader input, boolean direct) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "claim-segment batch");
        if (direct && size != 1) {
            throw new IOException("Direct claim-segment representation should contain one request");
        }
        List<ClaimSegment> claims = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            MessageType messageType = enumValue(MESSAGE_TYPES, input.readUnsignedByte(), "message type");
            String consumer = input.readString();
            String trackerId = input.readString();
            long maxTimeout = input.readLong();
            String typeFilter = input.readString();
            int flags = input.readUnsignedByte();
            claims.add(new ClaimSegment(requestId, messageType, consumer, trackerId, maxTimeout,
                                        (flags & (1 << 1)) != 0, typeFilter, (flags & 1) != 0,
                                        input.readNullableLong(), input.readNullableLong()));
        }
        return direct ? claims.getFirst() : new RequestBatch<>(claims);
    }

    private static JsonType decodeReads(Reader input, boolean direct) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "read batch");
        if (direct && size != 1) {
            throw new IOException("Direct read representation should contain one request");
        }
        List<Read> reads = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            MessageType messageType = enumValue(MESSAGE_TYPES, input.readUnsignedByte(), "message type");
            String consumer = input.readString();
            String trackerId = input.readString();
            int maxSize = input.readInt();
            long maxBytes = input.readLong();
            long maxTimeout = input.readLong();
            String typeFilter = input.readString();
            int flags = input.readUnsignedByte();
            reads.add(new Read(requestId, messageType, consumer, trackerId, maxSize, maxBytes, maxTimeout,
                               typeFilter, (flags & 1) != 0, (flags & (1 << 1)) != 0,
                               (flags & (1 << 2)) != 0, (flags & (1 << 3)) != 0,
                               input.readNullableLong(), input.readNullableLong()));
        }
        return direct ? reads.getFirst() : new RequestBatch<>(reads);
    }

    private static byte[] encodeReadResults(List<ReadResult> results, int kind) throws IOException {
        requireNonEmptyBatch(results);
        List<SerializedMessage> messages = results.stream()
                .flatMap(result -> result.getMessageBatch().getMessages().stream()).toList();
        MessageDescriptor descriptor = MessageDescriptor.of(messages);
        Writer output = writer(kind, messages);
        output.writeInt(results.size());
        descriptor.write(output);
        for (ReadResult result : results) {
            output.writeLong(result.getRequestId());
            output.writeLong(result.getTimestamp());
            output.writeLong(result.getRequestReceivedTimestamp());
            output.writeLong(result.getResponseQueuedTimestamp());
            output.writeLong(result.getResponseSendStartTimestamp());
            MessageBatch batch = result.getMessageBatch();
            int[] segment = batch.getSegment();
            if (segment == null || segment.length != 2) {
                throw new IOException("Tracking segment should contain exactly two bounds");
            }
            output.writeInt(segment[0]);
            output.writeInt(segment[1]);
            output.writeInt(batch.getMessages().size());
            for (SerializedMessage message : batch.getMessages()) {
                writeMessage(output, message, descriptor);
            }
            output.writeNullableLong(batch.getLastIndex());
            writePosition(output, batch.getPosition());
            output.writeBoolean(batch.isCaughtUp());
        }
        return output.toByteArray();
    }

    private static byte[] encodeNativeReadResults(List<ReadResult> results, int kind) throws IOException {
        requireNonEmptyBatch(results);
        Writer output = nativeWriter(kind, nativeReadResultsWireSize(results));
        output.writeInt(results.size());
        for (ReadResult result : results) {
            output.writeLong(result.getRequestId());
            output.writeLong(result.getTimestamp());
            output.writeLong(result.getRequestReceivedTimestamp());
            output.writeLong(result.getResponseQueuedTimestamp());
            output.writeLong(result.getResponseSendStartTimestamp());
            MessageBatch batch = result.getMessageBatch();
            int[] segment = batch.getSegment();
            if (segment == null || segment.length != 2) {
                throw new IOException("Tracking segment should contain exactly two bounds");
            }
            output.writeInt(segment[0]);
            output.writeInt(segment[1]);
            output.writeInt(batch.getMessages().size());
            for (SerializedMessage message : batch.getMessages()) {
                writeNativeMessage(output, message);
            }
            output.writeNullableLong(batch.getLastIndex());
            writePosition(output, batch.getPosition());
            output.writeBoolean(batch.isCaughtUp());
        }
        return output.toByteArray();
    }

    private static JsonType decodeNativeReadResults(Reader input, boolean direct) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "read result batch");
        if (direct && size != 1) {
            throw new IOException("Direct read-result representation should contain one result");
        }
        List<RequestResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            long timestamp = input.readLong();
            long received = input.readLong();
            long queued = input.readLong();
            long sendStarted = input.readLong();
            int[] segment = {input.readInt(), input.readInt()};
            int messageCount = input.readSize(MAX_COLLECTION_SIZE, "messages");
            List<SerializedMessage> messages = new ArrayList<>(messageCount);
            for (int message = 0; message < messageCount; message++) {
                messages.add(input.readNativeMessage());
            }
            MessageBatch batch = new MessageBatch(segment, messages, input.readNullableLong(),
                                                  readPosition(input), input.readBoolean());
            ReadResult result = new ReadResult(requestId, batch, timestamp);
            result.setRequestReceivedTimestamp(received);
            result.setResponseQueuedTimestamp(queued);
            result.setResponseSendStartTimestamp(sendStarted);
            results.add(result);
        }
        return direct ? (JsonType) results.getFirst() : new ResultBatch(results);
    }

    private static void writeNativeMessage(Writer output, SerializedMessage message) {
        SerializedMessage nativeMessage = SerializedMessage.encode(message);
        output.writeInt(nativeMessage.envelopeSize());
        output.ensure(nativeMessage.envelopeSize());
        nativeMessage.copyEnvelopeTo(output.bytes, output.position);
        output.position += nativeMessage.envelopeSize();
    }

    private static JsonType decodeReadResults(Reader input, boolean direct) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "read result batch");
        if (direct && size != 1) {
            throw new IOException("Direct read-result representation should contain one result");
        }
        MessageDescriptor descriptor = MessageDescriptor.read(input);
        List<RequestResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            long timestamp = input.readLong();
            long received = input.readLong();
            long queued = input.readLong();
            long sendStarted = input.readLong();
            int[] segment = {input.readInt(), input.readInt()};
            int messageCount = input.readSize(MAX_COLLECTION_SIZE, "messages");
            List<SerializedMessage> messages = new ArrayList<>(messageCount);
            for (int message = 0; message < messageCount; message++) {
                messages.add(readMessage(input, descriptor));
            }
            MessageBatch batch = new MessageBatch(segment, messages, input.readNullableLong(),
                                                  readPosition(input), input.readBoolean());
            ReadResult result = new ReadResult(requestId, batch, timestamp);
            result.setRequestReceivedTimestamp(received);
            result.setResponseQueuedTimestamp(queued);
            result.setResponseSendStartTimestamp(sendStarted);
            results.add(result);
        }
        return direct ? (JsonType) results.getFirst() : new ResultBatch(results);
    }

    private static void writePosition(Writer output, Position position) {
        if (position == null) {
            output.writeInt(-1);
            return;
        }
        List<SegmentRange> ranges = position.getSegmentRanges();
        output.writeInt(ranges.size());
        for (SegmentRange range : ranges) {
            output.writeInt(range.segmentStart());
            output.writeInt(range.segmentEnd());
            output.writeLong(range.index());
        }
    }

    private static Position readPosition(Reader input) throws IOException {
        int size = input.readInt();
        if (size < 0) {
            return null;
        }
        if (size > MAX_COLLECTION_SIZE) {
            throw new IOException("Position exceeds maximum size");
        }
        List<SegmentRange> ranges = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ranges.add(new SegmentRange(input.readInt(), input.readInt(), input.readLong()));
        }
        return new Position(ranges);
    }

    private static void writeMessage(Writer output, SerializedMessage message, MessageDescriptor descriptor) {
        Data<byte[]> data = message.getData();
        output.writeBytes(data.getValue());
        if (!descriptor.sharedDataType()) {
            output.writeString(data.getType());
        }
        output.writeInt(data.getRevision());
        if (!descriptor.sharedDataFormat()) {
            output.writeString(data.getFormat());
        }
        output.writeRaw((message.getMetadata() == null ? Metadata.empty() : message.getMetadata())
                                .toData().getValue());
        output.writeNullableInt(message.getSegment());
        output.writeNullableLong(message.getIndex());
        if (!descriptor.sharedSource()) {
            output.writeString(message.getSource());
        }
        if (!descriptor.sharedTarget()) {
            output.writeString(message.getTarget());
        }
        output.writeNullableInt(message.getRequestId());
        output.writeNullableLong(message.getTimestamp());
        output.writeString(message.getMessageId());
    }

    private static SerializedMessage readMessage(Reader input, MessageDescriptor descriptor) throws IOException {
        byte[] value = input.readBytes();
        String type = descriptor.sharedDataType() ? descriptor.dataType() : input.readString();
        int revision = input.readInt();
        String format = descriptor.sharedDataFormat() ? descriptor.dataFormat() : input.readString();
        Metadata metadata = input.readMetadata();
        Integer segment = input.readNullableInt();
        Long index = input.readNullableLong();
        String source = descriptor.sharedSource() ? descriptor.source() : input.readString();
        String target = descriptor.sharedTarget() ? descriptor.target() : input.readString();
        return new SerializedMessage(new Data<>(value, type, revision, format), metadata,
                                     segment, index, source, target, input.readNullableInt(),
                                     input.readNullableLong(), input.readString(), null);
    }

    private static Writer writer(int kind, List<SerializedMessage> messages) {
        return writer(kind, messages, VERSION);
    }

    private static Writer writer(int kind, List<SerializedMessage> messages, int version) {
        long payloadBytes = messages.stream().mapToLong(SerializedMessage::getBytes).sum();
        int initialSize = (int) Math.min(MAX_VALUE_BYTES,
                                         Math.max(256L, payloadBytes + messages.size() * 64L));
        Writer output = new Writer(initialSize);
        output.writeInt(MAGIC);
        output.writeByte(version);
        output.writeByte(kind);
        return output;
    }

    private static Writer nativeWriter(int kind, long valueSize) {
        long exactSize = valueSize < 0 ? -1 : Integer.BYTES + 2L + valueSize;
        int initialSize = exactSize < 0 || exactSize > MAX_VALUE_BYTES
                ? 256 : (int) Math.max(256L, exactSize);
        Writer output = new Writer(initialSize);
        output.writeInt(MAGIC);
        output.writeByte(NATIVE_VERSION);
        output.writeByte(kind);
        return output;
    }

    private static long nativeAppendsWireSize(List<Append> appends) {
        long result = Integer.BYTES;
        for (Append append : appends) {
            long messageBytes = nativeMessagesWireSize(append.getMessages());
            if (messageBytes < 0) {
                return -1;
            }
            result += Long.BYTES + 2L + Integer.BYTES + messageBytes;
            if (result > MAX_VALUE_BYTES) {
                return -1;
            }
        }
        return result;
    }

    private static long nativeReadResultsWireSize(List<ReadResult> results) {
        long result = Integer.BYTES;
        for (ReadResult value : results) {
            long valueSize = nativeReadResultWireSize(value);
            if (valueSize < 0) {
                return -1;
            }
            result += valueSize;
            if (result > MAX_VALUE_BYTES) {
                return -1;
            }
        }
        return result;
    }

    private static long nativeReadResultWireSize(ReadResult result) {
        MessageBatch batch = result.getMessageBatch();
        long messageBytes = nativeMessagesWireSize(batch.getMessages());
        if (messageBytes < 0) {
            return -1;
        }
        long positionBytes = batch.getPosition() == null
                ? Integer.BYTES
                : Integer.BYTES + (long) batch.getPosition().getSegmentRanges().size()
                                  * (2L * Integer.BYTES + Long.BYTES);
        return 5L * Long.BYTES + 3L * Integer.BYTES + messageBytes
               + 1L + (batch.getLastIndex() == null ? 0L : Long.BYTES)
               + positionBytes + 1L;
    }

    private static long nativeMessagesWireSize(List<SerializedMessage> messages) {
        long result = 0;
        for (SerializedMessage message : messages) {
            if (!message.isReusable()) {
                return -1;
            }
            result += Integer.BYTES + (long) message.envelopeSize();
            if (result > MAX_VALUE_BYTES) {
                return -1;
            }
        }
        return result;
    }

    private static void requireNonEmptyBatch(List<?> values) throws IOException {
        if (values.isEmpty() || values.size() > MAX_BATCH_SIZE) {
            throw new IOException("Tracking wire batch size out of range: " + values.size());
        }
    }

    private static boolean allExactInstancesOf(List<?> values, Class<?> type) {
        return !values.isEmpty() && values.stream().allMatch(value -> value != null && value.getClass() == type);
    }

    private static <E> E enumValue(E[] values, int ordinal, String label) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("Invalid " + label + " ordinal " + ordinal);
        }
        return values[ordinal];
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
               | ((bytes[offset + 1] & 0xff) << 16)
               | ((bytes[offset + 2] & 0xff) << 8)
               | (bytes[offset + 3] & 0xff);
    }

    private record MessageDescriptor(int flags, String dataType, String dataFormat, String source, String target) {
        private static final int DATA_TYPE = 1;
        private static final int DATA_FORMAT = 1 << 1;
        private static final int SOURCE = 1 << 2;
        private static final int TARGET = 1 << 3;

        private static MessageDescriptor of(List<SerializedMessage> messages) {
            if (messages.isEmpty()) {
                return new MessageDescriptor(0, null, null, null, null);
            }
            SerializedMessage first = messages.getFirst();
            String dataType = first.getData().getType();
            String dataFormat = first.getData().getFormat();
            String source = first.getSource();
            String target = first.getTarget();
            int flags = DATA_TYPE | DATA_FORMAT | SOURCE | TARGET;
            for (int i = 1; i < messages.size() && flags != 0; i++) {
                SerializedMessage message = messages.get(i);
                if (!Objects.equals(dataType, message.getData().getType())) {
                    flags &= ~DATA_TYPE;
                }
                if (!Objects.equals(dataFormat, message.getData().getFormat())) {
                    flags &= ~DATA_FORMAT;
                }
                if (!Objects.equals(source, message.getSource())) {
                    flags &= ~SOURCE;
                }
                if (!Objects.equals(target, message.getTarget())) {
                    flags &= ~TARGET;
                }
            }
            return new MessageDescriptor(flags, dataType, dataFormat, source, target);
        }

        private static MessageDescriptor read(Reader input) throws IOException {
            int flags = input.readUnsignedByte();
            return new MessageDescriptor(flags,
                                         (flags & DATA_TYPE) != 0 ? input.readString() : null,
                                         (flags & DATA_FORMAT) != 0 ? input.readString() : null,
                                         (flags & SOURCE) != 0 ? input.readString() : null,
                                         (flags & TARGET) != 0 ? input.readString() : null);
        }

        private void write(Writer output) {
            output.writeByte(flags);
            if (sharedDataType()) {
                output.writeString(dataType);
            }
            if (sharedDataFormat()) {
                output.writeString(dataFormat);
            }
            if (sharedSource()) {
                output.writeString(source);
            }
            if (sharedTarget()) {
                output.writeString(target);
            }
        }

        private boolean sharedDataType() {
            return (flags & DATA_TYPE) != 0;
        }

        private boolean sharedDataFormat() {
            return (flags & DATA_FORMAT) != 0;
        }

        private boolean sharedSource() {
            return (flags & SOURCE) != 0;
        }

        private boolean sharedTarget() {
            return (flags & TARGET) != 0;
        }
    }

    private static final class Writer {
        private byte[] bytes;
        private int position;

        private Writer(int initialSize) {
            bytes = new byte[initialSize];
        }

        private void writeByte(int value) {
            ensure(1);
            bytes[position++] = (byte) value;
        }

        private void writeBoolean(boolean value) {
            writeByte(value ? 1 : 0);
        }

        private void writeInt(int value) {
            ensure(Integer.BYTES);
            bytes[position++] = (byte) (value >>> 24);
            bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8);
            bytes[position++] = (byte) value;
        }

        private void writeLong(long value) {
            ensure(Long.BYTES);
            for (int shift = 56; shift >= 0; shift -= 8) {
                bytes[position++] = (byte) (value >>> shift);
            }
        }

        private void writeNullableInt(Integer value) {
            writeBoolean(value != null);
            if (value != null) {
                writeInt(value);
            }
        }

        private void writeNullableLong(Long value) {
            writeBoolean(value != null);
            if (value != null) {
                writeLong(value);
            }
        }

        private void writeString(String value) {
            if (value == null) {
                writeInt(-1);
                return;
            }
            int length = value.length();
            for (int index = 0; index < length; index++) {
                if (value.charAt(index) > 0x7f) {
                    writeBytes(value.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            writeInt(length);
            ensure(length);
            for (int index = 0; index < value.length(); index++) {
                bytes[position++] = (byte) value.charAt(index);
            }
        }

        private void writeBytes(byte[] value) {
            if (value == null) {
                writeInt(-1);
                return;
            }
            writeInt(value.length);
            ensure(value.length);
            System.arraycopy(value, 0, bytes, position, value.length);
            position += value.length;
        }

        private void writeRaw(byte[] value) {
            ensure(value.length);
            System.arraycopy(value, 0, bytes, position, value.length);
            position += value.length;
        }

        private void ensure(int additional) {
            int required = Math.addExact(position, additional);
            if (required > bytes.length) {
                int grown = Math.max(required, Math.min(MAX_VALUE_BYTES, bytes.length + (bytes.length >>> 1) + 1));
                if (grown < required || grown > MAX_VALUE_BYTES) {
                    throw new IllegalArgumentException("Tracking wire value exceeds maximum size");
                }
                bytes = java.util.Arrays.copyOf(bytes, grown);
            }
        }

        private byte[] toByteArray() {
            return position == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, position);
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        private Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        private int available() {
            return bytes.length - position;
        }

        private int readUnsignedByte() throws EOFException {
            require(1);
            return bytes[position++] & 0xff;
        }

        private boolean readBoolean() throws IOException {
            int value = readUnsignedByte();
            if (value > 1) {
                throw new IOException("Invalid tracking wire boolean " + value);
            }
            return value == 1;
        }

        private int readInt() throws EOFException {
            require(Integer.BYTES);
            int value = TrackingWireCodec.readInt(bytes, position);
            position += Integer.BYTES;
            return value;
        }

        private long readLong() throws EOFException {
            require(Long.BYTES);
            long value = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (bytes[position++] & 0xffL);
            }
            return value;
        }

        private Integer readNullableInt() throws IOException {
            return readBoolean() ? readInt() : null;
        }

        private Long readNullableLong() throws IOException {
            return readBoolean() ? readLong() : null;
        }

        private String readString() throws IOException {
            int length = readInt();
            if (length < 0) {
                return null;
            }
            if (length > MAX_VALUE_BYTES) {
                throw new IOException("Tracking wire value exceeds maximum size");
            }
            require(length);
            String value = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        private Metadata readMetadata() throws IOException {
            int start = position;
            int size = readSize(MAX_COLLECTION_SIZE, "metadata");
            for (int i = 0; i < size; i++) {
                skipString();
                skipString();
            }
            byte[] data = java.util.Arrays.copyOfRange(bytes, start, position);
            return Metadata.fromData(new Data<>(data, Metadata.DATA_TYPE, 0, Metadata.DATA_FORMAT));
        }

        private void skipString() throws IOException {
            int length = readInt();
            if (length < 0 || length > MAX_VALUE_BYTES) {
                throw new IOException("Invalid tracking metadata string size " + length);
            }
            require(length);
            position += length;
        }

        private byte[] readBytes() throws IOException {
            int length = readInt();
            if (length < 0) {
                return null;
            }
            if (length > MAX_VALUE_BYTES) {
                throw new IOException("Tracking wire value exceeds maximum size");
            }
            require(length);
            byte[] value = java.util.Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return value;
        }

        private SerializedMessage readNativeMessage() throws IOException {
            int length = readSize(MAX_VALUE_BYTES, "native message");
            require(length);
            SerializedMessage result = SerializedMessage.decodeView(bytes, position, length);
            position += length;
            return result;
        }

        private int readSize(int maximum, String label) throws IOException {
            int size = readInt();
            if (size < 0 || size > maximum) {
                throw new IOException("Invalid " + label + " size " + size);
            }
            return size;
        }

        private void require(int length) throws EOFException {
            if (length < 0 || available() < length) {
                throw new EOFException();
            }
        }
    }
}
