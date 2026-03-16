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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.client.DefaultTracker;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.EVENT;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchInterceptorTest {

    @Test
    void testInvocationOrder() {
        List<Object> invokedInstances = new ArrayList<>();
        BatchInterceptor outerInterceptor = new BatchInterceptor() {
            @Override
            public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
                return messages -> {
                    invokedInstances.add(this);
                    consumer.accept(messages);
                };
            }
        };
        BatchInterceptor innerInterceptor = new BatchInterceptor() {
            @Override
            public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
                return messages -> {
                    invokedInstances.add(this);
                    consumer.accept(messages);
                };
            }
        };
        Consumer<MessageBatch> function = new Consumer<>() {
            @Override
            public void accept(MessageBatch messages) {
                invokedInstances.add(this);
            }
        };
        var configuration = ConsumerConfiguration.builder().name("test").build();
        Consumer<MessageBatch> invocation = BatchInterceptor.join(Arrays.asList(outerInterceptor, innerInterceptor))
                .intercept(function, new Tracker("0", COMMAND, null, configuration, null));
        assertEquals(emptyList(), invokedInstances);
        invocation.accept(new MessageBatch(new int[]{0, 128}, emptyList(), 0L, Position.newPosition(), true));
        assertEquals(Arrays.asList(outerInterceptor, innerInterceptor, function), invokedInstances);
    }

    @Test
    void ordersCustomBatchInterceptorsAroundBuiltIns() {
        List<String> invocationOrder = new ArrayList<>();

        TestFixture.createAsync(DefaultFluxzero.builder()
                        .addBatchInterceptor(new PositiveBatchInterceptor(invocationOrder), COMMAND)
                        .addBatchInterceptor(new HigherPriorityBatchInterceptor(invocationOrder), COMMAND),
                new Object() {
                    @HandleCommand
                    String handle(String command) {
                        invocationOrder.add("handler");
                        return command;
                    }
                })
                .whenCommand("foo")
                .expectResult("foo")
                .expectNoErrors();

        assertEquals(List.of("negative", "positive", "handler"), invocationOrder);
    }

    @Test
    void defaultTrackerStartedWithFluxzeroCombinesGlobalAndConsumerBatchInterceptors() throws Exception {
        List<String> invocationOrder = new ArrayList<>();
        CountDownLatch handled = new CountDownLatch(1);
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .addBatchInterceptor(new PositiveBatchInterceptor(invocationOrder), EVENT)
                .addBatchInterceptor(new HigherPriorityBatchInterceptor(invocationOrder), EVENT)
                .build(LocalClient.newInstance(null));
        Registration registration = DefaultTracker.start(messages -> {
            invocationOrder.add("consumer");
            handled.countDown();
        }, EVENT, ConsumerConfiguration.builder()
                .name("direct")
                .batchInterceptor(new PositiveConsumerBatchInterceptor(invocationOrder))
                .batchInterceptor(new HigherPriorityConsumerBatchInterceptor(invocationOrder))
                .build(), fluxzero);
        try {
            fluxzero.eventGateway().publish(Message.asMessage("foo"), Guarantee.STORED).get();
            assertTrue(handled.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("consumer-negative", "consumer-positive", "negative", "positive", "consumer"),
                         invocationOrder);
        } finally {
            registration.cancel();
            fluxzero.close(true);
        }
    }

    @Order(10)
    static class PositiveBatchInterceptor implements BatchInterceptor {
        private final List<String> invocationOrder;

        PositiveBatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            return messages -> {
                invocationOrder.add("positive");
                consumer.accept(messages);
            };
        }
    }

    @Order(-10)
    static class HigherPriorityBatchInterceptor implements BatchInterceptor {
        private final List<String> invocationOrder;

        HigherPriorityBatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            return messages -> {
                invocationOrder.add("negative");
                consumer.accept(messages);
            };
        }
    }

    @Order(10)
    static class PositiveConsumerBatchInterceptor implements BatchInterceptor {
        private final List<String> invocationOrder;

        PositiveConsumerBatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            return messages -> {
                invocationOrder.add("consumer-positive");
                consumer.accept(messages);
            };
        }
    }

    @Order(-10)
    static class HigherPriorityConsumerBatchInterceptor implements BatchInterceptor {
        private final List<String> invocationOrder;

        HigherPriorityConsumerBatchInterceptor(List<String> invocationOrder) {
            this.invocationOrder = invocationOrder;
        }

        @Override
        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            return messages -> {
                invocationOrder.add("consumer-negative");
                consumer.accept(messages);
            };
        }
    }
}
