/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.configuration.client;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketClientConfigTest {

    @Test
    void defaultsToExistingMaxInFlightWebSocketBytesWhenPropertyIsUnset() {
        withProperties(Map.of(), () -> assertEquals(
                WebSocketClient.ClientConfig.DEFAULT_MAX_IN_FLIGHT_WEBSOCKET_BYTES,
                clientConfig().getMaxInFlightWebSocketBytes()));
    }

    @Test
    void readsMaxInFlightWebSocketBytesFromApplicationProperties() {
        withProperties(Map.of(WebSocketClient.ClientConfig.MAX_IN_FLIGHT_WEBSOCKET_BYTES_PROPERTY, "4096"),
                       () -> assertEquals(4096, clientConfig().getMaxInFlightWebSocketBytes()));
    }

    @Test
    void explicitMaxInFlightWebSocketBytesOverridesApplicationProperty() {
        withProperties(Map.of(WebSocketClient.ClientConfig.MAX_IN_FLIGHT_WEBSOCKET_BYTES_PROPERTY, "4096"),
                       () -> assertEquals(8192, clientConfigBuilder()
                               .maxInFlightWebSocketBytes(8192)
                               .build()
                               .getMaxInFlightWebSocketBytes()));
    }

    private static WebSocketClient.ClientConfig clientConfig() {
        return clientConfigBuilder().build();
    }

    private static WebSocketClient.ClientConfig.ClientConfigBuilder clientConfigBuilder() {
        return WebSocketClient.ClientConfig.builder()
                .name("test-app")
                .runtimeBaseUrl("ws://localhost");
    }

    private static void withProperties(Map<String, String> properties, Runnable task) {
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(ignored -> new SimplePropertySource(properties))
                .disableShutdownHook()
                .disableKeepalive()
                .build(LocalClient.newInstance());
        try {
            fluxzero.execute(ignored -> task.run());
        } finally {
            fluxzero.close(true);
        }
    }
}
