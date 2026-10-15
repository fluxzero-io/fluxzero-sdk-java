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

package io.fluxzero.testserver;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.tracking.WebSocketTracker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.METRICS;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end metric counts, including emitted SDK metrics; ordinary CI skips these measurement workloads. */
@EnabledIfSystemProperty(named = "fluxzero.test.consumerFilterBenchmark", matches = "true")
class ConsumerMetricsBenchmarkTest {
    @Test
    void measuresEmittedMetricsForSelectiveConsumers() throws Exception {
        var observed = new java.util.concurrent.atomic.AtomicReference<Consumer<WebSocketTracker>>(ignored -> {});
        var server = TestServer.startServer(0, tracker -> observed.get().accept(tracker));
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        try {
            for (boolean filtered : List.of(false, true)) {
                int consumers = 12;
                String prefix = "selective-" + filtered + "-";
                var following = new CountDownLatch(consumers);
                Set<String> caughtUp = ConcurrentHashMap.newKeySet();
                var lastIndex = new AtomicLong(Long.MAX_VALUE);
                observed.set(tracker -> {
                    if (tracker.getConsumerName().startsWith(prefix)
                        && java.util.Objects.equals(tracker.getLastTrackerIndex(), lastIndex.get())
                        && caughtUp.add(tracker.getConsumerName())) following.countDown();
                });
                try (Fluxzero app = application(port, prefix, consumers, filtered, Duration.ofMinutes(15))) {
                    var messages = new ArrayList<SerializedMessage>();
                    for (int i = 0; i < 1024; i++) messages.add(message(Ignored.class.getName()));
                    messages.add(message(Accepted.class.getName()));
                    app.client().getGatewayClient(EVENT).append(Guarantee.STORED, messages.toArray(SerializedMessage[]::new)).join();
                    lastIndex.set(app.client().getTrackingClient(EVENT).readFromIndex(0, 2048).getLast().getIndex());
                    assertTrue(following.await(20, TimeUnit.SECONDS), "consumers did not open their next reads");
                    Map<String, Long> metrics = snapshot(app);
                    System.out.println("CONSUMER_METRICS_SELECTIVE filtered=" + filtered + " counts=" + metrics);
                    assertEquals(12L, metrics.getOrDefault("HandleMessageEvent", 0L));
                    assertEquals(filtered ? 12L : 204L, metrics.getOrDefault("ProcessBatchEvent", 0L));
                    assertEquals(filtered ? 12L : 204L, metrics.getOrDefault("StorePosition", 0L));
                }
            }
        } finally { server.stop(); }
    }

    @Test
    @EnabledIfSystemProperty(named = "fluxzero.test.consumerMetricsIdleBenchmark", matches = "true")
    void measuresRealSixtySecondVersusFifteenMinuteIdleReadMetrics() throws Exception {
        int consumers = 8;
        var secondShortReads = new CountDownLatch(consumers);
        Map<String, Integer> readCounts = new ConcurrentHashMap<>();
        var server = TestServer.startServer(0, tracker -> {
            if (tracker.getConsumerName().startsWith("idle-short-")
                && readCounts.merge(tracker.getConsumerName(), 1, Integer::sum) == 2) secondShortReads.countDown();
        });
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        try (Fluxzero shorter = application(port, "idle-short-", consumers, false, Duration.ofSeconds(60));
             Fluxzero longer = application(port, "idle-long-", consumers, true, Duration.ofMinutes(15))) {
            assertTrue(secondShortReads.await(75, TimeUnit.SECONDS), "short reads did not reach their real idle deadlines");
            Map<String, Long> shortMetrics = snapshot(shorter), longMetrics = snapshot(longer);
            System.out.println("CONSUMER_METRICS_IDLE waitSeconds=60 consumers=8 counts=" + shortMetrics);
            System.out.println("CONSUMER_METRICS_IDLE waitSeconds=900 consumers=8 counts=" + longMetrics);
            assertEquals(16L, shortMetrics.getOrDefault("Read", 0L));
            assertEquals(8L, longMetrics.getOrDefault("Read", 0L));
        } finally { server.stop(); }
    }

    private static Fluxzero application(int port, String prefix, int consumers, boolean filtered, Duration wait) {
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port).namespace(UUID.randomUUID().toString())
                .name("metric-counts").disableMetrics(false).build());
        var builder = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive();
        List<Object> handlers = new ArrayList<>();
        for (int i = 0; i < consumers; i++) {
            var handler = new AcceptedHandler();
            handlers.add(handler);
            builder.addConsumerConfiguration(ConsumerConfiguration.builder().name(prefix + i)
                    .handlerFilter(target -> target == handler).consumerFiltering(filtered ? null : Boolean.FALSE)
                    .maxFetchSize(64).maxWaitDuration(wait).maxScanLookBack(60_000L).minIndex(0L).build(), EVENT);
        }
        Fluxzero app = builder.build(client);
        app.registerHandlers(handlers);
        return app;
    }

    private static Map<String, Long> snapshot(Fluxzero app) {
        // Publication and following reads have already returned from all consumer stacks. A STORED append on the
        // same metrics gateway fences their queued metric writes before the snapshot is read from the server log.
        app.client().getGatewayClient(METRICS).append(Guarantee.STORED, message("MetricFence")).join();
        Map<String, Long> counts = new TreeMap<>();
        for (var metric : app.client().getTrackingClient(METRICS).readFromIndex(0, 100_000)) {
            String type = metric.getType();
            String simpleName = type.substring(type.lastIndexOf('.') + 1);
            // The app also opens a RESULT consumer at bootstrap; compare only the measured EVENT reads.
            if (!simpleName.equals("Read") || "EVENT".equals(metric.getMetadata().get("messageType"))) {
                counts.merge(simpleName, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static SerializedMessage message(String type) {
        return new SerializedMessage(new Data<>("{}".getBytes(StandardCharsets.UTF_8), type, 0, Data.JSON_FORMAT),
                Metadata.empty(), UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    record Accepted() {}
    record Ignored() {}
    static class AcceptedHandler {
        @HandleEvent void handle(Accepted input) {}
    }
}
