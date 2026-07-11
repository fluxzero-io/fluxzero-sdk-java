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

import com.sun.net.httpserver.HttpServer;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.publishing.TimeoutException;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.testserver.TestServer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.NONE;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class ProxyServerLifecycleTest {
    private static final Duration FORWARDED_HEALTH_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FORWARDED_HEALTH_MAX_WAIT = Duration.ofSeconds(25);
    private static final Duration FORWARDED_HEALTH_RETRY_DELAY = Duration.ofMillis(100);

    @Test
    @Timeout(20)
    void failedHttpStartupStopsTheAlreadyStartedForwardConsumer() throws Exception {
        Server runtime = null;
        String previousFluxzeroProxyPort = System.getProperty("FLUXZERO_PROXY_PORT");
        String previousFluxzeroBaseUrl = System.getProperty("FLUXZERO_BASE_URL");
        String previousProxyMetricsEnabled = System.getProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY);
        String previousProxyCompressionAlgorithms = System.getProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY);
        String previousDrainDelay = System.getProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY);
        String previousShutdownTimeout = System.getProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY);
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            runtime = TestServer.startServer(0);
            configureProxy("ws://localhost:" + localPort(runtime), 0L, 1_000L);
            System.setProperty("FLUXZERO_PROXY_PORT", Integer.toString(occupiedPort.getLocalPort()));

            assertThrows(IllegalStateException.class, ProxyServer::start);

            assertEventuallyNoThread("forward-proxy-WEBREQUEST-");
        } finally {
            if (runtime != null) {
                runtime.stop();
            }
            restoreProperty("FLUXZERO_PROXY_PORT", previousFluxzeroProxyPort);
            restoreProperty("FLUXZERO_BASE_URL", previousFluxzeroBaseUrl);
            restoreProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, previousProxyMetricsEnabled);
            restoreProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, previousProxyCompressionAlgorithms);
            restoreProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY, previousDrainDelay);
            restoreProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, previousShutdownTimeout);
        }
    }

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
        String previousDrainDelay = System.getProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY);
        String previousShutdownTimeout = System.getProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY);
        try {
            testServer = TestServer.startServer(0);
            String runtimeUrl = "ws://localhost:" + localPort(testServer);
            occupiedProxyPort = new ServerSocket(0);

            System.setProperty("FLUXZERO_PROXY_PORT", "0");
            System.setProperty("PROXY_PORT", String.valueOf(occupiedProxyPort.getLocalPort()));
            System.setProperty("FLUXZERO_BASE_URL", runtimeUrl);
            System.setProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, "false");
            System.setProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, NONE.name());
            System.setProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY, "1000");
            System.setProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, "5000");
            System.clearProperty("FLUX_BASE_URL");
            System.clearProperty("FLUX_URL");

            proxyServer = ProxyServer.start();

            assertTrue(proxyServer.getPort() > 0);
            assertNotEquals(occupiedProxyPort.getLocalPort(), proxyServer.getPort());

            String healthUrl = "http://localhost:" + proxyServer.getPort() + "/proxy/health";
            String readinessUrl = "http://localhost:" + proxyServer.getPort() + ProxyServer.DEFAULT_READINESS_ENDPOINT;
            assertLocalHealth(healthUrl);
            assertEventuallyReady(proxyServer, readinessUrl);

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
            requester.registerHandlers(new Object() {
                @HandleGet("/during-drain")
                String duringDrain() {
                    return "accepted-during-drain";
                }
            });
            WebResponse response = sendForwardedHealthRequest(requester, healthUrl);
            assertEquals(200, response.getStatus());
            assertEquals("Healthy", new String(response.<byte[]>getPayload(), StandardCharsets.UTF_8));
            assertEventuallyStatus("http://localhost:" + proxyServer.getPort() + "/during-drain",
                                   200, "accepted-during-drain");

            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxyServer::cancel);
            assertEventuallyStatus(readinessUrl, 503, "Not ready");
            assertFalse(shutdown.isDone(), "Proxy should remain available while the load balancer observes readiness");
            assertLocalHealth(healthUrl);
            assertEventuallyStatus("http://localhost:" + proxyServer.getPort() + "/during-drain",
                                   200, "accepted-during-drain");
            shutdown.get(5, TimeUnit.SECONDS);
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
            restoreProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY, previousDrainDelay);
            restoreProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, previousShutdownTimeout);
        }
    }

    @Test
    @Timeout(20)
    void shutdownLetsCurrentForwardBatchComplete() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        ExecutorService targetExecutor = Executors.newCachedThreadPool();
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.setExecutor(targetExecutor);
        target.createContext("/", exchange -> {
            requestReceived.countDown();
            try {
                releaseResponse.await();
                byte[] response = "completed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        target.start();

        Server runtime = null;
        ProxyServer proxyServer = null;
        Fluxzero requester = null;
        String previousFluxzeroProxyPort = System.getProperty("FLUXZERO_PROXY_PORT");
        String previousFluxzeroBaseUrl = System.getProperty("FLUXZERO_BASE_URL");
        String previousProxyMetricsEnabled = System.getProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY);
        String previousProxyCompressionAlgorithms = System.getProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY);
        String previousDrainDelay = System.getProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY);
        String previousShutdownTimeout = System.getProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY);
        try {
            runtime = TestServer.startServer(0);
            String runtimeUrl = "ws://localhost:" + localPort(runtime);
            configureProxy(runtimeUrl, 0L, 5_000L);
            proxyServer = ProxyServer.start();
            assertEventuallyReady(proxyServer,
                                  "http://localhost:" + proxyServer.getPort() + ProxyServer.DEFAULT_READINESS_ENDPOINT);
            requester = newRequester(runtimeUrl);

            CompletableFuture<WebResponse> response = requester.webRequestGateway().send(
                    WebRequest.builder().url("http://localhost:" + target.getAddress().getPort()).method(GET).build(),
                    WebRequestSettings.builder().timeout(Duration.ofSeconds(5)).build());
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxyServer::cancel);

            TimeUnit.MILLISECONDS.sleep(100L);
            assertFalse(shutdown.isDone(), "Shutdown returned before the active forward request completed");

            releaseResponse.countDown();
            assertEquals(200, response.get(2, TimeUnit.SECONDS).getStatus());
            shutdown.get(2, TimeUnit.SECONDS);
        } finally {
            releaseResponse.countDown();
            if (requester != null) {
                requester.close(true);
            }
            if (proxyServer != null) {
                proxyServer.cancel();
            }
            if (runtime != null) {
                runtime.stop();
            }
            target.stop(0);
            targetExecutor.shutdownNow();
            restoreProperty("FLUXZERO_PROXY_PORT", previousFluxzeroProxyPort);
            restoreProperty("FLUXZERO_BASE_URL", previousFluxzeroBaseUrl);
            restoreProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, previousProxyMetricsEnabled);
            restoreProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, previousProxyCompressionAlgorithms);
            restoreProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY, previousDrainDelay);
            restoreProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, previousShutdownTimeout);
        }
    }

    @Test
    @Timeout(20)
    void shutdownForcesPermanentlyBlockedForwardRequestAfterDeadline() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch neverReleaseDuringShutdown = new CountDownLatch(1);
        ExecutorService targetExecutor = Executors.newCachedThreadPool();
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.setExecutor(targetExecutor);
        target.createContext("/", exchange -> {
            requestReceived.countDown();
            try {
                neverReleaseDuringShutdown.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        target.start();

        Server runtime = null;
        ProxyServer proxyServer = null;
        Fluxzero requester = null;
        String previousFluxzeroProxyPort = System.getProperty("FLUXZERO_PROXY_PORT");
        String previousFluxzeroBaseUrl = System.getProperty("FLUXZERO_BASE_URL");
        String previousProxyMetricsEnabled = System.getProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY);
        String previousProxyCompressionAlgorithms = System.getProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY);
        String previousDrainDelay = System.getProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY);
        String previousShutdownTimeout = System.getProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY);
        try {
            runtime = TestServer.startServer(0);
            String runtimeUrl = "ws://localhost:" + localPort(runtime);
            configureProxy(runtimeUrl, 0L, 200L);
            proxyServer = ProxyServer.start();
            assertEventuallyReady(proxyServer,
                                  "http://localhost:" + proxyServer.getPort() + ProxyServer.DEFAULT_READINESS_ENDPOINT);
            requester = newRequester(runtimeUrl);

            CompletableFuture<WebResponse> response = requester.webRequestGateway().send(
                    WebRequest.builder().url("http://localhost:" + target.getAddress().getPort()).method(GET).build(),
                    WebRequestSettings.builder().timeout(Duration.ofSeconds(10)).build());
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();

            proxyServer.cancel();

            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0,
                       "Forced proxy shutdown exceeded its hard-stop allowance");
            assertEquals(502, response.get(2, TimeUnit.SECONDS).getStatus());
        } finally {
            neverReleaseDuringShutdown.countDown();
            if (requester != null) {
                requester.close(true);
            }
            if (proxyServer != null) {
                proxyServer.cancel();
            }
            if (runtime != null) {
                runtime.stop();
            }
            target.stop(0);
            targetExecutor.shutdownNow();
            restoreProperty("FLUXZERO_PROXY_PORT", previousFluxzeroProxyPort);
            restoreProperty("FLUXZERO_BASE_URL", previousFluxzeroBaseUrl);
            restoreProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, previousProxyMetricsEnabled);
            restoreProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, previousProxyCompressionAlgorithms);
            restoreProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY, previousDrainDelay);
            restoreProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, previousShutdownTimeout);
        }
    }

    private static void configureProxy(String runtimeUrl, long drainDelayMillis, long shutdownTimeoutMillis) {
        System.setProperty("FLUXZERO_PROXY_PORT", "0");
        System.setProperty("FLUXZERO_BASE_URL", runtimeUrl);
        System.setProperty(ForwardProxyConsumer.METRICS_ENABLED_PROPERTY, "false");
        System.setProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, NONE.name());
        System.setProperty(ProxyServer.DRAIN_DELAY_MILLIS_PROPERTY, Long.toString(drainDelayMillis));
        System.setProperty(ProxyServer.SHUTDOWN_TIMEOUT_MILLIS_PROPERTY, Long.toString(shutdownTimeoutMillis));
    }

    private static Fluxzero newRequester(String runtimeUrl) {
        WebSocketClient.ClientConfig requesterConfig = WebSocketClient.ClientConfig.builder()
                .name("proxy-lifecycle-test")
                .runtimeBaseUrl(runtimeUrl)
                .supportedCompressionAlgorithms(List.of(NONE))
                .disableMetrics(true)
                .build();
        return DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableTrackingMetrics()
                .disableShutdownHook()
                .disableKeepalive()
                .build(WebSocketClient.newInstance(requesterConfig));
    }

    private static void assertEventuallyReady(ProxyServer proxyServer, String readinessUrl) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!proxyServer.isReady() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(proxyServer.isReady(), "Proxy did not become ready after connecting to the tracking endpoint");
        assertEventuallyStatus(readinessUrl, 200, "Ready");
    }

    private static void assertEventuallyStatus(String url, int status, String body) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        AssertionError lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpClient httpClient = HttpClient.newBuilder().build();
                try {
                    var response = httpClient.send(
                            HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
                    assertEquals(status, response.statusCode());
                    assertEquals(body, response.body());
                    return;
                } finally {
                    httpClient.shutdownNow();
                }
            } catch (AssertionError e) {
                lastError = e;
            }
            Thread.sleep(10L);
        }
        throw lastError == null ? new AssertionError("No response from " + url) : lastError;
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

    private static void assertEventuallyNoThread(String namePrefix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (hasLiveThread(namePrefix) && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse(hasLiveThread(namePrefix), () -> "Thread with prefix " + namePrefix + " did not stop");
    }

    private static boolean hasLiveThread(String namePrefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(namePrefix));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
