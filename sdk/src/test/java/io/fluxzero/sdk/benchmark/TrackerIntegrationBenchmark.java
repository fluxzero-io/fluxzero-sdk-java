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
 *
 */

package io.fluxzero.sdk.benchmark;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.UuidFactory;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static io.fluxzero.sdk.tracking.IndexUtils.indexForCurrentTime;

@Slf4j
public class TrackerIntegrationBenchmark {

    public static void main(String[] args) {
        new TrackerIntegrationBenchmark().main();
    }

    void main() {
        int clientCount = ApplicationProperties.getIntegerProperty("clientCount", 6);
        int threadCount = ApplicationProperties.getIntegerProperty("threadCount", 1);
        int consumerCount = ApplicationProperties.getIntegerProperty("consumerCount", 200);
        int messageCount = ApplicationProperties.getIntegerProperty("messageCount", 20_000);
        int publisherCount = ApplicationProperties.getIntegerProperty("publisherCount", 4);
        int publishBatchSize = ApplicationProperties.getIntegerProperty("publishBatchSize", 8);
        int distinctKeys = ApplicationProperties.getIntegerProperty("distinctKeys", 2048);
        int payloadBytes = ApplicationProperties.getIntegerProperty("payloadBytes", 2048);
        int benchmarkTimeoutMs = ApplicationProperties.getIntegerProperty("benchmarkTimeoutMs", 60_000);
        int port = ApplicationProperties.getIntegerProperty(
                "FLUXZERO_PORT", ApplicationProperties.getIntegerProperty(
                        "FLUX_PORT", ApplicationProperties.getIntegerProperty("port", 8888)));
        BenchmarkMode mode = BenchmarkMode.parse(ApplicationProperties.getProperty("mode", "CONCURRENT"));
        ClientMode clientMode = ClientMode.parse(ApplicationProperties.getProperty("clientMode", "WEBSOCKET"));

        run(UuidFactory.defaultIdentityProvider.nextFunctionalId(),
            port, clientCount, consumerCount, threadCount, messageCount, publisherCount,
            publishBatchSize, distinctKeys, payloadBytes, benchmarkTimeoutMs, mode, clientMode);
        log.info("Shutting down");
        System.exit(0);
        log.info("Shutdown complete");
    }

    @SneakyThrows
    void run(String namespace, int port, int clientCount, int consumerCount, int threadCount,
             int messageCount, int publisherCount,
             int publishBatchSize, int distinctKeys, int payloadBytes, int benchmarkTimeoutMs,
             BenchmarkMode mode, ClientMode clientMode) {

        String runId = namespace.substring(0, Math.min(namespace.length(), 12));

        log.info("""
                         Starting TrackerIntegrationBenchmark with namespace={}, port={}, clientCount={}, consumerCount={}, threadCount={}, messageCount={}, \
                         publisherCount={}, publishBatchSize={}, distinctKeys={}, payloadBytes={}, \
                         benchmarkTimeoutMs={}, mode={}, clientMode={}
                         """.replaceAll("\\s+", " "),
                 namespace, port, clientCount, consumerCount, threadCount, messageCount, publisherCount, publishBatchSize,
                 distinctKeys, payloadBytes, benchmarkTimeoutMs, mode, clientMode);

        int totalCount = consumerCount * messageCount;
        CountDownLatch latch = new CountDownLatch(totalCount);
        ConcurrentHashMap<String, LongAdder> deliveriesPerConsumer = new ConcurrentHashMap<>();

        // LocalClient namespaces have separate in-memory stores; use the root client so default consumers and publishers
        // share the same message log.
        Client localClient = clientMode == ClientMode.LOCAL ? LocalClient.newInstance(null) : null;
        List<Fluxzero> clients = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            String clientName = "bench-client-" + i;
            Client benchmarkClient = createClient(clientMode, localClient, namespace, port, i, clientName, runId);
            FluxzeroBuilder fluxzeroBuilder = DefaultFluxzero.builder()
                    .disableAutomaticTracking()
                    .disableTrackingMetrics()
                    .disableShutdownHook()
                    .makeApplicationInstance(i == 0)
                    .configureDefaultConsumer(MessageType.EVENT, c -> c.toBuilder().handlerFilter(o -> false).build());

            for (int j = 0; j < consumerCount; j++) {
                String consumerName = "consumer-%d".formatted(j);
                fluxzeroBuilder.addConsumerConfiguration(
                        ConsumerConfiguration.builder().name(consumerName)
                                .handlerFilter(o -> true)
                                .maxWaitDuration(Duration.ofSeconds(10))
                                .exclusive(false)
                                .minIndex(-1L)
                                .threads(threadCount)
                                .build());
            }

            Fluxzero fluxzero = fluxzeroBuilder.build(benchmarkClient);
            clients.add(fluxzero);
        }

