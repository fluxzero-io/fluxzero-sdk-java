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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

class JettyWebsocketSession implements ServerWebsocketSession {
    private final Session jettySession;
    private final URI requestUri;
    private final Map<String, List<String>> requestParameters;
    private final Map<String, List<String>> requestHeaders;
    private final Map<String, Object> userProperties;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Object sendInitiationLock = new Object();
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);

    JettyWebsocketSession(Session jettySession, JettyWebsocketHandshake handshake) {
        this.jettySession = jettySession;
        this.requestUri = handshake.requestUri();
        this.requestParameters = handshake.requestParameters();
        this.requestHeaders = handshake.requestHeaders();
        this.userProperties = handshake.userProperties();
    }

    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        return requestParameters;
    }

    @Override
    public Map<String, List<String>> getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public boolean isOpen() {
        return open.get() && jettySession.isOpen();
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        await(sendBinaryAsync(data));
    }

    @Override
    public CompletableFuture<Void> sendBinaryAsync(ByteBuffer data) {
        return sendBinaryAsync(data, 0);
    }

    @Override
    public CompletableFuture<Void> sendBinaryAsync(ByteBuffer data, int maxFragmentBytes) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(new ClosedChannelException());
        }
        ByteBuffer message = data.slice();
        if (maxFragmentBytes <= 0 || message.remaining() <= maxFragmentBytes) {
            return sendFrame(callback -> jettySession.sendBinary(message, callback));
        }
        return sendBinaryFragments(message, maxFragmentBytes);
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        ByteBuffer message = applicationData.slice();
        await(sendFrame(callback -> jettySession.sendPing(message, callback)));
    }

    @Override
    public void close() throws IOException {
        close(new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "Normal closure"));
    }

    @Override
    public void close(WebsocketCloseReason closeReason) throws IOException {
        if (open.compareAndSet(true, false)) {
            await(sendFrame(callback -> jettySession.close(closeReason.code(), closeReason.reason(), callback)));
        }
    }

    @Override
    public void abort(WebsocketCloseReason closeReason) {
        open.set(false);
        jettySession.disconnect();
    }

    void markClosed() {
        open.set(false);
    }

    private CompletableFuture<Void> sendFrame(FrameSender sender) {
        synchronized (sendInitiationLock) {
            CompletableFuture<Void> result = sendTail.handle((ignored, error) -> null)
                    .thenCompose(ignored -> {
                        Callback.Completable callback = new Callback.Completable();
                        try {
                            sender.send(callback);
                            return callback;
                        } catch (Throwable e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
            sendTail = result;
            return result;
        }
    }

    private CompletableFuture<Void> sendBinaryFragments(ByteBuffer data, int maxFragmentBytes) {
        synchronized (sendInitiationLock) {
            CompletableFuture<Void> result = sendTail.handle((ignored, error) -> null);
            ByteBuffer remaining = data.slice();
            while (remaining.hasRemaining()) {
                ByteBuffer fragment = nextFragment(remaining, maxFragmentBytes);
                boolean last = !remaining.hasRemaining();
                result = result.thenCompose(ignored -> {
                    Callback.Completable callback = new Callback.Completable();
                    try {
                        jettySession.sendPartialBinary(fragment, last, callback);
                        return callback;
                    } catch (Throwable e) {
                        return CompletableFuture.failedFuture(e);
                    }
                });
            }
            sendTail = result;
            return result;
        }
    }

    private static ByteBuffer nextFragment(ByteBuffer source, int maxFragmentBytes) {
        int length = Math.min(source.remaining(), maxFragmentBytes);
        ByteBuffer fragment = source.slice();
        fragment.limit(length);
        source.position(source.position() + length);
        return fragment;
    }

    private static void await(CompletableFuture<Void> callback) throws IOException {
        try {
            callback.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending websocket frame", e);
        } catch (ExecutionException e) {
            throw new IOException("Failed to send websocket frame", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Timed out while sending websocket frame", e);
        }
    }

    @FunctionalInterface
    private interface FrameSender {
        void send(Callback.Completable callback) throws Exception;
    }
}
