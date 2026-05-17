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

import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.sdk.configuration.ApplicationProperties.getFirstAvailableProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getLongProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getProperty;
import static io.undertow.Handlers.path;
import static io.undertow.UndertowOptions.ENABLE_HTTP2;
import static io.undertow.util.Headers.CONTENT_TYPE;

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

        ProxyServer proxyServer = startHttpProxyOnly(getConfiguredPort(), new ProxyRequestHandler(client));
        Registration registration = proxyServer
                .merge(ForwardProxyConsumer.start(client));
        log.info("Fluxzero proxy server running on port {}", proxyServer.getPort());

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
            return startHttpProxyOnly(getConfiguredPort(), new ProxyRequestHandler(client), () -> {
                try {
                    startedForwardProxyConsumer.cancel();
                } finally {
                    client.shutDown();
                }
            });
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
        return startHttpProxyOnly(port, proxyHandler, Registration.noOp());
    }

    private static ProxyServer startHttpProxyOnly(int port, ProxyRequestHandler proxyHandler,
                                                 Registration shutdownRegistration) {
        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, getLongProperty(
                        "FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE", DEFAULT_MAX_REQUEST_BODY_SIZE))
                .setServerOption(UndertowOptions.MULTIPART_MAX_ENTITY_SIZE, getLongProperty(
                        "FLUXZERO_PROXY_MAX_MULTIPART_REQUEST_BODY_SIZE", DEFAULT_MAX_MULTIPART_REQUEST_BODY_SIZE))
                .setServerOption(ENABLE_HTTP2, true)
                .setHandler(path()
                        .addPrefixPath("/", proxyHandler)
                        .addExactPath(getProperty("PROXY_HEALTH_ENDPOINT", "/proxy/health"), exchange -> {
                            exchange.getResponseHeaders().put(CONTENT_TYPE, "text/plain");
                            exchange.getResponseSender().send("Healthy");
                        }))
                .build();
        server.start();
        port = server.getListenerInfo().getFirst().getAddress() instanceof InetSocketAddress a ? a.getPort() : port;
        return new ProxyServer(proxyHandler, server, port, shutdownRegistration);
    }

    private static Client createConfiguredClient() {
        return Optional.ofNullable(getFirstAvailableProperty("FLUXZERO_BASE_URL", "FLUX_BASE_URL", "FLUX_URL"))
                .map(url -> WebSocketClient.newInstance(
                        WebSocketClient.ClientConfig.builder()
                                .name(getProperty("FLUXZERO_APPLICATION_NAME", "$proxy"))
                                .runtimeBaseUrl(url)
                                .namespace(getFirstAvailableProperty("FLUXZERO_NAMESPACE", "FLUXZERO_PROJECT_ID",
                                                                       "FLUX_PROJECT_ID", "PROJECT_ID"))
                                .build()))
                .orElseThrow(() -> new IllegalStateException(
                        "FLUXZERO_BASE_URL, FLUX_BASE_URL or FLUX_URL property is not set"));
    }

    private static int getConfiguredPort() {
        return getIntegerProperty("FLUXZERO_PROXY_PORT", getIntegerProperty("PROXY_PORT", 8080));
    }

    private final ProxyRequestHandler proxyHandler;
    private final Undertow server;
    @Getter
    private final int port;
    private final Registration shutdownRegistration;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    protected ProxyServer(ProxyRequestHandler proxyHandler, Undertow server, int port,
                          Registration shutdownRegistration) {
        this.proxyHandler = proxyHandler;
        this.server = server;
        this.port = port;
        this.shutdownRegistration = shutdownRegistration;
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            try {
                proxyHandler.close(false);
                server.stop();
            } finally {
                shutdownRegistration.cancel();
            }
        }
    }
}
