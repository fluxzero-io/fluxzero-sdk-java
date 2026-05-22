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
import io.fluxzero.sdk.modeling.Member;

import java.lang.reflect.Executable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

public class StatefulHandlerBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 2_000_000);
    private static final int STRESS_ITERATIONS = Integer.getInteger("stressIterations", 1_000);
    private static final int MEMBER_COUNT = Integer.getInteger("memberCount", 100);
    private static final int ROOT_COUNT = Integer.getInteger("rootCount", 10);
    private static final int WARM_UP = Integer.getInteger("warmup", 1);
    private static final JacksonSerializer serializer = new JacksonSerializer();
    private static final BenchmarkEntry storedEntry = new BenchmarkEntry(
            "order-1", new BenchmarkHandler("order-1", "customer-1", Instant.parse("2024-01-01T00:00:00Z")));
    private static final HandlerRepository repository = new BenchmarkRepository(storedEntry);
    private static final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers =
            List.of(new PayloadParameterResolver());
    private static final Function<Executable, ? extends java.lang.annotation.Annotation> annotationProvider =
            e -> ReflectionUtils.getMethodAnnotation(e, HandleCommand.class).orElse(null);
    private static final BiFunction<Class<?>, List<ParameterResolver<? super DeserializingMessage>>,
            HandlerMatcher<Object, DeserializingMessage>> matcherFactory =
            (targetClass, resolvers) -> HandlerInspector.inspect(
                    targetClass,
                    resolvers,
                    HandlerConfiguration.<DeserializingMessage>builder()
                            .methodAnnotation(HandleCommand.class)
                            .messageFilter(new PayloadFilter())
                            .build());
    private static final HandlerMatcher<Object, DeserializingMessage> matcher =
            matcherFactory.apply(BenchmarkHandler.class, parameterResolvers);
    private static final StatefulHandler statefulHandler =
            statefulHandler(BenchmarkHandler.class, repository);
    private static final HandlerAssociations handlerAssociations = new HandlerAssociations(
            BenchmarkHandler.class,
            parameterResolvers,
            annotationProvider);
    private static final DeserializingMessage message = message();
    private static final DeserializingMessage sharedPaymentMessage =
            new DeserializingMessage(new Message(new CaptureSharedPayment("shared-payment")), MessageType.COMMAND,
                                     serializer);
    private static final MutableBenchmarkRepository listMemberRepository =
            new MutableBenchmarkRepository().add("customer-1", listCustomer("customer-1"));
    private static final MutableBenchmarkRepository mapMemberRepository =
            new MutableBenchmarkRepository().add("customer-1", mapCustomer("customer-1"));
    private static final MutableBenchmarkRepository fanoutMemberRepository = fanoutRepository();
    private static final StatefulHandler listMemberHandler =
            statefulHandler(ListMemberCustomer.class, listMemberRepository);
    private static final StatefulHandler mapMemberHandler =
            statefulHandler(MapMemberCustomer.class, mapMemberRepository);
    private static final StatefulHandler fanoutMemberHandler =
            statefulHandler(ListMemberCustomer.class, fanoutMemberRepository);
    private static final List<java.lang.reflect.Executable> matchingMethods = matcher.matchingMethods(message).toList();
    private static volatile Object blackhole;

    public static void main(String[] args) {
        System.out.printf(
                "Benchmark config: iterations=%d, stressIterations=%d, memberCount=%d, rootCount=%d, warmup=%d%n",
                ITERATIONS, STRESS_ITERATIONS, MEMBER_COUNT, ROOT_COUNT, WARM_UP);
        for (int i = 0; i < WARM_UP; i++) {
            benchmarkAssociations();
            benchmarkGetInvoker();
            benchmarkGetInvokerAndInvoke();
            benchmarkListMemberInvoke();
            benchmarkMapMemberInvoke();
            benchmarkFanoutMemberInvoke();
        }
        run("stateful no-members associations", ITERATIONS, StatefulHandlerBenchmark::benchmarkAssociations);
        run("stateful no-members getInvoker", ITERATIONS, StatefulHandlerBenchmark::benchmarkGetInvoker);
        run("stateful no-members getInvoker+invoke", ITERATIONS, StatefulHandlerBenchmark::benchmarkGetInvokerAndInvoke);
        run("stateful list-members getInvoker+invoke", STRESS_ITERATIONS,
            StatefulHandlerBenchmark::benchmarkListMemberInvoke);
        run("stateful map-members getInvoker+invoke", STRESS_ITERATIONS,
            StatefulHandlerBenchmark::benchmarkMapMemberInvoke);
        run("stateful fanout-members getInvoker+invoke", STRESS_ITERATIONS,
            StatefulHandlerBenchmark::benchmarkFanoutMemberInvoke);
    }

    private static DeserializingMessage message() {
        return new DeserializingMessage(
                new Message(new UpdateOrder("order-1", "customer-1")),
                MessageType.COMMAND,
                serializer);
    }

    private static StatefulHandler statefulHandler(Class<?> targetClass, HandlerRepository repository) {
        return new StatefulHandler(
                targetClass,
                matcherFactory.apply(targetClass, parameterResolvers),
                repository,
                parameterResolvers,
                annotationProvider,
                matcherFactory,
                serializer);
    }

    private static void run(String name, int iterations, Runnable scenario) {
        TimingUtils.time(scenario, duration -> {
            long operationsPerSecond = duration == 0 ? iterations : iterations * 1000L / duration;
            System.out.printf("%s: %d iterations in %dms (%d ops/s)%n",
                              name, iterations, duration, operationsPerSecond);
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

    private static void benchmarkListMemberInvoke() {
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            blackhole = listMemberHandler.getInvoker(sharedPaymentMessage).orElseThrow().invoke();
        }
    }

    private static void benchmarkMapMemberInvoke() {
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            blackhole = mapMemberHandler.getInvoker(sharedPaymentMessage).orElseThrow().invoke();
        }
    }

    private static void benchmarkFanoutMemberInvoke() {
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            blackhole = fanoutMemberHandler.getInvoker(sharedPaymentMessage).orElseThrow().invoke();
        }
    }

    private static ListMemberCustomer listCustomer(String customerId) {
        List<ListMemberPayment> payments = new ArrayList<>(MEMBER_COUNT);
        for (int i = 0; i < MEMBER_COUNT; i++) {
            payments.add(new ListMemberPayment("line-" + i, "shared-payment", 0));
        }
        return new ListMemberCustomer(customerId, payments);
    }

    private static MapMemberCustomer mapCustomer(String customerId) {
        Map<String, MapMemberPayment> payments = new LinkedHashMap<>();
        for (int i = 0; i < MEMBER_COUNT; i++) {
            String lineId = "line-" + i;
            payments.put(lineId, new MapMemberPayment(lineId, "shared-payment", 0));
        }
        return new MapMemberCustomer(customerId, payments);
    }

    private static MutableBenchmarkRepository fanoutRepository() {
        MutableBenchmarkRepository repository = new MutableBenchmarkRepository();
        for (int i = 0; i < ROOT_COUNT; i++) {
            repository.add("customer-" + i, listCustomer("customer-" + i));
        }
        return repository;
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

    record CaptureSharedPayment(String paymentId) {
    }

    record ListMemberCustomer(@EntityId String customerId,
                              @Member List<ListMemberPayment> payments) {
    }

    record ListMemberPayment(@EntityId String lineId,
                             @Association String paymentId,
                             int captureCount) {
        @HandleCommand
        ListMemberPayment capture(CaptureSharedPayment command) {
            return new ListMemberPayment(lineId, paymentId, captureCount + 1);
        }
    }

    record MapMemberCustomer(@EntityId String customerId,
                             @Member Map<String, MapMemberPayment> payments) {
    }

    record MapMemberPayment(@EntityId String lineId,
                            @Association String paymentId,
                            int captureCount) {
        @HandleCommand
        MapMemberPayment capture(CaptureSharedPayment command) {
            return new MapMemberPayment(lineId, paymentId, captureCount + 1);
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

    private static class MutableBenchmarkRepository implements HandlerRepository {
        private final Map<String, Object> values = new LinkedHashMap<>();

        MutableBenchmarkRepository add(String id, Object value) {
            values.put(id, value);
            return this;
        }

        @Override
        public Collection<? extends Entry<?>> findByAssociation(Map<Object, String> associations) {
            return associations.isEmpty() ? List.of() : entries();
        }

        @Override
        public Collection<? extends Entry<?>> getAll() {
            return entries();
        }

        @Override
        public CompletableFuture<?> put(Object id, Object value) {
            values.put(id.toString(), value);
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletableFuture<?> delete(Object id) {
            values.remove(id.toString());
            return CompletableFuture.completedFuture(id);
        }

        private Collection<? extends Entry<?>> entries() {
            return values.entrySet().stream()
                    .map(entry -> new BenchmarkEntry(entry.getKey(), entry.getValue()))
                    .toList();
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
