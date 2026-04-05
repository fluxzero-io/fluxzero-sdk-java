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

package io.fluxzero.common.api;

import lombok.Value;

/**
 * A runtime-generated event that indicates a client has successfully connected to the Fluxzero Runtime.
 * <p>
 * This event includes metadata such as the client's name, instance ID, session ID, and the service endpoint
 * it connected to, along with the negotiated SDK and runtime versions when available.
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Track client connection activity for monitoring or alerting</li>
 *   <li>Audit log of system uptime or usage</li>
 * </ul>
 *
 * @see DisconnectEvent
 */
@Value
public class ConnectEvent implements JsonType {

    /**
     * Logical client name, typically set via {@code ClientConfiguration#getClientName()}.
     */
    String client;

    /**
     * Unique client instance ID (e.g., including machine identifier).
     */
    String clientId;

    /**
     * Internal (websocket) session ID used by the Fluxzero Runtime to track this connection.
     */
    String sessionId;

    /**
     * Timestamp when the connection occurred.
     */
    long timestamp = System.currentTimeMillis();

    /**
     * Name of the service the client connected to (e.g., {@code event-sourcing-endpoint}).
     */
    String service;

    /**
     * Version of the Fluxzero SDK used by the client, if advertised during the websocket handshake.
     */
    String sdkVersion;

    /**
     * Version of the Fluxzero Runtime that accepted the connection, if known.
     */
    String runtimeVersion;
}
