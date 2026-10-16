/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.scheduling.ScheduledCommand;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.Trigger;
import io.fluxzero.sdk.web.HandleGet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerBlueprintTest {
    @Test
    void nestedPayloadDoesNotInheritItsEnclosingAggregatesInjectionRole() throws Exception {
        var handler = new NestedEventHandler();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        var delivered = new CopyOnWriteArrayList<SerializedMessage>();
        try (Fluxzero app = application(delivered, configuration, EVENT)) {
            app.registerHandlers(handler);
            assertTrue(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            app.eventGateway().publish(new Message(new Ignored()), Guarantee.STORED).join();
            app.eventGateway().publish(new Message(new AggregateContainer.Changed("nested")), Guarantee.STORED).join();
            assertEquals("nested", handler.result.get(2, TimeUnit.SECONDS));
            assertEquals(1, delivered.size());
        }
    }

    @io.fluxzero.sdk.modeling.Aggregate
    static class AggregateContainer {
        record Changed(String value) {}
    }

    static class NestedEventHandler {
        final CompletableFuture<String> result = new CompletableFuture<>();
        @HandleEvent void handle(AggregateContainer.Changed event) { result.complete(event.value()); }
    }

    @Test
    void additionalJsonViewDoesNotWidenTheTypedPayloadSelection() throws Exception {
        var handler = new JsonViewHandler();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        var delivered = new CopyOnWriteArrayList<SerializedMessage>();
        try (Fluxzero app = application(delivered, configuration, EVENT)) {
            app.registerHandlers(handler);
            assertTrue(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            app.eventGateway().publish(new Message(new Ignored()), Guarantee.STORED).join();
            app.eventGateway().publish(new Message(new Created("json")), Guarantee.STORED).join();
            assertEquals("json:json", handler.result.get(2, TimeUnit.SECONDS));
            assertEquals(1, delivered.size());
        }
    }

    @Test
    void triggerInjectionOnlyDescribesItsAnnotatedParameter() {
        var configuration = new AtomicReference<ConsumerConfiguration>();
        try (Fluxzero app = application(new CopyOnWriteArrayList<>(), configuration, EVENT)) {
            app.registerHandlers(new TriggeredHandler());
            var filter = configuration.get().getConsumerBlueprint().getFilter();
            assertTrue(filter.payloadRestricted());
            assertTrue(filter.compile().test(raw(Created.class.getName(), 0, Metadata.empty())));
            assertFalse(filter.compile().test(raw(Ignored.class.getName(), 0, Metadata.empty())));
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filtersAutomaticModelCommandsBeforeBatchDelivery(boolean receiverDefined) throws Exception {
        List<SerializedMessage> delivered = new CopyOnWriteArrayList<>();
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        Object command = receiverDefined ? new ReceiverCommand("receiver") : new CreateModel("payload");
        Object expected = receiverDefined ? new ReceiverModel("receiver") : new PayloadModel("payload");
        try (Fluxzero app = application(delivered, configuration, COMMAND)) {
            app.registerHandlers(receiverDefined ? ReceiverModel.class : CreateModel.class);
            app.commandGateway().setSelfHandlerFilter((type, method) -> false);
            var filter = configuration.get().getConsumerBlueprint().getFilter();
            assertTrue(filter.payloadRestricted());
            assertTrue(filter.compile().test(raw(command.getClass().getName(), 0, Metadata.empty())));
            assertFalse(filter.compile().test(raw(Ignored.class.getName(), 0, Metadata.empty())));
            app.client().getGatewayClient(COMMAND).append(Guarantee.STORED,
                    raw(Ignored.class.getName(), 0, Metadata.empty())).join();
            assertNull(app.commandGateway().send(command).get(2, TimeUnit.SECONDS));
            assertEquals(expected, app.apply(ignored -> Fluxzero.loadModel(
                    receiverDefined ? "receiver" : "payload", expected.getClass()).get()));
            assertEquals(List.of(command.getClass().getName()),
                         delivered.stream().map(SerializedMessage::getType).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scheduledCommandsUseWrapperAndCommandSelections(boolean async) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant deadline = start.plusSeconds(60);
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        var handler = new ScheduledHandler();
        var builder = DefaultFluxzero.builder().addBatchInterceptor((consumer, tracker) -> {
            configuration.set(tracker.getConfiguration());
            return consumer;
        }, SCHEDULE);
        var fixture = async ? TestFixture.createAsync(builder, handler) : TestFixture.create(builder, handler);
        var command = new ScheduledPayload("due");
        fixture.atFixedTime(start).givenScheduledCommands(new Schedule(command, "due", deadline))
                .whenTimeAdvancesTo(deadline).expectCommands(command).expectNoErrors()
                .expectTrue(ignored -> handler.result.isDone());
        if (async) {
            var filter = configuration.get().getConsumerBlueprint().getFilter();
            assertTrue(filter.payloadRestricted());
            assertTrue(filter.compile().test(raw(ScheduledCommand.class.getName(), 0, Metadata.empty())));
            assertFalse(filter.compile().test(raw(ScheduledPayload.class.getName(), 0, Metadata.empty())));
        }
    }

    @Test
    void filtersOrdinaryRegisteredHandlersBeforeBatchDelivery() throws Exception {
        List<SerializedMessage> delivered = new CopyOnWriteArrayList<>();
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        var handler = new CreatedHandler();
        try (Fluxzero app = application(delivered, configuration, EVENT)) {
            app.registerHandlers(handler);
            assertTrue(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            app.eventGateway().publish(new Message(new Ignored()), Guarantee.STORED).join();
            app.eventGateway().publish(new Message(new Created("current")), Guarantee.STORED).join();
            assertEquals("current", handler.result.get(2, TimeUnit.SECONDS).value());
            assertEquals(List.of(Created.class.getName()), delivered.stream().map(SerializedMessage::getType).toList());
            assertEquals(Duration.ofMinutes(15), configuration.get().getMaxWaitDuration());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(2)
    void asyncFixtureUsesTheSameAutomaticFiltering() {
        List<SerializedMessage> delivered = new CopyOnWriteArrayList<>();
        var notification = new CreatedNotificationHandler();
        var fixture = io.fluxzero.sdk.test.TestFixture.createAsync(DefaultFluxzero.builder()
                .addBatchInterceptor((consumer, tracker) -> batch -> {
                    delivered.addAll(batch.getMessages());
                    consumer.accept(batch);
                }, EVENT), new CreatedHandler(), notification);
        fixture.givenEvents(new Ignored()).whenEvent(new Created("fixture")).expectNoErrors()
                .expectTrue(ignored -> notification.result.isDone());
        assertEquals(List.of(Created.class.getName()), delivered.stream().map(SerializedMessage::getType).toList());
    }

    @Test
    void includesMigrationInputsAndRefreshesAfterRegistrationAndCancellation() throws Exception {
        List<SerializedMessage> delivered = new CopyOnWriteArrayList<>();
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        var handler = new CreatedHandler();
        try (Fluxzero app = application(delivered, configuration, EVENT)) {
            app.registerHandlers(handler);
            var caster = app.serializer().registerUpcasters(new RenamingCaster());
            var filter = configuration.get().getConsumerBlueprint().getFilter();
            var legacy = raw("LegacyCreated", 0, Metadata.empty());
            assertTrue(filter.compile().test(legacy));
            app.client().getGatewayClient(EVENT).append(Guarantee.STORED, legacy).join();
            assertEquals("legacy", handler.result.get(2, TimeUnit.SECONDS).value());
            caster.cancel();
            assertFalse(configuration.get().getConsumerBlueprint().getFilter().compile().test(legacy));
        }
    }

    @Test
    void preservesAliasChainsPackageRenamesAndTransitiveSplittingAndDroppingUpcasts() throws Exception {
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        var handler = new CreatedHandler();
        try (Fluxzero app = application(new CopyOnWriteArrayList<>(), configuration, EVENT)) {
            app.registerHandlers(handler);
            app.serializer().registerTypeAlias("FirstAlias", "SecondAlias");
            app.serializer().registerTypeAlias("SecondAlias", Created.class.getName());
            app.serializer().registerPackageAlias("legacy", Created.class.getPackageName());
            app.serializer().registerUpcasters(new SplittingCaster());
            app.client().getGatewayClient(EVENT).append(Guarantee.STORED,
                    raw("LegacyDrop", 0, Metadata.empty()), raw("FirstAlias", 0, Metadata.empty()),
                    raw("legacy.ConsumerBlueprintTest$Created", 0, Metadata.empty()),
                    raw("LegacySplit", 0, Metadata.empty())).join();
            for (int i = 0; i < 4; i++) {
                assertNotNull(handler.received.poll(2, TimeUnit.SECONDS));
            }
            assertEquals(List.of(new Created(null), new Created(null), new Created("one"), new Created("two")),
                         handler.handled);
        }
    }

    @Test
    void allowedClassesRestrictAnOtherwiseOpenPayloadParameter() throws Exception {
        List<SerializedMessage> delivered = new CopyOnWriteArrayList<>();
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        var handler = new AllowedClassHandler();
        try (Fluxzero app = application(delivered, configuration, EVENT)) {
            app.registerHandlers(handler);
            app.eventGateway().publish(new Message(new Ignored()), Guarantee.STORED).join();
            app.eventGateway().publish(new Message(new Created("allowed")), Guarantee.STORED).join();
            assertEquals(new Created("allowed"), handler.result.get(2, TimeUnit.SECONDS));
            assertTrue(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            assertEquals(List.of(Created.class.getName()), delivered.stream().map(SerializedMessage::getType).toList());
        }
    }

    @Test
    void customArgumentResolversCannotBeNarrowedToTheirDeclaredArgumentType() throws Exception {
        var handler = new CreatedHandler();
        var builder = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addParameterResolver((parameter, annotation) -> parameter.getType() == Created.class
                        ? message -> new Created("injected") : null);
        try (Fluxzero app = builder.build(io.fluxzero.sdk.configuration.client.LocalClient.newInstance())) {
            app.registerHandlers(handler);
            app.eventGateway().publish(new Message(new Ignored()), Guarantee.STORED).join();
            assertEquals("injected", handler.result.get(2, TimeUnit.SECONDS).value());
        }
    }

    @Test
    void exportsTheActualCompiledWebRoutesIncludingAutomaticMethods() {
        AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
        try (Fluxzero app = application(new CopyOnWriteArrayList<>(), configuration, WEBREQUEST)) {
            app.registerHandlers(new WebHandler());
            var filter = configuration.get().getConsumerBlueprint().getFilter();
            assertTrue(filter.webRestricted());
            var predicate = filter.compile();
            for (String method : List.of("GET", "HEAD", "OPTIONS")) {
                assertTrue(predicate.test(raw("[B", 0, Metadata.of("method", method, "url", "/items/abc/detail?x=y"))), method);
            }
            assertTrue(predicate.test(raw("[B", 0, Metadata.of("method", "GET", "url", "/items/abc"))));
            assertFalse(predicate.test(raw("[B", 0, Metadata.of("method", "POST", "url", "/items/abc"))));
            assertFalse(predicate.test(raw("[B", 0, Metadata.of("method", "GET", "url", "/unrelated"))));
        }
    }

    @Test
    void automaticDefaultsAndExplicitOverridesStayApplicationLocal() {
        var base = ConsumerConfiguration.builder().name("consumer").build();
        for (String version : List.of("", "2026.10.14", "2026.10.15", "2027.01.01")) {
            var source = new SimplePropertySource(Map.of("fluxzero.defaults.version", version));
            var resolved = base.resolveTrackingDefaults(source);
            assertTrue(resolved.getConsumerFiltering());
            assertEquals(Duration.ofMinutes(15), resolved.getMaxWaitDuration());
            assertEquals(60_000L, resolved.getMaxScanLookBack());
        }
        assertEquals(Duration.ofMinutes(15), base.getMaxWaitDuration());
        var enabled = base.resolveTrackingDefaults(new SimplePropertySource(Map.of(
                "fluxzero.tracking.consumerFiltering", "true", "fluxzero.tracking.maxWaitDuration", "PT30M")));
        assertTrue(enabled.getConsumerFiltering());
        assertEquals(Duration.ofMinutes(30), enabled.getMaxWaitDuration());
        var disabled = base.resolveTrackingDefaults(new SimplePropertySource(Map.of(
                "fluxzero.defaults.version", "2026.10.15", "fluxzero.tracking.consumerFiltering", "false",
                "fluxzero.tracking.maxWaitDuration", "PT1M")));
        assertFalse(disabled.getConsumerFiltering());
        assertEquals(Duration.ofMinutes(1), disabled.getMaxWaitDuration());
        var explicit = base.toBuilder().maxWaitDuration(Duration.ofSeconds(60)).consumerFiltering(false).build()
                .resolveTrackingDefaults(new SimplePropertySource(Map.of("fluxzero.defaults.version", "2026.10.15")));
        assertEquals(Duration.ofSeconds(60), explicit.getMaxWaitDuration());
        assertFalse(explicit.getConsumerFiltering());
        assertThrows(IllegalArgumentException.class, () -> base.resolveTrackingDefaults(
                new SimplePropertySource(Map.of("fluxzero.tracking.maxWaitDuration", "PT-1M"))));
    }

    @Test
    void customSerializersRemainUnrestricted() {
        var custom = new JacksonSerializer() {};
        var description = io.fluxzero.common.api.tracking.ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT,
                List.of(new io.fluxzero.common.api.tracking.ConsumerFilter.PayloadType("Current", null)), null, List.of());
        assertFalse(custom.describeConsumerFilter(description).payloadRestricted());
    }

    @Test
    void serializerPreservesCustomHandlerMigrationInputs() {
        var description = io.fluxzero.common.api.tracking.ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT,
                List.of(new io.fluxzero.common.api.tracking.ConsumerFilter.PayloadType("Current", null)), null,
                List.of(new io.fluxzero.common.api.tracking.ConsumerFilter.PayloadType("Legacy", 0)));
        assertTrue(new JacksonSerializer().describeConsumerFilter(description).compile()
                .test(raw("Legacy", 0, Metadata.empty())));
    }

    @Test
    void serializerCannotNarrowUnknownOrDifferentFormatHandlerContracts() {
        for (var description : List.of(
                io.fluxzero.common.api.tracking.ConsumerFilter.select(null, 0, 1, "text/plain",
                        List.of(), null, List.of()),
                io.fluxzero.common.api.tracking.ConsumerFilter.select(null, 0, 99, Data.JSON_FORMAT,
                        List.of(), null, List.of()))) {
            assertTrue(description.compile().test(raw("AnyJson", 0, Metadata.empty())));
            assertTrue(new JacksonSerializer().describeConsumerFilter(description).compile()
                    .test(raw("AnyJson", 0, Metadata.empty())));
        }
    }

    @Test
    void covariantArrayParametersAndAllowedClassesRemainEligible() throws Exception {
        for (ArrayHandler handler : List.of(new ArrayParameterHandler(), new AllowedArrayHandler())) {
            AtomicReference<ConsumerConfiguration> configuration = new AtomicReference<>();
            try (Fluxzero app = application(new CopyOnWriteArrayList<>(), configuration, EVENT)) {
                app.registerHandlers(handler);
                assertFalse(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
                app.eventGateway().publish(new Message(new Integer[]{1, 2}), Guarantee.STORED).join();
                assertEquals(2, handler.result.get(2, TimeUnit.SECONDS));
            }
        }
    }

    private static Fluxzero application(List<SerializedMessage> delivered,
                                         AtomicReference<ConsumerConfiguration> configuration, MessageType type) {
        return DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addBatchInterceptor((consumer, tracker) -> {
                    configuration.set(tracker.getConfiguration());
                    return batch -> {
                        delivered.addAll(batch.getMessages());
                        consumer.accept(batch);
                    };
                }, type).build(io.fluxzero.sdk.configuration.client.LocalClient.newInstance());
    }

    private static SerializedMessage raw(String type, int revision, Metadata metadata) {
        return new SerializedMessage(new Data<>("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), type, revision,
                                                Data.JSON_FORMAT), metadata, java.util.UUID.randomUUID().toString(),
                                     System.currentTimeMillis());
    }

    record Created(String value) {}
    static class JsonViewHandler {
        final CompletableFuture<String> result = new CompletableFuture<>();
        @HandleEvent void handle(Created event, com.fasterxml.jackson.databind.JsonNode json) {
            result.complete(event.value() + ":" + json.get("value").asText());
        }
    }
    static class TriggeredHandler {
        @HandleEvent void handle(Created event, @Trigger Ignored trigger) {}
    }
    record Ignored() {}
    @Model(searchable = false)
    record PayloadModel(@EntityId String id) {}
    record CreateModel(String id) {
        @Apply PayloadModel apply() { return new PayloadModel(id); }
    }
    @Model(searchable = false)
    record ReceiverModel(@EntityId String id) {
        @Apply static ReceiverModel apply(ReceiverCommand command) { return new ReceiverModel(command.id()); }
    }
    record ReceiverCommand(String id) {}
    record ScheduledPayload(String value) {}
    static class ScheduledHandler {
        final CompletableFuture<ScheduledPayload> result = new CompletableFuture<>();
        @HandleCommand void handle(ScheduledPayload command) { result.complete(command); }
    }
    static class CreatedHandler {
        final CompletableFuture<Created> result = new CompletableFuture<>();
        final java.util.concurrent.BlockingQueue<Created> received = new java.util.concurrent.LinkedBlockingQueue<>();
        final List<Created> handled = new CopyOnWriteArrayList<>();
        @HandleEvent void on(Created event) {
            handled.add(event);
            received.add(event);
            result.complete(event);
        }
    }
    static class AllowedClassHandler {
        final CompletableFuture<Object> result = new CompletableFuture<>();
        @HandleEvent(allowedClasses = Created.class)
        void on(Object event) { result.complete(event); }
    }
    static class CreatedNotificationHandler {
        final CompletableFuture<Created> result = new CompletableFuture<>();
        @io.fluxzero.sdk.tracking.handling.HandleNotification
        void on(Created event) { result.complete(event); }
    }
    static class RenamingCaster {
        @Upcast(type = "LegacyCreated", revision = 0)
        Data<com.fasterxml.jackson.databind.node.ObjectNode> rename(Data<com.fasterxml.jackson.databind.node.ObjectNode> input) {
            input.getValue().put("value", "legacy");
            return input.withType(Created.class.getName());
        }
    }
    static class WebHandler {
        @HandleGet("/items/{id:[a-z]+}[/{part}]") String get() { return "ok"; }
    }
    static class SplittingCaster {
        @Upcast(type = "LegacySplit", revision = 0)
        Data<com.fasterxml.jackson.databind.node.ObjectNode> intermediate(Data<com.fasterxml.jackson.databind.node.ObjectNode> input) {
            return new Data<>(input.getValue(), "IntermediateSplit", 0, input.getFormat());
        }
        @Upcast(type = "IntermediateSplit", revision = 0)
        java.util.stream.Stream<Data<com.fasterxml.jackson.databind.node.ObjectNode>> split(Data<com.fasterxml.jackson.databind.node.ObjectNode> input) {
            return java.util.stream.Stream.of(
                    new Data<>(input.getValue().deepCopy().put("value", "one"), Created.class.getName(), 0, input.getFormat()),
                    new Data<>(input.getValue().deepCopy().put("value", "two"), Created.class.getName(), 0, input.getFormat()));
        }
        @Upcast(type = "LegacyDrop", revision = 0)
        java.util.stream.Stream<Data<com.fasterxml.jackson.databind.node.ObjectNode>> drop(Data<com.fasterxml.jackson.databind.node.ObjectNode> input) {
            return java.util.stream.Stream.empty();
        }
    }
    abstract static class ArrayHandler {
        final CompletableFuture<Integer> result = new CompletableFuture<>();
    }
    static class ArrayParameterHandler extends ArrayHandler {
        @HandleEvent void on(Number[] event) { result.complete(event.length); }
    }
    static class AllowedArrayHandler extends ArrayHandler {
        @HandleEvent(allowedClasses = Number[].class) void on() { result.complete(2); }
    }
}
