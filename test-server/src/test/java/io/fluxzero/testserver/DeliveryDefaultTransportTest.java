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

package io.fluxzero.testserver;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULT_DELIVERY_GUARANTEE_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryDefaultTransportTest {
    @Test
    void storedDefaultCompletesTransportAndRemainsReadableAfterClientRestart() throws Exception {
        var server = TestServer.startServer(0);
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        String namespace = "delivery-" + UUID.randomUUID();
        try {
            try (Fluxzero sender = application(port, namespace)) {
                sender.apply(f -> {
                    AsyncCompletionScope.runAndAwait(() -> {
                        for (int i = 0; i < 256; i++) Fluxzero.publishEvent("event-" + i);
                    });
                    return null;
                });
            }
            try (Fluxzero reader = application(port, namespace)) {
                var messages = reader.client().getTrackingClient(MessageType.EVENT).readFromIndex(0, 512);
                assertEquals(256, messages.size());
                assertEquals(256, messages.stream().map(m -> m.getMessageId()).distinct().count());
                reader.eventGateway().publish(new Message("after-restart"), Guarantee.DEFAULT)
                        .get(10, TimeUnit.SECONDS);
                assertEquals(257, reader.client().getTrackingClient(MessageType.EVENT).readFromIndex(0, 512).size());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "delivery.benchmark", matches = "true")
    void compareExplicitAndDefaultStoredInBatches() throws Exception {
        var server = TestServer.startServer(0);
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        try (Fluxzero sender = application(port, "delivery-benchmark-" + UUID.randomUUID())) {
            // Both variants use the same transport/durability, with ABBA ordering after warm-up.
            measure(sender, false, 256);
            measure(sender, true, 256);
            for (int repeat = 0; repeat < 3; repeat++) {
                for (boolean defaults : new boolean[]{false, true, true, false}) {
                    measure(sender, defaults, 32);
                }
            }
        } finally {
            server.stop();
        }
    }

    private static void measure(Fluxzero sender, boolean defaults, int batches) {
        long[] times = new long[batches];
        long start = System.nanoTime();
        sender.apply(f -> {
            for (int batch = 0; batch < batches; batch++) {
                long batchStart = System.nanoTime();
                AsyncCompletionScope.runAndAwait(() -> {
                    for (int i = 0; i < 256; i++) {
                        if (defaults) Fluxzero.publishEvent("payload");
                        else f.eventGateway().publish(new Message("payload"), Guarantee.STORED);
                    }
                });
                times[batch] = System.nanoTime() - batchStart;
            }
            return null;
        });
        long elapsed = System.nanoTime() - start;
        Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT, "DELIVERY_BENCH variant=%s messages=%d messagesPerSecond=%.0f batchP50Ms=%.3f batchP95Ms=%.3f%n",
                          defaults ? "DEFAULT" : "STORED", batches * 256,
                          batches * 256.0e9 / elapsed, times[batches / 2] / 1e6,
                          times[Math.min(batches - 1, (int) (batches * 0.95))] / 1e6);
    }

    private static Fluxzero application(int port, String namespace) {
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port).namespace(namespace).name("delivery")
                .id(UUID.randomUUID().toString()).disableMetrics(true).build());
        return DefaultFluxzero.builder().disableShutdownHook().disableTrackingMetrics()
                .replacePropertySource(ignored -> new SimplePropertySource(
                        Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED"))).build(client);
    }
}
