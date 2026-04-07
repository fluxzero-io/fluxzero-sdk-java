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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.HandlerRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class StatefulHandlerBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 2_000_000);
    private static final int WARM_UP = Integer.getInteger("warmup", 1);
    private static final JacksonSerializer serializer = new JacksonSerializer();
    private static final BenchmarkEntry storedEntry = new BenchmarkEntry(
            "order-1", new BenchmarkHandler("order-1", "customer-1", Instant.parse("2024-01-01T00:00:00Z")));
    private static final HandlerRepository repository = new BenchmarkRepository(storedEntry);
    private static final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers =
            List.of(new PayloadParameterResolver());
    private static final HandlerMatcher<Object, DeserializingMessage> matcher = HandlerInspector.inspect(
            BenchmarkHandler.class,
            parameterResolvers,
            HandlerConfiguration.<DeserializingMessage>builder()
                    .methodAnnotation(HandleCommand.class)
                    .messageFilter(new PayloadFilter())
                    .build());
    private static final StatefulHandler statefulHandler = new StatefulHandler(
            BenchmarkHandler.class,
            matcher,
            repository,
            parameterResolvers,
            e -> ReflectionUtils.getMethodAnnotation(e, HandleCommand.class).orElse(null));
    private static final HandlerAssociations handlerAssociations = new HandlerAssociations(
            BenchmarkHandler.class,
            parameterResolvers,
            e -> ReflectionUtils.getMethodAnnotation(e, HandleCommand.class).orElse(null));
    private static final DeserializingMessage message = message();
    private static final List<java.lang.reflect.Executable> matchingMethods = matcher.matchingMethods(message).toList();
    private static volatile Object blackhole;

    public static void main(String[] args) {
        System.out.printf("Benchmark config: iterations=%d, warmup=%d%n", ITERATIONS, WARM_UP);
        for (int i = 0; i < WARM_UP; i++) {
            benchmarkAssociations();
            benchmarkGetInvoker();
            benchmarkGetInvokerAndInvoke();
        }
        run("stateful associations", StatefulHandlerBenchmark::benchmarkAssociations);
        run("stateful getInvoker", StatefulHandlerBenchmark::benchmarkGetInvoker);
        run("stateful getInvoker+invoke", StatefulHandlerBenchmark::benchmarkGetInvokerAndInvoke);
    }

    private static DeserializingMessage message() {
        return new DeserializingMessage(
                new Message(new UpdateOrder("order-1", "customer-1")),
                MessageType.COMMAND,
                serializer);
    }

    private static void run(String name, Runnable scenario) {
        TimingUtils.time(scenario, duration -> {
            long operationsPerSecond = duration == 0 ? ITERATIONS : ITERATIONS * 1000L / duration;
            System.out.printf("%s: %d iterations in %dms (%d ops/s)%n",
                              name, ITERATIONS, duration, operationsPerSecond);
        });
    }

    private static void benchmarkAssociations() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = handlerAssociations.associations(message, matchingMethods.stream());
        }
    }

    private static void benchmarkGetInvoker() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = statefulHandler.getInvoker(message);
        }
    }

    private static void benchmarkGetInvokerAndInvoke() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = statefulHandler.getInvoker(message).orElseThrow().invoke();
        }
    }

    record UpdateOrder(String orderId, String customerId) {
    }

    record BenchmarkHandler(@EntityId @Association("orderId") String orderId,
                            @Association("customerId") String customerId,
                            Instant lastUpdated) {
        @HandleCommand
        void handle(@Association("customerId") UpdateOrder command) {
        }
    }

    record BenchmarkEntry(String id, Object value) implements Entry<Object> {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public Object getValue() {
            return value;
        }
    }

    private record BenchmarkRepository(BenchmarkEntry storedEntry) implements HandlerRepository {
        @Override
        public Collection<? extends Entry<Object>> findByAssociation(Map<Object, String> associations) {
            return associations.entrySet().stream().allMatch(entry -> switch (entry.getValue()) {
                case "orderId" -> storedEntry.id().equals(entry.getKey());
                case "customerId" -> ((BenchmarkHandler) storedEntry.value()).customerId().equals(entry.getKey());
                default -> false;
            }) ? List.of(storedEntry) : List.of();
        }

        @Override
        public Collection<? extends Entry<?>> getAll() {
            return List.of(storedEntry);
        }

        @Override
        public CompletableFuture<?> put(Object id, Object value) {
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletableFuture<?> delete(Object id) {
            return CompletableFuture.completedFuture(id);
        }
    }
}
