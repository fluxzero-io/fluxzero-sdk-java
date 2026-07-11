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

package io.fluxzero.proxy;

import io.fluxzero.common.Registration;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.ObjectUtils.supportsVirtualThreadWorkers;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getFirstAvailableProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getLongProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getProperty;

@Slf4j
public class ProxyServer implements Registration {

    static final String READINESS_ENDPOINT_PROPERTY = "PROXY_READINESS_ENDPOINT";
    static final String DRAIN_DELAY_MILLIS_PROPERTY = "FLUXZERO_PROXY_DRAIN_DELAY_MILLIS";
    static final String SHUTDOWN_TIMEOUT_MILLIS_PROPERTY = "FLUXZERO_PROXY_SHUTDOWN_TIMEOUT_MILLIS";

    static final String DEFAULT_READINESS_ENDPOINT = "/proxy/ready";
    static final long DEFAULT_DRAIN_DELAY_MILLIS = 0L;
    static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 60_000L;

    /**
     * Default maximum request body size: 256 MiB
     */
    public static final long DEFAULT_MAX_REQUEST_BODY_SIZE = 1L << 28;

    /**
     * Default maximum multipart request body size: 1 GiB
     */
    public static final long DEFAULT_MAX_MULTIPART_REQUEST_BODY_SIZE = 1L << 30;

    /**
     * Default maximum request and response header size: 1 MiB
     */
    public static final int DEFAULT_MAX_HEADER_SIZE = 1 << 20;

    /**
     * Default connector idle timeout: 60 seconds
     */
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 60_000L;

    /**
     * Default Jetty maximum platform thread count.
     */
    public static final int DEFAULT_MAX_THREADS = 200;

    /**
     * Default Jetty minimum platform thread count.
     */
    public static final int DEFAULT_MIN_THREADS = 8;

    /**
     * Default maximum in-flight web requests accepted by the proxy. A value of {@code 0} disables this guardrail.
     */
    public static final int DEFAULT_MAX_IN_FLIGHT_WEB_REQUESTS = 0;

    /**
     * Default maximum queued outgoing websocket sends per session.
     */
    public static final int DEFAULT_MAX_PENDING_WEBSOCKET_SENDS = 1024;

    /**
     * Default websocket idle timeout: 120 seconds
     */
    public static final long DEFAULT_WEBSOCKET_IDLE_TIMEOUT_MILLIS = 120_000L;

    /**
     * Default delay between proxy websocket keep-alive pings: 55 seconds
     */
    public static final long DEFAULT_WEBSOCKET_PING_DELAY_MILLIS = 55_000L;

    /**
     * Default time allowed for receiving a proxy websocket keep-alive pong: 10 seconds
     */
    public static final long DEFAULT_WEBSOCKET_PING_TIMEOUT_MILLIS = 10_000L;

    static final String MAX_REQUEST_BODY_SIZE_PROPERTY = "FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE";
    static final String MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY = "FLUXZERO_PROXY_MAX_MULTIPART_REQUEST_BODY_SIZE";
    static final String MAX_HEADER_SIZE_PROPERTY = "FLUXZERO_PROXY_MAX_HEADER_SIZE";
    static final String IDLE_TIMEOUT_MILLIS_PROPERTY = "FLUXZERO_PROXY_IDLE_TIMEOUT_MILLIS";
    static final String MAX_THREADS_PROPERTY = "FLUXZERO_PROXY_MAX_THREADS";
    static final String MIN_THREADS_PROPERTY = "FLUXZERO_PROXY_MIN_THREADS";
    static final String USE_VIRTUAL_THREADS_PROPERTY = "FLUXZERO_PROXY_USE_VIRTUAL_THREADS";
    static final String MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY = "FLUXZERO_PROXY_MAX_IN_FLIGHT_WEB_REQUESTS";
    static final String MAX_PENDING_WEBSOCKET_SENDS_PROPERTY = "FLUXZERO_PROXY_MAX_PENDING_WEBSOCKET_SENDS";
    static final String WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY = "FLUXZERO_PROXY_WEBSOCKET_IDLE_TIMEOUT_MILLIS";
    static final String WEBSOCKET_PING_DELAY_MILLIS_PROPERTY = "FLUXZERO_PROXY_WEBSOCKET_PING_DELAY_MILLIS";
    static final String WEBSOCKET_PING_TIMEOUT_MILLIS_PROPERTY = "FLUXZERO_PROXY_WEBSOCKET_PING_TIMEOUT_MILLIS";
    static final String COMPRESSION_ALGORITHMS_PROPERTY = "FLUXZERO_PROXY_COMPRESSION_ALGORITHMS";

