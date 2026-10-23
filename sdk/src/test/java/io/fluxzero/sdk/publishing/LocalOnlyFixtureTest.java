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

package io.fluxzero.sdk.publishing;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.*;

class LocalOnlyFixtureTest {
    private TestFixture fixture(boolean async, Object... handlers) {
        return async ? TestFixture.createAsync(handlers) : TestFixture.create(handlers);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void automaticModelHandlingIsNotALocalHandler(boolean async) {
        fixture(async).whenCommand(new AutomaticCommand("missing"))
                .expectExceptionalResult(LocalOnlyDispatchException.class)
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadGraph("missing", Counter.class).isEmpty());
                    assertTrue(fc.client().getTrackingClient(COMMAND).readFromIndex(0, 10).isEmpty());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitSelfHandlerCanApplyModelsLocally(boolean async) {
        fixture(async).whenCommand(new ExplicitCommand("local"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(new Counter("local", 1), Fluxzero.loadGraph("local", Counter.class).get());
                    assertTrue(fc.client().getTrackingClient(COMMAND).readFromIndex(0, 10).isEmpty());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void trackedHandlerDoesNotSatisfyLocalOnly(boolean async) {
        fixture(async, new TrackedHandler()).whenCommand(new LocalCommand())
                .expectExceptionalResult(LocalOnlyDispatchException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void trackSelfDoesNotSatisfyLocalOnly(boolean async) {
        fixture(async).whenCommand(new TrackedSelfCommand())
                .expectExceptionalResult(LocalOnlyDispatchException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realLocalHandlerRemainsAvailableAfterTrackedCandidate(boolean async) {
        fixture(async, new TrackedHandler(), new LocalCommandHandler()).whenCommand(new LocalCommand())
                .expectResult("local").expectNoErrors();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restrictionSurvivesReplacementWithUnmarkedPayload(boolean async) {
        var builder = DefaultFluxzero.builder().addDispatchInterceptor((message, type, topic) ->
                message.getPayload() instanceof LocalCommand ? message.withPayload(new OrdinaryCommand()) : message,
                COMMAND);
        (async ? TestFixture.createAsync(builder, new TrackedHandler())
                : TestFixture.create(builder, new TrackedHandler())).whenCommand(new LocalCommand())
                .expectExceptionalResult(LocalOnlyDispatchException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryTrackingStillWorks(boolean async) {
        fixture(async, new TrackedHandler()).whenCommand(new OrdinaryCommand()).expectResult("tracked");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitlyRegisteredSelfHandlerStillWorks(boolean async) {
        fixture(async, ExplicitCommand.class).whenCommand(new ExplicitCommand("registered"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(new Counter("registered", 1),
                        Fluxzero.loadGraph("registered", Counter.class).get()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitCustomLocalRegistrationAndCancellationRemainSupported(boolean async) {
        fixture(async).whenExecuting(fc -> {
            var registration = fc.commandGateway().registerHandler(new TrackedHandler(), HandlerFilter.ALWAYS_HANDLE);
            assertEquals("tracked", Fluxzero.sendCommandAndWait(new LocalCommand()));
            registration.cancel();
            assertThrows(LocalOnlyDispatchException.class, () -> Fluxzero.sendCommandAndWait(new LocalCommand()));
        }).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void registeredClassSharesOneInstanceAcrossDispatchModes(boolean async) {
        fixture(async, MutableLocalHandler.class).whenExecuting(fc -> {
            assertEquals(1, Fluxzero.<Integer>sendCommandAndWait(new Increment()));
            assertEquals(1, Fluxzero.<Integer>sendCommandAndWait(new ReadCounter()));
            assertEquals(2, Fluxzero.<Integer>sendCommandAndWait(new Increment()));
            assertEquals(2, Fluxzero.<Integer>sendCommandAndWait(new ReadCounter()));
        }).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mixedMethodsSelectLocalCandidateAndRetainOrdinaryCandidate(boolean async) {
        fixture(async, new MixedHandler()).whenExecuting(fc -> {
            assertEquals("ordinary", Fluxzero.sendCommandAndWait(new OrdinaryCommand()));
            assertEquals("local", Fluxzero.sendCommandAndWait(new LocalCommand()));
            assertEquals("ordinary", Fluxzero.sendCommandAndWait(new OrdinaryCommand()));
        }).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modeChangesRetainLocalEligibility(boolean async) {
        fixture(async).async().sync().whenCommand(new TrackedSelfCommand())
                .expectExceptionalResult(LocalOnlyDispatchException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void registeredBaseDoesNotHideInheritedLocalSelfHandler(boolean async) {
        fixture(async, BaseSelfCommand.class).whenCommand(new DerivedSelfCommand()).expectResult("self");
    }

    static class BaseSelfCommand {
        @HandleCommand String handle() { return "self"; }
    }
    @LocalOnly static class DerivedSelfCommand extends BaseSelfCommand {}

    record Increment() {}
    @LocalOnly record ReadCounter() {}
    @LocalHandler static class MutableLocalHandler {
        private int count;
        @HandleCommand int handle(Increment command) { return ++count; }
        @HandleCommand int handle(ReadCounter command) { return count; }
    }
    static class MixedHandler {
        @HandleCommand String handle(OrdinaryCommand command) { return "ordinary"; }
        @HandleCommand String handle(LocalCommand command) { return "wrong"; }
        @HandleCommand @LocalHandler String local(LocalMessage command) { return "local"; }
    }

    @Model(searchable = false) record Counter(@EntityId String id, int value) {}
    @LocalOnly record AutomaticCommand(String id) {
        @Apply Counter apply() { return new Counter(id, 1); }
    }
    @LocalOnly record ExplicitCommand(String id) {
        @HandleCommand void handle() { Fluxzero.assertAndApply(this); }
        @Apply Counter apply() { return new Counter(id, 1); }
    }
    interface LocalMessage {}
    @LocalOnly record LocalCommand() implements LocalMessage {}
    record OrdinaryCommand() {}
    @LocalOnly @TrackSelf record TrackedSelfCommand() {
        @HandleCommand String handle() { return "tracked"; }
    }
    static class TrackedHandler {
        @HandleCommand String handle(LocalCommand command) { return "tracked"; }
        @HandleCommand String handle(OrdinaryCommand command) { return "tracked"; }
    }
    @LocalHandler static class LocalCommandHandler {
        @HandleCommand String handle(LocalCommand command) { return "local"; }
    }
}
