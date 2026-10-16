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

package io.fluxzero.sdk.test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;

class SynchronousConsumerFilterTest {
    @Test
    void incorrectPayloadFilterBreaksAnOrdinaryFixtureExpectation() {
        var fixture = TestFixture.create(builder(SynchronousConsumerFilterTest::rejectPayloads), new EventHandler());
        assertThrows(GivenWhenThenAssertionError.class,
                     () -> fixture.whenEvent(new Accepted("event")).expectEvents("event"));
    }

    @Test
    void filterOffSwitchRetainsHandling() {
        TestFixture.create(builder(SynchronousConsumerFilterTest::rejectPayloads)
                        .replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                                "fluxzero.tracking.consumerFiltering", "false"))), new EventHandler())
                .whenEvent(new Accepted("event")).expectEvents("event");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consumerAdmissionDoesNotSerializeThePayloadBody(boolean restricted) {
        var serializer = spy(new JacksonSerializer());
        doThrow(new AssertionError("Admission must not serialize the payload body"))
                .when(serializer).serialize(isA(Accepted.class), any());
        Object handler = restricted ? new Object() {
            @HandleCommand
            String handle(Accepted input) { return "accepted"; }
        } : new Object() {
            @HandleCommand
            String handle(Object input) { return "accepted"; }
        };
        TestFixture.create(DefaultFluxzero.builder().replaceSerializer(serializer), handler)
                .whenCommand(new Accepted("input")).expectResult("accepted");
    }

    @Test
    void admissionPreservesLocallyValidTimestampsOutsideTheWireRange() {
        var fixture = TestFixture.create(new Object() {
            @HandleCommand String handle(Accepted input) { return input.value(); }
        });
        for (Instant instant : List.of(Instant.MIN, Instant.MAX)) {
            fixture.whenCommand(new Message(new Accepted("extreme"), Metadata.empty(), "timestamp", instant))
                    .expectResult("extreme");
        }
    }

    @Test
    void lateRegistrationRefreshesTheWholeConsumerUnion() {
        AtomicInteger unions = new AtomicInteger();
        var builder = builder(filter -> {
            if (filter.payloadTypes().stream().anyMatch(t -> t.name().equals(Accepted.class.getName()))
                && filter.payloadTypes().stream().anyMatch(t -> t.name().equals(Other.class.getName()))) {
                unions.incrementAndGet();
            }
            return filter;
        });
        TestFixture.create(builder, new EventHandler()).registerHandlers(new OtherHandler())
                .whenEvent(new Other("late")).expectEvents("late")
                .andThen().whenEvent(new Accepted("first")).expectEvents("first");
        assertTrue(unions.get() > 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyInputIsCheckedBeforeUpcasting(boolean removeMigrationInput) {
        var fixture = TestFixture.create(builder(filter -> removeMigrationInput
                        ? filter.forRevision(filter.subscriptionId(), filter.revision(), filter.format(), List.of())
                        : filter), new EventHandler()).registerCasters(new Caster());
        Runnable check = () -> fixture.whenEvent(legacy("same-id")).expectEvents("legacy");
        if (removeMigrationInput) {
            assertThrows(GivenWhenThenAssertionError.class, check::run);
        } else {
            check.run();
            fixture.whenEvent(new Message(new Accepted("current"), Metadata.empty(), "same-id", Instant.now()))
                    .expectEvents("current");
        }
    }

    @Test
    void localHandlersAndLocalSelfHandlersBypassFiltering() {
        var fixture = TestFixture.create(builder(SynchronousConsumerFilterTest::rejectPayloads), new LocalQueries());
        fixture.whenQuery(new Accepted("local")).expectResult("local");
        fixture.whenQuery(new LocalQuery()).expectResult("self");
    }

    @Test
    void automaticSelfTrackingHandlersAreFiltered() {
        var fixture = TestFixture.create(builder(SynchronousConsumerFilterTest::rejectPayloads));
        assertThrows(GivenWhenThenAssertionError.class,
                     () -> fixture.whenCommand(new SelfCommand()).expectResult("self"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticModelCommandsAreFiltered(boolean reject) {
        var fixture = TestFixture.create(builder(reject ? SynchronousConsumerFilterTest::rejectPayloads : f -> f));
        Runnable check = () -> fixture.whenCommand(new CreateModel("created")).expectNoErrors()
                .expectTrue(fc -> Fluxzero.loadModel("created", ExampleModel.class).isPresent());
        if (reject) {
            assertThrows(GivenWhenThenAssertionError.class, check::run);
        } else {
            check.run();
        }
    }

    @Test
    void scheduledCommandsStillRunThroughTheirOwnConsumer() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        TestFixture.create(new CommandHandler()).atFixedTime(now)
                .givenScheduledCommands(new Schedule(new Accepted("due"), "due", now.plusSeconds(1)))
                .whenTimeAdvancesTo(now.plusSeconds(1)).expectEvents("due").expectNoErrors();
    }

    @Test
    void incorrectRouteFilterBreaksAnOrdinaryWebExpectation() {
        var fixture = TestFixture.create(builder(filter -> ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT,
                null, List.of(), List.of())), new WebHandler());
        assertThrows(GivenWhenThenAssertionError.class,
                     () -> fixture.whenGet("/api/items/a%2Fb").expectResult("ok"));
    }

    @Test
    void encodedWebPathsRemainEligible() {
        var fixture = TestFixture.create(new WebHandler());
        for (String value : List.of("a%2Fb", "a%252Fb", "a+b", "%E2%82%AC")) {
            fixture.whenGet("/api/items/" + value + "?x=1").expectResult("ok");
        }
    }

    @Test
    void copiedFixturesHaveIndependentFilterLifecycles() {
        var fixture = TestFixture.create(new EventHandler());
        fixture.spy().whenEvent(new Accepted("copy")).expectEvents("copy");
    }

    @Test
    void customPreparedHandlersAreAlsoGated() {
        AtomicInteger handled = new AtomicInteger();
        var handler = new Handler<DeserializingMessage>() {
            @Override public Class<?> getTargetClass() { return EventHandler.class; }
            @Override public ConsumerFilter getConsumerFilter() {
                return ConsumerFilter.select(null, 0, 1, null,
                        List.of(new ConsumerFilter.PayloadType(Other.class.getName(), null)), null, List.of());
            }
            @Override public Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return message.getPayload() instanceof Accepted
                        ? Optional.of(HandlerInvoker.run(handled::incrementAndGet))
                        : Optional.empty();
            }
        };
        TestFixture.create(handler).whenEvent(new Accepted("ignored")).expectNoErrors();
        assertEquals(0, handled.get());
    }

    @Test
    void routeAdmissionCannotBeCachedByJavaPayloadType() {
        var fixture = TestFixture.create(builder(filter -> ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT,
                null, List.of(new ConsumerFilter.WebRoute("GET", null, "^/api/items/first$")), List.of())), new WebHandler());
        fixture.whenGet("/api/items/first").expectResult("ok");
        assertThrows(GivenWhenThenAssertionError.class,
                     () -> fixture.whenGet("/api/items/second").expectResult("ok"));
    }

    @Test
    void consumerSpecificOffSwitchDoesNotDisableAnotherInstanceOfTheSameHandler() {
        var first = new NamedHandler("first");
        var second = new NamedHandler("second");
        var builder = builder(SynchronousConsumerFilterTest::rejectPayloads)
                .addConsumerConfiguration(ConsumerConfiguration.builder().name("first")
                        .handlerFilter(h -> h == first).consumerFiltering(false).build(), MessageType.EVENT)
                .addConsumerConfiguration(ConsumerConfiguration.builder().name("second")
                        .handlerFilter(h -> h == second).build(), MessageType.EVENT);
        TestFixture.create(builder, first, second).whenEvent(new Accepted("input")).expectOnlyEvents("first");
    }

    @Test
    void synchronousHandlingDoesNotExecuteSerializedDispatchInterceptors() {
        AtomicInteger serializedInterceptions = new AtomicInteger();
        var builder = DefaultFluxzero.builder().addDispatchInterceptor(new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                return message;
            }

            @Override
            public SerializedMessage modifySerializedMessage(SerializedMessage wire, Message logical,
                                                               MessageType type, String topic) {
                serializedInterceptions.incrementAndGet();
                return wire.withData(wire.getData().withType("DifferentWireType"));
            }
        }, MessageType.COMMAND);
        TestFixture.create(builder, new CommandHandler()).whenCommand(new Accepted("local-execution"))
                .expectEvents("local-execution");
        assertEquals(0, serializedInterceptions.get());
    }

    record NamedHandler(String name) {
        @HandleEvent void handle(Accepted input) { Fluxzero.publishEvent(name); }
    }

    private static DefaultFluxzero.Builder builder(UnaryOperator<ConsumerFilter> adjustment) {
        var serializer = spy(new JacksonSerializer());
        doAnswer(call -> adjustment.apply((ConsumerFilter) call.callRealMethod()))
                .when(serializer).describeConsumerFilter(any());
        return DefaultFluxzero.builder().replaceSerializer(serializer);
    }

    private static ConsumerFilter rejectPayloads(ConsumerFilter filter) {
        return ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT, List.of(), null, List.of());
    }

    private static SerializedMessage legacy(String id) {
        return new SerializedMessage(new Data<>("{}".getBytes(StandardCharsets.UTF_8), "OldAccepted", 0,
                                                Data.JSON_FORMAT), Metadata.empty(), id, 0L);
    }

    record Accepted(String value) {}
    record Other(String value) {}
    @Consumer(name = "shared")
    static class EventHandler {
        @HandleEvent void handle(Accepted input) { Fluxzero.publishEvent(input.value()); }
    }
    @Consumer(name = "shared")
    static class OtherHandler {
        @HandleEvent void handle(Other input) { Fluxzero.publishEvent(input.value()); }
    }
    static class CommandHandler {
        @HandleCommand void handle(Accepted input) { Fluxzero.publishEvent(input.value()); }
    }
    static class LocalQueries {
        @LocalHandler @HandleQuery String handle(Accepted input) { return input.value(); }
    }
    record LocalQuery() { @HandleQuery String handle() { return "self"; } }
    @TrackSelf record SelfCommand() { @HandleCommand String handle() { return "self"; } }
    @Model(searchable = false) record ExampleModel(@EntityId String id) {}
    record CreateModel(String id) { @Apply ExampleModel apply() { return new ExampleModel(id); } }
    @Path("/api") static class WebHandler {
        @HandleGet("/items/{id}") String get() { return "ok"; }
    }
    static class Caster {
        @Upcast(type = "OldAccepted", revision = 0)
        Data<ObjectNode> cast(Data<ObjectNode> data) {
            data.getValue().put("value", "legacy");
            return data.withType(Accepted.class.getName());
        }
    }
}
