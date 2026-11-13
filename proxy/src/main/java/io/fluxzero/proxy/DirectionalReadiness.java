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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.sdk.common.websocket.ConnectionMonitor;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.MessageType.WEBRESPONSE;

/** Local readiness of the two forwarding directions. Only transport state changes advance the loss timer. */
final class DirectionalReadiness {
    static final String ENABLED = "fluxzero.proxy.directional-readiness.enabled";
    static final String DISCONNECT_GRACE = "fluxzero.proxy.directional-readiness.disconnect-grace";
    static final String INGRESS_PATH = "/proxy/ready/ingress";
    static final String EGRESS_PATH = "/proxy/ready/egress";

    final Direction ingress;
    final Direction egress;

    DirectionalReadiness(long graceNanos, LongSupplier nanoTime) {
        ingress = new Direction(graceNanos, nanoTime);
        egress = new Direction(graceNanos, nanoTime);
    }

    static Duration configuredGrace(PropertySource properties) {
        if (!properties.getBoolean(ENABLED)) {
            return null;
        }
        String value = properties.get(DISCONNECT_GRACE, "PT30S");
        try {
            return validateGrace(Duration.parse(value));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException(DISCONNECT_GRACE + " must be a non-negative duration: " + value, e);
        }
    }

    static Duration validateGrace(Duration grace) {
        if (grace.isNegative()) {
            throw new IllegalArgumentException("Disconnect grace must be non-negative");
        }
        try {
            grace.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Disconnect grace is too long", e);
        }
        return grace;
    }

    static boolean usesPath(String path) {
        return INGRESS_PATH.equals(path) || EGRESS_PATH.equals(path);
    }

    Registration observe(WebSocketClient client) {
        Registration incoming = ingress.observe(client, WEBREQUEST, WEBRESPONSE);
        try {
            return incoming.merge(egress.observe(client, WEBRESPONSE, WEBREQUEST));
        } catch (RuntimeException | Error e) {
            incoming.cancel();
            throw e;
        }
    }

    Handler handler(Handler next, BooleanSupplier accepting, BooleanSupplier receivingResponses,
                    BooleanSupplier forwardingRequests) {
        return new Handler.Wrapper(next) {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                boolean ready;
                switch (Request.getPathInContext(request)) {
                    case INGRESS_PATH -> ready = accepting.getAsBoolean() && receivingResponses.getAsBoolean()
                                                 && ingress.isReady();
                    case EGRESS_PATH -> ready = accepting.getAsBoolean() && forwardingRequests.getAsBoolean()
                                                && egress.isReady();
                    default -> { return super.handle(request, response, callback); }
                }
                response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
                ProxyServer.writeReadiness(response, callback, ready);
                return true;
            }
        };
    }

    static final class Direction {
        private final long graceNanos;
        private final LongSupplier nanoTime;
        private ConnectionMonitor publisher, tracker;
        private boolean connected, previouslyConnected;
        private long disconnectedAt;

        private Direction(long graceNanos, LongSupplier nanoTime) {
            this.graceNanos = graceNanos;
            this.nanoTime = nanoTime;
        }

        synchronized Registration observe(WebSocketClient client, MessageType publishType, MessageType trackType) {
            publisher = client.monitorPublishing(publishType, null, ignored -> refresh());
            try {
                tracker = client.monitorTracking(trackType, null, ignored -> refresh());
                refresh();
                return publisher.merge(tracker);
            } catch (RuntimeException | Error e) {
                publisher.cancel();
                if (tracker != null) { tracker.cancel(); }
                throw e;
            }
        }

        // Independent listeners can arrive out of order. Reconcile the current pair before declaring recovery.
        private synchronized void refresh() {
            boolean available = tracker != null && publisher.isConnected() && tracker.isConnected();
            if (connected && !available) {
                disconnectedAt = nanoTime.getAsLong();
            }
            connected = available;
            previouslyConnected |= available;
        }

        synchronized boolean isReady() {
            return connected || previouslyConnected && nanoTime.getAsLong() - disconnectedAt < graceNanos;
        }
    }
}
