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
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.ClientDispatchMonitor;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EntityParameterResolver;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor;
import io.fluxzero.sdk.publishing.dataprotection.MissingProtectedDataPolicy;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Application-shaped tracked handling scenarios used by the pull-request performance gate. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms384m", "-Xmx384m", "-XX:+AlwaysPreTouch"})
public class TrackingHotPathBenchmark {
    private static final int BATCH_SIZE = 32;
    private static final int TRACKED_ROUTE_COUNT = 4;
    private static final int AGGREGATE_COUNT = 8;

    private JacksonSerializer serializer;
    private Fluxzero fluxzero;
    private MetricsSinkGatewayClient metricsSink;
    private DefaultTracking eventTracking;
    private DefaultTracking commandTracking;

    private ConsumerConfiguration statefulEventConfiguration;
    private ConsumerConfiguration selfTrackedCommandConfiguration;
    private ConsumerConfiguration standaloneCommandConfiguration;
    private ConsumerConfiguration standaloneEventConfiguration;

    private Tracker statefulEventTracker;
    private Tracker selfTrackedCommandTracker;
    private Tracker standaloneCommandTracker;
    private Tracker standaloneEventTracker;

    private List<SerializedMessage> statefulEventMessages;
    private List<SerializedMessage> selfTrackedCommandMessages;
    private List<SerializedMessage> standaloneCommandMessages;
    private List<SerializedMessage> standaloneEventMessages;

    private List<Handler<DeserializingMessage>> statefulEventHandlers;
    private List<Handler<DeserializingMessage>> selfTrackedCommandHandlers;
    private List<Handler<DeserializingMessage>> standaloneCommandHandlers;
    private List<Handler<DeserializingMessage>> standaloneEventHandlers;

    private StatelessEventHandler statelessHandler;
    private StatefulProcessRepository statefulRepository;
    private StandaloneCommandHandler standaloneCommandHandler;
    private StandaloneEventHandler standaloneEventHandler;

    @Setup(Level.Trial)
    public void setUp() {
        serializer = new JacksonSerializer();
        metricsSink = new MetricsSinkGatewayClient();
        // Entity injection uses the default repository cache; its asynchronous cache-sync tracker is out of scope.
        fluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableAutomaticAggregateCaching()
                .disableShutdownHook()
                .disableKeepalive()
                .build(new MetricsSinkClient(LocalClient.newInstance(Duration.ofMinutes(5)), metricsSink));
        seedAggregates();

        statefulRepository = new StatefulProcessRepository(
                new PaymentProcess("handler-1", "process-1", 0L));
        statelessHandler = new StatelessEventHandler();
        standaloneCommandHandler = new StandaloneCommandHandler();
        standaloneEventHandler = new StandaloneEventHandler();
        SelfTrackedProbe.reset();

        DefaultHandlerFactory eventHandlerFactory = createHandlerFactory(MessageType.EVENT);
        DefaultHandlerFactory commandHandlerFactory = createHandlerFactory(MessageType.COMMAND);

        statefulEventHandlers = List.of(
                createHandler(eventHandlerFactory, statelessHandler),
                createHandler(eventHandlerFactory, PaymentProcess.class));
        selfTrackedCommandHandlers = List.of(createHandler(commandHandlerFactory, SelfTrackedCommand.class));
        standaloneCommandHandlers = List.of(createHandler(commandHandlerFactory, standaloneCommandHandler));
        standaloneEventHandlers = List.of(createHandler(eventHandlerFactory, standaloneEventHandler));

        eventTracking = new DefaultTracking(
                MessageType.EVENT, null, List.of(), List.of(), serializer, eventHandlerFactory);
        commandTracking = new DefaultTracking(
                MessageType.COMMAND, null, List.of(), List.of(), serializer, commandHandlerFactory);

        statefulEventConfiguration = configuration("benchmark-stateful-events");
        selfTrackedCommandConfiguration = configuration("benchmark-self-tracked-commands");
        standaloneCommandConfiguration = configuration("benchmark-standalone-commands");
        standaloneEventConfiguration = configuration("benchmark-standalone-events");
        statefulEventTracker = tracker(MessageType.EVENT, statefulEventConfiguration);
        selfTrackedCommandTracker = tracker(MessageType.COMMAND, selfTrackedCommandConfiguration);
        standaloneCommandTracker = tracker(MessageType.COMMAND, standaloneCommandConfiguration);
        standaloneEventTracker = tracker(MessageType.EVENT, standaloneEventConfiguration);

        statefulEventMessages = createStatefulEventMessages();
        selfTrackedCommandMessages = createMessages("self-command", i -> switch (i & 3) {
            case 0 -> new CreateSelfTrackedOrder(i);
            case 1 -> new AmendSelfTrackedOrder(i);
            case 2 -> new ApproveSelfTrackedOrder(i);
            default -> new CancelSelfTrackedOrder(i);
        }, false);
        standaloneCommandMessages = createMessages("standalone-command", i -> switch (i & 3) {
            case 0 -> new CreateTrackedOrder(i);
            case 1 -> new AmendTrackedOrder(i);
            case 2 -> new ApproveTrackedOrder(i);
            default -> new CancelTrackedOrder(i);
        }, false);
        standaloneEventMessages = createMessages("standalone-event", i -> switch (i & 3) {
            case 0 -> new OrderShipped(i);
            case 1 -> new PaymentSettled(i);
            case 2 -> new StockAdjusted(i);
            default -> new CustomerNotified(i);
        }, true);

        verifyScenarios();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        commandTracking.close();
        eventTracking.close();
        fluxzero.close(true);
    }

