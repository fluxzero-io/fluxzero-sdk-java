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
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.common.websocket.ServiceUrlBuilder;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient.TrackingClientConfig;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.WebsocketTrackingClient;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static java.lang.Integer.getInteger;
import static java.lang.Long.getLong;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

@Slf4j
public class WebsocketPingLatencyBenchmark {
    /*
     * Example against a real runtime:
     * ./mvnw -f test-server/pom.xml -Dexec.classpathScope=test \
     *   -Dexec.mainClass=io.fluxzero.testserver.WebsocketPingLatencyBenchmark \
     *   -Dport=8888 -DmessagesPerSecond=1000 test-compile exec:java
     */

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        log.info("Starting websocket ping latency benchmark: {}", config);

        PingMetrics metrics = new PingMetrics();
        List<InstrumentedTrackingClient> clients = new ArrayList<>();
        List<WebSocketClient> webSocketClients = new ArrayList<>();
        List<ReadLoop> readLoops = new ArrayList<>();
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("ping-latency-reporter").factory());
        ScheduledExecutorService publisher = Executors.newScheduledThreadPool(
                config.publisherThreads(), Thread.ofPlatform().name("ping-latency-publisher", 0).factory());
        reporter.scheduleAtFixedRate(() -> log.info("Ping latency snapshot: {}", metrics.summary()),
                                     config.reportEvery().toMillis(), config.reportEvery().toMillis(),
                                     TimeUnit.MILLISECONDS);

        try {
            for (int clientIndex = 0; clientIndex < config.clientCount(); clientIndex++) {
                WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                        .runtimeBaseUrl(config.runtimeBaseUrl())
                        .namespace(config.namespace())
                        .id("ping-benchmark-" + clientIndex + "-" + UUID.randomUUID())
                        .name("ping-benchmark-" + clientIndex)
                        .disableMetrics(true)
                        .pingDelay(config.pingDelay())
                        .pingTimeout(config.pingTimeout())
                        .build()
                        .withTrackingConfig(MessageType.EVENT,
                                            TrackingClientConfig.builder().sessions(config.sessions()).build());
                WebSocketClient webSocketClient = WebSocketClient.newInstance(clientConfig);
                webSocketClients.add(webSocketClient);
                URI endpoint = URI.create(ServiceUrlBuilder.trackingUrl(MessageType.EVENT, null, clientConfig));
                InstrumentedTrackingClient trackingClient =
                        new InstrumentedTrackingClient(endpoint, webSocketClient, metrics, clientIndex);
                clients.add(trackingClient);

                for (int readIndex = 0; readIndex < config.readsPerClient(); readIndex++) {
                    ConsumerConfiguration consumerConfiguration = ConsumerConfiguration.builder()
                            .name("ping-benchmark-" + clientIndex + "-" + readIndex)
                            .maxWaitDuration(config.duration().plusSeconds(30))
                            .build();
                    ReadLoop readLoop = new ReadLoop(trackingClient,
                                                     "ping-benchmark-tracker-" + clientIndex + "-" + readIndex,
                                                     consumerConfiguration, metrics);
                    readLoops.add(readLoop);
                    readLoop.start();
                }
            }

            startPublishers(config, metrics, webSocketClients, publisher);
            Thread.sleep(config.duration().toMillis());
        } finally {
            readLoops.forEach(ReadLoop::stop);
            publisher.shutdownNow();
            clients.forEach(InstrumentedTrackingClient::close);
            webSocketClients.forEach(WebSocketClient::shutDown);
            reporter.shutdownNow();
        }

        log.info("Final websocket ping latency summary: {}", metrics.summary());
    }

    private static void startPublishers(BenchmarkConfig config, PingMetrics metrics, List<WebSocketClient> clients,
                                        ScheduledExecutorService publisher) {
        if (config.messagesPerSecond() <= 0) {
            return;
        }
        long periodNanos = max(1_000_000L, 1_000_000_000L * config.messageBatchSize() * config.publisherThreads()
                                          / config.messagesPerSecond());
        AtomicLong sequence = new AtomicLong();
        for (int publisherIndex = 0; publisherIndex < config.publisherThreads(); publisherIndex++) {
            GatewayClient gatewayClient = clients.get(publisherIndex % clients.size()).getGatewayClient(MessageType.EVENT);
            int index = publisherIndex;
            publisher.scheduleAtFixedRate(
                    () -> publishBatch(config, metrics, gatewayClient, sequence, index),
                    publisherIndex * periodNanos / config.publisherThreads(), periodNanos, NANOSECONDS);
        }
    }

    private static void publishBatch(BenchmarkConfig config, PingMetrics metrics, GatewayClient gatewayClient,
                                     AtomicLong sequence, int publisherIndex) {
        SerializedMessage[] messages = new SerializedMessage[config.messageBatchSize()];
        for (int i = 0; i < messages.length; i++) {
            long id = sequence.incrementAndGet();
            messages[i] = new SerializedMessage(
                    new Data<>(("ping-latency-message-" + id).getBytes(UTF_8),
                               WebsocketPingLatencyBenchmark.class.getName(), 0, "text/plain"),
                    Metadata.of("benchmark", "websocket-ping-latency", "publisher", publisherIndex, "sequence", id),
                    UUID.randomUUID().toString(), System.currentTimeMillis());
        }
        metrics.published.add(messages.length);
        gatewayClient.append(config.publishGuarantee(), messages).whenComplete((ignored, e) -> {
            if (e == null) {
                metrics.publishAcks.add(messages.length);
            } else {
                metrics.publishFailures.increment();
                log.warn("Failed to publish benchmark message batch", e);
            }
        });
    }

    private record BenchmarkConfig(String runtimeBaseUrl, String namespace, int clientCount, int sessions,
                                   int readsPerClient, Duration duration, Duration pingDelay,
                                   Duration pingTimeout, Duration reportEvery, int port, int messagesPerSecond,
                                   int messageBatchSize, int publisherThreads, Guarantee publishGuarantee) {

        static BenchmarkConfig fromSystemProperties() {
            int port = intProperty("port", 8888);
            String runtimeBaseUrl = System.getProperty("runtimeBaseUrl", "ws://localhost:" + port);
            int sessions = intProperty("sessions", 1);
            return new BenchmarkConfig(
                    runtimeBaseUrl,
                    System.getProperty("namespace", "ping-benchmark"),
                    intProperty("clientCount", 8),
                    sessions,
                    intProperty("readsPerClient", sessions),
                    Duration.ofSeconds(getLong("durationSeconds", 60L)),
                    Duration.ofMillis(getLong("pingDelayMillis", 1000L)),
                    Duration.ofMillis(getLong("pingTimeoutMillis", 1000L)),
                    Duration.ofSeconds(getLong("reportEverySeconds", 10L)),
                    port,
                    intProperty("messagesPerSecond", 0),
                    intProperty("messageBatchSize", 16),
                    intProperty("publisherThreads", 1),
                    Guarantee.valueOf(System.getProperty("publishGuarantee", Guarantee.SENT.name())));
        }

        private static int intProperty(String name, int defaultValue) {
            return Math.toIntExact(max(name.equals("messagesPerSecond") ? 0L : 1L, getLong(name, (long) defaultValue)));
        }
    }

    private static class ReadLoop {
        private final InstrumentedTrackingClient trackingClient;
        private final String trackerId;
        private final ConsumerConfiguration consumerConfiguration;
        private final PingMetrics metrics;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile CompletableFuture<MessageBatch> currentRead;
        private volatile long lastIndex = -1L;

        ReadLoop(InstrumentedTrackingClient trackingClient, String trackerId,
                 ConsumerConfiguration consumerConfiguration, PingMetrics metrics) {
            this.trackingClient = trackingClient;
            this.trackerId = trackerId;
            this.consumerConfiguration = consumerConfiguration;
            this.metrics = metrics;
        }

        void start() {
            readNext();
        }

        void stop() {
            running.set(false);
            CompletableFuture<MessageBatch> read = currentRead;
            if (read != null) {
                read.cancel(true);
            }
        }

        private void readNext() {
            if (!running.get()) {
                return;
            }
            CompletableFuture<MessageBatch> read = trackingClient.read(trackerId, lastIndex, consumerConfiguration);
            currentRead = read;
            read.whenComplete((batch, e) -> {
                if (!running.get()) {
                    return;
                }
                if (e != null) {
                    metrics.readFailures.increment();
                    log.warn("Failed to read benchmark message batch for tracker {}", trackerId, e);
                } else {
                    metrics.readBatches.increment();
                    metrics.readMessages.add(batch.getSize());
                    if (batch.getLastIndex() != null) {
                        lastIndex = batch.getLastIndex();
                    }
                }
                readNext();
            });
        }
    }

    private static class InstrumentedTrackingClient extends WebsocketTrackingClient {
        private final PingMetrics metrics;
        private final int clientIndex;
        private final Duration pingDelay;
        private final Map<String, PingTiming> timings = new ConcurrentHashMap<>();

        InstrumentedTrackingClient(URI endpoint, WebSocketClient client, PingMetrics metrics, int clientIndex) {
            super(endpoint, client, MessageType.EVENT, null, false);
            this.metrics = metrics;
            this.clientIndex = clientIndex;
            this.pingDelay = client.getClientConfig().getPingDelay();
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            metrics.opens.increment();
            super.onOpen(session, config);
        }

        @Override
        protected PingRegistration schedulePing(Session session) {
            String sessionId = getNegotiatedSessionId(session);
            timings.put(sessionId, new PingTiming(nanoTime() + pingDelay.toNanos()));
            metrics.scheduled.increment();
            return super.schedulePing(session);
        }

        @Override
        protected void sendPing(Session session) {
            String sessionId = getNegotiatedSessionId(session);
            long now = nanoTime();
            PingTiming timing = timings.computeIfAbsent(sessionId, ignored -> new PingTiming(now));
            timing.sentAtNanos = now;
            metrics.sent.increment();
            metrics.recordPingExecutionLag(now - timing.expectedSendAtNanos);
            metrics.recordThread(Thread.currentThread().getName(), metrics.sendThreads);
            super.sendPing(session);
        }

        @Override
        public void onPong(PongMessage message, Session session) {
            String sessionId = getNegotiatedSessionId(session);
            long now = nanoTime();
            PingTiming timing = timings.get(sessionId);
            if (timing != null) {
                timing.callbackAtNanos = now;
                metrics.recordPongCallbackLatency(now - timing.sentAtNanos);
                metrics.recordPongId(message.getApplicationData());
            }
            metrics.pongCallbacks.increment();
            metrics.recordThread(Thread.currentThread().getName(), metrics.pongCallbackThreads);
            super.onPong(message, session);
        }

        @Override
        protected void handlePong(Session session) {
            String sessionId = getNegotiatedSessionId(session);
            long now = nanoTime();
            PingTiming timing = timings.get(sessionId);
            if (timing != null) {
                metrics.recordPongHandlingLag(now - timing.callbackAtNanos);
                metrics.recordPongEndToEnd(now - timing.sentAtNanos);
            }
            metrics.pongHandled.increment();
            metrics.recordThread(Thread.currentThread().getName(), metrics.pongHandledThreads);
            super.handlePong(session);
        }

        @Override
        protected void abort(Session session, String reason) {
            if ("Ping failed".equals(reason)) {
                metrics.timeouts.increment();
                log.warn("Ping timeout observed for client {} session {}", clientIndex, getNegotiatedSessionId(session));
            }
            super.abort(session, reason);
        }

        @Override
        protected void handleClose(Session session, CloseReason closeReason) {
            metrics.closes.increment();
            metrics.recordThread(Thread.currentThread().getName(), metrics.closeThreads);
            super.handleClose(session, closeReason);
        }
    }

    private static class PingTiming {
        private final long expectedSendAtNanos;
        private volatile long sentAtNanos;
        private volatile long callbackAtNanos;

        PingTiming(long expectedSendAtNanos) {
            this.expectedSendAtNanos = expectedSendAtNanos;
        }
    }

    private static class PingMetrics {
        private final LongAdder opens = new LongAdder();
        private final LongAdder scheduled = new LongAdder();
        private final LongAdder sent = new LongAdder();
        private final LongAdder pongCallbacks = new LongAdder();
        private final LongAdder pongHandled = new LongAdder();
        private final LongAdder timeouts = new LongAdder();
        private final LongAdder closes = new LongAdder();
        private final LongAdder published = new LongAdder();
        private final LongAdder publishAcks = new LongAdder();
        private final LongAdder publishFailures = new LongAdder();
        private final LongAdder readBatches = new LongAdder();
        private final LongAdder readMessages = new LongAdder();
        private final LongAdder readFailures = new LongAdder();
        private final LongAdder pongIds = new LongAdder();
        private final LongAdder pingExecutionLagNanos = new LongAdder();
        private final LongAdder pongCallbackLatencyNanos = new LongAdder();
        private final LongAdder pongHandlingLagNanos = new LongAdder();
        private final LongAdder pongEndToEndNanos = new LongAdder();
        private final AtomicLong maxPingExecutionLagNanos = new AtomicLong();
        private final AtomicLong maxPongCallbackLatencyNanos = new AtomicLong();
        private final AtomicLong maxPongHandlingLagNanos = new AtomicLong();
        private final AtomicLong maxPongEndToEndNanos = new AtomicLong();
        private final LatencyBuckets pongCallbackBuckets = new LatencyBuckets();
        private final LatencyBuckets pongEndToEndBuckets = new LatencyBuckets();
        private final Map<String, LongAdder> sendThreads = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> pongCallbackThreads = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> pongHandledThreads = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> closeThreads = new ConcurrentHashMap<>();

        void recordPingExecutionLag(long nanos) {
            pingExecutionLagNanos.add(nanos);
            max(maxPingExecutionLagNanos, nanos);
        }

        void recordPongCallbackLatency(long nanos) {
            if (nanos >= 0) {
                pongCallbackLatencyNanos.add(nanos);
                max(maxPongCallbackLatencyNanos, nanos);
                pongCallbackBuckets.record(nanos);
            }
        }

        void recordPongHandlingLag(long nanos) {
            if (nanos >= 0) {
                pongHandlingLagNanos.add(nanos);
                max(maxPongHandlingLagNanos, nanos);
            }
        }

        void recordPongEndToEnd(long nanos) {
            if (nanos >= 0) {
                pongEndToEndNanos.add(nanos);
                max(maxPongEndToEndNanos, nanos);
                pongEndToEndBuckets.record(nanos);
            }
        }

        void recordPongId(ByteBuffer applicationData) {
            if (applicationData != null && applicationData.hasRemaining()) {
                pongIds.increment();
            }
        }

        void recordThread(String threadName, Map<String, LongAdder> threads) {
            threads.computeIfAbsent(threadName, ignored -> new LongAdder()).increment();
        }

        String summary() {
            long sentCount = sent.sum();
            long callbackCount = pongCallbacks.sum();
            long handledCount = pongHandled.sum();
            return format(
                    "opens=%d scheduled=%d sent=%d pongCallbacks=%d pongHandled=%d timeouts=%d closes=%d "
                    + "published=%d publishAcks=%d publishFailures=%d readBatches=%d readMessages=%d "
                    + "readFailures=%d "
                    + "avgSendLagMs=%.2f maxSendLagMs=%.2f avgPongCallbackMs=%.2f maxPongCallbackMs=%.2f "
                    + "avgPongHandlingLagMs=%.2f maxPongHandlingLagMs=%.2f avgPongEndToEndMs=%.2f "
                    + "maxPongEndToEndMs=%.2f pongCallbackBuckets=%s pongEndToEndBuckets=%s pongIds=%d "
                    + "sendThreads=%s pongCallbackThreads=%s pongHandledThreads=%s closeThreads=%s",
                    opens.sum(), scheduled.sum(), sentCount, callbackCount, handledCount, timeouts.sum(),
                    closes.sum(),
                    published.sum(), publishAcks.sum(), publishFailures.sum(), readBatches.sum(),
                    readMessages.sum(), readFailures.sum(),
                    avgMillis(pingExecutionLagNanos.sum(), sentCount), millis(maxPingExecutionLagNanos.get()),
                    avgMillis(pongCallbackLatencyNanos.sum(), callbackCount),
                    millis(maxPongCallbackLatencyNanos.get()),
                    avgMillis(pongHandlingLagNanos.sum(), handledCount),
                    millis(maxPongHandlingLagNanos.get()),
                    avgMillis(pongEndToEndNanos.sum(), handledCount), millis(maxPongEndToEndNanos.get()),
                    pongCallbackBuckets.summary(), pongEndToEndBuckets.summary(), pongIds.sum(),
                    summarizeThreads(sendThreads), summarizeThreads(pongCallbackThreads),
                    summarizeThreads(pongHandledThreads), summarizeThreads(closeThreads));
        }

        private static void max(AtomicLong current, long value) {
            current.accumulateAndGet(value, Math::max);
        }

        private static double avgMillis(long nanos, long count) {
            return count == 0 ? 0D : millis(nanos / count);
        }

        private static double millis(long nanos) {
            return NANOSECONDS.toMicros(nanos) / 1_000D;
        }

        private static Map<String, Long> summarizeThreads(Map<String, LongAdder> threads) {
            Map<String, Long> result = new LinkedHashMap<>();
            threads.entrySet().stream()
                    .sorted(Map.Entry.<String, LongAdder>comparingByValue((left, right) -> Long.compare(
                            right.sum(), left.sum())).thenComparing(Map.Entry.comparingByKey()))
                    .limit(getInteger("threadSummaryLimit", 8))
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue().sum()));
            long omitted = threads.size() - result.size();
            if (omitted > 0) {
                result.put("... " + omitted + " more", 0L);
            }
            return result;
        }
    }

    private static class LatencyBuckets {
        private final long[] thresholdsMs = new long[]{10, 25, 50, 100, 250, 500, 750, 1000};
        private final LongAdder[] buckets = new LongAdder[thresholdsMs.length + 1];

        LatencyBuckets() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }

        void record(long nanos) {
            long millis = NANOSECONDS.toMillis(nanos);
            for (int i = 0; i < thresholdsMs.length; i++) {
                if (millis <= thresholdsMs[i]) {
                    buckets[i].increment();
                    return;
                }
            }
            buckets[thresholdsMs.length].increment();
        }

        Map<String, Long> summary() {
            Map<String, Long> result = new LinkedHashMap<>();
            for (int i = 0; i < thresholdsMs.length; i++) {
                result.put("<=" + thresholdsMs[i] + "ms", buckets[i].sum());
            }
            result.put(">" + thresholdsMs[thresholdsMs.length - 1] + "ms", buckets[thresholdsMs.length].sum());
            return result;
        }
    }
}
