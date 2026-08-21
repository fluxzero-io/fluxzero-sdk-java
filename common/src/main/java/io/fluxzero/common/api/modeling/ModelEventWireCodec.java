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

import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.api.internal.BinaryWire.Reader;
import io.fluxzero.common.api.internal.BinaryWire.Writer;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    private static final int VERSION = 7;
    private static final int MAX_BATCH_SIZE = 1_000_000;
    private static final int MAX_COLLECTION_SIZE = 2_000_000;
    private static final int MAX_VALUE_BYTES = 512 * 1024 * 1024;

    private ModelEventWireCodec() {
    }

    /**
     * Encodes a homogeneous compact model-event request or result batch.
     */
    public static byte[] tryEncode(JsonType value) throws IOException {
        if (value instanceof GetModelEvents request) {
            return encodeRequests(
                    new RequestBatch<>(List.of(request)),
                    DIRECT_REQUEST_MAGIC);
        }
        if (value instanceof GetModelEventsResult result) {
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
        int magic = BinaryWire.peekInt(bytes, 0);
        if (magic != REQUEST_MAGIC && magic != RESULT_MAGIC
            && magic != DIRECT_REQUEST_MAGIC
            && magic != DIRECT_RESULT_MAGIC) {
            return null;
        }
        try {
            Reader input = new Reader(bytes, MAX_VALUE_BYTES);
            input.readInt();
            int version = input.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported compact model-event wire version " + version);
            }
            JsonType result;
            if (magic == REQUEST_MAGIC || magic == DIRECT_REQUEST_MAGIC) {
                RequestBatch<GetModelEvents> decoded = decodeRequests(input);
                result = magic == DIRECT_REQUEST_MAGIC
                        ? decoded.getRequests().getFirst()
                        : decoded;
            } else {
                ResultBatch decoded = decodeResults(input);
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
            if (!(value instanceof GetModelEvents)) {
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
            if (!(value instanceof GetModelEventsResult)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeRequests(
            RequestBatch<?> batch, int magic) {
        Writer output = new Writer(Math.max(256, batch.getRequests().size() * 128), MAX_VALUE_BYTES);
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
            ModelReadBoundary boundary = request.getBoundary();
            output.writeNullableLong(boundary.stateIndex());
            output.writeString(boundary.commitId());
            output.writeNullableInt(boundary.substep());
            output.writeNullableLong(boundary.eventIndex());
            output.writeLong(request.getMaxBytes());
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
                            new ModelReadBoundary(
                                    input.readNullableLong(),
                                    input.readString(),
                                    input.readNullableInt(),
                                    input.readNullableLong(),
                                    false, false),
                            input.readLong()));
        }
        return new RequestBatch<>(requests);
    }

    private static byte[] encodeResults(
            ResultBatch batch, int magic) {
        Writer output = new Writer(encodedResultSize(batch), MAX_VALUE_BYTES);
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
                output.writeEnvelope(payload.getEvent());
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
            output.writeLongs(result.getPayloadStateIndices());
            List<ModelEventPayloadBlock> blocks = result.getPayloadBlocks();
            output.writeInt(blocks.size());
            for (ModelEventPayloadBlock block : blocks) {
                output.writeLong(block.getFirstIndex());
                output.writeInt(block.getMessageCount());
                output.writeBoolean(block.isCompressed());
                output.writeBytes(block.getData());
            }
            output.writeLongs(result.getPayloadEventIndices());
            List<ModelEventDataBlock> membershipBlocks =
                    result.getMembershipBlocks();
            output.writeInt(membershipBlocks.size());
            membershipBlocks.forEach(
                    block ->
                            output.writeBytes(
                                    block.data(),
                                    block.offset(),
                                    block.length()));
            output.writeLong(result.getRequestReceivedTimestamp());
            output.writeLong(result.getResponseQueuedTimestamp());
            output.writeLong(result.getResponseSendStartTimestamp());
        }
        return output.toExactByteArray();
    }

    private static ResultBatch decodeResults(Reader input) throws IOException {
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
                                input.readLong(), input.readEnvelope()));
            }
            int streamCount = input.readSize(MAX_COLLECTION_SIZE, "stream collection");
            String sharedModelType = input.readBoolean() ? input.readString() : null;
            String modelIdPrefix = input.readString();
            Long sharedSequenceNumber = input.readBoolean() ? input.readLong() : null;
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
                            input.readLongs(MAX_COLLECTION_SIZE),
                            readPayloadBlocks(input),
                            input.readLongs(MAX_COLLECTION_SIZE),
                            readByteBlocks(input));
            result.setRequestReceivedTimestamp(input.readLong());
            result.setResponseQueuedTimestamp(input.readLong());
            result.setResponseSendStartTimestamp(input.readLong());
            results.add(result);
        }
        return new ResultBatch(results);
    }

    private static List<ModelEventPayloadBlock> readPayloadBlocks(Reader input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_COLLECTION_SIZE) {
            throw new IOException("Invalid compact model-event payload block count " + size);
        }
        List<ModelEventPayloadBlock> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            try {
                result.add(new ModelEventPayloadBlock(
                        input.readLong(), input.readInt(), input.readBoolean(), input.readBytes()));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid compact model-event payload block", e);
            }
        }
        return result;
    }

    private static List<ModelEventDataBlock> readByteBlocks(Reader input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_COLLECTION_SIZE) {
            throw new IOException("Invalid compact model-event membership block count " + size);
        }
        List<ModelEventDataBlock> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            int length = input.readInt();
            if (length <= 0 || length > MAX_VALUE_BYTES) {
                throw new IOException("Compact model-event membership block must not be empty");
            }
            input.require(length);
            result.add(new ModelEventDataBlock(input.bytes(), input.position(), length));
            input.skip(length);
        }
        return result;
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
                size += BinaryWire.stringSize(sharedModelType);
            }
            String modelIdPrefix =
                    commonModelIdPrefix(result.getStreams());
            size += BinaryWire.stringSize(modelIdPrefix);
            Long sharedSequenceNumber =
                    sharedSequenceNumber(result.getStreams());
            size += 1L
                    + (sharedSequenceNumber == null ? 0L : Long.BYTES);
            for (ModelEventStream stream : result.getStreams()) {
                size += BinaryWire.stringSize(
                        stream.getModelId()
                                .substring(modelIdPrefix.length())) + 1L;
                ModelHeadState head = stream.getHead();
                if (head != null) {
                    size += (sharedModelType == null
                            ? BinaryWire.stringSize(head.getModelType())
                            : 0)
                            + (sharedSequenceNumber == null
                                    ? Long.BYTES
                                    : 0L)
                            + Long.BYTES + 2L;
                }
                size += Integer.BYTES;
                for (ModelEventMembership membership : stream.getMemberships()) {
                    size += Long.BYTES * 3L
                            + BinaryWire.stringSize(membership.getCommitId())
                            + Integer.BYTES;
                }
            }
            size += BinaryWire.longsSize(result.getPayloadStateIndices());
            List<ModelEventPayloadBlock> payloadBlocks =
                    result.getPayloadBlocks();
            size += Integer.BYTES;
            for (ModelEventPayloadBlock block : payloadBlocks) {
                size += Long.BYTES + Integer.BYTES + 1L
                        + BinaryWire.bytesSize(block.getData());
            }
            size += BinaryWire.longsSize(result.getPayloadEventIndices());
            List<ModelEventDataBlock> membershipBlocks =
                    result.getMembershipBlocks();
            size += Integer.BYTES;
            for (ModelEventDataBlock block : membershipBlocks) {
                size += Integer.BYTES + block.length();
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
        return BinaryWire.nestedEnvelopeSize(message);
    }

}
