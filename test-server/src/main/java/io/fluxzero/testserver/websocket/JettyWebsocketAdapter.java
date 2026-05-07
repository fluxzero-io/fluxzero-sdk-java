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

import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

import java.nio.ByteBuffer;
import java.util.function.Function;

/**
 * Jetty listener that adapts a physical WebSocket connection to the test server endpoint contract.
 * <p>
 * Jetty invokes listener callbacks from outside this package, so this adapter must be public.
 */
public class JettyWebsocketAdapter extends Session.Listener.AbstractAutoDemanding {
    private final Function<ServerWebsocketSession, WebsocketEndpoint> endpointSupplier;
    private final JettyWebsocketHandshake handshake;
    private volatile JettyWebsocketSession session;
    private volatile WebsocketEndpoint endpoint;

    JettyWebsocketAdapter(Function<ServerWebsocketSession, WebsocketEndpoint> endpointSupplier,
                          JettyWebsocketHandshake handshake) {
        this.endpointSupplier = endpointSupplier;
        this.handshake = handshake;
    }

    @Override
    public void onWebSocketOpen(Session jettySession) {
        JettyWebsocketSession openedSession = new JettyWebsocketSession(jettySession, handshake);
        session = openedSession;
        try {
            endpoint = endpointSupplier.apply(openedSession);
            endpoint.onOpen(openedSession);
        } catch (Throwable e) {
            onWebSocketError(e);
            openedSession.abort(new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, e.getMessage()));
        }
    }

    @Override
    public void onWebSocketBinary(ByteBuffer message, Callback callback) {
        try {
            endpoint.onMessage(copyBytes(message), session);
            callback.succeed();
        } catch (Throwable e) {
            callback.fail(e);
            onWebSocketError(e);
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        WebsocketEndpoint endpoint = this.endpoint;
        JettyWebsocketSession session = this.session;
        if (endpoint != null && session != null) {
            endpoint.onError(session, cause);
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        JettyWebsocketSession session = this.session;
        WebsocketEndpoint endpoint = this.endpoint;
        if (session != null) {
            session.markClosed();
        }
        if (endpoint != null && session != null) {
            endpoint.onClose(session, new WebsocketCloseReason(statusCode, reason));
        }
        callback.succeed();
    }

    private static byte[] copyBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
