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

import io.fluxzero.sdk.common.websocket.JdkWebsocketConnector;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.common.websocket.WebsocketConnectionOptions;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JettyWebsocketRouterTest {

    @Test
    void stopsEndpointsWhenServerStopsWithoutRegisteringJettyShutdownHook() throws Exception {
        CountDownLatch endpointOpened = new CountDownLatch(1);
        CountDownLatch endpointStopped = new CountDownLatch(1);
        JettyWebsocketRouter router = WebsocketDeploymentUtils.deploy(
                ignored -> new TrackingEndpoint(endpointOpened, endpointStopped), "/test", new JettyWebsocketRouter());
        Server server = router.start(0);

        try {
            assertFalse(server.getStopAtShutdown());
            connectTestSession(server);
            assertTrue(endpointOpened.await(1, SECONDS), "Expected test endpoint to open before server stop");
        } finally {
            server.stop();
        }

        assertTrue(endpointStopped.await(1, SECONDS), "Expected server stop to shut down tracked endpoints");
    }

    private static WebsocketSession connectTestSession(Server server) throws Exception {
        return new JdkWebsocketConnector().connect(new NoOpClientEndpoint(),
                                                   new WebsocketConnectionOptions(
                                                           Map.of(), Map.of(), Duration.ofSeconds(5), List.of()),
                                                   URI.create("ws://localhost:" + getLocalPort(server)
                                                              + "/test?clientId=test-client&clientName=test-client"));
    }

    private static int getLocalPort(Server server) {
        return Arrays.stream(server.getConnectors())
                .filter(ServerConnector.class::isInstance)
                .map(ServerConnector.class::cast)
                .mapToInt(ServerConnector::getLocalPort)
                .filter(port -> port > 0)
                .findFirst()
                .orElseThrow();
    }

    private static class TrackingEndpoint extends WebsocketEndpoint {
        private final CountDownLatch opened;
        private final CountDownLatch stopped;

        TrackingEndpoint(CountDownLatch opened, CountDownLatch stopped) {
            this.opened = opened;
            this.stopped = stopped;
        }

        @Override
        public void onOpen(ServerWebsocketSession session) {
            super.onOpen(session);
            opened.countDown();
        }

        @Override
        protected void shutDown() {
            super.shutDown();
            stopped.countDown();
        }
    }

    private static class NoOpClientEndpoint implements io.fluxzero.sdk.common.websocket.WebsocketEndpoint {
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
    }
}
