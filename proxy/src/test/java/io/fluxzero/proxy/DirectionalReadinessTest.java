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

import io.fluxzero.common.application.PropertySource;
import io.fluxzero.sdk.common.websocket.ConnectionMonitor;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.MessageType.WEBRESPONSE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectionalReadinessTest {
    private final AtomicLong clock = new AtomicLong();

    private static class Route implements ConnectionMonitor {
        boolean connected;
        Consumer<Boolean> listener;

        void signal(boolean value) {
            connected = value;
            listener.accept(value);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
        @Override
        public void cancel() {}
    }

    private final Route publisher = new Route(), tracker = new Route();

    private void observe(DirectionalReadiness.Direction direction, Route writer, Route reader) {
        var client = mock(WebSocketClient.class);
        when(client.monitorPublishing(eq(WEBREQUEST), isNull(), any())).thenAnswer(call -> {
            writer.listener = call.getArgument(2);
            return writer;
        });
        when(client.monitorTracking(eq(WEBRESPONSE), isNull(), any())).thenAnswer(call -> {
            reader.listener = call.getArgument(2);
            return reader;
        });
        direction.observe(client, WEBREQUEST, WEBRESPONSE);
    }

    @Test
    void continuousLossExpiresAtTheDefaultBoundaryEvenWhenDifferentRoutesAreDown() {
        var grace = DirectionalReadiness.configuredGrace(Map.of(DirectionalReadiness.ENABLED, "true")::get);
        var direction = new DirectionalReadiness(grace.toNanos(), clock::get).ingress;
        observe(direction, publisher, tracker);
        publisher.signal(true);
        assertFalse(direction.isReady());
        tracker.signal(true);
        assertTrue(direction.isReady());
        publisher.signal(false);
        clock.set(Duration.ofSeconds(30).toNanos() - 1);
        tracker.signal(false);
        publisher.signal(true);
        assertTrue(direction.isReady());
        clock.incrementAndGet();
        assertFalse(direction.isReady());
        tracker.signal(true);
        assertTrue(direction.isReady());
    }

    @Test
    void connectedIdleRoutesNeverExpireWithoutATransportLoss() {
        var direction = configured("PT0.2S").ingress;
        observe(direction, publisher, tracker);
        publisher.signal(true);
        tracker.signal(true);
        assertTrue(direction.isReady());

        // No ping, request, response or transport callback refreshes readiness while time advances.
        for (Duration elapsed : new Duration[]{Duration.ofMillis(200), Duration.ofMillis(5500), Duration.ofDays(1)}) {
            clock.set(elapsed.toNanos());
            assertTrue(direction.isReady(), "Connected idle routes must not expire after " + elapsed);
        }
    }

    @Test
    void shortReconnectStartsANewGracePeriodAtTheNextDisconnect() {
        var direction = configured("PT45S").egress;
        observe(direction, publisher, tracker);
        publisher.signal(true);
        tracker.signal(true);
        tracker.signal(false);
        clock.set(Duration.ofSeconds(44).toNanos());
        tracker.signal(true);
        tracker.signal(false);
        clock.set(Duration.ofSeconds(88).toNanos());
        assertTrue(direction.isReady());
        clock.set(Duration.ofSeconds(89).toNanos());
        assertFalse(direction.isReady());
    }

    @Test
    void lateTrackerCloseCannotRestartGraceDuringAnOverlappingOutage() {
        var direction = configured("PT30S").ingress;
        observe(direction, publisher, tracker);
        tracker.signal(true);
        publisher.signal(true);
        publisher.signal(false);
        clock.set(Duration.ofSeconds(31).toNanos());
        assertFalse(direction.isReady());

        tracker.connected = false; // Its close notification is still waiting for delivery.
        publisher.signal(true);
        assertFalse(direction.isReady());
        tracker.listener.accept(false);
        assertFalse(direction.isReady());
        tracker.signal(true);
        assertTrue(direction.isReady());
    }

    @Test
    void embeddedGraceIsValidatedBeforeStartingResources() {
        var config = ProxyServerConfig.forRuntime("ws://localhost");
        assertThrows(IllegalArgumentException.class, () -> ProxyServer.start(config, "127.0.0.1", Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> ProxyServer.start(config, "127.0.0.1", Duration.ofDays(200_000)));
    }

    @Test
    void disabledByDefault() {
        assertNull(DirectionalReadiness.configuredGrace(key -> null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT-1S", "thirty", "P999999999D"})
    void rejectsInvalidGrace(String value) {
        var error = assertThrows(IllegalArgumentException.class, () -> configured(value));
        assertTrue(error.getMessage().contains(DirectionalReadiness.DISCONNECT_GRACE));
    }

    @Test
    void httpStatusRequiresTheCorrespondingWorkerAndDrainingWinsOverRecovery() throws Exception {
        var readiness = configured("PT0S");
        var accepting = new AtomicBoolean(true);
        var receiving = new AtomicBoolean(true);
        var forwarding = new AtomicBoolean(true);
        Server server = new Server();
        var http = new LocalConnector(server);
        server.addConnector(http);
        server.setHandler(readiness.handler(new DefaultHandler(), accepting::get, receiving::get, forwarding::get));
        try {
            server.start();
            var egressPublisher = new Route();
            var egressTracker = new Route();
            observe(readiness.ingress, publisher, tracker);
            observe(readiness.egress, egressPublisher, egressTracker);
            publisher.signal(true);
            tracker.signal(true);
            egressPublisher.signal(true);
            egressTracker.signal(true);
            tracker.signal(false);
            assertEquals(503, status(http, DirectionalReadiness.INGRESS_PATH));
            assertEquals(200, status(http, DirectionalReadiness.EGRESS_PATH));
            tracker.signal(true);
            forwarding.set(false);
            assertEquals(200, status(http, DirectionalReadiness.INGRESS_PATH));
            assertEquals(503, status(http, DirectionalReadiness.EGRESS_PATH));
            receiving.set(false);
            assertEquals(503, status(http, DirectionalReadiness.INGRESS_PATH));
            accepting.set(false);
            receiving.set(true);
            forwarding.set(true);
            egressPublisher.signal(false);
            egressPublisher.signal(true);
            assertEquals(503, status(http, DirectionalReadiness.INGRESS_PATH));
            assertEquals(503, status(http, DirectionalReadiness.EGRESS_PATH));
            assertEquals(404, status(http, "/another-path"));
        } finally {
            server.stop();
        }
    }

    private DirectionalReadiness configured(String value) {
        PropertySource properties = Map.of(DirectionalReadiness.ENABLED, "true",
                                           DirectionalReadiness.DISCONNECT_GRACE, value)::get;
        return new DirectionalReadiness(DirectionalReadiness.configuredGrace(properties).toNanos(), clock::get);
    }

    private static int status(LocalConnector http, String path) throws Exception {
        var response = HttpTester.parseResponse(http.getResponse("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        if (path.equals(DirectionalReadiness.INGRESS_PATH) || path.equals(DirectionalReadiness.EGRESS_PATH)) {
            assertEquals("no-store", response.get(HttpHeader.CACHE_CONTROL), path);
        }
        return response.getStatus();
    }
}
