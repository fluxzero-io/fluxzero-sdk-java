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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.SearchUtils;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.api.Metadata;
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
import io.fluxzero.sdk.publishing.correlation.DefaultCorrelationDataProvider;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleResult;
import io.fluxzero.sdk.tracking.handling.HandlerAssociations;
import io.fluxzero.sdk.tracking.handling.PayloadFilter;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import io.fluxzero.sdk.tracking.handling.StatefulHandler;
import io.fluxzero.sdk.tracking.handling.Trigger;
import io.fluxzero.sdk.tracking.handling.TriggerParameterResolver;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-version comparable benchmark for a few high-frequency public SDK paths.
 * <p>
 * This harness intentionally stays on public APIs so the same source can be compiled against another checkout or
 * worktree when comparing two versions of the SDK.
 */
public class CrossVersionHotPathBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 2_000_000);
    private static final int WARM_UP = Integer.getInteger("warmup", 2);
    private static final JacksonSerializer serializer = new JacksonSerializer();
    private static final DefaultCorrelationDataProvider correlationDataProvider =
            DefaultCorrelationDataProvider.INSTANCE;
    private static final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers =
            List.of(new PayloadParameterResolver());
    private static final BenchmarkEntry storedEntry = new BenchmarkEntry(
            "order-1", new BenchmarkHandler("order-1", "customer-1", Instant.parse("2024-01-01T00:00:00Z")));
    private static final HandlerRepository repository = new BenchmarkRepository(storedEntry);
    private static final HandlerAssociations handlerAssociations = new HandlerAssociations(
            BenchmarkHandler.class,
            parameterResolvers,
            e -> ReflectionUtils.getMethodAnnotation(e, HandleCommand.class).orElse(null));
    private static final TriggerParameterResolver triggerResolver = new TriggerParameterResolver(null, serializer);
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
    private static final DeserializingMessage commandMessage = new DeserializingMessage(
            new Message(new UpdateOrder("order-1", "customer-1")),
            MessageType.COMMAND,
            serializer);
    private static final DeserializingMessage triggerMessage = new DeserializingMessage(
            new Message("result", Metadata.of(
                    correlationDataProvider.getTriggerKey(), TriggerPayload.class.getName(),
                    correlationDataProvider.getTriggerTypeKey(), MessageType.COMMAND.name(),
                    correlationDataProvider.getConsumerKey(), "main")),
            MessageType.RESULT,
            serializer);
    private static final SearchContainer searchContainer = new SearchContainer(
            new NestedTimestamp(Instant.parse("2024-01-01T00:00:00Z")));
    private static final Method associationMethod;
    private static final Method triggerMethod;
    private static volatile Object blackhole;

    static {
        try {
            associationMethod = BenchmarkHandler.class.getDeclaredMethod("handle", UpdateOrder.class);
            triggerMethod = TriggerHandler.class.getDeclaredMethod("handle", String.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) {
        System.out.printf("Benchmark config: iterations=%d, warmup=%d%n", ITERATIONS, WARM_UP);
        for (int i = 0; i < WARM_UP; i++) {
            benchmarkAssociationProperties();
            benchmarkMethodAssociationProperties();
            benchmarkTriggerTest();
            benchmarkSearchParseTimeProperty();
            benchmarkStatefulGetInvoker();
            benchmarkStatefulGetInvokerAndInvoke();
        }
        run("association properties", CrossVersionHotPathBenchmark::benchmarkAssociationProperties);
        run("method association properties", CrossVersionHotPathBenchmark::benchmarkMethodAssociationProperties);
        run("trigger filter", CrossVersionHotPathBenchmark::benchmarkTriggerTest);
        run("search parseTimeProperty", CrossVersionHotPathBenchmark::benchmarkSearchParseTimeProperty);
        run("stateful getInvoker", CrossVersionHotPathBenchmark::benchmarkStatefulGetInvoker);
        run("stateful getInvoker+invoke", CrossVersionHotPathBenchmark::benchmarkStatefulGetInvokerAndInvoke);
    }

    private static void run(String name, Runnable scenario) {
        TimingUtils.time(scenario, duration -> {
            long operationsPerSecond = duration == 0 ? ITERATIONS : ITERATIONS * 1000L / duration;
            System.out.printf("%s: %d iterations in %dms (%d ops/s)%n",
                              name, ITERATIONS, duration, operationsPerSecond);
        });
    }

    private static void benchmarkAssociationProperties() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = handlerAssociations.getAssociationProperties();
        }
    }

    private static void benchmarkMethodAssociationProperties() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = handlerAssociations.getMethodAssociationProperties(associationMethod);
        }
    }

    private static void benchmarkTriggerTest() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = triggerResolver.test(triggerMessage, triggerMethod, HandleResult.class, TriggerHandler.class);
        }
    }

    private static void benchmarkSearchParseTimeProperty() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = SearchUtils.parseTimeProperty("nested.timestamp", searchContainer, false, () -> null);
        }
    }

    private static void benchmarkStatefulGetInvoker() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = statefulHandler.getInvoker(commandMessage);
        }
    }

    private static void benchmarkStatefulGetInvokerAndInvoke() {
        for (int i = 0; i < ITERATIONS; i++) {
            blackhole = statefulHandler.getInvoker(commandMessage).orElseThrow().invoke();
        }
    }

    record UpdateOrder(String orderId, String customerId) {
    }

    record TriggerPayload(String result) {
    }

    record SearchContainer(NestedTimestamp nested) {
    }

    record NestedTimestamp(Instant timestamp) {
    }

    record BenchmarkHandler(@EntityId @Association("orderId") String orderId,
                            @Association("customerId") String customerId,
                            Instant lastUpdated) {
        @HandleCommand
        void handle(@Association("customerId") UpdateOrder command) {
        }
    }

    static class TriggerHandler {
        @HandleResult
        @Trigger(value = TriggerPayload.class, consumer = "main")
        void handle(String result) {
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
