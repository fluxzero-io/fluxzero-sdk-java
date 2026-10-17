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
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.DefaultTracking.PreparedHandler;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.RepositoryProvider;
import io.fluxzero.sdk.web.ApiDocInfo;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleHead;
import io.fluxzero.sdk.web.HandleOptions;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.ServeStatic;
import io.fluxzero.sdk.web.SocketEndpoint;
import io.fluxzero.sdk.web.WebParamParameterResolver;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        var fixture = TestFixture.create(builder(filter -> ConsumerFilter.select(null, 0, ConsumerFilter.VERSION, Data.JSON_FORMAT,
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

    @ParameterizedTest
    @CsvSource({"GET,/shared/openapi.json", "HEAD,/shared/openapi.json", "OPTIONS,/shared/openapi.json",
            "GET,/shared/docs", "HEAD,/shared/docs", "OPTIONS,/shared/docs",
            "GET,/shared/assets/index.html", "HEAD,/shared/assets/index.html", "OPTIONS,/shared/assets/index.html"})
    void sharedWebHelpersSurviveDifferentLocalAndTrackedOwners(String method, String path) {
        var first = new SharedWebHelpers("/first");
        var second = new SharedWebHelpers("/second");
        var serializer = new JacksonSerializer();
        var localFactory = webFactory(serializer);
        var trackedFactory = webFactory(serializer);
        var localFirst = localFactory.createHandler(first, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var localSecond = localFactory.createHandler(second, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        // Consumer preparation may visit groups in a different order than local registration.
        var trackedSecond = trackedFactory.createHandler(second, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var trackedFirst = trackedFactory.createHandler(first, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var message = serializer.deserializeMessage(WebRequest.builder().method(method).url(path + "?x=1")
                                                            .build().serialize(serializer), MessageType.WEBREQUEST);
        assertTrue(localFirst.getInvoker(message).isPresent());
        assertTrue(localSecond.getInvoker(message).isEmpty(), "automatic endpoints retain one local owner");
        assertTrue(trackedSecond.getInvoker(message).isPresent());
        assertTrue(trackedFirst.getInvoker(message).isEmpty(), "automatic endpoints retain one tracked owner");
        try (var app = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().build(LocalClient.newInstance())) {
            var consumers = new SynchronousConsumers();
            var firstRegistration = consumers.observe(app, MessageType.WEBREQUEST,
                    ConsumerConfiguration.builder().name("first").consumerFiltering(true).build(),
                    List.of(new PreparedHandler(first, trackedFirst)));
            var secondRegistration = consumers.observe(app, MessageType.WEBREQUEST,
                    ConsumerConfiguration.builder().name("second").consumerFiltering(true).build(),
                    List.of(new PreparedHandler(second, trackedSecond)));
            try {
                assertTrue(consumers.wrap(first, localFirst).getInvoker(message).isPresent());
                assertTrue(trackedFirst.getConsumerFilter().routeRestricted());
                assertFalse(trackedFirst.getConsumerFilter().compile().test(
                        WebRequest.get("/unrelated").build().serialize(serializer)));
                assertFalse(trackedFirst.getConsumerFilter().compile().test(
                        WebRequest.post(path).build().serialize(serializer)));
            } finally {
                firstRegistration.cancel();
                secondRegistration.cancel();
            }
        }
    }

    @Test
    void duplicateHelperDescriptionsDoNotStealExplicitHttpHelpers() {
        var serializer = new JacksonSerializer();
        var factory = webFactory(serializer);
        var explicit = factory.createHandler(new Object() {
            @HandleHead("/shared/openapi.json") String head() { return "explicit-head"; }
            @HandleOptions("/shared/docs") String options() { return "explicit-options"; }
        }, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var owner = factory.createHandler(new SharedWebHelpers("/first"), HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var duplicate = factory.createHandler(new SharedWebHelpers("/second"), HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        for (var request : List.of(WebRequest.builder().method("HEAD").url("/shared/openapi.json").build(),
                                   WebRequest.builder().method("OPTIONS").url("/shared/docs").build())) {
            var message = serializer.deserializeMessage(request.serialize(serializer), MessageType.WEBREQUEST);
            assertTrue(explicit.getInvoker(message).isPresent());
            assertTrue(owner.getInvoker(message).isEmpty());
            assertTrue(duplicate.getInvoker(message).isEmpty());
        }
        var automatic = serializer.deserializeMessage(WebRequest.builder().method("OPTIONS")
                .url("/shared/assets/index.html").build().serialize(serializer), MessageType.WEBREQUEST);
        assertTrue(owner.getInvoker(automatic).isPresent(), "describing duplicates must not replace the route owner");
        assertTrue(duplicate.getInvoker(automatic).isEmpty());
    }

    @Test
    void sharedStaticRoutesRemainEligibleForSocketEndpoints() {
        var serializer = new JacksonSerializer();
        var factory = webFactory(serializer);
        factory.createHandler(new SharedWebHelpers("/first"), HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var socket = factory.createHandler(SharedStaticSocket.class, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        assertTrue(socket.getConsumerFilter().routeRestricted());
        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            var wire = WebRequest.builder().method(method).url("/shared/assets/index.html").build().serialize(serializer);
            assertTrue(socket.getConsumerFilter().compile().test(wire));
            assertTrue(socket.getInvoker(serializer.deserializeMessage(wire, MessageType.WEBREQUEST)).isEmpty());
        }
        assertFalse(socket.getConsumerFilter().compile().test(WebRequest.get("/unrelated").build().serialize(serializer)));
    }

    private static DefaultHandlerFactory webFactory(JacksonSerializer serializer) {
        return new DefaultHandlerFactory(MessageType.WEBREQUEST, HandlerDecorator.noOp,
                List.of(new MessageParameterResolver(), new WebParamParameterResolver()),
                MethodInvocationValidator.noOp(), ignored -> null, new RepositoryProvider() {
                    @Override public <T> Map<Object, T> getRepository(Class<T> type) {
                        return new java.util.concurrent.ConcurrentHashMap<>();
                    }
                }, false, serializer);
    }

    @SocketEndpoint
    @ServeStatic(value = "/shared/assets", resourcePath = "classpath:/web/static")
    static class SharedStaticSocket {
        @HandleSocketOpen("/socket") static SharedStaticSocket open() { return new SharedStaticSocket(); }
    }

    @ApiDocInfo(serveOpenApi = true, openApiPath = "/shared/openapi.json",
            serveApiReference = true, apiReferencePath = "/shared/docs")
    @ServeStatic(value = "/shared/assets", resourcePath = "classpath:/web/static")
    static class SharedWebHelpers {
        @Path final String path;
        SharedWebHelpers(String path) { this.path = path; }
        @HandleGet String get() { return path; }
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
                return ConsumerFilter.select(null, 0, ConsumerFilter.VERSION, null,
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
        var fixture = TestFixture.create(builder(filter -> ConsumerFilter.select(null, 0, ConsumerFilter.VERSION, Data.JSON_FORMAT,
                null, List.of(io.fluxzero.sdk.web.WebRequestRoute.pattern("GET", null, "^/api/items/first$")), List.of())), new WebHandler());
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
        return ConsumerFilter.select(null, 0, ConsumerFilter.VERSION, Data.JSON_FORMAT, List.of(), null, List.of());
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
