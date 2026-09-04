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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.fluxzero.sdk.configuration.ApplicationProperties.CLIENT_ID_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.LEGACY_CLIENT_ID_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.LEGACY_TASK_ID_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.TASK_ID_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketClientConfigTest {

    @Test
    void taskIdentityPrefixesUniqueClientInstanceIds() {
        withProperties(Map.of(TASK_ID_PROPERTY, "task-123"), () -> {
            WebSocketClient.ClientConfig first = clientConfig();
            WebSocketClient.ClientConfig second = clientConfig();

            assertTrue(first.getId().startsWith("task-123_"));
            assertTrue(second.getId().startsWith("task-123_"));
            assertNotEquals(first.getId(), second.getId());
            assertDoesNotThrow(() -> UUID.fromString(first.getId().substring("task-123_".length())));
        });
    }

    @Test
    void legacyTaskIdentityAlsoPrefixesClientInstanceId() {
        withProperties(Map.of(LEGACY_TASK_ID_PROPERTY, "legacy-task"),
                       () -> assertTrue(clientConfig().getId().startsWith("legacy-task_")));
    }

    @Test
    void configuredClientIdTakesPrecedenceOverTaskIdentity() {
        withProperties(Map.of(CLIENT_ID_PROPERTY, " client-instance ", TASK_ID_PROPERTY, "task-123"),
                       () -> assertEquals("client-instance", clientConfig().getId()));
    }

    @Test
    void legacyConfiguredClientIdTakesPrecedenceOverTaskIdentity() {
        withProperties(Map.of(LEGACY_CLIENT_ID_PROPERTY, "legacy-client", TASK_ID_PROPERTY, "task-123"),
                       () -> assertEquals("legacy-client", clientConfig().getId()));
    }

    @Test
    void blankConfiguredClientIdFallsBackToTaskPrefixedIdentity() {
        withProperties(Map.of(CLIENT_ID_PROPERTY, " \t ", TASK_ID_PROPERTY, "task-123"),
                       () -> assertTrue(clientConfig().getId().startsWith("task-123_")));
    }

    @Test
    void explicitBuilderClientIdTakesPrecedenceOverConfiguredIdentity() {
        withProperties(Map.of(CLIENT_ID_PROPERTY, "configured", TASK_ID_PROPERTY, "task-123"),
                       () -> assertEquals("explicit", clientConfigBuilder().id("explicit").build().getId()));
    }

    @Test
    void derivedConfigurationRetainsClientInstanceId() {
        withProperties(Map.of(TASK_ID_PROPERTY, "task-123"), () -> {
            WebSocketClient.ClientConfig original = clientConfig();

            assertEquals(original.getId(), original.toBuilder().namespace("other").build().getId());
        });
    }

    @Test
    void generatesUuidWithoutConfiguredClientOrTaskIdentity() {
        withProperties(Map.of(), () -> assertDoesNotThrow(() -> UUID.fromString(clientConfig().getId())));
    }

    @Test
    void defaultsToBoundedRuntimeWebSocketCapacity() {
        withProperties(Map.of(), () -> {
            WebSocketClient.ClientConfig config = clientConfig();

            assertEquals(3, config.getMaxConcurrentRuntimeWebSocketMessages());
            assertEquals(128, config.getMaxRetainedRuntimeWebSocketMessages());
            assertEquals(64L * 1024 * 1024, config.getMaxRetainedRuntimeWebSocketBytes());
            assertEquals(8, config.getMaxConcurrentRuntimeResultCompletions());
            assertEquals(Duration.ZERO, config.getRuntimeIngressStallCloseTimeout());
        });
    }

    @Test
    void readsCanonicalRuntimeIngressProperties() {
        withProperties(Map.of(
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_MESSAGES_PROPERTY, "2",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_MESSAGES_PROPERTY, "11",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_BYTES_PROPERTY, "8388608",
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_RESULT_COMPLETIONS_PROPERTY, "5",
                WebSocketClient.ClientConfig.RUNTIME_INGRESS_STALL_CLOSE_TIMEOUT_PROPERTY, "PT45S"), () -> {
            WebSocketClient.ClientConfig config = clientConfig();

            assertEquals(2, config.getMaxConcurrentRuntimeWebSocketMessages());
            assertEquals(11, config.getMaxRetainedRuntimeWebSocketMessages());
            assertEquals(8L * 1024 * 1024, config.getMaxRetainedRuntimeWebSocketBytes());
            assertEquals(5, config.getMaxConcurrentRuntimeResultCompletions());
            assertEquals(Duration.ofSeconds(45), config.getRuntimeIngressStallCloseTimeout());
        });
    }

    @Test
    void canonicalRuntimeIngressPropertiesTakePrecedenceOverWebSocketAliases() {
        withProperties(Map.of(
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_MESSAGES_PROPERTY, "2",
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_ALIAS, "4",
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY, "6"),
                       () -> assertEquals(2, clientConfig().getMaxConcurrentRuntimeWebSocketMessages()));
    }

    @Test
    void readsDottedWebSocketAliases() {
        withProperties(Map.of(
                WebSocketClient.ClientConfig.MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_ALIAS, "2",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_ALIAS, "12",
                WebSocketClient.ClientConfig.MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_ALIAS, "4194304"), () -> {
            WebSocketClient.ClientConfig config = clientConfig();

            assertEquals(2, config.getMaxConcurrentRuntimeWebSocketMessages());
            assertEquals(12, config.getMaxRetainedRuntimeWebSocketMessages());
            assertEquals(4L * 1024 * 1024, config.getMaxRetainedRuntimeWebSocketBytes());
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
        IllegalArgumentException completionError = assertThrows(IllegalArgumentException.class,
                                                                 () -> WebSocketClient.newInstance(
                                                                         clientConfigBuilder()
                                                                                 .maxConcurrentRuntimeResultCompletions(0)
                                                                                 .build()));
        IllegalArgumentException stallError = assertThrows(IllegalArgumentException.class,
                                                            () -> WebSocketClient.newInstance(
                                                                    clientConfigBuilder()
                                                                            .runtimeIngressStallCloseTimeout(
                                                                                    Duration.ofSeconds(-1))
                                                                            .build()));

        assertTrue(concurrencyError.getMessage().contains("maxConcurrentRuntimeWebSocketMessages"));
        assertTrue(messageError.getMessage().contains("maxRetainedRuntimeWebSocketMessages"));
        assertTrue(byteError.getMessage().contains("maxRetainedRuntimeWebSocketBytes"));
        assertTrue(completionError.getMessage().contains("maxConcurrentRuntimeResultCompletions"));
        assertTrue(stallError.getMessage().contains("runtimeIngressStallCloseTimeout"));
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
