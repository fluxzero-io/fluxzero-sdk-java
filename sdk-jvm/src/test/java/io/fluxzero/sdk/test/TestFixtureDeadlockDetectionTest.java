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

package io.fluxzero.sdk.test;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.publishing.Timeout;
import io.fluxzero.sdk.publishing.TimeoutException;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFixtureDeadlockDetectionTest {

    @Test
    void syncFixtureFailsNestedRequestWhenConsumerMayBeSame() {
        TestFixture.createJvmCompatibility(defaultAppConsumerBuilder(), new OuterHandler(), new InnerHandler())
                .whenQuery(new OuterQuery())
                .verifyExceptionalResult((IllegalStateException e) -> {
                    assertTrue(e.getMessage().contains("production deadlock risk"));
                    assertTrue(e.getMessage()
                                       .contains("`OuterHandler#handle(OuterQuery)` is handling `QUERY OuterQuery`"));
                    assertTrue(e.getMessage().contains("sends `QUERY InnerQuery` while waiting for a result"));
                    assertTrue(e.getMessage().contains("`InnerHandler#handle(InnerQuery)` handles that request"));
                    assertTrue(e.getMessage()
                                       .contains("Put these handlers on separate consumers"));
                    assertFalse(e.getMessage().contains(TestFixtureDeadlockDetectionTest.class.getPackageName()));
                });
    }

    @Test
    void syncFixtureAllowsNestedRequestWhenConsumersAreConfiguredSeparately() {
        FluxzeroBuilder builder = defaultAppConsumerBuilder();
        builder.addConsumerConfiguration(consumer("outer", OuterHandler.class), MessageType.QUERY);
        builder.addConsumerConfiguration(consumer("inner", InnerHandler.class), MessageType.QUERY);

        TestFixture.createJvmCompatibility(builder, new OuterHandler(), new InnerHandler())
                .whenQuery(new OuterQuery())
                .expectResult("inner");
    }

    @Test
    void syncFixtureAllowsNestedRequestWhenSharedConsumerHasMultipleThreads() {
        FluxzeroBuilder builder = defaultAppConsumerBuilder();
        builder.addConsumerConfiguration(
                ConsumerConfiguration.builder()
                        .name("shared")
                        .threads(2)
                        .handlerFilter(handler -> ReflectionUtils.asClass(handler).equals(OuterHandler.class)
                                || ReflectionUtils.asClass(handler).equals(InnerHandler.class))
                        .build(),
                MessageType.QUERY);

        TestFixture.createJvmCompatibility(builder, new OuterHandler(), new InnerHandler())
                .whenQuery(new OuterQuery())
                .expectResult("inner");
    }

    @Test
    void syncFixtureFailsNestedRequestWhenSameHandlerConsumerMayBeSame() {
        TestFixture.createJvmCompatibility(defaultAppConsumerBuilder(), new SameConsumerHandler())
                .whenQuery(new OuterQuery())
                .verifyExceptionalResult((IllegalStateException e) -> {
                    assertTrue(e.getMessage().contains("production deadlock risk"));
                    assertTrue(e.getMessage().contains("same consumer"));
                });
    }

    @Test
    void syncFixtureAllowsNestedFireAndForgetCommandOnSameConsumer() {
        TestFixture.createJvmCompatibility(defaultAppConsumerBuilder(), new FireAndForgetOuterHandler(), new FireAndForgetInnerHandler())
                .whenCommand(new FireAndForgetOuterCommand())
                .expectEvents(new FireAndForgetHandledEvent());
    }

    @Test
    void syncFixtureAllowsNestedRequestToLocalHandlerOnSameConsumer() {
        TestFixture.createJvmCompatibility(defaultAppConsumerBuilder(), new OuterHandler(), new LocalInnerHandler())
                .whenQuery(new OuterQuery())
                .expectResult("inner");
    }

    @Test
    void syncFixtureAllowsNestedRequestToPassiveHandlerOnSameConsumer() {
        TestFixture.createJvmCompatibility(defaultAppConsumerBuilder(), new PassiveOuterHandler(), new PassiveInnerHandler())
                .whenQuery(new PassiveOuterQuery())
                .expectResult("timed-out");
    }

    @Test
    void syncFixtureAllowsNestedRequestBetweenRegisteredLocalSelfHandlers() {
        TestFixture.createJvmCompatibility(defaultAppConsumerBuilder(), LocalOuterQuery.class, LocalInnerQuery.class)
                .whenQuery(new LocalOuterQuery("outer"))
                .expectResult("inner");
    }

    private static FluxzeroBuilder defaultAppConsumerBuilder() {
        return DefaultFluxzero.builder().replacePropertySource(existing -> new SimplePropertySource(Map.of(
                ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY,
                ConsumerConfiguration.DEFAULT_APP_CONSUMER_MODE)).andThen(existing));
    }

    private static ConsumerConfiguration consumer(String name, Class<?> handlerType) {
        return ConsumerConfiguration.builder()
                .name(name)
                .handlerFilter(handler -> ReflectionUtils.asClass(handler).equals(handlerType))
                .build();
    }

    private static class OuterHandler {
        @HandleQuery
        String handle(OuterQuery query) {
            return Fluxzero.queryAndWait(new InnerQuery());
        }
    }

    private static class InnerHandler {
        @HandleQuery
        String handle(InnerQuery query) {
            return "inner";
        }
    }

    private static class SameConsumerHandler {
        @HandleQuery
        String handle(OuterQuery query) {
            return Fluxzero.queryAndWait(new InnerQuery());
        }

        @HandleQuery
        String handle(InnerQuery query) {
            return "inner";
        }
    }

    private static class LocalInnerHandler {
        @HandleQuery
        @LocalHandler(allowExternalMessages = true)
        String handle(InnerQuery query) {
            return "inner";
        }
    }

    private static class FireAndForgetOuterHandler {
        @HandleCommand
        void handle(FireAndForgetOuterCommand command) {
            Fluxzero.sendAndForgetCommand(new FireAndForgetInnerCommand());
        }
    }

    private static class FireAndForgetInnerHandler {
        @HandleCommand
        void handle(FireAndForgetInnerCommand command) {
            Fluxzero.publishEvent(new FireAndForgetHandledEvent());
        }
    }

    private static class PassiveOuterHandler {
        @HandleQuery
        String handle(PassiveOuterQuery query) {
            try {
                return Fluxzero.queryAndWait(new PassiveInnerQuery());
            } catch (TimeoutException e) {
                return "timed-out";
            }
        }
    }

    private static class PassiveInnerHandler {
        @HandleQuery(passive = true)
        String handle(PassiveInnerQuery query) {
            return "ignored";
        }
    }

    private record OuterQuery() {
    }

    private record InnerQuery() {
    }

    private record FireAndForgetOuterCommand() {
    }

    private record FireAndForgetInnerCommand() {
    }

    private record FireAndForgetHandledEvent() {
    }

    private record PassiveOuterQuery() {
    }

    @Timeout(10)
    private record PassiveInnerQuery() {
    }

    private record LocalOuterQuery(String value) {
        @HandleQuery
        String handle() {
            return Fluxzero.queryAndWait(new LocalInnerQuery("inner"));
        }
    }

    private record LocalInnerQuery(String value) {
        @HandleQuery
        String handle() {
            return value;
        }
    }
}
