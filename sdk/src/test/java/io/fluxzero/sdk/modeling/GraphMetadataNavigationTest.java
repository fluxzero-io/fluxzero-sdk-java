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
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.repository.ModelGraphResolver;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GraphMetadataNavigationTest {
    @Test
    void pathViewsAndExactLookupsDoNotReplayUnrelatedValues() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class, Leaf.class);
            client.queries.clear();
            client.eventQueries.clear();
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            Graph<Root> selected = graph.selectPaths("children/leaves");
            assertTrue(client.queries.isEmpty());
            assertTrue(client.eventQueries.isEmpty());
            assertEquals(List.of(), graph.selectPaths("unrelated").children());
            assertEquals(List.of("known", "foreign"), ids(selected.children()));
            assertEquals(List.of("leaf"), ids(selected.descendants("children/leaves", false)));
            assertEquals("known", graph.find("known").orElseThrow().id());
            assertEquals("known", graph.find("known", KnownChild.class).orElseThrow().id());
            assertEquals("foreign", graph.find("foreign").orElseThrow().id());
            assertTrue(graph.selectPaths("unrelated").find("known", KnownChild.class).isEmpty());
            assertEquals(0, rejected.get());
            assertThrows(RuntimeException.class, selected::get);
        }
    }

    @Test
    void aliasLookupPreservesSuppliedEntitiesAndCustomMetadataResolvers() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            commit(app, new CreateRoot("root", 1));
            Entity<Root> stored = app.modelRepository().load("root", Root.class);
            @SuppressWarnings("unchecked")
            Entity<Root> wrapped = (Entity<Root>) Proxy.newProxyInstance(
                    Entity.class.getClassLoader(), new Class<?>[]{Entity.class}, (proxy, method, args) -> {
                        if (method.getName().equals("aliases")) {
                            return List.of("external-alias");
                        }
                        try {
                            return method.invoke(stored, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            long boundary = ((ModelRoot<?>) stored).stateIndex();
            Graph<Root> supplied = Graphs.lazy(wrapped, boundary, app.modelRepository());
            assertEquals("root", supplied.find("external-alias").orElseThrow().id());

            ModelRepository custom = (ModelRepository) Proxy.newProxyInstance(
                    ModelRepository.class.getClassLoader(), new Class<?>[]{ModelRepository.class, ModelGraphResolver.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("resolveGraphIdentity")) {
                            return new ModelGraphResolver.Identity("root", true, ModelReadBoundary.at(boundary), false,
                                                                   () -> wrapped);
                        }
                        if (method.getName().equals("loadGraphRelations")) {
                            return new ModelGraphResolver.Relations(ModelReadBoundary.at(boundary),
                                    java.util.Map.of("root", new ModelGraphResolver.ModelNode("root", "root", Root.class,
                                                                                            () -> wrapped)),
                                    List.of(), java.util.Set.of("root"), false);
                        }
                        try {
                            return method.invoke(app.modelRepository(), args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            Graph<Root> graph = Graphs.lazy("root", Root.class, custom);
            assertEquals("root", graph.find("external-alias", Root.class).orElseThrow().id());
            assertEquals("root", graph.find("external-alias").orElseThrow().id());
        }
    }

    @Test
    void currentAndUntypedFactoriesPinRootsWithoutReplay() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            commit(writer, new CreateAliased("canonical", "alias", 1));
            catalog(reader, Root.class, Aliased.class, KnownChild.class);
            Graph<Root> current = reader.apply(fc -> Fluxzero.loadCurrentGraph("root", Root.class));
            Graph<?> untyped = reader.apply(fc -> Fluxzero.loadGraph((Object) "root"));
            Graph<?> alias = reader.apply(fc -> Fluxzero.loadGraph((Object) "alias"));
            assertEquals("root", current.id());
            assertEquals(Root.class, untyped.type());
            assertEquals("canonical", alias.id());
            assertEquals(1, current.namedChildren("known").size());
            assertEquals(1, untyped.namedChildren("known").size());
            assertEquals(0, rejected.get());
            assertTrue(reader.<Boolean>apply(fc -> Fluxzero.loadGraph((Object) "missing").isEmpty()));
            assertThrows(RuntimeException.class, current::get);
        }
    }

    @Test
    void factoriesRetainTheirCreationBoundaryForLaterValuesAndRelationships() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class);
            Graph<Root> current = reader.apply(fc -> Fluxzero.loadCurrentGraph("root", Root.class));
            Graph<?> untyped = reader.apply(fc -> Fluxzero.loadGraph((Object) "root"));
            commit(writer, new CreateRoot("root", 2));
            commit(writer, new CreateKnown("later", "root"));
            assertEquals(new Root("root", 1), current.get());
            assertEquals(new Root("root", 1), untyped.get());
            assertEquals(List.of("known"), ids(current.children(KnownChild.class)));
            assertEquals(List.of("known"), ids(untyped.children(KnownChild.class)));
        }
    }

    @Test
    void currentGraphValuesSeedTheValidatedCacheWithoutWeakeningSnapshotBoundaries() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateRoot("root", 1));
            Graph<Root> cold = Graphs.lazyCurrent("root", Root.class, reader.modelRepository());
            assertEquals(new Root("root", 1), cold.get());
            // Tracking bootstrap is asynchronous. Once it has validated this load, new roots need no storage request.
            Graph<Root> cached = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                while (!Thread.currentThread().isInterrupted()) {
                    int requests = client.eventQueries.size();
                    Graph<Root> candidate = Graphs.lazyCurrent("root", Root.class, reader.modelRepository());
                    assertEquals(new Root("root", 1), candidate.get());
                    if (client.eventQueries.size() == requests) {
                        return candidate;
                    }
                    Thread.yield();
                }
                throw new AssertionError("The current Graph value never became reusable");
            });
            long revision = cached.sequenceNumber();
            ModelGraphResolver.Identity beforeCreation = ((ModelGraphResolver) reader.modelRepository())
                    .resolveGraphIdentity("root", Root.class, ModelReadBoundary.current().asBefore());
            assertFalse(beforeCreation.present());
            assertTrue(beforeCreation.entity().get().isEmpty(), "A current cache entry is not a before-current revision");
            commit(writer, new CreateRoot("root", 2));
            assertEquals(new Root("root", 1), cached.get());
            assertEquals(revision, cached.sequenceNumber());
        }
    }

    @Test
    void headRevisionGettersRemainReplayFreeAndPinned() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class);
            long rootRevision = writer.modelRepository().load("root", Root.class).sequenceNumber();
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals(rootRevision, graph.sequenceNumber());
            long index = graph.revisionStateIndex();
            Graph<?> child = graph.namedChildren("known").getFirst();
            assertEquals(0, child.sequenceNumber());
            long childIndex = child.revisionStateIndex();
            commit(writer, new CreateRoot("root", 2));
            int queries = client.eventQueries.size();
            assertEquals(rootRevision, graph.sequenceNumber());
            assertEquals(index, graph.revisionStateIndex());
            assertEquals(childIndex, child.revisionStateIndex());
            assertEquals(queries, client.eventQueries.size());
            assertEquals(0, rejected.get());
            assertEquals(-1, Graphs.lazy("missing", Root.class, reader.modelRepository()).sequenceNumber());
            assertThrows(RuntimeException.class, graph::get);
        }
    }

    @Test
    void typedLookupRegistersItsContractAndProvesAbsenceWithoutReplay() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class);
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals("known", graph.find("known", KnownChild.class).orElseThrow().id());
            assertTrue(graph.find("missing", KnownChild.class).isEmpty());
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void lazyPathSelectionPreservesRemappingAndSuccessiveNarrowing() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            catalog(reader, Root.class, KnownChild.class, Leaf.class);
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            Graph<Root> before = Graphs.remapPaths(graph.selectPaths("children/leaves"), Map.of("children", "items"));
            Graph<Root> after = Graphs.remapPaths(graph, Map.of("children", "items")).selectPaths("items/leaves");
            assertEquals(List.of("known", "foreign"), ids(before.children()));
            assertEquals(List.of("known", "foreign"), ids(after.children()));
            assertEquals(List.of("items"), before.childPaths());
            assertEquals(List.of("items"), after.childPaths());
            assertEquals(List.of("leaf"), ids(before.descendants("items/leaves", false)));
            assertEquals(List.of("leaf"), ids(after.descendants("items/leaves", false)));
            Graph<?> foreign = graph.namedChildren("foreign", false).getFirst();
            Graph<?> selectedChild = foreign.selectPaths("leaves");
            assertEquals(List.of("leaf"), ids(selectedChild.children()));
            assertEquals(List.of("foreign"), ids(selectedChild.parent().orElseThrow().children()));
            assertEquals(List.of("foreign"), ids(selectedChild.root().children()));
            assertTrue(graph.selectPaths("children").selectPaths("children/leaves")
                               .descendants("children/leaves", false).isEmpty());
            client.queries.clear();
            assertTrue(Graphs.lazy("root", Root.class, reader.modelRepository())
                               .selectPaths("children").descendants(Leaf.class).isEmpty());
            assertEquals(1, client.queries.size(), "Selected leaves must not fetch their excluded descendants");
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void selectingAPathlessChildRetainsItsAncestorsWithoutReplayingValues() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            seed(writer);
            commit(writer, new PutRevisionChild("pathless", "root", 1));
            catalog(reader, Root.class, RevisionChild.class);
            Graph<?> child = Graphs.lazy("root", Root.class, reader.modelRepository())
                    .children(RevisionChild.class).getFirst();
            assertNull(child.relationshipPath());
            Graph<?> selected = child.selectPaths("unused");
            assertTrue(selected.children().isEmpty());
            assertEquals(List.of("pathless"), ids(selected.parent().orElseThrow().children()));
            assertEquals(List.of("pathless"), ids(selected.root().children()));
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void lateIdentityLookupBatchesSiblingRelationshipReads() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            commit(writer, new CreateRoot("root", 1));
            for (int i = 0; i < 100; i++) commit(writer, new CreateKnown("child-%03d".formatted(i), "root"));
            catalog(reader, Root.class, KnownChild.class);
            client.queries.clear();
            Graph<Root> graph = Graphs.lazy("root", Root.class, reader.modelRepository());
            assertEquals("child-099", graph.find("child-099", KnownChild.class).orElseThrow().id());
            assertTrue(client.queries.size() <= 2, "Root and one bounded sibling frontier: " + client.queries.size());
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void headOnlyRevisionReadParticipatesInRetryWithoutReplayingTheReadModel() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer() {
                 @Override protected boolean isKnownType(String type) {
                     if (type.endsWith("GraphMetadataNavigationTest$PutRevisionChild")) {
                         rejected.incrementAndGet();
                         return false;
                     }
                     return super.isKnownType(type);
                 }
             })) {
            commit(writer, new CreateRoot("root", 1));
            commit(writer, new PutRevisionChild("versioned", "root", 1));
            catalog(reader, Root.class, RevisionChild.class, Receipt.class);
            var once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && once.compareAndSet(false, true)) {
                    assertTrue(request.getReadModelIds().contains("versioned"));
                    commit(writer, new PutRevisionChild("versioned", "root", 2));
                }
            };
            RuntimeException error = assertThrows(RuntimeException.class, () -> commit(reader, new CheckRevision("receipt", "root")));
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            assertInstanceOf(IllegalCommandException.class, cause);
            assertTrue(once.get());
            assertEquals(0, rejected.get());
            assertTrue(reader.modelRepository().load("receipt", Receipt.class).isEmpty());
        }
    }

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
            assertThrows(IllegalStateException.class, foreign::sequenceNumber);
            assertThrows(IllegalStateException.class, foreign::revisionStateIndex);
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
    void canonicalAndAliasRootsNavigateWithoutReplayingTheirValues() {
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
            for (String id : List.of("canonical", "alias")) {
                Graph<Aliased> graph = Graphs.lazy(id, Aliased.class, reader.modelRepository());
                assertEquals("canonical", graph.id());
                assertEquals("Aliased", graph.modelName());
                assertEquals(1, graph.namedChildren("AliasChild").size());
                assertEquals(1, graph.namedDescendants("AliasChild").size());
                assertEquals(0, rejected.get());
            }
            Graph<Aliased> alias = Graphs.lazy("alias", Aliased.class, reader.modelRepository());
            assertEquals("canonical", alias.id());
            assertThrows(RuntimeException.class, alias::get);
            assertTrue(rejected.get() > 0);
        }
    }

    @Test
    void resolvedAliasesNeverRebindEvenWhenTheNewOwnerExistedAtThePinnedBoundary() {
        for (boolean valueFirst : List.of(false, true)) {
            var client = new ObservedClient();
            try (Fluxzero writer = app(client, new JacksonSerializer());
                 Fluxzero reader = app(client, new JacksonSerializer())) {
                commit(writer, new CreateAliased("first", "shared", 1));
                commit(writer, new CreateAliased("second", "other", 1));
                commit(writer, new CreateAliasChild("first-child", "first"));
                commit(writer, new CreateAliasChild("second-child", "second"));
                catalog(reader, Aliased.class, AliasChild.class);
                Graph<Aliased> graph = Graphs.lazy("shared", Aliased.class, reader.modelRepository());
                assertEquals("first", graph.id());
                long boundary = graph.stateIndex();
                commit(writer, new CreateAliased("first", "moved", 2));
                commit(writer, new CreateAliased("second", "shared", 2));
                commit(writer, new CreateAliasChild("later", "first"));
                if (valueFirst) {
                    assertEquals(new Aliased("first", "shared", 1), graph.get());
                }
                assertEquals(List.of("first-child"), ids(graph.namedChildren("AliasChild")));
                assertEquals(new Aliased("first", "shared", 1), graph.get());
                assertEquals(boundary, graph.stateIndex());
                assertEquals(List.of("first-child"), ids(graph.children()));
                assertEquals(List.of("first-child"), ids(graph.update(value -> value).children()));
                assertEquals("second", Graphs.lazy("shared", Aliased.class, reader.modelRepository()).id());
            }
        }
    }

    @Test
    void resolvedMissingAliasesStayAbsentAfterAssignmentToAnExistingModel() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateAliased("existing", "old", 1));
            commit(writer, new CreateAliasChild("child", "existing"));
            Graph<Aliased> graph = Graphs.lazy("missing", Aliased.class, reader.modelRepository());
            assertEquals("missing", graph.id());
            long boundary = graph.stateIndex();
            commit(writer, new CreateAliased("existing", "missing", 2));
            assertTrue(graph.namedChildren("AliasChild").isEmpty());
            assertTrue(graph.parents().isEmpty());
            assertTrue(graph.isEmpty());
            assertTrue(graph.children().isEmpty());
            assertEquals(1, graph.stream().count());
            assertTrue(graph.update(value -> value).children().isEmpty());
            assertEquals("missing", graph.id());
            assertEquals(boundary, graph.stateIndex());
            assertEquals("existing", Graphs.lazy("missing", Aliased.class, reader.modelRepository()).id());
        }
    }

    @Test
    void affixedIdentityPrecedenceAndMissingFallbackRemainMetadataOnly() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            commit(writer, new CreateAffixed("first", "raw-alias"));
            commit(writer, new CreateAffixed("second", "first"));
            for (var entry : Map.of("first", "prefix-first", "raw-alias", "prefix-first",
                                    "absent", "prefix-absent").entrySet()) {
                Graph<Affixed> graph = Graphs.lazy(entry.getKey(), Affixed.class, reader.modelRepository());
                assertEquals(entry.getValue(), graph.id());
                assertTrue(graph.namedChildren("AliasChild").isEmpty());
            }
            assertEquals(0, rejected.get());
        }
    }

    @Test
    void absentAliasRootsKeepTheirOwnDanglingChildrenAfterAliasAssignment() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateAliased("existing", "old", 1));
            commit(writer, new CreateAliasChild("own-child", "missing"));
            commit(writer, new CreateAliasChild("foreign-child", "existing"));
            catalog(reader, Aliased.class, AliasChild.class);
            Graph<Aliased> graph = Graphs.lazy("missing", Aliased.class, reader.modelRepository());
            assertEquals("missing", graph.id());
            commit(writer, new CreateAliased("existing", "missing", 2));
            assertEquals(List.of("own-child"), ids(graph.children(AliasChild.class)));
            assertTrue(graph.isEmpty());
            assertEquals(List.of("own-child"), ids(graph.children()));
            assertEquals(List.of("own-child"), ids(graph.update(value -> value).children()));
            assertEquals(List.of("own-child"), ids(graph.update(value -> value).children(AliasChild.class)));
            assertTrue(graph.stream().findFirst().orElseThrow().isEmpty());
        }
    }

    @Test
    void identityPresenceAndValuesRespectBeforeCreationAndDeletion() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            var repository = (DefaultModelRepository) app.modelRepository();
            commit(app, new CreateAliased("root", "alias", 1));
            long creation = repository.resolveGraphIdentity("root", Aliased.class, ModelReadBoundary.current())
                    .boundary().stateIndex();
            var beforeCreation = repository.resolveGraphIdentity("root", Aliased.class,
                    ModelReadBoundary.at(creation).asBefore());
            assertFalse(beforeCreation.present());
            assertTrue(beforeCreation.entity().get().isEmpty());
            commit(app, new DeleteAliased("root"));
            long deletion = repository.resolveGraphIdentity("root", Aliased.class, ModelReadBoundary.current())
                    .boundary().stateIndex();
            var beforeDeletion = repository.resolveGraphIdentity("root", Aliased.class,
                    ModelReadBoundary.at(deletion).asBefore());
            assertTrue(beforeDeletion.present());
            assertEquals(new Aliased("root", "alias", 1), beforeDeletion.entity().get().get());
        }
    }

    @Test
    void metadataFirstMissingRootRetainsMutationAndValidationSupport() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            app.apply(fc -> {
                Graph<Aliased> graph = Graphs.lazy("new", Aliased.class, fc.modelRepository());
                assertEquals("new", graph.id());
                assertThrows(IllegalCommandException.class, () -> graph.assertLegal(new RejectAlias()));
                assertEquals(new Aliased("new", "alias", 1), graph.apply(new CreateAliased("new", "alias", 1)).get());
                return null;
            });
        }
    }

    @Test
    void erasingAResolvedRootCannotTurnItsPinnedValueIntoFreshAbsence() {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateAliased("root", "alias", 1));
            Graph<Aliased> graph = Graphs.lazy("alias", Aliased.class, reader.modelRepository());
            assertEquals("root", graph.id());
            writer.modelRepository().deleteModel("root", io.fluxzero.common.api.modeling.ModelDeletionCascade.NONE).join();
            assertThrows(RuntimeException.class, graph::get);
            assertEquals("root", graph.id());
        }
    }

    @Test
    void historicalAliasLookupRetainsCurrentLookupSemanticsButPinsTheSelectedModelsState() {
        var client = new ObservedClient();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            commit(app, new CreateAliased("second", "other", 1));
            commit(app, new CreateAliased("first", "shared", 1));
            commit(app, new CreateAliased("first", "moved", 2));
            commit(app, new CreateAliased("second", "shared", 2));
            app.apply(fc -> fc.eventStore().getEvents("first").findFirst().orElseThrow().apply(event -> {
                Graph<Aliased> graph = Graphs.lazy("shared", Aliased.class, fc.modelRepository());
                assertEquals("second", graph.id(), "Initial alias lookup uses the current alias table");
                assertEquals(new Aliased("second", "other", 1), graph.get(), "Value uses the handler boundary");
                return null;
            }));
        }
    }

    @Test
    void aliasMetadataKeepsCurrentDocumentAuthorityWithoutReplay() {
        var client = new ObservedClient();
        var rejected = new AtomicInteger();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, rejecting(rejected))) {
            commit(writer, new CreateAliasedDocument("document", "alias"));
            Graph<AliasedDocument> graph = Graphs.lazy("alias", AliasedDocument.class, reader.modelRepository());
            assertEquals("document", graph.id());
            assertTrue(graph.namedChildren("AliasChild").isEmpty());
            assertEquals(new AliasedDocument("document", "alias"), graph.get());
            assertEquals(0, rejected.get());
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
                Graph<Aliased> named = Graphs.lazy("new", Aliased.class, app.modelRepository());
                assertEquals("canonical", named.id());
                assertTrue(named.namedChildren("AliasChild").isEmpty());
                assertEquals(new Aliased("canonical", "new", 2), named.get());
                Graph<Aliased> removed = Graphs.lazy("old", Aliased.class, app.modelRepository());
                assertEquals("old", removed.id());
                assertTrue(removed.namedChildren("AliasChild").isEmpty());
                assertTrue(removed.isEmpty());
                assertTrue(removed.children().isEmpty());
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

    @Test
    void pendingRootDeletionSuppressesRelationshipsRegardlessOfIdentityReadOrder() {
        for (boolean idFirst : List.of(false, true)) {
            var client = new ObservedClient();
            try (Fluxzero app = app(client, new JacksonSerializer())) {
                commit(app, new CreateAliased("root", "alias", 1));
                commit(app, new CreateAliasChild("child", "root"));
                inPendingBatch(app, client, new DeleteAliased("root"), () -> {
                    Graph<Aliased> graph = Graphs.lazy("root", Aliased.class, app.modelRepository());
                    if (idFirst) {
                        assertEquals("root", graph.id());
                    }
                    assertTrue(graph.children(AliasChild.class).isEmpty());
                    assertTrue(graph.isEmpty());
                    assertTrue(graph.children().isEmpty());
                    assertTrue(graph.update(value -> value).children(AliasChild.class).isEmpty());
                    assertTrue(graph.stream().findFirst().orElseThrow().update(value -> value)
                            .children(AliasChild.class).isEmpty());
                });
            }
        }
    }

    @Test
    void customMetadataResolverRetainsValueLookupAndMaterializedProjectionsWithoutOptingIn() {
        Entity<Aliased> empty = ImmutableModelRoot.initial("missing", Aliased.class, "rootId", null);
        var value = new java.util.concurrent.atomic.AtomicReference<Entity<Aliased>>(empty);
        var complete = Graphs.materialized(List.of(
                new Graphs.MaterializedNode("missing", Aliased.class, -1, null, () -> null),
                new Graphs.MaterializedNode("child", AliasChild.class, 0, "children", () -> new AliasChild("child", "missing"))),
                Aliased.class, 0L, null, null, Map.of(), Map.of());
        ModelGraphResolver resolver = (ModelGraphResolver) Proxy.newProxyInstance(
                ModelGraphResolver.class.getClassLoader(), new Class<?>[]{ModelGraphResolver.class, ModelRepository.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return switch (method.getName()) {
                        case "loadGraphValue" -> new ModelGraphResolver.Value(value.get(), ModelReadBoundary.at(0L), false);
                        case "loadGraphProjection" -> complete;
                        case "graphStagedValues" -> ModelBatchScope.Snapshot.EMPTY;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        assertNull(resolver.resolveGraphIdentity("missing", Aliased.class, ModelReadBoundary.current()));
        Graph<Aliased> graph = Graphs.lazy("missing", Aliased.class, (ModelRepository) resolver);
        assertEquals("missing", graph.id());
        assertTrue(graph.isEmpty());
        assertEquals(List.of("child"), ids(graph.children()));
        assertThrows(UnsupportedOperationException.class,
                () -> resolver.loadGraphProjection("missing", Aliased.class, ModelReadBoundary.at(0L), false, empty));
    }

    @Test
    void concurrentIdentityValueAndRelationshipReadsShareOneResolution() throws Exception {
        var client = new ObservedClient();
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = app(client, new JacksonSerializer())) {
            commit(writer, new CreateAliased("root", "alias", 1));
            commit(writer, new CreateAliasChild("child", "root"));
            catalog(reader, Aliased.class, AliasChild.class);
            Graph<Aliased> graph = Graphs.lazy("alias", Aliased.class, reader.modelRepository());
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var lookups = new AtomicInteger();
            client.beforeEvents = request -> {
                if (request.getRequests().getFirst().getModelId().equals("alias")) {
                    lookups.incrementAndGet();
                    entered.complete(null);
                    release.join();
                }
            };
            var identity = new CompletableFuture<Object>();
            var value = new CompletableFuture<Object>();
            var children = new CompletableFuture<Object>();
            try {
                Thread.ofVirtual().start(() -> identity.completeAsync(graph::id, Runnable::run));
                entered.get(5, TimeUnit.SECONDS);
                Thread.ofVirtual().start(() -> value.completeAsync(graph::get, Runnable::run));
                Thread.ofVirtual().start(() -> children.completeAsync(() -> ids(graph.children(AliasChild.class)), Runnable::run));
            } finally {
                release.complete(null);
            }
            assertEquals("root", identity.get(5, TimeUnit.SECONDS));
            assertEquals(new Aliased("root", "alias", 1), value.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("child"), children.get(5, TimeUnit.SECONDS));
            assertEquals(1, lookups.get());
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
        final List<GetModelGraph> queries = new CopyOnWriteArrayList<>();
        final List<GetModelEvents> eventQueries = new CopyOnWriteArrayList<>();
        Consumer<CommitModels> beforeCommit = ignored -> {};
        Consumer<GetModelEvents> beforeEvents = ignored -> {};
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
                            beforeEvents.accept((GetModelEvents) arguments[0]);
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
    record DeleteAliased(String rootId) { @Apply Aliased apply() { return null; } }
    record RejectAlias() { @AssertLegal void check() { throw new IllegalCommandException("rejected"); } }
    @Model record Affixed(@EntityId(prefix = "prefix-") String rootId, @Alias String alias) {}
    record CreateAffixed(String rootId, String alias) {
        @Apply Affixed apply() { return new Affixed(rootId, alias); }
    }
    @Model(persistence = ModelPersistence.DOCUMENT)
    record AliasedDocument(@EntityId String documentId, @Alias String alias) {}
    record CreateAliasedDocument(String documentId, String alias) {
        @Apply AliasedDocument apply() { return new AliasedDocument(documentId, alias); }
    }
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
    @Model record RevisionChild(@EntityId String childId, @Parent(Root.class) String rootId, int version) {}
    record PutRevisionChild(String childId, String rootId, int version) {
        @Apply RevisionChild apply() { return new RevisionChild(childId, rootId, version); }
    }
    record CheckRevision(String receiptId, String rootId) {
        @AssertLegal void check(Graph<Root> root) {
            if (root.find("versioned", RevisionChild.class).orElseThrow().sequenceNumber() != 0) {
                throw new IllegalCommandException("Revision changed");
            }
        }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Receipt apply() { return new Receipt(receiptId, 0); }
    }
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
