/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.test.contracts;

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.tracking.handling.Association;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;

/** Snapshot history and erasure contract shared by local, TestServer and JDBC clients. */
@Timeout(30)
public abstract class ModelSnapshotContract {
    /** Returns two clients sharing isolated storage, for a writer followed by a fresh reader application. */
    protected abstract Client[] clients(String namespace);

    @ParameterizedTest
    @CsvSource({"canonical,250,0", "canonical,1,1", "friendly,250,0", "friendly,252,2", "friendly,1,1"})
    void coldAliasReadsUseCanonicalSnapshots(String lookup, int history, int expectedPayloads) {
        Client[] clients = clients("alias-snapshot-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            int snapshotEnd = Math.max(1, history - history % 25);
            write(writer, new SetAliasedHistory("canonical", "friendly", snapshotEnd));
            for (int i = snapshotEnd + 1; i <= history; i++) {
                write(writer, new SetAliasedCounter("canonical", "friendly", i));
            }
            assertEquals(history / 25,
                    writer.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
        }
        var observed = new SnapshotReads(clients[1]);
        try (Fluxzero reader = app(observed.client)) {
            reader.apply(fc -> {
                assertEquals(new AliasedCounter("canonical", "friendly", history),
                        Fluxzero.loadModel(lookup, AliasedCounter.class, false).get());
                return null;
            });
            assertEquals(expectedPayloads, observed.payloads(), "only the suffix after the selected snapshot is replayed");
            if (lookup.equals("canonical")) {
                assertEquals(history >= 25 ? 1 : 2, observed.responses.size(),
                        "a primary snapshot hit needs one event call; an ambiguous snapshot miss also probes its head");
                assertEquals(1, observed.searches.get(), "primary reads retain their existing snapshot search count");
            } else {
                assertEquals(2, observed.responses.size(), "resolve the owner, then load its suffix at the same boundary");
                assertEquals(2, observed.searches.get(), "search the requested key and its newly discovered owner once each");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer", "snapshot", "erase"})
    void aliasSnapshotCannotChangeOwnerAdvanceOrSurviveErasure(String change) {
        Client[] clients = clients("alias-snapshot-race-" + UUID.randomUUID());
        var observed = new SnapshotReads(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new SetAliasedHistory("first", "friendly", 250));
            observed.afterRead.set(() -> {
                switch (change) {
                    case "transfer" -> {
                        write(writer, new SetAliasedCounter("first", "released", 251));
                        write(writer, new SetAliasedHistory("second", "friendly", 25));
                    }
                    case "snapshot" -> write(writer, new SetAliasedHistory("first", "friendly", 25));
                    case "erase" -> writer.modelRepository().deleteModel("first", ModelDeletionCascade.NONE).join();
                    default -> throw new AssertionError(change);
                }
            });
            reader.apply(fc -> {
                if (change.equals("erase")) {
                    assertThrows(EventSourcingException.class,
                            () -> Fluxzero.loadModel("friendly", AliasedCounter.class, false));
                    assertNull(Fluxzero.loadModel("friendly", AliasedCounter.class, false).get());
                } else {
                    assertEquals(new AliasedCounter("first", "friendly", 250),
                            Fluxzero.loadModel("friendly", AliasedCounter.class, false).get());
                    assertEquals(new AliasedCounter(change.equals("transfer") ? "second" : "first", "friendly", 25),
                            Fluxzero.loadModel("friendly", AliasedCounter.class, false).get());
                }
                return null;
            });
            assertNull(observed.afterRead.get(), "the interleaving must run after the owner probe");
            assertEquals(0, observed.payloads());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownAliasSnapshotPublicationRetainsItsOriginalCacheFence(boolean invalidate) {
        Client[] clients = clients("alias-snapshot-fence-" + UUID.randomUUID());
        var observed = new SnapshotReads(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new SetAliasedHistory("canonical", "friendly", 250));
            awaitCacheTracking(reader);
            reader.apply(fc -> {
                Fluxzero.loadModel("canonical", AliasedCounter.class, false);
                Fluxzero.loadModel("friendly", AliasedCounter.class, false);
                return null;
            });
            clearCacheAndAwait(reader);
            if (invalidate) { observed.afterRead.set(() -> clearCacheAndAwait(reader)); }
            reader.apply(fc -> {
                var alias = Fluxzero.loadModel("friendly", AliasedCounter.class, false).get();
                var primary = Fluxzero.loadModel("canonical", AliasedCounter.class, false).get();
                assertEquals(new AliasedCounter("canonical", "friendly", 250), alias);
                assertEquals(alias, primary);
                if (invalidate) { assertNotSame(alias, primary, "a clear during the probe must fence publication"); }
                else { assertSame(alias, primary, "the known owner's original fence permits publication"); }
                return null;
            });
        }
    }

    @Test
    void mixedPrimaryAndAliasBatchReusesEachSelectedSnapshot() {
        Client[] clients = clients("alias-snapshot-batch-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            write(writer, new SetAliasedHistory("first", "friendly", 250));
            write(writer, new SetAliasedHistory("other", "other-alias", 25));
        }
        var observed = new SnapshotReads(clients[1]);
        try (Fluxzero reader = app(observed.client)) {
            reader.apply(fc -> {
                assertEquals(List.of(new AliasedCounter("first", "friendly", 250),
                                new AliasedCounter("other", "other-alias", 25)),
                        fc.modelRepository().loadAll(List.of("friendly", "other"), AliasedCounter.class, false)
                                .stream().map(value -> value.get()).toList());
                return null;
            });
            assertEquals(0, observed.payloads());
            assertEquals(2, observed.responses.size());
            assertEquals(3, observed.searches.get(), "the batch must reuse the first primary snapshot search");
        }
    }

    @Test
    void primaryIdWithoutSnapshotTakesPrecedenceOverAnAliasedSnapshot() {
        Client[] clients = clients("alias-snapshot-primary-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0], true); Fluxzero reader = app(clients[1])) {
            write(writer, new SetAliasedHistory("canonical", "friendly", 250));
            write(writer, new SetAliasedCounter("friendly", "primary-alias", 17));
            reader.apply(fc -> {
                assertEquals(new AliasedCounter("friendly", "primary-alias", 17),
                        Fluxzero.loadModel("friendly", AliasedCounter.class, false).get());
                assertThrows(EventSourcingException.class,
                        () -> fc.modelRepository().loadAll(List.of("primary-alias", "friendly"),
                                AliasedCounter.class, false));
                return null;
            });
        }
    }

    @Test
    void snapshotDependencyCannotUseASnapshotFromALaterSubstepOfItsOwnCommit() {
        Client[] clients = clients("alias-snapshot-prefix-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            write(writer, new SetAliasedCounter("owner", "friendly", 1));
            write(writer, new CommitAliasedPrefix("dependent", "owner", "friendly"));
            assertEquals(1, writer.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
        }
        try (Fluxzero reader = app(clients[1])) {
            reader.apply(fc -> {
                assertEquals(new AliasDependent("dependent", 2),
                        Fluxzero.loadModel("dependent", AliasDependent.class, false).get());
                assertEquals(new AliasedCounter("owner", "friendly", 25),
                        Fluxzero.loadModel("friendly", AliasedCounter.class, false).get());
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 250})
    void graphWithAnAlreadySelectedOwnerDoesNotProbeItsHeadAgain(int history) {
        Client[] clients = clients("alias-snapshot-graph-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            write(writer, new SetAliasedHistory("canonical", "friendly", history));
        }
        var observed = new SnapshotReads(clients[1]);
        try (Fluxzero reader = app(observed.client)) {
            reader.apply(fc -> {
                Graph<AliasedCounter> graph = Fluxzero.loadGraph("friendly", AliasedCounter.class, false);
                assertEquals("canonical", graph.id());
                assertEquals(new AliasedCounter("canonical", "friendly", history), graph.get());
                return null;
            });
            assertEquals(2, observed.responses.size(), "the identity read already established the owner and boundary");
            assertEquals(1, observed.searches.get());
            assertEquals(history < 25 ? history : 0, observed.payloads());
        }
    }

    @Test
    void unlimitedSnapshotsSurviveClientRestartAndRemainErasable() {
        Client[] clients = clients("snapshot-contract-" + UUID.randomUUID());
        long boundary;
        try (Fluxzero writer = app(clients[0])) {
            writer.apply(fc -> fc.executeModelCommit(new Message(new SetCounter("counter", 1))).join());
            boundary = writer.apply(fc -> Fluxzero.loadGraph("counter", Counter.class).revisionStateIndex());
            write(writer, new SetCounter("counter", 2));
            write(writer, new SetCounterTwice("counter", 3));
            assertEquals(4, writer.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
        }
        try (Fluxzero reader = app(clients[1])) {
            reader.apply(fc -> {
                Graph<Counter> graph = Fluxzero.loadGraph("counter", Counter.class);
                for (int value = 4; value >= 1; value--) {
                    assertNotNull(graph, "Missing Graph for value " + value);
                    assertNotNull(graph.get(), "Missing state for value " + value + " at " + graph.stateIndex()
                            + ", revision " + graph.revisionStateIndex() + ", sequence " + graph.sequenceNumber());
                    assertEquals(value, graph.get().value());
                    graph = graph.previous();
                }
                assertNull(graph, "Creation must terminate the revision chain");
                assertEquals(1, fc.modelRepository().loadGraphAt("counter", Counter.class,
                        boundary, Graph.Options.DEFAULT).get().value());
                assertEquals(4, fc.client().getEventStoreClient().getModelEvents(new GetModelEvents(
                        List.of(new ModelEventStreamRequest("counter", -1, 10)), ModelReadBoundary.current(), 0))
                        .getStreams().getFirst().getMemberships().size());
                fc.modelRepository().deleteModel("counter", ModelDeletionCascade.NONE).join();
                assertEquals(0, fc.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
                assertNull(Fluxzero.loadCurrentGraph("counter", Counter.class).get());
                return null;
            });
        }
    }

    @Test
    void boundedSnapshotPredecessorsRetainTheSurroundingGraphBoundary() {
        Client[] clients = clients("snapshot-boundary-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            write(writer, new SetParent("parent", 1));
            write(writer, new AddChild("first", "parent"));
            write(writer, new SetParent("parent", 2));
            write(writer, new AddChild("second", "parent"));
            write(writer, new SetParent("parent", 3));
        }
        try (Fluxzero reader = app(clients[1])) {
            reader.apply(fc -> {
                Graph<SnapshotParent> graph = Fluxzero.loadGraph("parent", SnapshotParent.class).previous();
                assertNotNull(graph);
                assertEquals(2, graph.get().value());
                assertEquals(2, graph.children("children", SnapshotChild.class).size());
                graph = graph.previous();
                assertNotNull(graph);
                assertEquals(1, graph.get().value());
                assertEquals(List.of("first"), graph.children("children", SnapshotChild.class)
                        .stream().map(child -> child.get().id()).toList());
                assertNull(graph.previous());
                assertEquals(2, fc.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
                return null;
            });
        }
    }

    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    @Model(snapshotPeriod = 1, maxSnapshotCount = 2, cached = false)
    public record SnapshotParent(@EntityId String id, int value) {}

    @Model(cached = false)
    public record SnapshotChild(@EntityId String id,
                                @Parent(value = SnapshotParent.class, pathInParent = "children") String parentId) {}

    public record SetParent(String id, int value) {
        @Apply SnapshotParent apply(@Nullable SnapshotParent previous) { return new SnapshotParent(id, value); }
    }

    public record AddChild(String id, String parentId) {
        @Apply SnapshotChild apply() { return new SnapshotChild(id, parentId); }
    }

    public record SetCounterTwice(String id, int value) {
        @InterceptApply List<SetCounter> split() {
            return List.of(new SetCounter(id, value), new SetCounter(id, value + 1));
        }
    }

    private static Fluxzero app(Client client) {
        return app(client, false);
    }

    private static Fluxzero app(Client client, boolean aliasTargets) {
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
        if (aliasTargets) {
            builder.replacePropertySource(ignored -> new SimplePropertySource(Map.of("fluxzero.model.aliasTargets", "true")));
        }
        Fluxzero app = builder.build(client);
        app.registerHandlers(Counter.class, SnapshotParent.class, SnapshotChild.class, AliasedCounter.class,
                AliasDependent.class);
        return app;
    }

    @Model(snapshotPeriod = 1, maxSnapshotCount = -7, cached = false)
    public record Counter(@EntityId String id, int value) {}

    public record SetCounter(String id, int value) {
        @Apply Counter apply(@Nullable Counter previous) { return new Counter(id, value); }
    }

    @Model(snapshotPeriod = 25, maxSnapshotCount = -1)
    public record AliasedCounter(@EntityId String id, @Alias String alias, int value) {}

    public record SetAliasedCounter(String id, String alias, int value) {
        @Apply AliasedCounter apply(@Nullable AliasedCounter previous) { return new AliasedCounter(id, alias, value); }
    }

    public record SetAliasedHistory(String id, String alias, int history) {
        @InterceptApply List<SetAliasedCounter> split() {
            return IntStream.rangeClosed(1, history).mapToObj(i -> new SetAliasedCounter(id, alias, i)).toList();
        }
    }

    @Model(cached = false)
    public record AliasDependent(@EntityId String id, int value) {}

    public record CopyAlias(String id, String ownerId) {
        @Apply AliasDependent apply(@Association("ownerId") AliasedCounter owner) {
            return new AliasDependent(id, owner.value());
        }
    }

    public record CommitAliasedPrefix(String id, String ownerId, String alias) {
        @InterceptApply List<Object> split() {
            List<Object> commands = new ArrayList<>();
            commands.add(new SetAliasedCounter(ownerId, alias, 2));
            commands.add(new CopyAlias(id, ownerId));
            IntStream.rangeClosed(3, 25).forEach(value -> commands.add(new SetAliasedCounter(ownerId, alias, value)));
            return commands;
        }
    }

    private static final class SnapshotReads {
        final Client client;
        final List<GetModelEventsResult> responses = new CopyOnWriteArrayList<>();
        final AtomicInteger searches = new AtomicInteger();
        final AtomicReference<Runnable> afterRead = new AtomicReference<>();

        SnapshotReads(Client storage) {
            client = mock(Client.class, delegatesTo(storage));
            EventStoreClient events = storage.getEventStoreClient();
            EventStoreClient observed = mock(EventStoreClient.class, delegatesTo(events));
            // Preserve the existing separate-read contract as the compatibility control.
            doReturn(false).when(observed).supportsModelReconstruction(any());
            doAnswer(invocation -> {
                GetModelEventsResult response = events.getModelEvents(invocation.getArgument(0));
                responses.add(response);
                Runnable action = afterRead.getAndSet(null);
                if (action != null) { action.run(); }
                return response;
            }).when(observed).getModelEvents(any());
            doReturn(observed).when(client).getEventStoreClient();
            SearchClient search = storage.getSearchClient();
            SearchClient observedSearch = mock(SearchClient.class, delegatesTo(search));
            doAnswer(invocation -> {
                searches.incrementAndGet();
                return search.search(invocation.getArgument(0), invocation.getArgument(1));
            }).when(observedSearch).search(any(), anyInt());
            doReturn(observedSearch).when(client).getSearchClient();
            doAnswer(invocation -> invocation.getArgument(0) == null ? client
                    : storage.forNamespace(invocation.getArgument(0))).when(client).forNamespace(any());
        }

        long payloads() {
            return responses.stream().mapToLong(response -> response.getPayloads().size()
                    + response.getPayloadStateIndices().length).sum();
        }
    }
}