        log.info("Resetting positions");
        Fluxzero client = clients.getFirst();
        for (int i = 0; i < consumerCount; i++) {
            String consumerName = "consumer-%d".formatted(i);
            client.client().getTrackingClient(MessageType.EVENT)
                    .resetPosition(consumerName, indexForCurrentTime() - 1L).join();
        }
        log.info("Positions reset");

        log.info("Warming up");
        warmUp(clients);
        log.info("Warming up complete");

        List<Fluxzero> publishers = clients.subList(0, Math.min(publisherCount, clients.size()));

        if (mode == BenchmarkMode.PRELOAD) {
            publishEvents(mode, publishers, messageCount, publishBatchSize, distinctKeys, payloadBytes);
            log.info("Waiting for benchmark to complete");
            timeConsumption(totalCount, consumerCount, messageCount, benchmarkTimeoutMs, latch, deliveriesPerConsumer,
                            () -> registerHandlers(clients, latch, deliveriesPerConsumer));
        } else {
            registerHandlers(clients, latch, deliveriesPerConsumer);
            Thread.sleep(1000);
            log.info("Waiting for benchmark to complete");
            timeConsumption(totalCount, consumerCount, messageCount, benchmarkTimeoutMs, latch, deliveriesPerConsumer,
                            () -> publishEvents(mode, publishers, messageCount, publishBatchSize, distinctKeys,
                                                payloadBytes));
        }

