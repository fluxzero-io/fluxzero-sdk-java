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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.GetPosition;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.common.websocket.ReadinessTestConnector;
import io.fluxzero.sdk.common.websocket.WebsocketConnector;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.DefaultTracker;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.testserver.websocket.ConsumerEndpoint;
import io.fluxzero.testserver.websocket.JettyWebsocketRouter;
import io.fluxzero.testserver.websocket.ProducerEndpoint;
import io.fluxzero.testserver.websocket.ServerWebsocketSession;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.MessageType.WEBRESPONSE;
import static io.fluxzero.common.ServicePathBuilder.gatewayPath;
import static io.fluxzero.common.ServicePathBuilder.trackingPath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Isolated
@Timeout(25)
class ProxyDirectionalReadinessTest {
    private static final String ENABLED = "fluxzero.proxy.directional-readiness.enabled";
    private static final String GRACE = "fluxzero.proxy.directional-readiness.disconnect-grace";
    private static final String INGRESS = "/proxy/ready/ingress", EGRESS = "/proxy/ready/egress";
    private final WebsocketConnector previousConnector = AbstractWebsocketClient.defaultWebsocketConnector;
    private final ReadinessTestConnector connections = new ReadinessTestConnector(previousConnector);
    private final LinkedBlockingQueue<String> applicationTraffic = new LinkedBlockingQueue<>();
    private final InMemoryMessageStore requests = new InMemoryMessageStore(WEBREQUEST);
    private final InMemoryMessageStore responses = new InMemoryMessageStore(WEBRESPONSE);
    private final CountDownLatch extraTracking = new CountDownLatch(1);
    private final CountDownLatch responseTracking = new CountDownLatch(1);
    private final CountDownLatch forwardTracking = new CountDownLatch(1);
    private final AtomicReference<String> requestWriter = new AtomicReference<>();
    private final String previousEnabled = System.getProperty(ENABLED), previousGrace = System.getProperty(GRACE);
    private final HttpClient http = HttpClient.newHttpClient();
    private Server runtime;
    private WebSocketClient client;
    private ProxyServer proxy;

    private void start(int requestPublisherSlots, Duration grace) throws Exception {
        start(requestPublisherSlots, grace, true);
    }

    private void start(int requestPublisherSlots, Duration grace, boolean enabled) throws Exception {
        runtime = startRuntime(0);
        String url = "ws://127.0.0.1:" + ((ServerConnector) runtime.getConnectors()[0]).getLocalPort();
        System.setProperty(ENABLED, Boolean.toString(enabled));
        if (grace == null) {
            System.clearProperty(GRACE);
        } else {
            System.setProperty(GRACE, grace.toString());
        }
        AbstractWebsocketClient.defaultWebsocketConnector = connections;
        var tracking = WebSocketClient.TrackingClientConfig.builder().cacheSize(0).build();
        var config = WebSocketClient.ClientConfig.builder().runtimeBaseUrl(url).name("directional-readiness")
                .disableMetrics(true).pingDelay(Duration.ofMillis(50)).build()
                .withTrackingConfig(WEBREQUEST, tracking).withTrackingConfig(WEBRESPONSE, tracking)
                .withGatewaySessions(WEBREQUEST, requestPublisherSlots);
        client = WebSocketClient.newInstance(config);
        var proxyConfig = ProxyServerConfig.forRuntime(url).withMetricsEnabled(false);
        proxy = ProxyServer.start(proxyConfig, "127.0.0.1", client,
                                  DirectionalReadiness.configuredGrace(ApplicationProperties::getProperty));
    }

