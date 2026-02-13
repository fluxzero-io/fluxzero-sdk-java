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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Optional;

import static io.fluxzero.sdk.configuration.ApplicationProperties.getFirstAvailableProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getProperty;
import static io.undertow.Handlers.path;
import static io.undertow.util.Headers.CONTENT_TYPE;

@Slf4j
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class ProxyServer implements Registration {
    public static void main(final String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("Uncaught error", e));
        int port = getIntegerProperty("PROXY_PORT", 8080);
        Client client = Optional.ofNullable(getFirstAvailableProperty("FLUXZERO_BASE_URL", "FLUX_BASE_URL", "FLUX_URL"))
                .map(url -> WebSocketClient.newInstance(
                        WebSocketClient.ClientConfig.builder()
                                .name(getProperty("FLUXZERO_APPLICATION_NAME", "$proxy"))
                                .runtimeBaseUrl(url)
                                .namespace(getFirstAvailableProperty("FLUXZERO_NAMESPACE", "FLUXZERO_PROJECT_ID", "FLUX_PROJECT_ID", "PROJECT_ID")).build()))
                .orElseThrow(() -> new IllegalStateException("FLUXZERO_BASE_URL environment variable is not set"));

        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .makeApplicationInstance(true)
                .build(client);

        Registration registration = start(port, new ProxyRequestHandler(client))
                .merge(ForwardProxyConsumer.start(client));
        log.info("Fluxzero proxy server running on port {}", port);

        fluxzero.beforeShutdown(() -> {
            log.info("Stopping Fluxzero proxy server");
            registration.cancel();
        });
    }

    /**
     * Starts a proxy server on a random available port with the specified proxy request handler.
     * The server will listen for HTTP requests and route them through the provided handler.
     *
     * @param proxyHandler the handler responsible for processing proxy requests.
     * @return a ProxyServer instance representing the started proxy server, allowing further management such as shutdown.
     */
    public static ProxyServer start(ProxyRequestHandler proxyHandler) {
        return start(0, proxyHandler);
    }

    /**
     * Starts a proxy server on the specified port with the given proxy request handler.
     * The server will listen for HTTP requests and route them through the provided handler.
     * Additionally, it sets up a health endpoint that responds with a simple "Healthy" message.
     *
     * @param port the port number on which the proxy server will listen. Use 0 to select a random available port.
     * @param proxyHandler the handler responsible for processing proxy requests.
     * @return a ProxyServer instance representing the started proxy server, allowing further management such as shutdown.
     */
    public static ProxyServer start(int port, ProxyRequestHandler proxyHandler) {
        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, 16L * 1024 * 1024)
                .setServerOption(UndertowOptions.MULTIPART_MAX_ENTITY_SIZE, 16L * 1024 * 1024)
                .setHandler(path()
                        .addPrefixPath("/", proxyHandler)
                        .addExactPath(getProperty("PROXY_HEALTH_ENDPOINT", "/proxy/health"), exchange -> {
                            exchange.getResponseHeaders().put(CONTENT_TYPE, "text/plain");
                            exchange.getResponseSender().send("Healthy");
                        }))
                .build();
        server.start();
        port = server.getListenerInfo().getFirst().getAddress() instanceof InetSocketAddress a ? a.getPort() : port;
        return new ProxyServer(proxyHandler, server, port);
    }

    private final ProxyRequestHandler proxyHandler;
    private final Undertow server;
    @Getter
    private final int port;

    @Override
    public void cancel() {
        proxyHandler.close();
        server.stop();
    }
}
