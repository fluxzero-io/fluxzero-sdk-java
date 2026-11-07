/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GraphEnvelopeContextTest {
    @Model record Order(@EntityId OrderId orderId) {}
    static class OrderId extends Id<Order> { public OrderId(String id){super(id);} }
    @Model record Profile(@EntityId ProfileId profileId) {}
    static class ProfileId extends Id<Profile> { public ProfileId(String id){super(id);} }
    record CreateOrder(OrderId orderId) { @Apply Order apply(){return new Order(orderId);} }
    record CreateProfile(ProfileId profileId) { @Apply Profile apply(){return new Profile(profileId);} }

    static class Observed { final AtomicInteger calls=new AtomicInteger(); }
    static class GraphOnly extends Observed {
        @HandleEvent void on(Graph<Order> order) { calls.incrementAndGet(); }
    }
    static class LogicalMessageContext extends Observed {
        @HandleEvent void on(Graph<Order> order, Message message) { calls.incrementAndGet(); }
    }
    static class EnvelopeContext extends Observed {
        @HandleEvent void on(Graph<Order> order, DeserializingMessage message) { calls.incrementAndGet(); }
    }
    static class EnvelopeFirst extends Observed {
        @HandleEvent void on(DeserializingMessage message, Graph<Order> order) { calls.incrementAndGet(); }
    }
    void unrelatedProfileMustNotReachOrderHandler(Observed handler) {
        TestFixture.createAsync(handler)
            .whenCommand(new CreateProfile(new ProfileId("profile")))
            .expectNoErrors().expectThat(f->assertEquals(0,handler.calls.get()));
    }
    void matchingOrderReachesHandler(Observed handler) {
        TestFixture.createAsync(handler).whenCommand(new CreateOrder(new OrderId("order")))
            .expectNoErrors().expectThat(f->assertEquals(1,handler.calls.get()));
    }
    @Test void graphOnlyReceivesMatchingOrder(){matchingOrderReachesHandler(new GraphOnly());}
    @Test void messageContextReceivesMatchingOrder(){matchingOrderReachesHandler(new LogicalMessageContext());}
    @Test void envelopeContextReceivesMatchingOrder(){matchingOrderReachesHandler(new EnvelopeContext());}
    @Test void envelopeFirstReceivesMatchingOrder(){matchingOrderReachesHandler(new EnvelopeFirst());}
    @Test void graphOnlyIgnoresUnrelatedProfile(){unrelatedProfileMustNotReachOrderHandler(new GraphOnly());}
    @Test void messageContextIgnoresUnrelatedProfile(){unrelatedProfileMustNotReachOrderHandler(new LogicalMessageContext());}
    @Test void envelopeContextMustIgnoreUnrelatedProfile(){unrelatedProfileMustNotReachOrderHandler(new EnvelopeContext());}
    @Test void envelopeFirstMustIgnoreUnrelatedProfile(){unrelatedProfileMustNotReachOrderHandler(new EnvelopeFirst());}

    record Token(String value) {}
    static class DynamicContext extends Observed {
        @HandleEvent @HandleNotification
        void on(Graph<Order> graph, Token token) { assertEquals("context", token.value()); calls.incrementAndGet(); }
    }
    static class DynamicFirst extends Observed {
        @HandleEvent @HandleNotification
        void on(Token token, Graph<Order> graph) { assertEquals("context", token.value()); calls.incrementAndGet(); }
    }
    static class RawLast extends Observed {
        @HandleEvent @HandleNotification
        void on(Graph<Order> graph, SerializedMessage context) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class RawFirst extends Observed {
        @HandleEvent @HandleNotification
        void on(SerializedMessage context, Graph<Order> graph) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class TimestampLast extends Observed {
        @HandleEvent @HandleNotification
        void on(Graph<Order> graph, Instant context) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class TimestampFirst extends Observed {
        @HandleEvent @HandleNotification
        void on(Instant context, Graph<Order> graph) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class ClockLast extends Observed {
        @HandleEvent @HandleNotification
        void on(Graph<Order> graph, Clock context) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class ClockFirst extends Observed {
        @HandleEvent @HandleNotification
        void on(Clock context, Graph<Order> graph) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class EnvelopeBothLast extends Observed {
        @HandleEvent @HandleNotification
        void on(Graph<Order> graph, DeserializingMessage context) { assertNotNull(context); calls.incrementAndGet(); }
    }
    static class EnvelopeBothFirst extends Observed {
        @HandleEvent @HandleNotification
        void on(DeserializingMessage context, Graph<Order> graph) { assertNotNull(context); calls.incrementAndGet(); }
    }

    static Stream<Arguments> contexts() {
        return Stream.of(DynamicContext.class, DynamicFirst.class, RawFirst.class, RawLast.class,
                TimestampFirst.class, TimestampLast.class, ClockFirst.class, ClockLast.class,
                EnvelopeBothFirst.class, EnvelopeBothLast.class)
                .flatMap(type -> Stream.of(false, true).map(async ->
                        Arguments.of(type, async)));
    }

    @ParameterizedTest @MethodSource("contexts")
    void contextDoesNotBroadenEventsOrNotifications(Class<? extends Observed> type, boolean async) throws Exception {
        Observed handler = type.getDeclaredConstructor().newInstance();
        var resolver = new ParameterResolver<DeserializingMessage>() {
            @Override public boolean isContextParameter(Parameter parameter) { return true; }
            @Override public Function<DeserializingMessage,Object> resolve(
                    Parameter p, Annotation a) {
                return p.getType() == Token.class ? ignored -> new Token("context") : null;
            }
            @Override public boolean matches(Parameter p, Annotation a,
                                             DeserializingMessage message) {
                return p.getType() == Token.class && !message.getMetadata().containsKey("denyContext");
            }
        };
        var fixture = TestFixture.create(DefaultFluxzero.builder()
                .addParameterResolver(resolver).addHandlerDecorator(opaqueDecorator()));
        if (async) fixture = fixture.async();
        fixture.registerHandlers(handler)
                .whenCommand(new CreateProfile(new ProfileId("unrelated"))).expectNoErrors()
                .expectThat(f -> assertEquals(0, handler.calls.get()))
                .andThen().whenCommand(new CreateOrder(new OrderId("matching"))).expectNoErrors()
                .expectThat(f -> assertEquals(2, handler.calls.get()));
        if (type == DynamicContext.class || type == DynamicFirst.class) {
            fixture.whenCommand(new Message(new CreateOrder(new OrderId("denied")),
                    Metadata.of("denyContext", true)))
                    .expectNoErrors().expectThat(f -> assertEquals(2, handler.calls.get()));
        }
    }
    @Test
    void jsonPayloadKeepsPriorityOverGraphContext() {
        class Observer {
            int payloadCalls;
            int contextCalls;
            @HandleEvent(allowedClasses = CreateOrder.class)
            void payload(Graph<Order> graph, JsonNode payload, Metadata metadata) {
                assertNotNull(payload.get("orderId"));
                payloadCalls++;
            }
            @HandleEvent
            void context(Graph<Order> graph, DeserializingMessage context) { contextCalls++; }
        }
        Observer handler = new Observer();
        TestFixture.createAsync(handler)
                .whenCommand(new CreateOrder(new OrderId("json")))
                .expectNoErrors().expectThat(f -> {
                    assertEquals(1, handler.payloadCalls);
                    assertEquals(0, handler.contextCalls);
                });
    }

    private static HandlerDecorator opaqueDecorator() {
        return handler -> new Handler<>() {
            @Override public Class<?> getTargetClass() { return handler.getTargetClass(); }
            @Override public Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return handler.getInvoker(message).map(invoker ->
                        new HandlerInvoker.DelegatingHandlerInvoker(invoker) {
                            // Simulates existing decorators that do not know the new optional role metadata.
                            @Override public Set<Parameter> getContextParameters() {
                                return null;
                            }
                            @Override public Object invoke(BiFunction<Object,Object,Object> combiner) {
                                return delegate.invoke(combiner);
                            }
                        });
            }
        };
    }

}
