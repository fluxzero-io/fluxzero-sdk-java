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

package io.fluxzero.proxy;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.SocketSession;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Integer.getInteger;
import static java.lang.Long.getLong;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Small proxy hot-path benchmark that is intentionally runnable from test sources without extra tooling.
 *
 * <p>Example:</p>
 * <pre>{@code
 * ./mvnw -pl proxy -am -DskipTests test-compile
 * ./mvnw -pl proxy -DskipTests \
 *   org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=io.fluxzero.proxy.ProxyServerBenchmark \
 *   -Drequests=10000 -Dwarmup=1000 -Dconcurrency=32
 *
 * FLUXZERO_PROXY_MAX_PENDING_WEBSOCKET_SENDS=8 ./mvnw -pl proxy -DskipTests \
 *   org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=io.fluxzero.proxy.ProxyServerBenchmark \
 *   -Dscenarios=websocket-slow-clients -Dconcurrency=16
 * }</pre>
 *
 * <p>Run this same class on the Undertow baseline commit and the Jetty commit to compare transport overhead.</p>
 */
public class ProxyServerBenchmark {
    private static volatile int blackhole;

    public static void main(String[] args) {
        try {
            new ProxyServerBenchmark().run(BenchmarkConfig.fromSystemProperties());
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    @SneakyThrows
    void run(BenchmarkConfig config) {
        byte[] responsePayload = payload(config.responseBytes(), (byte) 'r');
        byte[] requestPayload = payload(config.requestBytes(), (byte) 'q');
        String websocketPayload = "w".repeat(config.websocketBytes());
        String slowWebsocketPayload = "s".repeat(config.slowWebsocketBytes());

        BenchmarkHandlers handlers = new BenchmarkHandlers(responsePayload, slowWebsocketPayload,
                                                           config.slowWebsocketMessages());
        Fluxzero fluxzero = createFluxzero(handlers);
        Fluxzero directFluxzero = config.usesDirectScenarios()
                ? createFluxzero(new BenchmarkHandlers(responsePayload)) : null;
        ProxyServer proxyServer = ProxyServer.startHttpProxyOnly(
                0, new ProxyRequestHandler(fluxzero.client()));
        HttpClient httpClient = HttpClient.newBuilder()
                .version(config.httpVersion())
                .connectTimeout(config.timeout())
                .build();

        try {
            Thread.sleep(config.startupDelay().toMillis());
            URI baseUri = URI.create("http://localhost:%d".formatted(proxyServer.getPort()));
            URI websocketBaseUri = URI.create("ws://localhost:%d".formatted(proxyServer.getPort()));
            if (config.primeHttp2()) {
                httpClient.send(HttpRequest.newBuilder(baseUri.resolve("/proxy/health")).GET().build(),
                                BodyHandlers.ofString());
            }

            System.out.printf("Proxy benchmark config: %s, proxyPort=%d%s%n",
                              config, proxyServer.getPort(), proxyDetails(proxyServer));

            if (config.scenarios().contains(Scenario.HEALTH)) {
                ObservedVersions observedVersions = new ObservedVersions();
                benchmark("health", config, worker -> {
                    var response = httpClient.send(
                            HttpRequest.newBuilder(baseUri.resolve("/proxy/health")).GET().build(),
                            BodyHandlers.ofString());
                    observedVersions.record(response.version());
                    assertStatus("health", 200, response.statusCode());
                    blackhole += response.body().length();
                }, observedVersions);
            }

            if (config.scenarios().contains(Scenario.HTTP_GET)) {
                ObservedVersions observedVersions = new ObservedVersions();
                benchmark("http-get", config, worker -> {
                    var response = httpClient.send(
                            HttpRequest.newBuilder(baseUri.resolve("/benchmark/bytes"))
                                    .GET()
                                    .header("X-Benchmark", "proxy")
                                    .build(),
                            BodyHandlers.ofByteArray());
                    observedVersions.record(response.version());
                    assertStatus("http-get", 200, response.statusCode());
                    blackhole += response.body().length;
                }, observedVersions);
            }

            if (config.scenarios().contains(Scenario.HTTP_POST)) {
                ObservedVersions observedVersions = new ObservedVersions();
                benchmark("http-post", config, worker -> {
                    var response = httpClient.send(
                            HttpRequest.newBuilder(baseUri.resolve("/benchmark/echo-size"))
                                    .POST(BodyPublishers.ofByteArray(requestPayload))
                                    .header("Content-Type", "application/octet-stream")
                                    .build(),
                            BodyHandlers.ofString());
                    observedVersions.record(response.version());
                    assertStatus("http-post", 200, response.statusCode());
                    blackhole += response.body().length();
                }, observedVersions);
            }

            if (config.scenarios().contains(Scenario.DIRECT_GET)) {
                benchmark("direct-get", config, worker -> {
                    WebResponse response = directFluxzero.webRequestGateway().sendAndWait(
                            WebRequest.builder()
                                    .url("/benchmark/bytes")
                                    .method(HttpRequestMethod.GET)
                                    .acceptGzipEncoding(false)
                                    .header("X-Benchmark", "direct")
                                    .build());
                    assertStatus("direct-get", 200, response.getStatus());
                    blackhole += payloadLength(response.getPayload());
                });
            }

            if (config.scenarios().contains(Scenario.DIRECT_POST)) {
                benchmark("direct-post", config, worker -> {
                    WebResponse response = directFluxzero.webRequestGateway().sendAndWait(
                            WebRequest.builder()
                                    .url("/benchmark/echo-size")
                                    .method(HttpRequestMethod.POST)
                                    .acceptGzipEncoding(false)
                                    .payload(requestPayload)
                                    .header("Content-Type", "application/octet-stream")
                                    .build());
                    assertStatus("direct-post", 200, response.getStatus());
                    blackhole += payloadLength(response.getPayload());
                });
            }

            if (config.scenarios().contains(Scenario.WEBSOCKET_TEXT)) {
                benchmarkWebsocketText("websocket-text", config, httpClient,
                                       websocketBaseUri.resolve("/benchmark/ws"), websocketPayload);
            }

            if (config.scenarios().contains(Scenario.WEBSOCKET_OPEN)) {
                benchmarkWebsocketOpen("websocket-open", config, httpClient,
                                       websocketBaseUri.resolve("/benchmark/ws-open"));
            }

            if (config.scenarios().contains(Scenario.WEBSOCKET_SLOW_CLIENTS)) {
                benchmarkSlowWebsocketClients("websocket-slow-clients", config, handlers,
                                              websocketBaseUri.resolve("/benchmark/ws-slow"));
            }
        } finally {
            proxyServer.cancel();
            fluxzero.close(true);
            if (directFluxzero != null) {
                directFluxzero.close(true);
            }
        }
    }

    private static Fluxzero createFluxzero(BenchmarkHandlers handlers) {
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableTrackingMetrics()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null));
        fluxzero.registerHandlers(handlers);
        return fluxzero;
    }

