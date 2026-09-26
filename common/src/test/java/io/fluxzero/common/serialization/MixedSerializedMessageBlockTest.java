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

package io.fluxzero.common.serialization;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventPageDecoder;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventPayloadBlock;
import io.fluxzero.common.api.modeling.ModelEventWireCodec;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MixedSerializedMessageBlockTest {
    @Test
    void readsAllLegacyVersionsInterleavedWithBinaryWithinExactByteRange() throws Exception {
        for (int version = 0; version <= 2; version++) {
            byte[] binary = BinaryWire.encodeEnvelope(message(2));
            byte[] legacy = legacy(version, 1);
            for (var parts : List.of(List.of(legacy, binary, legacy), List.of(binary, legacy, binary))) {
                var expected = parts.stream().flatMap(p -> LegacySerializedMessagePackCodec.decode(p).stream()).toList();
                byte[] bytes = concatenate(parts);
                assertEquals(expected, SerializedMessagePackCodec.decode(bytes));
                byte[] surrounded = new byte[bytes.length + 14];
                Arrays.fill(surrounded, (byte) 0xc1);
                System.arraycopy(bytes, 0, surrounded, 7, bytes.length);
                var decoded = SerializedMessagePackCodec.decode(surrounded, 7, bytes.length);
                assertEquals(expected, decoded);
                // Later reuse and failure must not invalidate views retained by an earlier result.
                SerializedMessagePackCodec.decode(binary);
                assertThrows(IllegalArgumentException.class, () -> SerializedMessagePackCodec.decode(new byte[]{(byte) 0xc1}));
                assertEquals(expected, decoded);
            }
        }
    }

    @Test
    void rejectsEveryIncompleteRecordWithoutReturningPartialResults() throws Exception {
        for (int version = 0; version <= 2; version++) {
            byte[] legacy = legacy(version, 1);
            byte[] binary = BinaryWire.encodeEnvelope(message(2));
            for (var parts : List.of(List.of(legacy, binary), List.of(binary, legacy))) {
                byte[] bytes = concatenate(parts);
                for (int length = 1; length < bytes.length; length++) {
                    byte[] prefix = Arrays.copyOf(bytes, length);
                    if (length == parts.getFirst().length) {
                        assertEquals(LegacySerializedMessagePackCodec.decode(parts.getFirst()), SerializedMessagePackCodec.decode(prefix));
                    } else {
                        assertThrows(IllegalArgumentException.class, () -> SerializedMessagePackCodec.decode(prefix));
                    }
                }
                assertThrows(IllegalArgumentException.class,
                        () -> SerializedMessagePackCodec.decode(concatenate(List.of(bytes, new byte[]{(byte) 0xc1}))));
            }
        }
    }

    @Test
    void rejectsInvalidBinaryVersionInsideOtherwiseValidMixedBlock() throws Exception {
        byte[] binary = BinaryWire.encodeEnvelope(message(2));
        binary[4] = 127;
        byte[] legacy = legacy(2, 1);
        assertThrows(IllegalArgumentException.class,
                () -> SerializedMessagePackCodec.decode(concatenate(List.of(legacy, binary, legacy))));
    }

    @Test
    void recognizesBinaryRecordsEvenWhenTheyAlsoParseAsVersionZero() throws Exception {
        byte[] payload = {0, 0, (byte) 0xc0, (byte) 0xc0, (byte) 0xc0, (byte) 0xc0, (byte) 0xc0, (byte) 0xa4};
        var message = new SerializedMessage(new Data<byte[]>(payload, null, 0, ""), Metadata.empty(),
                null, null, null, null, null, null, null, 192);
        byte[] binary = BinaryWire.encodeEnvelope(message);
        byte[] legacy = new MessagePackIO.Writer().packInt(0).packNil().packInt(0).packInt(0)
                .packNil().packNil().packNil().packNil().packNil().packNil().toByteArray();
        var expected = List.of(SerializedMessagePackCodec.decode(legacy).getFirst(), message);
        assertEquals(expected, SerializedMessagePackCodec.decode(concatenate(List.of(legacy, binary))));
        // Unsupported envelopes must not be reinterpreted as legacy records either.
        for (int headerOffset : new int[]{4, 5}) {
            byte[] corrupt = binary.clone();
            corrupt[headerOffset] = 127;
            assertThrows(IllegalArgumentException.class,
                    () -> SerializedMessagePackCodec.decode(concatenate(List.of(legacy, corrupt))));
        }
    }

    @Test
    void expandsMixedModelBlocksWithCompressionSelectionAndParallelDecoding() throws Exception {
        for (int version = 0; version <= 2; version++) {
            for (boolean compressed : List.of(false, true)) {
                for (boolean binaryFirst : List.of(false, true)) {
                    var blocks = new ArrayList<ModelEventPayloadBlock>();
                    var expected = new ArrayList<ModelEventPayload>();
                    long[] indices = new long[16];
                    for (int i = 0; i < 16; i++) {
                        long first = 3L * i;
                        byte[] legacy = legacy(version, binaryFirst ? first + 1 : first);
                        byte[] binary = BinaryWire.encodeEnvelope(message(binaryFirst ? first : first + 1));
                        byte[] bytes = concatenate(binaryFirst ? List.of(binary, legacy) : List.of(legacy, binary));
                        // Select only one of the neighboring messages, alternating formats across blocks.
                        int selected = i % 2;
                        byte[] selectedRecord = selected == 0 ? (binaryFirst ? binary : legacy) : (binaryFirst ? legacy : binary);
                        var event = LegacySerializedMessagePackCodec.decode(selectedRecord).getFirst();
                        indices[i] = first + selected;
                        event.setIndex(indices[i]);
                        expected.add(new ModelEventPayload(indices[i], event));
                        blocks.add(new ModelEventPayloadBlock(first, 2, compressed,
                                compressed ? CompressionAlgorithm.ZSTD.compress(bytes) : bytes));
                    }
                    var request = new GetModelEvents(List.of(), ModelReadBoundary.current(), 0);
                    var response = new GetModelEventsResult(1L, 100L, true, List.of(), List.of(),
                            indices, blocks, indices, List.of());
                    var transported = assertInstanceOf(GetModelEventsResult.class,
                            ModelEventWireCodec.tryDecode(ModelEventWireCodec.tryEncode(response)));
                    assertEquals(expected, ModelEventPageDecoder.expand(request, transported).getPayloads());
                }
            }
        }
    }

    @Test
    void concurrentMixedReadsRemainIsolatedAfterFailure() throws Exception {
        byte[] bytes = concatenate(List.of(legacy(1, 1), BinaryWire.encodeEnvelope(message(2)), legacy(2, 3)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 16).mapToObj(i -> executor.submit(() -> {
                assertThrows(IllegalArgumentException.class,
                        () -> SerializedMessagePackCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
                assertEquals(List.of(1L, 2L, 3L), SerializedMessagePackCodec.decode(bytes).stream()
                        .map(SerializedMessage::getIndex).toList());
            })).toList();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] legacy(int version, long index) throws Exception {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            // The payload contains a real binary envelope: format recognition must stay on record boundaries.
            byte[] payload = BinaryWire.encodeEnvelope(message(index));
            if (version > 0) packer.packInt(-version);
            packer.packInt(payload.length).addPayload(payload).packString("legacy-type").packInt(7);
            if (version > 0) packer.packString("application/custom");
            if (version == 2) {
                byte[] metadata = Metadata.of("tenant", "é", "number", 42).toData().getValue();
                packer.packInt(metadata.length).addPayload(metadata);
            } else {
                packer.packInt(1).packString("tenant").packString("é");
            }
            packer.packInt(3);
            if (version > 0) packer.packLong(index);
            packer.packString("source").packNil().packInt(42).packLong(1234L).packString("legacy-" + index);
            return packer.toByteArray();
        }
    }

    private static SerializedMessage message(long index) {
        return new SerializedMessage(new Data<>(new byte[]{1, 2, 3}, "type", 0, "application/json"),
                Metadata.of("tenant", "binary"), 2, index, "source", "target", 42, 1234L, "binary-" + index, null);
    }

    private static byte[] concatenate(List<byte[]> parts) {
        var joined = new ByteArrayOutputStream();
        parts.forEach(joined::writeBytes);
        return joined.toByteArray();
    }
}
