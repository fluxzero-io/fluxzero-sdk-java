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

import io.fluxzero.common.api.modeling.ModelEventDataBlock;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import lombok.SneakyThrows;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.buffer.ArrayBufferInput;
import org.msgpack.core.buffer.MessageBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for persisted independent-model stream batches.
 *
 * <p>The runtime can transport these immutable blocks directly to avoid expanding every membership into a wire object.
 * Encoding remains runtime-owned; this class is the compatibility reader for all persisted versions.</p>
 */
public final class ModelStreamBatchDecoder {

    private static final int CURRENT_VERSION = 5;

    private ModelStreamBatchDecoder() {
    }

    /**
     * Decodes one stored stream batch.
     */
    @SneakyThrows
    public static List<Entry> decode(byte[] data) {
        return decodeBlock(data).entries();
    }

    /**
     * Decodes one stored stream batch directly from a byte range.
     */
    public static List<Entry> decode(
            ModelEventDataBlock block) {
        return decodeBlock(block).entries();
    }

    /**
     * Decodes one stored stream batch together with optional co-located serialized event payloads.
     */
    @SneakyThrows
    public static DecodedBlock decodeBlock(byte[] data) {
        return decodeBlock(
                new ModelEventDataBlock(data));
    }

    /**
     * Decodes one stored stream batch directly from a byte range.
     */
    @SneakyThrows
    public static DecodedBlock decodeBlock(
            ModelEventDataBlock block) {
        byte[] decoded = block.data();
        int offset = block.offset();
        int length = block.length();
        if (length >= 2
            && decoded[offset] == (byte) 0xff
            && decoded[offset + 1] == 0) {
            decoded =
                    CompressionAlgorithm.ZSTD.decompress(
                            decoded, offset, length);
            offset = 0;
            length = decoded.length;
        }
        ArrayBufferInput input =
                new ArrayBufferInput(decoded, offset, length);
        try (MessageUnpacker unpacker =
                     MessagePack.newDefaultUnpacker(input)) {
            int version = unpacker.unpackInt();
            DecodedBlock result = switch (version) {
                case 1 -> new DecodedBlock(decodeV1(unpacker), null);
                case 2 -> new DecodedBlock(decodeV2(unpacker), null);
                case 3 -> new DecodedBlock(decodeV3(unpacker), null);
                case 4 -> new DecodedBlock(decodeV4(unpacker), null);
                case CURRENT_VERSION -> decodeV5(unpacker);
                default -> throw new IllegalStateException(
                        "Unsupported model stream batch version " + version);
            };
            if (unpacker.hasNext()) {
                throw new IllegalStateException(
                        "Unexpected trailing model stream batch data");
            }
            return result;
        }
    }

    private static DecodedBlock decodeV5(MessageUnpacker unpacker) throws Exception {
        List<Entry> entries = decodeV4(unpacker);
        EmbeddedPayloads embeddedPayloads = null;
        if (unpacker.unpackBoolean()) {
            int length = unpacker.unpackBinaryHeader();
            MessageBuffer payload =
                    unpacker.readPayloadAsReference(length);
            if (!payload.hasArray()) {
                throw new IllegalStateException(
                        "Embedded model payload buffer is not array-backed");
            }
            embeddedPayloads =
                    new EmbeddedPayloads(
                            payload.array(),
                            payload.arrayOffset(),
                            length);
        }
        return new DecodedBlock(entries, embeddedPayloads);
    }

    private static List<Entry> decodeV4(MessageUnpacker unpacker) throws Exception {
        int count = positiveCount(unpacker);
        long stateIndex = unpacker.unpackLong();
        long eventIndex = unpacker.unpackLong();
        long readStateIndex = unpacker.unpackLong();
        boolean sharedModelType = unpacker.unpackBoolean();
        String commonModelType =
                sharedModelType ? unpackNullableString(unpacker) : null;
        List<Entry> result = new ArrayList<>(count);
        for (int remaining = count; remaining > 0; remaining--) {
            String modelId = unpacker.unpackString();
            stateIndex += unpacker.unpackLong();
            eventIndex += unpacker.unpackLong();
            readStateIndex += unpacker.unpackLong();
            String commitId = unpacker.unpackString();
            long sequenceNumber = unpacker.unpackLong();
            boolean historyComplete = unpacker.unpackBoolean();
            long payloadBytes = nonNegative(unpacker.unpackLong());
            String modelType = sharedModelType
                    ? commonModelType : unpackNullableString(unpacker);
            String documentCollection = unpackNullableString(unpacker);
            result.add(
                    new Entry(
                            modelId, modelType, stateIndex, readStateIndex,
                            commitId, 0, eventIndex, sequenceNumber,
                            historyComplete, payloadBytes, documentCollection));
        }
        return result;
    }