    private static void benchmark(String name, BenchmarkConfig config, BenchmarkOperation operation) {
        benchmark(name, config, operation, null);
    }

    private static void benchmark(String name, BenchmarkConfig config, BenchmarkOperation operation,
                                  ObservedVersions observedVersions) {
        runConcurrent(config.warmup(), config.concurrency(), config.driverVirtualThreads(), operation, null);
        BenchmarkResult result = runConcurrent(config.requests(), config.concurrency(), config.driverVirtualThreads(),
                                               operation, new long[config.requests()]);
        String suffix = observedVersions == null ? "" : ", responseVersions=" + observedVersions.summary();
        System.out.println(result.withName(name).summary() + suffix);
    }

    private static void benchmarkWebsocketText(String name, BenchmarkConfig config, HttpClient httpClient,
                                               URI uri, String payload) {
        List<BenchmarkWebSocket> sockets = openSockets(config.concurrency(), config.timeout(), httpClient, uri);
        try {
            benchmark(name, config, worker -> {
                BenchmarkWebSocket socket = sockets.get(worker);
                CompletableFuture<String> response = socket.listener().nextResponse();
                socket.webSocket().sendText(payload, true).get(config.timeout().toMillis(), MILLISECONDS);
                String echoed = response.get(config.timeout().toMillis(), MILLISECONDS);
                blackhole += echoed.length();
            });
        } finally {
            sockets.forEach(BenchmarkWebSocket::close);
        }
    }

    private static void benchmarkWebsocketOpen(String name, BenchmarkConfig config, HttpClient httpClient, URI uri) {
        benchmark(name, config, worker -> {
            BenchmarkWebSocketListener listener = new BenchmarkWebSocketListener();
            CompletableFuture<String> opened = listener.nextResponse();
            WebSocket webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(config.timeout())
                    .buildAsync(uri, listener)
                    .get(config.timeout().toMillis(), MILLISECONDS);
            BenchmarkWebSocket socket = new BenchmarkWebSocket(webSocket, listener);
            try {
                blackhole += opened.get(config.timeout().toMillis(), MILLISECONDS).length();
            } finally {
                socket.close();
            }
        });
    }

