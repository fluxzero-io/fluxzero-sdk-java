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

import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GraphMetadataNavigationTest {
    @Test
    void selectsPathsAndNamesWithoutReadingKnownOrUnknownValues() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class, Leaf.class);
            client.queries.clear();
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals(List.of("known"), ids(graph.children("children")));
            assertEquals(List.of("known", "foreign"), ids(graph.children("children", false)));
            assertEquals(List.of(), graph.namedChildren("foreign"));
            assertEquals(List.of("foreign"), ids(graph.namedChildren("foreign", false)));
            assertEquals(List.of("foreign"), ids(graph.children("children", "foreign", false)));
            assertEquals(List.of(), graph.children("other", "foreign", false));
            assertEquals(List.of("known"), ids(graph.children("children", "known")));
            assertEquals(List.of("known"), ids(graph.children(KnownChild.class)));
            assertEquals(List.of(), graph.children((String) null, false));
            assertEquals(0, rejected.get());
            assertEquals(1, client.queries.size(), "Repeated selections reuse their pinned metadata");
            assertEquals(1, client.queries.getFirst().getMaxDepth());
            assertEquals(0, client.queries.getFirst().getMaxEventsPerModel());
            Graph<?> foreign = graph.namedChildren("foreign", false).getFirst();
            assertEquals("foreign", foreign.modelName());
            assertEquals(Optional.empty(), foreign.knownType());
            assertEquals(List.of("leaves"), foreign.childPaths());
            assertThrows(IllegalStateException.class, foreign::type);
            assertThrows(IllegalStateException.class, foreign::get);
            assertThrows(IllegalStateException.class, foreign::isEmpty);
            assertThrows(IllegalStateException.class, () -> foreign.filterNodes(ignored -> false).get());
            assertThrows(IllegalStateException.class, () -> Graphs.mapValues(foreign, ignored -> null).get());
            assertThrows(IllegalStateException.class, () -> foreign.apply(new Object()));
            assertEquals("root", foreign.parent().orElseThrow().id());
            assertEquals("root", foreign.ancestor(Root.class).orElseThrow().id());
            assertEquals("root", foreign.root().id());
            assertEquals(0, rejected.get());
            assertThrows(RuntimeException.class, () -> graph.namedChildren("known").getFirst().get());
            assertTrue(rejected.get() > 0, "Known type replay errors remain errors");
            assertThrows(RuntimeException.class,
                         () -> reader.modelRepository().loadGraph("root", Root.class, Graph.Options.DEFAULT));
        }
    }

    @Test
    void traversesUnknownIntermediatesAndBatchesMetadataFrontiers() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class, Leaf.class);
            client.queries.clear();
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals(List.of("leaf"), ids(graph.descendants("children/leaves")));
            assertEquals(List.of("leaf"), ids(graph.descendants("children/leaves", "leaf")));
            assertEquals(List.of("foreign"), ids(graph.descendants("children", "foreign", false)));
            assertEquals(List.of(), graph.namedDescendants("foreign"));
            assertEquals(List.of("foreign"), ids(graph.namedDescendants("foreign", false)));
            assertEquals(List.of("leaf"), ids(graph.namedDescendants("leaf")));
            assertEquals(List.of("leaf"), ids(graph.descendants(Leaf.class)));
            assertEquals(List.of("known", "leaf"), ids(graph.descendants(Object.class)));
            assertTrue(client.queries.stream().allMatch(query -> query.getMaxDepth() == 1));
            assertTrue(client.queries.stream().anyMatch(query -> query.getModelIds().size() == 2));
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void exactNameCountsDoNotRequireCompatibleLocalReplayContracts() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class, ForeignView.class);
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            List<Graph<?>> selected = graph.namedChildren("foreign");
            assertEquals(List.of("foreign"), ids(selected));
            assertEquals(Optional.of(ForeignView.class), selected.getFirst().knownType());
            assertEquals(0, rejected.get());
            assertThrows(RuntimeException.class, selected.getFirst()::get);
            assertTrue(rejected.get() > 0);
        }
    }

    @Test
    void metadataAndLaterValuesStayOnTheFirstSnapshot() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class);
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals(1, graph.namedChildren("known").size());
            long first = graph.stateIndex();
            commit(writer, new CreateKnown("later", "root"));
            commit(writer, new CreateRoot("root", 2));
            assertEquals(1, graph.namedChildren("known").size());
            assertEquals(new Root("root", 1), graph.get());
            assertEquals(first, graph.stateIndex());
            Graph<Root> updated = graph.update(root -> new Root(root.rootId(), 3));
            assertEquals(first, updated.stateIndex());
            assertEquals(1, updated.namedChildren("known").size());
            assertEquals(new Root("root", 3), updated.get());
            assertEquals(2, Graphs.lazy("root", Root.class, reader.modelRepository()).namedChildren("known").size());
        }
    }

    @Test
    void valuesReadBeforeRelationshipsAlsoPinTheSnapshot() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class);
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals(new Root("root", 1), graph.get());
            long first = graph.stateIndex();
            commit(writer, new CreateKnown("later", "root"));
            commit(writer, new CreateRoot("root", 2));
            assertEquals(List.of("known"), ids(graph.namedChildren("known")));
            assertEquals(first, graph.stateIndex());
            assertEquals(new Root("root", 1), graph.get());
        }
    }

    @Test
    void aliasResolutionKeepsTheSameBoundaryForValuesAndRelationships() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateAliased("canonical", "alias", 1));
            commit(writer, new CreateAliasChild("first", "canonical"));
            catalog(reader, Aliased.class, AliasChild.class);
            Graph<Aliased> graph = Graphs.lazy("alias", Aliased.class, reader.modelRepository());
            assertEquals("canonical", graph.id());
            long first = graph.stateIndex();
            commit(writer, new CreateAliasChild("later", "canonical"));
            commit(writer, new CreateAliased("canonical", "alias", 2));
            assertEquals(List.of("first"), ids(graph.children(AliasChild.class)));
            assertEquals(new Aliased("canonical", "alias", 1), graph.get());
            assertEquals(first, graph.stateIndex());
        }
    }

    @Test
    void anExactRepositoryRootSkipsAliasReplayWhileUnresolvedRootsRetainIt() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            commit(writer, new CreateAliased("canonical", "alias", 1));
            commit(writer, new CreateAliasChild("child", "canonical"));
            catalog(reader, Aliased.class, AliasChild.class);
            Graph<Aliased> exact = Graphs.lazyRepositoryId("canonical", Aliased.class, reader.modelRepository());
            assertEquals(1, exact.namedChildren("AliasChild").size());
            assertEquals(0, rejected.get());
            assertThrows(RuntimeException.class,
                         () -> Graphs.lazy("canonical", Aliased.class, reader.modelRepository()).namedChildren("AliasChild"));
            assertTrue(rejected.get() > 0);
        }
    }

    @Test
    void selectedValuesReplayInBatchesWithoutLoadingUnrelatedModels() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            seed(writer);
            for (int i = 0; i < 32; i++) {
                commit(writer, new CreateKnown("child-" + i, "root"));
            }
            catalog(reader, Root.class, KnownChild.class);
            client.eventQueries.clear();
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals(33, graph.childModels(KnownChild.class).size());
            List<GetModelEvents> replay = client.eventQueries.stream()
                    .filter(query -> query.getRequests().stream().anyMatch(request -> request.getMaxSize() > 0)).toList();
            assertEquals(1, replay.size(), "Selected values share a batched replay request");
            var replayIds = replay.getFirst().getRequests().stream().map(request -> request.getModelId()).toList();
            assertEquals(33, replayIds.size());
            assertFalse(replayIds.contains("foreign"));
            assertFalse(replayIds.contains("root"));
        }
    }

    @Test
    void currentDocumentModelsNeverSwitchToReplayBecauseTheirMetadataWasPinned() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            commit(writer, new CreateDocument("document", null));
            commit(writer, new CreateDocument("first", "document"));
            commit(writer, new CreateDocument("second", "document"));
            catalog(reader, Document.class);
            Graph<Document> graph = Graphs.lazy("document", Document.class, reader.modelRepository());
            assertEquals(new Document("document", null), graph.get());
            assertEquals(2, graph.childModels(Document.class).size());
            assertTrue(graph.children(Document.class).getFirst().namedChildren("Document").isEmpty());
            assertEquals(new Document("first", "document"), graph.children(Document.class).getFirst().get());
            Graph<Document> current = Graphs.lazyCurrent("document", Document.class, reader.modelRepository());
            assertEquals(2, current.childModels(Document.class).size());
            assertEquals(2, graph.children().size());
            assertEquals(2, current.children().size());
            Graph<Document> complete = reader.modelRepository().loadGraph("document", Document.class, Graph.Options.DEFAULT);
            assertEquals(2, complete.update(value -> value).childModels(Document.class).size());
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void deliberatelyCurrentMetadataBypassesAnOldHandlerBoundary() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            commit(app, new CreateRoot("root", 1));
            commit(app, new CreateKnown("known", "root"));
            app.apply(fc -> fc.eventStore().getEvents("root").findFirst().orElseThrow().apply(event -> {
                assertTrue(Graphs.lazy("root", Root.class, fc.modelRepository()).namedChildren("known").isEmpty());
                assertEquals(1, Graphs.lazyCurrent("root", Root.class, fc.modelRepository()).namedChildren("known").size());
                return null;
            }));
        }
    }

    @Test
    void pendingAliasChangesStayVisibleInLazyAndDeliberatelyCurrentGraphs() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            commit(app, new CreateAliased("canonical", "old", 1));
            inPendingBatch(app, client, new CreateAliased("canonical", "new", 2), () -> {
                assertEquals(new Aliased("canonical", "new", 2),
                             Graphs.lazy("new", Aliased.class, app.modelRepository()).get());
                assertTrue(Graphs.lazy("old", Aliased.class, app.modelRepository()).isEmpty());
                assertEquals(new Aliased("canonical", "new", 2),
                             Graphs.lazyCurrent("new", Aliased.class, app.modelRepository()).get());
                assertTrue(Graphs.lazyCurrent("old", Aliased.class, app.modelRepository()).isEmpty());
            });
            inPendingBatch(app, client, new CreateAliased("created", "created-alias", 3), () -> {
                assertEquals("created", Graphs.lazy("created-alias", Aliased.class, app.modelRepository()).id());
                assertEquals("created", Graphs.lazyCurrent("created-alias", Aliased.class, app.modelRepository()).id());
            });
        }
    }

    @Test
    void fullExpansionAfterPendingReparentKeepsExistingDescendantsAndSnapshot() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            seed(app);
            commit(app, new CreateRoot("other", 1));
            inPendingBatch(app, client, new CreateForeign("foreign", "other"), () -> {
                Graph<Root> graph = Graphs.lazy("other", Root.class, app.modelRepository());
                assertEquals(new Root("other", 1), graph.get());
                assertEquals(1, graph.namedChildren("foreign", false).size());
                Graph<Root> updated = graph.update(root -> new Root(root.rootId(), 2));
                assertEquals("foreign", updated.children().getFirst().id());
                assertEquals("leaf", updated.children().getFirst().children().getFirst().id());
                assertEquals(new Root("other", 2), updated.get());
            });
        }
    }

    private static void inPendingBatch(Fluxzero app, ObservedClient client, Object command, Runnable read) {
        var gate = new CompletableFuture<Void>();
        client.commitGate = gate;
        try {
            app.apply(fc -> {
                var messages = List.of("producer", "consumer").stream().map(value -> {
                    var message = new DeserializingMessage(new Message(value), io.fluxzero.common.MessageType.COMMAND,
                                                           fc.serializer());
                    message.getSerializedObject().setSegment(17);
                    return message;
                }).toList();
                DeserializingMessage.forEachInBatch(messages, message -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        assertFalse(fc.executeModelCommit(new Message(command)).isDone());
                    } else {
                        read.run();
                    }
                });
                return null;
            });
        } finally {
            client.commitGate = null;
            gate.complete(null);
        }
    }

    @Test
    void explicitClassSelectionCanResolvePreviouslyUnknownMetadata() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            seed(writer);
            catalog(reader, Root.class);
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            Graph<?> child = graph.namedChildren("known", false).getFirst();
            assertTrue(child.knownType().isEmpty());
            assertSame(child, graph.children(KnownChild.class).getFirst());
            assertEquals(Optional.of(KnownChild.class), child.knownType());
            assertEquals(new KnownChild("known", "root"), child.get());
        }
    }

    @Test
    void rejectsIncompatibleTypedRootsAndPreservesMissingParents() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateRoot("root", 1));
            commit(writer, new CreateForeign("foreign", "missing"));
            catalog(reader, Root.class, Foreign.class);
            Graph<KnownChild> wrong = Graphs.lazy("root", KnownChild.class, reader.modelRepository());
            assertThrows(IllegalStateException.class, () -> wrong.namedChildren("known", false));
            Graph<Foreign> orphan = Graphs.lazy("foreign", Foreign.class, reader.modelRepository());
            assertTrue(orphan.parent().isEmpty());
            assertTrue(orphan.parents().isEmpty());
            assertTrue(orphan.ancestor(Root.class).isEmpty());
        }
    }

    @Test
    void remappedMetadataViewsPreservePathsContextAndDeferredMapping() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class);
            Graph<Root> graph = Graphs.remapPaths(Graphs.lazy("root", Root.class, reader.modelRepository()),
                                                java.util.Map.of("children", "items")).withContext("response");
            assertTrue(graph.children("children", false).isEmpty());
            var calls = new AtomicInteger();
            Graph<Root> mapped = Graphs.mapValues(graph, node -> {
                calls.incrementAndGet();
                return node.get();
            });
            Graph<?> child = mapped.children("items", "known").getFirst();
            assertEquals(0, calls.get());
            assertEquals("items", child.relationshipPath());
            assertEquals(Optional.of("response"), child.context(String.class));
            assertEquals(new KnownChild("known", "root"), child.get());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void emptyUnknownMembershipInvalidatesRetryAndFailAssertions() {
        for (boolean fail : new boolean[]{false, true}) {
            var client = new ObservedClient();
            try (Fluxzero writer = app(client, new JacksonSerializer());
                 Fluxzero reader = app(client, new JacksonSerializer())) {
                commit(writer, new CreateRoot("root", 1));
                catalog(reader, Root.class, Receipt.class);
                var once = new AtomicBoolean();
                client.beforeCommit = request -> {
                    if (request.getReadModelIds().contains("receipt") && once.compareAndSet(false, true)) {
                        assertFalse(request.getReadRelationships().isEmpty());
                        commit(writer, new CreateForeign("foreign", "root"));
                    }
                };
                RuntimeException failure = assertThrows(RuntimeException.class, () -> commit(reader,
                        fail ? new FailCapacity("receipt", "root") : new CheckCapacity("receipt", "root")));
                Throwable cause = failure;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (!fail) {
                    assertInstanceOf(IllegalCommandException.class, cause);
                }
                assertTrue(once.get());
                assertTrue(reader.modelRepository().load("receipt", Receipt.class).isEmpty());
            }
        }
    }

    @Test
    void unknownRemovalAndReparentingReevaluateMetadataCounts() {
        for (boolean delete : new boolean[]{false, true}) {
            var client = new ObservedClient();
            try (Fluxzero writer = app(client, new JacksonSerializer());
                 Fluxzero reader = app(client, new JacksonSerializer())) {
                commit(writer, new CreateRoot("root", 1));
                commit(writer, new CreateRoot("other", 1));
                commit(writer, new CreateForeign("foreign", "root"));
                catalog(reader, Root.class, Receipt.class);
                var once = new AtomicBoolean();
                client.beforeCommit = request -> {
                    if (request.getReadModelIds().contains("receipt") && once.compareAndSet(false, true)) {
                        assertFalse(request.getReadModelIds().contains("foreign"), "Counting needs memberships, not values");
                        commit(writer, delete ? new DeleteForeign("foreign") : new CreateForeign("foreign", "other"));
                    }
                };
                commit(reader, new ReadCount("receipt", "root"));
                assertEquals(0, reader.modelRepository().load("receipt", Receipt.class).get().count());
                assertTrue(once.get());
            }
        }
    }

    private static void seed(Fluxzero writer) {
        commit(writer, new CreateRoot("root", 1));
        commit(writer, new CreateKnown("known", "root"));
        commit(writer, new CreateForeign("foreign", "root"));
        commit(writer, new CreateLeaf("leaf", "foreign"));
    }

    private static List<Object> ids(List<? extends Graph<?>> graphs) {
        return graphs.stream().map(Graph::id).toList();
    }

    private static void catalog(Fluxzero app, Class<?>... types) {
        ((DefaultModelRepository) app.modelRepository()).configureModelTypes(() -> List.of(types));
    }

    private static Fluxzero app(LocalClient client, JacksonSerializer serializer) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().replaceSerializer(serializer).build(client);
    }

    private static JacksonSerializer rejecting(AtomicInteger rejected) {
        return new JacksonSerializer() {
            @Override protected boolean isKnownType(String type) {
                if (type.contains("GraphMetadataNavigationTest$Create")) {
                    rejected.incrementAndGet();
                    return false;
                }
                return super.isKnownType(type);
            }
        };
    }

    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    private static class ObservedClient extends LocalClient {
        final List<GetModelGraph> queries = new ArrayList<>();
        final List<GetModelEvents> eventQueries = new ArrayList<>();
        Consumer<CommitModels> beforeCommit = ignored -> {};
        CompletableFuture<Void> commitGate;

        ObservedClient() { super(null); }

        @Override protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getModelGraph")) {
                            queries.add((GetModelGraph) arguments[0]);
                        }
                        if (method.getName().equals("getModelEvents")) {
                            eventQueries.add((GetModelEvents) arguments[0]);
                        }
                        if (method.getName().equals("commitModels")) {
                            beforeCommit.accept((CommitModels) arguments[0]);
                            if (commitGate != null) {
                                return commitGate.thenCompose(ignored -> delegate.commitModels((CommitModels) arguments[0]));
                            }
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @Model(name = "root") record Root(@EntityId String rootId, int version) {}
    @Model record Aliased(@EntityId String rootId, @Alias String alias, int version) {}
    @Model record AliasChild(@EntityId String childId, @Parent(Aliased.class) String rootId) {}
    @Model(persistence = ModelPersistence.DOCUMENT)
    record Document(@EntityId String documentId, @Parent(Document.class) String parentId) {}
    record CreateDocument(String documentId, String parentId) {
        @Apply Document apply() { return new Document(documentId, parentId); }
    }
    record CreateAliased(String rootId, String alias, int version) {
        @Apply Aliased apply() { return new Aliased(rootId, alias, version); }
    }
    record CreateAliasChild(String childId, String rootId) {
        @Apply AliasChild apply() { return new AliasChild(childId, rootId); }
    }
    @Model(name = "known") record KnownChild(@EntityId String childId,
            @Parent(value = Root.class, pathInParent = "children") String rootId) {}
    @Model(name = "foreign") record Foreign(@EntityId String childId,
            @Parent(value = Root.class, pathInParent = "children") String rootId) {}
    @Model(name = "foreign") record ForeignView(@EntityId String childId, String incompatibleField) {}
    @Model(name = "leaf") record Leaf(@EntityId String leafId,
            @Parent(value = Foreign.class, pathInParent = "leaves") String childId) {}
    record CreateRoot(String rootId, int version) { @Apply Root apply() { return new Root(rootId, version); } }
    record CreateKnown(String childId, String rootId) { @Apply KnownChild apply() { return new KnownChild(childId, rootId); } }
    record CreateForeign(String childId, String rootId) { @Apply Foreign apply() { return new Foreign(childId, rootId); } }
    record CreateLeaf(String leafId, String childId) { @Apply Leaf apply() { return new Leaf(leafId, childId); } }
    record DeleteForeign(String childId) { @Apply Foreign apply() { return null; } }
    @Model record Receipt(@EntityId String receiptId, int count) {}
    record CheckCapacity(String receiptId, String rootId) {
        @AssertLegal void check(Graph<Root> root) {
            if (!root.namedChildren("foreign", false).isEmpty()) {
                throw new IllegalCommandException("Capacity reached");
            }
        }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Receipt apply() { return new Receipt(receiptId, 0); }
    }
    record FailCapacity(String receiptId, String rootId) {
        @AssertLegal void check(Graph<Root> root) { new CheckCapacity(receiptId, rootId).check(root); }
        @Apply(conflictPolicy = ModelConflictPolicy.FAIL) Receipt apply() { return new Receipt(receiptId, 0); }
    }
    record ReadCount(String receiptId, String rootId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Receipt apply(Graph<Root> root) {
            return new Receipt(receiptId, root.namedChildren("foreign", false).size());
        }
    }
}
