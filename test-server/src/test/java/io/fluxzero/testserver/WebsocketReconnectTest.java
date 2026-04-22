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

package io.fluxzero.testserver;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.common.websocket.ServiceUrlBuilder;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.WebsocketTrackingClient;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import io.fluxzero.testserver.websocket.ConsumerEndpoint;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.websockets.jsr.UndertowSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.ServicePathBuilder.trackingPath;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.deploy;
import static io.undertow.Handlers.path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class WebsocketReconnectTest {
    private static final int port = 9125;
    private static Undertow server;

    @BeforeAll
    static void beforeAll() {
        PathHandler pathHandler = deploy(
                ignored -> new ClosingConsumerEndpoint(),
                "/%s/".formatted(trackingPath(MessageType.EVENT)), path());
        server = Undertow.builder().addHttpListener(port, "0.0.0.0").setHandler(pathHandler).build();
        server.start();
    }

    @AfterAll
    static void afterAll() {
        server.stop();
    }

    @Test
    void reconnectsAfterServerAbnormallyClosesSessionWithOutstandingRequest() throws Exception {
        String namespace = UUID.randomUUID().toString().replace("-", "");
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port)
                .namespace(namespace)
                .id("reconnect-test")
                .name("reconnect-test")
                .disableMetrics(true)
                .build();
        WebSocketClient webSocketClient = WebSocketClient.newInstance(clientConfig);
        URI endpoint = URI.create(ServiceUrlBuilder.trackingUrl(MessageType.EVENT, null, clientConfig));
        ReconnectObservingTrackingClient trackingClient =
                new ReconnectObservingTrackingClient(endpoint, webSocketClient);
        ConsumerConfiguration consumerConfiguration = ConsumerConfiguration.builder()
                .name("reconnect-test")
                .maxWaitDuration(Duration.ofSeconds(30))
                .build();

        CompletableFuture<MessageBatch> read =
                trackingClient.read("reconnect-test-tracker", -1L, consumerConfiguration);
        try {
            assertTrue(trackingClient.awaitReconnect(10, TimeUnit.SECONDS),
                       "Expected a replacement websocket session to be opened");
            assertTrue(trackingClient.awaitCloseHandled(1, TimeUnit.SECONDS),
                       "Expected the abnormal close to be handled");
            assertFalse(trackingClient.getCloseThreadName().contains("XNIO"),
                        "Close handling should not run on an XNIO I/O thread");
        } finally {
            read.cancel(true);
            trackingClient.close();
        }
    }

    private static class ClosingConsumerEndpoint extends ConsumerEndpoint {
        ClosingConsumerEndpoint() {
            super(new InMemoryMessageStore(MessageType.EVENT), MessageType.EVENT);
        }

        @Override
        protected void handleMessage(Session session, JsonType message) {
            log.info("Abnormally closing test websocket session {} on first request", getNegotiatedSessionId(session));
            if (session instanceof UndertowSession undertowSession) {
                undertowSession.forceClose();
            } else {
                super.handleMessage(session, message);
            }
        }
    }

    private static class ReconnectObservingTrackingClient extends WebsocketTrackingClient {
        private final AtomicInteger openCount = new AtomicInteger();
        private final CountDownLatch reconnected = new CountDownLatch(1);
        private final CountDownLatch closeHandled = new CountDownLatch(1);
        private final AtomicReference<String> closeThreadName = new AtomicReference<>();

        ReconnectObservingTrackingClient(URI endpoint, WebSocketClient client) {
            super(endpoint, client, MessageType.EVENT, null, false);
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            int count = openCount.incrementAndGet();
            super.onOpen(session, config);
            log.info("Observed websocket open #{}: {}", count, getNegotiatedSessionId(session));
            if (count >= 2) {
                reconnected.countDown();
            }
        }

        @Override
        protected void handleClose(Session session, CloseReason closeReason) {
            closeThreadName.set(Thread.currentThread().getName());
            closeHandled.countDown();
            super.handleClose(session, closeReason);
        }

        boolean awaitReconnect(long timeout, TimeUnit unit) throws InterruptedException {
            return reconnected.await(timeout, unit);
        }

        boolean awaitCloseHandled(long timeout, TimeUnit unit) throws InterruptedException {
            return closeHandled.await(timeout, unit);
        }

        String getCloseThreadName() {
            return closeThreadName.get();
        }
    }
}
