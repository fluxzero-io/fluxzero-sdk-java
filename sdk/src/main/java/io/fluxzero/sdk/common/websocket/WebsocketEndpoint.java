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

import java.nio.ByteBuffer;

/**
 * Callback contract for low-level Fluxzero runtime websocket sessions.
 *
 * <p>Implementations should return quickly from these callbacks. Higher-level Fluxzero clients dispatch expensive
 * result handling onto worker executors so transport callbacks do not block the underlying WebSocket implementation.</p>
 */
public interface WebsocketEndpoint {
    /**
     * Called after the opening handshake has completed and the session metadata is available.
     *
     * @param session the newly opened session
     */
    void onOpen(WebsocketSession session);

    /**
     * Called when a complete binary message has been received.
     *
     * @param bytes   the full binary message payload
     * @param session the session that received the message
     */
    void onMessage(byte[] bytes, WebsocketSession session);

    /**
     * Called when a pong frame has been received.
     *
     * @param data    the pong application data
     * @param session the session that received the pong
     */
    void onPong(ByteBuffer data, WebsocketSession session);

    /**
     * Called once when a session closes or is aborted.
     *
     * @param session     the closed session
     * @param closeReason the close status and optional reason
     */
    void onClose(WebsocketSession session, WebsocketCloseReason closeReason);

    /**
     * Called when the underlying WebSocket implementation reports an error.
     *
     * @param session the session that failed
     * @param error   the reported error
     */
    void onError(WebsocketSession session, Throwable error);
}
