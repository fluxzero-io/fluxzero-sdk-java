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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.publishing.TimeoutException;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.testserver.TestServer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.NONE;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class ProxyServerLifecycleTest {
    private static final Duration FORWARDED_HEALTH_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FORWARDED_HEALTH_MAX_WAIT = Duration.ofSeconds(25);
    private static final Duration FORWARDED_HEALTH_RETRY_DELAY = Duration.ofMillis(100);

    @Test
    @Timeout(45)
    void startWithoutArgumentsStartsHttpAndForwardProxyUsingConfiguredProperties() throws Exception {
        Server testServer = null;
        ProxyServer proxyServer = null;
        Fluxzero requester = null;
        ServerSocket occupiedProxyPort = null;
        String previousFluxzeroProxyPort = System.getProperty("FLUXZERO_PROXY_PORT");
        String previousProxyPort = System.getProperty("PROXY_PORT");
        String previousFluxzeroBaseUrl = System.getProperty("FLUXZERO_BASE_URL");
        String previousFluxBaseUrl = System.getProperty("FLUX_BASE_URL");
        String previousFluxUrl = System.getProperty("FLUX_URL");
        String previousProxyMetricsEnabled = System.getProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY);
        String previousProxyCompressionAlgorithms = System.getProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY);
        try {
            testServer = TestServer.startServer(0);
            String runtimeUrl = "ws://localhost:" + localPort(testServer);
            occupiedProxyPort = new ServerSocket(0);

            System.setProperty("FLUXZERO_PROXY_PORT", "0");
            System.setProperty("PROXY_PORT", String.valueOf(occupiedProxyPort.getLocalPort()));
            System.setProperty("FLUXZERO_BASE_URL", runtimeUrl);
            System.setProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, "false");
            System.setProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, NONE.name());
            System.clearProperty("FLUX_BASE_URL");
            System.clearProperty("FLUX_URL");

            proxyServer = ProxyServer.start();

            assertTrue(proxyServer.getPort() > 0);
            assertNotEquals(occupiedProxyPort.getLocalPort(), proxyServer.getPort());

            String healthUrl = "http://localhost:" + proxyServer.getPort() + "/proxy/health";
            assertLocalHealth(healthUrl);

            WebSocketClient.ClientConfig requesterConfig = WebSocketClient.ClientConfig.builder()
                    .name("proxy-lifecycle-test")
                    .runtimeBaseUrl(runtimeUrl)
                    .supportedCompressionAlgorithms(List.of(NONE))
                    .disableMetrics(true)
                    .build();
            requester = DefaultFluxzero.builder()
                    .disableAutomaticTracking()
                    .disableTrackingMetrics()
                    .disableShutdownHook()
                    .disableKeepalive()
                    .build(WebSocketClient.newInstance(requesterConfig));
            WebResponse response = sendForwardedHealthRequest(requester, healthUrl);
            assertEquals(200, response.getStatus());
            assertEquals("Healthy", new String(response.<byte[]>getPayload(), StandardCharsets.UTF_8));

            requester.close(true);
            requester = null;
            proxyServer.cancel();
            proxyServer.cancel();
        } finally {
            if (requester != null) {
                requester.close(true);
            }
            if (proxyServer != null) {
                proxyServer.cancel();
            }
            if (occupiedProxyPort != null) {
                occupiedProxyPort.close();
            }
            if (testServer != null) {
                testServer.stop();
            }
            restoreProperty("FLUXZERO_PROXY_PORT", previousFluxzeroProxyPort);
            restoreProperty("PROXY_PORT", previousProxyPort);
            restoreProperty("FLUXZERO_BASE_URL", previousFluxzeroBaseUrl);
            restoreProperty("FLUX_BASE_URL", previousFluxBaseUrl);
            restoreProperty("FLUX_URL", previousFluxUrl);
            restoreProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, previousProxyMetricsEnabled);
            restoreProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, previousProxyCompressionAlgorithms);
        }
    }

    private static void assertLocalHealth(String healthUrl) throws Exception {
        HttpClient httpClient = HttpClient.newBuilder().build();
        try {
            var response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(healthUrl)).GET().build(), BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("Healthy", response.body());
        } finally {
            httpClient.shutdownNow();
        }
    }

    private static WebResponse sendForwardedHealthRequest(Fluxzero requester, String healthUrl)
            throws InterruptedException {
        long deadline = System.nanoTime() + FORWARDED_HEALTH_MAX_WAIT.toNanos();
        while (true) {
            try {
                return requester.webRequestGateway().sendAndWait(WebRequest.builder()
                                                                 .url(healthUrl)
                                                                 .method(GET)
                                                                 .build(),
                                                         WebRequestSettings.builder()
                                                                 .timeout(FORWARDED_HEALTH_ATTEMPT_TIMEOUT)
                                                                 .build());
            } catch (TimeoutException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                Thread.sleep(FORWARDED_HEALTH_RETRY_DELAY.toMillis());
            }
        }
    }

    private static int localPort(Server server) {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
