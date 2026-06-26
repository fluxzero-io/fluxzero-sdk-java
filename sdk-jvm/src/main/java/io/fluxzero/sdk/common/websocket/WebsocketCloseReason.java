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

/**
 * WebSocket close information used by the low-level Fluxzero runtime clients.
 *
 * @param code   the numeric WebSocket close status code
 * @param reason the optional close reason, normalized to an empty string when {@code null}
 */
public record WebsocketCloseReason(int code, String reason) {
    /**
     * Standard close code for a normal, intentional shutdown.
     */
    public static final int NORMAL_CLOSURE = 1000;

    /**
     * Standard close code used when an endpoint is going away.
     */
    public static final int GOING_AWAY = 1001;

    /**
     * Synthetic close code used when no status code was received from the peer.
     */
    public static final int NO_STATUS_CODE = 1005;

    /**
     * Standard close code used when the client aborts because an unexpected condition occurred.
     */
    public static final int UNEXPECTED_CONDITION = 1011;

    public WebsocketCloseReason {
        reason = reason == null ? "" : reason;
    }

    @Override
    public String toString() {
        return "CloseReason[%s,%s]".formatted(code, reason);
    }
}
