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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.api.tracking.*;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.websocket.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutedMessageTest {
    @Test
    void routesSurviveNativeEnvelopesCopiesPatchesAndJacksonViews() throws Exception {
        for (String route : List.of("", "GET /a%2Fb+z", "WS_CLOSE https://[::1]:8443/東京/😀")) {
            var source = message(route);
            byte[] bytes = BinaryWire.encodeEnvelope(source);
            assertEquals(2, bytes[4]);
            assertEquals(source.getBytes(), bytes.length);
            var decoded = BinaryWire.decodeEnvelope(bytes, 100_000);
            assertEquals(source, decoded);
            assertArrayEquals(bytes, BinaryWire.encodeEnvelope(decoded));
            assertEquals(route, decoded.withData(new Data<>(new byte[]{2}, "Changed", 3, Data.JSON_FORMAT)).getRoute());
            assertEquals(route, decoded.withMetadata(Metadata.of("changed", true)).getRoute());
            assertEquals(route, decoded.withSegment(8).getRoute());
            decoded.setIndex(900L);
            assertEquals(900L, BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(decoded), 100_000).getIndex());
            decoded.setRoute("POST /changed");
            assertEquals("POST /changed", JsonUtils.reader.readValue(JsonUtils.asJson(decoded), SerializedMessage.class).getRoute());
            assertEquals("POST /changed", BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(decoded), 100_000).getRoute());
            decoded.setRoute(null);
            byte[] legacy = BinaryWire.encodeEnvelope(decoded);
            assertEquals(1, legacy[4]);
            assertNull(BinaryWire.decodeEnvelope(legacy, 100_000).getRoute());
        }
    }

    @Test
    void selectionNeverOpensMetadataOrPayloadIncludingUnknownTypes() throws Exception {
        var predicate = ConsumerFilter.select(null, 1, ConsumerFilter.VERSION, Data.JSON_FORMAT, null,
                List.of(new ConsumerFilter.RoutePattern("GET ", "^/allowed$")), List.of()).compile();
        for (String type : java.util.Arrays.asList(null, "Body")) {
            for (String route : List.of("GET /allowed", "POST /allowed", "GET /other")) {
                var guarded = new SerializedMessage(new Data<>(() -> { throw new AssertionError("payload"); }, type, 0,
                                                              Data.JSON_FORMAT), null, "id", 0L) {
                    @Override public Metadata getMetadata() { throw new AssertionError("metadata"); }
                    @Override public boolean metadataContainsKey(String key) { throw new AssertionError("metadata"); }
                    @Override public String getMetadataValue(String key) { throw new AssertionError("metadata"); }
                };
                guarded.setRoute(route);
                assertEquals(route.equals("GET /allowed"), predicate.test(guarded));
            }
        }
        // Invalid opaque metadata proves lazy native route access does not decode that metadata either.
        byte[] bytes = BinaryWire.encodeEnvelope(message("GET /allowed"));
        int metadataLength = ByteBuffer.wrap(bytes).getInt(64);
        java.util.Arrays.fill(bytes, bytes.length - metadataLength, bytes.length, (byte) 0xff);
        assertTrue(predicate.test(BinaryWire.decodeEnvelope(bytes, 100_000)));
    }

    @Test
    void missingRoutesAndUpcastInputsStayEligibleButPresentRoutesAreSelective() {
        var filter = ConsumerFilter.select(null, 1, ConsumerFilter.VERSION, Data.JSON_FORMAT, null,
                List.of(new ConsumerFilter.RoutePattern("GET ", "/allowed")),
                List.of(new ConsumerFilter.PayloadType("Legacy", 0))).compile();
        assertTrue(filter.test(message(null)));
        var legacy = message("POST /other").withData(new Data<>(new byte[0], "Legacy", 0, Data.JSON_FORMAT));
        assertTrue(filter.test(legacy));
        legacy.setData(new Data<>(new byte[0], "Legacy", 1, Data.JSON_FORMAT));
        assertFalse(filter.test(legacy));
    }

    @Test
    void unknownPeersUseCborWhileCapablePeersKeepCompactEnvelopes() throws Exception {
        var append = new Append(MessageType.WEBREQUEST, List.of(message("GET /a%2Fb")), Guarantee.STORED);
        var read = new ReadResult(1L, new MessageBatch(new int[]{0, 1}, append.getMessages(), 1L,
                                                     Position.newPosition(), false), 0L);
        for (var format : WebSocketTransportFormat.values()) {
            for (boolean routed : List.of(false, true)) {
                var codec = WebSocketTransportCodecs.forFormat(format, JsonUtils.reader,
                        List.of(TrackingWebSocketCodec.INSTANCE), routed);
                for (JsonType input : List.of(append, new RequestBatch<>(List.of(append)), read,
                                              new ResultBatch(List.of(read)))) {
                    byte[] bytes = codec.encode(input);
                    assertEquals(input, codec.decode(bytes));
                    if (format == WebSocketTransportFormat.BINARY) {
                        assertEquals(routed, TrackingWireCodec.tryDecode(bytes) != null);
                    }
                }
            }
        }
        assertNull(TrackingWireCodec.tryEncode(append));
        append.getMessages().getFirst().setRoute(null);
        assertNotNull(TrackingWireCodec.tryEncode(append));
    }

    @Test
    void rejectsInvalidRouteLengthsAndTruncatedExtendedHeaders() {
        byte[] bytes = BinaryWire.encodeEnvelope(message("GET /path"));
        for (int invalid : List.of(-2, -1, Integer.MAX_VALUE)) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt).putInt(72, invalid);
            assertThrows(IOException.class, () -> BinaryWire.decodeEnvelope(corrupt, 100_000));
        }
        byte[] truncated = java.util.Arrays.copyOf(bytes, 74);
        ByteBuffer.wrap(truncated).putInt(8, 74);
        assertThrows(IOException.class, () -> BinaryWire.decodeEnvelope(truncated, 100_000));
    }

    private static SerializedMessage message(String route) {
        var result = new SerializedMessage(new Data<>(new byte[]{1, 2, 3}, null, 2, "unknown"),
                                           Metadata.of("opaque", "value"), "id", 42L);
        result.setRoute(route);
        return result;
    }
}