    private Server startRuntime(int port) throws Exception {
        var router = new JettyWebsocketRouter();
        for (var type : List.of(WEBREQUEST, WEBRESPONSE)) {
            var store = type == WEBREQUEST ? requests : responses;
            var producer = new ProducerEndpoint(store, type, null) {
                @Override
                public void onMessage(byte[] bytes, ServerWebsocketSession session) {
                    applicationTraffic.add(gatewayPath(type));
                    if (type == WEBREQUEST) {
                        requestWriter.set(getNegotiatedSessionId(session));
                    }
                    super.onMessage(bytes, session);
                }
            };
            var consumer = new ConsumerEndpoint(store, type) {
                @Override
                protected void dispatchRequest(ServerWebsocketSession session, JsonType request, long receivedAt) {
                    if (!(request instanceof RequestBatch<?>)) {
                        applicationTraffic.add(trackingPath(type) + " " + request.getClass().getSimpleName()
                                               + (request instanceof GetPosition position ? " " + position.getConsumer() : ""));
                    }
                    if (request instanceof Read read) {
                        if (type == WEBRESPONSE) {
                            responseTracking.countDown();
                        } else if ("second-forward-consumer".equals(read.getConsumer())) {
                            extraTracking.countDown();
                        } else if (ForwardProxyConsumer.defaultSettings.getConsumer().equals(read.getConsumer())) {
                            forwardTracking.countDown();
                        }
                    }
                    super.dispatchRequest(session, request, receivedAt);
                }
            };
            router.addRoute(gatewayPath(type), ignored -> producer).addRoute(trackingPath(type), ignored -> consumer);
        }
        // SDK tracker metrics use their own route even when the forwarder's metrics are disabled.
        var metrics = new ProducerEndpoint(new InMemoryMessageStore(METRICS));
        router.addRoute(gatewayPath(METRICS), ignored -> metrics);
        return router.start(new InetSocketAddress("127.0.0.1", port));
    }

    @AfterEach
    void stop() throws Exception {
        try {
            // Release completed test HTTP exchanges before asking Jetty to drain its listener.
            http.shutdownNow();
            if (proxy != null) {
                proxy.cancel();
            } else if (client != null) {
                client.shutDown();
            }
        } finally {
            AbstractWebsocketClient.defaultWebsocketConnector = previousConnector;
            restore(ENABLED, previousEnabled);
            restore(GRACE, previousGrace);
            if (runtime != null) {
                runtime.stop();
            }
        }
    }

    record Route(String path, String affected, String independent) {}

    static Stream<Route> requiredRoutes() {
        return Stream.of(new Route(gatewayPath(WEBREQUEST), INGRESS, EGRESS),
                         new Route(trackingPath(WEBREQUEST), EGRESS, INGRESS),
                         new Route(gatewayPath(WEBRESPONSE), EGRESS, INGRESS));
    }

    @ParameterizedTest
    @MethodSource("requiredRoutes")
    void onlyTheAffectedDirectionDropsAndIdleConnectionsRecover(Route route) throws Exception {
        start(1, Duration.ofMillis(200));
        checkRouteRecovery(route, () -> {});
    }

    @Test
    void restoresResponseTrackingAndCompletesAnAcceptedExchange() throws Exception {
        start(1, Duration.ofMillis(200));
        awaitStatus(INGRESS, 200);
        var stored = new CompletableFuture<SerializedMessage>();
        var capture = requests.registerMonitor(batch -> stored.complete(batch.getFirst()));
        try {
            var pending = send("/pending");
            var request = stored.get(4, TimeUnit.SECONDS);
            var response = WebResponse.builder().payload("completed".getBytes(UTF_8)).build()
                    .serialize(new ProxySerializer());
            response.setRequestId(request.getRequestId());
            response.setTarget(request.getSource());
            checkRouteRecovery(new Route(trackingPath(WEBRESPONSE), INGRESS, EGRESS), () -> {
                responses.append(List.of(response)).join();
                assertFalse(pending.isDone());
            });
            var result = pending.get(4, TimeUnit.SECONDS);
            assertEquals(200, result.statusCode());
            assertEquals("completed", result.body());
        } finally {
            capture.cancel();
        }
    }

