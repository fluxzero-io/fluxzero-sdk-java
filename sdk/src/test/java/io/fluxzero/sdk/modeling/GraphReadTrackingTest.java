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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelRelationshipRead;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.fluxzero.common.api.modeling.ModelRelationshipRead.Direction.CHILDREN;
import static io.fluxzero.common.api.modeling.ModelRelationshipRead.Direction.PARENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphReadTrackingTest {
    private final ModelRepository repository = mock(ModelRepository.class);
    private final Entity<Node> parent = entity("parent", new Node("parent", "root"));
    private final Entity<Node> child = entity("child", new Node("child", "excluded"));
    private final CommitAttempt attempt = CommitAttempt.create(42L,
            new MutationPlan.Resolution(List.of(new MutationPlan.ResolvedModel(
                    "parent", Node.class, MutationPlan.Access.READ_ONLY, List.of("id"))), List.of()),
            Map.of("parent", parent));

    @Test
    void emptyPathReadAndPlainValueReadHaveDifferentDependencies() {
        Graph<Node> graph = graph();
        read(() -> graph.children("empty", Node.class));
        assertEquals(List.of(new ModelRelationshipRead("parent", CHILDREN, "empty")), relationships());
        assertTrue(attempt.readRelationships(ModelConflictPolicy.ACCEPT).isEmpty());
        attempt.resetGraphReads();
        Graph<Node> next = graph();
        read(next::get);
        assertTrue(relationships().isEmpty());
        assertEquals(List.of("parent"), values());
    }

    @Test
    void shortCircuitScansProtectExaminedValuesAndMembershipEvenWhenNoValueMatches() {
        Graph<Node> graph = graph();
        assertTrue(read(() -> graph.find(candidate -> candidate.get() instanceof Node node
                && node.label().equals("missing"))).isEmpty());
        assertTrue(relationships().contains(new ModelRelationshipRead("parent", CHILDREN, null)));
        assertTrue(relationships().contains(new ModelRelationshipRead("child", CHILDREN, null)));
        assertEquals(Set.of("parent", "child"), Set.copyOf(values()));
    }

    @Test
    void remappedPathsProtectPersistedMembershipRatherThanTheViewName() {
        Graph<Node> graph = Graphs.remapPaths(graph(), Map.of("children", "renamed"));
        assertEquals(1, read(() -> graph.children("renamed", Node.class)).size());
        assertTrue(relationships().contains(new ModelRelationshipRead("parent", CHILDREN, null)));
        assertFalse(relationships().contains(new ModelRelationshipRead("parent", CHILDREN, "renamed")));
    }

    @Test
    void cachedFilterReadsEnterTheApplySetWithoutRerunningPredicates() {
        Graph<Node> graph = graph();
        AtomicInteger calls = new AtomicInteger();
        Graph<Node> filtered = read(() -> Graphs.filterBranches(graph, node -> {
            calls.incrementAndGet();
            return false;
        }));
        int evaluated = calls.get();
        assertTrue(attempt.readRelationships(ModelConflictPolicy.ACCEPT).isEmpty());
        attempt.collectReads(new HashSet<>());
        read(filtered::children);
        assertEquals(evaluated, calls.get());
        assertTrue(attempt.readRelationships(ModelConflictPolicy.ACCEPT)
                .contains(new ModelRelationshipRead("child", CHILDREN, null)));
        values();
        assertTrue(attempt.readModelIds(ModelConflictPolicy.ACCEPT).contains("child"));
    }

    @Test
    void cachedMapperReadsEnterTheApplySetWithoutRerunningTheMapper() {
        Graph<Node> graph = graph();
        AtomicInteger calls = new AtomicInteger();
        Graph<Node> mapped = Graphs.mapValues(graph, node -> {
            calls.incrementAndGet();
            graph.children("children", Node.class).getFirst().get();
            return node.get();
        });
        read(mapped::get);
        attempt.collectReads(new HashSet<>());
        read(mapped::get);
        assertEquals(1, calls.get());
        assertTrue(attempt.readRelationships(ModelConflictPolicy.ACCEPT)
                .contains(new ModelRelationshipRead("parent", CHILDREN, "children")));
        values();
        assertTrue(attempt.readModelIds(ModelConflictPolicy.ACCEPT).contains("child"));
    }

    @Test
    void parentAndPlacedAncestorReadsProtectTheExaminedEdges() {
        Graph<Node> graph = graph();
        Graph<?> nested = read(() -> graph.children().getFirst());
        read(nested::root);
        assertTrue(relationships().contains(new ModelRelationshipRead("child", PARENTS, null)));
    }

    @Test
    void joinedParallelMapperReadsRemainInItsCachedProof() {
        Graph<Node> graph = graph();
        Graph<Node> mapped = Graphs.mapValues(graph, node -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> graph.children("children", Node.class)
                    .parallelStream().forEach(Graph::get)).join();
            return node.get();
        });
        read(mapped::get);
        attempt.collectReads(new HashSet<>());
        read(mapped::get);
        assertTrue(attempt.readRelationships(ModelConflictPolicy.ACCEPT)
                .contains(new ModelRelationshipRead("parent", CHILDREN, "children")));
        values();
        assertTrue(attempt.readModelIds(ModelConflictPolicy.ACCEPT).contains("child"));
    }

    @Test
    void foreignRepositoriesAndPreviousAttemptsDoNotContaminateTheCurrentReadSet() {
        Graph<Node> previous = graph();
        attempt.resetGraphReads();
        read(previous::children);
        assertTrue(relationships().isEmpty());
        ModelRepository foreign = mock(ModelRepository.class);
        Graph<Node> foreignGraph = Graphs.compose("parent", 42L, Map.of("parent", parent), List.of(), foreign, true);
        read(foreignGraph::children);
        assertTrue(relationships().isEmpty());
        read(() -> graph().children());
        assertFalse(relationships().isEmpty());
    }

    @Test
    void explicitHistoryAtTheSameNumericBoundaryIsNotALiveRead() {
        Graph<Node> graph = graph();
        Graph<Node> historical = Graphs.compose("parent", 42L, Map.of("parent", parent), List.of(), repository, true);
        when(repository.loadGraphAt("parent", Node.class, 42L, Graph.Options.DEFAULT)).thenReturn(historical);
        read(() -> graph.atStateIndex(42L).children());
        assertTrue(relationships().isEmpty());
        assertTrue(values().isEmpty());
    }

    @Test
    void mappedHistoricalSourcesAndParallelScansDoNotBecomeLiveDependencies() {
        Graph<Node> graph = graph();
        Graph<Node> historical = Graphs.compose("parent", 42L, Map.of("parent", parent), List.of(), repository, true);
        when(repository.loadGraphAt("parent", Node.class, 42L, Graph.Options.DEFAULT)).thenReturn(historical);
        Graph<Node> mapped = Graphs.mapValues(Graphs.mapValues(graph, source -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> source.children().parallelStream().forEach(Graph::get)).join();
            return source.get();
        }), Graph::get);
        read(() -> mapped.atStateIndex(42L).get());
        assertTrue(relationships().isEmpty());
        assertTrue(values().isEmpty());
        assertTrue(read(() -> graph.context(Object.class)).isEmpty(), "Internal provenance is not application context");
    }

    @Test
    void redecoratingHistoricalFiltersDoesNotCaptureLiveReads() {
        Graph<Node> filtered = read(() -> Graphs.filterBranches(graph(), source -> source.get() != null));
        Graph<Node> historical = Graphs.compose("parent", 42L, Map.of("parent", parent), List.of(), repository, true);
        when(repository.loadGraphAt("parent", Node.class, 42L, Graph.Options.DEFAULT)).thenReturn(historical);
        List<ModelRelationshipRead> before = relationships();
        Set<String> beforeValues = Set.copyOf(values());
        attempt.collectReads(new HashSet<>());
        read(() -> filtered.atStateIndex(42L).get());
        assertEquals(before, relationships());
        assertEquals(beforeValues, Set.copyOf(values()));
        assertTrue(attempt.readRelationships(ModelConflictPolicy.ACCEPT).isEmpty());
        assertTrue(attempt.readModelIds(ModelConflictPolicy.ACCEPT).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordinaryCustomGraphChildrenRemainLazyAndTransactionalNavigationFailsClosed() {
        Graph<Node> custom = mock(Graph.class);
        when(custom.children()).thenReturn(List.of());
        when(repository.load("parent", Node.class)).thenReturn(parent);
        when(repository.load((Object) "parent", Node.class)).thenReturn(parent);
        when(repository.loadGraph(eq("parent"), eq(Node.class), any(ModelReadBoundary.class), eq(Graph.Options.DEFAULT)))
                .thenReturn(custom);
        assertTrue(Graphs.lazy("parent", Node.class, repository).children().isEmpty());
        verify(custom, never()).stream();

        graph(); // Bind the evaluation owner, but do not materialize a custom repository graph.
        when(repository.loadGraphAt("parent", Node.class, 42L, Graph.Options.DEFAULT)).thenReturn(custom);
        Graph<Node> injected = Graphs.lazy(parent, attempt, repository);
        assertThrows(UnsupportedOperationException.class, () -> read(injected::children));
        verify(custom, never()).stream();
    }

    private Graph<Node> graph() {
        attempt.bindGraphReads(attempt);
        return attempt.trackGraph(Graphs.compose("parent", 42L, Map.of("parent", parent, "child", child),
                List.of(new ModelGraphEdge("child", "parent", Node.class.getName(), "children", 0L, null, false)),
                repository, true), repository);
    }

    private <T> T read(Supplier<T> action) {
        return CommitAttempt.withGraphReads(attempt, action);
    }

    private List<ModelRelationshipRead> relationships() {
        return attempt.readRelationships(ModelConflictPolicy.RETRY);
    }

    private List<String> values() {
        attempt.evaluated(42L, List.of(), Map.of(), List.of());
        return attempt.readModelIds();
    }

    private static Entity<Node> entity(String id, Node value) {
        return ImmutableEntity.<Node>builder().id(id).type(Node.class).value(value).build();
    }

    @Model
    record Node(@EntityId String id, String label) {
    }
}
