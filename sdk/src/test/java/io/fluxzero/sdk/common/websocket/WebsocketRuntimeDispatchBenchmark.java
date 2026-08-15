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
import com.sun.management.ThreadMXBean;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodec;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in microbenchmark for SDK runtime-message isolation and bounded parallel processing.
 *
 * <p>The low-level direct baseline deliberately omits the internal SDK runtime marker. Production SDK clients always
 * enable isolation; the baseline exists only to quantify its bookkeeping cost. The isolated scenario uses a direct
 * backing executor so executor scheduling variance does not dominate this comparison. Each receive scenario runs with
 * transport metrics disabled and enabled. A separate bounded-load comparison uses a controlled fixed worker pool to
 * measure effective per-session concurrency with one through three runtime workers. Its latency percentiles represent
 * the time to drain one full retained-capacity batch, not an amortized per-message latency. An anomaly comparison
 * measures metric construction, fallback serialization, and hand-off to a local sink without network variance.</p>
 */
public class WebsocketRuntimeDispatchBenchmark {
    private static final int[] MESSAGE_SIZES = {1 << 10, 64 << 10, 1 << 20};
    private static final long TARGET_BYTES = Long.getLong("targetBytes", 64L << 20);
    private static final int MAX_ITERATIONS = Integer.getInteger("maxIterations", 100_000);
    private static final int MIN_ITERATIONS = Integer.getInteger("minIterations", 128);
    private static final int WARMUPS = Integer.getInteger("warmups", 3);
    private static final int LATENCY_SAMPLES = Integer.getInteger("latencySamples", 2_000);
    private static final int METRIC_ITERATIONS = Integer.getInteger("metricIterations", 100_000);
    private static final int LOAD_ITERATIONS = Integer.getInteger("loadIterations", 3_000);
    private static final int LOAD_SESSION_COUNT = Integer.getInteger("loadSessions", 4);
    private static final int LOAD_PAYLOAD_BYTES = Integer.getInteger("loadPayloadBytes", 64 << 10);
    private static final long LOAD_WORK_NANOS = TimeUnit.MICROSECONDS.toNanos(
            Long.getLong("loadWorkMicros", 250L));
    private static final int FRAGMENTS = Integer.getInteger("fragments", 4);
    private static final String BENCHMARK_MODE = System.getProperty("benchmarkMode", "all");
    private static final ThreadMXBean ALLOCATION_BEAN = allocationBean();
    private static volatile long blackhole;

