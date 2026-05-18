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

package io.fluxzero.proxy;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyWebsocketEndpointTest {

    @Test
    void shutdownWaitsForCloseRequestToFinish() throws Exception {
        TestEndpoint endpoint = new TestEndpoint();
        ProxyWebsocketSession session = mock(ProxyWebsocketSession.class);
        prepareSession(endpoint, session);

        addOpenSession(endpoint, session);
        setStarted(endpoint, true);
        setRegistration(endpoint, Registration.noOp());

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(endpoint::shutDown);

        assertTrue(endpoint.closeRequestStarted.await(1, TimeUnit.SECONDS),
                   "Expected shutdown to trigger websocket close handling");
        assertFalse(shutdown.isDone(), "Shutdown returned before the websocket close request completed");

        endpoint.allowCloseRequestToFinish.countDown();
        shutdown.get(1, TimeUnit.SECONDS);
        assertTrue(endpoint.closeRequestFinished.await(1, TimeUnit.SECONDS),
                   "Expected websocket close request to finish before shutdown completes");
    }

    @Test
    void serverShutdownUsesShorterCloseTimeout() throws Exception {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        when(gatewayClient.append(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        RequestHandler requestHandler = mock(RequestHandler.class);
        ProxyWebsocketEndpoint endpoint = new ProxyWebsocketEndpoint(createClient(gatewayClient), requestHandler);
        ProxyWebsocketSession session = mock(ProxyWebsocketSession.class);
        prepareSession(endpoint, session);

        addOpenSession(endpoint, session);
        setStarted(endpoint, true);
        setRegistration(endpoint, Registration.noOp());

        long start = System.nanoTime();
        endpoint.shutDown(ProxyRequestHandler.SERVER_SHUTDOWN_CLOSE_TIMEOUT, false, false);
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        verify(gatewayClient).append(eq(Guarantee.NONE), any());
        verify(requestHandler, never()).sendRequest(any(), any(), any(Duration.class));
        assertTrue(durationMillis < 2_000,
                   "Expected server shutdown to use the shorter websocket close timeout");
    }

    @Test
    void closeRequestUsesCloseNotificationTimeout() throws Exception {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        RequestHandler requestHandler = mock(RequestHandler.class);
        when(requestHandler.sendRequest(any(), any(), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        ProxyWebsocketEndpoint endpoint = new ProxyWebsocketEndpoint(createClient(gatewayClient), requestHandler);
        ProxyWebsocketSession session = mock(ProxyWebsocketSession.class);
        prepareSession(endpoint, session);

        endpoint.sendCloseRequest(session, new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "done"))
                .get(1, TimeUnit.SECONDS);

        verify(requestHandler).sendRequest(any(), any(), eq(ProxyWebsocketEndpoint.CLOSE_NOTIFICATION_TIMEOUT));
    }

    @Test
    void sendBacklogFailurePublishesBackpressureMetric() throws Exception {
        GatewayClient requestGateway = mock(GatewayClient.class);
        GatewayClient metricsGateway = mock(GatewayClient.class);
        when(metricsGateway.append(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        ProxyWebsocketEndpoint endpoint = new ProxyWebsocketEndpoint(
                createClient(requestGateway, metricsGateway, "tenant-a"), mock(RequestHandler.class));
        ProxyWebsocketSession session = mock(ProxyWebsocketSession.class);
        prepareSession(endpoint, session);
        when(session.sendText(any())).thenReturn(CompletableFuture.failedFuture(
                new WebsocketSendBacklogExceededException("session-1", "client-1", "tracker-1", "tenant-a", 2, 2)));

        addOpenSession(endpoint, session);
        SerializedMessage result = new SerializedMessage(
                new Data<>("slow".getBytes(StandardCharsets.UTF_8), String.class.getName(), 0),
                Metadata.of(WebRequest.sessionIdKey, "session-1"), "message-1", 1L);

        endpoint.handleResultMessages(List.of(result));

        ArgumentCaptor<SerializedMessage> metric = ArgumentCaptor.forClass(SerializedMessage.class);
        verify(metricsGateway).append(eq(Guarantee.NONE), metric.capture());
        assertEquals(ProxyWebsocketBackpressureEvent.class.getName(), metric.getValue().getData().getType());
        ProxyWebsocketBackpressureEvent event = new JacksonSerializer()
                .deserialize(metric.getValue().getData(), ProxyWebsocketBackpressureEvent.class);
        assertEquals("session-1", event.sessionId());
        assertEquals("client-1", event.clientId());
        assertEquals("tracker-1", event.trackerId());
        assertEquals("tenant-a", event.namespace());
        assertEquals(2, event.pendingSends());
        assertEquals(2, event.maxPendingSends());
        assertEquals("session-1", metric.getValue().getMetadata().get("sessionId"));
        assertEquals("client-1", metric.getValue().getMetadata().get("clientId"));
        assertEquals("tenant-a", metric.getValue().getMetadata().get("namespace"));
    }

    private static void prepareSession(ProxyWebsocketEndpoint endpoint, ProxyWebsocketSession session) throws Exception {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getRequestParameterMap()).thenReturn(Map.of(
                ProxyWebsocketEndpoint.clientIdKey, List.of("client-1"),
                ProxyWebsocketEndpoint.trackerIdKey, List.of("tracker-1")));
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        doAnswer(invocation -> {
            var reason = invocation.getArgument(0, WebsocketCloseReason.class);
            CompletableFuture.runAsync(() -> endpoint.onClose(session, reason));
            return CompletableFuture.completedFuture(null);
        }).when(session).close(any(WebsocketCloseReason.class));
    }

    @SuppressWarnings("unchecked")
    private static void addOpenSession(ProxyWebsocketEndpoint endpoint, ProxyWebsocketSession session) throws Exception {
        Field field = ProxyWebsocketEndpoint.class.getDeclaredField("openSessions");
        field.setAccessible(true);
        ((Map<String, ProxyWebsocketSession>) field.get(endpoint)).put(session.getId(), session);
    }

    private static void setStarted(ProxyWebsocketEndpoint endpoint, boolean value) throws Exception {
        Field field = ProxyWebsocketEndpoint.class.getDeclaredField("started");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(endpoint)).set(value);
    }

    private static void setRegistration(ProxyWebsocketEndpoint endpoint, Registration registration) throws Exception {
        Field field = ProxyWebsocketEndpoint.class.getDeclaredField("registration");
        field.setAccessible(true);
        field.set(endpoint, registration);
    }

    private static Client createClient() {
        return createClient(mock(GatewayClient.class));
    }

    private static Client createClient(GatewayClient gatewayClient) {
        return createClient(gatewayClient, mock(GatewayClient.class), null);
    }

    private static Client createClient(GatewayClient gatewayClient, GatewayClient metricsGateway, String namespace) {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        when(client.getGatewayClient(MessageType.WEBREQUEST, null)).thenReturn(gatewayClient);
        when(client.getGatewayClient(MessageType.METRICS, null)).thenReturn(metricsGateway);
        when(client.namespace()).thenReturn(namespace);
        return client;
    }

    private static class TestEndpoint extends ProxyWebsocketEndpoint {
        private final CountDownLatch closeRequestStarted = new CountDownLatch(1);
        private final CountDownLatch allowCloseRequestToFinish = new CountDownLatch(1);
        private final CountDownLatch closeRequestFinished = new CountDownLatch(1);

        private TestEndpoint() {
            super(createClient(), mock(RequestHandler.class));
        }

        @Override
        protected CompletableFuture<?> sendCloseRequest(ProxyWebsocketSession session,
                                                        WebsocketCloseReason closeReason) {
            closeRequestStarted.countDown();
            try {
                assertTrue(allowCloseRequestToFinish.await(1, TimeUnit.SECONDS),
                           "Timed out waiting to release the websocket close request");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting to release the websocket close request", e);
            } finally {
                closeRequestFinished.countDown();
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
