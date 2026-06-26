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

import java.net.URI;

/**
 * Creates low-level websocket sessions for Fluxzero runtime clients.
 *
 * <p>This interface is intentionally smaller than Jakarta's container API. Fluxzero clients only need a binary
 * message transport with handshake metadata, ping support, lifecycle callbacks, and session state.</p>
 */
public interface WebsocketConnector {
    /**
     * Opens a WebSocket connection to the given URI.
     *
     * @param endpoint endpoint callbacks for the new session
     * @param options  connection options; implementations may accept {@code null} as default options
     * @param uri      target WebSocket URI
     * @return an open WebSocket session
     * @throws Exception when the connection cannot be established
     */
    WebsocketSession connect(WebsocketEndpoint endpoint, WebsocketConnectionOptions options, URI uri) throws Exception;
}
