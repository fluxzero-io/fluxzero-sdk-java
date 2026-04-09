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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.web.HttpRequestMethod;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsocketEndpointTest {

    @Test
    void shutdownWaitsForCloseRequestToFinish() throws Exception {
        TestEndpoint endpoint = new TestEndpoint();
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getRequestParameterMap()).thenReturn(Map.of(
                WebsocketEndpoint.clientIdKey, List.of("client-1"),
                WebsocketEndpoint.trackerIdKey, List.of("tracker-1")));
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        doAnswer(invocation -> {
            var reason = invocation.getArgument(0, CloseReason.class);
            CompletableFuture.runAsync(() -> endpoint.onClose(session, reason));
            return null;
        }).when(session).close(any(CloseReason.class));

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

    @SuppressWarnings("unchecked")
    private static void addOpenSession(WebsocketEndpoint endpoint, Session session) throws Exception {
        Field field = WebsocketEndpoint.class.getDeclaredField("openSessions");
        field.setAccessible(true);
        ((Map<String, Session>) field.get(endpoint)).put(session.getId(), session);
    }

    private static void setStarted(WebsocketEndpoint endpoint, boolean value) throws Exception {
        Field field = WebsocketEndpoint.class.getDeclaredField("started");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(endpoint)).set(value);
    }

    private static void setRegistration(WebsocketEndpoint endpoint, Registration registration) throws Exception {
        Field field = WebsocketEndpoint.class.getDeclaredField("registration");
        field.setAccessible(true);
        field.set(endpoint, registration);
    }

    private static Client createClient() {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        GatewayClient gatewayClient = mock(GatewayClient.class);
        when(client.getGatewayClient(MessageType.WEBREQUEST, null)).thenReturn(gatewayClient);
        return client;
    }

    private static class TestEndpoint extends WebsocketEndpoint {
        private final CountDownLatch closeRequestStarted = new CountDownLatch(1);
        private final CountDownLatch allowCloseRequestToFinish = new CountDownLatch(1);
        private final CountDownLatch closeRequestFinished = new CountDownLatch(1);

        private TestEndpoint() {
            super(createClient());
        }

        @Override
        protected CompletableFuture<?> sendCloseRequest(Session session, CloseReason closeReason) {
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
