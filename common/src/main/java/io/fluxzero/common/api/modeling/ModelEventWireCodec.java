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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact websocket representation for model-stream reads.
 * <p>
 * Model-event responses contain many small memberships. Encoding those as generic CBOR objects costs substantially
 * more CPU and allocation than loading the underlying rows. This codec preserves the same protocol objects while
 * representing their fixed fields directly.
 */
public final class ModelEventWireCodec {

    private static final int REQUEST_MAGIC = 0x465A4551; // FZEQ
    private static final int RESULT_MAGIC = 0x465A4552; // FZER
    private static final int DIRECT_REQUEST_MAGIC = 0x465A4571; // FZEq
    private static final int DIRECT_RESULT_MAGIC = 0x465A4572; // FZEr
    private static final int VERSION = 4;
    private static final int MINIMUM_SUPPORTED_VERSION = 2;
    private static final int MAX_BATCH_SIZE = 1_000_000;
    private static final int MAX_COLLECTION_SIZE = 2_000_000;
    private static final int MAX_VALUE_BYTES = 512 * 1024 * 1024;

    private ModelEventWireCodec() {
    }

    /**
     * Encodes a homogeneous compact model-event request or result batch.
     */
    public static byte[] tryEncode(JsonType value) throws IOException {
        if (value instanceof GetModelEvents request
            && request.isCompactPayloads()) {
            return encodeRequests(
                    new RequestBatch<>(List.of(request)),
                    DIRECT_REQUEST_MAGIC);
        }
        if (value instanceof GetModelEventsResult result
            && supportsResult(result)) {
            return encodeResults(
                    new ResultBatch(List.of(result)),
                    DIRECT_RESULT_MAGIC);
        }
        if (value instanceof RequestBatch<?> batch && supportsRequests(batch)) {
            return encodeRequests(batch, REQUEST_MAGIC);
        }
        if (value instanceof ResultBatch batch && supportsResults(batch)) {
            return encodeResults(batch, RESULT_MAGIC);
        }
        return null;
    }

