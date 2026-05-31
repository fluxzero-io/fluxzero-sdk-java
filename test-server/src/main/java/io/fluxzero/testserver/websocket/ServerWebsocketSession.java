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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal server-side WebSocket session contract used by the in-memory Fluxzero test server.
 */
public interface ServerWebsocketSession {
    /**
     * Default upper bound for one physical WebSocket binary frame when a message is sent as fragments.
     */
    int DEFAULT_MAX_BINARY_FRAGMENT_BYTES = 256 * 1024;

    /**
     * Returns the URI that was used for the WebSocket upgrade request.
     */
    URI getRequestURI();

    /**
     * Returns the decoded query parameters from the WebSocket upgrade request.
     */
    Map<String, List<String>> getRequestParameterMap();

    /**
     * Returns the headers from the WebSocket upgrade request.
     */
    Map<String, List<String>> getRequestHeaders();

    /**
     * Returns per-session attributes derived during the WebSocket handshake.
     */
    Map<String, Object> getUserProperties();

    /**
     * Returns whether the underlying transport still accepts frames.
     */
    boolean isOpen();

    /**
     * Sends a complete binary message.
     */
    void sendBinary(ByteBuffer data) throws IOException;

    /**
     * Sends a complete binary message asynchronously.
     *
     * <p>The default delegates to the blocking send so existing lightweight sessions remain compatible.</p>
     */
    default CompletableFuture<Void> sendBinaryAsync(ByteBuffer data) {
        try {
            sendBinary(data);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Sends one complete binary WebSocket message asynchronously, allowing implementations to split the message into
     * WebSocket continuation frames.
     *
     * <p>The default delegates to {@link #sendBinaryAsync(ByteBuffer)} so existing simple implementations keep working.
     * Implementations that support native WebSocket fragmentation should preserve message ordering and complete the
     * returned future only after the final fragment has been accepted by the transport.</p>
     */
    default CompletableFuture<Void> sendBinaryAsync(ByteBuffer data, int maxFragmentBytes) {
        return sendBinaryAsync(data);
    }

    /**
     * Sends a ping frame with optional application data.
     */
    void sendPing(ByteBuffer applicationData) throws IOException;

    /**
     * Closes the session normally.
     */
    void close() throws IOException;

    /**
     * Closes the session with the provided WebSocket close code and reason.
     */
    void close(WebsocketCloseReason closeReason) throws IOException;

    /**
     * Terminates the connection without waiting for a close handshake.
     */
    void abort(WebsocketCloseReason closeReason);
}
