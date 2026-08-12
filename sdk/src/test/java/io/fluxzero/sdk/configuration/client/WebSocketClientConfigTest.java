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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketClientConfigTest {

    @Test
    void defaultsToBoundedRuntimeWebSocketCapacity() {
        withProperties(Map.of(), () -> {
            WebSocketClient.ClientConfig config = clientConfig();

            assertEquals(3, config.getMaxConcurrentRuntimeWebSocketMessages());
            assertEquals(19, config.getMaxRetainedRuntimeWebSocketMessages());
            assertEquals(16L * 1024 * 1024, config.getMaxRetainedRuntimeWebSocketBytes());
        });
    }

    @Test
    void readsRuntimeWebSocketCapacityFromApplicationProperties() {
        withProperties(Map.of(
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY, "2",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY, "11",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_PROPERTY, "8388608"), () -> {
            WebSocketClient.ClientConfig config = clientConfig();

            assertEquals(2, config.getMaxConcurrentRuntimeWebSocketMessages());
            assertEquals(11, config.getMaxRetainedRuntimeWebSocketMessages());
            assertEquals(8L * 1024 * 1024, config.getMaxRetainedRuntimeWebSocketBytes());
        });
    }

    @Test
    void explicitRuntimeWebSocketCapacityOverridesApplicationProperties() {
        withProperties(Map.of(
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY, "2",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY, "11",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_PROPERTY, "8388608"), () -> {
            WebSocketClient.ClientConfig config = clientConfigBuilder()
                    .maxConcurrentRuntimeWebSocketMessages(4)
                    .maxRetainedRuntimeWebSocketMessages(28)
                    .maxRetainedRuntimeWebSocketBytes(32L * 1024 * 1024)
                    .build();

            assertEquals(4, config.getMaxConcurrentRuntimeWebSocketMessages());
            assertEquals(28, config.getMaxRetainedRuntimeWebSocketMessages());
            assertEquals(32L * 1024 * 1024, config.getMaxRetainedRuntimeWebSocketBytes());
        });
    }

    @Test
    void rejectsInvalidRuntimeWebSocketCapacityAtClientConstruction() {
        IllegalArgumentException concurrencyError = assertThrows(IllegalArgumentException.class,
                                                                  () -> WebSocketClient.newInstance(
                                                                          clientConfigBuilder()
                                                                                  .maxConcurrentRuntimeWebSocketMessages(0)
                                                                                  .build()));
        IllegalArgumentException messageError = assertThrows(IllegalArgumentException.class,
                                                              () -> WebSocketClient.newInstance(
                                                                      clientConfigBuilder()
                                                                              .maxConcurrentRuntimeWebSocketMessages(4)
                                                                              .maxRetainedRuntimeWebSocketMessages(3)
                                                                              .build()));
        IllegalArgumentException byteError = assertThrows(IllegalArgumentException.class,
                                                           () -> WebSocketClient.newInstance(
                                                                   clientConfigBuilder()
                                                                           .maxRetainedRuntimeWebSocketBytes(0)
                                                                           .build()));

        assertTrue(concurrencyError.getMessage().contains("maxConcurrentRuntimeWebSocketMessages"));
        assertTrue(messageError.getMessage().contains("maxRetainedRuntimeWebSocketMessages"));
        assertTrue(byteError.getMessage().contains("maxRetainedRuntimeWebSocketBytes"));
    }

    @Test
    void acceptsZeroPendingCapacityAndIntegerLimitWithoutOverflow() {
        assertDoesNotThrow(() -> WebSocketClient.newInstance(clientConfigBuilder()
                                                                      .maxConcurrentRuntimeWebSocketMessages(
                                                                              Integer.MAX_VALUE)
                                                                      .maxRetainedRuntimeWebSocketMessages(
                                                                              Integer.MAX_VALUE)
                                                                      .maxRetainedRuntimeWebSocketBytes(Long.MAX_VALUE)
                                                                      .build()));
    }

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