    private static final long UNLIMITED_WEBSOCKET_SIZE = 0L;
    private static final long IMMEDIATE_STOP_TIMEOUT_MILLIS = 0L;
    private static final long GRACEFUL_STOP_TIMEOUT_MILLIS = 5000L;
    private static final byte[] HEALTH_PAYLOAD = "Healthy".getBytes(StandardCharsets.UTF_8);
    private static final String HEALTH_PAYLOAD_LENGTH = String.valueOf(HEALTH_PAYLOAD.length);
    private static final byte[] READY_PAYLOAD = "Ready".getBytes(StandardCharsets.UTF_8);
    private static final String READY_PAYLOAD_LENGTH = String.valueOf(READY_PAYLOAD.length);
    private static final byte[] NOT_READY_PAYLOAD = "Not ready".getBytes(StandardCharsets.UTF_8);
    private static final String NOT_READY_PAYLOAD_LENGTH = String.valueOf(NOT_READY_PAYLOAD.length);

    /**
     * Standalone process entry point.
     *
     * <p>Do not call this method to embed the proxy in another application. Use {@link #start()} instead so the caller
     * owns the proxy lifecycle and shutdown order.</p>
     */
    public static void main(final String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("Uncaught error", e));
        Client client = createConfiguredClient();

        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .makeApplicationInstance(true)
                .build(client);

        ForwardProxyConsumer.Lifecycle forwardProxy = ForwardProxyConsumer.startManaged(client);
        ProxyServer proxyServer;
        try {
            proxyServer = startHttpProxyOnly(
                    getConfiguredPort(), new ProxyRequestHandler(client), forwardProxy, forwardProxy::force,
                    Registration.noOp(), true, LifecycleState.STARTING);
            proxyServer.probeRuntimeReadiness(client);
        } catch (RuntimeException | Error e) {
            forwardProxy.force();
            forwardProxy.cancel();
            fluxzero.close(true);
            throw e;
        }
        logStarted(proxyServer);

