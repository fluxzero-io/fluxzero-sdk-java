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

package io.fluxzero.proxy;

import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Jetty listener that adapts a physical websocket connection to the proxy websocket endpoint contract.
 */
public class JettyProxyWebsocketAdapter extends Session.Listener.AbstractAutoDemanding {
    private final ProxyWebsocketEndpoint endpoint;
    private final Map<String, List<String>> requestParameters;
    private final int maxPendingSends;
    private final String namespace;
    private volatile JettyProxyWebsocketSession session;

    JettyProxyWebsocketAdapter(ProxyWebsocketEndpoint endpoint, Map<String, List<String>> requestParameters,
                               int maxPendingSends, String namespace) {
        this.endpoint = endpoint;
        this.requestParameters = requestParameters;
        this.maxPendingSends = maxPendingSends;
        this.namespace = namespace;
    }

    @Override
    public void onWebSocketOpen(Session jettySession) {
        JettyProxyWebsocketSession openedSession =
                new JettyProxyWebsocketSession(jettySession, requestParameters, maxPendingSends, namespace);
        session = openedSession;
        try {
            endpoint.onOpen(openedSession);
        } catch (Throwable e) {
            onWebSocketError(e);
            openedSession.abort(new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, e.getMessage()));
        }
    }

    @Override
    public void onWebSocketBinary(ByteBuffer message, Callback callback) {
        JettyProxyWebsocketSession session = this.session;
        try {
            endpoint.onBinary(session, copyBytes(message));
            callback.succeed();
        } catch (Throwable e) {
            callback.fail(e);
            onWebSocketError(e);
        }
    }

    @Override
    public void onWebSocketText(String message) {
        JettyProxyWebsocketSession session = this.session;
        try {
            endpoint.onText(session, message);
        } catch (Throwable e) {
            onWebSocketError(e);
        }
    }

    @Override
    public void onWebSocketPong(ByteBuffer message) {
        JettyProxyWebsocketSession session = this.session;
        if (session != null) {
            endpoint.onPong(session, copyBuffer(message));
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        JettyProxyWebsocketSession session = this.session;
        if (session != null) {
            endpoint.onError(session, cause);
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        JettyProxyWebsocketSession session = this.session;
        if (session != null) {
            session.markClosed();
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

    private static ByteBuffer copyBuffer(ByteBuffer buffer) {
        return ByteBuffer.wrap(copyBytes(buffer));
    }
}
