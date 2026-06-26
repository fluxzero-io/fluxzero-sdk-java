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

package io.fluxzero.sdk.publishing;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DispatchInterceptorTest {

    private final Object commandHandler = new Object() {
        @HandleCommand
        Object handle(Object command) {
            Fluxzero.publishEvent(command);
            return command;
        }
    };

    @Test
    void changeMessageType() {
        TestFixture.create(
                        DefaultFluxzero.builder().addDispatchInterceptor(
                                (message, messageType, topic) -> message.withPayload(new DifferentCommand()), COMMAND),
                        commandHandler)
                .whenCommand(new Command(""))
                .expectEvents(new DifferentCommand());
    }

    @Test
    void changeMessageContent() {
        TestFixture.createAsync(
                        DefaultFluxzero.builder().addDispatchInterceptor(
                                (message, messageType, topic) -> message.withPayload(new Command("intercepted")), COMMAND),
                        commandHandler)
                .whenCommand(new Command(""))
                .expectEvents(new Command("intercepted"));
    }

    @Test
    void blockMessagePublication() {
        TestFixture.create(
                DefaultFluxzero.builder().addDispatchInterceptor((message, messageType, topic) -> null, COMMAND),
                commandHandler)
                .whenCommand(new Command("whatever"))
                .expectNoEvents().expectNoResult();
    }

    @Test
    void throwException() {
        TestFixture.create(
                        DefaultFluxzero.builder().addDispatchInterceptor((message, messageType, topic) -> {
                            throw new MockException();
                        }, COMMAND), commandHandler)
                .whenCommand(new Command("whatever"))
                .expectNoEvents().expectExceptionalResult(MockException.class);
    }

    @Test
    void ordersCustomInterceptorsUsingOrderAnnotation() {
        List<String> invocationOrder = new ArrayList<>();

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .disableMessageCorrelation()
                                .replaceMessageRoutingInterceptor(DispatchInterceptor.noOp)
                                .disableAdhocDispatchInterceptor()
                                .addDispatchInterceptor(new PositiveDispatchInterceptor(invocationOrder), COMMAND)
                                .addDispatchInterceptor(new HigherPriorityDispatchInterceptor(invocationOrder), COMMAND),
                        commandHandler)
                .whenCommand(new Command("whatever"))
                .expectEvents(new Command("whatever"))
                .expectResult(new Command("whatever"));

        assertEquals(List.of("negative", "positive"), invocationOrder);
    }

    @Test
    void acceptsSpringOrderAnnotationWhenPresent() {
        List<String> invocationOrder = new ArrayList<>();

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .disableMessageCorrelation()
                                .replaceMessageRoutingInterceptor(DispatchInterceptor.noOp)
                                .disableAdhocDispatchInterceptor()
                                .addDispatchInterceptor(new PositiveDispatchInterceptor(invocationOrder), COMMAND)
                                .addDispatchInterceptor(new SpringHigherPriorityDispatchInterceptor(invocationOrder),
                                                        COMMAND),
                        commandHandler)
                .whenCommand(new Command("whatever"))
                .expectEvents(new Command("whatever"))
                .expectResult(new Command("whatever"));

        assertEquals(List.of("spring-negative", "positive"), invocationOrder);
    }

    @Value
    static class Command {
        String content;
    }

    @Value
    static class DifferentCommand {
    }

    @Order(10)
    static class PositiveDispatchInterceptor implements DispatchInterceptor {
        private final List<String> invocationOrder;

        PositiveDispatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public io.fluxzero.sdk.common.Message interceptDispatch(io.fluxzero.sdk.common.Message message,
                                                                io.fluxzero.common.MessageType messageType,
                                                                String topic) {
            invocationOrder.add("positive");
            return message;
        }
    }

    @Order(-10)
    static class HigherPriorityDispatchInterceptor implements DispatchInterceptor {
        private final List<String> invocationOrder;

        HigherPriorityDispatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public io.fluxzero.sdk.common.Message interceptDispatch(io.fluxzero.sdk.common.Message message,
                                                                io.fluxzero.common.MessageType messageType,
                                                                String topic) {
            invocationOrder.add("negative");
            return message;
        }
    }

    @org.springframework.core.annotation.Order(-20)
    static class SpringHigherPriorityDispatchInterceptor implements DispatchInterceptor {
        private final List<String> invocationOrder;

        SpringHigherPriorityDispatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public io.fluxzero.sdk.common.Message interceptDispatch(io.fluxzero.sdk.common.Message message,
                                                                io.fluxzero.common.MessageType messageType,
                                                                String topic) {
            invocationOrder.add("spring-negative");
            return message;
        }
    }
}
