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
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
class JettyProxyWebsocketSession implements ProxyWebsocketSession {
    private final String id = UUID.randomUUID().toString();
    private final Session jettySession;
    private final Map<String, List<String>> requestParameters;
    private final Map<String, Object> userProperties;
    private final int maxPendingSends;
    private final String namespace;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Object sendInitiationLock = new Object();
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private int pendingSends;

    JettyProxyWebsocketSession(Session jettySession, Map<String, List<String>> requestParameters,
                               int maxPendingSends) {
        this(jettySession, requestParameters, maxPendingSends, null);
    }

    JettyProxyWebsocketSession(Session jettySession, Map<String, List<String>> requestParameters,
                               int maxPendingSends, String namespace) {
        if (maxPendingSends < 1) {
            throw new IllegalArgumentException("maxPendingSends must be >= 1");
        }
        this.jettySession = jettySession;
        this.requestParameters = requestParameters;
        this.userProperties = new java.util.concurrent.ConcurrentHashMap<>();
        this.maxPendingSends = maxPendingSends;
        this.namespace = namespace;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        return requestParameters;
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
    public CompletableFuture<Void> sendBinary(ByteBuffer data) {
        ByteBuffer payload = copyBuffer(data);
        return enqueueSend(callback -> jettySession.sendBinary(payload, callback));
    }

    @Override
    public CompletableFuture<Void> sendText(String text) {
        return enqueueSend(callback -> jettySession.sendText(text, callback));
    }

    @Override
    public CompletableFuture<Void> sendPing(ByteBuffer applicationData) {
        ByteBuffer payload = copyBuffer(applicationData);
        return enqueueSend(callback -> jettySession.sendPing(payload, callback));
    }

    @Override
    public CompletableFuture<Void> close(WebsocketCloseReason closeReason) {
        if (!open.compareAndSet(true, false)) {
            return CompletableFuture.completedFuture(null);
        }
        return enqueueClose(callback -> {
            if (jettySession.isOpen()) {
                jettySession.close(closeReason.code(), closeReason.reason(), callback);
            } else {
                callback.succeed();
            }
        });
    }

    @Override
    public void abort(WebsocketCloseReason closeReason) {
        open.set(false);
        jettySession.disconnect();
    }

    void markClosed() {
        open.set(false);
    }

    private CompletableFuture<Void> enqueueSend(SendOperation operation) {
        synchronized (sendInitiationLock) {
            if (!isOpen()) {
                return CompletableFuture.failedFuture(new ClosedChannelException());
            }
            if (pendingSends >= maxPendingSends) {
                WebsocketSendBacklogExceededException failure = new WebsocketSendBacklogExceededException(
                        id, requestParameter(ProxyWebsocketEndpoint.clientIdKey),
                        requestParameter(ProxyWebsocketEndpoint.trackerIdKey), namespace, pendingSends, maxPendingSends);
                log.warn("Disconnecting websocket session {} for client {} in namespace {} because {} outgoing sends "
                         + "are pending (configured max {})", failure.sessionId(), failure.clientId(),
                         failure.namespace(), failure.pendingSends(), failure.maxPendingSends());
                abort(new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, failure.getMessage()));
                return CompletableFuture.failedFuture(failure);
            }
            pendingSends++;
            CompletableFuture<Void> result = sendChain.thenCompose(ignored -> sendNow(operation));
            sendChain = result.whenComplete((ignored, error) -> {
                decrementPendingSends();
                if (error != null) {
                    open.set(false);
                }
            });
            return result;
        }
    }

    private CompletableFuture<Void> enqueueClose(SendOperation operation) {
        synchronized (sendInitiationLock) {
            CompletableFuture<Void> result = sendChain.handle((ignored, error) -> null)
                    .thenCompose(ignored -> sendNow(operation));
            sendChain = result.whenComplete((ignored, error) -> open.set(false));
            return result;
        }
    }

    private CompletableFuture<Void> sendNow(SendOperation operation) {
        if (!jettySession.isOpen()) {
            return CompletableFuture.failedFuture(new ClosedChannelException());
        }
        Callback.Completable callback = new Callback.Completable();
        try {
            operation.send(callback);
        } catch (Throwable e) {
            callback.fail(e);
        }
        return callback;
    }

    private void decrementPendingSends() {
        synchronized (sendInitiationLock) {
            pendingSends--;
        }
    }

    private static ByteBuffer copyBuffer(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return ByteBuffer.wrap(bytes);
    }

    private String requestParameter(String name) {
        return Optional.ofNullable(requestParameters.get(name)).filter(values -> !values.isEmpty())
                .map(List::getFirst).orElse(null);
    }

    @FunctionalInterface
    private interface SendOperation {
        void send(Callback callback);
    }
}
