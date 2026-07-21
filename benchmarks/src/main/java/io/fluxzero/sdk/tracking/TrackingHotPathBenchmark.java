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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EntityParameterResolver;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor;
import io.fluxzero.sdk.publishing.dataprotection.MissingProtectedDataPolicy;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.JsonPayloadParameterResolver;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.MetadataParameterResolver;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import io.fluxzero.sdk.tracking.handling.RepositoryProvider;
import io.fluxzero.sdk.tracking.handling.Stateful;
import io.fluxzero.sdk.tracking.handling.TimestampParameterResolver;
import io.fluxzero.sdk.tracking.handling.TriggerParameterResolver;
import io.fluxzero.sdk.tracking.handling.contentfiltering.ContentFilterInterceptor;
import io.fluxzero.sdk.tracking.handling.errorreporting.ErrorReportingInterceptor;
import io.fluxzero.sdk.tracking.metrics.HandlerMonitor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** A received-event batch flowing through deserialization, tracking contexts, and mixed handler types. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms384m", "-Xmx384m", "-XX:+AlwaysPreTouch"})
public class TrackingHotPathBenchmark {
    private static final int BATCH_SIZE = 32;

    private JacksonSerializer serializer;
    private DefaultTracking tracking;
    private ConsumerConfiguration configuration;
    private List<SerializedMessage> serializedMessages;
    private List<Handler<DeserializingMessage>> handlers;
    private StatelessEventHandler statelessHandler;
    private StatefulProcessRepository statefulRepository;

    @Setup(Level.Trial)
    public void setUp() {
        serializer = new JacksonSerializer();
        statefulRepository = new StatefulProcessRepository(
                new PaymentProcess("handler-1", "process-1", 0L));
        statelessHandler = new StatelessEventHandler();

        List<ParameterResolver<? super DeserializingMessage>> parameterResolvers = List.of(
                new TriggerParameterResolver(null, null), new MessageParameterResolver(),
                new MetadataParameterResolver(), new TimestampParameterResolver(), new PayloadParameterResolver(),
                new JsonPayloadParameterResolver(), new EntityParameterResolver());
        HandlerDecorator handlerStack = new ErrorReportingInterceptor(null)
                .andThen(new HandlerMonitor())
                .andThen(new DataProtectionInterceptor(
                        null, serializer, MissingProtectedDataPolicy.HANDLE, true))
                .andThen(new ContentFilterInterceptor(serializer));
        DefaultHandlerFactory handlerFactory = new DefaultHandlerFactory(
                MessageType.EVENT, handlerStack, parameterResolvers, MethodInvocationValidator.noOp(),
                ignored -> statefulRepository, new InMemoryRepositoryProvider(), true, serializer);

        handlers = List.of(
                handlerFactory.createHandler(statelessHandler, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow(),
                handlerFactory.createHandler(PaymentProcess.class, HandlerFilter.ALWAYS_HANDLE, List.of())
                        .orElseThrow());
        tracking = new DefaultTracking(
                MessageType.EVENT, null, List.of(), List.of(), serializer, handlerFactory);
        configuration = ConsumerConfiguration.builder()
                .name("benchmark-tracking")
                .handlingMode(ConsumerHandlingMode.SYNC)
                .awaitAsyncResults(true)
                .build();
        serializedMessages = createMessages();

        long statelessBefore = statelessHandler.invocations;
        long statefulBefore = statefulRepository.total();
        trackingHandling();
        if (statelessHandler.invocations <= statelessBefore || statefulRepository.total() <= statefulBefore) {
            throw new IllegalStateException("Tracking scenario did not invoke both stateless and stateful handlers");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        tracking.close();
    }

    /**
     * Deserializes and handles one batch containing synchronous, completed-asynchronous, and stateful event handlers.
     * Result publication and external position storage are disabled; both would measure an I/O boundary instead.
     */
    @Benchmark
    public long trackingHandling() {
        List<DeserializingMessage> messages = tracking.deserializeMessageList(
                serializedMessages, null, null, new ConcurrentHashMap<>(), BATCH_SIZE);
        tracking.handleBatch(messages, handlers, configuration, false);
        return statelessHandler.invocations + statefulRepository.total();
    }

    private List<SerializedMessage> createMessages() {
        return java.util.stream.IntStream.range(0, BATCH_SIZE).mapToObj(i -> {
            Object payload = switch (i & 3) {
                case 0 -> new OrderObserved("order-" + (i & 7), i);
                case 1 -> new AsyncProjectionUpdated("projection-" + (i & 7), i);
                case 2 -> new PaymentObserved("process-1", i * 10L);
                default -> new CustomerObserved("customer-" + (i & 7), "name-" + i);
            };
            SerializedMessage message = new SerializedMessage(
                    serializer.serialize(payload), Metadata.of("benchmark", "true"),
                    "tracking-message-" + i, 1_700_000_000_000L + i);
            message.setIndex((long) i);
            message.setSegment(i & 3);
            return message;
        }).toList();
    }

    record OrderObserved(String orderId, int line) {
    }

    record AsyncProjectionUpdated(String projectionId, int version) {
    }

    record PaymentObserved(String processId, long amount) {
    }

    record CustomerObserved(String customerId, String name) {
    }

    static class StatelessEventHandler {
        private long invocations;

        @HandleEvent
        void handle(OrderObserved event, Metadata metadata) {
            invocations += event.line() + metadata.getEntries().size();
        }

        @HandleEvent
        CompletionStage<Void> handle(AsyncProjectionUpdated event) {
            invocations += event.version();
            return CompletableFuture.completedFuture(null);
        }

        @HandleEvent
        void handle(CustomerObserved event, Instant timestamp) {
            invocations += event.name().length() + timestamp.getEpochSecond();
        }
    }

    @Stateful
    record PaymentProcess(@EntityId String id, @Association String processId, long total) {
        @HandleEvent
        PaymentProcess handle(PaymentObserved event) {
            return new PaymentProcess(id, processId, total + event.amount());
        }
    }

    private record ProcessEntry(String id, Object value) implements Entry<Object> {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public Object getValue() {
            return value;
        }
    }

    private static class StatefulProcessRepository implements HandlerRepository {
        private volatile PaymentProcess process;

        private StatefulProcessRepository(PaymentProcess process) {
            this.process = process;
        }

        @Override
        public Collection<? extends Entry<?>> findByAssociation(Map<Object, String> associations) {
            boolean matches = associations.entrySet().stream().anyMatch(
                    entry -> "processId".equals(entry.getValue()) && process.processId().equals(entry.getKey()));
            return matches ? List.of(new ProcessEntry(process.id(), process)) : List.of();
        }

        @Override
        public Collection<? extends Entry<?>> getAll() {
            return List.of(new ProcessEntry(process.id(), process));
        }

        @Override
        public CompletableFuture<?> put(Object id, Object value) {
            process = (PaymentProcess) value;
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletableFuture<?> delete(Object id) {
            return CompletableFuture.completedFuture(null);
        }

        long total() {
            return process.total();
        }
    }

    private static class InMemoryRepositoryProvider implements RepositoryProvider {
        private final Map<Class<?>, Map<Object, ?>> repositories = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Map<Object, T> getRepository(Class<T> repositoryClass) {
            return (Map<Object, T>) repositories.computeIfAbsent(repositoryClass, ignored -> new ConcurrentHashMap<>());
        }
    }
}
