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

package io.fluxzero.common.api.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerFilterTest {
    @Test
    void preservesCustomReadMetricOverrides() {
        Read custom = new Read(MessageType.EVENT, "consumer", "tracker", 10, 0L, null,
                               false, false, false, false, 0L, null) {
            @Override
            public Object toMetric() {
                return "custom metric";
            }
        };
        assertEquals("custom metric", custom.toMetric());
        Read ordinary = read();
        assertSame(ordinary, ordinary.toMetric());
    }

    @Test
    void acceptsCurrentTypesAtEveryRevisionAndOnlyDeclaredLegacyInputs() {
        var filter = ConsumerFilter.select(null, 1, 1, "application/json",
                List.of(new ConsumerFilter.PayloadType("Current", null)), null,
                List.of(new ConsumerFilter.PayloadType("Legacy", 0)), false);
        var predicate = filter.compile();
        assertTrue(predicate.test(message("Current", 0)));
        assertTrue(predicate.test(message("Current", 9)));
        assertTrue(predicate.test(message("Legacy", 0)));
        assertFalse(predicate.test(message("Legacy", 1)));
        assertFalse(predicate.test(message("Unrelated", 0)));
        assertTrue(predicate.test(message(null, 0)));
        assertTrue(predicate.test(message("Unrelated", 0).withData(new Data<>(new byte[0], "Unrelated", 0, "text/plain"))));
    }

    @Test
    void upcastInputsBypassWebConstraintsAndUnknownVersionsAllowDelivery() {
        var filter = ConsumerFilter.select(null, 1, 1, null, null,
                List.of(new ConsumerFilter.WebRoute("GET", null, "^/orders/[^/]+/?$")),
                List.of(new ConsumerFilter.PayloadType("Legacy", 0)), false);
        assertTrue(filter.compile().test(web("GET", "/orders/a%2Fb?include=all")));
        assertFalse(filter.compile().test(web("POST", "/orders/a")));
        assertFalse(filter.compile().test(web("GET", "/other")));
        assertTrue(filter.compile().test(message("Legacy", 0).withMetadata(Metadata.of("method", "POST", "url", "/other"))));
        assertTrue(filter.compile().test(message("Current", 0)));
        assertTrue(filter.compile().test(web("GET", "/bad%xx")));
        assertTrue(ConsumerFilter.select(null, 1, 99, null, List.of(), List.of(), List.of(), false).compile().test(message("Anything", 0)));
    }

    @Test
    void absoluteOriginsWithoutAPathUseTheRootRoute() {
        var predicate = ConsumerFilter.select(null, 1, 1, null, null,
                List.of(new ConsumerFilter.WebRoute(null, "https://example.test:8443", "^/$")), List.of(), false)
                .compile();
        assertTrue(predicate.test(web("GET", "https://example.test:8443")));
        assertTrue(predicate.test(web("HEAD", "https://example.test:8443?x=y")));
        assertTrue(predicate.test(web("OPTIONS", "https://example.test:8443/#fragment")));
        assertFalse(predicate.test(web("GET", "https://other.test:8443")));
        assertFalse(predicate.test(web("GET", "https://example.test:8443/other")));
    }

    @Test
    void distinguishesOriginsAndKeepsUnknownUnionMembersUnrestricted() {
        var filter = ConsumerFilter.select(null, 1, 1, null, null,
                List.of(new ConsumerFilter.WebRoute(null, "https://example.test:8443", "^/endpoint$")), List.of(), false);
        assertTrue(filter.compile().test(web("PATCH", "https://example.test:8443/endpoint?x=y")));
        assertFalse(filter.compile().test(web("PATCH", "/endpoint")));
        assertTrue(ConsumerFilter.union(java.util.Arrays.asList(filter, null)).compile().test(web("DELETE", "/other")));
    }

    @Test
    void preservesFilteredReadsThroughEveryTransportAndFallsBackForAnEntireMixedBinaryBatch() throws Exception {
        Read filtered = read();
        filtered.setConsumerFilter(ConsumerFilter.select(null, 8, 1, "application/json",
                List.of(new ConsumerFilter.PayloadType("Current", null)), null, List.of(), false));
        filtered.setMaxScanLookBack(60_000L);
        Read metric = (Read) filtered.toMetric();
        assertEquals(filtered.getRequestId(), metric.getRequestId());
        assertTrue(metric.getConsumerFilter().referenceOnly());
        assertFalse(filtered.getConsumerFilter().referenceOnly());
        assertTrue(metric.getConsumerFilter().payloadTypes().isEmpty());
        for (var format : WebSocketTransportFormat.values()) {
            var codec = WebSocketTransportCodecs.forFormat(format, JsonUtils.reader,
                                                           List.of(TrackingWebSocketCodec.INSTANCE));
            Read decoded = (Read) codec.decode(codec.encode(filtered));
            assertEquals(filtered.getConsumerFilter(), decoded.getConsumerFilter());
            assertEquals(60_000L, decoded.getMaxScanLookBack());
            var batch = new RequestBatch<>(List.of(read(), filtered));
            var decodedBatch = (RequestBatch<?>) codec.decode(codec.encode(batch));
            assertEquals(filtered.getConsumerFilter(), ((Read) decodedBatch.getRequests().getLast()).getConsumerFilter());
        }
        assertNull(TrackingWireCodec.tryEncode(filtered));
        assertNull(TrackingWireCodec.tryEncode(new RequestBatch<>(List.of(read(), filtered))));
        assertNotNull(TrackingWireCodec.tryEncode(read()));
    }

    private static Read read() {
        return new Read(MessageType.EVENT, "consumer", "tracker", 10, 900_000L, null,
                        false, false, false, false, 0L, null);
    }

    @Test
    void preservesCacheMissControlAcrossEveryTransportWithoutChangingOrdinaryBatches() throws Exception {
        var control = MessageBatch.requireConsumerFilter();
        var empty = new MessageBatch(new int[]{0, 0}, List.of(), null, Position.newPosition(), false);
        assertNotEquals(control, empty);
        assertNotEquals(empty, control);
        assertTrue(control.withMessages(List.of()).isConsumerFilterRequired());
        var result = new ReadResult(42, control, 123L);
        var ordinary = new ReadResult(43, empty, 123L);
        for (var format : WebSocketTransportFormat.values()) {
            var codec = WebSocketTransportCodecs.forFormat(format, JsonUtils.reader,
                                                           List.of(TrackingWebSocketCodec.INSTANCE));
            var decoded = (ReadResult) codec.decode(codec.encode(result));
            assertEquals(42, decoded.getRequestId());
            assertTrue(decoded.getMessageBatch().isConsumerFilterRequired(), format + " " + JsonUtils.asJson(result));
            var batch = (ResultBatch) codec.decode(codec.encode(new ResultBatch(List.of(ordinary, result))));
            assertFalse(((ReadResult) batch.getResults().getFirst()).getMessageBatch().isConsumerFilterRequired());
            assertTrue(((ReadResult) batch.getResults().getLast()).getMessageBatch().isConsumerFilterRequired());
        }
        assertNull(TrackingWireCodec.tryEncode(result));
        assertNotNull(TrackingWireCodec.tryEncode(ordinary));
    }

    private static SerializedMessage message(String type, int revision) {
        return new SerializedMessage(new Data<>(new byte[0], type, revision, "application/json"), Metadata.empty(),
                                     "id", 0L);
    }

    private static SerializedMessage web(String method, String url) {
        return message("[B", 0).withMetadata(Metadata.of("method", method, "url", url));
    }
}