    private void checkRouteRecovery(Route route, Runnable whileDisconnected) throws Exception {
        var first = connections.awaitConnection(route.path());
        awaitStatus(INGRESS, 200);
        awaitStatus(EGRESS, 200);
        connections.block(route.path());
        try {
            if (route.path().equals(trackingPath(WEBREQUEST))) {
                first.backpressure(true); // A real close must still count while pings are suspended.
            }
            first.session().close();
            awaitStatus(route.affected(), 503);
            assertEquals(200, status(route.independent()));
            assertEquals(200, status("/proxy/health"));
            whileDisconnected.run();
        } finally {
            connections.allow(route.path());
        }
        // No probe or new customer request triggers the replacement handshake.
        var replacement = connections.awaitConnection(route.path());
        assertNotSame(first.session(), replacement.session());
        awaitStatus(route.affected(), 200);
    }

    @Test
    void pausedPingsAndExtraConsumersDoNotCauseReadinessTrafficOrExpireAnIdleRoute() throws Exception {
        start(1, Duration.ofMillis(200));
        var session = connections.awaitConnection(trackingPath(WEBREQUEST));
        var extra = DefaultTracker.start(batch -> {}, WEBREQUEST,
                                         ConsumerConfiguration.builder().name("second-forward-consumer").build(), client);
        try {
            awaitStatus(INGRESS, 200);
            awaitStatus(EGRESS, 200);
            assertTrue(forwardTracking.await(2, TimeUnit.SECONDS), "Forward tracking did not start");
            assertTrue(extraTracking.await(2, TimeUnit.SECONDS), "Second consumer did not start");
            assertTrue(responseTracking.await(2, TimeUnit.SECONDS), "Response tracking did not start");
            awaitStatus("/proxy/ready", 200);
            // Successful startup must finish this worker, rather than leave periodic Runtime probes behind.
            var startupProbe = ReflectionUtils.<Thread>getFieldValue("readinessThread", proxy).orElseThrow();
            assertTrue(startupProbe.join(Duration.ofSeconds(2)), "Startup readiness probe did not finish");
            assertFalse(startupProbe.isAlive());
            applicationTraffic.clear(); // All ordinary long polls and the compatibility startup check have arrived.
            session.backpressure(true);
            try {
                for (int i = 0; i < 5; i++) {
                    assertEquals(200, status(INGRESS));
                    assertEquals(200, status(EGRESS));
                }
                assertEquals(200, status(EGRESS));
                assertEquals(200, status(INGRESS));
                assertTrue(session.session().isOpen());
                // Acknowledged requests fence both tracking routes after the HTTP probes. Only these explicit
                // barriers may add traffic; elapsed idle/grace behavior is covered with a fake clock separately.
                String barrier = "$readiness-test-barrier";
                client.getTrackingClient(WEBREQUEST).getPosition(barrier);
                client.getTrackingClient(WEBRESPONSE).getPosition(barrier);
                assertEquals(List.of(trackingPath(WEBREQUEST) + " GetPosition " + barrier,
                                     trackingPath(WEBRESPONSE) + " GetPosition " + barrier),
                             List.copyOf(applicationTraffic), "Readiness caused a Runtime request");
            } finally {
                session.backpressure(false);
            }
        } finally {
            extra.cancel();
        }
    }

    @Test
    void anOpenSiblingSocketCannotMaskLossOfTheSlotUsedForRequestAppends() throws Exception {
        start(2, Duration.ofMillis(200));
        var publisher = client.getGatewayClient(WEBREQUEST);
        // Unkeyed operations open both slots. Real appends select one of them by routing key.
        for (int i = 0; i < 2; i++) {
            publisher.setRetentionTime(Duration.ofHours(1), Guarantee.STORED).get(2, TimeUnit.SECONDS);
        }
        String route = gatewayPath(WEBREQUEST);
        var first = connections.awaitConnection(route);
        var second = connections.awaitConnection(route);
        publisher.append(Guarantee.STORED).get(2, TimeUnit.SECONDS);
        String writer = requestWriter.get();
        assertNotNull(writer);
        var peers = List.of(first, second);
        var pinned = peers.stream().filter(c -> writer.equals(c.id())).findFirst().orElseThrow();
        var sibling = pinned == first ? second : first;
        awaitStatus(INGRESS, 200);
        connections.block(route);
        try {
            pinned.session().close();
            awaitStatus(INGRESS, 503);
            assertTrue(sibling.session().isOpen());
            assertEquals(200, status(EGRESS));
        } finally {
            connections.allow(route);
        }
    }

