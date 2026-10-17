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

import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.DOCUMENT;
import static org.junit.jupiter.api.Assertions.*;

class StorePositionTest {
    @ParameterizedTest
    @EnumSource(WebSocketTransportFormat.class)
    void preservesTopicAndPositionAcrossTransports(WebSocketTransportFormat format) throws Exception {
        StorePosition request = new StorePosition(DOCUMENT, "projection", new int[]{0, 128}, 42L, STORED, "orders");
        var codec = WebSocketTransportCodecs.forFormat(format, JsonUtils.writer);
        StorePosition decoded = (StorePosition) codec.decode(codec.encode(request));
        assertEquals(request, decoded);
        assertEquals(request.getRequestId(), decoded.getRequestId());
        assertEquals("orders", decoded.getTopic());
        var metric = JsonUtils.writer.valueToTree(request.toMetric());
        assertEquals("orders", metric.path("topic").asText());
        assertEquals(42L, metric.path("lastIndex").asLong());
        assertEquals("DOCUMENT_projection", decoded.routingKey());
    }

    @Test
    void readsOldRequestsAndPreservesUnscopedEntryPoints() {
        StorePosition oldRequest = new StorePosition(DOCUMENT, "projection", new int[]{0, 128}, 42L, STORED);
        String json = JsonUtils.asJson(oldRequest);
        assertFalse(JsonUtils.writer.valueToTree(oldRequest).has("topic"));
        StorePosition decoded = (StorePosition) JsonUtils.fromJson(json, JsonType.class);
        assertNull(decoded.getTopic());
        assertEquals(oldRequest, decoded);
        assertEquals(oldRequest, StorePosition.fromJson(DOCUMENT, "projection", new int[]{0, 128}, 42L, STORED));
    }

    @Test
    void topicDoesNotChangeRoutingOrSegmentValidation() {
        StorePosition first = new StorePosition(DOCUMENT, "projection", new int[]{0, 128}, 42L, STORED, "orders");
        StorePosition second = new StorePosition(DOCUMENT, "projection", new int[]{0, 128}, 42L, STORED, "invoices");
        assertEquals(first.routingKey(), second.routingKey());
        for (int[] segment : new int[][]{{0}, {10, 5}, {-1, 10}, {0, 129}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> StorePosition.fromJson(DOCUMENT, "projection", segment, 42L, STORED));
            assertThrows(IllegalArgumentException.class,
                    () -> StorePosition.fromJson(DOCUMENT, "projection", segment, 42L, STORED, "orders"));
        }
    }
}