    /**
     * Exercises the representative tracked handling routes as one application-shaped acceptance scenario. Combining
     * the routes makes the regression signal less sensitive to short-lived GitHub runner noise while retaining every
     * route-specific setup assertion.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE * TRACKED_ROUTE_COUNT)
    @Warmup(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
    @Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    public long trackedHandlingRoutes() {
        selfTrackedCommandHandling();
        standaloneTrackedCommandHandling();
        standaloneTrackedEventHandling();
        statefulAndStandaloneTrackedEventHandling();
        return SelfTrackedProbe.checksum + standaloneCommandHandler.checksum + standaloneEventHandler.checksum
               + statelessHandler.invocations + statefulRepository.total() + metricsSink.checksum();
    }

    /** Handles concrete tracked commands through a {@link TrackSelf} interface carrying the handler method. */
    private long selfTrackedCommandHandling() {
        handleBatch(commandTracking, selfTrackedCommandMessages, selfTrackedCommandHandlers,
                    selfTrackedCommandConfiguration, selfTrackedCommandTracker);
        return SelfTrackedProbe.checksum;
    }

    /** Handles mixed tracked commands through one standalone handler with several {@link HandleCommand} methods. */
    private long standaloneTrackedCommandHandling() {
        handleBatch(commandTracking, standaloneCommandMessages, standaloneCommandHandlers,
                    standaloneCommandConfiguration, standaloneCommandTracker);
        return standaloneCommandHandler.checksum;
    }

    /**
     * Handles mixed tracked events through a multi-method standalone handler. Each method resolves several context
     * parameters and injects either {@code Entity<TrackedAccount>} or {@code TrackedAccount}.
     */
    private long standaloneTrackedEventHandling() {
        handleBatch(eventTracking, standaloneEventMessages, standaloneEventHandlers,
                    standaloneEventConfiguration, standaloneEventTracker);
        return standaloneEventHandler.checksum;
    }

    /**
     * Preserves the original mixed tracked-event scenario: a multi-method standalone handler plus a
     * {@link Stateful} handler repository, with synchronous and already-completed asynchronous results.
     */
    private long statefulAndStandaloneTrackedEventHandling() {
        handleBatch(eventTracking, statefulEventMessages, statefulEventHandlers,
                    statefulEventConfiguration, statefulEventTracker);
        return statelessHandler.invocations + statefulRepository.total();
    }

    private void handleBatch(DefaultTracking tracking, List<SerializedMessage> serializedMessages,
                             List<Handler<DeserializingMessage>> handlers, ConsumerConfiguration configuration,
                             Tracker tracker) {
        fluxzero.apply(ignored -> {
            Tracker previous = Tracker.current.get();
            Tracker.current.set(tracker);
            try {
                List<DeserializingMessage> messages = tracking.deserializeMessageList(
                        serializedMessages, null, null, new ConcurrentHashMap<>(), BATCH_SIZE);
                tracking.handleBatch(messages, handlers, configuration, false);
            } finally {
                if (previous == null) {
                    Tracker.current.remove();
                } else {
                    Tracker.current.set(previous);
                }
            }
            return null;
        });
    }

    private void verifyScenarios() {
        long statelessBefore = statelessHandler.invocations;
        long statefulBefore = statefulRepository.total();
        statefulAndStandaloneTrackedEventHandling();
        if (statelessHandler.invocations <= statelessBefore || statefulRepository.total() <= statefulBefore) {
            throw new IllegalStateException("Stateful tracking scenario did not invoke both handler types");
        }

        long selfTrackedBefore = SelfTrackedProbe.checksum;
        selfTrackedCommandHandling();
        if (SelfTrackedProbe.checksum <= selfTrackedBefore) {
            throw new IllegalStateException("Self-tracked command scenario did not invoke the interface handler");
        }

        long standaloneCommandBefore = standaloneCommandHandler.checksum;
        standaloneTrackedCommandHandling();
        if (standaloneCommandHandler.checksum <= standaloneCommandBefore) {
            throw new IllegalStateException("Standalone tracked command scenario did not invoke its handler");
        }

        long standaloneEventBefore = standaloneEventHandler.checksum;
        long metricsBefore = metricsSink.checksum();
        standaloneTrackedEventHandling();
        if (standaloneEventHandler.checksum <= standaloneEventBefore
            || standaloneEventHandler.entityInvocations != BATCH_SIZE || metricsSink.checksum() <= metricsBefore) {
            throw new IllegalStateException(
                    "Standalone tracked event scenario did not inject every entity and publish handler metrics");
        }
    }

