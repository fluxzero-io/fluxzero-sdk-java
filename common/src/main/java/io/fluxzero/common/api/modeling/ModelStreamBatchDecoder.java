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

import io.fluxzero.common.serialization.MessagePackIO;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for the v7 packed Model membership transport format.
 * <p>
 * The public metadata view remains v7-only, with implicit substep zero. The page decoder also accepts negotiated
 * logical membership v8, which carries exact substeps without stored-model metadata. Existing readers must receive
 * ordinary {@link ModelEventMembership} objects for non-zero substeps; the v7 layout is unchanged.
 */
public final class ModelStreamBatchDecoder {

    private static final int VERSION = 7;

    private ModelStreamBatchDecoder() {
    }

    /** Decodes one packed membership batch. */
    public static List<Entry> decode(byte[] data) {
        return decode(new ModelEventDataBlock(data));
    }

    /** Decodes one packed membership batch directly from a byte range. */
    public static List<Entry> decode(ModelEventDataBlock block) {
        return decode(block, unpacker -> {
            int version = unpacker.unpackInt();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported model stream batch version " + version);
            }
            return decodeEntries(unpacker);
        });
    }

    // v8 carries logical memberships only. Keep the public v7 metadata view unchanged.
    static List<? extends Membership> decodeMemberships(ModelEventDataBlock block) {
        return decode(block, unpacker -> {
            int version = unpacker.unpackInt();
            return switch (version) {
                case VERSION -> decodeEntries(unpacker);
                case 8 -> decodeLogicalMemberships(unpacker);
                default -> throw new IllegalStateException("Unsupported model stream batch version " + version);
            };
        });
    }

    @SneakyThrows
    private static <T> T decode(ModelEventDataBlock block, Decoder<T> decoder) {
        byte[] decoded = block.data();
        int offset = block.offset();
        int length = block.length();
        if (length >= 2 && decoded[offset] == (byte) 0xff && decoded[offset + 1] == 0) {
            decoded = CompressionAlgorithm.ZSTD.decompress(decoded, offset, length);
            offset = 0;
            length = decoded.length;
        }
        try (MessagePackIO.Reader unpacker = new MessagePackIO.Reader(decoded, offset, length)) {
            T result = decoder.decode(unpacker);
            if (unpacker.hasNext()) {
                throw new IllegalStateException("Unexpected trailing model stream batch data");
            }
            return result;
        }
    }

    private static List<Entry> decodeEntries(MessagePackIO.Reader unpacker) throws Exception {
        int count = unpacker.unpackArrayHeader();
        if (count <= 0) {
            throw new IllegalStateException("A model stream batch must contain an entry");
        }
        long stateIndex = unpacker.unpackLong();
        long eventIndex = unpacker.unpackLong();
        long readStateIndex = unpacker.unpackLong();
        boolean sharedModelType = unpacker.unpackBoolean();
        String commonModelType = sharedModelType ? unpacker.unpackString() : null;
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
                    modelId, sharedModelType ? commonModelType : unpacker.unpackString(),
                    stateIndex, readStateIndex, commitId, 0, eventIndex, sequenceNumber,
                    historyComplete, payloadBytes, unpackNullableString(unpacker)));
        }
        return List.copyOf(result);
    }

    private static List<LogicalMembership> decodeLogicalMemberships(MessagePackIO.Reader unpacker) throws Exception {
        int count = unpacker.unpackArrayHeader();
        if (count <= 0 || count > 1024) {
            throw new IllegalStateException("A v8 membership block must contain between 1 and 1024 entries");
        }
        long stateIndex = 0, readStateIndex = 0;
        List<LogicalMembership> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String modelId = unpacker.unpackString();
            long sequenceNumber = unpacker.unpackLong();
            stateIndex = Math.addExact(stateIndex, unpacker.unpackLong());
            readStateIndex = Math.addExact(readStateIndex, unpacker.unpackLong());
            String commitId = unpackNullableString(unpacker);
            int substep = unpacker.unpackInt();
            if (substep < 0) {
                throw new IllegalStateException("Model membership contains a negative substep");
            }
            result.add(new LogicalMembership(modelId, sequenceNumber, stateIndex, readStateIndex, commitId, substep));
        }
        return List.copyOf(result);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(MessagePackIO.Reader unpacker) throws Exception;
    }

    interface Membership {
        String modelId();
        long sequenceNumber();
        long stateIndex();
        long readStateIndex();
        String commitId();
        int substep();
    }

    private record LogicalMembership(String modelId, long sequenceNumber, long stateIndex, long readStateIndex,
                                     String commitId, int substep) implements Membership {}

    private static String unpackNullableString(MessagePackIO.Reader unpacker) throws Exception {
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
            String documentCollection) implements Membership {
    }
}
