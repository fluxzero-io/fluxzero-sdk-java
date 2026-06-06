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
import lombok.SneakyThrows;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.ObjectUtils.supportsVirtualThreadWorkers;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getFirstAvailableProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getLongProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getProperty;

@Slf4j
public class ProxyServer implements Registration {

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

    static final String MAX_REQUEST_BODY_SIZE_PROPERTY = "FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE";
    static final String MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY = "FLUXZERO_PROXY_MAX_MULTIPART_REQUEST_BODY_SIZE";
    static final String MAX_HEADER_SIZE_PROPERTY = "FLUXZERO_PROXY_MAX_HEADER_SIZE";
    static final String IDLE_TIMEOUT_MILLIS_PROPERTY = "FLUXZERO_PROXY_IDLE_TIMEOUT_MILLIS";
    static final String MAX_THREADS_PROPERTY = "FLUXZERO_PROXY_MAX_THREADS";
    static final String MIN_THREADS_PROPERTY = "FLUXZERO_PROXY_MIN_THREADS";
    static final String USE_VIRTUAL_THREADS_PROPERTY = "FLUXZERO_PROXY_USE_VIRTUAL_THREADS";
    static final String MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY = "FLUXZERO_PROXY_MAX_IN_FLIGHT_WEB_REQUESTS";
    static final String MAX_PENDING_WEBSOCKET_SENDS_PROPERTY = "FLUXZERO_PROXY_MAX_PENDING_WEBSOCKET_SENDS";
    static final String COMPRESSION_ALGORITHMS_PROPERTY = "FLUXZERO_PROXY_COMPRESSION_ALGORITHMS";

    private static final long UNLIMITED_WEBSOCKET_SIZE = 0L;
    private static final long IMMEDIATE_STOP_TIMEOUT_MILLIS = 0L;
    private static final long GRACEFUL_STOP_TIMEOUT_MILLIS = 5000L;
    private static final byte[] HEALTH_PAYLOAD = "Healthy".getBytes(StandardCharsets.UTF_8);
    private static final String HEALTH_PAYLOAD_LENGTH = String.valueOf(HEALTH_PAYLOAD.length);

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

        ProxyServer proxyServer = startHttpProxyOnly(
                getConfiguredPort(), new ProxyRequestHandler(client), Registration.noOp(), true);
        Registration registration = proxyServer
                .merge(ForwardProxyConsumer.start(client));
        logStarted(proxyServer);

        fluxzero.beforeShutdown(() -> {
            log.info("Stopping Fluxzero proxy server");
            registration.cancel();
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
     * <p>This method does not register a JVM shutdown hook, create a Fluxzero keepalive thread, or set a global Fluxzero
     * application instance. Use {@link #main(String[])} for standalone process startup.</p>
     *
     * @return a ProxyServer instance representing the started proxy server and owned embedded resources
     */
    public static ProxyServer start() {
        Client client = createConfiguredClient();
        Registration forwardProxyConsumer = Registration.noOp();
        try {
            forwardProxyConsumer = ForwardProxyConsumer.start(client);
            Registration startedForwardProxyConsumer = forwardProxyConsumer;
            ProxyServer proxyServer = startHttpProxyOnly(getConfiguredPort(), new ProxyRequestHandler(client), () -> {
                try {
                    startedForwardProxyConsumer.cancel();
                } finally {
                    client.shutDown();
                }
            }, true);
            logStarted(proxyServer);
            return proxyServer;
        } catch (RuntimeException | Error e) {
            try {
                forwardProxyConsumer.cancel();
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
        return startHttpProxyOnly(port, proxyHandler, Registration.noOp(), false);
    }

    private static void logStarted(ProxyServer proxyServer) {
        ProxyVersion.version().ifPresentOrElse(
                version -> log.info("Fluxzero proxy server (version {}) running on port {}",
                                    version, proxyServer.getPort()),
                () -> log.info("Fluxzero proxy server running on port {}", proxyServer.getPort()));
    }

    @SneakyThrows
    private static ProxyServer startHttpProxyOnly(int port, ProxyRequestHandler proxyHandler,
                                                 Registration shutdownRegistration, boolean gracefulShutdown) {
        Server server = new Server(createThreadPool());
        server.setStopAtShutdown(false);
        // Standalone proxy shutdown remains graceful; tests use the HTTP-only helper with immediate Jetty stop.
        server.setStopTimeout(gracefulShutdown ? GRACEFUL_STOP_TIMEOUT_MILLIS : IMMEDIATE_STOP_TIMEOUT_MILLIS);

        HttpConfiguration httpConfiguration = new HttpConfiguration();
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

        server.setHandler(createHandler(server, proxyHandler, getProperty("PROXY_HEALTH_ENDPOINT", "/proxy/health")));
        server.start();
        return new ProxyServer(proxyHandler, server, connector.getLocalPort(), shutdownRegistration, gracefulShutdown);
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

    private static Handler createHandler(Server server, ProxyRequestHandler proxyHandler, String healthEndpoint) {
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
                    response.setStatus(HttpStatus.OK_200);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, HEALTH_PAYLOAD_LENGTH);
                    response.write(true, ByteBuffer.wrap(HEALTH_PAYLOAD), callback);
                    return true;
                }
                return super.handle(request, response, callback);
            }
        });
        return context;
    }

    private static void configureContainer(ServerWebSocketContainer container) {
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

    private final ProxyRequestHandler proxyHandler;
    private final Server server;
    @Getter
    private final int port;
    private final Registration shutdownRegistration;
    private final boolean gracefulShutdown;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    protected ProxyServer(ProxyRequestHandler proxyHandler, Server server, int port,
                          Registration shutdownRegistration, boolean gracefulShutdown) {
        this.proxyHandler = proxyHandler;
        this.server = server;
        this.port = port;
        this.shutdownRegistration = shutdownRegistration;
        this.gracefulShutdown = gracefulShutdown;
    }

    long getIdleTimeoutMillis() {
        return server.getConnectors().length == 0 ? -1L : connectorIdleTimeout();
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

    private long connectorIdleTimeout() {
        return ((ServerConnector) server.getConnectors()[0]).getIdleTimeout();
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            try {
                proxyHandler.close(gracefulShutdown);
                server.stop();
            } catch (Exception e) {
                log.warn("Failed to stop Fluxzero proxy server", e);
            } finally {
                shutdownRegistration.cancel();
            }
        }
    }
}
