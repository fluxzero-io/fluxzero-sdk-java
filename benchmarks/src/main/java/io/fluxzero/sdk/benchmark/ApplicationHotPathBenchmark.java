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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.ClientDispatchMonitor;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.EventGateway;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Application-shaped dispatch and local command scenarios used by the pull-request performance gate. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms384m", "-Xmx384m", "-XX:+AlwaysPreTouch"})
public class ApplicationHotPathBenchmark {
    private static final int BATCH_SIZE = 32;

    private final Object[] dispatchEvents = createDispatchEvents();
    private final Object[] localCommands = createLocalCommands();

    private SinkGatewayClient sink;
    private Fluxzero dispatchFluxzero;
    private Fluxzero localFluxzero;
    private EventGateway eventGateway;
    private CommandGateway commandGateway;

    @Setup(Level.Trial)
    public void setUp() {
        sink = new SinkGatewayClient();
        dispatchFluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableShutdownHook()
                .build(new SinkClient(LocalClient.newInstance(Duration.ofMinutes(5)), sink));
        eventGateway = dispatchFluxzero.eventGateway();

        localFluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableShutdownHook()
                .build(LocalClient.newInstance(Duration.ofMinutes(5)));
        commandGateway = localFluxzero.commandGateway();
        commandGateway.registerHandler(new MixedCommandHandler());

        long checksumBefore = sink.checksum();
        outboundDispatch();
        if (sink.checksum() <= checksumBefore || sink.lastBatchSize() != BATCH_SIZE) {
            throw new IllegalStateException("Outbound dispatch did not reach the serialized-message sink");
        }
        long commandResult = localCommandHandling();
        if (commandResult != 640L) {
            throw new IllegalStateException("Unexpected local command result: " + commandResult);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        localFluxzero.close();
        dispatchFluxzero.close();
    }

    /**
     * Dispatches a mixed event batch through message construction, dispatch interceptors, routing, serialization,
     * monitoring, and the low-level client boundary. The sink deliberately performs no transport I/O.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public long outboundDispatch() {
        eventGateway.publish(Guarantee.NONE, dispatchEvents).join();
        return sink.checksum();
    }

    /**
     * Handles a mixed command batch through the public local gateway and its default interceptor stack. Both regular
     * and already-completed asynchronous handler results are represented.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public long localCommandHandling() {
        long result = 0L;
        for (Object command : localCommands) {
            CommandResult commandResult = commandGateway.sendAndWait(command);
            result += commandResult.value();
        }
        return result;
    }

    private static Object[] createDispatchEvents() {
        Object[] result = new Object[BATCH_SIZE];
        for (int i = 0; i < result.length; i++) {
            result[i] = switch (i & 3) {
                case 0 -> new OrderAccepted("order-" + (i & 7), i, "accepted");
                case 1 -> new PaymentCaptured("payment-" + (i & 7), i * 10L);
                case 2 -> new StockReserved("stock-" + (i & 7), "item-" + i, i + 1);
                default -> new CustomerNotified("customer-" + (i & 7), "message-" + i);
            };
        }
        return result;
    }

    private static Object[] createLocalCommands() {
        Object[] result = new Object[BATCH_SIZE];
        for (int i = 0; i < result.length; i++) {
            result[i] = switch (i & 7) {
                case 0 -> new CreateOrder(i);
                case 1 -> new AmendOrder(i);
                case 2 -> new ApproveOrder(i);
                case 3 -> new RejectOrder(i);
                case 4 -> new CapturePayment(i);
                case 5 -> new RefundPayment(i);
                case 6 -> new ReserveStock(i);
                default -> new ReleaseStock(i);
            };
        }
        return result;
    }

    record OrderAccepted(@RoutingKey String orderId, int lineCount, String status) {
    }

    record PaymentCaptured(@RoutingKey String paymentId, long amount) {
    }

    record StockReserved(@RoutingKey String reservationId, String itemId, int quantity) {
    }

    record CustomerNotified(@RoutingKey String customerId, String text) {
    }

    record CreateOrder(int value) {
    }

    record AmendOrder(int value) {
    }

    record ApproveOrder(int value) {
    }

    record RejectOrder(int value) {
    }

    record CapturePayment(int value) {
    }

    record RefundPayment(int value) {
    }

    record ReserveStock(int value) {
    }

    record ReleaseStock(int value) {
    }

    record CommandResult(long value) {
    }

    @LocalHandler
    static class MixedCommandHandler {
        @HandleCommand
        CommandResult handle(CreateOrder command) {
            return new CommandResult(command.value() + 1L);
        }

        @HandleCommand
        CommandResult handle(AmendOrder command) {
            return new CommandResult(command.value() + 2L);
        }

        @HandleCommand
        CompletionStage<CommandResult> handle(ApproveOrder command) {
            return CompletableFuture.completedFuture(new CommandResult(command.value() + 3L));
        }

        @HandleCommand
        CommandResult handle(RejectOrder command) {
            return new CommandResult(command.value() + 4L);
        }

        @HandleCommand
        CommandResult handle(CapturePayment command) {
            return new CommandResult(command.value() + 5L);
        }

        @HandleCommand
        CompletionStage<CommandResult> handle(RefundPayment command) {
            return CompletableFuture.completedFuture(new CommandResult(command.value() + 6L));
        }

        @HandleCommand
        CommandResult handle(ReserveStock command) {
            return new CommandResult(command.value() + 7L);
        }

        @HandleCommand
        CommandResult handle(ReleaseStock command) {
            return new CommandResult(command.value() + 8L);
        }
    }

    private static class SinkGatewayClient implements GatewayClient {
        private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);
        private volatile long checksum;
        private volatile int lastBatchSize;

        @Override
        public CompletableFuture<Void> append(Guarantee guarantee, SerializedMessage... messages) {
            long updated = checksum;
            for (SerializedMessage message : messages) {
                updated += message.getBytes();
                updated += message.getSegment() == null ? 0 : message.getSegment();
                updated += message.getMetadata().getEntries().size();
            }
            checksum = updated;
            lastBatchSize = messages.length;
            return COMPLETED;
        }

        long checksum() {
            return checksum;
        }

        int lastBatchSize() {
            return lastBatchSize;
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

    /** Uses the real in-memory SDK subsystems but replaces gateway transport with a non-buffering sink. */
    private record SinkClient(LocalClient delegate, SinkGatewayClient sink) implements Client {
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
            return this;
        }

        @Override
        public GatewayClient getGatewayClient(MessageType messageType, String topic) {
            return sink;
        }

        @Override
        public Registration monitorDispatch(ClientDispatchMonitor monitor, MessageType... messageTypes) {
            return Registration.noOp();
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
            sink.close();
            delegate.shutDown();
        }

        @Override
        public Registration beforeShutdown(Runnable task) {
            return delegate.beforeShutdown(task);
        }
    }
}
