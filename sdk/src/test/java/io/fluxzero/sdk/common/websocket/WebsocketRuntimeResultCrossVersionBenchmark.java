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
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Cross-version benchmark for the complete JDK WebSocket, decode and SDK result-completion path.
 *
 * <p>This class intentionally uses only APIs present in SDK 1.212.3, with a narrow reflective adapter for the changed
 * internal JDK session constructor, so the exact same source can be compiled and run against 1.212.3, 1.239.0 and a
 * proposed replacement. Feed work in retained-capacity batches to avoid measuring a different producer-side queue in
 * either version. {@code -DbatchSize=0} measures individual responses, while positive values encode an actual
 * {@link ResultBatch}; the measured result count stays constant across batch sizes. Use
 * {@code -DtransportMetrics=true} to enable the sparse transport diagnostics and their progress tracking. A
 * demand-aware fake peer delivers only frames requested by the SDK. Dedicated runtime workers follow the SDK's
 * Java-version policy, and the default two sessions match the normal event-sourcing, search, and key-value client
 * configuration. Use {@code -Dsessions=4} to exercise the client-wide result-completion bound and
 * {@code -DcallbackWorkMicros=250} to model temporarily slower functional processing.</p>
 */
public class WebsocketRuntimeResultCrossVersionBenchmark {
    private static final String TRANSPORT_METRICS_PROPERTY = "fluxzero.websocket.transportMetrics.enabled";
    private static final int DEFAULT_RETAINED_MESSAGES = 128;
    private static final long DEFAULT_RETAINED_BYTES = 64L << 20;
    private static final boolean TRANSPORT_METRICS = Boolean.getBoolean("transportMetrics");

    private static final Method RUNTIME_STATE_METHOD = declaredMethod(JdkWebSocketSession.class, "runtimeDataState");
    private static final boolean DEDICATED_RUNTIME_EXECUTOR = hasDedicatedRuntimeExecutor();
    private static final Method RETAINED_MESSAGES_METHOD = RUNTIME_STATE_METHOD == null ? null
            : declaredMethod(RUNTIME_STATE_METHOD.getReturnType(), "retainedMessages");
    private static final int TARGET_RESULTS = Integer.getInteger(
            "results", Integer.getInteger("iterations", 2_097_152));
    private static final int WARMUPS = Integer.getInteger("warmups", 5);
    private static final int SESSION_COUNT = Integer.getInteger("sessions", 2);
    private static final int RETAINED_MESSAGES = Integer.getInteger(
            "retainedMessages", DEFAULT_RETAINED_MESSAGES);
    private static final long RETAINED_BYTES = Long.getLong("retainedBytes", DEFAULT_RETAINED_BYTES);
    private static final int VALUE_BYTES = Integer.getInteger("valueBytes", 320);
    private static final int BATCH_SIZE = Integer.getInteger("batchSize", 0);
    private static final int RESULTS_PER_MESSAGE = Math.max(1, BATCH_SIZE);
    private static final long CALLBACK_WORK_NANOS = TimeUnit.MICROSECONDS.toNanos(
            Long.getLong("callbackWorkMicros", 0L));
    private static final String WORKER_MODE = System.getProperty(
            "workerMode", Runtime.version().feature() >= 25 ? "virtual" : "fixed");
    private static final int FIXED_WORKERS = Integer.getInteger(
            "fixedWorkers", Math.min(8, SESSION_COUNT * 3));
    private static final CompressionAlgorithm COMPRESSION = CompressionAlgorithm.valueOf(
            System.getProperty("compression", "LZ4"));
    private static final WebSocketClient.ClientConfig CLIENT_CONFIG = clientConfig(TRANSPORT_METRICS);
    private static final String COMPLETION_LIMIT = completionLimit(CLIENT_CONFIG, Runtime.version().feature());
    private static volatile long blackhole;