    private DefaultHandlerFactory createHandlerFactory(MessageType messageType) {
        List<ParameterResolver<? super DeserializingMessage>> parameterResolvers = List.of(
                new TriggerParameterResolver(null, null), new MessageParameterResolver(),
                new MetadataParameterResolver(), new TimestampParameterResolver(), new PayloadParameterResolver(),
                new JsonPayloadParameterResolver(), new EntityParameterResolver());
        HandlerDecorator handlerStack = new ErrorReportingInterceptor(null)
                .andThen(new HandlerMonitor())
                .andThen(new DataProtectionInterceptor(
                        null, serializer, MissingProtectedDataPolicy.HANDLE, true))
                .andThen(new ContentFilterInterceptor(serializer));
        return new DefaultHandlerFactory(
                messageType, handlerStack, parameterResolvers, MethodInvocationValidator.noOp(),
                ignored -> statefulRepository, new InMemoryRepositoryProvider(), true, serializer);
    }

    private static Handler<DeserializingMessage> createHandler(DefaultHandlerFactory factory, Object target) {
        return factory.createHandler(target, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
    }

    private static ConsumerConfiguration configuration(String name) {
        return ConsumerConfiguration.builder()
                .name(name)
                .handlingMode(ConsumerHandlingMode.SYNC)
                .awaitAsyncResults(true)
                .build();
    }

    private static Tracker tracker(MessageType messageType, ConsumerConfiguration configuration) {
        return new Tracker(configuration.getName(), messageType, null, configuration, null);
    }

    private void seedAggregates() {
        fluxzero.apply(fc -> {
            for (int i = 0; i < AGGREGATE_COUNT; i++) {
                String id = "account-" + i;
                long balance = 1_000L + i;
                fc.aggregateRepository().load(id, TrackedAccount.class)
                        .update(ignored -> new TrackedAccount(id, balance)).commit();
            }
            return null;
        });
    }

    private List<SerializedMessage> createStatefulEventMessages() {
        return createMessages("stateful-event", i -> switch (i & 3) {
            case 0 -> new OrderObserved("order-" + (i & 7), i);
            case 1 -> new AsyncProjectionUpdated("projection-" + (i & 7), i);
            case 2 -> new PaymentObserved("process-1", i * 10L);
            default -> new CustomerObserved("customer-" + (i & 7), "name-" + i);
        }, false);
    }

    private List<SerializedMessage> createMessages(String prefix, IntFunction<Object> payloadSupplier,
                                                    boolean includeAggregateMetadata) {
        return java.util.stream.IntStream.range(0, BATCH_SIZE).mapToObj(i -> {
            Metadata metadata = includeAggregateMetadata
                    ? Metadata.of("benchmark", "true",
                                  Entity.AGGREGATE_ID_METADATA_KEY, "account-" + (i & 7),
                                  Entity.AGGREGATE_TYPE_METADATA_KEY, TrackedAccount.class.getName())
                    : Metadata.of("benchmark", "true");
            SerializedMessage message = new SerializedMessage(
                    serializer.serialize(payloadSupplier.apply(i)), metadata,
                    prefix + "-" + i, 1_700_000_000_000L + i);
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

    @TrackSelf
    interface SelfTrackedCommand {
        int value();

        @HandleCommand
        default void handle(Metadata metadata) {
            SelfTrackedProbe.checksum += value() + metadata.getEntries().size()
                                         + Tracker.current().orElseThrow().getName().length();
        }
    }

    record CreateSelfTrackedOrder(int value) implements SelfTrackedCommand {
    }

    record AmendSelfTrackedOrder(int value) implements SelfTrackedCommand {
    }

    record ApproveSelfTrackedOrder(int value) implements SelfTrackedCommand {
    }

    record CancelSelfTrackedOrder(int value) implements SelfTrackedCommand {
    }

    static class SelfTrackedProbe {
        private static long checksum;

        private static void reset() {
            checksum = 0L;
        }
    }

    record CreateTrackedOrder(int value) {
    }

    record AmendTrackedOrder(int value) {
    }

    record ApproveTrackedOrder(int value) {
    }

    record CancelTrackedOrder(int value) {
    }

    static class StandaloneCommandHandler {
        private long checksum;

        @HandleCommand
        void handle(CreateTrackedOrder command) {
            checksum += command.value() + 1L;
        }

        @HandleCommand
        void handle(AmendTrackedOrder command, Metadata metadata) {
            checksum += command.value() + metadata.getEntries().size();
        }

        @HandleCommand
        void handle(ApproveTrackedOrder command, DeserializingMessage message) {
            checksum += command.value() + message.getMessageId().length();
        }

        @HandleCommand
        void handle(CancelTrackedOrder command, Instant timestamp) {
            checksum += command.value() + timestamp.getEpochSecond();
        }
    }

    record OrderShipped(int value) {
    }

    record PaymentSettled(int value) {
    }

    record StockAdjusted(int value) {
    }

    record CustomerNotified(int value) {
    }

    static class StandaloneEventHandler {
        private long checksum;
        private long entityInvocations;

        @HandleEvent
        void handle(OrderShipped event, Entity<TrackedAccount> entity, Metadata metadata,
                    DeserializingMessage message) {
            checksum += event.value() + entity.get().balance() + metadata.getEntries().size()
                        + message.getMessageId().length();
            entityInvocations++;
        }

        @HandleEvent
        void handle(PaymentSettled event, TrackedAccount account, Metadata metadata, Instant timestamp) {
            checksum += event.value() + account.balance() + metadata.getEntries().size() + timestamp.getEpochSecond();
            entityInvocations++;
        }

        @HandleEvent
        void handle(StockAdjusted event, Entity<TrackedAccount> entity, Instant timestamp,
                    DeserializingMessage message) {
            checksum += event.value() + entity.get().balance() + timestamp.getEpochSecond()
                        + message.getMessageId().length();
            entityInvocations++;
        }

        @HandleEvent
        void handle(CustomerNotified event, TrackedAccount account, Metadata metadata,
                    DeserializingMessage message) {
            checksum += event.value() + account.balance() + metadata.getEntries().size()
                        + message.getMessageId().length();
            entityInvocations++;
        }
    }

    @Aggregate(eventSourced = false, searchable = true)
    record TrackedAccount(@EntityId String id, long balance) {
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
            return (Map<Object, T>) repositories.computeIfAbsent(repositoryClass,
                                                                  ignored -> new ConcurrentHashMap<>());
        }
    }

    private static class MetricsSinkGatewayClient implements GatewayClient {
        private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);
        private volatile long checksum;

        @Override
        public CompletableFuture<Void> append(Guarantee guarantee, SerializedMessage... messages) {
            long updated = checksum;
            for (SerializedMessage message : messages) {
                updated += message.getBytes();
                updated += message.getMetadata().getEntries().size();
            }
            checksum = updated;
            return COMPLETED;
        }

        long checksum() {
            return checksum;
        }

        @Override
        public Registration registerMonitor(Consumer<List<SerializedMessage>> monitor) {
            return Registration.noOp();
        }

        @Override
        public CompletableFuture<Void> setRetentionTime(Duration duration, Guarantee guarantee) {
            return COMPLETED;
        }

        @Override
        public CompletableFuture<Void> truncate(Guarantee guarantee) {
            return COMPLETED;
        }

        @Override
        public void close() {
        }
    }

    /** Delegates SDK storage to the local client while preventing benchmark metrics from accumulating in memory. */
    private record MetricsSinkClient(LocalClient delegate, MetricsSinkGatewayClient metricsSink) implements Client {
        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public String applicationId() {
            return delegate.applicationId();
        }

        @Override
        public String namespace() {
            return delegate.namespace();
        }

        @Override
        public Client forNamespace(String namespace) {
            return new MetricsSinkClient((LocalClient) delegate.forNamespace(namespace), metricsSink);
        }

        @Override
        public GatewayClient getGatewayClient(MessageType messageType, String topic) {
            return messageType == MessageType.METRICS ? metricsSink : delegate.getGatewayClient(messageType, topic);
        }

        @Override
        public Registration monitorDispatch(ClientDispatchMonitor monitor, MessageType... messageTypes) {
            return delegate.monitorDispatch(monitor, messageTypes);
        }

        @Override
        public TrackingClient getTrackingClient(MessageType messageType, String topic) {
            return delegate.getTrackingClient(messageType, topic);
        }

        @Override
        public EventStoreClient getEventStoreClient() {
            return delegate.getEventStoreClient();
        }

        @Override
        public SchedulingClient getSchedulingClient() {
            return delegate.getSchedulingClient();
        }

        @Override
        public KeyValueClient getKeyValueClient() {
            return delegate.getKeyValueClient();
        }

        @Override
        public SearchClient getSearchClient() {
            return delegate.getSearchClient();
        }

        @Override
        public void shutDown() {
            metricsSink.close();
            delegate.shutDown();
        }

        @Override
        public Registration beforeShutdown(Runnable task) {
            return delegate.beforeShutdown(task);
        }
    }
}
