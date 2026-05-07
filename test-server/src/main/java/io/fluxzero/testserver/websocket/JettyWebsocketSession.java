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
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        Callback.Completable callback = new Callback.Completable();
        synchronized (sendInitiationLock) {
            jettySession.sendBinary(copyBuffer(data), callback);
        }
        await(callback);
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        Callback.Completable callback = new Callback.Completable();
        synchronized (sendInitiationLock) {
            jettySession.sendPing(copyBuffer(applicationData), callback);
        }
        await(callback);
    }

    @Override
    public void close() throws IOException {
        close(new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "Normal closure"));
    }

    @Override
    public void close(WebsocketCloseReason closeReason) throws IOException {
        if (open.compareAndSet(true, false)) {
            Callback.Completable callback = new Callback.Completable();
            synchronized (sendInitiationLock) {
                jettySession.close(closeReason.code(), closeReason.reason(), callback);
            }
            await(callback);
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

    private static ByteBuffer copyBuffer(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return ByteBuffer.wrap(bytes);
    }

    private static void await(Callback.Completable callback) throws IOException {
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
}
