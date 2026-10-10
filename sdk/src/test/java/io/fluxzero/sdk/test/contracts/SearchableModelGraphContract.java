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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.search.Facet;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.search.Search;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.fluxzero.common.api.search.constraints.AnyConstraint.any;
import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static io.fluxzero.common.api.search.constraints.ContainsConstraint.contains;
import static io.fluxzero.common.api.search.constraints.LookAheadConstraint.lookAhead;
import static io.fluxzero.common.api.search.constraints.QueryConstraint.query;
import static io.fluxzero.common.api.search.constraints.NotConstraint.not;
import static org.junit.jupiter.api.Assertions.*;

/** The same search contract runs for all storage modes, against local and WebSocket clients. */
@Timeout(30)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class SearchableModelGraphContract extends GraphSchemaMigrationContract {
    protected abstract Client client(String namespace);

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void collectionValueProfileMatchesAnnotatedAndPlainGraphPaths(GraphProjectionMode mode) {
        try (var app = application(mode, false, true)) {
            app.apply(fc -> {
                for (String path : List.of("children/sortableLabel", "children/label")) {
                    assertEquals(List.of("one"), graphs(mode).constraint(
                            io.fluxzero.common.api.search.constraints.BetweenConstraint.below("green", path))
                            .fetchAll().stream().map(Graph::id).toList());
                    assertEquals(List.of("one", "two"), graphs(mode).sortBy(path).fetchAll().stream().map(Graph::id).toList());
                    assertEquals(List.of("one", "two"), graphs(mode).sortBy(path, true).fetchAsync(10).join()
                            .stream().map(Graph::id).toList());
                    assertEquals(0, graphs(mode).constraint(io.fluxzero.common.api.search.SearchValue.max(path).below("green")).count());
                }
                assertEquals(1, graphs(mode).whereChild(types(mode)[1],
                        io.fluxzero.common.api.search.constraints.BetweenConstraint.below("green", "label")).count());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void selectsRootAndChildContentWithoutPruningOtherChildren(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                var search = graphs(mode).match("open", "children/status");
                var found = search.fetchAll();
                assertEquals(1, found.size());
                assertEquals("one", found.getFirst().id());
                assertEquals(2, found.getFirst().children(types(mode)[1]).size());
                assertEquals(1, graphs(mode).match("alpha", "label").fetchAll().size());
                assertEquals(1, graphs(mode).whereChild(types(mode)[1], match("open", "status")).fetchAll().size());
                assertEquals(2, Fluxzero.search(types(mode)[1]).whereAncestor("one", types(mode)[0]).fetchAll().size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void retainsExcludedContentButDoesNotSearchIt(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertTrue(graphs(mode).match("hiddenvalue", "children/secret").fetchAll().isEmpty());
                assertTrue(graphs(mode).match("hiddenvalue").fetchAll().isEmpty());
                assertTrue(graphs(mode).whereChild(types(mode)[1], match("hiddenvalue", "secret")).fetchAll().isEmpty());
                assertEquals(2, graphs(mode).fetchAll().size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void excludesChildFieldsWhenTheSameTextOccursInRootsOrOtherFields(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                var childType = types(mode)[1];
                set(childType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class)
                        .newInstance("a", "one", "open", "alpha", "alpha"));
                for (var constraint : List.of(match("alpha", "children/secret"),
                        contains("alpha", "children/secret"), lookAhead("al", "children/secret"),
                        query("alpha", "children/secret"))) {
                    assertTrue(graphs(mode).constraint(constraint).fetchAll().isEmpty(), constraint::toString);
                    assertTrue(graphs(mode).constraint(constraint).fetchAsync(10).join().isEmpty(), constraint::toString);
                }
                assertTrue(graphs(mode).whereChild(childType, match("alpha", "secret")).fetchAll().isEmpty());
                assertTrue(Fluxzero.search(childType).match("alpha", "secret").fetchAll().isEmpty());
                assertTrue(Fluxzero.searchGraph(childType).match("alpha", "secret").fetchAll().isEmpty());
                assertEquals(1, graphs(mode).match("alpha", "children/label").fetchAll().size());
                ObjectNode json = graphs(mode).match("alpha", "label").fetch(1, ObjectNode.class).getFirst();
                assertEquals("alpha", json.path("children").get(0).path("secret").asText());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void correlatesPredicatesOnOneChildAndSupportsNegation(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertTrue(graphs(mode).whereChild(types(mode)[1], match("open", "status"), match("blue", "label"))
                        .fetchAll().isEmpty());
                assertEquals(1, graphs(mode).whereChild(types(mode)[1], match("open", "status"))
                        .whereChild(types(mode)[1], match("blue", "label")).fetchAll().size());
                assertEquals(List.of("two"), graphs(mode).constraint(not(match("open", "children/status")))
                        .fetchAll().stream().map(Graph::id).toList());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void sortsByMaximumChildValueInBothDirections(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                // Root one has red and blue; root two has green. The maximum is independent of direction.
                assertEquals(0, graphs(mode).constraint(
                        io.fluxzero.common.api.search.constraints.BetweenConstraint.below("green", "children/sortableLabel"))
                        .count());
                assertEquals(1, graphs(mode).whereChild(types(mode)[1],
                        io.fluxzero.common.api.search.constraints.BetweenConstraint.below("green", "sortableLabel")).count());
                assertEquals(List.of("two", "one"), graphs(mode).sortBy("children/sortableLabel").fetchAll()
                        .stream().map(Graph::id).toList());
                assertEquals(List.of("one", "two"), graphs(mode).sortBy("children/sortableLabel", true).fetchAsync(10).join()
                        .stream().map(Graph::id).toList());
                assertEquals(2, graphs(mode).match("blue", "children/label").fetch(1).getFirst()
                        .children(types(mode)[1]).size());
                var childType = types(mode)[1];
                set(childType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class)
                        .newInstance("a", "one", "open", "amber", "hiddenvalue"));
                assertEquals(List.of("one", "two"), graphs(mode).sortBy("children/sortableLabel").fetchAll()
                        .stream().map(Graph::id).toList(), "Replacing the maximum must expose the remaining child's value");
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void sortsAndPagesRootsBeforeReturningCompleteGraphs(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertEquals(List.of("one"), graphs(mode).sortBy("rank").fetch(1).stream().map(Graph::id).toList());
                assertEquals(List.of("two"), graphs(mode).sortBy("rank").skip(1).fetch(1).stream().map(Graph::id).toList());
                assertEquals(2, graphs(mode).sortBy("rank").fetch(1).getFirst().children(types(mode)[1]).size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void explicitSelfScopeDoesNotActivateOrLoadChildren(GraphProjectionMode mode) {
        try (var app = application(mode, true)) {
            app.apply(fc -> {
                var rootType = types(mode, true)[0];
                var childType = types(mode, true)[1];
                List<Graph<?>> roots = (List) Fluxzero.searchGraph(rootType).fetchAll();
                assertEquals(2, roots.size());
                roots.forEach(root -> assertTrue(root.children(childType).isEmpty()));
                assertTrue(Fluxzero.search(childType).fetchAll().isEmpty());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void supportsDisjunctionAndAncestorContentFilters(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertEquals(2, graphs(mode).constraint(any(match("alpha", "label"), match("green", "children/label")))
                        .fetchAll().size());
                assertEquals(2, Fluxzero.search(types(mode)[1])
                        .whereAncestor(types(mode)[0], match("alpha", "label")).fetchAll().size());
                assertEquals(1, Fluxzero.searchGraph(types(mode)[1])
                        .whereParent(types(mode)[0], match("beta", "label")).fetchAll().size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void supportsAsyncFetchAndPagedStreams(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertEquals(List.of("one", "two"), graphs(mode).sortBy("rank").fetchAsync(10).join()
                        .stream().map(Graph::id).toList());
                try (var hits = graphs(mode).sortBy("rank").streamHits(1)) {
                    assertEquals(List.of("one", "two"), hits.map(hit -> hit.getValue().id()).toList());
                }
                assertEquals(List.of("two"), graphs(mode).sortBy("rank").skip(1).fetchAsync(1).join()
                        .stream().map(Graph::id).toList());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void filtersResultFieldsOnlyAfterSelectingCompleteGraphs(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertThrows(IllegalStateException.class, () -> graphs(mode).includeOnly("children").fetchAll());
                ObjectNode json = graphs(mode).match("open", "children/status")
                        .includeOnly("children").fetch(1, ObjectNode.class).getFirst();
                assertFalse(json.has("id"));
                assertEquals(2, json.get("children").size());
                assertEquals("hiddenvalue", json.get("children").get(0).get("secret").asText());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void updatesMovesAndDeletesAreReflectedInSearch(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                var childType = types(mode)[1];
                set(childType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class)
                        .newInstance("a", "two", "changed", "red", "hiddenvalue"));
                assertTrue(graphs(mode).match("open", "children/status").fetchAll().isEmpty());
                Graph<?> moved = graphs(mode).match("changed", "children/status").fetchAll().getFirst();
                assertEquals("two", moved.id());
                assertEquals(2, moved.children(childType).size());
                assertEquals(1, Fluxzero.search(childType).whereAncestor("one", types(mode)[0]).fetchAll().size());
                set(childType, "a", null);
                assertTrue(graphs(mode).match("changed", "children/status").fetchAll().isEmpty());
                set(types(mode)[0], "one", null);
                assertEquals(List.of("two"), graphs(mode).fetchAll().stream().map(Graph::id).toList());
                assertEquals(1, Fluxzero.search(childType).fetchAll().size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void statisticsUseTheFullSelectionIncludingRelatedPredicates(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                assertEquals(2L, graphs(mode).skip(1).includeOnly("label").count());
                assertEquals(2L, graphs(mode).countAsync().join());
                assertEquals(0, graphs(mode).aggregate("rank").get("rank").getSum().compareTo(new BigDecimal("3")));
                assertEquals(2, graphs(mode).groupBy("label").count().size());
                assertEquals(graphs(mode).groupBy("label").count(), graphs(mode).groupBy("label").countAsync().join());
                assertEquals(1L, graphs(mode).whereChild(types(mode)[1], match("open", "status")).count());
                assertEquals(1L, graphs(mode).whereChild(types(mode)[1], match("open", "status")).countAsync().join());
                Map<String, Integer> labels = graphs(mode).facetStats().stream()
                        .filter(stat -> stat.getName().equals("label"))
                        .collect(java.util.stream.Collectors.toMap(stat -> stat.getValue(), stat -> stat.getCount()));
                assertEquals(Map.of("alpha", 1, "beta", 1), labels);
                assertEquals(graphs(mode).facetStats(), graphs(mode).facetStatsAsync().join());
                assertEquals(0L, graphs(mode).match("missing", "label").count());
                assertTrue(graphs(mode).match("missing", "label").facetStats().isEmpty());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void appliesSkipOnlyOnTheFirstTransportPage(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                Class<?> rootType = types(mode)[0];
                set(rootType.getDeclaredConstructor(String.class, String.class, int.class).newInstance("three", "gamma", 3));
                set(rootType.getDeclaredConstructor(String.class, String.class, int.class).newInstance("four", "delta", 4));
                try (var hits = graphs(mode).sortBy("rank").skip(1).streamHits(1)) {
                    assertEquals(List.of("two", "three", "four"), hits.map(hit -> hit.getValue().id()).toList());
                }
                var all = graphs(mode).sortByTimestamp().fetchAll().stream().map(Graph::id).toList();
                try (var hits = graphs(mode).sortByTimestamp().skip(1).streamHits(1)) {
                    assertEquals(all.subList(1, all.size()), hits.map(hit -> hit.getValue().id()).toList());
                }
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void returnsAllChildrenOfAGraphWithHundredsOfDocuments(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                Class<?> type = types(mode)[1];
                var constructor = type.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class);
                Class<?> commandType = Class.forName(SearchableModelGraphContract.class.getName() + "$Set" + type.getSimpleName());
                var commands = new java.util.ArrayList<io.fluxzero.sdk.common.Message>();
                for (int i = 0; i < 200; i++) {
                    String id = "bulk-" + i;
                    Object child = constructor.newInstance(id, "one", "closed", i == 199 ? "needle" : "bulk", "hiddenvalue");
                    commands.add(new io.fluxzero.sdk.common.Message(commandType.getDeclaredConstructor(String.class, type)
                            .newInstance(id, child)));
                }
                fc.executeModelCommits(commands).join();
                var selected = graphs(mode).whereChild(type, match("needle", "label")).fetch(1);
                assertEquals(1, selected.size());
                assertEquals("one", selected.getFirst().id());
                assertEquals(202, selected.getFirst().children(type).size());
                assertEquals(202, graphs(mode).match("needle", "children/label").fetchAsync(1).join()
                        .getFirst().children(type).size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void composesMultipleLevelsAndSearchesOverlappingGraphs(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                Class<?> noteType = switch (mode) {
                    case NONE -> LiveNote.class;
                    case ASYNC -> AsyncNote.class;
                    case AWAIT -> AwaitNote.class;
                };
                set(noteType.getDeclaredConstructor(String.class, String.class, String.class)
                        .newInstance("note", "a", "deepneedle"));
                var roots = graphs(mode).match("deepneedle", "children/annotations/text").fetchAll();
                assertEquals(List.of("one"), roots.stream().map(Graph::id).toList());
                assertEquals(2, roots.getFirst().children(types(mode)[1]).size());
                assertEquals(1, roots.getFirst().children(types(mode)[1]).stream()
                        .mapToInt(child -> child.children(noteType).size()).sum());
                assertTrue(graphs(mode).match("deepneedle", "children/notes/text").fetchAll().isEmpty());
                assertEquals(1, Fluxzero.search(noteType).whereAncestor("one", types(mode)[0]).count());
                assertEquals(1, Fluxzero.searchGraph(types(mode)[1]).match("deepneedle", "notes/text").count());
                set(noteType.getDeclaredConstructor(String.class, String.class, String.class)
                        .newInstance("note", "c", "movedneedle"));
                assertEquals(List.of("two"), graphs(mode).match("movedneedle").fetchAll().stream()
                        .map(Graph::id).toList());
                assertEquals(List.of("c"), Fluxzero.searchGraph(types(mode)[1]).match("movedneedle")
                        .fetchAll().stream().map(Graph::id).toList());
                set(noteType, "note", null);
                assertTrue(graphs(mode).match("movedneedle").fetchAll().isEmpty());
                assertEquals(0, Fluxzero.searchGraph(types(mode)[1]).match("movedneedle").count());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void inferredGraphHandlersObserveChildrenAndMovesButNotAncestorOnlyChanges(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                GraphObserver observer = observer(mode);
                var registration = fc.registerHandlers(observer);
                try {
                    Class<?> rootType = types(mode)[0], childType = types(mode)[1];
                    var child = childType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class);
                    set(child.newInstance("a", "one", "changed", "red", "hiddenvalue"));
                    Graph<?> root = take(observer.roots, g -> g.id().equals("one")
                            && g.childModels(childType).stream().anyMatch(v -> v.toString().contains("changed")));
                    Graph<?> handledChild = take(observer.children, g -> g.id().equals("a")
                            && g.get().toString().contains("changed"));
                    assertEquals(2, root.children(childType).size());
                    if (mode == GraphProjectionMode.NONE) {
                        assertNull(root.previous());
                        assertNull(root.children(childType).getFirst().previous());
                        assertNull(handledChild.previous());
                    }
                    var definition = ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository())
                            .graphSearchDefinition(childType).orElseThrow();
                    var query = new io.fluxzero.common.api.search.GetDocument("a", definition.getCollection());
                    // NONE handlers hydrate live nodes even when handling an older notification marker.
                    // Settle preceding child updates before attributing later marker changes to the parent.
                    awaitGraphNotifications(fc, definition.getCollection(), "a");
                    var before = fc.client().getSearchClient().fetch(query).orElseThrow();
                    set(rootType.getDeclaredConstructor(String.class, String.class, int.class).newInstance("one", "renamed", 1));
                    take(observer.roots, g -> g.id().equals("one") && g.get().toString().contains("renamed"));
                    // Also process the parent boundary: an early read must not conceal a spurious child update.
                    awaitGraphNotifications(fc, definition.getCollection(), "a");
                    var after = fc.client().getSearchClient().fetch(query).orElseThrow();
                    assertEquals(io.fluxzero.common.search.ModelGraphDocumentManifest.from(before),
                            io.fluxzero.common.search.ModelGraphDocumentManifest.from(after),
                            "Ancestor-only changes must not produce a Task Graph update");
                    set(child.newInstance("a", "two", "moved", "red", "hiddenvalue"));
                    take(observer.roots, g -> g.id().equals("one") && g.children(childType).size() == 1);
                    take(observer.roots, g -> g.id().equals("two") && g.children(childType).size() == 2);
                    take(observer.children, g -> g.id().equals("a") && g.get().toString().contains("moved"));
                    assertNotNull(Fluxzero.loadCurrentGraph("a", childType).get(), "Returning null must not delete a Model");
                    if (mode == GraphProjectionMode.NONE) {
                        var stored = fc.client().getSearchClient().fetch(query).orElseThrow();
                        assertEquals(io.fluxzero.common.search.ModelGraphInvalidation.class.getName(), stored.getDocument().getType());
                        assertFalse(stored.getDocument().getType().equals(childType.getName()));
                    }
                } finally { registration.cancel(); }
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void inferredGraphHandlersReceiveTypedDeletions(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                GraphObserver observer = observer(mode);
                var registration = fc.registerHandlers(observer);
                try {
                    set(types(mode)[0], "one", null);
                    Graph<?> deleted = take(observer.roots, g -> g.id().equals("one") && g.isEmpty());
                    assertEquals(types(mode)[0], deleted.type());
                    if (mode == GraphProjectionMode.NONE) { assertNull(deleted.previous()); }
                    assertTrue(Fluxzero.loadCurrentGraph("one", types(mode)[0]).isEmpty());
                } finally { registration.cancel(); }
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void hardErasureRemovesNodeAndGraphSearchStateAndNotifiesSurvivingParents(GraphProjectionMode mode) {
        try (var app = application(mode)) {
            app.apply(fc -> {
                GraphObserver observer = observer(mode);
                var registration = fc.registerHandlers(observer);
                try {
                    Class<?> rootType = types(mode)[0], childType = types(mode)[1];
                    var erased = fc.modelRepository().deleteModel("erase-child", "a",
                            io.fluxzero.common.api.modeling.ModelDeletionCascade.NONE).join();
                    var definition = ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository())
                            .graphSearchDefinition(rootType).orElseThrow();
                    fc.client().getEventStoreClient().awaitModelGraphProjection(
                            new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                                    definition.getCollection(), erased.getStateIndex())).join();
                    assertFalse(Fluxzero.hasDocument("a", childType));
                    assertEquals(2, Fluxzero.search(childType).fetchAll().size());
                    Graph<?> parent = take(observer.roots, graph -> graph.id().equals("one")
                            && graph.children(childType).size() == 1);
                    assertEquals("b", parent.children(childType).getFirst().id());
                    assertTrue(graphs(mode).match("open", "children/status").fetchAll().isEmpty());

                    var plan = fc.modelRepository().planDeletion("one",
                            io.fluxzero.common.api.modeling.ModelDeletionCascade.DESCENDANTS);
                    var rootErased = fc.modelRepository().deleteModel("erase-root", plan).join();
                    fc.client().getEventStoreClient().awaitModelGraphProjection(
                            new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                                    definition.getCollection(), rootErased.getStateIndex())).join();
                    assertFalse(Fluxzero.hasDocument("one", rootType));
                    assertFalse(Fluxzero.hasDocument("b", childType));
                    assertEquals(List.of("two"), graphs(mode).fetchAll().stream().map(Graph::id).toList());
                    assertEquals(1, Fluxzero.search(childType).fetchAll().size());
                } finally { registration.cancel(); }
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void selfOnlyGraphsDoNotChangeWhenAnExcludedChildIsHardErased(GraphProjectionMode mode) {
        try (var app = application(mode, true)) {
            app.apply(fc -> {
                Class<?> rootType = types(mode, true)[0], childType = types(mode, true)[1];
                fc.modelRepository().registerGraphProjection(rootType, false).join();
                var definition = ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository())
                        .graphSearchDefinition(rootType).orElseThrow();
                var client = fc.client().getEventStoreClient();
                var registered = client.getModelGraphProjectionStatus(
                        new io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus(definition.getCollection()));
                client.awaitModelGraphProjection(new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                        definition.getCollection(), registered.getSourceStateIndex())).join();
                var query = new io.fluxzero.common.api.search.GetDocument("one", definition.getCollection());
                var before = fc.client().getSearchClient().fetch(query).orElseThrow();
                var erased = fc.modelRepository().deleteModel("erase-child", "a",
                        io.fluxzero.common.api.modeling.ModelDeletionCascade.NONE).join();
                client.awaitModelGraphProjection(new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                        definition.getCollection(), erased.getStateIndex())).join();
                assertFalse(Fluxzero.hasDocument("a", childType));
                var after = fc.client().getSearchClient().fetch(query).orElseThrow();
                assertEquals(io.fluxzero.common.search.ModelGraphDocumentManifest.from(before),
                        io.fluxzero.common.search.ModelGraphDocumentManifest.from(after));
                assertEquals(2, Fluxzero.searchGraph(rootType).fetchAll().size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void graphConsumerRegistersAndHydratesInItsSelectedNamespace(GraphProjectionMode mode) throws Exception {
        try (var writer = application(mode);
             var consumer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .configureDefaultConsumer(io.fluxzero.common.MessageType.DOCUMENT,
                             config -> config.toBuilder().namespace(writer.client().namespace()).build())
                     .build(writer.client().forNamespace("observer-contract-" + UUID.randomUUID()))) {
            if (mode == GraphProjectionMode.NONE) {
                var definition = ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) writer.modelRepository())
                        .graphSearchDefinition(types(mode)[0]).orElseThrow();
                assertTrue(writer.client().getSearchClient().fetch(new io.fluxzero.common.api.search.GetDocument(
                        "one", definition.getCollection())).isEmpty(), "Query-only NONE must not maintain notification markers");
            }
            GraphObserver observer = observer(mode);
            var registration = consumer.apply(fc -> fc.registerHandlers(observer));
            try {
                writer.apply(fc -> {
                    set(types(mode)[1].getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class)
                            .newInstance("a", "one", "remote-update", "red", "hiddenvalue"));
                    return null;
                });
                Graph<?> root = take(observer.roots, g -> g.id().equals("one")
                        && g.childModels(types(mode)[1]).stream().anyMatch(v -> v.toString().contains("remote-update")));
                assertEquals(2, root.children(types(mode)[1]).size());
            } finally { registration.cancel(); }
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void childCommitsIncludeConcretePolymorphicParentProjections(GraphProjectionMode mode) throws Exception {
        Class<?> rootType = switch (mode) {
            case NONE -> PolyLiveRoot.class;
            case ASYNC -> PolyAsyncRoot.class;
            case AWAIT -> PolyAwaitRoot.class;
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT)
                .build(client("polymorphic-search-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                set(rootType.getDeclaredConstructor(String.class).newInstance("first"));
                set(rootType.getDeclaredConstructor(String.class).newInstance("second"));
                set(new PolyChild("child", "first", "before"));
                assertEquals(1, Fluxzero.searchGraph(rootType).match("before", "children/value").fetchAll().size());
                set(new PolyChild("child", "second", "after"));
                assertTrue(Fluxzero.searchGraph(rootType).match("before", "children/value").fetchAll().isEmpty());
                assertEquals("second", Fluxzero.searchGraph(rootType).match("after", "children/value")
                        .fetchAll().getFirst().id());
                return null;
            });
        }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT)
    public interface PolyRoot { @EntityId String id(); }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT)
    public record PolyLiveRoot(@EntityId String id) implements PolyRoot { }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC))
    public record PolyAsyncRoot(@EntityId String id) implements PolyRoot { }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    public record PolyAwaitRoot(@EntityId String id) implements PolyRoot { }
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record PolyChild(@EntityId String id, @Parent(value = PolyRoot.class, pathInParent = "children") String parentId,
                            String value) { }
    public record SetPolyLiveRoot(String id, PolyLiveRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public PolyLiveRoot apply(@jakarta.annotation.Nullable PolyLiveRoot previous) { return value; }
    }
    public record SetPolyAsyncRoot(String id, PolyAsyncRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public PolyAsyncRoot apply(@jakarta.annotation.Nullable PolyAsyncRoot previous) { return value; }
    }
    public record SetPolyAwaitRoot(String id, PolyAwaitRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public PolyAwaitRoot apply(@jakarta.annotation.Nullable PolyAwaitRoot previous) { return value; }
    }
    public record SetPolyChild(String id, PolyChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public PolyChild apply(@jakarta.annotation.Nullable PolyChild previous) { return value; }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void parentSearchExclusionCutsSubtreeButRetainsIndependentSearchAndDomainGraph(GraphProjectionMode mode) {
        try (var app = excludedApplication(mode)) {
            app.apply(fc -> {
                Class<?> rootType = excludedRoot(mode);
                assertTrue(Fluxzero.searchGraph(rootType).match("branchneedle").fetchAll().isEmpty());
                assertTrue(Fluxzero.searchGraph(rootType).match("leafneedle").fetchAll().isEmpty());
                ObjectNode json = Fluxzero.searchGraph(rootType).fetch(1, ObjectNode.class).getFirst();
                assertFalse(json.has("privateBranches"));
                assertFalse(json.has("unindexed"));
                var searchGraph = Fluxzero.searchGraph(rootType).fetchAll().getFirst();
                assertTrue(searchGraph.children(ExcludedBranch.class).isEmpty());
                String searchResponse = new String(assertInstanceOf(byte[].class,
                        fc.serializer().serialize(searchGraph).getValue()), java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(searchResponse.contains("privateBranches"));
                assertEquals(1, Fluxzero.search(ExcludedBranch.class).whereAncestor("root", rootType).count());
                assertEquals(1, Fluxzero.search(ExcludedLeaf.class).whereAncestor("root", rootType).count());
                assertEquals(1, Fluxzero.searchGraph(ExcludedBranch.class).match("leafneedle", "leaves/text").count());
                assertEquals(1, Fluxzero.search(ExcludedBranch.class).whereParent(rootType, match("root", "id")).count());
                assertEquals(0, Fluxzero.search(UnindexedBranch.class).count());
                assertEquals(0, Fluxzero.search(UnindexedLeaf.class).count());
                var domainGraph = Fluxzero.loadCurrentGraph("root", rootType);
                assertEquals(1, domainGraph.children(ExcludedBranch.class).size());
                assertEquals(1, domainGraph.children(ExcludedBranch.class).getFirst().children(ExcludedLeaf.class).size());
                String response = new String(assertInstanceOf(byte[].class,
                        fc.serializer().serialize(domainGraph).getValue()), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(response.contains("leafneedle"));
                assertTrue(response.contains("privateBranches"));
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void alternateIncludedParentStillComposesTheSameSubtree(GraphProjectionMode mode) {
        try (var app = excludedApplication(mode)) {
            app.apply(fc -> {
                Class<?> rootType = excludedRoot(mode);
                set(new ExcludedBranch("branch", "root", "root", "branchneedle"));
                ObjectNode json = Fluxzero.searchGraph(rootType).fetch(1, ObjectNode.class).getFirst();
                assertFalse(json.has("privateBranches"));
                assertEquals("leafneedle", json.path("sharedBranches").get(0).path("leaves").get(0).path("text").asText());
                assertEquals(1, Fluxzero.searchGraph(rootType).match("leafneedle").count());
                set(new ExcludedBranch("branch", "root", null, "branchneedle"));
                assertEquals(0, Fluxzero.searchGraph(rootType).match("leafneedle").count());
                assertEquals(1, Fluxzero.loadCurrentGraph("root", rootType).children(ExcludedBranch.class).size());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void excludedWritesAndHardErasureDoNotInvalidateAncestorGraphDocuments(GraphProjectionMode mode) {
        try (var app = excludedApplication(mode)) {
            app.apply(fc -> {
                Class<?> rootType = excludedRoot(mode);
                ExcludedObserver observer = switch (mode) {
                    case NONE -> new ExcludedLiveObserver();
                    case ASYNC -> new ExcludedAsyncObserver();
                    case AWAIT -> new ExcludedAwaitObserver();
                };
                var registration = fc.registerHandlers(observer);
                try {
                    fc.modelRepository().registerGraphProjection(rootType, false).join();
                    var definition = ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository())
                            .graphSearchDefinition(rootType).orElseThrow();
                    awaitProjection(fc, definition.getCollection());
                    var query = new io.fluxzero.common.api.search.GetDocument("root", definition.getCollection());
                    var before = fc.client().getSearchClient().fetch(query).orElseThrow();
                    set(new ExcludedBranch("branch", "root", null, "changedbranch"));
                    take(observer.branches, g -> g.get().toString().contains("changedbranch"));
                    take(observer.events, g -> g.childModels(ExcludedBranch.class).stream()
                            .anyMatch(v -> v.text().equals("changedbranch")));
                    set(new ExcludedLeaf("leaf", "branch", "changedleaf"));
                    take(observer.branches, g -> g.childModels(ExcludedLeaf.class).stream()
                            .anyMatch(v -> v.text().equals("changedleaf")));
                    take(observer.events, g -> g.children(ExcludedBranch.class).stream()
                            .flatMap(branch -> branch.childModels(ExcludedLeaf.class).stream())
                            .anyMatch(v -> v.text().equals("changedleaf")));
                    awaitProjection(fc, definition.getCollection());
                    assertEquals(io.fluxzero.common.search.ModelGraphDocumentManifest.from(before),
                            io.fluxzero.common.search.ModelGraphDocumentManifest.from(
                                    fc.client().getSearchClient().fetch(query).orElseThrow()));
                    var plan = fc.modelRepository().planDeletion("branch",
                            io.fluxzero.common.api.modeling.ModelDeletionCascade.DESCENDANTS);
                    var erased = fc.modelRepository().deleteModel("erase-excluded", plan).join();
                    fc.client().getEventStoreClient().awaitModelGraphProjection(
                            new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                                    definition.getCollection(), erased.getStateIndex())).join();
                    assertEquals(io.fluxzero.common.search.ModelGraphDocumentManifest.from(before),
                            io.fluxzero.common.search.ModelGraphDocumentManifest.from(
                                    fc.client().getSearchClient().fetch(query).orElseThrow()));
                    assertEquals(0, Fluxzero.search(ExcludedLeaf.class).count());
                } finally { registration.cancel(); }
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void excludedBranchesRetainCascadeDeletion(GraphProjectionMode mode) {
        try (var app = excludedApplication(mode)) {
            app.apply(fc -> {
                set(excludedRoot(mode), "root", null);
                assertTrue(Fluxzero.loadCurrentGraph("branch", ExcludedBranch.class).isEmpty());
                assertTrue(Fluxzero.loadCurrentGraph("leaf", ExcludedLeaf.class).isEmpty());
                assertTrue(Fluxzero.loadCurrentGraph("unindexed", UnindexedBranch.class).isEmpty());
                assertTrue(Fluxzero.loadCurrentGraph("unindexed-leaf", UnindexedLeaf.class).isEmpty());
                return null;
            });
        }
    }

    private static void awaitProjection(Fluxzero fc, String collection) {
        var client = fc.client().getEventStoreClient();
        var status = client.getModelGraphProjectionStatus(
                new io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus(collection));
        client.awaitModelGraphProjection(new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                collection, status.getSourceStateIndex())).join();
    }

    private Fluxzero excludedApplication(GraphProjectionMode mode) {
        var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT)
                .build(client("excluded-parent-" + UUID.randomUUID()));
        app.apply(fc -> {
            // Test-only nested Models have no generated production catalog; include the abstract parent contract too.
            ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository()).configureModelTypes(
                    () -> List.of(PolyRoot.class, excludedRoot(mode), ExcludedBranch.class, ExcludedLeaf.class,
                            UnindexedBranch.class, UnindexedLeaf.class));
            set(excludedRoot(mode).getDeclaredConstructor(String.class).newInstance("root"));
            set(new ExcludedBranch("branch", "root", null, "branchneedle"));
            set(new ExcludedLeaf("leaf", "branch", "leafneedle"));
            set(new UnindexedBranch("unindexed", "root"));
            set(new UnindexedLeaf("unindexed-leaf", "unindexed"));
            return null;
        });
        return app;
    }

    private static Class<?> excludedRoot(GraphProjectionMode mode) {
        return switch (mode) {
            case NONE -> PolyLiveRoot.class;
            case ASYNC -> PolyAsyncRoot.class;
            case AWAIT -> PolyAwaitRoot.class;
        };
    }

    public static class ExcludedObserver {
        final java.util.concurrent.BlockingQueue<Graph<?>> events = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.BlockingQueue<Graph<?>> branches = new java.util.concurrent.LinkedBlockingQueue<>();
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        void branch(Graph<ExcludedBranch> graph) { branches.add(graph); }
    }
    public static class ExcludedLiveObserver extends ExcludedObserver {
        @io.fluxzero.sdk.tracking.handling.HandleEvent void root(Graph<PolyLiveRoot> graph) { events.add(graph); }
    }
    public static class ExcludedAsyncObserver extends ExcludedObserver {
        @io.fluxzero.sdk.tracking.handling.HandleEvent void root(Graph<PolyAsyncRoot> graph) { events.add(graph); }
    }
    public static class ExcludedAwaitObserver extends ExcludedObserver {
        @io.fluxzero.sdk.tracking.handling.HandleEvent void root(Graph<PolyAwaitRoot> graph) { events.add(graph); }
    }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT)
    public record ExcludedBranch(@EntityId String id,
            @Parent(value = PolyRoot.class, pathInParent = "privateBranches", propagateSearch = false) String privateParent,
            @Parent(value = PolyRoot.class, pathInParent = "sharedBranches") String sharedParent,
            @Facet String text) { }
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record ExcludedLeaf(@EntityId String id,
            @Parent(value = ExcludedBranch.class, pathInParent = "leaves") String parent, @Facet String text) { }
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record UnindexedBranch(@EntityId String id,
            @Parent(value = PolyRoot.class, pathInParent = "unindexed", propagateSearch = false) String parent) { }
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record UnindexedLeaf(@EntityId String id,
            @Parent(value = UnindexedBranch.class, pathInParent = "leaves") String parent) { }
    public record SetExcludedBranch(String id, ExcludedBranch value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public ExcludedBranch apply(@jakarta.annotation.Nullable ExcludedBranch previous) { return value; }
    }
    public record SetExcludedLeaf(String id, ExcludedLeaf value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public ExcludedLeaf apply(@jakarta.annotation.Nullable ExcludedLeaf previous) { return value; }
    }
    public record SetUnindexedBranch(String id, UnindexedBranch value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public UnindexedBranch apply(@jakarta.annotation.Nullable UnindexedBranch previous) { return value; }
    }
    public record SetUnindexedLeaf(String id, UnindexedLeaf value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public UnindexedLeaf apply(@jakarta.annotation.Nullable UnindexedLeaf previous) { return value; }
    }

    private static void awaitGraphNotifications(Fluxzero app, String collection, String modelId) {
        var events = app.client().getEventStoreClient();
        long boundary = events.getModelEvents(new io.fluxzero.common.api.modeling.GetModelEvents(
                List.of(new io.fluxzero.common.api.modeling.ModelEventStreamRequest(modelId, -1L, 0)),
                io.fluxzero.common.api.modeling.ModelReadBoundary.current(), 0)).getStateIndex();
        events.awaitModelGraphProjection(new io.fluxzero.common.api.modeling.AwaitModelGraphProjection(
                collection, boundary)).join();
    }

    private static Graph<?> take(java.util.concurrent.BlockingQueue<Graph<?>> values,
                                 java.util.function.Predicate<Graph<?>> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var pending = new java.util.ArrayList<Graph<?>>();
        try {
            while (true) {
                Graph<?> value = values.poll(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                assertNotNull(value, "Expected Graph update was not delivered");
                if (predicate.test(value)) { return value; }
                pending.add(value);
            }
        } finally { values.addAll(pending); }
    }

    private static GraphObserver observer(GraphProjectionMode mode) {
        return switch (mode) {
            case NONE -> new LiveObserver();
            case ASYNC -> new AsyncObserver();
            case AWAIT -> new AwaitObserver();
        };
    }

    public abstract static class GraphObserver {
        final java.util.concurrent.BlockingQueue<Graph<?>> roots = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.BlockingQueue<Graph<?>> children = new java.util.concurrent.LinkedBlockingQueue<>();
    }
    public static class LiveObserver extends GraphObserver {
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        Graph<LiveRoot> root(io.fluxzero.common.api.Metadata metadata, Graph<LiveRoot> graph) { roots.add(graph); return graph; }
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        Graph<LiveChild> child(Graph<LiveChild> graph) { children.add(graph); return null; }
    }
    public static class AsyncObserver extends GraphObserver {
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        Graph<AsyncRoot> root(io.fluxzero.common.api.Metadata metadata, Graph<AsyncRoot> graph) { roots.add(graph); return graph; }
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        Graph<AsyncChild> child(Graph<AsyncChild> graph) { children.add(graph); return null; }
    }
    public static class AwaitObserver extends GraphObserver {
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        Graph<AwaitRoot> root(io.fluxzero.common.api.Metadata metadata, Graph<AwaitRoot> graph) { roots.add(graph); return graph; }
        @io.fluxzero.sdk.tracking.handling.HandleDocument
        Graph<AwaitChild> child(Graph<AwaitChild> graph) { children.add(graph); return null; }
    }

    private Fluxzero application(GraphProjectionMode mode) { return application(mode, false); }

    private Fluxzero application(GraphProjectionMode mode, boolean selfScope) {
        return application(mode, selfScope, false);
    }

    private Fluxzero application(GraphProjectionMode mode, boolean selfScope, boolean values) {
        Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(source -> values ? new io.fluxzero.common.application.SimplePropertySource(
                        java.util.Map.of("fluxzero.search.collectionValues", "true")) : source)
                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT)
                .build(client("search-contract-" + UUID.randomUUID()));
        try {
            app.apply(fc -> {
                Class<?>[] types = types(mode, selfScope);
                // Seed independent roots together, then their children. Each phase retains durable/projection completion.
                fc.executeModelCommits(List.of(
                        command(types[0].getDeclaredConstructor(String.class, String.class, int.class)
                                .newInstance("one", "alpha", 1)),
                        command(types[0].getDeclaredConstructor(String.class, String.class, int.class)
                                .newInstance("two", "beta", 2)))).join();
                var children = new java.util.ArrayList<io.fluxzero.sdk.common.Message>();
                for (String[] child : List.of(new String[]{"a", "one", "open", "red"},
                        new String[]{"b", "one", "closed", "blue"}, new String[]{"c", "two", "closed", "green"})) {
                    children.add(command(types[1].getDeclaredConstructor(
                                    String.class, String.class, String.class, String.class, String.class)
                            .newInstance(child[0], child[1], child[2], child[3], "hiddenvalue")));
                }
                fc.executeModelCommits(children).join();
                return null;
            });
            return app;
        } catch (Throwable failure) {
            try {
                app.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw io.fluxzero.common.ObjectUtils.rethrow(failure);
        }
    }

    private static io.fluxzero.sdk.common.Message command(Object value) throws Exception {
        Class<?> type = value.getClass();
        Class<?> commandType = Class.forName(SearchableModelGraphContract.class.getName() + "$Set" + type.getSimpleName());
        return new io.fluxzero.sdk.common.Message(commandType.getDeclaredConstructor(String.class, type)
                .newInstance(EntityMetadata.of(type).repositoryIdOf(value), value));
    }

    private static void set(Object value) throws Exception {
        set(value.getClass(), EntityMetadata.of(value.getClass()).repositoryIdOf(value), value);
    }

    private static void set(Class<?> type, String id, Object value) throws Exception {
        Class<?> commandType = Class.forName(SearchableModelGraphContract.class.getName() + "$Set" + type.getSimpleName());
        Object command = commandType.getDeclaredConstructor(String.class, type).newInstance(id, value);
        Fluxzero.get().executeModelCommit(new io.fluxzero.sdk.common.Message(command)).join();
    }

    private static Search<Graph<Object>> graphs(GraphProjectionMode mode) {
        return Fluxzero.searchGraph((Class) types(mode)[0]);
    }

    private static Class<?>[] types(GraphProjectionMode mode) { return types(mode, false); }

    private static Class<?>[] types(GraphProjectionMode mode, boolean selfScope) {
        if (selfScope) {
            return switch (mode) {
                case NONE -> new Class<?>[]{SelfLiveRoot.class, SelfLiveChild.class};
                case ASYNC -> new Class<?>[]{SelfAsyncRoot.class, SelfAsyncChild.class};
                case AWAIT -> new Class<?>[]{SelfAwaitRoot.class, SelfAwaitChild.class};
            };
        }
        return switch (mode) {
            case NONE -> new Class<?>[]{LiveRoot.class, LiveChild.class};
            case ASYNC -> new Class<?>[]{AsyncRoot.class, AsyncChild.class};
            case AWAIT -> new Class<?>[]{AwaitRoot.class, AwaitChild.class};
        };
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.NONE,
                    pathOverrides = @GraphPathOverride(path = "notes", projectionPath = "annotations")))
    public record LiveRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.NONE))
    public record LiveChild(@EntityId String id, @Parent(value = LiveRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {
        @Sortable public String getSortableLabel() { return label; }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC,
                    pathOverrides = @GraphPathOverride(path = "notes", projectionPath = "annotations")))
    public record AsyncRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC))
    public record AsyncChild(@EntityId String id, @Parent(value = AsyncRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {
        @Sortable public String getSortableLabel() { return label; }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT,
                    pathOverrides = @GraphPathOverride(path = "notes", projectionPath = "annotations")))
    public record AwaitRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    public record AwaitChild(@EntityId String id, @Parent(value = AwaitRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {
        @Sortable public String getSortableLabel() { return label; }
    }

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record LiveNote(@EntityId String id,
            @Parent(value = LiveChild.class, pathInParent = "notes") String childId, String text) {}

    public record SetLiveNote(String id, LiveNote value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply
        public LiveNote apply(@jakarta.annotation.Nullable LiveNote previous) { return value; }
    }

    public record SetLiveRoot(String id, LiveRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public LiveRoot apply(@jakarta.annotation.Nullable LiveRoot previous) { return value; }
    }

    public record SetLiveChild(String id, LiveChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public LiveChild apply(@jakarta.annotation.Nullable LiveChild previous) { return value; }
    }

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record AsyncNote(@EntityId String id,
            @Parent(value = AsyncChild.class, pathInParent = "notes") String childId, String text) {}

    public record SetAsyncNote(String id, AsyncNote value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply
        public AsyncNote apply(@jakarta.annotation.Nullable AsyncNote previous) { return value; }
    }

    public record SetAsyncRoot(String id, AsyncRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public AsyncRoot apply(@jakarta.annotation.Nullable AsyncRoot previous) { return value; }
    }

    public record SetAsyncChild(String id, AsyncChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public AsyncChild apply(@jakarta.annotation.Nullable AsyncChild previous) { return value; }
    }

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record AwaitNote(@EntityId String id,
            @Parent(value = AwaitChild.class, pathInParent = "notes") String childId, String text) {}

    public record SetAwaitNote(String id, AwaitNote value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply
        public AwaitNote apply(@jakarta.annotation.Nullable AwaitNote previous) { return value; }
    }

    public record SetAwaitRoot(String id, AwaitRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public AwaitRoot apply(@jakarta.annotation.Nullable AwaitRoot previous) { return value; }
    }

    public record SetAwaitChild(String id, AwaitChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public AwaitChild apply(@jakarta.annotation.Nullable AwaitChild previous) { return value; }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            searchSettings = @SearchSettings(includeDescendants = false),
            graphProjection = @GraphProjection(mode = GraphProjectionMode.NONE))
    public record SelfLiveRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record SelfLiveChild(@EntityId String id, @Parent(value = SelfLiveRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {}

    public record SetSelfLiveRoot(String id, SelfLiveRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public SelfLiveRoot apply(@jakarta.annotation.Nullable SelfLiveRoot previous) { return value; }
    }

    public record SetSelfLiveChild(String id, SelfLiveChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public SelfLiveChild apply(@jakarta.annotation.Nullable SelfLiveChild previous) { return value; }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            searchSettings = @SearchSettings(includeDescendants = false),
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC))
    public record SelfAsyncRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record SelfAsyncChild(@EntityId String id, @Parent(value = SelfAsyncRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {}

    public record SetSelfAsyncRoot(String id, SelfAsyncRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public SelfAsyncRoot apply(@jakarta.annotation.Nullable SelfAsyncRoot previous) { return value; }
    }

    public record SetSelfAsyncChild(String id, SelfAsyncChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public SelfAsyncChild apply(@jakarta.annotation.Nullable SelfAsyncChild previous) { return value; }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            searchSettings = @SearchSettings(includeDescendants = false),
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    public record SelfAwaitRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT)
    public record SelfAwaitChild(@EntityId String id, @Parent(value = SelfAwaitRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {}

    public record SetSelfAwaitRoot(String id, SelfAwaitRoot value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public SelfAwaitRoot apply(@jakarta.annotation.Nullable SelfAwaitRoot previous) { return value; }
    }

    public record SetSelfAwaitChild(String id, SelfAwaitChild value) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply public SelfAwaitChild apply(@jakarta.annotation.Nullable SelfAwaitChild previous) { return value; }
    }
}
