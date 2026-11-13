/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.configuration.client;

import io.fluxzero.common.Registration;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.sdk.tracking.client.CachingTrackingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.MessageType.CUSTOM;
import static io.fluxzero.common.ServicePathBuilder.gatewayPath;
import static io.fluxzero.common.ServicePathBuilder.trackingPath;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Isolated
class WebSocketClientRouteTest {
    @Test
    void cachedReadersAndPublishersUseTheConfiguredTopicAndNamespace() throws Exception {
        var connector = AbstractWebsocketClient.defaultWebsocketConnector;
        var handshakes = new LinkedBlockingQueue<URI>();
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost").name("routes").disableMetrics(true).build()
                .withTrackingConfig(CUSTOM, WebSocketClient.TrackingClientConfig.builder().cacheSize(16).build()));
        Registration subscriptions = Registration.noOp();
        try {
            AbstractWebsocketClient.defaultWebsocketConnector = (endpoint, options, uri) -> {
                var session = mock(WebsocketSession.class);
                when(session.isOpen()).thenReturn(true);
                handshakes.add(uri);
                return session;
            };
            assertInstanceOf(CachingTrackingClient.class, client.getTrackingClient(CUSTOM, "orders"));
            var read = new LinkedBlockingQueue<Boolean>();
            subscriptions = client.monitorTracking(CUSTOM, "orders", read::add);
            assertEquals(false, read.poll(3, TimeUnit.SECONDS));
            assertEquals(true, read.poll(3, TimeUnit.SECONDS));
            var cached = handshakes.remove();
            assertEquals("/" + trackingPath(CUSTOM), cached.getPath());
            assertTrue(cached.getQuery().contains("topic=orders"));

            var sameRoute = new LinkedBlockingQueue<Boolean>();
            subscriptions = subscriptions.merge(client.monitorTracking(CUSTOM, "orders", sameRoute::add));
            assertEquals(true, sameRoute.poll(3, TimeUnit.SECONDS));
            assertTrue(handshakes.isEmpty());

            var namespaced = (WebSocketClient) client.forNamespace("second");
            var publish = new LinkedBlockingQueue<Boolean>();
            subscriptions = subscriptions.merge(namespaced.monitorPublishing(CUSTOM, "receipts", publish::add));
            assertEquals(false, publish.poll(3, TimeUnit.SECONDS));
            assertEquals(true, publish.poll(3, TimeUnit.SECONDS));
            var writer = handshakes.remove();
            assertEquals("/" + gatewayPath(CUSTOM), writer.getPath());
            assertTrue(writer.getQuery().contains("topic=receipts"));
            assertTrue(writer.getQuery().contains("projectId=second"));
        } finally {
            subscriptions.cancel();
            try { client.shutDown(); } finally { AbstractWebsocketClient.defaultWebsocketConnector = connector; }
        }
    }
}
