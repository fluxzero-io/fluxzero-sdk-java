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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.metrics.DisableMetrics;
import io.fluxzero.sdk.tracking.metrics.TrackerMonitor;
import io.fluxzero.sdk.web.HandleGet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.*;

class BatchConsumerFilterTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void arbitraryInterceptorCanRewritePayloadTypeBeforeHandling(boolean global) {
        var serializer = new JacksonSerializer();
        BatchInterceptor rewriting = (consumer, tracker) -> batch -> consumer.accept(batch.withMessages(
                batch.getMessages().stream().map(message -> message.withData(
                        serializer.serialize(new Accepted("mapped")))).toList()));
        var fixture = TestFixture.createAsync(configure(rewriting, global, false), new PayloadHandler());
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new Original("input")).expectResult("mapped").expectNoErrors();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mappingInterceptorCanRewriteWebRouteBeforeMatching(boolean global) {
        MappingBatchInterceptor rewriting = (batch, tracker) -> batch.withMessages(batch.getMessages().stream()
                .map(message -> message.withMetadata(message.getMetadata().with("url", "/accepted"))).toList());
        var fixture = TestFixture.createAsync(configure(rewriting, global, true), new WebHandler());
        try (var app = fixture.getFluxzero()) {
            fixture.whenGet("/original").expectResult("mapped").expectNoErrors();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void unknownInterceptorsKeepAdmissionBroadInBothFixtureModes(boolean async, boolean web) {
        // Sync fixtures retain local execution and do not execute batch interceptors. Their admission must still
        // describe the real consumer, so an unknown upstream transform cannot inherit a narrower handler filter.
        var builder = configure((consumer, tracker) -> consumer, true, web)
                .addHandlerDecorator(handler -> new Handler.DelegatingHandler<>(handler) {
                    @Override
                    public ConsumerFilter getConsumerFilter() {
                        return ConsumerFilter.select(null, 0, ConsumerFilter.VERSION, null,
                                List.of(), List.of(), List.of());
                    }

                    @Override
                    public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                        return delegate.getInvoker(message);
                    }
                }, web ? WEBREQUEST : COMMAND);
        Object handler = web ? new WebHandler() : new PayloadHandler();
        var fixture = async ? TestFixture.createAsync(builder, handler) : TestFixture.create(builder, handler);
        try (var app = fixture.getFluxzero()) {
            if (web) {
                fixture.whenGet("/accepted").expectResult("mapped").expectNoErrors();
            } else {
                fixture.whenCommand(new Accepted("mapped")).expectResult("mapped").expectNoErrors();
            }
        }
    }

    @Test
    void inverseProjectionFollowsExecutionOrderAfterSerializerProjection() {
        List<String> projectionOrder = new ArrayList<>();
        var serializer = new JacksonSerializer() {
            @Override
            public ConsumerFilter describeConsumerFilter(ConsumerFilter handlers) {
                projectionOrder.add("serializer");
                return selection("serialized");
            }
        };
        var outer = projecting("outer", "middle", "original", projectionOrder);
        var inner = projecting("inner", "serialized", "middle", projectionOrder);
        var chain = outer.andThen(inner);
        try (var blueprint = new ConsumerBlueprint(serializer, () -> selection("handler"),
                new AtomicLong()::incrementAndGet, chain::describeConsumerFilter)) {
            assertEquals(List.of("serializer", "inner", "outer"), projectionOrder);
            assertTrue(blueprint.mayHandle(message("original")));
            assertFalse(blueprint.mayHandle(message("serialized")));
        }
        assertFalse(BatchInterceptor.join(List.of((consumer, tracker) -> consumer))
                .describeConsumerFilter(selection("handler")).payloadRestricted());
        assertEquals(selection("handler"), BatchInterceptor.join(List.of())
                .describeConsumerFilter(selection("handler")));
    }

    @Test
    void observerOptInPreservesInvocationShutdownAndOrder() {
        var invoked = new AtomicBoolean();
        var stopped = new AtomicBoolean();
        @Order(-10)
        class Observer implements BatchInterceptor {
            @Override
            public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
                return batch -> {
                    invoked.set(true);
                    consumer.accept(batch);
                };
            }

            @Override
            public void shutdown(Tracker tracker) {
                stopped.set(true);
            }
        }
        var wrapper = BatchInterceptor.preservingConsumerFilter(new Observer());
        var downstream = selection("handler");
        assertSame(downstream, wrapper.describeConsumerFilter(downstream));
        assertEquals(-10, ClientUtils.orderOf(wrapper));
        wrapper.intercept(batch -> {}, null).accept(null);
        wrapper.shutdown(null);
        assertTrue(invoked.get());
        assertTrue(stopped.get());
    }

    @Test
    void builtinsPreserveSelectionAndCustomSubclassesStayUnknown() {
        var downstream = selection("handler");
        for (BatchInterceptor interceptor : List.of(new FluxzeroInterceptor(null), new TrackerMonitor(),
                                                     new DisableMetrics(), new StallingBatchInterceptor())) {
            assertSame(downstream, interceptor.describeConsumerFilter(downstream));
        }
        for (BatchInterceptor interceptor : List.of(new FluxzeroInterceptor(null) {}, new TrackerMonitor() {},
                                                     new DisableMetrics() {}, new StallingBatchInterceptor() {})) {
            assertFalse(interceptor.describeConsumerFilter(downstream).payloadRestricted());
        }
    }

    private static FluxzeroBuilder configure(BatchInterceptor interceptor, boolean global, boolean web) {
        var builder = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics();
        var type = web ? WEBREQUEST : COMMAND;
        return global ? builder.addBatchInterceptor(interceptor, type)
                : builder.addConsumerConfiguration(ConsumerConfiguration.builder().name("rewriting")
                        .batchInterceptor(interceptor).build(), type);
    }

    private static BatchInterceptor projecting(String name, String downstreamName, String inputName,
                                                List<String> order) {
        return new BatchInterceptor() {
            @Override
            public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
                return consumer;
            }

            @Override
            public ConsumerFilter describeConsumerFilter(ConsumerFilter downstream) {
                order.add(name);
                assertEquals(downstreamName, downstream.payloadTypes().getFirst().name());
                return selection(inputName);
            }
        };
    }

    private static ConsumerFilter selection(String type) {
        return ConsumerFilter.select(null, 0, ConsumerFilter.VERSION, Data.JSON_FORMAT,
                List.of(new ConsumerFilter.PayloadType(type, null)), null, List.of());
    }

    private static SerializedMessage message(String type) {
        return new SerializedMessage(new Data<>(new byte[0], type, 0, Data.JSON_FORMAT), Metadata.empty(), "input", 0L);
    }

    record Original(String value) {}
    record Accepted(String value) {}
    static class PayloadHandler {
        @HandleCommand String handle(Accepted input) { return input.value(); }
    }
    static class WebHandler {
        @HandleGet("/accepted") String handle() { return "mapped"; }
    }
}
