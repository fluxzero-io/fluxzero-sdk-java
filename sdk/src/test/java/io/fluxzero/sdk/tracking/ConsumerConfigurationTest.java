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

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Slf4j
public class ConsumerConfigurationTest {
    private final Clock nowClock = Clock.fixed(Instant.parse("2022-01-01T00:00:00.000Z"), ZoneId.systemDefault());
    private static final List<String> invocationOrder = new CopyOnWriteArrayList<>();

    @Test
    void nonExclusiveConsumerLetsHandlerThrough() {
        TestFixture.createAsync(DefaultFluxzero.builder()
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("nonExclusive")
                                                                          .passive(true)
                                                                          .exclusive(false).build(),
                                                                  MessageType.COMMAND)
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("exclusive")
                                                                          .build(), MessageType.COMMAND)
                                        .configureDefaultConsumer(COMMAND, c -> c.toBuilder().name("default").build()),
                                new Handler())

                .whenCommand(new Command())
                .expectOnlyEvents("nonExclusive", "exclusive");
    }

    @Test
    void orderOfExclusiveVsNonExclusiveDoesntMatter() {
        TestFixture.createAsync(DefaultFluxzero.builder()
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("exclusive1")
                                                                          .build())
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("exclusive2")
                                                                          .build())
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("nonExclusive")
                                                                          .passive(true)
                                                                          .exclusive(false).build())
                                        .configureDefaultConsumer(COMMAND, c -> c.toBuilder().name("default").build()),
                                new Handler())

                .whenCommand(new Command())
                .expectOnlyEvents("nonExclusive", "exclusive1");
    }

    @Test
    void passiveConsumerReturnsNothing() {
        TestFixture.createAsync(DefaultFluxzero.builder()
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("nonExclusivePassive")
                                                                          .exclusive(false).passive(true).build(),
                                                                  MessageType.COMMAND)
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("default").build(), MessageType.COMMAND),
                                new Handler())

                .whenCommand(new Command())
                .expectOnlyEvents("nonExclusivePassive", "default")
                .expectResult("default");
    }

    @Test
    void exceptionWhenHandlerHasNoConsumer() {
        assertThrows(TrackingException.class, () ->
                TestFixture.createAsync(
                        DefaultFluxzero.builder().configureDefaultConsumer(COMMAND, c -> c.toBuilder()
                                .handlerFilter(h -> !h.getClass().equals(Handler.class)).build()),
                        new Handler()));
    }

    @Test
    void noExceptionWhenHandlerHasOnlyNonExclusiveConsumer() {
        assertDoesNotThrow(() -> TestFixture.createAsync(DefaultFluxzero.builder()
                                                                 .configureDefaultConsumer(COMMAND, c -> c.toBuilder()
                                                                         .exclusive(false).build()),
                                                         new Handler()));
    }

    @Test
    void dontProcessMessageWhenMaxIndexIsReached() {
        Long nowIndex = 107544261427200000L;
        TestFixture.createAsync(DefaultFluxzero.builder()
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("minIndex")
                                                                          .exclusive(false).minIndex(nowIndex).build(),
                                                                  MessageType.COMMAND)
                                        .addConsumerConfiguration(ConsumerConfiguration.builder()
                                                                          .name("maxIndex")
                                                                          .maxIndexExclusive(nowIndex).build(),
                                                                  MessageType.COMMAND)
                                        .configureDefaultConsumer(COMMAND, c -> c.toBuilder().name("default").build()),
                                new Handler())
                .withClock(nowClock)

                .whenCommand(new Command())
                .expectEvents("minIndex")
                .expectResult("minIndex");
    }

    @Test
    void interceptorInConsumerTest() {
        TestFixture.createAsync(
                        DefaultFluxzero.builder()
                                .addHandlerInterceptor((f, i) -> m -> "common " + f.apply(m))
                                .addConsumerConfiguration(
                                        ConsumerConfiguration.builder().name("test")
                                                .handlerInterceptor((f, i) -> m -> "consumer-1 " + f.apply(m))
                                                .handlerInterceptor((f, i) -> m -> "consumer-2 " + f.apply(m))
                                                .build()),
                        new Handler())
                .withClock(nowClock)
                .whenCommand(new Command())
                .expectEvents("test")
                .expectResult("consumer-1 consumer-2 common test");
    }

    @Test
    void orderedHandlerInterceptorsInConsumerConfiguration() {
        invocationOrder.clear();

        TestFixture.createAsync(
                        DefaultFluxzero.builder()
                                .addConsumerConfiguration(
                                        ConsumerConfiguration.builder().name("test")
                                                .handlerInterceptor(new PositiveConsumerHandlerInterceptor(invocationOrder))
                                                .handlerInterceptor(new NegativeConsumerHandlerInterceptor(invocationOrder))
                                                .build()),
                        new OrderedHandler())
                .withClock(nowClock)
                .whenCommand(new Command())
                .expectEvents("test")
                .expectResult("test");

        assertEquals(List.of("negative-handler", "positive-handler", "handler"), invocationOrder);
    }

    @Test
    void orderedBatchInterceptorsInConsumerAnnotation() {
        invocationOrder.clear();

        TestFixture.createAsync(DefaultFluxzero.builder(), new OrderedBatchConsumerHandler())
                .withClock(nowClock)
                .whenCommand(new Command())
                .expectEvents("annotated")
                .expectResult("annotated");

        assertEquals(List.of("negative-batch", "positive-batch", "annotated-handler"), invocationOrder);
    }

    @Test
    void orderedDispatchInterceptorsInConsumerConfiguration() {
        TestFixture.createAsync(
                        DefaultFluxzero.builder()
                                .addConsumerConfiguration(
                                        ConsumerConfiguration.builder().name("dispatch-config")
                                                .dispatchInterceptor(new PositiveConsumerDispatchInterceptor())
                                                .dispatchInterceptor(new NegativeConsumerDispatchInterceptor())
                                                .build()),
                        new DispatchingHandler("dispatch-config"))
                .withClock(nowClock)
                .whenCommand(new Command())
                .expectEvents("positive negative dispatch-config")
                .expectResult("positive negative dispatch-config");
    }

    @Test
    void orderedDispatchInterceptorsInConsumerAnnotation() {
        TestFixture.createAsync(DefaultFluxzero.builder(), new AnnotatedDispatchingHandler())
                .withClock(nowClock)
                .whenCommand(new Command())
                .expectEvents("positive negative annotated-dispatch")
                .expectResult("positive negative annotated-dispatch");
    }

    static class Handler {
        @HandleCommand
        String handle(Command command) {
            String consumerName = Tracker.current().orElseThrow().getConfiguration().getName();
            Fluxzero.publishEvent(consumerName);
            return consumerName;
        }
    }

    static class Command {
    }

    static class OrderedHandler {
        @HandleCommand
        String handle(Command command) {
            invocationOrder.add("handler");
            Fluxzero.publishEvent("test");
            return "test";
        }
    }

    static class DispatchingHandler {
        private final String consumerName;

        DispatchingHandler(String consumerName) {
            this.consumerName = consumerName;
        }

        @HandleCommand
        String handle(Command command) {
            Fluxzero.publishEvent(consumerName);
            return consumerName;
        }
    }

    @Consumer(name = "annotated", batchInterceptors = {PositiveConsumerBatchInterceptor.class,
            NegativeConsumerBatchInterceptor.class})
    static class OrderedBatchConsumerHandler {
        @HandleCommand
        String handle(Command command) {
            invocationOrder.add("annotated-handler");
            Fluxzero.publishEvent("annotated");
            return "annotated";
        }
    }

    @Consumer(name = "annotated-dispatch", dispatchInterceptors = {PositiveConsumerDispatchInterceptor.class,
            NegativeConsumerDispatchInterceptor.class})
    static class AnnotatedDispatchingHandler {
        @HandleCommand
        String handle(Command command) {
            Fluxzero.publishEvent("annotated-dispatch");
            return "annotated-dispatch";
        }
    }

    @Order(10)
    static class PositiveConsumerHandlerInterceptor implements HandlerInterceptor {
        private final List<String> invocationOrder;

        PositiveConsumerHandlerInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Function<io.fluxzero.sdk.common.serialization.DeserializingMessage, Object> interceptHandling(
                Function<io.fluxzero.sdk.common.serialization.DeserializingMessage, Object> function,
                io.fluxzero.common.handling.HandlerInvoker invoker) {
            return message -> {
                invocationOrder.add("positive-handler");
                return function.apply(message);
            };
        }
    }

    @Order(-10)
    static class NegativeConsumerHandlerInterceptor implements HandlerInterceptor {
        private final List<String> invocationOrder;

        NegativeConsumerHandlerInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Function<io.fluxzero.sdk.common.serialization.DeserializingMessage, Object> interceptHandling(
                Function<io.fluxzero.sdk.common.serialization.DeserializingMessage, Object> function,
                io.fluxzero.common.handling.HandlerInvoker invoker) {
            return message -> {
                invocationOrder.add("negative-handler");
                return function.apply(message);
            };
        }
    }

    @Order(10)
    public static class PositiveConsumerBatchInterceptor implements BatchInterceptor {
        @Override
        public java.util.function.Consumer<io.fluxzero.common.api.tracking.MessageBatch> intercept(
                java.util.function.Consumer<io.fluxzero.common.api.tracking.MessageBatch> consumer, Tracker tracker) {
            return batch -> {
                invocationOrder.add("positive-batch");
                consumer.accept(batch);
            };
        }
    }

    @Order(-10)
    public static class NegativeConsumerBatchInterceptor implements BatchInterceptor {
        @Override
        public java.util.function.Consumer<io.fluxzero.common.api.tracking.MessageBatch> intercept(
                java.util.function.Consumer<io.fluxzero.common.api.tracking.MessageBatch> consumer, Tracker tracker) {
            return batch -> {
                invocationOrder.add("negative-batch");
                consumer.accept(batch);
            };
        }
    }

    @Order(10)
    public static class PositiveConsumerDispatchInterceptor implements DispatchInterceptor {
        @Override
        public io.fluxzero.sdk.common.Message interceptDispatch(io.fluxzero.sdk.common.Message message,
                                                                MessageType messageType, String topic) {
            return message.withPayload("positive " + message.getPayload());
        }
    }

    @Order(-10)
    public static class NegativeConsumerDispatchInterceptor implements DispatchInterceptor {
        @Override
        public io.fluxzero.sdk.common.Message interceptDispatch(io.fluxzero.sdk.common.Message message,
                                                                MessageType messageType, String topic) {
            return message.withPayload("negative " + message.getPayload());
        }
    }
}