    /**
     * Decodes this codec's representation, or returns {@code null} for another transport format.
     */
    public static JsonType tryDecode(byte[] bytes) throws IOException {
        if (bytes.length < Integer.BYTES + 1) {
            return null;
        }
        int magic = readInt(bytes, 0);
        if (magic != REQUEST_MAGIC && magic != RESULT_MAGIC
            && magic != DIRECT_REQUEST_MAGIC
            && magic != DIRECT_RESULT_MAGIC) {
            return null;
        }
        try {
            Reader input = new Reader(bytes);
            input.readInt();
            int version = input.readUnsignedByte();
            if (version < MINIMUM_SUPPORTED_VERSION
                || version > VERSION) {
                throw new IOException("Unsupported compact model-event wire version " + version);
            }
            JsonType result;
            if (magic == REQUEST_MAGIC || magic == DIRECT_REQUEST_MAGIC) {
                RequestBatch<GetModelEvents> decoded = decodeRequests(input);
                result = magic == DIRECT_REQUEST_MAGIC
                        ? decoded.getRequests().getFirst()
                        : decoded;
            } else {
                ResultBatch decoded = decodeResults(input, version);
                result = magic == DIRECT_RESULT_MAGIC
                        ? (JsonType) decoded.getResults().getFirst()
                        : decoded;
            }
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing compact model-event bytes");
            }
            return result;
        } catch (EOFException e) {
            throw new IOException("Truncated compact model-event batch", e);
        }
    }

    private static boolean supportsRequests(RequestBatch<?> batch) {
        if (batch.getRequests().isEmpty()) {
            return false;
        }
        for (JsonType value : batch.getRequests()) {
            if (!(value instanceof GetModelEvents request)
                || !request.isCompactPayloads()) {
                return false;
            }
        }
        return true;
    }

    private static boolean supportsResults(ResultBatch batch) {
        if (batch.getResults().isEmpty()) {
            return false;
        }
        for (RequestResult value : batch.getResults()) {
            if (!(value instanceof GetModelEventsResult result)
                || !supportsResult(result)) {
                return false;
            }
        }
        return true;
    }

    private static boolean supportsResult(GetModelEventsResult result) {
        return result.getCompactPayloadStateIndices() != null
               && (result.getCompactPayloads() != null
                   || result.getCompactPayloadBlocks() != null);
    }

    private static byte[] encodeRequests(
            RequestBatch<?> batch, int magic) {
        Writer output = new Writer(Math.max(256, batch.getRequests().size() * 128));
        output.writeInt(magic);
        output.writeByte(VERSION);
        output.writeInt(batch.getRequests().size());
        for (JsonType value : batch.getRequests()) {
            GetModelEvents request = (GetModelEvents) value;
            output.writeLong(request.getRequestId());
            output.writeInt(request.getRequests().size());
            for (ModelEventStreamRequest stream : request.getRequests()) {
                output.writeString(stream.getModelId());
                output.writeLong(stream.getLastSequenceNumber());
                output.writeInt(stream.getMaxSize());
            }
            output.writeNullableLong(request.getMaxStateIndex());
            output.writeString(request.getBoundaryCommitId());
            output.writeNullableInt(request.getBoundarySubstep());
            output.writeNullableLong(request.getBoundaryEventIndex());
            output.writeLong(request.getMaxBytes());
            output.writeBoolean(request.isCompactPayloads());
        }
        return output.toByteArray();
    }

    private static RequestBatch<GetModelEvents> decodeRequests(
            Reader input) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "batch");
        List<GetModelEvents> requests = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            int streamCount = input.readSize(MAX_COLLECTION_SIZE, "stream collection");
            List<ModelEventStreamRequest> streams = new ArrayList<>(streamCount);
            for (int stream = 0; stream < streamCount; stream++) {
                streams.add(
                        new ModelEventStreamRequest(
                                input.readString(),
                                input.readLong(),
                                input.readInt()));
            }
            requests.add(
                    new GetModelEvents(
                            requestId,
                            streams,
                            input.readNullableLong(),
                            input.readString(),
                            input.readNullableInt(),
                            input.readNullableLong(),
                            input.readLong(),
                            input.readBoolean()));
        }
        return new RequestBatch<>(requests);
    }

    private static byte[] encodeResults(
            ResultBatch batch, int magic) {
        long started = System.nanoTime();
        Writer output = new Writer(encodedResultSize(batch));
        output.writeInt(magic);
        output.writeByte(VERSION);
        output.writeInt(batch.getResults().size());
        for (RequestResult value : batch.getResults()) {
            GetModelEventsResult result = (GetModelEventsResult) value;
            output.writeLong(result.getRequestId());
            output.writeLong(result.getStateIndex());
            output.writeInt(result.getPayloads().size());
            for (ModelEventPayload payload : result.getPayloads()) {
                output.writeLong(payload.getStateIndex());
                output.writeMessage(payload.getEvent());
            }
            output.writeInt(result.getStreams().size());
            String sharedModelType =
                    sharedModelType(result.getStreams());
            output.writeBoolean(sharedModelType != null);
            if (sharedModelType != null) {
                output.writeString(sharedModelType);
            }
            String modelIdPrefix =
                    commonModelIdPrefix(result.getStreams());
            output.writeString(modelIdPrefix);
            Long sharedSequenceNumber =
                    sharedSequenceNumber(result.getStreams());
            output.writeBoolean(sharedSequenceNumber != null);
            if (sharedSequenceNumber != null) {
                output.writeLong(sharedSequenceNumber);
            }
            for (ModelEventStream stream : result.getStreams()) {
                output.writeString(
                        stream.getModelId()
                                .substring(modelIdPrefix.length()));
                ModelHeadState head = stream.getHead();
                output.writeBoolean(head != null);
                if (head != null) {
                    if (sharedModelType == null) {
                        output.writeString(head.getModelType());
                    }
                    if (sharedSequenceNumber == null) {
                        output.writeLong(head.getSequenceNumber());
                    }
                    output.writeLong(head.getStateIndex());
                    output.writeBoolean(head.isHistoryComplete());
                    output.writeBoolean(head.isDeleted());
                }
                output.writeInt(stream.getMemberships().size());
                for (ModelEventMembership membership : stream.getMemberships()) {
                    output.writeLong(membership.getSequenceNumber());
                    output.writeLong(membership.getStateIndex());
                    output.writeLong(membership.getReadStateIndex());
                    output.writeString(membership.getCommitId());
                    output.writeInt(membership.getSubstep());
                }
            }
            output.writeBytes(result.getCompactPayloads());
            output.writeLongs(result.getCompactPayloadStateIndices());
            List<ModelEventPayloadBlock> blocks = result.getCompactPayloadBlocks();
            output.writeInt(blocks == null ? -1 : blocks.size());
            if (blocks != null) {
                for (ModelEventPayloadBlock block : blocks) {
                    output.writeLong(block.getFirstIndex());
                    output.writeInt(block.getMessageCount());
                    output.writeBoolean(block.isCompressed());
                    output.writeBytes(block.getData());
                }
            }
            output.writeLongs(result.getCompactPayloadEventIndices());
            List<ModelEventDataBlock> membershipBlocks =
                    result.getCompactMembershipBlocks();
            output.writeInt(membershipBlocks == null ? -1 : membershipBlocks.size());
            if (membershipBlocks != null) {
                membershipBlocks.forEach(
                        block ->
                                output.writeBytes(
                                        block.data(),
                                        block.offset(),
                                        block.length()));
            }
            output.writeLong(result.getRequestReceivedTimestamp());
            output.writeLong(result.getResponseQueuedTimestamp());
            output.writeLong(result.getResponseSendStartTimestamp());
        }
        byte[] result = output.toByteArray();
        GetModelEventsResult first =
                (GetModelEventsResult) batch.getResults().getFirst();
        if (Boolean.getBoolean("fluxzero.modelEventWireDiagnostics")
            && first.getStreams().size() >= 1_000) {
            System.out.printf(
                    "Compact model result encode: %,d streams, %,d memberships, %,d bytes in %.3f ms%n",
                    first.getStreams().size(),
                    first.getStreams().stream()
                            .mapToLong(stream -> stream.getMemberships().size())
                            .sum(),
                    result.length,
                    (System.nanoTime() - started) / 1_000_000.0);
        }
        return result;
    }

    private static ResultBatch decodeResults(
            Reader input, int version) throws IOException {
        long started = System.nanoTime();
        int size = input.readSize(MAX_BATCH_SIZE, "batch");
        List<RequestResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            long stateIndex = input.readLong();
            int payloadCount = input.readSize(MAX_COLLECTION_SIZE, "payload collection");
            List<ModelEventPayload> payloads = new ArrayList<>(payloadCount);
            for (int payload = 0; payload < payloadCount; payload++) {
                payloads.add(
                        new ModelEventPayload(
                                input.readLong(), input.readMessage()));
            }
            int streamCount = input.readSize(MAX_COLLECTION_SIZE, "stream collection");
            String sharedModelType =
                    version >= 3 && input.readBoolean()
                            ? input.readString()
                            : null;
            String modelIdPrefix =
                    version >= 4 ? input.readString() : "";
            Long sharedSequenceNumber =
                    version >= 4 && input.readBoolean()
                            ? input.readLong()
                            : null;
            List<ModelEventStream> streams = new ArrayList<>(streamCount);
            for (int streamIndex = 0; streamIndex < streamCount; streamIndex++) {
                String modelId =
                        modelIdPrefix + input.readString();
                ModelHeadState head = input.readBoolean()
                        ? new ModelHeadState(
                                modelId,
                                sharedModelType == null
                                        ? input.readString()
                                        : sharedModelType,
                                sharedSequenceNumber == null
                                        ? input.readLong()
                                        : sharedSequenceNumber,
                                input.readLong(),
                                input.readBoolean(),
                                input.readBoolean())
                        : null;
                int membershipCount =
                        input.readSize(MAX_COLLECTION_SIZE, "membership collection");
                List<ModelEventMembership> memberships =
                        new ArrayList<>(membershipCount);
                for (int membership = 0; membership < membershipCount; membership++) {
                    memberships.add(
                            new ModelEventMembership(
                                    input.readLong(),
                                    input.readLong(),
                                    input.readLong(),
                                    input.readString(),
                                    input.readInt()));
                }
                streams.add(new ModelEventStream(modelId, head, memberships));
            }
            GetModelEventsResult result =
                    new GetModelEventsResult(
                            requestId,
                            stateIndex,
                            payloads,
                            streams,
                            input.readBytes(),
                            input.readLongs(),
                            input.readPayloadBlocks(),
                            input.readLongs(),
                            input.readByteBlocks());
            result.setRequestReceivedTimestamp(input.readLong());
            result.setResponseQueuedTimestamp(input.readLong());
            result.setResponseSendStartTimestamp(input.readLong());
            results.add(result);
        }
        ResultBatch result = new ResultBatch(results);
        GetModelEventsResult first =
                (GetModelEventsResult) results.getFirst();
        if (Boolean.getBoolean("fluxzero.modelEventWireDiagnostics")
            && first.getStreams().size() >= 1_000) {
            System.out.printf(
                    "Compact model result decode: %,d streams, %,d memberships in %.3f ms%n",
                    first.getStreams().size(),
                    first.getStreams().stream()
                            .mapToLong(stream -> stream.getMemberships().size())
                            .sum(),
                    (System.nanoTime() - started) / 1_000_000.0);
        }
        return result;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
               | (bytes[offset + 1] & 0xff) << 16
               | (bytes[offset + 2] & 0xff) << 8
               | bytes[offset + 3] & 0xff;
    }

    private static int capacity(int size) {
        return size < 3 ? size + 1 : (int) Math.ceil(size / 0.75d);
    }

    private static int encodedResultSize(ResultBatch batch) {
        long size = Integer.BYTES + 1L + Integer.BYTES;
        for (RequestResult value : batch.getResults()) {
            GetModelEventsResult result = (GetModelEventsResult) value;
            size += Long.BYTES * 2L + Integer.BYTES;
            for (ModelEventPayload payload : result.getPayloads()) {
                size += Long.BYTES + encodedMessageSize(payload.getEvent());
            }
            size += Integer.BYTES + 1L;
            String sharedModelType =
                    sharedModelType(result.getStreams());
            if (sharedModelType != null) {
                size += encodedStringSize(sharedModelType);
            }
            String modelIdPrefix =
                    commonModelIdPrefix(result.getStreams());
            size += encodedStringSize(modelIdPrefix);
            Long sharedSequenceNumber =
                    sharedSequenceNumber(result.getStreams());
            size += 1L
                    + (sharedSequenceNumber == null ? 0L : Long.BYTES);
            for (ModelEventStream stream : result.getStreams()) {
                size += encodedStringSize(
                        stream.getModelId()
                                .substring(modelIdPrefix.length())) + 1L;
                ModelHeadState head = stream.getHead();
                if (head != null) {
                    size += (sharedModelType == null
                            ? encodedStringSize(head.getModelType())
                            : 0)
                            + (sharedSequenceNumber == null
                                    ? Long.BYTES
                                    : 0L)
                            + Long.BYTES + 2L;
                }
                size += Integer.BYTES;
                for (ModelEventMembership membership : stream.getMemberships()) {
                    size += Long.BYTES * 3L
                            + encodedStringSize(membership.getCommitId())
                            + Integer.BYTES;
                }
            }
            size += encodedBytesSize(result.getCompactPayloads());
            size += encodedLongsSize(result.getCompactPayloadStateIndices());
            List<ModelEventPayloadBlock> payloadBlocks =
                    result.getCompactPayloadBlocks();
            size += Integer.BYTES;
            if (payloadBlocks != null) {
                for (ModelEventPayloadBlock block : payloadBlocks) {
                    size += Long.BYTES + Integer.BYTES + 1L
                            + encodedBytesSize(block.getData());
                }
            }
            size += encodedLongsSize(result.getCompactPayloadEventIndices());
            List<ModelEventDataBlock> membershipBlocks =
                    result.getCompactMembershipBlocks();
            size += Integer.BYTES;
            if (membershipBlocks != null) {
                for (ModelEventDataBlock block : membershipBlocks) {
                    size += Integer.BYTES + block.length();
                }
            }
            size += Long.BYTES * 3L;
        }
        return Math.toIntExact(size);
    }

    private static String sharedModelType(
            List<ModelEventStream> streams) {
        String shared = null;
        boolean found = false;
        for (ModelEventStream stream : streams) {
            ModelHeadState head = stream.getHead();
            if (head == null || head.getModelType() == null) {
                continue;
            }
            if (!found) {
                shared = head.getModelType();
                found = true;
            } else if (!shared.equals(head.getModelType())) {
                return null;
            }
        }
        return found ? shared : null;
    }

    private static String commonModelIdPrefix(
            List<ModelEventStream> streams) {
        if (streams.isEmpty()) {
            return "";
        }
        String first = streams.getFirst().getModelId();
        int length = first.length();
        for (int index = 1;
             index < streams.size() && length > 0;
             index++) {
            String candidate =
                    streams.get(index).getModelId();
            length = Math.min(length, candidate.length());
            int position = 0;
            while (position < length
                   && first.charAt(position)
                      == candidate.charAt(position)) {
                position++;
            }
            length = position;
        }
        return first.substring(0, length);
    }

    private static Long sharedSequenceNumber(
            List<ModelEventStream> streams) {
        Long shared = null;
        for (ModelEventStream stream : streams) {
            ModelHeadState head = stream.getHead();
            if (head == null) {
                continue;
            }
            if (shared == null) {
                shared = head.getSequenceNumber();
            } else if (shared != head.getSequenceNumber()) {
                return null;
            }
        }
        return shared;
    }

    private static int encodedMessageSize(SerializedMessage message) {
        Data<byte[]> data = message.getData();
        long size = encodedBytesSize(data.getValue())
                + encodedStringSize(data.getType())
                + Integer.BYTES
                + encodedStringSize(data.getFormat());
        Map<String, String> metadata =
                message.getMetadata() == null
                        ? Map.of() : message.getMetadata().getEntries();
        size += Integer.BYTES;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            size += encodedStringSize(entry.getKey())
                    + encodedStringSize(entry.getValue());
        }
        size += encodedNullableIntSize(message.getSegment())
                + encodedNullableLongSize(message.getIndex())
                + encodedStringSize(message.getSource())
                + encodedStringSize(message.getTarget())
                + encodedNullableIntSize(message.getRequestId())
                + encodedNullableLongSize(message.getTimestamp())
                + encodedStringSize(message.getMessageId());
        return Math.toIntExact(size);
    }

    private static int encodedStringSize(String value) {
        return encodedBytesSize(
                value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private static int encodedBytesSize(byte[] value) {
        return Integer.BYTES + (value == null ? 0 : value.length);
    }

    private static int encodedLongsSize(long[] values) {
        return Math.toIntExact(
                Integer.BYTES
                + (values == null ? 0L : (long) values.length * Long.BYTES));
    }

    private static int encodedNullableIntSize(Integer value) {
        return 1 + (value == null ? 0 : Integer.BYTES);
    }

    private static int encodedNullableLongSize(Long value) {
        return 1 + (value == null ? 0 : Long.BYTES);
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
            bytes[position++] = (byte) (value >>> 56);
            bytes[position++] = (byte) (value >>> 48);
            bytes[position++] = (byte) (value >>> 40);
            bytes[position++] = (byte) (value >>> 32);
            bytes[position++] = (byte) (value >>> 24);
            bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8);
            bytes[position++] = (byte) value;
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
            writeBytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
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

        private void writeBytes(
                byte[] value, int offset, int length) {
            writeInt(length);
            ensure(length);
            System.arraycopy(
                    value, offset, bytes, position, length);
            position += length;
        }

        private void writeLongs(long[] values) {
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

        private void writeMessage(SerializedMessage message) {
            Data<byte[]> data = message.getData();
            writeBytes(data.getValue());
            writeString(data.getType());
            writeInt(data.getRevision());
            writeString(data.getFormat());
            Map<String, String> metadata =
                    message.getMetadata() == null
                            ? Map.of()
                            : message.getMetadata().getEntries();
            writeInt(metadata.size());
            metadata.forEach((key, value) -> {
                writeString(key);
                writeString(value);
            });
            writeNullableInt(message.getSegment());
            writeNullableLong(message.getIndex());
            writeString(message.getSource());
            writeString(message.getTarget());
            writeNullableInt(message.getRequestId());
            writeNullableLong(message.getTimestamp());
            writeString(message.getMessageId());
        }

        private void ensure(int additional) {
            int required = Math.addExact(position, additional);
            if (required > bytes.length) {
                int next = Math.max(required, Math.multiplyExact(bytes.length, 2));
                bytes = java.util.Arrays.copyOf(bytes, next);
            }
        }

        private byte[] toByteArray() {
            return position == bytes.length
                    ? bytes : java.util.Arrays.copyOf(bytes, position);
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
                throw new IOException("Invalid compact model-event boolean " + value);
            }
            return value == 1;
        }

        private int readInt() throws EOFException {
            require(Integer.BYTES);
            int result = ModelEventWireCodec.readInt(bytes, position);
            position += Integer.BYTES;
            return result;
        }

        private long readLong() throws EOFException {
            require(Long.BYTES);
            long result =
                    (long) (bytes[position] & 0xff) << 56
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

        private Integer readNullableInt() throws IOException {
            return readBoolean() ? readInt() : null;
        }

        private Long readNullableLong() throws IOException {
            return readBoolean() ? readLong() : null;
        }

        private String readString() throws IOException {
            byte[] value = readBytes();
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        }

        private byte[] readBytes() throws IOException {
            int size = readInt();
            if (size == -1) {
                return null;
            }
            if (size < 0 || size > MAX_VALUE_BYTES) {
                throw new IOException("Invalid compact model-event byte value size " + size);
            }
            require(size);
            byte[] result = java.util.Arrays.copyOfRange(bytes, position, position + size);
            position += size;
            return result;
        }

        private long[] readLongs() throws IOException {
            int size = readInt();
            if (size == -1) {
                return null;
            }
            if (size < 0 || size > MAX_COLLECTION_SIZE) {
                throw new IOException("Invalid compact model-event long collection size " + size);
            }
            require(Math.multiplyExact(size, Long.BYTES));
            long[] result = new long[size];
            for (int i = 0; i < size; i++) {
                result[i] = readLong();
            }
            return result;
        }

        private List<ModelEventPayloadBlock> readPayloadBlocks() throws IOException {
            int size = readInt();
            if (size == -1) {
                return null;
            }
            if (size < 0 || size > MAX_COLLECTION_SIZE) {
                throw new IOException(
                        "Invalid compact model-event payload block count " + size);
            }
            List<ModelEventPayloadBlock> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                long firstIndex = readLong();
                int messageCount = readInt();
                boolean compressed = readBoolean();
                byte[] data = readBytes();
                try {
                    result.add(
                            new ModelEventPayloadBlock(
                                    firstIndex, messageCount, compressed, data));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid compact model-event payload block", e);
                }
            }
            return result;
        }

        private List<ModelEventDataBlock> readByteBlocks() throws IOException {
            int size = readInt();
            if (size == -1) {
                return null;
            }
            if (size < 0 || size > MAX_COLLECTION_SIZE) {
                throw new IOException(
                        "Invalid compact model-event membership block count " + size);
            }
            List<ModelEventDataBlock> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int length = readInt();
                if (length <= 0 || length > MAX_VALUE_BYTES) {
                    throw new IOException(
                            "Compact model-event membership block must not be empty");
                }
                require(length);
                result.add(
                        new ModelEventDataBlock(
                                bytes,
                                position,
                                length));
                position += length;
            }
            return result;
        }

        private int readSize(int maximum, String description) throws IOException {
            int size = readInt();
            if (size < 0 || size > maximum) {
                throw new IOException(
                        "Invalid compact model-event " + description + " size " + size);
            }
            return size;
        }

        private SerializedMessage readMessage() throws IOException {
            Data<byte[]> data =
                    new Data<>(
                            readBytes(),
                            readString(),
                            readInt(),
                            readString());
            int metadataSize = readSize(MAX_COLLECTION_SIZE, "metadata");
            Map<String, String> metadata =
                    new LinkedHashMap<>(capacity(metadataSize));
            for (int i = 0; i < metadataSize; i++) {
                metadata.put(readString(), readString());
            }
            return new SerializedMessage(
                    data,
                    Metadata.of(metadata),
                    readNullableInt(),
                    readNullableLong(),
                    readString(),
                    readString(),
                    readNullableInt(),
                    readNullableLong(),
                    readString(),
                    null);
        }

        private void require(int count) throws EOFException {
            if (count < 0 || count > available()) {
                throw new EOFException();
            }
        }
    }
}
