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

package io.fluxzero.sdk.common.websocket;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forwards real connections and lets the test exercise transport callbacks on a chosen route. */
@RequiredArgsConstructor
public class ReadinessTestConnector implements WebsocketConnector {
    private final WebsocketConnector delegate;
    private final Map<String, LinkedBlockingQueue<Connection>> opened = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> blocked = new ConcurrentHashMap<>();

    @Override
    public WebsocketSession connect(WebsocketEndpoint endpoint, WebsocketConnectionOptions options, URI uri)
            throws Exception {
        String route = uri.getPath().substring(1);
        var refusal = blocked.get(route);
        if (refusal != null) {
            refusal.countDown();
            throw new IOException("Test connection unavailable for " + route);
        }
        WebsocketSession session = delegate.connect(endpoint, options, uri);
        opened.computeIfAbsent(route, ignored -> new LinkedBlockingQueue<>()).add(new Connection(endpoint, session));
        return session;
    }

    public Connection awaitConnection(String route) throws InterruptedException {
        var connection = opened.computeIfAbsent(route, ignored -> new LinkedBlockingQueue<>()).poll(4, TimeUnit.SECONDS);
        assertNotNull(connection, () -> "Proxy did not establish " + route + " without a client request");
        return connection;
    }

    public void block(String route) {
        blocked.put(route, new CountDownLatch(1));
    }

    public void awaitReconnectAttempt(String route) throws InterruptedException {
        assertTrue(blocked.get(route).await(4, TimeUnit.SECONDS), "No idle reconnect attempt for " + route);
    }

    public void allow(String route) {
        blocked.remove(route);
    }

    public record Connection(WebsocketEndpoint endpoint, WebsocketSession session) {
        public String id() {
            return (String) session.getUserProperties().get(AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY);
        }

        public void backpressure(boolean active) {
            ((SdkRuntimeWebsocketEndpoint) endpoint).onRuntimeIngressBackpressure(session, active, null);
        }
    }
}