    private static void benchmarkSlowWebsocketClients(String name, BenchmarkConfig config, BenchmarkHandlers handlers,
                                                      URI uri) {
        List<SlowRawWebSocket> sockets = new ArrayList<>();
        int beforeEvents = handlers.backpressureEvents();
        long started = nanoTime();
        try {
            for (int i = 0; i < config.concurrency(); i++) {
                sockets.add(openSlowRawWebSocket(config.timeout(), uri));
            }
            Thread.sleep(config.slowClientHold().toMillis());
            int events = handlers.backpressureEvents() - beforeEvents;
            double totalMillis = (nanoTime() - started) / 1_000_000.0;
            System.out.printf("%s: opened %d slow raw sockets for %.1f ms, backpressureEvents=%d, "
                              + "serverMessagesPerSocket=%d, messageBytes=%d%n",
                              name, sockets.size(), totalMillis, events,
                              config.slowWebsocketMessages(), config.slowWebsocketBytes());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding slow websocket clients", e);
        } finally {
            sockets.forEach(SlowRawWebSocket::close);
        }
    }

    private static BenchmarkResult runConcurrent(int operations, int concurrency, boolean virtualThreads,
                                                 BenchmarkOperation operation, long[] latencies) {
        if (operations <= 0) {
            return new BenchmarkResult("warmup", 0, 0L, new long[0]);
        }
        int workers = Math.min(max(1, concurrency), operations);
        AtomicInteger next = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = virtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int worker = 0; worker < workers; worker++) {
                int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    int operationIndex;
                    while ((operationIndex = next.getAndIncrement()) < operations) {
                        long before = nanoTime();
                        try {
                            operation.run(workerIndex);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        if (latencies != null) {
                            latencies[operationIndex] = nanoTime() - before;
                        }
                    }
                }));
            }
            await(ready);
            long started = nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            return new BenchmarkResult(null, operations, nanoTime() - started, latencies == null ? new long[0] : latencies);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Benchmark failed", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<BenchmarkWebSocket> openSockets(int count, Duration timeout, HttpClient httpClient, URI uri) {
        List<BenchmarkWebSocket> sockets = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                sockets.add(openSocket(timeout, httpClient, uri));
            }
            return sockets;
        } catch (RuntimeException e) {
            sockets.forEach(BenchmarkWebSocket::close);
            throw e;
        }
    }

    private static BenchmarkWebSocket openSocket(Duration timeout, HttpClient httpClient, URI uri) {
        try {
            BenchmarkWebSocketListener listener = new BenchmarkWebSocketListener();
            WebSocket webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(uri, listener)
                    .get(timeout.toMillis(), MILLISECONDS);
            return new BenchmarkWebSocket(webSocket, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening websocket", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new IllegalStateException("Failed to open websocket", e);
        }
    }

    private static SlowRawWebSocket openSlowRawWebSocket(Duration timeout, URI uri) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), Math.toIntExact(timeout.toMillis()));
            socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
            OutputStream output = socket.getOutputStream();
            String key = websocketKey();
            output.write(websocketHandshake(uri, key).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.flush();
            String response = readHttpHeaders(socket.getInputStream());
            if (!response.startsWith("HTTP/1.1 101")) {
                throw new IllegalStateException("Websocket handshake failed: " + response.lines().findFirst()
                        .orElse("<empty response>"));
            }
            socket.setSoTimeout(0);
            return new SlowRawWebSocket(socket);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open slow raw websocket", e);
        }
    }

    private static String websocketHandshake(URI uri, String key) {
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String pathQuery = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        return """
                GET %s HTTP/1.1\r
                Host: %s:%d\r
                Upgrade: websocket\r
                Connection: Upgrade\r
                Sec-WebSocket-Key: %s\r
                Sec-WebSocket-Version: 13\r
                \r
                """.formatted(pathQuery, uri.getHost(), uri.getPort(), key);
    }

    private static String websocketKey() {
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String readHttpHeaders(InputStream input) throws Exception {
        StringBuilder response = new StringBuilder();
        int previous3 = -1, previous2 = -1, previous1 = -1, current;
        while ((current = input.read()) != -1) {
            response.append((char) current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                return response.toString();
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        throw new IllegalStateException("Websocket handshake response ended before headers completed");
    }

    private static byte[] payload(int size, byte value) {
        byte[] payload = new byte[size];
        Arrays.fill(payload, value);
        return payload;
    }

    private static int payloadLength(Object payload) {
        return switch (payload) {
            case null -> 0;
            case byte[] bytes -> bytes.length;
            case CharSequence text -> text.length();
            default -> payload.toString().length();
        };
    }

    private static void assertStatus(String scenario, int expected, int actual) {
        if (actual != expected) {
            throw new IllegalStateException("%s expected status %d but got %d".formatted(scenario, expected, actual));
        }
    }

    private static String proxyDetails(ProxyServer proxyServer) {
        Object virtualThreads = invokeNoArg(proxyServer, "isUsingVirtualThreads");
        Object minThreads = invokeNoArg(proxyServer, "getMinThreads");
        Object maxThreads = invokeNoArg(proxyServer, "getMaxThreads");
        StringBuilder details = new StringBuilder();
        if (virtualThreads != null) {
            details.append(", proxyVirtualThreads=").append(virtualThreads);
        }
        if (minThreads != null && maxThreads != null) {
            details.append(", proxyThreads=").append(minThreads).append("..").append(maxThreads);
        }
        return details.toString();
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException | SecurityException e) {
            return null;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for benchmark workers", e);
        }
    }

    @FunctionalInterface
    private interface BenchmarkOperation {
        void run(int workerIndex) throws Exception;
    }

    private record BenchmarkConfig(int requests, int warmup, int concurrency, int requestBytes, int responseBytes,
                                   int websocketBytes, int slowWebsocketMessages, int slowWebsocketBytes,
                                   Duration slowClientHold, Duration timeout, Duration startupDelay,
                                   HttpClient.Version httpVersion, boolean driverVirtualThreads,
                                   boolean primeHttp2, Set<Scenario> scenarios) {
        static BenchmarkConfig fromSystemProperties() {
            return new BenchmarkConfig(
                    getInteger("requests", 5_000),
                    getInteger("warmup", 500),
                    getInteger("concurrency", max(1, Runtime.getRuntime().availableProcessors() * 2)),
                    getInteger("requestBytes", 1024),
                    getInteger("responseBytes", 1024),
                    getInteger("websocketBytes", 128),
                    getInteger("slowWebsocketMessages", 2_048),
                    getInteger("slowWebsocketBytes", 64 * 1024),
                    Duration.ofMillis(getLong("slowClientHoldMillis", 5_000L)),
                    Duration.ofMillis(getLong("timeoutMillis", 10_000L)),
                    Duration.ofMillis(getLong("startupDelayMillis", 250L)),
                    HttpClient.Version.valueOf(System.getProperty("httpVersion", "HTTP_1_1")),
                    Boolean.parseBoolean(System.getProperty(
                            "driverVirtualThreads", System.getProperty("clientVirtualThreads", "true"))),
                    Boolean.parseBoolean(System.getProperty("primeHttp2", "false")),
                    Scenario.parse(System.getProperty(
                            "scenarios", "health,http-get,http-post,websocket-text")));
        }

        boolean usesDirectScenarios() {
            return scenarios.contains(Scenario.DIRECT_GET) || scenarios.contains(Scenario.DIRECT_POST);
        }
    }

    private enum Scenario {
        HEALTH,
        HTTP_GET,
        HTTP_POST,
        DIRECT_GET,
        DIRECT_POST,
        WEBSOCKET_TEXT,
        WEBSOCKET_OPEN,
        WEBSOCKET_SLOW_CLIENTS;

        static Set<Scenario> parse(String value) {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("all")) {
                return EnumSet.allOf(Scenario.class);
            }
            EnumSet<Scenario> result = EnumSet.noneOf(Scenario.class);
            for (String token : value.split(",")) {
                String normalized = token.strip().replace('-', '_').toUpperCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    result.add(Scenario.valueOf(normalized));
                }
            }
            return result;
        }
    }

    private record BenchmarkResult(String name, int operations, long totalNanos, long[] latencies) {
        BenchmarkResult withName(String name) {
            return new BenchmarkResult(name, operations, totalNanos, latencies);
        }

        String summary() {
            long[] sorted = latencies.clone();
            Arrays.sort(sorted);
            long latencyTotal = 0L;
            for (long latency : sorted) {
                latencyTotal += latency;
            }
            double totalMillis = totalNanos / 1_000_000.0;
            double throughput = totalNanos == 0L ? operations : operations * 1_000_000_000.0 / totalNanos;
            return "%s: %d ops in %.1f ms, %.0f ops/s, avg %.1f us, p50 %.1f us, p95 %.1f us, p99 %.1f us, max %.1f us"
                    .formatted(name, operations, totalMillis, throughput,
                               nanosToMicros(latencyTotal / max(1, sorted.length)),
                               percentile(sorted, 0.50),
                               percentile(sorted, 0.95),
                               percentile(sorted, 0.99),
                               sorted.length == 0 ? 0.0 : nanosToMicros(sorted[sorted.length - 1]));
        }

        private static double percentile(long[] sorted, double percentile) {
            if (sorted.length == 0) {
                return 0.0;
            }
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return nanosToMicros(sorted[Math.clamp(index, 0, sorted.length - 1)]);
        }

        private static double nanosToMicros(long nanos) {
            return nanos / 1_000.0;
        }
    }

    private record BenchmarkWebSocket(WebSocket webSocket, BenchmarkWebSocketListener listener) {
        void close() {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "benchmark").orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(e -> null).join();
            listener.awaitClosed();
        }
    }

    private record SlowRawWebSocket(Socket socket) {
        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
                // The benchmark is already done; shutdown remains best-effort.
            }
        }
    }

    private static class ObservedVersions {
        private final Set<HttpClient.Version> versions = ConcurrentHashMap.newKeySet();

        void record(HttpClient.Version version) {
            versions.add(version);
        }

        String summary() {
            return versions.isEmpty() ? "[]" : versions.toString();
        }
    }

    private static class BenchmarkWebSocketListener implements WebSocket.Listener {
        private final AtomicReference<CompletableFuture<String>> response = new AtomicReference<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        CompletableFuture<String> nextResponse() {
            CompletableFuture<String> future = new CompletableFuture<>();
            if (!response.compareAndSet(null, future)) {
                throw new IllegalStateException("Previous websocket response is still pending");
            }
            return future;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            CompletableFuture<String> future = response.get();
            if (future == null) {
                webSocket.request(1);
                return null;
            }
            text.append(data);
            if (last) {
                String result = text.toString();
                text.setLength(0);
                response.set(null);
                future.complete(result);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            CompletableFuture<String> future = response.getAndSet(null);
            if (future != null) {
                future.completeExceptionally(error);
            }
            closed.complete(null);
        }

        void awaitClosed() {
            try {
                closed.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // The benchmark is already done; shutdown remains best-effort.
            }
        }
    }

    private static class BenchmarkHandlers {
        private final byte[] responsePayload;
        private final String slowWebsocketPayload;
        private final int slowWebsocketMessages;
        private final AtomicInteger backpressureEvents = new AtomicInteger();

        BenchmarkHandlers(byte[] responsePayload) {
            this(responsePayload, "", 0);
        }

        BenchmarkHandlers(byte[] responsePayload, String slowWebsocketPayload, int slowWebsocketMessages) {
            this.responsePayload = responsePayload;
            this.slowWebsocketPayload = slowWebsocketPayload;
            this.slowWebsocketMessages = slowWebsocketMessages;
        }

        @HandleGet("/benchmark/bytes")
        WebResponse bytes() {
            return WebResponse.builder()
                    .contentType("application/octet-stream")
                    .payload(responsePayload)
                    .build();
        }

        @HandlePost("/benchmark/echo-size")
        String echoSize(WebRequest request) {
            byte[] payload = request.getPayload();
            return Integer.toString(payload == null ? 0 : payload.length);
        }

        @HandleSocketMessage("/benchmark/ws")
        String echo(String message) {
            return message;
        }

        @HandleSocketOpen("/benchmark/ws-open")
        String open() {
            return "opened";
        }

        @HandleSocketOpen("/benchmark/ws-slow")
        void slow(SocketSession session) {
            for (int i = 0; i < slowWebsocketMessages; i++) {
                session.sendMessage(slowWebsocketPayload);
            }
        }

        @HandleMetrics
        void backpressure(ProxyWebsocketBackpressureEvent event) {
            backpressureEvents.incrementAndGet();
        }

        int backpressureEvents() {
            return backpressureEvents.get();
        }
    }
}
