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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.msgpack.core.MessagePack;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelMembershipV8Test {
    @ParameterizedTest
    @EnumSource(WebSocketTransportFormat.class)
    void expandsMixedV7AndV8WithAliasesIndependentCursorsAndSubstepReset(WebSocketTransportFormat format)
            throws Exception {
        var request = new GetModelEvents(List.of(new ModelEventStreamRequest("alias", -1L, 4),
                new ModelEventStreamRequest("root", 0L, 1)), ModelReadBoundary.current(), 0L);
        var head = new ModelHeadState("root", "Root", 3, 8, true, false);
        var payloads = List.of(payload(1), payload(3), payload(6), payload(8));
        byte[] v7 = v7();
        byte[] v8 = v8(2, 2);
        var codec = WebSocketTransportCodecs.forFormat(format, JsonUtils.writer, List.of(ModelWebSocketCodec.INSTANCE));
        for (boolean compressed : List.of(false, true)) {
            byte[] data = compressed ? CompressionAlgorithm.ZSTD.compress(v8) : v8;
            // Also exercise an offset byte-range view rather than a separate block buffer.
            byte[] buffer = new byte[data.length + 9];
            System.arraycopy(data, 0, buffer, 4, data.length);
            var source = new GetModelEventsResult(1, 8, true, payloads,
                    List.of(new ModelEventStream("alias", head, List.of()),
                            new ModelEventStream("root", head, List.of())), new long[0], List.of(), new long[0],
                    List.of(new ModelEventDataBlock(v7), new ModelEventDataBlock(buffer, 4, data.length)));
            // JSON/CBOR carry explicit block offsets as well as bytes.
            var wire = (GetModelEventsResult) codec.decode(codec.encode(source));
            var expanded = ModelEventPageDecoder.expand(request, wire);
            assertEquals(List.of(0, 2, 0, 2), expanded.getStreams().getFirst().getMemberships().stream()
                    .map(ModelEventMembership::getSubstep).toList());
            assertEquals(List.of(0L, 1L, 2L, 3L), expanded.getStreams().getFirst().getMemberships().stream()
                    .map(ModelEventMembership::getSequenceNumber).toList());
            assertEquals(List.of(new ModelEventMembership(1, 3, -1, "first", 2)),
                         expanded.getStreams().getLast().getMemberships());
            assertEquals(payloads, expanded.getPayloads());
        }
    }

    @Test
    void rejectsMalformedLogicalBlocksAndKeepsPublicV7MetadataViewStrict() throws Exception {
        byte[] valid = v8(2, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, ModelStreamBatchDecoder.decodeMemberships(new ModelEventDataBlock(valid))
                .getLast().substep());
        assertThrows(IllegalStateException.class, () -> ModelStreamBatchDecoder.decode(valid));
        assertThrows(IllegalStateException.class,
                     () -> ModelStreamBatchDecoder.decodeMemberships(new ModelEventDataBlock(v8(2, -1))));
        byte[] unknown = valid.clone(); unknown[0] = 9;
        assertThrows(IllegalStateException.class,
                     () -> ModelStreamBatchDecoder.decodeMemberships(new ModelEventDataBlock(unknown)));
        assertThrows(IllegalStateException.class, () -> ModelStreamBatchDecoder.decodeMemberships(
                new ModelEventDataBlock(Arrays.copyOf(valid, valid.length + 1))));
        assertThrows(Exception.class, () -> ModelStreamBatchDecoder.decodeMemberships(
                new ModelEventDataBlock(Arrays.copyOf(valid, valid.length - 1))));
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(8).packArrayHeader(0);
            assertThrows(IllegalStateException.class, () -> ModelStreamBatchDecoder.decodeMemberships(
                    new ModelEventDataBlock(packer.toByteArray())));
        }
    }

    @Test
    void boundsLogicalBlockAllocationAndRejectsDeltaOverflow() throws Exception {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(8).packArrayHeader(Integer.MAX_VALUE);
            assertThrows(IllegalStateException.class, () -> ModelStreamBatchDecoder.decodeMemberships(
                    new ModelEventDataBlock(packer.toByteArray())));
        }
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(8).packArrayHeader(2);
            packer.packString("root").packLong(0).packLong(Long.MAX_VALUE).packLong(-1).packNil().packInt(1);
            packer.packString("root").packLong(1).packLong(1).packLong(0).packString("commit").packInt(2);
            assertThrows(ArithmeticException.class, () -> ModelStreamBatchDecoder.decodeMemberships(
                    new ModelEventDataBlock(packer.toByteArray())));
        }
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(8).packArrayHeader(1);
            packer.packString("root").packLong(0).packLong(1).packLong(-1).packNil().packInt(1);
            assertNull(ModelStreamBatchDecoder.decodeMemberships(new ModelEventDataBlock(packer.toByteArray()))
                               .getFirst().commitId());
        }
    }

    private static ModelEventPayload payload(long state) {
        return new ModelEventPayload(state, new SerializedMessage(new Data<>(new byte[]{1}, "event", 0),
                Metadata.empty(), "event-" + state, 1L));
    }

    private static byte[] v8(int firstStep, int secondStep) throws Exception {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(8).packArrayHeader(2);
            packer.packString("root").packLong(1).packLong(3).packLong(-1).packString("first").packInt(firstStep);
            packer.packString("root").packLong(3).packLong(5).packLong(5).packString("second").packInt(secondStep);
            return packer.toByteArray();
        }
    }

    private static byte[] v7() throws Exception {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(7).packArrayHeader(2).packLong(1).packLong(1).packLong(-1).packBoolean(true).packString("Root");
            packer.packString("root").packLong(0).packLong(0).packLong(0).packString("first")
                    .packLong(0).packBoolean(true).packLong(1).packNil();
            packer.packString("root").packLong(5).packLong(5).packLong(5).packString("second")
                    .packLong(2).packBoolean(true).packLong(1).packNil();
            return packer.toByteArray();
        }
    }
}
