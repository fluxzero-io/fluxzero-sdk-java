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

package io.fluxzero.sdk.common.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Optional.ofNullable;

class JdkWebSocketSession implements WebsocketSession {
    private final JdkWebsocketConnector connector;
    private final WebsocketEndpoint endpoint;
    private final JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse;
    private final URI requestUri;
    private final Map<String, Object> userProperties = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> openFuture = new CompletableFuture<>();
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean closeNotified = new AtomicBoolean();
    private final Object binaryMessageLock = new Object();
    /*
     * Keep calls into java.net.http.WebSocket ordered, but do not hold this monitor while waiting for the returned
     * CompletableFuture to complete. Slow network completion should not make the monitor itself a throughput bottleneck.
     */
    private final Object sendInitiationLock = new Object();
    private volatile ByteArrayOutputStream binaryMessage = new ByteArrayOutputStream();
    private volatile WebSocket webSocket;

    JdkWebSocketSession(JdkWebsocketConnector connector, WebsocketEndpoint endpoint,
                        WebsocketConnectionOptions options, URI requestUri,
                        JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse) {
        this.connector = connector;
        this.endpoint = endpoint;
        this.handshakeResponse = handshakeResponse;
        this.requestUri = requestUri;
        this.userProperties.putAll(options.userProperties());
    }

    WebSocket.Listener createListener() {
        return new Listener();
    }

    void awaitOpen() throws IOException {
        try {
            openFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening websocket endpoint " + requestUri, e);
        } catch (ExecutionException e) {
            throw new IOException("Websocket endpoint failed to open " + requestUri, e.getCause());
        }
    }

    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public Map<String, List<String>> getHandshakeResponseHeaders() {
        return handshakeResponse.headers();
    }

    @Override
    public Set<WebsocketSession> getOpenSessions() {
        return connector.getOpenSessions();
    }

    @Override
    public boolean isOpen() {
        WebSocket webSocket = this.webSocket;
        return open.get() && webSocket != null && !webSocket.isInputClosed() && !webSocket.isOutputClosed();
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        await(sendBinary(copyBuffer(data), true), 0);
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        await(sendPingFrame(copyBuffer(applicationData)), 0);
    }

    @Override
    public void close() throws IOException {
        close(new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "Normal closure"));
    }

    @Override
    public void close(WebsocketCloseReason closeReason) throws IOException {
        WebSocket webSocket = this.webSocket;
        if (!closeNotified.compareAndSet(false, true)) {
            return;
        }
        open.set(false);
        try {
            if (webSocket != null && !webSocket.isOutputClosed()) {
                await(sendClose(webSocket, closeReason), 0);
            }
        } finally {
            connector.removeOpenSession(this);
            endpoint.onClose(this, closeReason);
        }
    }

    @Override
    public void abort(WebsocketCloseReason closeReason) {
        WebSocket webSocket = this.webSocket;
        if (webSocket != null) {
            webSocket.abort();
        }
        notifyClose(closeReason);
    }

    private CompletableFuture<WebSocket> sendClose(WebSocket webSocket, WebsocketCloseReason closeReason) {
        synchronized (sendInitiationLock) {
            return webSocket.sendClose(closeReason.code(), closeReason.reason());
        }
    }

    private CompletableFuture<Void> sendBinary(ByteBuffer data, boolean last) {
        synchronized (sendInitiationLock) {
            return requireWebSocket().sendBinary(data, last).thenApply(ignored -> null);
        }
    }

    private CompletableFuture<Void> sendPingFrame(ByteBuffer data) {
        synchronized (sendInitiationLock) {
            return requireWebSocket().sendPing(data).thenApply(ignored -> null);
        }
    }

    private CompletableFuture<Void> sendPong(ByteBuffer data) {
        synchronized (sendInitiationLock) {
            return requireWebSocket().sendPong(data).thenApply(ignored -> null);
        }
    }

    private WebSocket requireWebSocket() {
        return ofNullable(webSocket).orElseThrow(() ->
                new IllegalStateException("Websocket connection to " + requestUri + " has not opened yet"));
    }

    private void notifyOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        open.set(true);
        connector.addOpenSession(this);
        try {
            endpoint.onOpen(this);
            openFuture.complete(null);
            webSocket.request(1);
        } catch (Throwable e) {
            open.set(false);
            openFuture.completeExceptionally(e);
            connector.removeOpenSession(this);
            try {
                endpoint.onError(this, e);
            } catch (Throwable ignored) {
            }
            webSocket.abort();
        }
    }

    private void notifyClose(WebsocketCloseReason closeReason) {
        open.set(false);
        if (closeNotified.compareAndSet(false, true)) {
            connector.removeOpenSession(this);
            endpoint.onClose(this, closeReason);
        }
    }

    private void notifyError(Throwable error) {
        open.set(false);
        try {
            endpoint.onError(this, error);
        } finally {
            notifyClose(new WebsocketCloseReason(
                    WebsocketCloseReason.UNEXPECTED_CONDITION,
                    ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName())));
        }
    }

    private void requestNext(WebSocket webSocket) {
        if (!closeNotified.get()) {
            webSocket.request(1);
        }
    }

    private void handleBinary(ByteBuffer message, boolean last) {
        byte[] bytes = appendBinary(message, last);
        if (bytes != null) {
            endpoint.onMessage(bytes, this);
        }
    }

    private void handlePong(ByteBuffer message) {
        endpoint.onPong(copyBuffer(message), this);
    }

    private byte[] appendBinary(ByteBuffer message, boolean last) {
        byte[] fragment = copyBytes(message);
        synchronized (binaryMessageLock) {
            try {
                binaryMessage.write(fragment);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to buffer websocket binary message", e);
            }
            if (!last) {
                return null;
            }
            byte[] bytes = binaryMessage.toByteArray();
            binaryMessage = new ByteArrayOutputStream();
            return bytes;
        }
    }

    private static ByteBuffer copyBuffer(ByteBuffer buffer) {
        return ByteBuffer.wrap(copyBytes(buffer));
    }

    private static byte[] copyBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static void await(CompletableFuture<?> future, long timeoutMillis) throws IOException {
        try {
            if (timeoutMillis > 0) {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending websocket frame", e);
        } catch (ExecutionException e) {
            throw new IOException("Failed to send websocket frame", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Timed out while sending websocket frame", e);
        }
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            notifyOpen(webSocket);
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                handleBinary(data, last);
            } catch (Throwable e) {
                notifyError(e);
            } finally {
                requestNext(webSocket);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket webSocket, ByteBuffer message) {
            try {
                sendPong(copyBuffer(message)).exceptionally(e -> {
                    notifyError(e);
                    return null;
                });
            } finally {
                requestNext(webSocket);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onPong(WebSocket webSocket, ByteBuffer message) {
            try {
                handlePong(message);
            } catch (Throwable e) {
                notifyError(e);
            } finally {
                requestNext(webSocket);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            notifyClose(new WebsocketCloseReason(statusCode, reason));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            notifyError(error);
        }
    }
}
