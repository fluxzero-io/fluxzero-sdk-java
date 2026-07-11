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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HandlerInterceptorTest {

    @Test
    void modifyResult() {
        TestFixture.create(DefaultFluxzero.builder().addHandlerInterceptor(
                (f, i) -> m -> f.apply(m) + "bar", COMMAND), MockCommandHandler.class)
                .whenCommand("foo").expectEvents("foo").expectResult("foobar").expectNoErrors();
    }

    @Test
    void blockCommand() {
        TestFixture.create(DefaultFluxzero.builder().addHandlerInterceptor(
                        (f, i) -> m -> null, COMMAND), MockCommandHandler.class)
                .whenCommand("foo").expectNoResult().expectNoEvents().expectNoErrors();
    }

    @Test
    void throwException() {
        TestFixture.create(DefaultFluxzero.builder().addHandlerInterceptor(
                        (f, i) -> m -> { throw new MockException(); }, COMMAND), MockCommandHandler.class)
                .whenCommand("foo").expectExceptionalResult(MockException.class).expectNoEvents();
    }

    @Test
    void changePayload() {
        TestFixture.create(DefaultFluxzero.builder().addHandlerInterceptor(
                        (f, i) -> m -> f.apply(m.withPayload("foobar")), COMMAND), MockCommandHandler.class)
                .whenCommand("foo").expectEvents("foobar").expectResult("foobar").expectNoErrors();
    }

    @Test
    void changePayloadTypeNotSupported() {
        TestFixture.create(DefaultFluxzero.builder().addHandlerInterceptor(
                        (f, i) -> m -> f.apply(new DeserializingMessage(
                                m.toMessage().withPayload(123), COMMAND, new JacksonSerializer())),
                        COMMAND), MockCommandHandler.class)
                .whenCommand("foo").expectExceptionalResult(UnsupportedOperationException.class);
    }

    @Test
    void ordersCustomInterceptorsUsingOrderAnnotation() {
        List<String> invocationOrder = new ArrayList<>();

        TestFixture.create(DefaultFluxzero.builder()
                                .addHandlerInterceptor(new PositiveHandlerInterceptor(invocationOrder), COMMAND)
                                .addHandlerInterceptor(new HigherPriorityHandlerInterceptor(invocationOrder), COMMAND),
                        MockCommandHandler.class)
                .whenCommand("foo")
                .expectEvents("foo")
                .expectResult("foo")
                .expectNoErrors();

        assertEquals(List.of("negative", "positive"), invocationOrder);
    }

    @Test
    void combinesDefaultWrappersWithoutChangingInterceptorOrder() {
        List<String> invocationOrder = new ArrayList<>();
        HandlerInterceptor outer = new RecordingPreparedInterceptor("outer", invocationOrder);
        HandlerInterceptor inner = new RecordingPreparedInterceptor("inner", invocationOrder);
        Handler<DeserializingMessage> handler = handler(new PlainHandler());

        Handler<DeserializingMessage> wrapped = outer.wrap(inner.wrap(handler));

        HandlerInterceptor.PreparedInterceptedHandler intercepted = assertInstanceOf(
                HandlerInterceptor.PreparedInterceptedHandler.class, wrapped);
        assertEquals(2, intercepted.interceptorCount());
        assertEquals("handled:input", wrapped.getInvokerOrNull(message("input")).invoke());
        assertEquals(List.of("outer-before", "inner-before", "inner-after", "outer-after"), invocationOrder);
    }

    @Test
    void keepsLegacyCustomInterceptorPositionBetweenPreparedWrappers() {
        List<String> invocationOrder = new ArrayList<>();
        HandlerInterceptor standardOuter = new RecordingPreparedInterceptor("standard-outer", invocationOrder);
        HandlerInterceptor custom = (function, invoker) -> message -> {
            invocationOrder.add("custom-before");
            Object result = function.apply(message);
            invocationOrder.add("custom-after");
            return result;
        };
        HandlerInterceptor standardInner = new RecordingPreparedInterceptor("standard-inner", invocationOrder);
        Handler<DeserializingMessage> wrapped = standardOuter.wrap(
                custom.wrap(standardInner.wrap(handler(new PlainHandler()))));

        assertEquals("handled:input", wrapped.getInvokerOrNull(message("input")).invoke());
        assertEquals(List.of("standard-outer-before", "custom-before", "standard-inner-before",
                             "standard-inner-after", "custom-after", "standard-outer-after"), invocationOrder);
    }

    @Test
    void preparesMethodStaticPolicyOnceForDynamicInvokerPath() {
        CountingPreparedInterceptor interceptor = new CountingPreparedInterceptor();
        Handler<DeserializingMessage> wrapped = interceptor.wrap(handler(new PlainHandler()));

        assertEquals("handled:first", wrapped.getInvokerOrNull(message("first")).invoke());
        assertEquals("handled:second", wrapped.getInvokerOrNull(message("second")).invoke());

        assertEquals(1, interceptor.prepareCount.get());
    }

    @Test
    void preparedInterceptorPreservesReusableHandlerMethod() {
        CountingPreparedInterceptor interceptor = new CountingPreparedInterceptor();
        Handler<DeserializingMessage> wrapped = interceptor.wrap(handler(new NoArgumentHandler()));

        var first = wrapped.getHandlerMethodOrNull(message("first"));
        var second = wrapped.getHandlerMethodOrNull(message("second"));

        assertNotNull(first);
        assertSame(first, second);
        assertEquals("handled", first.invoke(message("first")));
        assertEquals(1, interceptor.prepareCount.get());
    }

    @Test
    void legacyInterceptorKeepsReusableHandlerMethodDisabled() {
        HandlerInterceptor interceptor = (function, invoker) -> function;
        Handler<DeserializingMessage> wrapped = interceptor.wrap(handler(new NoArgumentHandler()));

        assertNull(wrapped.getHandlerMethodOrNull(message("input")));
    }

    @Test
    void subclassOverridingOnlyLegacyContractKeepsLegacyWrapper() {
        LegacyOverridePreparedInterceptor interceptor = new LegacyOverridePreparedInterceptor();
        Handler<DeserializingMessage> wrapped = interceptor.wrap(handler(new PlainHandler()));

        assertInstanceOf(HandlerInterceptor.InterceptedHandler.class, wrapped);
        assertEquals("legacy:handled:input", wrapped.getInvokerOrNull(message("input")).invoke());
        assertEquals(1, interceptor.legacyInvocationCount.get());
        assertEquals(0, interceptor.prepareCount.get());
    }

    @Test
    void transformedMessageRepreparesRemainingInterceptorsForSelectedMethod() {
        List<Class<?>> selectedParameterTypes = new ArrayList<>();
        HandlerInterceptor prepared = new HandlerInterceptor() {
            @Override
            public Function<DeserializingMessage, Object> interceptHandling(
                    Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
                return function;
            }

            @Override
            public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
                Class<?> parameterType = handler.getMethod().getParameterTypes()[0];
                return (message, descriptor, combiner, next) -> {
                    selectedParameterTypes.add(parameterType);
                    return next.apply(message, descriptor, combiner);
                };
            }

            @Override
            public boolean supportsPreparation() {
                return true;
            }
        };
        HandlerInterceptor transforming = new HandlerInterceptor() {
            @Override
            public Function<DeserializingMessage, Object> interceptHandling(
                    Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
                return m -> function.apply(m.withPayload(123));
            }

            @Override
            public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
                return (message, descriptor, combiner, next) -> next.apply(
                        message, message.withPayload(123), descriptor, combiner);
            }

            @Override
            public boolean supportsPreparation() {
                return true;
            }
        };
        Handler<DeserializingMessage> wrapped = transforming.wrap(prepared.wrap(handler(new OverloadedHandler())));

        assertEquals("integer:123", wrapped.getInvokerOrNull(message("input")).invoke());
        assertEquals(List.of(Integer.class), selectedParameterTypes);
    }

    private static Handler<DeserializingMessage> handler(Object target) {
        return HandlerInspector.createHandler(target, HandleCommand.class, List.of(new PayloadParameterResolver()));
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(new Message(payload), MessageType.COMMAND, null);
    }

    static class MockCommandHandler {
        @HandleCommand
        String handle(String command) {
            Fluxzero.publishEvent(command);
            return command;
        }
    }

    static class PlainHandler {
        @HandleCommand
        String handle(String value) {
            return "handled:" + value;
        }
    }

    static class NoArgumentHandler {
        @HandleCommand
        String handle() {
            return "handled";
        }
    }

    static class OverloadedHandler {
        @HandleCommand
        String handle(String value) {
            return "string:" + value;
        }

        @HandleCommand
        String handle(Integer value) {
            return "integer:" + value;
        }
    }

    static class CountingPreparedInterceptor implements HandlerInterceptor {
        private final AtomicInteger prepareCount = new AtomicInteger();

        @Override
        public Function<DeserializingMessage, Object> interceptHandling(
                Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
            return function;
        }

        @Override
        public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
            prepareCount.incrementAndGet();
            return (message, descriptor, combiner, next) -> next.apply(message, descriptor, combiner);
        }

        @Override
        public boolean supportsPreparation() {
            return true;
        }
    }

    static class SubclassablePreparedInterceptor implements HandlerInterceptor {
        protected final AtomicInteger prepareCount = new AtomicInteger();

        @Override
        public Function<DeserializingMessage, Object> interceptHandling(
                Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
            return function;
        }

        @Override
        public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
            prepareCount.incrementAndGet();
            return (message, descriptor, combiner, next) -> next.apply(message, descriptor, combiner);
        }

        @Override
        public boolean supportsPreparation() {
            return true;
        }
    }

    static class LegacyOverridePreparedInterceptor extends SubclassablePreparedInterceptor {
        private final AtomicInteger legacyInvocationCount = new AtomicInteger();

        @Override
        public Function<DeserializingMessage, Object> interceptHandling(
                Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
            return message -> {
                legacyInvocationCount.incrementAndGet();
                return "legacy:" + function.apply(message);
            };
        }
    }

    private record RecordingPreparedInterceptor(String name, List<String> invocationOrder)
            implements HandlerInterceptor {
        @Override
        public Function<DeserializingMessage, Object> interceptHandling(
                Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
            return message -> record(message, invoker, (current, next, handler, combiner) -> function.apply(next),
                                     (first, second) -> first);
        }

        @Override
        public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
            return (message, descriptor, combiner, next) -> record(message, descriptor, next, combiner);
        }

        private Object record(DeserializingMessage message, HandlerDescriptor handler,
                              PreparedHandlerFunction next, java.util.function.BiFunction<Object, Object, Object> combiner) {
            invocationOrder.add(name + "-before");
            Object result = next.apply(message, handler, combiner);
            invocationOrder.add(name + "-after");
            return result;
        }

        @Override
        public boolean supportsPreparation() {
            return true;
        }
    }

    @Order(10)
    static class PositiveHandlerInterceptor implements HandlerInterceptor {
        private final List<String> invocationOrder;

        PositiveHandlerInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                        io.fluxzero.common.handling.HandlerInvoker invoker) {
            return message -> {
                invocationOrder.add("positive");
                return function.apply(message);
            };
        }
    }

    @Order(-10)
    static class HigherPriorityHandlerInterceptor implements HandlerInterceptor {
        private final List<String> invocationOrder;

        HigherPriorityHandlerInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                        io.fluxzero.common.handling.HandlerInvoker invoker) {
            return message -> {
                invocationOrder.add("negative");
                return function.apply(message);
            };
        }
    }
}
