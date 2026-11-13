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

import static org.junit.jupiter.api.Assertions.*;

class DirectionalReadinessTest {
    private final AtomicLong clock = new AtomicLong();

    @Test
    void continuousLossExpiresAtTheDefaultBoundaryEvenWhenDifferentRoutesAreDown() {
        var direction = DirectionalReadiness.configured(Map.of(DirectionalReadiness.ENABLED, "true")::get,
                                                       clock::get).ingress;
        direction.publisherConnected(true);
        assertFalse(direction.isReady());
        direction.trackerConnected(true);
        assertTrue(direction.isReady());
        direction.publisherConnected(false);
        clock.set(Duration.ofSeconds(30).toNanos() - 1);
        direction.trackerConnected(false);
        direction.publisherConnected(true);
        assertTrue(direction.isReady());
        clock.incrementAndGet();
        assertFalse(direction.isReady());
        direction.trackerConnected(true);
        assertTrue(direction.isReady());
    }

    @Test
    void shortReconnectStartsANewGracePeriodAtTheNextDisconnect() {
        var direction = configured("PT45S").egress;
        direction.publisherConnected(true);
        direction.trackerConnected(true);
        direction.trackerConnected(false);
        clock.set(Duration.ofSeconds(44).toNanos());
        direction.trackerConnected(true);
        direction.trackerConnected(false);
        clock.set(Duration.ofSeconds(88).toNanos());
        assertTrue(direction.isReady());
        clock.set(Duration.ofSeconds(89).toNanos());
        assertFalse(direction.isReady());
    }

    @Test
    void disabledByDefault() {
        assertNull(DirectionalReadiness.configured(key -> null, clock::get));
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
            for (var direction : new DirectionalReadiness.Direction[]{readiness.ingress, readiness.egress}) {
                direction.publisherConnected(true);
                direction.trackerConnected(true);
            }
            readiness.ingress.trackerConnected(false);
            assertEquals(503, status(http, DirectionalReadiness.INGRESS_PATH));
            assertEquals(200, status(http, DirectionalReadiness.EGRESS_PATH));
            readiness.ingress.trackerConnected(true);
            forwarding.set(false);
            assertEquals(200, status(http, DirectionalReadiness.INGRESS_PATH));
            assertEquals(503, status(http, DirectionalReadiness.EGRESS_PATH));
            receiving.set(false);
            assertEquals(503, status(http, DirectionalReadiness.INGRESS_PATH));
            accepting.set(false);
            receiving.set(true);
            forwarding.set(true);
            readiness.egress.publisherConnected(false);
            readiness.egress.publisherConnected(true);
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
        return DirectionalReadiness.configured(properties, clock::get);
    }

    private static int status(LocalConnector http, String path) throws Exception {
        return HttpTester.parseResponse(http.getResponse("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n"))
                .getStatus();
    }
}