        log.info("Closing clients");
        clients.forEach(Fluxzero::close);
        log.info("Clients closed");
    }

    private static void registerHandlers(List<Fluxzero> clients, CountDownLatch latch,
                                         ConcurrentHashMap<String, LongAdder> deliveriesPerConsumer) {
        log.info("Registering handlers");
        clients.forEach(c -> c.registerHandlers(new Handler(latch, deliveriesPerConsumer)));
    }

    private static void timeConsumption(int totalCount, int consumerCount, int messageCount, int benchmarkTimeoutMs,
                                        CountDownLatch latch,
                                        ConcurrentHashMap<String, LongAdder> deliveriesPerConsumer, Runnable setup) {
        TimingUtils.time(() -> {
            setup.run();
            if (!latch.await(benchmarkTimeoutMs, TimeUnit.MILLISECONDS)) {
                log.error("Benchmark timed out after {} ms with {} events remaining",
                          benchmarkTimeoutMs, latch.getCount());
                logMissingConsumers(messageCount, deliveriesPerConsumer);
                throw new IllegalStateException("Timed out with %s events remaining".formatted(latch.getCount()));
            }
            return null;
        }, millis -> {
            log.info("Consumed {} events in {} ms across {} consumers ({} events/s)", messageCount, millis,
                     consumerCount,
                     millis == 0 ? totalCount : (totalCount * 1000L) / millis);
        });
    }

    private static Client createClient(ClientMode clientMode, Client localClient, String namespace, int port,
                                       int clientIndex, String clientName, String runId) {
        if (clientMode == ClientMode.LOCAL) {
            return localClient;
        }
        // Keep TestServer command-idempotency results from a previous benchmark JVM from matching this run.
        String clientId = "client-" + clientIndex + "-" + runId;
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .name(clientName)
                .id(clientId)
                .namespace(namespace)
                .runtimeBaseUrl("ws://localhost:" + port)
                .build();
        return WebSocketClient.newInstance(clientConfig);
    }

    @AllArgsConstructor
    static class Handler {
        private final CountDownLatch latch;
        private final ConcurrentHashMap<String, LongAdder> deliveriesPerConsumer;

        @HandleEvent
        void handle(DeserializingMessage event) {
            if (latch.getCount() == 0) {
                log.error("Received more events than expected");
            }
            deliveriesPerConsumer.computeIfAbsent(Tracker.current().orElseThrow().getName(), ignored -> new LongAdder())
                    .increment();
            latch.countDown();
        }
    }

    static void logMissingConsumers(int expectedPerConsumer,
                                    ConcurrentHashMap<String, LongAdder> deliveriesPerConsumer) {
        deliveriesPerConsumer.entrySet().stream()
                .map(entry -> new ConsumerProgress(entry.getKey(), entry.getValue().sum(), expectedPerConsumer))
                .filter(progress -> progress.missing() > 0)
                .sorted((left, right) -> Long.compare(right.missing(), left.missing()))
                .limit(20)
                .forEach(progress -> log.error("Consumer {} received {} / {} events (missing {})",
                                               progress.consumer(), progress.received(), progress.expected(),
                                               progress.missing()));
    }

    static void warmUp(List<Fluxzero> clients) {
        clients.forEach(fluxzero -> {
            fluxzero.client().getGatewayClient(MessageType.EVENT).setRetentionTime(Duration.ofDays(1), Guarantee.STORED)
                    .join();
            fluxzero.client().getTrackingClient(MessageType.EVENT).readFromIndex(0, 1);
            fluxzero.client().getTrackingClient(MessageType.EVENT).getPosition("bench-client-0");
        });
    }

    @SneakyThrows
    static void publishEventsConcurrently(List<Fluxzero> publishers, int messageCount, int publishBatchSize,
                                          int distinctKeys, int payloadBytes) {
        log.info("Append events concurrently");
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            int safeBatchSize = Math.max(1, publishBatchSize);
            int publisherCount = Math.max(1, publishers.size());
            int perPublisher = Math.ceilDiv(messageCount, publisherCount);
            for (int publisherIndex = 0; publisherIndex < publisherCount; publisherIndex++) {
                Fluxzero publisher = publishers.get(publisherIndex);
                int startIndex = publisherIndex * perPublisher;
                int endIndex = Math.min(messageCount, startIndex + perPublisher);
                if (startIndex >= endIndex) {
                    continue;
                }
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int i = startIndex; i < endIndex; i += safeBatchSize) {
                        int batchEnd = Math.min(endIndex, i + safeBatchSize);
                        Object[] batch = IntStream.range(i, batchEnd)
                                .mapToObj(index -> createMessage(index, distinctKeys, payloadBytes))
                                .toArray();
                        publisher.eventGateway().publish(Guarantee.STORED, batch).join();
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        log.info("Append events complete in {} ms", millis);
    }

    static void publishEvents(BenchmarkMode mode, List<Fluxzero> publishers, int messageCount,
                              int publishBatchSize, int distinctKeys, int payloadBytes) {
        switch (mode) {
            case PRELOAD, BULK -> publishEventsBulk(publishers.getFirst(), messageCount, distinctKeys, payloadBytes);
            case CONCURRENT -> publishEventsConcurrently(publishers, messageCount, publishBatchSize, distinctKeys,
                                                         payloadBytes);
        }
    }

    static void publishEventsBulk(Fluxzero publisher, int messageCount, int distinctKeys, int payloadBytes) {
        log.info("Append events in one bulk");
        long start = System.nanoTime();
        publisher.eventGateway().publish(Guarantee.STORED, IntStream.range(0, messageCount)
                .mapToObj(i -> createMessage(i, distinctKeys, payloadBytes)).toArray()).join();
        long millis = (System.nanoTime() - start) / 1_000_000L;
        log.info("Append events complete in {} ms", millis);
    }

    static Message createMessage(int index, int distinctKeys, int payloadBytes) {
        String routingKey = "key-" + (index % Math.max(1, distinctKeys));
        Object payload = payloadBytes <= 0 ? "event-" + index : new BenchEvent(index, repeat('x', payloadBytes));
        return Message.asMessage(payload).addMetadata("routingKey", routingKey);
    }

    static String repeat(char c, int count) {
        return String.valueOf(c).repeat(Math.max(0, count));
    }

    enum ClientMode {
        WEBSOCKET,
        LOCAL;

        static ClientMode parse(String value) {
            return switch (value.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "")) {
                case "LOCAL", "LOCALCLIENT" -> LOCAL;
                case "WEBSOCKET", "WEBSOCKETCLIENT", "WS" -> WEBSOCKET;
                default -> throw new IllegalArgumentException("Unsupported clientMode: " + value);
            };
        }
    }

    enum BenchmarkMode {
        PRELOAD,
        BULK,
        CONCURRENT;

        static BenchmarkMode parse(String value) {
            return switch (value.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "")) {
                case "PRELOAD", "PRELOADED" -> PRELOAD;
                case "BULK" -> BULK;
                case "CONCURRENT" -> CONCURRENT;
                default -> throw new IllegalArgumentException("Unsupported mode: " + value);
            };
        }
    }

    record BenchEvent(int index, String payload) {
    }

    record ConsumerProgress(String consumer, long received, long expected) {
        long missing() {
            return Math.max(0, expected - received);
        }
    }

}
