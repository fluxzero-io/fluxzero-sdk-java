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
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEventWireCodecTest {

    @Test
    void roundTripsRequestWithItsTransportIdentity() throws Exception {
        GetModelEvents request =
                new GetModelEvents(
                        List.of(
                                new ModelEventStreamRequest("order-1", -1L, 100),
                                new ModelEventStreamRequest("inventory-1", 4L, 20)),
                        ModelReadBoundary.event(123L), 8_192L);

        byte[] encoded =
                ModelEventWireCodec.tryEncode(
                        new RequestBatch<>(List.of(request)));
        assertEquals(7, encoded[Integer.BYTES] & 0xff);
        RequestBatch<?> decodedBatch =
                assertInstanceOf(
                        RequestBatch.class,
                        ModelEventWireCodec.tryDecode(encoded));
        GetModelEvents decoded =
                assertInstanceOf(
                        GetModelEvents.class,
                        decodedBatch.getRequests().getFirst());

        assertEquals(request.getRequestId(), decoded.getRequestId());
        assertEquals(request.getRequests(), decoded.getRequests());
        assertEquals(ModelReadBoundary.event(123L), decoded.getBoundary());
        assertEquals(8_192L, decoded.getMaxBytes());
        GetModelEvents direct =
                assertInstanceOf(
                        GetModelEvents.class,
                        ModelEventWireCodec.tryDecode(
                                ModelEventWireCodec.tryEncode(request)));
        assertEquals(request.getRequestId(), direct.getRequestId());
        assertEquals(request.getRequests(), direct.getRequests());
    }

    @Test
    void roundTripsEventFallbackRequestsWithoutChangingTheRegularWireVersion() throws Exception {
        GetModelEvents request = new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 10)),
                ModelReadBoundary.eventOrCurrent(123L), 1_024L);

        byte[] direct = ModelEventWireCodec.tryEncode(request);
        assertEquals(8, direct[Integer.BYTES] & 0xff);
        assertEquals(request, ModelEventWireCodec.tryDecode(direct));
        var batch = new RequestBatch<>(List.of(request));
        assertEquals(batch, ModelEventWireCodec.tryDecode(
                ModelEventWireCodec.tryEncode(batch)));
    }

    @Test
    void roundTripsHeadsMembershipsRegularAndPersistedPayloads() throws Exception {
        SerializedMessage regular =
                new SerializedMessage(
                        new Data<>(new byte[]{1, 2, 3}, "event", 2, "application/json"),
                        Metadata.of("tenant", "one"),
                        3, 80L, "source", "target", 9, 1234L, "message-1", null);
        GetModelEventsResult result =
                new GetModelEventsResult(
                        99L,
                        91L,
                        List.of(new ModelEventPayload(80L, regular)),
                        List.of(
                                new ModelEventStream(
                                        "order-1",
                                        new ModelHeadState(
                                                "order-1", "example.Order",
                                                7L, 90L, true, false),
                                        List.of(
                                                new ModelEventMembership(
                                                        7L, 80L, 70L,
                                                        "commit-😀", 2))),
                                new ModelEventStream(
                                        "missing", null, List.of())),
                        new long[]{81L, 82L},
                        List.of(
                                new ModelEventPayloadBlock(
                                        100L,
                                        2,
                                        true,
                                        new byte[]{5, 4, 3})),
                        new long[]{100L, 101L},
                        List.of(
                                new ModelEventDataBlock(
                                        new byte[]{0, 7, 7, 9},
                                        1,
                                        2),
                                new ModelEventDataBlock(
                                        new byte[]{8, 8, 8})));
        result.setRequestReceivedTimestamp(10L);
        result.setResponseQueuedTimestamp(11L);
        result.setResponseSendStartTimestamp(12L);

        byte[] encoded =
                ModelEventWireCodec.tryEncode(
                        new ResultBatch(List.of(result)));
        ResultBatch decodedBatch =
                assertInstanceOf(
                        ResultBatch.class,
                        ModelEventWireCodec.tryDecode(encoded));
        GetModelEventsResult decoded =
                assertInstanceOf(
                        GetModelEventsResult.class,
                        decodedBatch.getResults().getFirst());

        assertEquals(result.getRequestId(), decoded.getRequestId());
        assertEquals(result.getStateIndex(), decoded.getStateIndex());
        assertEquals(result.getStreams(), decoded.getStreams());
        assertEquals(1, decoded.getPayloads().size());
        SerializedMessage decodedRegular =
                decoded.getPayloads().getFirst().getEvent();
        assertTrue(decodedRegular.getData().byteArrayView() != null);
        assertArrayEquals(
                regular.getData().getValue(),
                decodedRegular.getData().getValue());
        assertEquals(regular.getData().getType(), decodedRegular.getData().getType());
        assertEquals(regular.getMetadata(), decodedRegular.getMetadata());
        assertEquals(regular.getIndex(), decodedRegular.getIndex());
        assertArrayEquals(
                result.getPayloadStateIndices(),
                decoded.getPayloadStateIndices());
        assertEquals(
                result.getPayloadBlocks(),
                decoded.getPayloadBlocks());
        assertArrayEquals(
                result.getPayloadEventIndices(),
                decoded.getPayloadEventIndices());
        assertEquals(
                result.getMembershipBlocks().size(),
                decoded.getMembershipBlocks().size());
        assertSame(
                encoded,
                decoded.getMembershipBlocks()
                        .getFirst()
                        .data());
        for (int i = 0;
             i < result.getMembershipBlocks().size();
             i++) {
            assertArrayEquals(
                    bytes(
                            result.getMembershipBlocks()
                                    .get(i)),
                    bytes(
                            decoded.getMembershipBlocks()
                                    .get(i)));
        }
        assertEquals(10L, decoded.getRequestReceivedTimestamp());
        assertEquals(11L, decoded.getResponseQueuedTimestamp());
        assertEquals(12L, decoded.getResponseSendStartTimestamp());

        GetModelEventsResult direct =
                assertInstanceOf(
                        GetModelEventsResult.class,
                        ModelEventWireCodec.tryDecode(
                                ModelEventWireCodec.tryEncode(result)));
        assertEquals(result.getStreams(), direct.getStreams());
        assertEquals(
                result.getPayloadBlocks(),
                direct.getPayloadBlocks());
        assertArrayEquals(
                result.getPayloadEventIndices(),
                direct.getPayloadEventIndices());
    }

    @Test
    void rejectsUnreleasedPreviewVersions() throws Exception {
        byte[] encoded = ModelEventWireCodec.tryEncode(compactResult("first", "second"));

        encoded[Integer.BYTES]--;

        assertThrows(IOException.class, () -> ModelEventWireCodec.tryDecode(encoded));
    }

    @Test
    void encodesARepeatedModelTypeOncePerResult() throws Exception {
        GetModelEventsResult sameType =
                compactResult("example.Repeated", "example.Repeated");
        GetModelEventsResult differentTypes =
                compactResult("example.First", "example.Second");

        byte[] shared =
                ModelEventWireCodec.tryEncode(sameType);
        byte[] repeated =
                ModelEventWireCodec.tryEncode(differentTypes);

        assertEquals(
                sameType.getStreams(),
                assertInstanceOf(
                        GetModelEventsResult.class,
                        ModelEventWireCodec.tryDecode(shared))
                        .getStreams());
        org.junit.jupiter.api.Assertions.assertTrue(
                shared.length < repeated.length);
    }

    @Test
    void roundTripsSharedIdPrefixesAndSequenceNumbers() throws Exception {
        GetModelEventsResult result =
                new GetModelEventsResult(
                        1L,
                        2L,
                        List.of(),
                        List.of(
                                new ModelEventStream(
                                        "order-1001",
                                        new ModelHeadState(
                                                "order-1001",
                                                "example.Order",
                                                7L, 1L,
                                                true, false),
                                        List.of()),
                                new ModelEventStream(
                                        "order-1002",
                                        new ModelHeadState(
                                                "order-1002",
                                                "example.Order",
                                                7L, 2L,
                                                true, false),
                                        List.of())),
                        new long[0],
                        List.of(),
                        new long[0],
                        List.of(new ModelEventDataBlock(new byte[]{1})));

        GetModelEventsResult decoded =
                assertInstanceOf(
                        GetModelEventsResult.class,
                        ModelEventWireCodec.tryDecode(
                                ModelEventWireCodec.tryEncode(result)));

        assertEquals(result.getStreams(), decoded.getStreams());
    }

    private static GetModelEventsResult compactResult(
            String firstType,
            String secondType) {
        return new GetModelEventsResult(
                1L,
                2L,
                List.of(),
                List.of(
                        new ModelEventStream(
                                "first",
                                new ModelHeadState(
                                        "first", firstType,
                                        0L, 1L, true, false),
                                List.of()),
                        new ModelEventStream(
                                "second",
                                new ModelHeadState(
                                        "second", secondType,
                                        0L, 2L, true, false),
                                List.of())),
                new long[0],
                List.of(),
                new long[0],
                List.of(new ModelEventDataBlock(new byte[]{1})));
    }

    private static byte[] bytes(
            ModelEventDataBlock block) {
        return Arrays.copyOfRange(
                block.data(),
                block.offset(),
                block.offset() + block.length());
    }
}