    public static void main(String[] args) {
        System.out.printf("java=%s feature=%d targetBytes=%d warmups=%d fragments=%d benchmarkMode=%s%n",
                          Runtime.version(), Runtime.version().feature(), TARGET_BYTES, WARMUPS, FRAGMENTS,
                          BENCHMARK_MODE);
        if (modeEnabled("receive")) {
            for (int messageSize : MESSAGE_SIZES) {
                runComparison(messageSize, 1);
                runComparison(messageSize, FRAGMENTS);
            }
        }
        if (modeEnabled("load")) {
            runBoundedLoadComparison();
        }
        if (modeEnabled("metrics")) {
            runTransportMetricComparison();
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static boolean modeEnabled(String mode) {
        if ("all".equals(BENCHMARK_MODE) || mode.equals(BENCHMARK_MODE)) {
            return true;
        }
        if (List.of("receive", "load", "metrics").contains(BENCHMARK_MODE)) {
            return false;
        }
        throw new IllegalArgumentException(
                "benchmarkMode must be one of all, receive, load, or metrics: " + BENCHMARK_MODE);
    }

    private static void runComparison(int messageSize, int fragments) {
        int iterations = Math.max(MIN_ITERATIONS,
                                  (int) Math.min(MAX_ITERATIONS, TARGET_BYTES / messageSize));
        try (Scenario legacyMetricsOff = new Scenario(messageSize, fragments, false, false);
             Scenario legacyMetricsOn = new Scenario(messageSize, fragments, false, true);
             Scenario isolatedMetricsOff = new Scenario(messageSize, fragments, true, false);
             Scenario isolatedMetricsOn = new Scenario(messageSize, fragments, true, true)) {
            for (int i = 0; i < WARMUPS; i++) {
                legacyMetricsOff.run(iterations);
                legacyMetricsOn.run(iterations);
                isolatedMetricsOff.run(iterations);
                isolatedMetricsOn.run(iterations);
            }
            measure("low-level-direct-baseline-metrics-off", legacyMetricsOff, iterations);
            measure("low-level-direct-baseline-metrics-on", legacyMetricsOn, iterations);
            measure("runtime-isolated-metrics-off", isolatedMetricsOff, iterations);
            measure("runtime-isolated-metrics-on", isolatedMetricsOn, iterations);
            measureLatency("low-level-direct-baseline-metrics-off", legacyMetricsOff,
                           Math.min(iterations, LATENCY_SAMPLES));
            measureLatency("low-level-direct-baseline-metrics-on", legacyMetricsOn,
                           Math.min(iterations, LATENCY_SAMPLES));
            measureLatency("runtime-isolated-metrics-off", isolatedMetricsOff,
                           Math.min(iterations, LATENCY_SAMPLES));
            measureLatency("runtime-isolated-metrics-on", isolatedMetricsOn,
                           Math.min(iterations, LATENCY_SAMPLES));
        }
    }

    private static void runBoundedLoadComparison() {
        for (CompressionAlgorithm compression : List.of(CompressionAlgorithm.LZ4, CompressionAlgorithm.ZSTD)) {
            for (int sessions : List.of(1, LOAD_SESSION_COUNT)) {
                double singleWorkerElapsed = 0d;
                for (int concurrency = 1;
                     concurrency <= JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES; concurrency++) {
                    try (BoundedLoadScenario scenario = new BoundedLoadScenario(
                            concurrency, sessions, compression, false)) {
                        for (int i = 0; i < WARMUPS; i++) {
                            scenario.run(LOAD_ITERATIONS);
                        }
                        BoundedLoadMeasurement measurement = measureBoundedLoad(scenario, LOAD_ITERATIONS);
                        if (concurrency == 1) {
                            singleWorkerElapsed = measurement.elapsedNanos();
                        } else {
                            System.out.printf(
                                    "runtime-bounded-speedup compression=%s sessions=%d concurrency=%d: %.2fx%n",
                                    compression, sessions, concurrency,
                                    singleWorkerElapsed / measurement.elapsedNanos());
                        }
                    }
                }
            }
        }
        runMetricsEnabledBoundedLoad();
        runConfiguredCapacitySmoke();
    }

    private static void runMetricsEnabledBoundedLoad() {
        for (int sessions : List.of(1, LOAD_SESSION_COUNT)) {
            try (BoundedLoadScenario scenario = new BoundedLoadScenario(
                    JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES, sessions, CompressionAlgorithm.LZ4, true)) {
                for (int i = 0; i < WARMUPS; i++) {
                    scenario.run(LOAD_ITERATIONS);
                }
                measureBoundedLoad(scenario, LOAD_ITERATIONS);
            }
        }
    }

    private static void runConfiguredCapacitySmoke() {
        try (BoundedLoadScenario scenario = new BoundedLoadScenario(
                2, 1, CompressionAlgorithm.LZ4, false, 7, 256L * 1024)) {
            scenario.run(LOAD_ITERATIONS);
            measureBoundedLoad(scenario, LOAD_ITERATIONS);
        }
    }

    private static BoundedLoadMeasurement measureBoundedLoad(BoundedLoadScenario scenario, int iterations) {
        stabilizeHeap();
        BoundedLoadMeasurement measurement = scenario.measure(iterations);
        long[] latencies = measurement.batchDrainLatencies();
        Arrays.sort(latencies);
        System.out.printf("runtime-bounded-load compression=%s sessions=%d concurrency=%d metrics=%s "
                                  + "maxRetainedMessages=%d maxRetainedBytes=%d compressedBytes=%d "
                                  + "retainedMessagesPerSession=%d retainedUpperBoundBytes=%d "
                                  + "iterations=%d: "
                                  + "%.2f ns/op, %.1f ops/s, batchDrainP50=%dns, batchDrainP95=%dns, "
                                  + "batchDrainP99=%dns%n",
                          scenario.compression, scenario.sessions.size(), scenario.maxConcurrency,
                          scenario.transportMetrics, scenario.maxRetainedMessages, scenario.maxRetainedBytes,
                          scenario.payload.length,
                          scenario.messagesPerSession,
                          (long) scenario.sessions.size() * scenario.messagesPerSession * scenario.payload.length,
                          measurement.iterations(),
                          (double) measurement.elapsedNanos() / measurement.iterations(),
                          measurement.iterations() * 1_000_000_000d / measurement.elapsedNanos(),
                          percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99));
        return measurement;
    }

    private static void runTransportMetricComparison() {
        try (TransportMetricScenario metricsOff = new TransportMetricScenario(false);
             TransportMetricScenario metricsOn = new TransportMetricScenario(true)) {
            for (int i = 0; i < WARMUPS; i++) {
                metricsOff.run(METRIC_ITERATIONS);
                metricsOn.run(METRIC_ITERATIONS);
            }
            measureTransportMetric("transport-anomaly-metrics-off", metricsOff, METRIC_ITERATIONS);
            measureTransportMetric("transport-anomaly-metrics-on", metricsOn, METRIC_ITERATIONS);
            measureTransportMetricLatency("transport-anomaly-metrics-off", metricsOff, LATENCY_SAMPLES);
            measureTransportMetricLatency("transport-anomaly-metrics-on", metricsOn, LATENCY_SAMPLES);
        }
    }

    private static void measure(String name, Scenario scenario, int iterations) {
        stabilizeHeap();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = ALLOCATION_BEAN == null ? 0L : ALLOCATION_BEAN.getThreadAllocatedBytes(threadId);
        GcSnapshot gcBefore = GcSnapshot.capture();
        long started = System.nanoTime();
        scenario.run(iterations);
        long elapsed = System.nanoTime() - started;
        GcSnapshot gcDelta = GcSnapshot.capture().minus(gcBefore);
        long allocated = ALLOCATION_BEAN == null ? 0L
                : ALLOCATION_BEAN.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%s size=%d fragments=%d iterations=%d: %.2f ns/op, %.2f bytes/op, %.1f K ops/s, "
                                  + "gcCollections=%d, gcMillis=%d%n",
                          name, scenario.payload.length, scenario.fragments, iterations,
                          (double) elapsed / iterations, (double) allocated / iterations,
                          iterations * 1_000_000d / elapsed, gcDelta.collections, gcDelta.millis);
    }

