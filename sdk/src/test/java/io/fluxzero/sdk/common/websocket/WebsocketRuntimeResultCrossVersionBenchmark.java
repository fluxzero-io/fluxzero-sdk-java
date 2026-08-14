/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common.websocket;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cross-version benchmark for the complete JDK WebSocket, decode and SDK result-completion path.
 *
 * <p>This class intentionally uses only APIs present in SDK 1.239.0 so the exact same source can be compiled and run
 * against that release and a proposed replacement. Feed work in retained-capacity batches to avoid measuring a
 * different producer-side queue in either version. The default virtual-thread worker mode matches the SDK default on
 * supported Java versions, and the default two sessions match the normal event-sourcing, search, and key-value client
 * configuration. The default worker mode follows the SDK's Java 25 virtual-worker boundary; override it explicitly
 * to isolate executor effects. Use {@code -Dsessions=4} to exercise the client-wide result-completion bound.</p>
 */
public class WebsocketRuntimeResultCrossVersionBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 500_000);
    private static final int WARMUPS = Integer.getInteger("warmups", 5);
    private static final int SESSION_COUNT = Integer.getInteger("sessions", 2);
    private static final int RETAINED_MESSAGES = Integer.getInteger(
            "retainedMessages", JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES);
    private static final int VALUE_BYTES = Integer.getInteger("valueBytes", 320);
    private static final boolean TRANSPORT_METRICS = Boolean.getBoolean("transportMetrics");
    private static final boolean RUNTIME_PROGRESS = Boolean.parseBoolean(
            System.getProperty("runtimeProgress", Boolean.toString(TRANSPORT_METRICS)));
    private static final String WORKER_MODE = System.getProperty(
            "workerMode", Runtime.version().feature() >= 25 ? "virtual" : "fixed");
    private static final int FIXED_WORKERS = Integer.getInteger(
            "fixedWorkers", Math.min(8, SESSION_COUNT * 3));
    private static final CompressionAlgorithm COMPRESSION = CompressionAlgorithm.valueOf(
            System.getProperty("compression", "LZ4"));
    private static volatile long blackhole;

    public static void main(String[] args) {
        try (Scenario scenario = new Scenario()) {
            for (int i = 0; i < WARMUPS; i++) {
                scenario.run(ITERATIONS);
            }
            System.gc();
            long started = System.nanoTime();
            int measuredIterations = scenario.run(ITERATIONS);
            long elapsed = System.nanoTime() - started;
            System.out.printf(
                    "runtime-result-cross-version java=%d valueBytes=%d compression=%s sessions=%d "
                            + "metrics=%s runtimeProgress=%s workers=%s fixedWorkers=%d compressedBytes=%d "
                            + "iterations=%d: %.2f ns/op, %.1f ops/s%n",
                    Runtime.version().feature(), VALUE_BYTES, COMPRESSION, SESSION_COUNT, TRANSPORT_METRICS,
                    RUNTIME_PROGRESS, WORKER_MODE, FIXED_WORKERS, scenario.payload.length,
                    measuredIterations, (double) elapsed / measuredIterations,
                    measuredIterations * 1_000_000_000d / elapsed);
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static final class Scenario implements AutoCloseable {
        private final byte[] payload = createPayload();
        private final ExecutorService runtimeExecutor = runtimeExecutor();
        private final Semaphore processed = new Semaphore(0);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ResultClient client = new ResultClient(processed, failure);
        private final List<JdkWebSocketSession> sessions = new ArrayList<>();
        private final List<WebSocket.Listener> listeners = new ArrayList<>();
        private final List<BenchmarkWebSocket> webSockets = new ArrayList<>();

        private Scenario() {
            for (int i = 0; i < SESSION_COUNT; i++) {
                Map<String, Object> properties = new java.util.HashMap<>();
                properties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true);
                properties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY, 3);
                properties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY,
                               RETAINED_MESSAGES);
                properties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY,
                               16L * 1024 * 1024);
                properties.put(AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY, "benchmark_" + i);
                properties.put(AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "benchmark");
                properties.put(AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, COMPRESSION);
                properties.put(AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY,
                               io.fluxzero.common.websocket.WebSocketTransportFormat.JSON);
                if (RUNTIME_PROGRESS) {
                    properties.put(JdkWebSocketSession.class.getName() + ".sdkRuntimeIngressProgressEnabled", true);
                }
                if (TRANSPORT_METRICS) {
                    properties.put(JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true);
                }
                JdkWebSocketSession session = new JdkWebSocketSession(
                        new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(client),
                        new WebsocketConnectionOptions(Map.of(), properties, Duration.ofSeconds(1), List.of()),
                        URI.create("ws://localhost/cross-version/" + i),
                        new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeExecutor);
                BenchmarkWebSocket webSocket = new BenchmarkWebSocket();
                WebSocket.Listener listener = session.createListener();
                listener.onOpen(webSocket);
                sessions.add(session);
                listeners.add(listener);
                webSockets.add(webSocket);
            }
        }

        private static ExecutorService runtimeExecutor() {
            return switch (WORKER_MODE) {
                case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
                case "fixed" -> Executors.newFixedThreadPool(FIXED_WORKERS);
                default -> throw new IllegalArgumentException(
                        "workerMode must be either virtual or fixed: " + WORKER_MODE);
            };
        }

        private int run(int requestedIterations) {
            int batchSize = SESSION_COUNT * RETAINED_MESSAGES;
            int iterations = Math.max(batchSize, requestedIterations / batchSize * batchSize);
            int remaining = iterations;
            while (remaining > 0) {
                for (int sessionIndex = 0; sessionIndex < SESSION_COUNT; sessionIndex++) {
                    WebSocket.Listener listener = listeners.get(sessionIndex);
                    WebSocket webSocket = webSockets.get(sessionIndex);
                    for (int i = 0; i < RETAINED_MESSAGES; i++) {
                        listener.onBinary(webSocket, ByteBuffer.wrap(payload), true);
                    }
                }
                awaitProcessed(batchSize);
                awaitDrain();
                remaining -= batchSize;
            }
            return iterations;
        }

        private void awaitProcessed(int count) {
            try {
                if (!processed.tryAcquire(count, 30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out awaiting SDK result completion");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted awaiting SDK result completion", e);
            }
            if (failure.get() != null) {
                throw new IllegalStateException("SDK result completion failed", failure.get());
            }
        }

        private void awaitDrain() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!allSessionsDrained()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Timed out awaiting retained ingress drain");
                }
                Thread.onSpinWait();
            }
        }

        private boolean allSessionsDrained() {
            for (JdkWebSocketSession session : sessions) {
                if (session.runtimeDataState().retainedMessages() != 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            sessions.forEach(session -> session.abort(
                    new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "benchmark complete")));
            runtimeExecutor.shutdownNow();
            client.close();
        }
    }

    private static byte[] createPayload() {
        try {
            StringBuilder value = new StringBuilder(VALUE_BYTES);
            int state = 0x13579bdf;
            for (int i = 0; i < VALUE_BYTES; i++) {
                state = state * 1_103_515_245 + 12_345;
                value.append((char) (' ' + Math.floorMod(state, 95)));
            }
            byte[] encoded = WebSocketTransportCodecs.json(AbstractWebsocketClient.defaultObjectMapper)
                    .encode(new StringResult(1L, value.toString()));
            String deterministic = new String(encoded, StandardCharsets.UTF_8)
                    .replaceFirst("\"timestamp\":\\d+", "\"timestamp\":1");
            return COMPRESSION.compress(deterministic.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create cross-version payload", e);
        }
    }

    private static final class ResultClient extends AbstractWebsocketClient {
        private final Semaphore processed;
        private final AtomicReference<Throwable> failure;
        private final LongAdder consumedResultBytes = new LongAdder();
        private final Logger logger;
        private final Level previousLogLevel;

        private ResultClient(Semaphore processed, AtomicReference<Throwable> failure) {
            super((endpoint, options, uri) -> {
                      throw new UnsupportedOperationException("Benchmark connector must not connect");
                  }, URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("cross-version-result-benchmark")
                                                      .build()),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1,
                  new SimplePropertySource(TRANSPORT_METRICS
                                                   ? Map.of(TRANSPORT_METRICS_ENABLED_PROPERTY, "true") : Map.of()),
                  (client, numberOfSessions) -> NoOpTaskScheduler.INSTANCE);
            this.processed = processed;
            this.failure = failure;
            this.logger = (Logger) LoggerFactory.getLogger(
                    "%s.%s".formatted(getClass().getPackageName(), this));
            this.previousLogLevel = logger.getLevel();
            logger.setLevel(Level.OFF);
        }

        @Override
        public void onOpen(WebsocketSession session) {
        }

        @Override
        protected void handleResult(RequestResult result, String batchId, String sessionId,
                                    WebsocketResultDiagnostics.ResultTiming timing) {
            try {
                consumedResultBytes.add(((StringResult) result).getResult().length());
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                throw e;
            } finally {
                processed.release();
            }
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
            failure.compareAndSet(null, error);
        }

        @Override
        void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
            blackhole += metric.hashCode() + metadata.hashCode();
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                blackhole += consumedResultBytes.sum();
                logger.setLevel(previousLogLevel);
            }
        }
    }

    private enum NoOpTaskScheduler implements TaskScheduler {
        INSTANCE;

        private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        @Override
        public Registration schedule(long deadline, ThrowingRunnable task) {
            return () -> {
            };
        }

        @Override
        public Clock clock() {
            return CLOCK;
        }

        @Override
        public void executeExpiredTasks() {
        }

        @Override
        public void shutdown() {
        }
    }

    private static final class BenchmarkWebSocket implements WebSocket {
        private boolean aborted;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return aborted;
        }

        @Override
        public boolean isInputClosed() {
            return aborted;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