        fluxzero.beforeShutdown(() -> {
            log.info("Stopping Fluxzero proxy server");
            proxyServer.cancel();
        });
    }

    /**
     * Starts an embedded proxy server using the configured port and runtime base URL.
     *
     * <p>The port is resolved from {@code FLUXZERO_PROXY_PORT}, {@code PROXY_PORT}, or {@code 8080}, in that order. The
     * Fluxzero runtime URL is resolved from {@code FLUXZERO_BASE_URL}, {@code FLUX_BASE_URL}, or {@code FLUX_URL}, in
     * that order. The returned server owns the created Fluxzero client and forward proxy consumer; callers should stop
     * all embedded proxy resources by calling {@link #cancel()}.</p>
     *
     * <p>This method does not register a JVM shutdown hook, create a Fluxzero keepalive thread, or set a global
     * Fluxzero application instance. Use {@link #main(String[])} for standalone process startup.</p>
     *
     * @return a ProxyServer instance representing the started proxy server and owned embedded resources
     */
    public static ProxyServer start() {
        Client client = createConfiguredClient();
        ForwardProxyConsumer.Lifecycle forwardProxyConsumer = null;
        try {
            forwardProxyConsumer = ForwardProxyConsumer.startManaged(client);
            ProxyServer proxyServer = startHttpProxyOnly(
                    getConfiguredPort(), new ProxyRequestHandler(client), forwardProxyConsumer,
                    forwardProxyConsumer::force, idempotent(client::shutDown), true, LifecycleState.STARTING);
            proxyServer.probeRuntimeReadiness(client);
            logStarted(proxyServer);
            return proxyServer;
        } catch (RuntimeException | Error e) {
            try {
                if (forwardProxyConsumer != null) {
                    forwardProxyConsumer.force();
                    forwardProxyConsumer.cancel();
                }
            } finally {
                client.shutDown();
            }
            throw e;
        }
    }

    /**
     * Starts only the HTTP proxy surface for tests and embedded callers that provide their own forwarding lifecycle.
     */
    static ProxyServer startHttpProxyOnly(int port, ProxyRequestHandler proxyHandler) {
        return startHttpProxyOnly(port, proxyHandler, Registration.noOp(), () -> {}, Registration.noOp(),
                                  false, LifecycleState.READY);
    }

    private static void logStarted(ProxyServer proxyServer) {
        ProxyVersion.version().ifPresentOrElse(
                version -> log.info("Fluxzero proxy server (version {}) running on port {}",
                                    version, proxyServer.getPort()),
                () -> log.info("Fluxzero proxy server running on port {}", proxyServer.getPort()));
    }

    private static ProxyServer startHttpProxyOnly(int port, ProxyRequestHandler proxyHandler,
                                                 Registration shutdownRegistration, Runnable forceShutdown,
                                                 Registration ownedClientShutdown, boolean gracefulShutdown,
                                                 LifecycleState initialState) {
        Server server = new Server(createThreadPool());
        server.setStopAtShutdown(false);
        // Standalone proxy shutdown remains graceful; tests use the HTTP-only helper with immediate Jetty stop.
        server.setStopTimeout(gracefulShutdown ? GRACEFUL_STOP_TIMEOUT_MILLIS : IMMEDIATE_STOP_TIMEOUT_MILLIS);

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setSendServerVersion(false);
        httpConfiguration.setSendXPoweredBy(false);
        int maxHeaderSize = getIntegerProperty(MAX_HEADER_SIZE_PROPERTY, DEFAULT_MAX_HEADER_SIZE);
        httpConfiguration.setRequestHeaderSize(maxHeaderSize);
        httpConfiguration.setResponseHeaderSize(maxHeaderSize);
        ServerConnector connector = new ServerConnector(
                server,
                new HttpConnectionFactory(httpConfiguration),
                new HTTP2CServerConnectionFactory(httpConfiguration));
        connector.setHost("0.0.0.0");
        connector.setPort(port);
        connector.setIdleTimeout(getLongProperty(IDLE_TIMEOUT_MILLIS_PROPERTY, DEFAULT_IDLE_TIMEOUT_MILLIS));
        server.addConnector(connector);

        proxyHandler.setMaxRequestBodySize(getLongProperty(
                MAX_REQUEST_BODY_SIZE_PROPERTY, DEFAULT_MAX_REQUEST_BODY_SIZE));
        proxyHandler.setMaxMultipartRequestBodySize(getLongProperty(
                MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY, DEFAULT_MAX_MULTIPART_REQUEST_BODY_SIZE));
        proxyHandler.setMaxInFlightWebRequests(getConfiguredMaxInFlightWebRequests());
        proxyHandler.setMaxPendingWebsocketSends(getConfiguredMaxPendingWebsocketSends());

        String healthEndpoint = getProperty("PROXY_HEALTH_ENDPOINT", "/proxy/health");
        String readinessEndpoint = getProperty(READINESS_ENDPOINT_PROPERTY, DEFAULT_READINESS_ENDPOINT);
        if (healthEndpoint.equals(readinessEndpoint)) {
            throw new IllegalArgumentException("Proxy health and readiness endpoints must be different");
        }
        Duration drainDelay = configuredDrainDelay();
        Duration shutdownTimeout = configuredShutdownTimeout();
        AtomicReference<LifecycleState> lifecycleState = new AtomicReference<>(initialState);
        server.setHandler(createHandler(server, proxyHandler, healthEndpoint, readinessEndpoint, lifecycleState));
        try {
            server.start();
        } catch (Exception e) {
            try {
                server.stop();
            } catch (Exception stopError) {
                e.addSuppressed(stopError);
            }
            throw new IllegalStateException("Failed to start Fluxzero proxy server", e);
        }
        return new ProxyServer(proxyHandler, server, connector.getLocalPort(), shutdownRegistration, forceShutdown,
                               ownedClientShutdown, gracefulShutdown, lifecycleState,
                               drainDelay, shutdownTimeout);
    }

    private static QueuedThreadPool createThreadPool() {
        int maxThreads = getIntegerProperty(MAX_THREADS_PROPERTY, DEFAULT_MAX_THREADS);
        int minThreads = getIntegerProperty(MIN_THREADS_PROPERTY, DEFAULT_MIN_THREADS);
        if (maxThreads < 1) {
            throw new IllegalArgumentException(MAX_THREADS_PROPERTY + " must be >= 1");
        }
        if (minThreads < 1) {
            throw new IllegalArgumentException(MIN_THREADS_PROPERTY + " must be >= 1");
        }
        if (minThreads > maxThreads) {
            throw new IllegalArgumentException(MIN_THREADS_PROPERTY + " must be <= " + MAX_THREADS_PROPERTY);
        }

        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads);
        threadPool.setName("fluxzero-proxy");
        if (getBooleanProperty(USE_VIRTUAL_THREADS_PROPERTY, false)) {
            if (supportsVirtualThreadWorkers()) {
                threadPool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
            } else {
                log.warn("{} is enabled but virtual-thread workers are only supported on Java 25+",
                         USE_VIRTUAL_THREADS_PROPERTY);
            }
        }
        return threadPool;
    }

    private static int getConfiguredMaxInFlightWebRequests() {
        int maxInFlight = getIntegerProperty(MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY,
                                             DEFAULT_MAX_IN_FLIGHT_WEB_REQUESTS);
        if (maxInFlight < 0) {
            throw new IllegalArgumentException(MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY + " must be >= 0");
        }
        return maxInFlight;
    }

    private static int getConfiguredMaxPendingWebsocketSends() {
        int maxPendingSends = getIntegerProperty(MAX_PENDING_WEBSOCKET_SENDS_PROPERTY,
                                                 DEFAULT_MAX_PENDING_WEBSOCKET_SENDS);
        if (maxPendingSends < 1) {
            throw new IllegalArgumentException(MAX_PENDING_WEBSOCKET_SENDS_PROPERTY + " must be >= 1");
        }
        return maxPendingSends;
    }

    static Duration getConfiguredWebsocketIdleTimeout() {
        long timeoutMillis = getLongProperty(WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY,
                                             DEFAULT_WEBSOCKET_IDLE_TIMEOUT_MILLIS);
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY + " must be >= 1");
        }
        return Duration.ofMillis(timeoutMillis);
    }

    static Duration getConfiguredWebsocketPingDelay() {
        long delayMillis = getLongProperty(WEBSOCKET_PING_DELAY_MILLIS_PROPERTY,
                                           DEFAULT_WEBSOCKET_PING_DELAY_MILLIS);
        if (delayMillis < 0) {
            throw new IllegalArgumentException(WEBSOCKET_PING_DELAY_MILLIS_PROPERTY + " must be >= 0");
        }
        return Duration.ofMillis(delayMillis);
    }

    static Duration getConfiguredWebsocketPingTimeout() {
        long timeoutMillis = getLongProperty(WEBSOCKET_PING_TIMEOUT_MILLIS_PROPERTY,
                                             DEFAULT_WEBSOCKET_PING_TIMEOUT_MILLIS);
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(WEBSOCKET_PING_TIMEOUT_MILLIS_PROPERTY + " must be >= 1");
        }
        return Duration.ofMillis(timeoutMillis);
    }

    private static Handler createHandler(Server server, ProxyRequestHandler proxyHandler, String healthEndpoint,
                                         String readinessEndpoint,
                                         AtomicReference<LifecycleState> lifecycleState) {
        ContextHandler context = new ContextHandler("/");
        WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(server, context, container -> {
            configureContainer(container);
            proxyHandler.setWebsocketContainer(container);
        });
        webSocketHandler.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response,
                                  org.eclipse.jetty.util.Callback callback) throws Exception {
                return proxyHandler.handle(request, response, callback);
            }
        });
        context.setHandler(new Handler.Wrapper(webSocketHandler) {
            @Override
            public boolean handle(Request request, Response response,
                                  org.eclipse.jetty.util.Callback callback) throws Exception {
                if (healthEndpoint.equals(Request.getPathInContext(request))) {
                    writeStatus(response, callback, HttpStatus.OK_200, HEALTH_PAYLOAD, HEALTH_PAYLOAD_LENGTH);
                    return true;
                }
                if (readinessEndpoint.equals(Request.getPathInContext(request))) {
                    boolean ready = lifecycleState.get() == LifecycleState.READY;
                    writeStatus(response, callback, ready ? HttpStatus.OK_200 : HttpStatus.SERVICE_UNAVAILABLE_503,
                                ready ? READY_PAYLOAD : NOT_READY_PAYLOAD,
                                ready ? READY_PAYLOAD_LENGTH : NOT_READY_PAYLOAD_LENGTH);
                    return true;
                }
                return super.handle(request, response, callback);
            }
        });
        return context;
    }

    private static void writeStatus(Response response, org.eclipse.jetty.util.Callback callback, int status,
                                    byte[] payload, String payloadLength) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, payloadLength);
        response.write(true, ByteBuffer.wrap(payload), callback);
    }

    private static void configureContainer(ServerWebSocketContainer container) {
        container.setIdleTimeout(getConfiguredWebsocketIdleTimeout());
        container.setMaxBinaryMessageSize(UNLIMITED_WEBSOCKET_SIZE);
        container.setMaxTextMessageSize(UNLIMITED_WEBSOCKET_SIZE);
        container.setMaxFrameSize(UNLIMITED_WEBSOCKET_SIZE);
    }

    private static Client createConfiguredClient() {
        return Optional.ofNullable(getFirstAvailableProperty("FLUXZERO_BASE_URL", "FLUX_BASE_URL", "FLUX_URL"))
                .map(url -> {
                    WebSocketClient.ClientConfig.ClientConfigBuilder builder =
                            WebSocketClient.ClientConfig.builder()
                                    .name(getProperty("FLUXZERO_APPLICATION_NAME", "$proxy"))
                                    .runtimeBaseUrl(url)
                                    .disableMetrics(!getBooleanProperty(
                                            ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, true))
                                    .namespace(getFirstAvailableProperty("FLUXZERO_NAMESPACE", "FLUXZERO_PROJECT_ID",
                                                                         "FLUX_PROJECT_ID", "PROJECT_ID"));
                    getConfiguredCompressionAlgorithms().ifPresent(builder::supportedCompressionAlgorithms);
                    return WebSocketClient.newInstance(builder.build());
                })
                .orElseThrow(() -> new IllegalStateException(
                        "FLUXZERO_BASE_URL, FLUX_BASE_URL or FLUX_URL property is not set"));
    }

    static Optional<List<CompressionAlgorithm>> getConfiguredCompressionAlgorithms() {
        String algorithms = getProperty(COMPRESSION_ALGORITHMS_PROPERTY, null);
        if (algorithms == null || algorithms.isBlank()) {
            return Optional.empty();
        }
        List<CompressionAlgorithm> parsedAlgorithms = Arrays.stream(algorithms.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(CompressionAlgorithm::valueOf)
                .distinct()
                .toList();
        if (parsedAlgorithms.isEmpty()) {
            throw new IllegalArgumentException(COMPRESSION_ALGORITHMS_PROPERTY + " must not be empty when configured");
        }
        return Optional.of(parsedAlgorithms);
    }

    private static int getConfiguredPort() {
        return getIntegerProperty("FLUXZERO_PROXY_PORT", getIntegerProperty("PROXY_PORT", 8080));
    }

    private static Duration configuredDrainDelay() {
        long millis = getLongProperty(DRAIN_DELAY_MILLIS_PROPERTY, DEFAULT_DRAIN_DELAY_MILLIS);
        if (millis < 0L) {
            throw new IllegalArgumentException(DRAIN_DELAY_MILLIS_PROPERTY + " must be >= 0");
        }
        return Duration.ofMillis(millis);
    }

    private static Duration configuredShutdownTimeout() {
        long millis = getLongProperty(SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
        if (millis < 1L) {
            throw new IllegalArgumentException(SHUTDOWN_TIMEOUT_MILLIS_PROPERTY + " must be >= 1");
        }
        return Duration.ofMillis(millis);
    }

    private static Registration idempotent(Runnable task) {
        AtomicBoolean invoked = new AtomicBoolean();
        return () -> {
            if (invoked.compareAndSet(false, true)) {
                task.run();
            }
        };
    }

    private final ProxyRequestHandler proxyHandler;
    private final Server server;
    @Getter
    private final int port;
    private final Registration shutdownRegistration;
    private final Runnable forceShutdown;
    private final Registration ownedClientShutdown;
    private final boolean gracefulShutdown;
    private final AtomicReference<LifecycleState> lifecycleState;
    private final Duration drainDelay;
    private final Duration shutdownTimeout;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread readinessThread;

    protected ProxyServer(ProxyRequestHandler proxyHandler, Server server, int port,
                          Registration shutdownRegistration, Runnable forceShutdown,
                          Registration ownedClientShutdown, boolean gracefulShutdown,
                          AtomicReference<LifecycleState> lifecycleState,
                          Duration drainDelay, Duration shutdownTimeout) {
        this.proxyHandler = proxyHandler;
        this.server = server;
        this.port = port;
        this.shutdownRegistration = shutdownRegistration;
        this.forceShutdown = forceShutdown;
        this.ownedClientShutdown = ownedClientShutdown;
        this.gracefulShutdown = gracefulShutdown;
        this.lifecycleState = lifecycleState;
        this.drainDelay = drainDelay;
        this.shutdownTimeout = shutdownTimeout;
    }

    long getIdleTimeoutMillis() {
        return server.getConnectors().length == 0 ? -1L : connectorIdleTimeout();
    }

    long getWebsocketIdleTimeoutMillis() {
        return proxyHandler.getWebsocketIdleTimeoutMillis();
    }

    int getMaxThreads() {
        return ((QueuedThreadPool) server.getThreadPool()).getMaxThreads();
    }

    int getMinThreads() {
        return ((QueuedThreadPool) server.getThreadPool()).getMinThreads();
    }

    boolean isUsingVirtualThreads() {
        return VirtualThreads.isUseVirtualThreads(server.getThreadPool());
    }

    /**
     * Returns whether the proxy has reached runtime readiness and is not draining.
     */
    public boolean isReady() {
        return lifecycleState.get() == LifecycleState.READY;
    }

    private long connectorIdleTimeout() {
        return ((ServerConnector) server.getConnectors()[0]).getIdleTimeout();
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            lifecycleState.set(LifecycleState.DRAINING);
            Thread thread = readinessThread;
            if (thread != null) {
                thread.interrupt();
            }
            long deadline = System.nanoTime() + shutdownTimeout.toNanos();
            CompletableFuture<Void> backendShutdown = new CompletableFuture<>();
            Thread.ofVirtual().name("fluxzero-proxy-backend-shutdown").start(() -> {
                try {
                    shutdownRegistration.cancel();
                    backendShutdown.complete(null);
                } catch (Throwable e) {
                    backendShutdown.completeExceptionally(e);
                }
            });
            try {
                awaitDrainDelay(deadline);
                server.setStopTimeout(gracefulShutdown
                                              ? Math.min(GRACEFUL_STOP_TIMEOUT_MILLIS, remainingMillis(deadline))
                                              : IMMEDIATE_STOP_TIMEOUT_MILLIS);
                server.stop();
            } catch (Exception e) {
                log.warn("Failed to stop Fluxzero proxy server", e);
            } finally {
                try {
                    proxyHandler.close(gracefulShutdown);
                } catch (RuntimeException e) {
                    log.warn("Failed to close Fluxzero proxy request handling", e);
                } finally {
                    try {
                        awaitBackendShutdown(backendShutdown, deadline);
                    } finally {
                        try {
                            ownedClientShutdown.cancel();
                        } finally {
                            lifecycleState.set(LifecycleState.STOPPED);
                        }
                    }
                }
            }
        }
    }

    private void probeRuntimeReadiness(Client client) {
        readinessThread = Thread.ofVirtual().name("fluxzero-proxy-readiness").start(() -> {
            while (lifecycleState.get() == LifecycleState.STARTING) {
                try {
                    client.getTrackingClient(io.fluxzero.common.MessageType.WEBREQUEST)
                            .getPosition(ForwardProxyConsumer.defaultSettings.getConsumer());
                    lifecycleState.compareAndSet(LifecycleState.STARTING, LifecycleState.READY);
                    return;
                } catch (Throwable e) {
                    if (lifecycleState.get() != LifecycleState.STARTING || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    private void awaitDrainDelay(long deadline) {
        long delayNanos = Math.min(drainDelay.toNanos(), Math.max(0L, deadline - System.nanoTime()));
        if (delayNanos <= 0L) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(delayNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitBackendShutdown(CompletableFuture<Void> backendShutdown, long deadline) {
        try {
            backendShutdown.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.warn("Graceful proxy shutdown timed out after {}. Forcing remaining outbound requests to stop",
                     shutdownTimeout);
            forceShutdown.run();
            try {
                backendShutdown.get(1L, TimeUnit.SECONDS);
            } catch (Exception forceError) {
                log.warn("Proxy backend did not stop after forced shutdown", forceError);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            forceShutdown.run();
        } catch (ExecutionException e) {
            log.warn("Proxy backend failed during shutdown", e.getCause());
        }
    }

    private long remainingMillis(long deadline) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private enum LifecycleState {
        STARTING, READY, DRAINING, STOPPED
    }
}
