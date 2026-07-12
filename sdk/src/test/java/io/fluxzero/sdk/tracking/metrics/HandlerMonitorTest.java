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
 */

package io.fluxzero.sdk.tracking.metrics;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HandlerMonitorTest {

    @Test
    void preparedPolicyKeepsSelfHandlerDecisionMessageDependent() throws Exception {
        CapturingHandlerMonitor monitor = new CapturingHandlerMonitor();
        HandlerInvoker invoker = HandlerInvoker.noOp(
                SelfHandler.class, SelfHandler.class.getDeclaredMethod("handle"));
        var prepared = monitor.prepare(invoker);

        DeserializingMessage selfMessage = message(new SelfHandler());
        DeserializingMessage externalMessage = message("external");
        prepared.interceptHandling(ignored -> null, invoker).apply(selfMessage);
        prepared.interceptHandling(ignored -> null, invoker).apply(externalMessage);

        assertEquals(1, monitor.publishedMetrics);
    }

    @Test
    void preparedMonitorPreservesReusableHandlerMethod() {
        HandlerMonitor monitor = new HandlerMonitor();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new SelfHandler(), HandleEvent.class, List.of());
        Handler<DeserializingMessage> wrapped = monitor.wrap(handler);
        DeserializingMessage message = message("external");

        var method = wrapped.getHandlerMethodOrNull(message);

        assertNotNull(method);
        method.invoke(message);
    }

    @Test
    void subclassWithLegacyMetricsHookUsesInvokerPath() {
        CapturingHandlerMonitor monitor = new CapturingHandlerMonitor();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new SelfHandler(), HandleEvent.class, List.of());
        Handler<DeserializingMessage> wrapped = monitor.wrap(handler);
        DeserializingMessage message = message("external");

        var method = wrapped.getHandlerMethodOrNull(message);
        var invoker = wrapped.getInvokerOrNull(message);

        assertNull(method);
        assertNotNull(invoker);
        invoker.invoke();
        assertEquals(1, monitor.publishedMetrics);
    }

    @Test
    void preparedPolicyLogsExplicitlySelfTrackingHandler() throws Exception {
        CapturingHandlerMonitor monitor = new CapturingHandlerMonitor();
        HandlerInvoker invoker = HandlerInvoker.noOp(
                TrackedSelfHandler.class, TrackedSelfHandler.class.getDeclaredMethod("handle"));

        monitor.prepare(invoker).interceptHandling(ignored -> null, invoker)
                .apply(message(new TrackedSelfHandler()));

        assertEquals(1, monitor.publishedMetrics);
    }

    @Test
    void explicitLocalMetricsKeepPayloadHandlerOnMetricsPath() throws Exception {
        HandlerMonitor monitor = new HandlerMonitor();
        ExplicitlyMonitoredSelfHandler payload = new ExplicitlyMonitoredSelfHandler();
        HandlerInvoker invoker = HandlerInvoker.noOp(
                ExplicitlyMonitoredSelfHandler.class,
                ExplicitlyMonitoredSelfHandler.class.getDeclaredMethod("handle"));
        HandlerInput<DeserializingMessage> input = new HandlerInput<>() {
            @Override
            public Object getPayload() {
                return payload;
            }

            @Override
            public DeserializingMessage getMessage() {
                return message(payload);
            }
        };

        assertNull(monitor.prepareInput(invoker, input));
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(new Message(payload), MessageType.EVENT, null);
    }

    private static class CapturingHandlerMonitor extends HandlerMonitor {
        private int publishedMetrics;

        @Override
        protected void publishMetrics(HandlerInvoker invoker, DeserializingMessage message,
                                      boolean exceptionalResult, Instant start, Object result) {
            publishedMetrics++;
        }

    }

    private static class SelfHandler {
        @HandleEvent
        void handle() {
        }
    }

    @LocalHandler(logMetrics = true)
    private static class ExplicitlyMonitoredSelfHandler {
        @HandleEvent
        void handle() {
        }
    }

    @TrackSelf
    private static class TrackedSelfHandler {
        @HandleEvent
        void handle() {
        }
    }
}
