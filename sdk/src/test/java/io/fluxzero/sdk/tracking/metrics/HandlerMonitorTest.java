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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.MetricsGateway;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void consumerNamespaceDoesNotChangeApplicationMetricsGateway() throws Exception {
        Fluxzero fluxzero = mock(Fluxzero.class);
        MetricsGateway applicationGateway = mock(MetricsGateway.class);
        when(fluxzero.metricsGateway()).thenReturn(applicationGateway);
        HandlerInvoker invoker = HandlerInvoker.noOp(
                SelfHandler.class, SelfHandler.class.getDeclaredMethod("handle"));
        DeserializingMessage message = message("external").putContext(
                ConsumerConfiguration.class, ConsumerConfiguration.builder()
                        .name("customer-consumer").namespace("customer").build());
        Fluxzero previous = Fluxzero.instance.get();
        try {
            Fluxzero.instance.set(fluxzero);
            new HandlerMonitor().prepare(invoker).interceptHandling(ignored -> null, invoker).apply(message);
        } finally {
            if (previous == null) {
                Fluxzero.instance.remove();
            } else {
                Fluxzero.instance.set(previous);
            }
        }

        verify(applicationGateway, never()).forNamespace(anyString());
    }

    @Test
    void asyncCompletionRestoresRequestContext() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        MetricsGateway applicationGateway = mock(MetricsGateway.class);
        when(fluxzero.metricsGateway()).thenReturn(applicationGateway);
        CompletableFuture<Void> handlerResult = new CompletableFuture<>();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new AsyncHandler(handlerResult), HandleEvent.class, List.of());
        Handler<DeserializingMessage> wrapped = new HandlerMonitor().wrap(handler);
        DeserializingMessage message = message("external");

        Fluxzero.instance.set(fluxzero);
        try {
            wrapped.getHandlerMethodOrNull(message).invoke(message);
        } finally {
            Fluxzero.instance.remove();
        }

        handlerResult.complete(null);

        verify(applicationGateway).publish(isA(CompleteMessageEvent.class), isA(Metadata.class));
        assertNull(Fluxzero.instance.get());
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

    private record AsyncHandler(CompletableFuture<Void> result) {
        @HandleEvent
        CompletionStage<Void> handle() {
            return result;
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