    public static void main(String[] args) {
        if (BATCH_SIZE < 0) {
            throw new IllegalArgumentException("batchSize must not be negative");
        }
        System.setProperty(TRANSPORT_METRICS_PROPERTY, Boolean.toString(TRANSPORT_METRICS));
        try (Scenario scenario = new Scenario()) {
            for (int i = 0; i < WARMUPS; i++) {
                scenario.run(TARGET_RESULTS);
            }
            System.gc();
            long started = System.nanoTime();
            int measuredResults = scenario.run(TARGET_RESULTS);
            long elapsed = System.nanoTime() - started;
            int measuredMessages = measuredResults / RESULTS_PER_MESSAGE;
            System.out.printf(
                    "runtime-result-cross-version java=%d valueBytes=%d compression=%s sessions=%d "
                            + "ingressMode=%s runtimeWorkers=%s fixedRuntimeWorkersSetting=%d completionLimit=%s "
                            + "demandAware=true transportMetrics=%s maxConcurrencySetting=3 "
                            + "retainedMessagesSetting=%d retainedBytesSetting=%d warmups=%d "
                            + "resultBatchSize=%d resultsPerMessage=%d compressedBytes=%d "
                            + "callbackWorkNanos=%d wireMessages=%d results=%d: "
                            + "%.2f ns/result, %.1f results/s, %.1f messages/s%n",
                    Runtime.version().feature(), VALUE_BYTES, COMPRESSION, SESSION_COUNT,
                    RUNTIME_STATE_METHOD == null ? "legacy-request-demand" : "bounded",
                    runtimeWorkerMode(DEDICATED_RUNTIME_EXECUTOR, WORKER_MODE), FIXED_WORKERS,
                    COMPLETION_LIMIT, TRANSPORT_METRICS, RETAINED_MESSAGES, RETAINED_BYTES, WARMUPS,
                    BATCH_SIZE, RESULTS_PER_MESSAGE,
                    scenario.payload.length, CALLBACK_WORK_NANOS, measuredMessages, measuredResults,
                    (double) elapsed / measuredResults, measuredResults * 1_000_000_000d / elapsed,
                    measuredMessages * 1_000_000_000d / elapsed);
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static final class Scenario implements AutoCloseable {
        private final byte[] payload = createPayload(COMPRESSION, VALUE_BYTES, BATCH_SIZE);
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
                properties.put(JdkWebSocketSession.class.getName() + ".sdkRuntimeDataDispatch", true);
                properties.put(JdkWebSocketSession.class.getName() + ".sdkRuntimeDataMaxConcurrency", 3);
                properties.put(JdkWebSocketSession.class.getName() + ".sdkRuntimeDataMaxRetainedMessages",
                               RETAINED_MESSAGES);
                properties.put(JdkWebSocketSession.class.getName() + ".sdkRuntimeDataMaxRetainedBytes",
                               RETAINED_BYTES);
                properties.put(JdkWebSocketSession.class.getName() + ".sdkTransportMetricsEnabled",
                               TRANSPORT_METRICS);
                properties.put(JdkWebSocketSession.class.getName() + ".sdkRuntimeIngressProgressEnabled",
                               TRANSPORT_METRICS);
                properties.put(AbstractWebsocketClient.NEGOTIATED_SESSION_ID_USER_PROPERTY, "benchmark_" + i);
                properties.put(AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "benchmark");
                properties.put(AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, COMPRESSION);
                properties.put(AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY,
                               io.fluxzero.common.websocket.WebSocketTransportFormat.JSON);
                JdkWebSocketSession session = createSession(
                        client, properties, URI.create("ws://localhost/cross-version/" + i), runtimeExecutor);
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

        private int run(int requestedResults) {
            int maxMessagesPerSession = messagesPerSession(RETAINED_MESSAGES, RETAINED_BYTES, payload.length);
            int messages = messageIterations(requestedResults, RESULTS_PER_MESSAGE);
            int remainingMessages = messages;
            while (remainingMessages > 0) {
                int offeredMessages = Math.min(
                        remainingMessages, Math.multiplyExact(maxMessagesPerSession, SESSION_COUNT));
                for (int i = 0; i < offeredMessages; i++) {
                    int sessionIndex = i % SESSION_COUNT;
                    WebSocket.Listener listener = listeners.get(sessionIndex);
                    BenchmarkWebSocket webSocket = webSockets.get(sessionIndex);
                    webSocket.awaitDemand();
                    listener.onBinary(webSocket, ByteBuffer.wrap(payload), true);
                }
                remainingMessages -= offeredMessages;
                awaitProcessed(Math.multiplyExact(offeredMessages, RESULTS_PER_MESSAGE));
                awaitDrain();
            }
            return Math.multiplyExact(messages, RESULTS_PER_MESSAGE);
        }

        private void awaitProcessed(int count) {
            try {
                if (!processed.tryAcquire(count, 30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                                    "Timed out awaiting %d SDK results; available=%d, runtimeStates=%s"
                                    .formatted(count, processed.availablePermits(),
                                               sessions.stream().map(
                                                       WebsocketRuntimeResultCrossVersionBenchmark::runtimeState)
                                                       .toList()));
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
                if (retainedMessages(session) != 0) {
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

    private static JdkWebSocketSession createSession(
            ResultClient client, Map<String, Object> properties, URI uri, ExecutorService runtimeExecutor) {
        try {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            WebsocketEndpoint endpoint = runtimeEndpoint(client);
            WebsocketConnectionOptions options = new WebsocketConnectionOptions(
                    Map.of(), properties, Duration.ofSeconds(1), List.of());
            JdkWebsocketConnector.CapturedHandshakeResponse response =
                    new JdkWebsocketConnector.CapturedHandshakeResponse();
            Executor callbackExecutor = Runnable::run;
            for (Constructor<?> constructor : JdkWebSocketSession.class.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 7) {
                    constructor.setAccessible(true);
                    return (JdkWebSocketSession) constructor.newInstance(
                            connector, endpoint, options, uri, response, callbackExecutor, runtimeExecutor);
                }
                if (constructor.getParameterCount() == 6) {
                    constructor.setAccessible(true);
                    return (JdkWebSocketSession) constructor.newInstance(
                            connector, endpoint, options, uri, response, callbackExecutor);
                }
            }
            throw new IllegalStateException("Unsupported JdkWebSocketSession constructor");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create cross-version JDK WebSocket session", e);
        }
    }

    private static WebsocketEndpoint runtimeEndpoint(ResultClient client) throws ReflectiveOperationException {
        try {
            Class<?> endpointType = Class.forName(
                    "io.fluxzero.sdk.common.websocket.SdkRuntimeWebsocketEndpoint");
            Constructor<?> constructor = endpointType.getDeclaredConstructor(WebsocketEndpoint.class);
            constructor.setAccessible(true);
            return (WebsocketEndpoint) constructor.newInstance(client);
        } catch (ClassNotFoundException ignored) {
            return client;
        }
    }

    private static int retainedMessages(JdkWebSocketSession session) {
        Object state = runtimeState(session);
        if (state == null || RETAINED_MESSAGES_METHOD == null) {
            return 0;
        }
        try {
            return (int) RETAINED_MESSAGES_METHOD.invoke(state);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read retained runtime benchmark state", e);
        }
    }

    private static Object runtimeState(JdkWebSocketSession session) {
        if (RUNTIME_STATE_METHOD == null) {
            return null;
        }
        try {
            return RUNTIME_STATE_METHOD.invoke(session);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read runtime benchmark state", e);
        }
    }

    private static Method declaredMethod(Class<?> type, String name) {
        try {
            Method result = type.getDeclaredMethod(name);
            result.setAccessible(true);
            return result;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static boolean hasDedicatedRuntimeExecutor() {
        for (Constructor<?> constructor : JdkWebSocketSession.class.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 7) {
                return true;
            }
        }
        return false;
    }

    static int messageIterations(int requestedResults, int resultsPerMessage) {
        if (requestedResults < 1 || resultsPerMessage < 1) {
            throw new IllegalArgumentException("Benchmark result values must be positive");
        }
        if (requestedResults % resultsPerMessage != 0) {
            throw new IllegalArgumentException("Benchmark results must be divisible by results per message");
        }
        return requestedResults / resultsPerMessage;
    }

    static WebSocketClient.ClientConfig clientConfig(boolean transportMetrics) {
        return WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("cross-version-result-benchmark")
                .disableMetrics(!transportMetrics)
                .build();
    }

    static String completionLimit(WebSocketClient.ClientConfig clientConfig, int javaFeatureVersion) {
        try {
            return Integer.toString((int) clientConfig.getClass()
                    .getMethod("getMaxConcurrentRuntimeResultCompletions").invoke(clientConfig));
        } catch (NoSuchMethodException ignored) {
            return javaFeatureVersion >= 25 ? "unbounded-virtual" : "8-fixed";
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inspect SDK result-completion limit", e);
        }
    }

    static String runtimeWorkerMode(boolean dedicatedRuntimeExecutor, String configuredWorkerMode) {
        return dedicatedRuntimeExecutor ? configuredWorkerMode : "inline-benchmark-callback";
    }

    static int defaultRetainedMessages() {
        return DEFAULT_RETAINED_MESSAGES;
    }

    static long defaultRetainedBytes() {
        return DEFAULT_RETAINED_BYTES;
    }

    static int messagesPerSession(int retainedMessages, long retainedBytes, int payloadBytes) {
        if (retainedMessages < 1 || retainedBytes < 1 || payloadBytes < 1) {
            throw new IllegalArgumentException("Benchmark capacity and payload values must be positive");
        }
        return Math.max(1, Math.min(retainedMessages, Math.toIntExact(
                Math.min(Integer.MAX_VALUE, retainedBytes / payloadBytes))));
    }

    static byte[] createPayload(CompressionAlgorithm compression, int valueBytes, int batchSize) {
        if (valueBytes < 0 || batchSize < 0) {
            throw new IllegalArgumentException("Benchmark payload and batch sizes must not be negative");
        }
        try {
            JsonType value;
            if (batchSize == 0) {
                value = new StringResult(1L, benchmarkValue(valueBytes, 1));
            } else {
                List<RequestResult> results = new ArrayList<>(batchSize);
                for (int i = 0; i < batchSize; i++) {
                    results.add(new StringResult(i + 1L, benchmarkValue(valueBytes, i + 1)));
                }
                value = new ResultBatch(results);
            }
            byte[] encoded = WebSocketTransportCodecs.json(AbstractWebsocketClient.defaultObjectMapper)
                    .encode(value);
            String deterministic = new String(encoded, StandardCharsets.UTF_8)
                    .replaceAll("\"timestamp\":\\d+", "\"timestamp\":1");
            return compression.compress(deterministic.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create cross-version payload", e);
        }
    }

    private static String benchmarkValue(int valueBytes, int salt) {
        StringBuilder value = new StringBuilder(valueBytes);
        int state = 0x13579bdf ^ salt * 0x9e3779b9;
        for (int i = 0; i < valueBytes; i++) {
            state = state * 1_103_515_245 + 12_345;
            value.append((char) (' ' + Math.floorMod(state, 95)));
        }
        return value.toString();
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
                  WebSocketClient.newInstance(CLIENT_CONFIG),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1);
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
                LockSupport.parkNanos(CALLBACK_WORK_NANOS);
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
        public void close() {
            try {
                super.close();
            } finally {
                blackhole += consumedResultBytes.sum();
                logger.setLevel(previousLogLevel);
            }
        }
    }

    private static final class BenchmarkWebSocket implements WebSocket {
        private final ReceiveDemand receiveDemand = new ReceiveDemand();
        private boolean aborted;

        private void awaitDemand() {
            try {
                if (!receiveDemand.tryAcquire(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out awaiting SDK WebSocket receive demand");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted awaiting SDK WebSocket receive demand", e);
            }
        }

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
            receiveDemand.request(n);
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

    static final class ReceiveDemand {
        private final Semaphore permits = new Semaphore(0);

        void request(long n) {
            if (n <= 0 || n > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Benchmark WebSocket demand must be between 1 and Integer.MAX_VALUE");
            }
            permits.release((int) n);
        }

        boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
            return permits.tryAcquire(timeout, unit);
        }
    }
}
