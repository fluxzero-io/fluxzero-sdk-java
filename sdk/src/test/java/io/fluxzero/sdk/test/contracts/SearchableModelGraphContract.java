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
public abstract class SearchableModelGraphContract {
    protected abstract Client client(String namespace);

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
                    var before = fc.client().getSearchClient().fetch(query).orElseThrow();
                    set(rootType.getDeclaredConstructor(String.class, String.class, int.class).newInstance("one", "renamed", 1));
                    take(observer.roots, g -> g.id().equals("one") && g.get().toString().contains("renamed"));
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
        Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT)
                .build(client("search-contract-" + UUID.randomUUID()));
        app.apply(fc -> {
            Class<?>[] types = types(mode, selfScope);
            set(types[0].getDeclaredConstructor(String.class, String.class, int.class).newInstance("one", "alpha", 1));
            set(types[0].getDeclaredConstructor(String.class, String.class, int.class).newInstance("two", "beta", 2));
            for (String[] child : List.of(new String[]{"a", "one", "open", "red"},
                    new String[]{"b", "one", "closed", "blue"}, new String[]{"c", "two", "closed", "green"})) {
                set(types[1].getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class)
                        .newInstance(child[0], child[1], child[2], child[3], "hiddenvalue"));
            }
            return null;
        });
        return app;
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
                         @Facet String status, String label, @SearchExclude String secret) {}

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC,
                    pathOverrides = @GraphPathOverride(path = "notes", projectionPath = "annotations")))
    public record AsyncRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC))
    public record AsyncChild(@EntityId String id, @Parent(value = AsyncRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {}

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT,
                    pathOverrides = @GraphPathOverride(path = "notes", projectionPath = "annotations")))
    public record AwaitRoot(@EntityId String id, @Facet String label, @Sortable int rank) {}

    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    public record AwaitChild(@EntityId String id, @Parent(value = AwaitRoot.class, pathInParent = "children") String rootId,
                         @Facet String status, String label, @SearchExclude String secret) {}

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
