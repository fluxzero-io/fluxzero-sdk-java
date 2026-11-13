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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Local readiness of the two forwarding directions. Only transport state changes advance the loss timer. */
final class DirectionalReadiness {
    static final String ENABLED = "fluxzero.proxy.directional-readiness.enabled";
    static final String DISCONNECT_GRACE = "fluxzero.proxy.directional-readiness.disconnect-grace";
    static final String INGRESS_PATH = "/proxy/ready/ingress";
    static final String EGRESS_PATH = "/proxy/ready/egress";

    final Direction ingress;
    final Direction egress;

    private DirectionalReadiness(long graceNanos, LongSupplier nanoTime) {
        ingress = new Direction(graceNanos, nanoTime);
        egress = new Direction(graceNanos, nanoTime);
    }

    static DirectionalReadiness configured(PropertySource properties, LongSupplier nanoTime) {
        if (!properties.getBoolean(ENABLED)) {
            return null;
        }
        String value = properties.get(DISCONNECT_GRACE, "PT30S");
        try {
            long grace = Duration.parse(value).toNanos();
            if (grace >= 0) {
                return new DirectionalReadiness(grace, nanoTime);
            }
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException(DISCONNECT_GRACE + " must be a non-negative duration: " + value, e);
        }
        throw new IllegalArgumentException(DISCONNECT_GRACE + " must be a non-negative duration: " + value);
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
                ProxyServer.writeReadiness(response, callback, ready);
                return true;
            }
        };
    }

    static final class Direction {
        private final long graceNanos;
        private final LongSupplier nanoTime;
        private boolean publisher, tracker, previouslyConnected;
        private long disconnectedAt;

        private Direction(long graceNanos, LongSupplier nanoTime) {
            this.graceNanos = graceNanos;
            this.nanoTime = nanoTime;
        }

        synchronized void publisherConnected(boolean connected) {
            update(connected, tracker);
        }

        synchronized void trackerConnected(boolean connected) {
            update(publisher, connected);
        }

        private void update(boolean publishing, boolean tracking) {
            if (publishing && tracking) {
                previouslyConnected = true;
            } else if (publisher && tracker) {
                disconnectedAt = nanoTime.getAsLong();
            }
            publisher = publishing;
            tracker = tracking;
        }

        synchronized boolean isReady() {
            return publisher && tracker || previouslyConnected && nanoTime.getAsLong() - disconnectedAt < graceNanos;
        }
    }
}
