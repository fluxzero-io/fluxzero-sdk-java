/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualGraphReplayTest {
    private static final ThreadLocal<Set<String>> replaying = ThreadLocal.withInitial(HashSet::new);

    @ParameterizedTest
    @ValueSource(strings = {"typed", "untyped", "current", "literal", "current-literal", "refresh", "options"})
    void manualReadOfDynamicWriteTargetReplaysItsPreviousValue(String route) {
        verifyColdRead(new ManualIncrement("counter", route));
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "untyped", "current", "literal", "current-literal", "refresh", "options"})
    void laterSubstepSeesEarlierWritesFromTheSameCommit(String route) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new IncrementTwice(route))).join());
            app.cache().clear();
            assertEquals(3, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
            assertNull(app.apply(fc -> CommitAttempt.currentReplayContext(fc.modelRepository())));
        }
    }

    record IncrementTwice(String route) {
        @InterceptApply List<ManualIncrement> split() {
            return List.of(new ManualIncrement("counter", route), new ManualIncrement("counter", route));
        }
    }

    @Test
    void publishedEventHandlersRetainPostEventState() {
        List<Integer> values = new CopyOnWriteArrayList<>();
        Object handler = new Object() {
            @HandleEvent void changed(ManualIncrement event) {
                values.add(Fluxzero.loadGraph(event.selectedId(), Counter.class).get().value());
            }
        };
        TestFixture.create(handler).givenCommands(new CreateCounter("counter"))
                .whenCommand(new ManualIncrement("counter", "typed"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(List.of(2), values));
    }

    @Test
    void injectedReadOfDynamicWriteTargetReplaysItsPreviousValue() {
        verifyColdRead(new InjectedIncrement("counter"));
    }

    @Test
    void manualReadOfSiblingChangedBySameEventUsesPreviousValue() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new IncrementAndObserve("counter", "receipt", "typed"))).join());
            assertEquals(1, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
            app.cache().clear();
            assertEquals(1, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
        }
    }

    @Test
    void modelPhaseRetainsThePayloadPhaseOverlay() {
        TestFixture.create(Counter.class).givenCommands(new CreateCounter("counter"))
                .whenCommand(new PhaseIncrement("counter"))
                .expectSuccessfulResult().expectNoErrors().expectThat(fc -> {
                    assertEquals(3, Fluxzero.loadModel("counter", Counter.class).get().value());
                    fc.cache().clear();
                    assertEquals(3, Fluxzero.loadModel("counter", Counter.class).get().value());
                });
    }

    record PhaseIncrement(String counterId) {
        @Apply Counter apply(Counter before) { return new Counter(counterId, before.value() + 1); }
    }

    @Test
    void explicitHistoricalReadsDoNotInheritTheReplayInputOverlay() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            long original = app.apply(fc -> {
                Graph<Counter> graph = Fluxzero.loadGraph("counter", Counter.class);
                graph.get();
                return graph.stateIndex();
            });
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new InjectedIncrement("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new HistoricalIncrement("counter", original))).join());
            assertEquals(11, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
            app.cache().clear();
            assertEquals(11, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
        }
    }

    record HistoricalIncrement(String counterId, long stateIndex) {
        @Apply List<Counter> apply(Counter current) {
            Counter before = Fluxzero.loadGraph(counterId, Counter.class).atStateIndex(stateIndex).get();
            return List.of(new Counter(before.counterId(), before.value() + 10));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alias", "literal", "untyped"})
    void replayPreservesAliasAndAffixSelection(String route) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateAliased("owner"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new IncrementAliased(route))).join());
            app.cache().clear();
            assertEquals(2, app.apply(fc -> Fluxzero.loadModel("owner", AliasedCounter.class).get().value()).intValue());
        }
    }

    @Model(searchable = false)
    record AliasedCounter(@EntityId(prefix = "counter-") String id, @Alias String alias, int value) {}
    record CreateAliased(String id) {
        @Apply AliasedCounter apply() { return new AliasedCounter(id, "selected", 1); }
    }
    record IncrementAliased(String route) {
        @Apply List<AliasedCounter> apply() {
            AliasedCounter before = switch (route) {
                case "literal" -> Fluxzero.loadGraph("counter-owner", AliasedCounter.class, false).get();
                case "untyped" -> (AliasedCounter) Fluxzero.loadGraph((Object) "selected").get();
                default -> Fluxzero.loadGraph("selected", AliasedCounter.class).get();
            };
            return List.of(new AliasedCounter(before.id(), before.alias(), before.value() + 1));
        }
    }

    @Test
    void freshApplicationReadsRetainedCommitsWithoutReseeding() {
        LocalClient client = LocalClient.newInstance();
        try (Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            writer.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            writer.apply(fc -> fc.executeModelCommit(Message.asMessage(new ManualIncrement("counter", "typed"))).join());
            try (Fluxzero reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
                assertEquals(2, reader.apply(fc -> Fluxzero.loadGraph("counter", Counter.class).get().value()).intValue());
                assertNull(reader.apply(fc -> CommitAttempt.currentReplayContext(fc.modelRepository())));
            }
        }
    }

    @Test
    void foreignNamespaceDoesNotBorrowReplayContext() {
        LocalClient client = LocalClient.newInstance();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             Fluxzero other = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(client.forNamespace("other"))) {
            other.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CheckForeign("counter"))).join());
            app.cache().clear();
            assertEquals(2, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
        }
    }

    record CheckForeign(String selectedId) {
        @Apply List<Counter> apply() {
            var other = Fluxzero.get().modelRepository().forNamespace("other");
            assertNull(CommitAttempt.currentReplayContext(other));
            assertEquals(1, Graphs.lazyCurrent(selectedId, Counter.class, other).get().value());
            Counter before = Fluxzero.loadGraph(selectedId, Counter.class).get();
            return List.of(new Counter(before.counterId(), before.value() + 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "options"})
    void replayDoesNotBorrowPendingValuesFromTheAmbientBatch(String route) {
        TestFixture.create().givenCommands(new CreateCounter("counter"),
                        new IncrementAndObserve("counter", "receipt", route))
                .whenExecuting(fc -> {
                    fc.cache().clear();
                    var messages = List.of("stage", "read").stream()
                            .map(value -> new DeserializingMessage(new Message(value), MessageType.QUERY, fc.serializer()))
                            .toList();
                    DeserializingMessage.forEachInBatch(messages, current -> {
                        if (DeserializingMessage.getMessageBatchIndex() == 0) {
                            ModelBatchScope.stageOwned(((DefaultModelRepository) fc.modelRepository()).modelDefinitionCompiler(),
                                    null, CommitAttempt.fromChanges(-1L, List.of("counter"), Map.of("counter", Counter.class),
                                            current, List.of(Change.applied("counter", Counter.class, 1L, null,
                                                    new Counter("counter", 2), new Counter("counter", 99), null, null, false))));
                        } else {
                            assertEquals(99, Fluxzero.loadGraph("counter", Counter.class).get().value());
                            assertEquals(1, Fluxzero.loadModel("receipt", Receipt.class).get().observed());
                            assertNull(CommitAttempt.currentReplayContext(fc.modelRepository()));
                        }
                    });
                }).expectNoErrors();
    }

    @Model(searchable = false)
    record Receipt(@EntityId String receiptId, int observed) {}

    record IncrementAndObserve(String counterId, String receiptId, String route) {
        @Apply Counter increment(Counter before) { return new Counter(counterId, before.value() + 1); }
        @Apply Receipt observe() {
            Graph<Counter> graph = route.equals("options")
                    ? Fluxzero.get().modelRepository().loadGraph(counterId, Counter.class, Graph.Options.DEFAULT)
                    : Fluxzero.loadGraph(counterId, Counter.class);
            return new Receipt(receiptId, graph.get().value());
        }
    }

    private void verifyColdRead(Object increment) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(increment)).join());
            assertEquals(2, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
            app.cache().clear();
            assertEquals(2, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
        }
    }

    @Model(searchable = false)
    record Counter(@EntityId String counterId, int value) {
        @Apply Counter after(PhaseIncrement event) {
            Counter staged = Fluxzero.loadGraph(counterId, Counter.class).get();
            return new Counter(counterId, staged.value() + 1);
        }
    }

    record CreateCounter(String counterId) {
        @Apply Counter apply() { return new Counter(counterId, 1); }
    }

    record ManualIncrement(String selectedId, String route) {
        @Apply List<Counter> apply() {
            var message = DeserializingMessage.getCurrent();
            String key = message.getMessageId() + ":" + message.getMetadata().get(ModelEventMetadata.SUBSTEP);
            Set<String> active = replaying.get();
            // Earlier substeps may legitimately reconstruct here; only re-entry of this exact event is a cycle.
            if (!active.add(key)) { throw new AssertionError("Replay recursively re-entered the same transition"); }
            try {
                assertTrue(CommitAttempt.currentReplayContext(Fluxzero.get().modelRepository()) == null
                        || Entity.isLoading());
                Counter before = switch (route) {
                    case "untyped" -> (Counter) Fluxzero.loadGraph((Object) selectedId).get();
                    case "current" -> Fluxzero.loadCurrentGraph(selectedId, Counter.class).get();
                    case "literal" -> Fluxzero.loadGraph(selectedId, Counter.class, false).get();
                    case "current-literal" -> Fluxzero.loadCurrentGraph(selectedId, Counter.class, false).get();
                    case "refresh" -> Fluxzero.loadGraph(selectedId, Counter.class).current().get();
                    case "options" -> Fluxzero.get().modelRepository().loadGraph(selectedId, Counter.class, Graph.Options.DEFAULT).get();
                    default -> Fluxzero.loadGraph(selectedId, Counter.class).get();
                };
                return List.of(new Counter(before.counterId(), before.value() + 1));
            } finally {
                active.remove(key);
                if (active.isEmpty()) { replaying.remove(); }
            }
        }
    }

    record InjectedIncrement(String counterId) {
        @Apply List<Counter> apply(Counter before) {
            return List.of(new Counter(before.counterId(), before.value() + 1));
        }
    }
}
