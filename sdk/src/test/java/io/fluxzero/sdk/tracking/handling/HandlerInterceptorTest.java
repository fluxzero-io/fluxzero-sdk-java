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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    static class MockCommandHandler {
        @HandleCommand
        String handle(String command) {
            Fluxzero.publishEvent(command);
            return command;
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
