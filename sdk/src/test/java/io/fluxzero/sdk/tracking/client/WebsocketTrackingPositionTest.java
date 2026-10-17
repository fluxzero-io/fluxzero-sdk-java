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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.tracking.StorePosition;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class WebsocketTrackingPositionTest {
    @ParameterizedTest
    @CsvSource({"DOCUMENT,orders", "CUSTOM,notifications", "EVENT,"})
    void includesEndpointTopicWithoutChangingPositionOrCompletion(MessageType type, String topic) {
        WebSocketClient owner = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost").name("position-test").disableMetrics(true).build());
        try (PositionClient client = new PositionClient(owner, type, topic)) {
            int[] segment = {0, 128};
            var completion = client.storePosition("projection", segment, 42L, Guarantee.STORED);
            assertSame(client.completion, completion);
            assertFalse(completion.isDone());
            StorePosition request = client.request;
            assertEquals(type, request.getMessageType());
            assertEquals(topic, request.getTopic());
            assertEquals("projection", request.getConsumer());
            assertArrayEquals(segment, request.getSegment());
            assertEquals(42L, request.getLastIndex());
            assertEquals(Guarantee.STORED, request.getGuarantee());
            assertEquals(topic, JsonUtils.writer.valueToTree(request.toMetric()).path("topic").asText(null));
            client.completion.complete(null);
            assertTrue(completion.isDone());
        } finally {
            owner.shutDown();
        }
    }

    private static final class PositionClient extends WebsocketTrackingClient {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private StorePosition request;

        PositionClient(WebSocketClient owner, MessageType type, String topic) {
            super(URI.create("ws://localhost/tracking"), owner, type, topic, false);
        }

        @Override
        protected CompletableFuture<Void> sendCommand(Command command) {
            request = (StorePosition) command;
            return completion;
        }
    }
}
