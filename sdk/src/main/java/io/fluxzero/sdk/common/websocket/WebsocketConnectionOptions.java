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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connection settings for the low-level runtime websocket connector.
 *
 * <p>The collections passed to this record are defensively copied. Header names are passed through unchanged because
 * the underlying connector forwards them to the runtime handshake as supplied.</p>
 *
 * @param headers        request headers to add to the WebSocket opening handshake
 * @param userProperties mutable session metadata that should be available to endpoint callbacks after the session opens
 * @param connectTimeout optional timeout for establishing the WebSocket connection
 * @param subprotocols   optional WebSocket subprotocols to advertise, ordered by preference
 */
public record WebsocketConnectionOptions(Map<String, List<String>> headers, Map<String, Object> userProperties,
                                         Duration connectTimeout, List<String> subprotocols) {

    public WebsocketConnectionOptions {
        headers = copyHeaders(headers);
        userProperties = userProperties == null ? Map.of() : Map.copyOf(userProperties);
        subprotocols = subprotocols == null ? List.of() : List.copyOf(subprotocols);
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
        return Map.copyOf(copy);
    }
}
