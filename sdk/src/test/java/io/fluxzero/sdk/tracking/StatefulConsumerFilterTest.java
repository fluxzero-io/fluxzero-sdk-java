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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.Stateful;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.tracking.BatchInterceptor.preservingConsumerFilter;
import static org.junit.jupiter.api.Assertions.*;

class StatefulConsumerFilterTest {
    private final Map<String, List<String>> delivered = new ConcurrentHashMap<>();
    private final Map<String, ConsumerConfiguration> configurations = new ConcurrentHashMap<>();

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void schedulesReachOnlyTheirTypedConsumerAndOptOutPreservesDelivery(boolean async, boolean filtering) {
        var fixture = fixture(async, filtering, Saga.class, OtherSaga.class);
        var start = Instant.parse("2026-01-01T00:00:00Z");
        try (var ignored = fixture.getFluxzero()) {
            fixture.atFixedTime(start).givenStateful(new Saga("a", new Child("child"))).givenStateful(new OtherSaga("a"))
                    .givenSchedules(new Schedule(new Tick("a"), "tick", start.plusSeconds(1)),
                                    new Schedule(new OtherTick("a"), "other", start.plusSeconds(1)),
                                    new Schedule(new Unrelated(), "unrelated", start.plusSeconds(1)))
                    .whenTimeAdvancesTo(start.plusSeconds(1))
                    .expectCommands("tick:a", "other:a").expectNoErrors();
            if (async) {
                assertEquals(filtering ? List.of(Tick.class.getName()) : allSchedules(), types("saga", SCHEDULE).stream().sorted().toList());
                assertEquals(filtering ? List.of(OtherTick.class.getName()) : allSchedules(), types("other", SCHEDULE).stream().sorted().toList());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staticCreationAndMemberHandlersKeepTheirInputsAndParentInjection(boolean async) {
        var fixture = fixture(async, true, Saga.class);
        try (var ignored = fixture.getFluxzero()) {
            fixture.givenEvents(new Unrelated()).whenEvent(new Start("a"))
                    .expectNoErrors().andThen()
                    .whenEvent(new ChildEvent("child"))
                    .expectOnlyCommands("child:a:child").expectNoErrors();
            if (async) {
                assertEquals(List.of(Start.class.getName(), ChildEvent.class.getName()), types("saga", EVENT));
                assertTrue(configurations.get("saga:EVENT").getConsumerBlueprint().getFilter().payloadRestricted());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingAssociationStillDeliversAnEligibleTypeWithoutInvokingSaga(boolean async) {
        var fixture = fixture(async, true, Saga.class);
        try (var ignored = fixture.getFluxzero()) {
            fixture.givenStateful(new Saga("a", new Child("child")))
                    .whenEvent(new ChildEvent("missing"))
                    .expectNoCommands().expectNoErrors();
            if (async) assertEquals(List.of(ChildEvent.class.getName()), types("saga", EVENT));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openPayloadsRemainEligible(boolean async) {
        var fixture = fixture(async, true, OpenSaga.class);
        try (var ignored = fixture.getFluxzero()) {
            fixture.givenStateful(new OpenSaga("a")).whenEvent(new Unrelated())
                    .expectOnlyCommands("open").expectNoErrors();
            if (async) assertFalse(configurations.get("open:EVENT").getConsumerBlueprint().getFilter().payloadRestricted());
        }
    }

    @Test
    void aliasesAndUpcastInputsRemainEligible() {
        var fixture = fixture(true, true, Saga.class);
        try (var app = fixture.getFluxzero()) {
            app.serializer().registerTypeAlias("OldChildEvent", ChildEvent.class.getName());
            var caster = app.serializer().registerUpcasters(new Caster());
            fixture.givenStateful(new Saga("a", new Child("child")))
                    .whenExecuting(fc -> fc.client().getGatewayClient(EVENT)
                            .append(io.fluxzero.common.Guarantee.STORED, raw("OldChildEvent")).join())
                    .expectOnlyCommands("child:a:child").expectNoErrors().andThen()
                    .whenExecuting(fc -> fc.client().getGatewayClient(EVENT)
                            .append(io.fluxzero.common.Guarantee.STORED, raw("LegacyChildEvent")).join())
                    .expectOnlyCommands("child:a:child").expectNoErrors();
            {
                var blueprint = configurations.get("saga:EVENT").getConsumerBlueprint();
                assertTrue(blueprint.mayHandle(raw("LegacyChildEvent")));
                caster.cancel();
                assertFalse(blueprint.mayHandle(raw("LegacyChildEvent")));
                assertEquals(List.of("OldChildEvent", "LegacyChildEvent"), types("saga", EVENT));
            }
        }
    }

    @Test
    void customPayloadResolverKeepsUnexpectedWireTypesEligible() {
        var fixture = TestFixture.createAsync(DefaultFluxzero.builder()
                .addParameterResolver((parameter, annotation) -> parameter.getType() == Tick.class
                        ? message -> new Tick("a") : null), InjectedSaga.class);
        try (var ignored = fixture.getFluxzero()) {
            var start = Instant.parse("2026-01-01T00:00:00Z");
            fixture.atFixedTime(start).givenStateful(new InjectedSaga("a"))
                    .givenSchedules(new Schedule(new Unrelated(), "custom", start.plusSeconds(1)))
                    .whenTimeAdvancesTo(start.plusSeconds(1)).expectOnlyCommands("tick:a").expectNoErrors();
        }
    }

    private static SerializedMessage raw(String type) {
        return new SerializedMessage(new Data<>("{\"childId\":\"child\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                type, 0, Data.JSON_FORMAT), Metadata.empty(), type, 0L);
    }

    static class Caster {
        @Upcast(type = "LegacyChildEvent", revision = 0)
        static Data<ObjectNode> cast(ObjectNode input) { return new Data<>(input, ChildEvent.class.getName(), 0); }
    }

    @Test
    void nestedCollectionMembersRetainTheirInputs() {
        var fixture = fixture(true, true, NestedSaga.class);
        try (var ignored = fixture.getFluxzero()) {
            fixture.givenStateful(new NestedSaga("a", List.of(new Layer(new Leaf("child")))))
                    .givenEvents(new Unrelated()).whenEvent(new ChildEvent("child"))
                    .expectOnlyCommands("nested:a:child").expectNoErrors();
            assertEquals(List.of(ChildEvent.class.getName()), types("nested", EVENT));
        }
    }

    @Test
    void contextOnlyMemberParameterDoesNotBecomeARequiredPayloadType() {
        var fixture = fixture(true, true, ContextSaga.class);
        try (var ignored = fixture.getFluxzero()) {
            fixture.givenStateful(new ContextSaga("a", new ContextChild()))
                    .whenEvent(new Unrelated()).expectOnlyCommands("context:a").expectNoErrors();
            assertFalse(configurations.get("context:EVENT").getConsumerBlueprint().getFilter().payloadRestricted());
        }
    }

    @Test
    void customFactoryRetainsUnknownSelection() {
        var factory = new io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory(SCHEDULE,
                io.fluxzero.sdk.tracking.handling.HandlerDecorator.noOp,
                List.of(new io.fluxzero.sdk.tracking.handling.PayloadParameterResolver()),
                io.fluxzero.common.handling.MethodInvocationValidator.noOp(), ignored -> null, null, false,
                new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer()) {};
        assertNull(factory.createHandler(OtherSaga.class, (type, method) -> true, List.of())
                           .orElseThrow().getConsumerFilter());
    }

    @Test
    void statefulWebBodiesDoNotBecomeWireTypeConstraints() {
        var factory = new io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory(MessageType.WEBREQUEST,
                io.fluxzero.sdk.tracking.handling.HandlerDecorator.noOp,
                List.of(new io.fluxzero.sdk.tracking.handling.PayloadParameterResolver()),
                io.fluxzero.common.handling.MethodInvocationValidator.noOp(), ignored -> null, null, false,
                new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer());
        assertNull(factory.createHandler(WebSaga.class, (type, method) -> true, List.of())
                           .orElseThrow().getConsumerFilter());
        TestFixture.createAsync(WebSaga.class).whenWebRequest(io.fluxzero.sdk.web.WebRequest.builder()
                        .method(io.fluxzero.sdk.web.HttpRequestMethod.POST).url("/stateful")
                        .payload(new Start("web")).build())
                .expectOnlyCommands("web").expectNoErrors();
    }

    private TestFixture fixture(boolean async, boolean filtering, Object... handlers) {
        var builder = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.CONSUMER_FILTERING_PROPERTY, Boolean.toString(filtering))).andThen(existing))
                .addBatchInterceptor(preservingConsumerFilter((consumer, tracker) -> {
                    String key = tracker.getName() + ":" + tracker.getMessageType();
                    configurations.put(key, tracker.getConfiguration());
                    return batch -> {
                        delivered.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>())
                                .addAll(batch.getMessages().stream().map(SerializedMessage::getType).toList());
                        consumer.accept(batch);
                    };
                }), EVENT, SCHEDULE);
        return async ? TestFixture.createAsync(builder, handlers) : TestFixture.create(builder, handlers);
    }

    private List<String> types(String consumer, MessageType type) {
        return delivered.getOrDefault(consumer + ":" + type, List.of());
    }

    private static List<String> allSchedules() {
        return List.of(Tick.class.getName(), OtherTick.class.getName(), Unrelated.class.getName()).stream().sorted().toList();
    }

    record Start(String id) {}
    record Tick(String id) {}
    record OtherTick(String id) {}
    record Unrelated() {}
    record ChildEvent(String childId) {}

    @Stateful @Consumer(name = "saga")
    record Saga(@EntityId @Association String id, @Member Child child) {
        @HandleEvent static Saga create(Start event) { return new Saga(event.id(), new Child("child")); }
        @HandleSchedule void handle(Tick tick) { Fluxzero.sendAndForgetCommand("tick:" + id); }
    }

    record Child(@EntityId @Association String childId) {
        @HandleEvent void handle(ChildEvent event, Saga parent) {
            Fluxzero.sendAndForgetCommand("child:" + parent.id() + ":" + childId);
        }
    }

    @Stateful @Consumer(name = "other")
    record OtherSaga(@EntityId @Association String id) {
        @HandleSchedule void handle(OtherTick tick) { Fluxzero.sendAndForgetCommand("other:" + id); }
    }

    @Stateful
    record InjectedSaga(@EntityId String id) {
        @HandleSchedule @Association(always = true)
        void handle(Tick tick) { Fluxzero.sendAndForgetCommand("tick:" + tick.id()); }
    }

    @Stateful @Consumer(name = "nested")
    record NestedSaga(@EntityId @Association String id, @Member List<Layer> layers) {}
    record Layer(@Member Leaf leaf) {}
    record Leaf(@EntityId @Association String childId) {
        @HandleEvent void handle(ChildEvent event, NestedSaga root, Layer layer) {
            Fluxzero.sendAndForgetCommand("nested:" + root.id() + ":" + layer.leaf().childId());
        }
    }

    @Stateful @Consumer(name = "context")
    record ContextSaga(@EntityId String id, @Member ContextChild child) {}
    record ContextChild() {
        @HandleEvent @Association(always = true) void handle(ContextSaga root) {
            Fluxzero.sendAndForgetCommand("context:" + root.id());
        }
    }

    @Stateful
    record WebSaga(@EntityId String id) {
        @io.fluxzero.sdk.web.HandlePost("/stateful")
        static WebSaga create(Start start) {
            Fluxzero.sendAndForgetCommand(start.id());
            return new WebSaga(start.id());
        }
    }

    @Stateful @Consumer(name = "open")
    record OpenSaga(@EntityId String id) {
        @HandleEvent @Association(always = true) void handle(Object event) { Fluxzero.sendAndForgetCommand("open"); }
    }
}
