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
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.scheduling.Schedule;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.scheduling.Deadline;
import io.fluxzero.sdk.scheduling.DeadlineInfo;
import io.fluxzero.sdk.scheduling.DeadlineMetadata;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelDeadlineMetadataTest {
    static final java.util.concurrent.atomic.AtomicInteger liveApplies = new java.util.concurrent.atomic.AtomicInteger();
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z"), DUE = START.plusSeconds(3600);

    TestFixture fixture(boolean async, boolean document) {
        Object model = document ? DocumentAlarm.class : EventAlarm.class;
        return (async ? TestFixture.createAsync(model, new Delivery()) : TestFixture.create(model, new Delivery()))
                .atFixedTime(START);
    }

    Object put(boolean document, String payload, int unrelated) {
        return document ? new PutDocument("alarm", payload, unrelated) : new PutEvent("alarm", payload, unrelated);
    }

    Graph<?> graph(boolean document) {
        return document ? Fluxzero.loadGraph("alarm", DocumentAlarm.class) : Fluxzero.loadGraph("alarm", EventAlarm.class);
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void originalTimeSurvivesUnrelatedWritesReloadAndElapsedPayloadChanges(boolean async, boolean document) {
        var original = new AtomicReference<Map<String, DeadlineInfo>>();
        fixture(async, document).givenCommands(put(document, "first", 0))
                .given(fc -> original.set(graph(document).deadlines()))
                .givenTimeAdvancedTo(START.plusSeconds(600))
                .whenCommand(put(document, "first", 1))
                .expectSuccessfulResult().expectNoErrors().expectNoNewSchedules()
                .expectThat(fc -> {
                    assertEquals(DUE, original.get().get("default").deadline());
                    assertEquals(original.get(), graph(document).deadlines());
                    fc.cache().clear();
                    assertEquals(original.get(), graph(document).deadlines());
                    assertThrows(UnsupportedOperationException.class, () -> graph(document).deadlines().clear());
                }).andThen().givenTimeAdvancedTo(DUE.plusSeconds(600))
                .whenCommand(put(document, "second", 2))
                .expectSuccessfulResult().expectNoErrors().expectNoNewSchedules()
                .expectOnlyActiveScheduledCommands()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(original.get(), graph(document).deadlines());
                });
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void nullThenPayloadStartsAnExplicitNewCycle(boolean async, boolean document) {
        fixture(async, document).givenCommands(put(document, "first", 0))
                .givenTimeAdvancedTo(DUE)
                .whenCommand(put(document, null, 1))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> { fc.cache().clear(); assertTrue(graph(document).deadlines().isEmpty()); })
                .andThen().whenCommand(put(document, "second", 2))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands("second")
                .expectThat(fc -> assertEquals(DUE.plusSeconds(3600), graph(document).deadlines().get("default").deadline()));
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void inputCannotForgeReservedDeadlineMetadata(boolean async, boolean document) {
        var forged = DeadlineMetadata.with(Metadata.of(DeadlineMetadata.PREFIX + "forged", "untrusted"), "alarm",
                Map.of("fake", new DeadlineInfo("fake", START, false, false)));
        fixture(async, document).whenCommand(new Message(put(document, "first", 0), forged))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(java.util.Set.of("default"), graph(document).deadlines().keySet());
                    assertEquals(DUE, graph(document).deadlines().get("default").deadline());
                });
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void materializedGraphsKeepTheirOwnRevisionMetadata(boolean async, boolean document) {
        var original = new AtomicReference<Graph<?>>();
        fixture(async, document).givenCommands(put(document, "first", 0))
                .given(fc -> original.set(document ? Fluxzero.searchGraph(DocumentAlarm.class).fetchFirst().orElseThrow()
                        : Fluxzero.searchGraph(EventAlarm.class).fetchFirst().orElseThrow()))
                .givenTimeAdvancedTo(START.plusSeconds(600))
                .whenCommand(put(document, "second", 1))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(DUE, original.get().deadlines().get("default").deadline());
                    assertEquals(DUE.plusSeconds(600), graph(document).deadlines().get("default").deadline());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptRebaseCanAddOrRemoveDerivedDeadlineRevisions(boolean active) {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(ModelConflictPolicy.ACCEPT,
                        context -> ModelConflictResolver.Resolution.RETRY, 3).build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.withClock(Clock.fixed(START, ZoneOffset.UTC));
            writer.withClock(Clock.fixed(START, ZoneOffset.UTC));
            commit(app, new SetPolicy("policy", DUE));
            commit(app, new SetDependent("dependent", "policy", active));
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getSubsteps().stream().flatMap(step -> step.getTargets().stream())
                        .anyMatch(target -> target.getModelId().equals("policy"))) {
                    assertEquals(ModelConflictPolicy.ACCEPT, request.getConflictPolicy());
                    if (once.compareAndSet(false, true)) {
                        commit(writer, new SetDependent("dependent", "policy", !active));
                    }
                }
            };
            commit(app, new SetPolicy("policy", DUE.plusSeconds(60)));
            app.apply(fc -> {
                fc.cache().clear();
                Graph<Dependent> graph = Fluxzero.loadGraph("dependent", Dependent.class);
                assertEquals(!active, graph.get().active());
                if (active) { assertTrue(graph.deadlines().isEmpty()); }
                else { assertEquals(DUE.plusSeconds(60), graph.deadlines().get("default").deadline()); }
                return null;
            });
            assertTrue(once.get());
        }
    }

    @ParameterizedTest @CsvSource({"false,FAIL", "true,FAIL", "false,ACCEPT", "true,ACCEPT", "false,RETRY", "true,RETRY"})
    void runtimeExpiryRebuildsMetadataWithoutReapplyingOrChangingConflictPolicy(
            boolean document, ModelConflictPolicy policy) {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(policy, context -> { fail("Expiry must not invoke the conflict resolver");
                    return ModelConflictResolver.Resolution.RETRY; }, 0).build(client)) {
            app.withClock(Clock.fixed(START, ZoneOffset.UTC));
            commit(app, put(document, "first", 0));
            var original = app.apply(fc -> graph(document).deadlines());
            app.withClock(Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC));
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            client.beforeCommit = request -> {
                assertEquals(policy, request.getConflictPolicy());
                if (attempts.incrementAndGet() == 1) {
                    var deadlines = assertInstanceOf(io.fluxzero.common.api.modeling.CommitModelsWithDeadlines.class, request);
                    assertEquals(DUE.toEpochMilli(), deadlines.getDeadlineUpdates().getFirst().previousDeadline());
                    // The service clock reaches the old deadline while the application's clock stays behind.
                    client.setClock(Clock.fixed(DUE, ZoneOffset.UTC));
                } else {
                    assertFalse(request instanceof io.fluxzero.common.api.modeling.CommitModelsWithDeadlines);
                }
            };
            liveApplies.set(0);
            commit(app, put(document, "second", 1));
            assertEquals(1, liveApplies.get());
            assertEquals(2, attempts.get());
            app.apply(fc -> {
                fc.cache().clear();
                assertEquals(original, graph(document).deadlines());
                assertEquals(document ? -1 : 1, graph(document).sequenceNumber());
                assertEquals("second", document ? Fluxzero.loadGraph("alarm", DocumentAlarm.class).get().payload()
                        : Fluxzero.loadGraph("alarm", EventAlarm.class).get().payload());
                return null;
            });
        }
    }

    @org.junit.jupiter.api.Test
    void expiryDoesNotBypassASubsequentStrictConflict() {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(ModelConflictPolicy.FAIL, context -> ModelConflictResolver.Resolution.FAIL, 0)
                .build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.withClock(Clock.fixed(START, ZoneOffset.UTC));
            writer.withClock(Clock.fixed(START, ZoneOffset.UTC));
            commit(app, put(false, "first", 0));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            client.beforeCommit = request -> {
                int call = calls.incrementAndGet();
                if (call == 1) { client.setClock(Clock.fixed(DUE, ZoneOffset.UTC)); }
                if (call == 2) { commit(writer, put(false, "concurrent", 2)); }
            };
            assertThrows(Exception.class, () -> commit(app, put(false, "second", 1)));
            app.apply(fc -> { fc.cache().clear();
                assertEquals("concurrent", Fluxzero.loadGraph("alarm", EventAlarm.class).get().payload()); return null; });
        }
    }

    @org.junit.jupiter.api.Test
    void atomicGraphUpdateReplansDeadlinesWithoutRepeatingItsCallback() {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.withClock(Clock.fixed(START, ZoneOffset.UTC));
            commit(app, put(false, "first", 0));
            var original = app.apply(fc -> graph(false).deadlines());
            client.beforeCommit = request -> client.setClock(Clock.fixed(DUE, ZoneOffset.UTC));
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            app.apply(fc -> {
                Graph<EventAlarm> result = Fluxzero.loadGraph("alarm", EventAlarm.class).updateAndGet(graph -> {
                    callbacks.incrementAndGet(); return graph.update(current -> new EventAlarm("alarm", "second", 1));
                });
                assertEquals(1, callbacks.get());
                assertEquals("second", result.get().payload());
                assertEquals(original, result.deadlines());
                fc.cache().clear(); assertEquals(original, graph(false).deadlines()); return null;
            });
        }
    }

    @org.junit.jupiter.api.Test
    void successiveCutoffsKeepMillisecondPrecisionAndTheOriginalDelayAnchor() {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(ModelConflictPolicy.FAIL, context -> ModelConflictResolver.Resolution.FAIL, 0)
                .build(client)) {
            app.withClock(Clock.fixed(START, ZoneOffset.UTC));
            commit(app, new PutMultiple("multiple", "first", false));
            var original = app.apply(fc -> Fluxzero.loadGraph("multiple", Multiple.class).deadlines());
            app.withClock(Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC));
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var boundary = new AtomicReference<Long>();
            client.beforeCommit = request -> {
                int call = attempts.incrementAndGet();
                if (call == 1) { boundary.set(request.getReadStateIndex()); }
                assertEquals(boundary.get(), request.getReadStateIndex());
                var deadlines = assertInstanceOf(io.fluxzero.common.api.modeling.CommitModelsWithDeadlines.class, request);
                assertEquals(4 - call, deadlines.getDeadlineUpdates().size());
                client.setClock(Clock.fixed(START.plusSeconds(call == 1 ? 10 : 20), ZoneOffset.UTC));
            };
            commit(app, new PutMultiple("multiple", "changed", true));
            assertEquals(3, attempts.get());
            app.apply(fc -> { fc.cache().clear(); var deadlines = Fluxzero.loadGraph("multiple", Multiple.class).deadlines();
                assertEquals(original.get("first"), deadlines.get("first"));
                assertEquals(original.get("second"), deadlines.get("second"));
                assertEquals(START.plusSeconds(61), deadlines.get("new").deadline()); return null; });
        }
    }

    @Model(searchable = false)
    record Multiple(@EntityId String multipleId, String payload, boolean extra) {
        @Deadline("first") Schedule first() { return new Schedule(payload, START.plusSeconds(10).plusNanos(500_000)); }
        @Deadline("second") Schedule second() { return new Schedule(payload, START.plusSeconds(20)); }
        @Deadline(value = "new", delay = 60, timeUnit = TimeUnit.SECONDS) String extraDeadline() { return extra ? payload : null; }
    }
    record PutMultiple(String multipleId, String payload, boolean extra) {
        @Apply Multiple apply(@jakarta.annotation.Nullable Multiple current) { return new Multiple(multipleId, payload, extra); }
    }

    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(Message.asMessage(command)).join());
    }

    private static class GateClient extends LocalClient {
        volatile Consumer<CommitModels> beforeCommit = ignored -> {};
        GateClient() { super(null); }
        @Override protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("commitModels")) { beforeCommit.accept((CommitModels) arguments[0]); }
                        try { return method.invoke(delegate, arguments); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        }
    }

    @Model(searchable = false, conflictPolicy = ModelConflictPolicy.ACCEPT)
    record Policy(@EntityId String policyId, Instant due) {}
    record SetPolicy(String policyId, Instant due) {
        @Apply Policy apply(@jakarta.annotation.Nullable Policy current) { return new Policy(policyId, due); }
    }
    @Model(searchable = false, conflictPolicy = ModelConflictPolicy.ACCEPT)
    record Dependent(@EntityId String dependentId, @Parent(Policy.class) String policyId, boolean active) {
        @Deadline Schedule deadline(Policy policy) { return active ? new Schedule("expire", policy.due()) : null; }
    }
    record SetDependent(String dependentId, String policyId, boolean active) {
        @Apply Dependent apply(@jakarta.annotation.Nullable Dependent current) {
            return new Dependent(dependentId, policyId, active);
        }
    }

    @Model(searchable = true, snapshotPeriod = 1)
    record EventAlarm(@EntityId String eventAlarmId, String payload, int unrelated) {
        @Deadline(delay = 1, timeUnit = TimeUnit.HOURS) String deadline() { return payload; }
    }
    record PutEvent(String eventAlarmId, String payload, int unrelated) {
        @Apply EventAlarm apply(@jakarta.annotation.Nullable EventAlarm current) {
            if (!Entity.isLoading()) { liveApplies.incrementAndGet(); }
            return new EventAlarm(eventAlarmId, payload, unrelated);
        }
    }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT, eventPublication = EventPublication.NEVER)
    record DocumentAlarm(@EntityId String documentAlarmId, String payload, int unrelated) {
        @Deadline(delay = 1, timeUnit = TimeUnit.HOURS) String deadline() { return payload; }
    }
    record PutDocument(String documentAlarmId, String payload, int unrelated) {
        @Apply DocumentAlarm apply(@jakarta.annotation.Nullable DocumentAlarm current) {
            if (!Entity.isLoading()) { liveApplies.incrementAndGet(); }
            return new DocumentAlarm(documentAlarmId, payload, unrelated);
        }
    }
    static class Delivery { @HandleCommand void handle(String payload) {} }
}
