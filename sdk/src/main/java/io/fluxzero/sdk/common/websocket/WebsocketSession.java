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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal websocket session contract needed by the Fluxzero runtime clients.
 *
 * <p>The contract is binary-frame oriented because Fluxzero runtime traffic is serialized and compressed before it is
 * sent. Implementations should make close and abort callbacks idempotent so higher-level retry logic can safely react
 * to transport races.</p>
 */
public interface WebsocketSession {
    /**
     * Returns the URI used to open this session.
     */
    URI getRequestURI();

    /**
     * Returns mutable metadata associated with this session.
     *
     * <p>Fluxzero clients use these properties to store negotiated session identifiers, runtime version information,
     * and selected compression settings.</p>
     */
    Map<String, Object> getUserProperties();

    /**
     * Returns the response headers from the successful opening handshake.
     */
    Map<String, List<String>> getHandshakeResponseHeaders();

    /**
     * Returns a snapshot of currently open sessions owned by the same connector.
     */
    Set<WebsocketSession> getOpenSessions();

    /**
     * Returns whether the session is still open for application traffic.
     */
    boolean isOpen();

    /**
     * Sends one complete binary WebSocket message.
     *
     * @param data bytes from the buffer's current position to its limit
     * @throws IOException when the frame cannot be sent
     */
    void sendBinary(ByteBuffer data) throws IOException;

    /**
     * Sends a ping frame.
     *
     * @param applicationData ping payload from the buffer's current position to its limit
     * @throws IOException when the frame cannot be sent
     */
    void sendPing(ByteBuffer applicationData) throws IOException;

    /**
     * Closes the session with a normal close reason.
     *
     * @throws IOException when the close frame cannot be sent
     */
    void close() throws IOException;

    /**
     * Closes the session with a specific close reason.
     *
     * @param closeReason close status and reason to send
     * @throws IOException when the close frame cannot be sent
     */
    void close(WebsocketCloseReason closeReason) throws IOException;

    /**
     * Immediately aborts the underlying transport and reports the given close reason to the endpoint.
     *
     * @param closeReason synthetic close status and reason to report
     */
    void abort(WebsocketCloseReason closeReason);
}
