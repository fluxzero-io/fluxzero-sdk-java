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

package io.fluxzero.sdk.publishing.dataprotection;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.keyvalue.client.InMemoryKeyValueStore;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.publishing.LocalOnly;
import io.fluxzero.sdk.publishing.LocalOnlyDispatchException;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.COMMAND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Isolated
class LocalProtectedDataDispatchTest {

    @Test
    void localCommandsAvoidKeyValueIoForSyncAsyncBulkAndFireAndForget() {
        CountingLocalClient client = new CountingLocalClient();
        LocalCommandHandler handler = new LocalCommandHandler();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            fluxzero.commandGateway().registerHandler(handler);

            assertEquals("one", fluxzero.commandGateway().sendAndWait(new ProtectedCommand("one")));
            assertEquals("two", fluxzero.commandGateway().<String>send(new ProtectedCommand("two")).join());
            List<CompletableFuture<String>> bulk = fluxzero.commandGateway().send(
                    new ProtectedCommand("three"), new ProtectedCommand("four"));
            assertEquals(List.of("three", "four"), bulk.stream().map(CompletableFuture::join).toList());
            fluxzero.commandGateway().sendAndForget(
                    Guarantee.STORED, new ProtectedCommand("five")).join();

            assertEquals(5, handler.invocations.get());
            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    @Test
    void localEventsAndDropProtectedDataAvoidKeyValueIo() {
        CountingLocalClient client = new CountingLocalClient();
        DroppingEventHandler handler = new DroppingEventHandler();
        ObservingEventHandler observer = new ObservingEventHandler();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            fluxzero.eventGateway().registerHandler(handler);
            fluxzero.eventGateway().registerHandler(observer);

            fluxzero.eventGateway().publish(Guarantee.STORED, new ProtectedEvent("secret")).join();

            assertEquals("secret", handler.value);
            assertNull(observer.value);
            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    @Test
    void localQueriesAvoidKeyValueIo() {
        CountingLocalClient client = new CountingLocalClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            fluxzero.queryGateway().registerHandler(new LocalQueryHandler());

            assertEquals("sync", fluxzero.queryGateway().sendAndWait(new ProtectedQuery("sync")));
            assertEquals("async", fluxzero.queryGateway().<String>send(new ProtectedQuery("async")).join());

            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    @Test
    void localOnlyMissingHandlerDoesNotTouchKeyValueStorage() {
        CountingLocalClient client = new CountingLocalClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            CompletionException exception = assertThrows(
                    CompletionException.class,
                    () -> fluxzero.commandGateway().send(new ProtectedLocalOnlyCommand("secret")).join());

            assertInstanceOf(LocalOnlyDispatchException.class, exception.getCause());
            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    @Test
    void externalFallbackStoresProtectedDataWithoutReadingItLocally() {
        CountingLocalClient client = new CountingLocalClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            fluxzero.commandGateway().sendAndForget(
                    Guarantee.STORED, new ExternallyHandledCommand("secret")).join();

            client.assertKeyValueCalls(1, 0, 0);
            assertEquals(1, client.getTrackingClient(COMMAND).readFromIndex(0, 10).size());
        }
    }

    @Test
    void logMessageExternalizesBeforeInvokingLocalHandler() {
        CountingLocalClient client = new CountingLocalClient();
        PublishingLocalHandler handler = new PublishingLocalHandler();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            fluxzero.commandGateway().registerHandler(handler);

            assertEquals("secret", fluxzero.apply(
                    fc -> fc.commandGateway().sendAndWait(new PublishedCommand("secret"))));

            client.assertKeyValueCalls(1, 1, 0);
            assertEquals(1, client.getTrackingClient(COMMAND).readFromIndex(0, 10).size());
            assertFalse(new String(client.getTrackingClient(COMMAND).readFromIndex(0, 10)
                                           .getFirst().getData().getValue(), UTF_8).contains("secret"));
        }
    }

    @Test
    void localOnlySuppressesLogMessageExternalization() {
        CountingLocalClient client = new CountingLocalClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(client)) {
            fluxzero.commandGateway().registerHandler(new LocalOnlyPublishingHandler());

            assertEquals("secret", fluxzero.apply(fc -> fc.commandGateway().sendAndWait(
                    new PublishedLocalOnlyCommand("secret"))));

            client.assertKeyValueCalls(0, 0, 0);
            assertEquals(0, client.getTrackingClient(COMMAND).readFromIndex(0, 10).size());
        }
    }

    @Test
    void interceptorReplacementKeepsDeferredDataAvailableLocally() {
        CountingLocalClient client = new CountingLocalClient();
        var builder = DefaultFluxzero.builder().addDispatchInterceptor((message, type, topic) ->
                message.getPayload() instanceof ReplacementSource
                        ? new io.fluxzero.sdk.common.Message(
                                new ReplacementTarget(null), message.getMetadata(),
                                message.getMessageId(), message.getTimestamp()) : message, COMMAND);
        try (Fluxzero fluxzero = builder.build(client)) {
            fluxzero.commandGateway().registerHandler(new ReplacementHandler());

            assertEquals("secret", fluxzero.commandGateway().sendAndWait(new ReplacementSource("secret")));

            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    @Test
    void interceptorSuppressionDoesNotExternalizeDeferredData() {
        CountingLocalClient client = new CountingLocalClient();
        var builder = DefaultFluxzero.builder().addDispatchInterceptor((message, type, topic) ->
                message.getPayload() instanceof SuppressedCommand ? null : message, COMMAND);
        try (Fluxzero fluxzero = builder.build(client)) {
            assertNull(fluxzero.commandGateway().sendAndWait(new SuppressedCommand("secret")));

            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    @Test
    void preservesCustomMessageSubtypeForLaterInterceptorsAndHandlerParameters() {
        CountingLocalClient client = new CountingLocalClient();
        var builder = DefaultFluxzero.builder().addDispatchInterceptor((message, type, topic) -> {
            if (message.getPayload() instanceof ProtectedCommand) {
                assertInstanceOf(ProtectedCommandMessage.class, message);
            }
            return message;
        }, COMMAND);
        try (Fluxzero fluxzero = builder.build(client)) {
            fluxzero.commandGateway().registerHandler(new MessageSubtypeHandler());

            assertEquals("secret", fluxzero.commandGateway().sendAndWait(
                    new ProtectedCommandMessage(new ProtectedCommand("secret"))));

            client.assertKeyValueCalls(0, 0, 0);
        }
    }

    private record ProtectedCommand(@ProtectData String value) {
    }

    private record ProtectedEvent(@ProtectData String value) {
    }

    private record ProtectedQuery(@ProtectData String value) {
    }

    @LocalOnly
    private record ProtectedLocalOnlyCommand(@ProtectData String value) {
    }

    private record ExternallyHandledCommand(@ProtectData String value) {
    }

    @Value
    private static class PublishedCommand {
        @ProtectData
        String value;
    }

    @LocalOnly
    private record PublishedLocalOnlyCommand(@ProtectData String value) {
    }

    private record ReplacementSource(@ProtectData String value) {
    }

    private record ReplacementTarget(String value) {
    }

    private record SuppressedCommand(@ProtectData String value) {
    }

    private static class ProtectedCommandMessage extends Message {
        private ProtectedCommandMessage(Object payload) {
            super(payload);
        }

        private ProtectedCommandMessage(Object payload, Metadata metadata, String messageId, Instant timestamp) {
            super(payload, metadata, messageId, timestamp);
        }

        @Override
        public ProtectedCommandMessage withPayload(Object payload) {
            return payload == getPayload() ? this
                    : new ProtectedCommandMessage(payload, getMetadata(), getMessageId(), getTimestamp());
        }

        @Override
        public ProtectedCommandMessage withMetadata(Metadata metadata) {
            return new ProtectedCommandMessage(getPayload(), metadata, getMessageId(), getTimestamp());
        }

        @Override
        public ProtectedCommandMessage withMessageId(String messageId) {
            return new ProtectedCommandMessage(getPayload(), getMetadata(), messageId, getTimestamp());
        }

        @Override
        public ProtectedCommandMessage withTimestamp(Instant timestamp) {
            return new ProtectedCommandMessage(getPayload(), getMetadata(), getMessageId(), timestamp);
        }
    }

    @LocalHandler
    private static class LocalCommandHandler {
        private final AtomicInteger invocations = new AtomicInteger();

        @HandleCommand
        String handle(ProtectedCommand command) {
            invocations.incrementAndGet();
            return command.value();
        }
    }

    @LocalHandler
    private static class DroppingEventHandler {
        private String value;

        @HandleEvent
        @DropProtectedData
        void handle(ProtectedEvent event) {
            value = event.value();
        }
    }

    @LocalHandler
    private static class ObservingEventHandler {
        private String value;

        @HandleEvent
        void handle(ProtectedEvent event) {
            value = event.value();
        }
    }

    @LocalHandler
    private static class LocalQueryHandler {
        @HandleQuery
        CompletableFuture<String> handle(ProtectedQuery query) {
            return CompletableFuture.completedFuture(query.value());
        }
    }

    @LocalHandler(logMessage = true)
    private static class PublishingLocalHandler {
        @HandleCommand
        String handle(PublishedCommand command) {
            return command.getValue();
        }
    }

    @LocalHandler(logMessage = true)
    private static class LocalOnlyPublishingHandler {
        @HandleCommand
        String handle(PublishedLocalOnlyCommand command) {
            return command.value();
        }
    }

    @LocalHandler
    private static class ReplacementHandler {
        @HandleCommand
        String handle(ReplacementTarget command) {
            return command.value();
        }
    }

    @LocalHandler
    private static class MessageSubtypeHandler {
        @HandleCommand
        String handle(ProtectedCommand command, ProtectedCommandMessage message) {
            assertInstanceOf(ProtectedCommandMessage.class, message);
            return command.value();
        }
    }

    private static class CountingLocalClient extends LocalClient {
        private CountingLocalClient() {
            super(Duration.ofMinutes(2));
        }

        @Override
        protected KeyValueClient createKeyValueClient() {
            return new CountingKeyValueClient();
        }

        private void assertKeyValueCalls(int puts, int gets, int deletes) {
            CountingKeyValueClient client = (CountingKeyValueClient) getKeyValueClient();
            assertEquals(puts, client.puts.get(), "KV puts");
            assertEquals(gets, client.gets.get(), "KV gets");
            assertEquals(deletes, client.deletes.get(), "KV deletes");
        }
    }

    private static class CountingKeyValueClient extends InMemoryKeyValueStore {
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();

        @Override
        public CompletableFuture<Void> putValue(String key, Data<byte[]> value, Guarantee guarantee) {
            puts.incrementAndGet();
            return super.putValue(key, value, guarantee);
        }

        @Override
        public Data<byte[]> getValue(String key) {
            gets.incrementAndGet();
            return super.getValue(key);
        }

        @Override
        public CompletableFuture<Void> deleteValue(String key, Guarantee guarantee) {
            deletes.incrementAndGet();
            return super.deleteValue(key, guarantee);
        }
    }
}