    private static List<Entry> decodeV3(MessageUnpacker unpacker) throws Exception {
        int count = positiveCount(unpacker);
        long stateIndex = unpacker.unpackLong();
        long eventIndex = unpacker.unpackLong();
        long readStateIndex = unpacker.unpackLong();
        boolean sharedModelType = unpacker.unpackBoolean();
        String commonModelType =
                sharedModelType ? unpackNullableString(unpacker) : null;
        List<Entry> result = new ArrayList<>(count);
        for (int remaining = count; remaining > 0; remaining--) {
            String modelId = unpacker.unpackString();
            stateIndex += unpacker.unpackLong();
            eventIndex += unpacker.unpackLong();
            readStateIndex += unpacker.unpackLong();
            String commitId = unpacker.unpackString();
            long sequenceNumber = unpacker.unpackLong();
            boolean historyComplete = unpacker.unpackBoolean();
            long payloadBytes = nonNegative(unpacker.unpackLong());
            result.add(
                    new Entry(
                            modelId,
                            sharedModelType
                                    ? commonModelType : unpackNullableString(unpacker),
                            stateIndex, readStateIndex, commitId, 0, eventIndex,
                            sequenceNumber, historyComplete, payloadBytes, null));
        }
        return result;
    }

    private static List<Entry> decodeV2(MessageUnpacker unpacker) throws Exception {
        int count = positiveCount(unpacker);
        long stateIndex = unpacker.unpackLong();
        long eventIndex = unpacker.unpackLong();
        long readStateIndex = unpacker.unpackLong();
        boolean sharedModelType = unpacker.unpackBoolean();
        String commonModelType =
                sharedModelType ? unpackNullableString(unpacker) : null;
        List<Entry> result = new ArrayList<>(count);
        for (int remaining = count; remaining > 0; remaining--) {
            String modelId = unpacker.unpackString();
            stateIndex += unpacker.unpackLong();
            eventIndex += unpacker.unpackLong();
            readStateIndex += unpacker.unpackLong();
            String commitId = unpacker.unpackString();
            long payloadBytes = nonNegative(unpacker.unpackLong());
            result.add(
                    new Entry(
                            modelId,
                            sharedModelType
                                    ? commonModelType : unpackNullableString(unpacker),
                            stateIndex, readStateIndex, commitId, 0, eventIndex,
                            0L, true, payloadBytes, null));
        }
        return result;
    }

    private static List<Entry> decodeV1(MessageUnpacker unpacker) throws Exception {
        int count = positiveCount(unpacker);
        long stateIndex = unpacker.unpackLong();
        long eventIndex = unpacker.unpackLong();
        List<Entry> result = new ArrayList<>(count);
        for (int remaining = count; remaining > 0; remaining--) {
            String modelId = unpacker.unpackString();
            stateIndex += unpacker.unpackLong();
            eventIndex += unpacker.unpackLong();
            long readStateIndex = unpacker.unpackLong();
            String commitId = unpacker.unpackString();
            int substep = unpacker.unpackInt();
            long sequenceNumber = unpacker.unpackLong();
            boolean historyComplete = unpacker.unpackBoolean();
            long payloadBytes = nonNegative(unpacker.unpackLong());
            result.add(
                    new Entry(
                            modelId, unpackNullableString(unpacker),
                            stateIndex, readStateIndex, commitId, substep,
                            eventIndex, sequenceNumber, historyComplete,
                            payloadBytes, null));
        }
        return result;
    }

    private static int positiveCount(MessageUnpacker unpacker) throws Exception {
        int count = unpacker.unpackArrayHeader();
        if (count <= 0) {
            throw new IllegalStateException(
                    "A model stream batch must contain an entry");
        }
        return count;
    }

    private static long nonNegative(long value) {
        if (value < 0L) {
            throw new IllegalStateException(
                    "Model stream batch contains negative payload bytes");
        }
        return value;
    }

    private static String unpackNullableString(MessageUnpacker unpacker)
            throws Exception {
        return unpacker.tryUnpackNil() ? null : unpacker.unpackString();
    }

    /**
     * One independently addressable stored model membership.
     */
    public record Entry(
            String modelId,
            String modelType,
            long stateIndex,
            long readStateIndex,
            String commitId,
            int substep,
            long eventIndex,
            long sequenceNumber,
            boolean historyComplete,
            long payloadBytes,
            String documentCollection) {
    }

    /**
     * Decoded memberships and their optional consecutive event-store payload representation.
     */
    public record DecodedBlock(
            List<Entry> entries,
            EmbeddedPayloads embeddedPayloads) {
    }

    /**
     * Zero-copy view of consecutive embedded event payloads in a decoded model-stream block.
     */
    public record EmbeddedPayloads(
            byte[] data,
            int offset,
            int length) {

        public EmbeddedPayloads {
            if (data == null
                || offset < 0
                || length < 0
                || offset > data.length - length) {
                throw new IllegalArgumentException(
                        "Invalid embedded model payload slice");
            }
        }
    }
}
