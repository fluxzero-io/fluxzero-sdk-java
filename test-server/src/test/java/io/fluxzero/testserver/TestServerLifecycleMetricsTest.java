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

import io.fluxzero.common.api.RuntimeLifecycleEvent;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.tracking.MessageStore;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STARTED;
import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STOPPING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
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

    @Test
    void startServerWithoutPortReadsConfiguredPortWithoutShutdownHook() throws Exception {
        String previousFluxzeroPort = System.getProperty("FLUXZERO_PORT");
        String previousFluxPort = System.getProperty("FLUX_PORT");
        String previousPort = System.getProperty("port");
        System.setProperty("FLUXZERO_PORT", "0");
        System.clearProperty("FLUX_PORT");
        System.clearProperty("port");
        Server server = null;
        try {
            server = TestServer.startServer();
            ServerConnector connector = getServerConnector(server);

            assertEquals(0, connector.getPort());
            assertTrue(connector.getLocalPort() > 0);
            assertTrue(server.isRunning());
        } finally {
            if (server != null && server.isRunning()) {
                server.stop();
            }
            restoreProperty("FLUXZERO_PORT", previousFluxzeroPort);
            restoreProperty("FLUX_PORT", previousFluxPort);
            restoreProperty("port", previousPort);
        }
    }

    @Test
    void restartedServerDeliversUpdatesToWaitingTrackers() throws Exception {
        Server first = TestServer.startServer(0);
        first.stop();

        Server second = null;
        WebSocketClient client = null;
        try {
            second = TestServer.startServer(0);
            int port = getServerConnector(second).getLocalPort();
            String consumer = "restart-" + UUID.randomUUID();
            client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl("ws://localhost:" + port)
                    .name("restart-test")
                    .disableMetrics(true)
                    .build());

            ConsumerConfiguration config = ConsumerConfiguration.builder()
                    .name(consumer)
                    .maxWaitDuration(Duration.ofSeconds(2))
                    .build();
            client.getTrackingClient(WEBREQUEST).readAndWait(
                    "warmup", null, config.toBuilder().maxWaitDuration(Duration.ZERO).build());
            client.getTrackingClient(WEBREQUEST).disconnectTracker(consumer, "warmup", false).get(5, SECONDS);

            CompletableFuture<MessageBatch> read = client.getTrackingClient(WEBREQUEST)
                    .read("tracker", null, config);
            Thread.sleep(100L);
            assertFalse(read.isDone(), "Read should be waiting before a message is appended");

            client.getGatewayClient(WEBREQUEST).append(
                    STORED, new Message("after-restart").serialize(serializer)).get(5, SECONDS);

            MessageBatch batch = read.get(5, SECONDS);
            assertEquals(1, batch.getMessages().size());
        } finally {
            if (client != null) {
                client.shutDown();
            }
            if (second != null && second.isRunning()) {
                second.stop();
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

    private static ServerConnector getServerConnector(Server server) {
        return Arrays.stream(server.getConnectors())
                .filter(ServerConnector.class::isInstance)
                .map(ServerConnector.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