    @Test
    void aShortReconnectStaysWithinTheGracePeriod() throws Exception {
        start(1, null); // Use the default disconnect grace.
        String route = trackingPath(WEBREQUEST);
        var first = connections.awaitConnection(route);
        awaitStatus(EGRESS, 200);
        connections.block(route);
        try {
            first.session().close();
            connections.awaitReconnectAttempt(route);
            assertEquals(200, status(EGRESS));
        } finally {
            connections.allow(route);
        }
        connections.awaitConnection(route);
        assertEquals(200, status(EGRESS));
    }

    @Test
    void disabledProbePathsForwardPendingRequestsAcrossARuntimeRestart() throws Exception {
        start(1, null, false);
        var storedRequests = new LinkedBlockingQueue<SerializedMessage>();
        var registration = requests.registerMonitor(storedRequests::addAll);
        String route = trackingPath(WEBRESPONSE);
        try {
            var ingress = send(INGRESS);
            var egress = send(EGRESS);
            var first = storedRequests.poll(4, TimeUnit.SECONDS);
            var second = storedRequests.poll(4, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(Set.of(INGRESS, EGRESS),
                         Set.of(WebRequest.getUrl(first.getMetadata()), WebRequest.getUrl(second.getMetadata())));
            assertTrue(responseTracking.await(4, TimeUnit.SECONDS));
            int port = ((ServerConnector) runtime.getConnectors()[0]).getLocalPort();
            connections.block(route);
            try {
                runtime.stop();
                assertTrue(runtime.isStopped());
                connections.awaitReconnectAttempt(route);
                assertFalse(ingress.isDone());
                assertFalse(egress.isDone());
                runtime = startRuntime(port); // Fresh endpoints, surviving request and response stores.
                for (var stored : List.of(first, second)) {
                    var path = WebRequest.getUrl(stored.getMetadata());
                    var response = WebResponse.builder().payload(path.getBytes(UTF_8)).build()
                            .serialize(new ProxySerializer());
                    response.setRequestId(stored.getRequestId());
                    response.setTarget(stored.getSource());
                    responses.append(List.of(response)).get(2, TimeUnit.SECONDS);
                }
                assertFalse(ingress.isDone());
                assertFalse(egress.isDone());
            } finally {
                connections.allow(route);
            }
            var ingressResponse = ingress.get(10, TimeUnit.SECONDS);
            var egressResponse = egress.get(10, TimeUnit.SECONDS);
            assertEquals(200, ingressResponse.statusCode());
            assertEquals(INGRESS, ingressResponse.body());
            assertEquals(200, egressResponse.statusCode());
            assertEquals(EGRESS, egressResponse.body());
            assertTrue(storedRequests.isEmpty(), "The proxy must not forward the HTTP request a second time");
        } finally {
            registration.cancel();
        }
    }

    private CompletableFuture<HttpResponse<String>> send(String path) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getPort() + path)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private int status(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getPort() + path))
                .timeout(Duration.ofSeconds(1)).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (HttpTimeoutException e) {
            return fail("Expected a local response for " + path + ". Runtime traffic: " + applicationTraffic, e);
        }
    }

    private void awaitStatus(String path, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        int actual = status(path);
        while (actual != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
            actual = status(path);
        }
        assertEquals(expected, actual, path);
    }

    private static void restore(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
