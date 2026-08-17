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

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import lombok.SneakyThrows;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.buffer.ArrayBufferInput;

import java.util.ArrayList;
import java.util.List;

/** Decoder for the final persisted independent-model stream batch format. */
public final class ModelStreamBatchDecoder {

    private static final int VERSION = 6;

    private ModelStreamBatchDecoder() {
    }

    /** Decodes one stored stream batch. */
    public static List<Entry> decode(byte[] data) {
        return decode(new ModelEventDataBlock(data));
    }

    /** Decodes one stored stream batch directly from a byte range. */
    @SneakyThrows
    public static List<Entry> decode(ModelEventDataBlock block) {
        byte[] decoded = block.data();
        int offset = block.offset();
        int length = block.length();
        if (length >= 2 && decoded[offset] == (byte) 0xff && decoded[offset + 1] == 0) {
            decoded = CompressionAlgorithm.ZSTD.decompress(decoded, offset, length);
            offset = 0;
            length = decoded.length;
        }
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(
                new ArrayBufferInput(decoded, offset, length))) {
            int version = unpacker.unpackInt();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported model stream batch version " + version);
            }
            List<Entry> result = decodeEntries(unpacker);
            if (unpacker.hasNext()) {
                throw new IllegalStateException("Unexpected trailing model stream batch data");
            }
            return result;
        }
    }

    private static List<Entry> decodeEntries(MessageUnpacker unpacker) throws Exception {
        int count = unpacker.unpackArrayHeader();
        if (count <= 0) {
            throw new IllegalStateException("A model stream batch must contain an entry");
        }
        long stateIndex = unpacker.unpackLong();
        long eventIndex = unpacker.unpackLong();
        long readStateIndex = unpacker.unpackLong();
        boolean sharedModelType = unpacker.unpackBoolean();
        String commonModelType = sharedModelType ? unpackNullableString(unpacker) : null;
        List<Entry> result = new ArrayList<>(count);
        for (int remaining = count; remaining > 0; remaining--) {
            String modelId = unpacker.unpackString();
            stateIndex += unpacker.unpackLong();
            eventIndex += unpacker.unpackLong();
            readStateIndex += unpacker.unpackLong();
            String commitId = unpacker.unpackString();
            long sequenceNumber = unpacker.unpackLong();
            boolean historyComplete = unpacker.unpackBoolean();
            long payloadBytes = unpacker.unpackLong();
            if (payloadBytes < 0L) {
                throw new IllegalStateException("Model stream batch contains negative payload bytes");
            }
            result.add(new Entry(
                    modelId, sharedModelType ? commonModelType : unpackNullableString(unpacker),
                    stateIndex, readStateIndex, commitId, 0, eventIndex, sequenceNumber,
                    historyComplete, payloadBytes, unpackNullableString(unpacker)));
        }
        return List.copyOf(result);
    }

    private static String unpackNullableString(MessageUnpacker unpacker) throws Exception {
        return unpacker.tryUnpackNil() ? null : unpacker.unpackString();
    }

    /** One independently addressable stored model membership. */
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
}
