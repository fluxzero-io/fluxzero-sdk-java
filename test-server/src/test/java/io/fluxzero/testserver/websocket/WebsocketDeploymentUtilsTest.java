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
 *
 */

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.websocket.JdkWebsocketConnector;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.common.websocket.WebsocketConnectionOptions;
import io.fluxzero.sdk.common.websocket.WebsocketEndpoint;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.testserver.TestServerVersion;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsocketDeploymentUtilsTest {

    private static final int port = 9126;
    private static final AtomicInteger receivedBinaryMessageSize = new AtomicInteger();
    private static volatile CountDownLatch receivedBinaryMessage;
    private static Server server;

    @BeforeAll
    static void beforeAll() throws Exception {
        JettyWebsocketRouter router = WebsocketDeploymentUtils.deploy(ignored -> new io.fluxzero.testserver.websocket.WebsocketEndpoint() {
            @Override
            public void onOpen(ServerWebsocketSession session) {
            }

            @Override
            public void onMessage(byte[] bytes, ServerWebsocketSession session) {
                receivedBinaryMessageSize.set(bytes.length);
                CountDownLatch latch = receivedBinaryMessage;
                if (latch != null) {
                    latch.countDown();
                }
            }
        }, "/test/", new JettyWebsocketRouter());
        server = router.start(port);
    }

    @AfterAll
    static void afterAll() throws Exception {
        server.stop();
    }

    @Test
    void handshakePublishesRuntimeVersionHeader() throws Exception {
        WebsocketSession session = connectTestSession();

        try {
            assertEquals(TestServerVersion.version().orElseThrow(),
                         WebSocketCapabilities.getRuntimeVersion(
                                 session.getHandshakeResponseHeaders()).orElseThrow());
        } finally {
            session.close();
        }
    }

    @Test
    void acceptsBinaryMessagesLargerThanJettyDefault() throws Exception {
        receivedBinaryMessage = new CountDownLatch(1);
        receivedBinaryMessageSize.set(0);
        WebsocketSession session = connectTestSession();

        try {
            byte[] payload = new byte[96 * 1024];
            session.sendBinary(ByteBuffer.wrap(payload));

            assertTrue(receivedBinaryMessage.await(5, TimeUnit.SECONDS));
            assertEquals(payload.length, receivedBinaryMessageSize.get());
        } finally {
            session.close();
            receivedBinaryMessage = null;
        }
    }

    private static WebsocketSession connectTestSession() throws Exception {
        return new JdkWebsocketConnector().connect(new WebsocketEndpoint() {
            @Override
            public void onOpen(WebsocketSession session) {
            }

            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
            }

            @Override
            public void onPong(ByteBuffer data, WebsocketSession session) {
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
            }

            @Override
            public void onError(WebsocketSession session, Throwable error) {
            }
        }, new WebsocketConnectionOptions(Map.of(), Map.of(), Duration.ofSeconds(5), List.of()),
           URI.create("ws://localhost:" + port + "/test"));
    }
}
