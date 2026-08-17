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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodec;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Opt-in microbenchmark for SDK runtime-message isolation and bounded parallel processing.
 *
 * <p>The low-level direct baseline deliberately omits the internal SDK runtime marker. Production SDK clients always
 * enable isolation; the baseline exists only to quantify its bookkeeping cost. The isolated scenario uses a direct
 * backing executor so executor scheduling variance does not dominate this comparison. Each receive scenario runs with
 * transport metrics disabled and enabled. A separate bounded-load comparison uses a controlled fixed worker pool to
 * measure effective per-session concurrency with one through three runtime workers. Its latency percentiles represent
 * the time to drain one full retained-capacity batch, not an amortized per-message latency. An anomaly comparison
 * measures metric construction, fallback serialization, and hand-off to a local sink without network variance. The
 * result-completion comparison contrasts a synthetic direct control with the bounded incremental dispatcher, using
 * the same deterministic manual executor for both paths. It is not a cross-version comparison. A focused small-result
 * load drives the full decode and result-completion path with compressed payloads matching observed production bursts,
 * without artificial callback work. A large-message profile calibrates valid runtime responses to 20 and 35 MiB of
 * compressed wire data, offers messages until protocol demand defers one, and compares backpressure, throughput and
 * peak heap at 16, 64 and 128 MiB retained-byte limits.</p>
 */
public class WebsocketRuntimeDispatchBenchmark {
    private static final int[] MESSAGE_SIZES = {128, 512, 1 << 10, 64 << 10, 1 << 20};
    private static final long TARGET_BYTES = Long.getLong("targetBytes", 64L << 20);
    private static final int MAX_ITERATIONS = Integer.getInteger("maxIterations", 100_000);
    private static final int MIN_ITERATIONS = Integer.getInteger("minIterations", 128);
    private static final int WARMUPS = Integer.getInteger("warmups", 3);
    private static final int LATENCY_SAMPLES = Integer.getInteger("latencySamples", 2_000);
    private static final int METRIC_ITERATIONS = Integer.getInteger("metricIterations", 100_000);
    private static final int LOAD_ITERATIONS = Integer.getInteger("loadIterations", 3_000);
    private static final int SMALL_LOAD_ITERATIONS = Integer.getInteger("smallLoadIterations", 20_000);
    private static final int LARGE_LOAD_ITERATIONS = Integer.getInteger("largeLoadIterations", 8);
    private static final int LARGE_LOAD_WARMUPS = Integer.getInteger("largeLoadWarmups", 1);
    private static final int LARGE_LOAD_TRACKING_MESSAGES = Integer.getInteger(
            "largeLoadTrackingMessages", 64);
    private static final int LOAD_SESSION_COUNT = Integer.getInteger("loadSessions", 4);
    private static final int SMALL_LOAD_MAX_CONCURRENCY = Integer.getInteger(
            "smallLoadMaxConcurrency", JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
    private static final int SMALL_LOAD_MAX_RETAINED_MESSAGES = Integer.getInteger(
            "smallLoadMaxRetainedMessages", JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES);
    private static final int RESULT_COMPLETION_CONCURRENCY = Integer.getInteger(
            "resultConcurrency", 8);
    private static final int LOAD_PAYLOAD_BYTES = Integer.getInteger("loadPayloadBytes", 64 << 10);
    private static final int[] SMALL_RESULT_VALUE_BYTES = {16, 320};
    private static final int[] LARGE_LOAD_TARGET_COMPRESSED_BYTES = {
            Math.multiplyExact(Integer.getInteger("largeLoadSmallMiB", 20), 1 << 20),
            Math.multiplyExact(Integer.getInteger("largeLoadLargeMiB", 35), 1 << 20)};
    private static final long[] LARGE_LOAD_RETAINED_BYTES = {16L << 20, 64L << 20, 128L << 20};
    private static final CompressionAlgorithm LARGE_LOAD_COMPRESSION = CompressionAlgorithm.valueOf(
            System.getProperty("largeLoadCompression", "LZ4"));
    private static final int COMPLETION_TARGET_RESULTS = Integer.getInteger(
            "completionTargetResults", 1_000_000);
    private static final long LOAD_WORK_NANOS = TimeUnit.MICROSECONDS.toNanos(
            Long.getLong("loadWorkMicros", 250L));
    private static final long LARGE_LOAD_WORK_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Long.getLong("largeLoadWorkMillis", 25L));
    private static final int FRAGMENTS = Integer.getInteger("fragments", 4);
    private static final String BENCHMARK_MODE = System.getProperty("benchmarkMode", "all");
    private static final ThreadMXBean ALLOCATION_BEAN = allocationBean();
    private static final Map<LoadPayloadKey, byte[]> LOAD_PAYLOADS = new ConcurrentHashMap<>();
    private static final Map<LargeLoadPayloadKey, SizedLoadPayload> LARGE_LOAD_PAYLOADS = new ConcurrentHashMap<>();
    private static volatile long blackhole;