    private static void measureLatency(String name, Scenario scenario, int samples) {
        stabilizeHeap();
        long[] latencies = new long[samples];
        for (int i = 0; i < samples; i++) {
            long started = System.nanoTime();
            scenario.runOne();
            latencies[i] = System.nanoTime() - started;
        }
        Arrays.sort(latencies);
        System.out.printf("%s-latency size=%d fragments=%d samples=%d: p50=%dns, p95=%dns, p99=%dns%n",
                          name, scenario.payload.length, scenario.fragments, samples,
                          percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99));
    }

    private static void measureTransportMetric(String name, TransportMetricScenario scenario, int iterations) {
        stabilizeHeap();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = ALLOCATION_BEAN == null ? 0L : ALLOCATION_BEAN.getThreadAllocatedBytes(threadId);
        GcSnapshot gcBefore = GcSnapshot.capture();
        long started = System.nanoTime();
        scenario.run(iterations);
        long elapsed = System.nanoTime() - started;
        GcSnapshot gcDelta = GcSnapshot.capture().minus(gcBefore);
        long allocated = ALLOCATION_BEAN == null ? 0L
                : ALLOCATION_BEAN.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%s iterations=%d: %.2f ns/op, %.2f bytes/op, %.1f K ops/s, "
                                  + "gcCollections=%d, gcMillis=%d%n",
                          name, iterations, (double) elapsed / iterations, (double) allocated / iterations,
                          iterations * 1_000_000d / elapsed, gcDelta.collections, gcDelta.millis);
    }

    private static void measureTransportMetricLatency(String name, TransportMetricScenario scenario, int samples) {
        stabilizeHeap();
        long[] latencies = new long[samples];
        for (int i = 0; i < samples; i++) {
            long started = System.nanoTime();
            scenario.runOne();
            latencies[i] = System.nanoTime() - started;
        }
        Arrays.sort(latencies);
        System.out.printf("%s-latency samples=%d: p50=%dns, p95=%dns, p99=%dns%n",
                          name, samples, percentile(latencies, 0.50), percentile(latencies, 0.95),
                          percentile(latencies, 0.99));
    }

    private static long percentile(long[] sortedValues, double percentile) {
        int index = Math.min(sortedValues.length - 1, (int) Math.ceil(sortedValues.length * percentile) - 1);
        return sortedValues[Math.max(0, index)];
    }

    private static void stabilizeHeap() {
        System.gc();
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean threadBean) || !threadBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }
        return threadBean;
    }

    private static class Scenario implements AutoCloseable {
        private final byte[] payload;
        private final int fragments;
        private final JdkWebSocketSession session;
        private final WebSocket.Listener listener;
        private final BenchmarkWebSocket webSocket = new BenchmarkWebSocket();

        private Scenario(int messageSize, int fragments, boolean isolated, boolean transportMetrics) {
            this.payload = new byte[messageSize];
            this.payload[0] = 1;
            this.fragments = fragments;
            Map<String, Object> userProperties = userProperties(isolated, transportMetrics);
            WebsocketEndpoint endpoint = new BenchmarkEndpoint();
            this.session = new JdkWebSocketSession(
                    new JdkWebsocketConnector(),
                    isolated ? new SdkRuntimeWebsocketEndpoint(endpoint) : endpoint,
                    new WebsocketConnectionOptions(Map.of(), userProperties, Duration.ofSeconds(1), List.of()),
                    URI.create("ws://localhost/benchmark"),
                    new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, Runnable::run);
            this.listener = session.createListener();
            listener.onOpen(webSocket);
        }

        private void run(int iterations) {
            for (int i = 0; i < iterations; i++) {
                runOne();
            }
        }

        private void runOne() {
            int offset = 0;
            for (int fragment = 0; fragment < fragments; fragment++) {
                int remainingFragments = fragments - fragment;
                int length = (payload.length - offset) / remainingFragments;
                boolean last = fragment == fragments - 1;
                listener.onBinary(webSocket, ByteBuffer.wrap(payload, offset, length), last);
                offset += length;
            }
        }

        @Override
        public void close() {
            session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "benchmark complete"));
        }
    }

    private static class BoundedLoadScenario implements AutoCloseable {
        private final int maxConcurrency;
        private final CompressionAlgorithm compression;
        private final boolean transportMetrics;
        private final int maxRetainedMessages;
        private final long maxRetainedBytes;
        private final byte[] payload;
        private final int messagesPerSession;
        private final ExecutorService executor;
        private final Semaphore processed = new Semaphore(0);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<JdkWebSocketSession> sessions;
        private final List<WebSocket.Listener> listeners;
        private final List<BenchmarkWebSocket> webSockets;

        private BoundedLoadScenario(int maxConcurrency, int sessionCount, CompressionAlgorithm compression,
                                    boolean transportMetrics) {
            this(maxConcurrency, sessionCount, compression, transportMetrics,
                 JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                 JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES);
        }

        private BoundedLoadScenario(int maxConcurrency, int sessionCount, CompressionAlgorithm compression,
                                    boolean transportMetrics, int maxRetainedMessages, long maxRetainedBytes) {
            this.maxConcurrency = maxConcurrency;
            this.compression = compression;
            this.transportMetrics = transportMetrics;
            this.maxRetainedMessages = maxRetainedMessages;
            this.maxRetainedBytes = maxRetainedBytes;
            this.payload = compressedLoadPayload(compression);
            this.messagesPerSession = Math.max(1, Math.min(
                    maxRetainedMessages, Math.toIntExact(maxRetainedBytes / payload.length)));
            this.executor = Executors.newFixedThreadPool(
                    sessionCount * JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
            this.sessions = new java.util.ArrayList<>(sessionCount);
            this.listeners = new java.util.ArrayList<>(sessionCount);
            this.webSockets = new java.util.ArrayList<>(sessionCount);
            LoadEndpoint endpoint = new LoadEndpoint(compression, processed, failure);
            Map<String, Object> userProperties = new java.util.HashMap<>(
                    userProperties(true, transportMetrics));
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY,
                               maxConcurrency);
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY,
                               maxRetainedMessages);
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY,
                               maxRetainedBytes);
            for (int i = 0; i < sessionCount; i++) {
                JdkWebSocketSession session = new JdkWebSocketSession(
                        new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(endpoint),
                        new WebsocketConnectionOptions(
                                Map.of(), userProperties, Duration.ofSeconds(1), List.of()),
                        URI.create("ws://localhost/benchmark-load/" + i),
                        new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, executor);
                JdkWebSocketSession.RuntimeDataState initialState = session.runtimeDataState();
                if (initialState.maxConcurrency() != maxConcurrency
                        || initialState.maxRetainedMessages() != maxRetainedMessages
                        || initialState.maxRetainedBytes() != maxRetainedBytes) {
                    throw new IllegalStateException("Runtime dispatcher did not apply the benchmark capacity");
                }
                BenchmarkWebSocket webSocket = new BenchmarkWebSocket();
                WebSocket.Listener listener = session.createListener();
                listener.onOpen(webSocket);
                sessions.add(session);
                listeners.add(listener);
                webSockets.add(webSocket);
            }
        }

        private void run(int iterations) {
            execute(iterations, false);
        }

        private BoundedLoadMeasurement measure(int iterations) {
            return execute(iterations, true);
        }

        private BoundedLoadMeasurement execute(int requestedIterations, boolean captureLatencies) {
            int batchCapacity = sessions.size() * messagesPerSession;
            int iterations = Math.max(batchCapacity, requestedIterations / batchCapacity * batchCapacity);
            long[] latencies = captureLatencies ? new long[iterations / batchCapacity] : new long[0];
            long totalStarted = System.nanoTime();
            int latencyIndex = 0;
            int remaining = iterations;
            while (remaining > 0) {
                long batchStarted = System.nanoTime();
                for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
                    WebSocket.Listener listener = listeners.get(sessionIndex);
                    WebSocket webSocket = webSockets.get(sessionIndex);
                    for (int i = 0; i < messagesPerSession; i++) {
                        listener.onBinary(webSocket, ByteBuffer.wrap(payload), true);
                    }
                }
                awaitProcessed(batchCapacity);
                awaitRuntimeDataDrain();
                if (captureLatencies) {
                    latencies[latencyIndex++] = System.nanoTime() - batchStarted;
                }
                remaining -= batchCapacity;
            }
            long elapsed = System.nanoTime() - totalStarted;
            return new BoundedLoadMeasurement(iterations, elapsed, latencies);
        }

        private void awaitProcessed(int batchSize) {
            try {
                if (!processed.tryAcquire(batchSize, 30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out awaiting bounded runtime work");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting bounded runtime work", e);
            }
            Throwable endpointFailure = failure.get();
            if (endpointFailure != null) {
                throw new IllegalStateException("Bounded runtime work failed", endpointFailure);
            }
        }

        private void awaitRuntimeDataDrain() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (sessions.stream().anyMatch(session -> session.runtimeDataState().retainedMessages() != 0)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Timed out awaiting runtime dispatcher bookkeeping");
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public void close() {
            sessions.forEach(session -> session.abort(
                    new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "benchmark complete")));
            executor.shutdownNow();
        }
    }

    private static byte[] compressedLoadPayload(CompressionAlgorithm compression) {
        return LoadPayloads.PAYLOADS.get(compression).clone();
    }

    private static byte[] createCompressedLoadPayload(CompressionAlgorithm compression) {
        try {
            StringBuilder value = new StringBuilder(LOAD_PAYLOAD_BYTES);
            int state = 0x13579bdf;
            for (int i = 0; i < LOAD_PAYLOAD_BYTES; i++) {
                state = state * 1_103_515_245 + 12_345;
                value.append((char) (' ' + Math.floorMod(state, 95)));
            }
            WebSocketTransportCodec codec = WebSocketTransportCodecs.json(
                    AbstractWebsocketClient.defaultObjectMapper);
            return compression.compress(codec.encode(new StringResult(1L, value.toString())));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create bounded-load payload", e);
        }
    }

    private static class LoadPayloads {
        private static final Map<CompressionAlgorithm, byte[]> PAYLOADS = Map.of(
                CompressionAlgorithm.LZ4, createCompressedLoadPayload(CompressionAlgorithm.LZ4),
                CompressionAlgorithm.ZSTD, createCompressedLoadPayload(CompressionAlgorithm.ZSTD));
    }

    private record BoundedLoadMeasurement(int iterations, long elapsedNanos, long[] batchDrainLatencies) {
    }

    private static Map<String, Object> userProperties(boolean isolated, boolean transportMetrics) {
        if (isolated && transportMetrics) {
            return Map.of(JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true,
                          JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true);
        }
        if (isolated) {
            return Map.of(JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true);
        }
        if (transportMetrics) {
            return Map.of(JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true);
        }
        return Map.of();
    }

    private static class TransportMetricScenario implements AutoCloseable {
        private static final JdkWebSocketSession.RuntimeDataDispatchException ANOMALY =
                JdkWebSocketSession.RuntimeDataDispatchException.overflow(
                        new JdkWebSocketSession.RuntimeDataState(
                                2, 4_096L, 2, 4_096L, 2, 4_096L, 0, 0L,
                                JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES, 0L));

        private final TransportMetricBenchmarkClient client;
        private final JdkWebSocketSession session;
        private final Logger logger;
        private final Level previousLogLevel;

        private TransportMetricScenario(boolean transportMetrics) {
            client = new TransportMetricBenchmarkClient(transportMetrics);
            logger = (Logger) LoggerFactory.getLogger(
                    "%s.%s".formatted(client.getClass().getPackageName(), client));
            previousLogLevel = logger.getLevel();
            logger.setLevel(Level.OFF);
            Map<String, Object> userProperties = transportMetrics
                    ? Map.of(JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true,
                             AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY, "benchmark_runtime",
                             AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "benchmark",
                             AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                             CompressionAlgorithm.NONE)
                    : Map.of(AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY, "benchmark_runtime",
                             AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "benchmark",
                             AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                             CompressionAlgorithm.NONE);
            session = new JdkWebSocketSession(
                    new JdkWebsocketConnector(), new BenchmarkEndpoint(),
                    new WebsocketConnectionOptions(Map.of(), userProperties, Duration.ofSeconds(1), List.of()),
                    URI.create("ws://localhost/benchmark"),
                    new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run);
        }

        private void run(int iterations) {
            for (int i = 0; i < iterations; i++) {
                runOne();
            }
        }

        private void runOne() {
            client.handleError(session, ANOMALY);
        }

        @Override
        public void close() {
            try {
                client.close();
            } finally {
                logger.setLevel(previousLogLevel);
            }
        }
    }

    private static class TransportMetricBenchmarkClient extends AbstractWebsocketClient {
        private TransportMetricBenchmarkClient(boolean transportMetrics) {
            super((endpoint, options, uri) -> {
                      throw new UnsupportedOperationException("Benchmark connector must not connect");
                  }, URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("transport-metric-benchmark")
                                                      .build()),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1,
                  new SimplePropertySource(transportMetrics
                                                   ? Map.of(TRANSPORT_METRICS_ENABLED_PROPERTY, "true") : Map.of()),
                  (client, numberOfSessions) -> NoOpTaskScheduler.INSTANCE);
        }

        @Override
        void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
            blackhole += Message.asMessage(metric).addMetadata(metadata)
                    .serialize(getFallbackSerializer()).getBytes();
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

    private static class BenchmarkEndpoint implements WebsocketEndpoint {
        @Override
        public void onOpen(WebsocketSession session) {
        }

        @Override
        public void onMessage(byte[] bytes, WebsocketSession session) {
            blackhole += bytes[0];
        }

        @Override
        public void onPong(ByteBuffer data, WebsocketSession session) {
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
            throw new IllegalStateException("Unexpected benchmark transport failure", error);
        }
    }

    private record LoadEndpoint(CompressionAlgorithm compression, Semaphore processed,
                                AtomicReference<Throwable> failure) implements WebsocketEndpoint {
        private static final WebSocketTransportCodec CODEC = WebSocketTransportCodecs.json(
                AbstractWebsocketClient.defaultObjectMapper);

        @Override
        public void onOpen(WebsocketSession session) {
        }

        @Override
        public void onMessage(byte[] bytes, WebsocketSession session) {
            try {
                JsonType decoded = CODEC.decode(compression.decompress(bytes));
                blackhole += ((StringResult) decoded).getResult().length();
                LockSupport.parkNanos(LOAD_WORK_NANOS);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                throw new IllegalStateException("Could not decode bounded-load payload", e);
            } finally {
                processed.release();
            }
        }

        @Override
        public void onPong(ByteBuffer data, WebsocketSession session) {
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    private static class BenchmarkWebSocket implements WebSocket {
        private long requested;
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
            requested += n;
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
            blackhole += requested;
        }
    }

    private record GcSnapshot(long collections, long millis) {
        private static GcSnapshot capture() {
            long collections = 0L;
            long millis = 0L;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                collections += Math.max(0L, bean.getCollectionCount());
                millis += Math.max(0L, bean.getCollectionTime());
            }
            return new GcSnapshot(collections, millis);
        }

        private GcSnapshot minus(GcSnapshot previous) {
            return new GcSnapshot(collections - previous.collections, millis - previous.millis);
        }
    }
}
