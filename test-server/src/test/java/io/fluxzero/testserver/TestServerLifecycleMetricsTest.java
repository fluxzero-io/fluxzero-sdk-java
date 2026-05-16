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
 */

package io.fluxzero.testserver;

import io.fluxzero.common.tracking.MessageStore;
import io.fluxzero.common.api.RuntimeLifecycleEvent;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STARTED;
import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STOPPING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestServerLifecycleMetricsTest {

    private final JacksonSerializer serializer = new JacksonSerializer();

    @Test
    void publishesLifecycleMetricsAfterStartupAndBeforeShutdown() throws Exception {
        int port = availablePort();
        String namespace = "lifecycle-" + UUID.randomUUID();
        Server server = TestServer.startServer(port);
        WebSocketClient client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port)
                .namespace(namespace)
                .name("lifecycle-metrics-test")
                .disableMetrics(true)
                .build());
        try {
            client.getGatewayClient(EVENT).append(STORED, new Message("warmup").serialize(serializer)).get(5, SECONDS);
            MessageStore metricsStore = TestServer.getMetricsMessageStore(namespace);

            assertLifecyclePhase(metricsStore, STARTED, port);

            server.stop();

            assertLifecyclePhase(metricsStore, STOPPING, port);
        } finally {
            client.shutDown();
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    private void assertLifecyclePhase(MessageStore metricsStore, RuntimeLifecycleEvent.Phase phase, int port) {
        assertTrue(lifecycleEvents(metricsStore).stream()
                           .anyMatch(event -> event.getPhase() == phase
                                              && event.getPort() == port
                                              && "FluxzeroTestServer".equals(event.getRuntime())),
                   () -> "Expected " + phase + " lifecycle metric in metrics log");
    }

    private List<RuntimeLifecycleEvent> lifecycleEvents(MessageStore metricsStore) {
        return serializer.deserializeMessages(metricsStore.getBatch(0L, 100, true).stream(), METRICS)
                .map(DeserializingMessage::getPayload)
                .filter(RuntimeLifecycleEvent.class::isInstance)
                .map(RuntimeLifecycleEvent.class::cast)
                .toList();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