    public static void main(String[] args) {
        System.out.printf("java=%s feature=%d targetBytes=%d warmups=%d fragments=%d benchmarkMode=%s "
                                  + "resultConcurrency=%d smallLoadMaxConcurrency=%d "
                                  + "smallLoadMaxRetainedMessages=%d%n",
                          Runtime.version(), Runtime.version().feature(), TARGET_BYTES, WARMUPS, FRAGMENTS,
                          BENCHMARK_MODE, RESULT_COMPLETION_CONCURRENCY, SMALL_LOAD_MAX_CONCURRENCY,
                          SMALL_LOAD_MAX_RETAINED_MESSAGES);
        if (modeEnabled("receive")) {
            for (int messageSize : MESSAGE_SIZES) {
                runComparison(messageSize, 1);
                runComparison(messageSize, FRAGMENTS);
            }
        }
        if (modeEnabled("load")) {
            runBoundedLoadComparison();
        }
        if (modeEnabled("small-load")) {
            runSmallResultLoadComparison();
        }
        if (modeEnabled("large-load")) {
            runLargeMessageLoadComparison();
        }
        if (modeEnabled("metrics")) {
            runTransportMetricComparison();
        }
        if (modeEnabled("completion")) {
            runResultCompletionComparison();
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static boolean modeEnabled(String mode) {
        if ("all".equals(BENCHMARK_MODE) || mode.equals(BENCHMARK_MODE)) {
            return true;
        }
        if (List.of("receive", "load", "small-load", "large-load", "metrics", "completion")
                .contains(BENCHMARK_MODE)) {
            return false;
        }
        throw new IllegalArgumentException(
                "benchmarkMode must be one of all, receive, load, small-load, large-load, metrics, or completion: "
                + BENCHMARK_MODE);
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
            for (int sessions : loadSessionCounts(LOAD_SESSION_COUNT)) {
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
        for (int sessions : loadSessionCounts(LOAD_SESSION_COUNT)) {
            try (BoundedLoadScenario scenario = new BoundedLoadScenario(
                    JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES, sessions, CompressionAlgorithm.LZ4, true)) {
                for (int i = 0; i < WARMUPS; i++) {
                    scenario.run(LOAD_ITERATIONS);
                }
                measureBoundedLoad(scenario, LOAD_ITERATIONS);
            }
        }
    }

    private static void runSmallResultLoadComparison() {
        for (int valueBytes : SMALL_RESULT_VALUE_BYTES) {
            for (CompressionAlgorithm compression : List.of(CompressionAlgorithm.LZ4, CompressionAlgorithm.ZSTD)) {
                for (int sessions : loadSessionCounts(LOAD_SESSION_COUNT)) {
                    measureSmallResultLoad(valueBytes, compression, sessions, false, false);
                    measureSmallResultLoad(valueBytes, compression, sessions, false, true);
                }
            }
            for (int sessions : loadSessionCounts(LOAD_SESSION_COUNT)) {
                measureSmallResultLoad(valueBytes, CompressionAlgorithm.LZ4, sessions, true, true);
            }
        }
    }

    private static void runLargeMessageLoadComparison() {
        for (int targetCompressedBytes : LARGE_LOAD_TARGET_COMPRESSED_BYTES) {
            SizedLoadPayload payload = compressedLoadPayloadNear(LARGE_LOAD_COMPRESSION, targetCompressedBytes);
            for (long maxRetainedBytes : LARGE_LOAD_RETAINED_BYTES) {
                for (int i = 0; i < LARGE_LOAD_WARMUPS; i++) {
                    try (LargeMessageLoadScenario warmup = new LargeMessageLoadScenario(
                            payload, LARGE_LOAD_COMPRESSION, maxRetainedBytes)) {
                        warmup.run(Math.min(LARGE_LOAD_ITERATIONS, 4));
                    }
                }
                stabilizeHeap();
                GcSnapshot gcBefore = GcSnapshot.capture();
                HeapPeakSnapshot heapPeak = HeapPeakSnapshot.start();
                try (LargeMessageLoadScenario scenario = new LargeMessageLoadScenario(
                        payload, LARGE_LOAD_COMPRESSION, maxRetainedBytes)) {
                    LargeLoadMeasurement measurement = scenario.measure(LARGE_LOAD_ITERATIONS);
                    GcSnapshot gcDelta = GcSnapshot.capture().minus(gcBefore);
                    long[] latencies = measurement.burstDrainLatencies();
                    Arrays.sort(latencies);
                    double compressedMiB = payload.bytes().length / (double) (1 << 20);
                    System.out.printf(
                            "runtime-large-message compression=%s targetCompressedBytes=%d compressedBytes=%d "
                                    + "valueBytes=%d trackingMessagesPerResponse=%d maxRetainedMessages=%d "
                                    + "maxRetainedBytes=%d workNanos=%d "
                                    + "messages=%d backpressureEpisodes=%d: %.3f ms/message, %.2f messages/s, "
                                    + "%.2f compressed-MiB/s, burstP50=%dns, burstP95=%dns, burstP99=%dns, "
                                    + "heapPeakDeltaBytes=%d, gcCollections=%d, gcMillis=%d%n",
                            LARGE_LOAD_COMPRESSION, payload.targetCompressedBytes(), payload.bytes().length,
                            payload.valueBytes(), payload.trackingMessages(),
                            JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                            maxRetainedBytes, LARGE_LOAD_WORK_NANOS, measurement.iterations(),
                            measurement.backpressureEpisodes(),
                            measurement.elapsedNanos() / 1_000_000d / measurement.iterations(),
                            measurement.iterations() * 1_000_000_000d / measurement.elapsedNanos(),
                            measurement.iterations() * compressedMiB * 1_000_000_000d / measurement.elapsedNanos(),
                            percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
                            heapPeak.deltaBytes(), gcDelta.collections, gcDelta.millis);
                }
            }
        }
    }

    private static void measureSmallResultLoad(
            int valueBytes, CompressionAlgorithm compression, int sessions, boolean transportMetrics,
            boolean boundedCompletion) {
        try (BoundedLoadScenario scenario = boundedCompletion
                ? BoundedLoadScenario.smallResult(sessions, compression, transportMetrics, valueBytes)
                : BoundedLoadScenario.smallDirect(sessions, compression, valueBytes)) {
            for (int i = 0; i < WARMUPS; i++) {
                scenario.run(SMALL_LOAD_ITERATIONS);
            }
            measureBoundedLoad(scenario, SMALL_LOAD_ITERATIONS);
        }
    }

    static List<Integer> loadSessionCounts(int configuredSessionCount) {
        if (configuredSessionCount < 1) {
            throw new IllegalArgumentException("loadSessions must be at least 1");
        }
        return configuredSessionCount == 1 ? List.of(1) : List.of(1, configuredSessionCount);
    }

    static int loadWorkerCount(int sessionCount, int messageConcurrency) {
        if (sessionCount < 1 || messageConcurrency < 1) {
            throw new IllegalArgumentException("Load sessions and message concurrency must be at least 1");
        }
        return Math.multiplyExact(sessionCount, messageConcurrency);
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
        GcSnapshot gcBefore = GcSnapshot.capture();
        BoundedLoadMeasurement measurement = scenario.measure(iterations);
        GcSnapshot gcDelta = GcSnapshot.capture().minus(gcBefore);
        long[] latencies = measurement.batchDrainLatencies();
        Arrays.sort(latencies);
        System.out.printf("runtime-bounded-load completion=%s valueBytes=%d workNanos=%d compression=%s "
                                  + "sessions=%d concurrency=%d metrics=%s "
                                  + "maxRetainedMessages=%d maxRetainedBytes=%d compressedBytes=%d "
                                  + "retainedMessagesPerSession=%d retainedUpperBoundBytes=%d "
                                  + "iterations=%d: "
                                  + "%.2f ns/op, %.1f ops/s, batchDrainP50=%dns, batchDrainP95=%dns, "
                                  + "batchDrainP99=%dns, gcCollections=%d, gcMillis=%d%n",
                          scenario.functionalCompletion ? "bounded" : "direct", scenario.valueBytes,
                          scenario.workNanos,
                          scenario.compression, scenario.sessions.size(), scenario.maxConcurrency,
                          scenario.transportMetrics, scenario.maxRetainedMessages, scenario.maxRetainedBytes,
                          scenario.payload.length,
                          scenario.messagesPerSession,
                          (long) scenario.sessions.size() * scenario.messagesPerSession * scenario.payload.length,
                          measurement.iterations(),
                          (double) measurement.elapsedNanos() / measurement.iterations(),
                          measurement.iterations() * 1_000_000_000d / measurement.elapsedNanos(),
                          percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
                          gcDelta.collections, gcDelta.millis);
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

    private static void runResultCompletionComparison() {
        for (int batchSize : List.of(1, 32, 1_024)) {
            int iterations = Math.max(128, COMPLETION_TARGET_RESULTS / batchSize);
            try (ResultCompletionScenario legacy = new ResultCompletionScenario(
                    batchSize, true, RESULT_COMPLETION_CONCURRENCY);
                 ResultCompletionScenario bounded = new ResultCompletionScenario(
                         batchSize, false, RESULT_COMPLETION_CONCURRENCY)) {
                for (int i = 0; i < WARMUPS; i++) {
                    legacy.run(iterations);
                    bounded.run(iterations);
                }
                measureResultCompletion("legacy-result-completion", legacy, iterations);
                measureResultCompletion("bounded-result-completion", bounded, iterations);
            }
        }
    }

    private static void measureResultCompletion(
            String name, ResultCompletionScenario scenario, int iterations) {
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
        long results = (long) iterations * scenario.batchSize;
        System.out.printf("%s batchSize=%d iterations=%d results=%d: %.2f ns/result, %.2f bytes/result, "
                                  + "maxQueuedTasks=%d, gcCollections=%d, gcMillis=%d%n",
                          name, scenario.batchSize, iterations, results,
                          (double) elapsed / results, (double) allocated / results,
                          scenario.maxQueuedTasks(), gcDelta.collections, gcDelta.millis);
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

    static class ResultCompletionScenario implements AutoCloseable {
        private final int batchSize;
        private final boolean legacy;
        private final MeasuringExecutor executor = new MeasuringExecutor();
        private final RuntimeResultDispatcher dispatcher;
        private final List<Integer> batchResults;
        private final Consumer<Integer> resultHandler = this::consumeResult;

        ResultCompletionScenario(int batchSize, boolean legacy, int concurrency) {
            this.batchSize = batchSize;
            this.legacy = legacy;
            this.dispatcher = legacy ? null : new RuntimeResultDispatcher(executor, concurrency);
            this.batchResults = java.util.stream.IntStream.range(0, batchSize).boxed().toList();
        }

        void run(int iterations) {
            executor.resetMaximum();
            for (int i = 0; i < iterations; i++) {
                runOne();
            }
        }

        int maxQueuedTasks() {
            return executor.maxQueuedTasks();
        }

        private void runOne() {
            if (legacy && batchSize == 1) {
                consumeResult(0);
                return;
            }
            if (legacy) {
                for (int i = 0; i < batchSize; i++) {
                    int result = i;
                    executor.execute(() -> consumeResult(result));
                }
                executor.runAll();
                return;
            }
            if (batchSize == 1) {
                CompletableFuture<Void> completion = dispatcher.submit(
                        "benchmark-session", () -> consumeResult(0));
                executor.runAll();
                completion.join();
                return;
            }
            CompletableFuture<Void> completion = dispatcher.submit(
                    "benchmark-session", batchResults, resultHandler);
            executor.runAll();
            completion.join();
        }

        private void consumeResult(int result) {
            blackhole += result + 1L;
        }

        @Override
        public void close() {
            if (dispatcher != null) {
                dispatcher.close();
            }
        }
    }

    private static class MeasuringExecutor implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private int maxQueuedTasks;

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
            maxQueuedTasks = Math.max(maxQueuedTasks, tasks.size());
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }

        private void resetMaximum() {
            if (!tasks.isEmpty()) {
                throw new IllegalStateException("Benchmark executor still has queued work");
            }
            maxQueuedTasks = 0;
        }

        private int maxQueuedTasks() {
            return maxQueuedTasks;
        }
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

    private static class LargeMessageLoadScenario implements AutoCloseable {
        private final SizedLoadPayload payload;
        private final long maxRetainedBytes;
        private final ExecutorService runtimeExecutor;
        private final Semaphore processed = new Semaphore(0);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ResultLoadBenchmarkClient client;
        private final JdkWebSocketSession session;
        private final WebSocket.Listener listener;
        private final BenchmarkWebSocket webSocket = new BenchmarkWebSocket();

        private LargeMessageLoadScenario(
                SizedLoadPayload payload, CompressionAlgorithm compression, long maxRetainedBytes) {
            this.payload = payload;
            this.maxRetainedBytes = maxRetainedBytes;
            this.runtimeExecutor = Runtime.version().feature() >= 25
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Executors.newFixedThreadPool(
                            JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
            this.client = new ResultLoadBenchmarkClient(
                    processed, failure, LARGE_LOAD_WORK_NANOS, false);
            Map<String, Object> userProperties = new java.util.HashMap<>(userProperties(true, false));
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY,
                               JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY,
                               JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES);
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY,
                               maxRetainedBytes);
            userProperties.put(AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY,
                               "large_message_benchmark");
            userProperties.put(AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "benchmark");
            userProperties.put(AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                               compression);
            userProperties.put(AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY,
                               io.fluxzero.common.websocket.WebSocketTransportFormat.JSON);
            this.session = new JdkWebSocketSession(
                    new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(client),
                    new WebsocketConnectionOptions(
                            Map.of(), userProperties, Duration.ofSeconds(1), List.of()),
                    URI.create("ws://localhost/benchmark-large-message"),
                    new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeExecutor);
            this.listener = session.createListener();
            listener.onOpen(webSocket);
        }

        private void run(int iterations) {
            execute(iterations, false);
        }

        private LargeLoadMeasurement measure(int iterations) {
            return execute(iterations, true);
        }

        private LargeLoadMeasurement execute(int iterations, boolean captureLatencies) {
            if (iterations < 1) {
                throw new IllegalArgumentException("Large-load iterations must be positive");
            }
            int initialBackpressureEpisodes = client.backpressureEpisodes();
            List<Long> latencies = captureLatencies ? new ArrayList<>() : List.of();
            long started = System.nanoTime();
            int remaining = iterations;
            while (remaining > 0) {
                long burstStarted = System.nanoTime();
                CompletableFuture<?> finalFrame = CompletableFuture.completedFuture(null);
                int burst = 0;
                while (burst < remaining) {
                    finalFrame = listener.onBinary(webSocket, ByteBuffer.wrap(payload.bytes()), true)
                            .toCompletableFuture();
                    burst++;
                    if (!finalFrame.isDone()) {
                        break;
                    }
                }
                awaitProcessed(burst);
                finalFrame.orTimeout(30, TimeUnit.SECONDS).join();
                awaitRuntimeDataDrain();
                if (captureLatencies) {
                    latencies.add(System.nanoTime() - burstStarted);
                }
                remaining -= burst;
            }
            long elapsed = System.nanoTime() - started;
            int backpressureEpisodes = client.backpressureEpisodes() - initialBackpressureEpisodes;
            return new LargeLoadMeasurement(
                    iterations, elapsed, latencies.stream().mapToLong(Long::longValue).toArray(),
                    backpressureEpisodes);
        }

        private void awaitProcessed(int count) {
            try {
                if (!processed.tryAcquire(count, 30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out awaiting large-message result completion");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted awaiting large-message result completion", e);
            }
            Throwable endpointFailure = failure.get();
            if (endpointFailure != null) {
                throw new IllegalStateException("Large-message result completion failed", endpointFailure);
            }
        }

        private void awaitRuntimeDataDrain() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (session.runtimeDataState().retainedMessages() != 0) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Timed out awaiting large-message runtime bookkeeping");
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public void close() {
            session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "benchmark complete"));
            runtimeExecutor.shutdownNow();
            client.close();
        }
    }

    private static class BoundedLoadScenario implements AutoCloseable {
        private final int maxConcurrency;
        private final CompressionAlgorithm compression;
        private final boolean transportMetrics;
        private final boolean functionalCompletion;
        private final int valueBytes;
        private final long workNanos;
        private final int maxRetainedMessages;
        private final long maxRetainedBytes;
        private final byte[] payload;
        private final int messagesPerSession;
        private final ExecutorService executor;
        private final Semaphore processed = new Semaphore(0);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AbstractWebsocketClient benchmarkClient;
        private final List<JdkWebSocketSession> sessions;
        private final List<WebSocket.Listener> listeners;
        private final List<BenchmarkWebSocket> webSockets;

        private BoundedLoadScenario(int maxConcurrency, int sessionCount, CompressionAlgorithm compression,
                                    boolean transportMetrics) {
            this(maxConcurrency, sessionCount, compression, transportMetrics,
                 JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                 JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES,
                 LOAD_PAYLOAD_BYTES, LOAD_WORK_NANOS, false);
        }

        private BoundedLoadScenario(int maxConcurrency, int sessionCount, CompressionAlgorithm compression,
                                    boolean transportMetrics, int maxRetainedMessages, long maxRetainedBytes) {
            this(maxConcurrency, sessionCount, compression, transportMetrics, maxRetainedMessages,
                 maxRetainedBytes, LOAD_PAYLOAD_BYTES, LOAD_WORK_NANOS, false);
        }

        private static BoundedLoadScenario smallResult(
                int sessionCount, CompressionAlgorithm compression, boolean transportMetrics, int valueBytes) {
            return new BoundedLoadScenario(
                    SMALL_LOAD_MAX_CONCURRENCY, sessionCount, compression,
                    transportMetrics, SMALL_LOAD_MAX_RETAINED_MESSAGES,
                    JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES, valueBytes, 0L, true);
        }

        private static BoundedLoadScenario smallDirect(
                int sessionCount, CompressionAlgorithm compression, int valueBytes) {
            return new BoundedLoadScenario(
                    SMALL_LOAD_MAX_CONCURRENCY, sessionCount, compression,
                    false, SMALL_LOAD_MAX_RETAINED_MESSAGES,
                    JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES, valueBytes, 0L, false);
        }

        private BoundedLoadScenario(
                int maxConcurrency, int sessionCount, CompressionAlgorithm compression, boolean transportMetrics,
                int maxRetainedMessages, long maxRetainedBytes, int valueBytes, long workNanos,
                boolean functionalCompletion) {
            this.maxConcurrency = maxConcurrency;
            this.compression = compression;
            this.transportMetrics = transportMetrics;
            this.functionalCompletion = functionalCompletion;
            this.valueBytes = valueBytes;
            this.workNanos = workNanos;
            this.maxRetainedMessages = maxRetainedMessages;
            this.maxRetainedBytes = maxRetainedBytes;
            this.payload = compressedLoadPayload(compression, valueBytes);
            this.messagesPerSession = Math.max(1, Math.min(
                    maxRetainedMessages, Math.toIntExact(maxRetainedBytes / payload.length)));
            this.executor = Executors.newFixedThreadPool(loadWorkerCount(sessionCount, maxConcurrency));
            this.sessions = new java.util.ArrayList<>(sessionCount);
            this.listeners = new java.util.ArrayList<>(sessionCount);
            this.webSockets = new java.util.ArrayList<>(sessionCount);
            LoadEndpoint loadEndpoint = new LoadEndpoint(compression, processed, failure, workNanos);
            this.benchmarkClient = functionalCompletion
                    ? new ResultLoadBenchmarkClient(processed, failure, workNanos, transportMetrics)
                    : transportMetrics ? new LoadMetricBenchmarkClient(loadEndpoint) : null;
            WebsocketEndpoint endpoint = benchmarkClient == null ? loadEndpoint : benchmarkClient;
            Map<String, Object> userProperties = new java.util.HashMap<>(
                    userProperties(true, transportMetrics));
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY,
                               maxConcurrency);
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY,
                               maxRetainedMessages);
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY,
                               maxRetainedBytes);
            for (int i = 0; i < sessionCount; i++) {
                Map<String, Object> sessionProperties = new java.util.HashMap<>(userProperties);
                sessionProperties.put(AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY,
                                      "benchmark_runtime_" + i);
                sessionProperties.put(AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "benchmark");
                sessionProperties.put(AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                                      compression);
                sessionProperties.put(AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY,
                                      io.fluxzero.common.websocket.WebSocketTransportFormat.JSON);
                JdkWebSocketSession session = new JdkWebSocketSession(
                        new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(endpoint),
                        new WebsocketConnectionOptions(
                                Map.of(), sessionProperties, Duration.ofSeconds(1), List.of()),
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
            boolean completed;
            try {
                completed = processed.tryAcquire(batchSize, 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting bounded runtime work", e);
            }
            Throwable endpointFailure = failure.get();
            if (endpointFailure != null) {
                throw new IllegalStateException("Bounded runtime work failed", endpointFailure);
            }
            if (!completed) {
                throw new IllegalStateException("Timed out awaiting bounded runtime work");
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
            if (benchmarkClient != null) {
                benchmarkClient.close();
            }
        }
    }

    private static byte[] createCompressedLoadPayload(CompressionAlgorithm compression, int valueBytes) {
        try {
            StringBuilder value = new StringBuilder(valueBytes);
            int state = 0x13579bdf;
            for (int i = 0; i < valueBytes; i++) {
                state = state * 1_103_515_245 + 12_345;
                value.append((char) (' ' + Math.floorMod(state, 95)));
            }
            WebSocketTransportCodec codec = WebSocketTransportCodecs.json(
                    AbstractWebsocketClient.defaultObjectMapper);
            String encoded = new String(codec.encode(new StringResult(1L, value.toString())), StandardCharsets.UTF_8)
                    .replaceFirst("\"timestamp\":\\d+", "\"timestamp\":1");
            return compression.compress(encoded.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create bounded-load payload", e);
        }
    }

    static byte[] compressedLoadPayload(CompressionAlgorithm compression, int valueBytes) {
        return LOAD_PAYLOADS.computeIfAbsent(
                new LoadPayloadKey(compression, valueBytes),
                key -> createCompressedLoadPayload(key.compression(), key.valueBytes())).clone();
    }

    static SizedLoadPayload compressedLoadPayloadNear(CompressionAlgorithm compression, int targetCompressedBytes) {
        if (targetCompressedBytes < 1) {
            throw new IllegalArgumentException("Target compressed bytes must be positive");
        }
        return LARGE_LOAD_PAYLOADS.computeIfAbsent(
                new LargeLoadPayloadKey(compression, targetCompressedBytes),
                WebsocketRuntimeDispatchBenchmark::createSizedLoadPayload);
    }

    private static SizedLoadPayload createSizedLoadPayload(LargeLoadPayloadKey key) {
        int sampleValueBytes = Math.min(key.targetCompressedBytes(), 1 << 20);
        byte[] sample = createCompressedTrackingLoadPayload(
                key.compression(), sampleValueBytes, LARGE_LOAD_TRACKING_MESSAGES);
        int estimatedValueBytes = Math.max(1, Math.toIntExact(Math.round(
                (double) key.targetCompressedBytes() * sampleValueBytes / sample.length)));
        int tolerance = Math.max(1, key.targetCompressedBytes() / 50);
        byte[] payload = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            payload = createCompressedTrackingLoadPayload(
                    key.compression(), estimatedValueBytes, LARGE_LOAD_TRACKING_MESSAGES);
            if (Math.abs(payload.length - key.targetCompressedBytes()) <= tolerance) {
                break;
            }
            int correctedValueBytes = Math.max(LARGE_LOAD_TRACKING_MESSAGES, Math.toIntExact(Math.round(
                    (double) estimatedValueBytes * key.targetCompressedBytes() / payload.length)));
            if (correctedValueBytes == estimatedValueBytes) {
                correctedValueBytes += payload.length < key.targetCompressedBytes() ? 1 : -1;
            }
            estimatedValueBytes = correctedValueBytes;
        }
        if (payload == null || Math.abs(payload.length - key.targetCompressedBytes()) > tolerance) {
            throw new IllegalStateException(
                    "%s payload target %d compressed to %d bytes"
                            .formatted(key.compression(), key.targetCompressedBytes(), payload.length));
        }
        return new SizedLoadPayload(
                payload, estimatedValueBytes, key.targetCompressedBytes(), LARGE_LOAD_TRACKING_MESSAGES);
    }

    private static byte[] createCompressedTrackingLoadPayload(
            CompressionAlgorithm compression, int valueBytes, int trackingMessages) {
        if (trackingMessages < 1 || valueBytes < trackingMessages) {
            throw new IllegalArgumentException(
                    "Large-load value bytes must provide at least one byte per tracking message");
        }
        try {
            List<SerializedMessage> messages = new ArrayList<>(trackingMessages);
            int state = 0x13579bdf;
            int remainingValueBytes = valueBytes;
            for (int messageIndex = 0; messageIndex < trackingMessages; messageIndex++) {
                int messageBytes = remainingValueBytes / (trackingMessages - messageIndex);
                byte[] value = new byte[messageBytes];
                for (int i = 0; i < messageBytes; i++) {
                    state = state * 1_103_515_245 + 12_345;
                    value[i] = (byte) (state >>> 24);
                }
                messages.add(new SerializedMessage(
                        new Data<>(value, "benchmark.TrackingPayload", 0, "application/octet-stream"),
                        Metadata.empty(), "benchmark-message-" + messageIndex, 1L));
                remainingValueBytes -= messageBytes;
            }
            WebSocketTransportCodec codec = WebSocketTransportCodecs.json(
                    AbstractWebsocketClient.defaultObjectMapper);
            ReadResult result = new ReadResult(
                    1L, new MessageBatch(new int[]{0, 128}, messages, (long) trackingMessages - 1,
                                        Position.newPosition(), true));
            String encoded = new String(codec.encode(result), StandardCharsets.UTF_8)
                    .replaceAll("\"timestamp\":\\d+", "\"timestamp\":1");
            return compression.compress(encoded.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create large tracking-response payload", e);
        }
    }

    private record LoadPayloadKey(CompressionAlgorithm compression, int valueBytes) {
    }

    private record LargeLoadPayloadKey(CompressionAlgorithm compression, int targetCompressedBytes) {
    }

    record SizedLoadPayload(byte[] bytes, int valueBytes, int targetCompressedBytes, int trackingMessages) {
    }

    private record BoundedLoadMeasurement(int iterations, long elapsedNanos, long[] batchDrainLatencies) {
    }

    private record LargeLoadMeasurement(
            int iterations, long elapsedNanos, long[] burstDrainLatencies, int backpressureEpisodes) {
    }

    private static Map<String, Object> userProperties(boolean isolated, boolean transportMetrics) {
        if (isolated && transportMetrics) {
            return Map.of(JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true,
                          JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true,
                          JdkWebSocketSession.SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY, true);
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
                                2, 4_096L, 2, 4_096L, 2, 4_096L, 0, 0L, 0, 0L,
                                JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES, 0L, 0L));

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

    private static class LoadMetricBenchmarkClient extends AbstractWebsocketClient {
        private final LoadEndpoint delegate;

        private LoadMetricBenchmarkClient(LoadEndpoint delegate) {
            super((endpoint, options, uri) -> {
                      throw new UnsupportedOperationException("Benchmark connector must not connect");
                  }, URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("load-metric-benchmark")
                                                      .build()),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1,
                  new SimplePropertySource(Map.of(TRANSPORT_METRICS_ENABLED_PROPERTY, "true")),
                  (client, numberOfSessions) -> NoOpTaskScheduler.INSTANCE);
            this.delegate = delegate;
        }

        @Override
        public void onOpen(WebsocketSession session) {
        }

        @Override
        public void onMessage(byte[] bytes, WebsocketSession session) {
            delegate.onMessage(bytes, session);
        }

        @Override
        public void onMessage(byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming) {
            delegate.onMessage(bytes, session);
        }

        @Override
        public void onPong(ByteBuffer data, WebsocketSession session) {
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
            delegate.onError(session, error);
        }

        @Override
        void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
            blackhole += Message.asMessage(metric).addMetadata(metadata)
                    .serialize(getFallbackSerializer()).getBytes();
        }
    }

    private static class ResultLoadBenchmarkClient extends AbstractWebsocketClient {
        private final Semaphore processed;
        private final AtomicReference<Throwable> failure;
        private final long workNanos;
        private final AtomicInteger backpressureEpisodes = new AtomicInteger();

        private ResultLoadBenchmarkClient(
                Semaphore processed, AtomicReference<Throwable> failure, long workNanos,
                boolean transportMetrics) {
            super((endpoint, options, uri) -> {
                      throw new UnsupportedOperationException("Benchmark connector must not connect");
                  }, URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("small-result-load-benchmark")
                                                      .maxConcurrentRuntimeResultCompletions(
                                                              RESULT_COMPLETION_CONCURRENCY)
                                                      .build()),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1,
                  new SimplePropertySource(transportMetrics
                                                   ? Map.of(TRANSPORT_METRICS_ENABLED_PROPERTY, "true") : Map.of()),
                  (client, numberOfSessions) -> NoOpTaskScheduler.INSTANCE);
            this.processed = processed;
            this.failure = failure;
            this.workNanos = workNanos;
        }

        @Override
        public void onOpen(WebsocketSession session) {
        }

        @Override
        void onRuntimeIngressBackpressure(
                WebsocketSession session, boolean backpressured, RuntimeIngressController.State state) {
            if (backpressured) {
                backpressureEpisodes.incrementAndGet();
            }
            super.onRuntimeIngressBackpressure(session, backpressured, state);
        }

        private int backpressureEpisodes() {
            return backpressureEpisodes.get();
        }

        @Override
        protected void handleResult(RequestResult result, String batchId, String sessionId,
                                    WebsocketResultDiagnostics.ResultTiming timing) {
            try {
                if (result instanceof StringResult stringResult) {
                    blackhole += stringResult.getResult().length();
                } else if (result instanceof ReadResult readResult) {
                    blackhole += readResult.getMessageBatch().getBytes();
                } else {
                    throw new IllegalArgumentException(
                            "Unexpected benchmark result type " + result.getClass().getName());
                }
                LockSupport.parkNanos(workNanos);
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
                                AtomicReference<Throwable> failure, long workNanos) implements WebsocketEndpoint {
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
                LockSupport.parkNanos(workNanos);
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

    private record HeapPeakSnapshot(long baselineBytes, List<MemoryPoolMXBean> pools) {
        private static HeapPeakSnapshot start() {
            List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP)
                    .toList();
            pools.forEach(MemoryPoolMXBean::resetPeakUsage);
            return new HeapPeakSnapshot(
                    pools.stream().mapToLong(pool -> pool.getUsage().getUsed()).sum(), pools);
        }

        private long deltaBytes() {
            long peakBytes = pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            return Math.max(0L, peakBytes - baselineBytes);
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
