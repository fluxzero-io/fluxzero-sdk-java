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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelReadBoundary;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static io.fluxzero.sdk.modeling.MutationPlan.Access.READ_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GraphTest {

    @Test
    void valueAndHistoryOperationsDoNotMaterializeRelationships() {
        ModelRepository repository = mock(ModelRepository.class);
        @SuppressWarnings("unchecked")
        Entity<Root> current = mock(Entity.class);
        @SuppressWarnings("unchecked")
        Entity<Root> previous = mock(Entity.class);
        @SuppressWarnings("unchecked")
        Entity<Root> updated = mock(Entity.class);
        Root before = new Root(new RootId("one"), "before");
        Root earlier = new Root(new RootId("one"), "earlier");
        Root after = new Root(new RootId("one"), "after");
        Object update = new Object();
        when(current.id()).thenReturn(before.id().toString());
        when(current.type()).thenReturn(Root.class);
        when(current.get()).thenReturn(before);
        when(current.previous()).thenReturn(previous);
        when(current.playBackToEvent(10L, null)).thenReturn(Optional.of(previous));
        when(current.apply(update)).thenReturn(updated);
        when(previous.id()).thenReturn(earlier.id().toString());
        when(previous.type()).thenReturn(Root.class);
        when(previous.get()).thenReturn(earlier);
        when(updated.id()).thenReturn(after.id().toString());
        when(updated.type()).thenReturn(Root.class);
        when(updated.get()).thenReturn(after);

        Graph<Root> graph = Graphs.lazy(current, 42L, repository);

        assertEquals(before, graph.get());
        assertEquals(earlier, graph.previous().get());
        assertEquals(earlier, graph.playBackToEvent(10L, null).orElseThrow().get());
        assertEquals(after, graph.apply(update).get());
        assertTrue(graph.hasChanged(Root::name));
        assertEquals("earlier", graph.previousValue(Root::name));
        verifyNoInteractions(repository);
    }

    @Test
    void optionalAndRevisionConveniencesDoNotMaterializeRelationships() {
        ModelRepository repository = mock(ModelRepository.class);
        Root value = new Root(new RootId("convenience"), "current");
        Root previousValue = new Root(value.id(), "previous");
        Entity<Root> current = entity(value.id().toString(), Root.class, value);
        Entity<Root> previous = entity(previousValue.id().toString(), Root.class, previousValue);
        when(current.previous()).thenReturn(previous);
        when(current.lastEventIndex()).thenReturn(null);
        when(previous.lastEventIndex()).thenReturn(91L);

        Graph<Root> graph = Graphs.lazy(current, 42L, repository);

        assertEquals(Optional.of(value), graph.optional());
        assertEquals(Optional.of(graph), graph.mapGraph(currentGraph -> currentGraph));
        assertEquals(Optional.of(graph), graph.filterGraph(currentGraph -> currentGraph.type() == Root.class));
        assertEquals(Optional.of(graph), graph.filterPresent());
        assertEquals(Optional.of("current"), graph.mapIfPresent(currentGraph -> currentGraph.get().name()));
        assertEquals(Optional.of("current"), graph.map(Root::name));
        assertSame(value, graph.orElse(new Root(new RootId("fallback"), "fallback")));
        assertSame(value, graph.orElseGet(() -> new Root(new RootId("fallback"), "fallback")));
        assertSame(value, graph.orElseThrow());
        assertSame(graph, graph.ifPresent(currentGraph -> currentGraph));
        assertEquals(91L, graph.highestEventIndex());
        verifyNoInteractions(repository);
    }

    @Test
    void emptyGraphConveniencesPreserveWrapperSemanticsWithoutLoadingRelationships() {
        ModelRepository repository = mock(ModelRepository.class);
        Entity<Root> missing = entity("graph-root-missing", Root.class, null);
        Graph<Root> graph = Graphs.lazy(missing, 42L, repository);
        Root fallback = new Root(new RootId("fallback"), "fallback");
        AtomicBoolean invoked = new AtomicBoolean();

        assertEquals(Optional.empty(), graph.optional());
        assertEquals(Optional.of(graph), graph.mapGraph(currentGraph -> currentGraph));
        assertEquals(Optional.of(graph), graph.filterGraph(currentGraph -> currentGraph.isEmpty()));
        assertEquals(Optional.empty(), graph.filterPresent());
        assertEquals(Optional.empty(), graph.mapIfPresent(currentGraph -> {
            invoked.set(true);
            return currentGraph;
        }));
        assertEquals(Optional.empty(), graph.map(Root::name));
        assertSame(fallback, graph.orElse(fallback));
        assertSame(fallback, graph.orElseGet(() -> fallback));
        assertThrows(NoSuchElementException.class, graph::orElseThrow);
        assertThrows(IllegalStateException.class,
                     () -> graph.orElseThrow(() -> new IllegalStateException("missing")));
        assertSame(graph, graph.ifPresent(currentGraph -> {
            invoked.set(true);
            return currentGraph;
        }));
        assertFalse(invoked.get());
        verifyNoInteractions(repository);
    }

    @Test
    void relationshipNavigationMaterializesOnceAndRemainsTyped() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("root"), "root");
        Child childValue = new Child(new ChildId("child"), rootValue.id(), "child");
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue);
        Entity<Child> child = entity(childValue.id().toString(), Child.class, childValue);
        Graph<Root> complete = Graphs.compose(
                rootValue.id().toString(), 7L,
                Map.of(rootValue.id().toString(), root, childValue.id().toString(), child),
                List.of(new ModelGraphEdge(
                        childValue.id().toString(), rootValue.id().toString(),
                        Root.class.getName(), "children", 0L, null)),
                repository, false);
        when(repository.loadGraph(
                rootValue.id().toString(), Root.class, Graph.Options.DEFAULT))
                .thenReturn(complete);

        Graph<Root> graph = Graphs.lazy(root, 7L, repository);

        verifyNoInteractions(repository);
        assertEquals(List.of(childValue), graph.childModels("children", Child.class));
        assertEquals(List.of(childValue), graph.childModels("children", Child.class));
        Graph<Child> childGraph = graph.children("children", Child.class).getFirst();
        assertTrue(graph.isRoot());
        assertFalse(childGraph.isRoot());
        assertEquals(Optional.of(rootValue), childGraph.parentModel(Root.class));
        assertEquals(Optional.of(rootValue), childGraph.ancestorModel(Root.class));
        assertSame(childGraph.root().get(), graph.get());
        verify(repository).loadGraph(
                rootValue.id().toString(), Root.class, Graph.Options.DEFAULT);
    }

    @Test
    void genericTraversalAndLookupMaterializeOnceAndPreferPrimaryIdentity() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("lookup"), "root");
        Child childValue = new Child(new ChildId("primary"), rootValue.id(), "child");
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue, List.of("shared"));
        Entity<Child> child = entity(childValue.id().toString(), Child.class, childValue,
                                     List.of("shared", "child-alias"));
        Graph<Root> complete = Graphs.compose(
                rootValue.id().toString(), 7L,
                Map.of(rootValue.id().toString(), root, childValue.id().toString(), child),
                List.of(new ModelGraphEdge(
                        childValue.id().toString(), rootValue.id().toString(),
                        Root.class.getName(), "children", 0L, null)),
                repository, false);
        when(repository.loadGraph(rootValue.id().toString(), Root.class, Graph.Options.DEFAULT))
                .thenReturn(complete);
        Graph<Root> graph = Graphs.lazy(root, 7L, repository);

        graph.stream();
        assertEquals(rootValue, graph.find(candidate -> candidate == graph).orElseThrow().get());
        verifyNoInteractions(repository);
        assertEquals(List.of(rootValue, childValue), graph.stream().map(Graph::get).toList());
        assertEquals(rootValue, graph.find("shared").orElseThrow().get());
        assertEquals(childValue, graph.find("child-alias").orElseThrow().get());
        assertEquals(childValue, graph.find(childValue.id().toString()).orElseThrow().get());
        assertEquals(rootValue, graph.find("lookup", Root.class).orElseThrow().get());
        assertEquals(childValue, graph.find("primary", Child.class).orElseThrow().get());
        assertEquals(childValue, graph.find(candidate -> candidate.type() == Child.class).orElseThrow().get());
        verify(repository).loadGraph(rootValue.id().toString(), Root.class, Graph.Options.DEFAULT);
    }

    @Test
    void lookupTreatsNullAliasesFromLegacyEntityImplementationsAsEmpty() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("null-aliases"), "root");
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue, null);
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 7L,
                Map.of(rootValue.id().toString(), root), List.of(), repository, false);

        assertEquals(rootValue, graph.find("null-aliases", Root.class).orElseThrow().get());
        assertTrue(graph.find("missing").isEmpty());
    }

    @Test
    void typedPrimaryIdentityLookupDoesNotMaterializeOrdinaryModelValues() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("identity-only"), "root");
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue, List.of("alias"));
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 7L,
                Map.of(rootValue.id().toString(), root), List.of(), repository, false);

        assertSame(graph, graph.find(rootValue.id(), Root.class).orElseThrow());
        verify(root, never()).get();
        verify(root, never()).aliases();
    }

    @Test
    void parentScopedLookupSelectsTheMostRecentRevisionOrFailsOnAmbiguity() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("scoped-lookup"), "root");
        Child olderParent = new Child(
                new ChildId("older-parent"), rootValue.id(), "older");
        Child newerParent = new Child(
                new ChildId("newer-parent"), rootValue.id(), "newer");
        ScopedLeaf older = new ScopedLeaf("shared", olderParent.id());
        ScopedLeaf newer = new ScopedLeaf("shared", newerParent.id());
        String olderId = EntityMetadata.of(ScopedLeaf.class)
                .repositoryId("shared", olderParent.id(), Child.class);
        String newerId = EntityMetadata.of(ScopedLeaf.class)
                .repositoryId("shared", newerParent.id(), Child.class);
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 21L,
                Map.of(
                        rootValue.id().toString(), entity(
                                rootValue.id().toString(), Root.class, rootValue),
                        olderParent.id().toString(), entity(
                                olderParent.id().toString(), Child.class, olderParent),
                        newerParent.id().toString(), entity(
                                newerParent.id().toString(), Child.class, newerParent),
                        olderId, modelEntity(
                                olderId, ScopedLeaf.class, older, 12L,
                                Instant.parse("2026-08-07T10:00:00Z")),
                        newerId, modelEntity(
                                newerId, ScopedLeaf.class, newer, 20L,
                                Instant.parse("2026-08-07T11:00:00Z"))),
                List.of(
                        new ModelGraphEdge(
                                olderParent.id().toString(), rootValue.id().toString(),
                                Root.class.getName(), "children", 0L, null),
                        new ModelGraphEdge(
                                newerParent.id().toString(), rootValue.id().toString(),
                                Root.class.getName(), "children", 0L, null),
                        new ModelGraphEdge(
                                olderId, olderParent.id().toString(),
                                Child.class.getName(), "scopedLeaves", 0L, null),
                        new ModelGraphEdge(
                                newerId, newerParent.id().toString(),
                                Child.class.getName(), "scopedLeaves", 0L, null)),
                repository, false);

        Graph<ScopedLeaf> selected = graph.find(
                "shared", ScopedLeaf.class).orElseThrow();

        assertEquals(newerId, selected.id());
        assertEquals(20L, selected.revisionStateIndex());
        assertThrows(
                IllegalStateException.class,
                () -> graph.find(
                        "shared", ScopedLeaf.class,
                        GraphLookupPolicy.FAIL_ON_AMBIGUITY));
        verifyNoInteractions(repository);
    }

    @Test
    void relationshipExpansionRetainsAStagedModelValue() {
        ModelRepository repository = mock(ModelRepository.class);
        Root before = new Root(new RootId("staged"), "before");
        Root after = new Root(before.id(), "after");
        Child childValue = new Child(new ChildId("staged"), before.id(), "child");
        Entity<Root> root = entity(before.id().toString(), Root.class, before);
        Entity<Root> updated = entity(after.id().toString(), Root.class, after);
        Entity<Child> child = entity(childValue.id().toString(), Child.class, childValue);
        Object update = new Object();
        when(root.apply(update)).thenReturn(updated);
        Graph<Root> durable = Graphs.compose(
                before.id().toString(), 7L,
                Map.of(before.id().toString(), root, childValue.id().toString(), child),
                List.of(new ModelGraphEdge(
                        childValue.id().toString(), before.id().toString(),
                        Root.class.getName(), "children", 0L, null)),
                repository, false);
        when(repository.loadGraph(
                before.id().toString(), Root.class, Graph.Options.DEFAULT))
                .thenReturn(durable);

        Graph<Root> staged = Graphs.lazy(root, 7L, repository).apply(update);
        Graph<Child> childGraph = staged.children("children", Child.class).getFirst();

        assertEquals(after, staged.get());
        assertEquals(after, childGraph.root().get());
        assertEquals(childValue, childGraph.get());
    }

    @Test
    void selectsDescendantsByComposedRelationshipPath() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("path"), "root");
        Child childValue = new Child(new ChildId("path"), rootValue.id(), "child");
        Grandchild grandchildValue = new Grandchild("grandchild", childValue.id());
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue);
        Entity<Child> child = entity(childValue.id().toString(), Child.class, childValue);
        Entity<Grandchild> grandchild = entity(grandchildValue.id(), Grandchild.class, grandchildValue);
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 7L,
                Map.of(rootValue.id().toString(), root,
                       childValue.id().toString(), child,
                       grandchildValue.id(), grandchild),
                List.of(
                        new ModelGraphEdge(
                                childValue.id().toString(), rootValue.id().toString(),
                                Root.class.getName(), "children", 0L, null),
                        new ModelGraphEdge(
                                grandchildValue.id(), childValue.id().toString(),
                                Child.class.getName(), "details/grandchildren", 0L, null)),
                repository, false);

        assertEquals(
                List.of(grandchildValue),
                graph.descendantModels("children/details/grandchildren", Grandchild.class));
        assertEquals(List.of(), graph.descendantModels("details/grandchildren", Grandchild.class));

        Graph<Root> changed = Graphs.withPrevious(graph, graph);
        assertEquals(
                List.of(grandchildValue),
                changed.descendantModels(
                        "children/details/grandchildren",
                        Grandchild.class));
        assertSame(graph, changed.previous());
    }

    @Test
    void retainsPathlessRelationshipsForGeneralNavigation() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("pathless"), "root");
        Child childValue = new Child(new ChildId("pathless"), rootValue.id(), "child");
        Grandchild grandchildValue = new Grandchild("pathless-leaf", childValue.id());
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 7L,
                Map.of(rootValue.id().toString(), entity(rootValue.id(), Root.class, rootValue),
                       childValue.id().toString(), entity(childValue.id(), Child.class, childValue),
                       grandchildValue.id(), entity(grandchildValue.id(), Grandchild.class, grandchildValue)),
                List.of(
                        new ModelGraphEdge(
                                childValue.id().toString(), rootValue.id().toString(),
                                Root.class.getName(), null, 0L, null),
                        new ModelGraphEdge(
                                grandchildValue.id(), childValue.id().toString(),
                                Child.class.getName(), null, 0L, null)),
                repository, false);

        assertEquals(List.of(childValue), graph.childModels(Child.class));
        assertEquals(List.of(childValue, grandchildValue), graph.descendantModels(Object.class));
        assertEquals(List.of(), graph.descendantModels("children", Child.class));
        assertNull(graph.children(Child.class).getFirst().relationshipPath());
    }

    @Test
    void parentNavigationReusesTheAlreadyLoadedCommitContext() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("context"), "root");
        Child childValue = new Child(new ChildId("context"), rootValue.id(), "child");
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue);
        Entity<Child> child = entity(childValue.id().toString(), Child.class, childValue);
        MutationPlan.ResolvedModel rootTarget = new MutationPlan.ResolvedModel(
                rootValue.id().toString(), Root.class, READ_ONLY, List.of("id"));
        MutationPlan.ResolvedModel childTarget = new MutationPlan.ResolvedModel(
                childValue.id().toString(), Child.class, READ_ONLY, List.of("id"));
        CommitAttempt commitContext = CommitAttempt.create(
                11L,
                new MutationPlan.Resolution(
                        List.of(rootTarget, childTarget), List.of(), List.of()),
                Map.of(rootValue.id().toString(), root, childValue.id().toString(), child));

        Graph<Child> graph = Graphs.lazy(child, commitContext, repository);

        Graph<Root> parent = graph.parent(Root.class).orElseThrow();
        assertEquals(rootValue, parent.get());
        assertEquals(rootValue, graph.root().get());
        assertFalse(graph.parent(Child.class).isPresent());
        verify(repository, never()).load(rootValue.id(), Root.class);
        verifyNoInteractions(repository);
    }

    @Test
    void parentNavigationOutsideTheLoadedCommitContextUsesItsExactBoundary() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("boundary"), "root");
        Child childValue = new Child(new ChildId("boundary"), rootValue.id(), "child");
        Entity<Child> child = entity(
                childValue.id().toString(), Child.class, childValue);
        @SuppressWarnings("unchecked")
        Graph<Root> parent = mock(Graph.class);
        when(parent.isPresent()).thenReturn(true);
        when(parent.type()).thenReturn(Root.class);
        MutationPlan.ResolvedModel childTarget =
                new MutationPlan.ResolvedModel(
                        childValue.id().toString(), Child.class,
                        READ_ONLY, List.of("id"));
        CommitAttempt commitContext = CommitAttempt.create(
                11L,
                new MutationPlan.Resolution(
                        List.of(childTarget), List.of(), List.of()),
                Map.of(childValue.id().toString(), child));
        when(repository.loadGraphAt(
                rootValue.id().toString(), Root.class,
                11L, Graph.Options.DEFAULT)).thenReturn(parent);

        Graph<Child> graph = Graphs.lazy(child, commitContext, repository);

        assertSame(parent, graph.parent(Root.class).orElseThrow());
        verify(repository).loadGraphAt(
                rootValue.id().toString(), Root.class,
                11L, Graph.Options.DEFAULT);
        verify(repository, never()).load(rootValue.id(), Root.class);
    }

    @Test
    void typedAncestorNavigationDisambiguatesMultipleParentBranches() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("multi"), "root");
        OtherRoot otherValue = new OtherRoot("other", "other");
        MultiChild childValue = new MultiChild("multi-child", rootValue.id(), otherValue.id());
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue);
        Entity<OtherRoot> other = entity(otherValue.id(), OtherRoot.class, otherValue);
        Entity<MultiChild> child = entity(childValue.id(), MultiChild.class, childValue);
        CommitAttempt context = CommitAttempt.create(
                12L,
                new MutationPlan.Resolution(
                        List.of(
                                new MutationPlan.ResolvedModel(
                                        rootValue.id().toString(), Root.class, READ_ONLY, List.of("id")),
                                new MutationPlan.ResolvedModel(
                                        otherValue.id(), OtherRoot.class, READ_ONLY, List.of("id")),
                                new MutationPlan.ResolvedModel(
                                        childValue.id(), MultiChild.class, READ_ONLY, List.of("id"))),
                        List.of(), List.of()),
                Map.of(rootValue.id().toString(), root, otherValue.id(), other, childValue.id(), child));

        Graph<MultiChild> graph = Graphs.lazy(child, context, repository);

        assertEquals(rootValue, graph.ancestor(Root.class).orElseThrow().get());
        assertEquals(otherValue, graph.ancestor(OtherRoot.class).orElseThrow().get());
        assertEquals(List.of(rootValue, otherValue), graph.parents().stream().map(Graph::get).toList());
        assertThrows(IllegalStateException.class, graph::parent);
        verifyNoInteractions(repository);
    }

    @Test
    void placedGraphRetainsEveryDirectParent() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("placed-multi"), "root");
        OtherRoot otherValue = new OtherRoot("placed-other", "other");
        MultiChild childValue = new MultiChild("placed-child", rootValue.id(), otherValue.id());
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 12L,
                Map.of(rootValue.id().toString(), entity(rootValue.id(), Root.class, rootValue),
                       otherValue.id(), entity(otherValue.id(), OtherRoot.class, otherValue),
                       childValue.id(), entity(childValue.id(), MultiChild.class, childValue)),
                List.of(new ModelGraphEdge(
                        childValue.id(), rootValue.id().toString(),
                        Root.class.getName(), "rootChildren", 0L, null)),
                repository, false);

        Graph<MultiChild> child = graph.children(MultiChild.class).getFirst();

        assertEquals(rootValue, child.parent().orElseThrow().get());
        assertEquals(rootValue, child.parent(Root.class).orElseThrow().get());
        assertEquals(otherValue, child.parent(OtherRoot.class).orElseThrow().get());
        assertEquals(List.of(rootValue, otherValue), child.parents().stream().map(Graph::get).toList());
        verifyNoInteractions(repository);
    }

    @Test
    void detachedAncestorNavigationUsesIdentityResolverWithoutLoadingIntermediateValues() {
        @SuppressWarnings("unchecked")
        Entity<Grandchild> source = mock(Entity.class);
        @SuppressWarnings("unchecked")
        Graph<Root> ancestor = mock(Graph.class);
        when(source.id()).thenReturn("grandchild");
        when(source.type()).thenReturn(Grandchild.class);
        AncestorRepository repository = new AncestorRepository(ancestor);

        Graph<Grandchild> graph = Graphs.lazy(source, 42L, repository);

        assertSame(ancestor, graph.ancestor(Root.class).orElseThrow());
        assertEquals("grandchild", repository.modelId);
        assertEquals(Grandchild.class, repository.modelType);
        assertEquals(Root.class, repository.ancestorType);
        assertNull(repository.boundary.stateIndex());
        assertTrue(repository.boundary.includeMessageBatch());
        verify(source, never()).get();
    }

    @Test
    void identityOnlyGraphResolvesAnAncestorWithoutLoadingItsSourceModel() {
        @SuppressWarnings("unchecked")
        Graph<Root> ancestor = mock(Graph.class);
        AncestorRepository repository = new AncestorRepository(ancestor);

        Graph<Grandchild> graph = Graphs.lazy("grandchild", Grandchild.class, repository);

        assertSame(ancestor, graph.ancestor(Root.class).orElseThrow());
        assertEquals("grandchild", repository.modelId);
        assertEquals(Grandchild.class, repository.modelType);
        assertEquals(Root.class, repository.ancestorType);
        assertNull(repository.boundary.stateIndex());
        assertTrue(repository.boundary.includeMessageBatch());
        assertFalse(repository.sourceLoaded);
    }

    @Test
    void parentScopedIdentityGraphDoesNotLoadItsSourceToResolveThePrimaryId() {
        ModelRepository repository = mock(ModelRepository.class);
        RootId rootId = new RootId("scoped");
        String primaryId = EntityMetadata.of(ScopedChild.class)
                .repositoryId("child", rootId, Root.class);
        ScopedChild value = new ScopedChild("child", rootId);
        Entity<ScopedChild> entity = entity(primaryId, ScopedChild.class, value);
        when(repository.load(primaryId, ScopedChild.class))
                .thenReturn(entity);

        Graph<ScopedChild> graph = Graphs.lazy(
                rootId, Root.class, "child", ScopedChild.class, repository);

        assertEquals(primaryId, graph.id());
        verifyNoInteractions(repository);
        assertSame(value, graph.get());
        verify(repository).load(primaryId, ScopedChild.class);
    }

    @Test
    void identityOnlyGraphFallsBackToModelRelationshipsWhenTheIdentityIndexLags() {
        Root rootValue = new Root(new RootId("fallback"), "root");
        Child childValue = new Child(new ChildId("fallback"), rootValue.id(), "child");
        Grandchild sourceValue = new Grandchild("fallback", childValue.id());
        FallbackAncestorRepository repository = new FallbackAncestorRepository(Map.of(
                sourceValue.id(), entity(sourceValue.id(), Grandchild.class, sourceValue),
                childValue.id().toString(), entity(childValue.id().toString(), Child.class, childValue),
                rootValue.id().toString(), entity(rootValue.id().toString(), Root.class, rootValue)));

        Graph<Grandchild> graph = Graphs.lazy(sourceValue.id(), Grandchild.class, repository);

        assertEquals(rootValue, graph.ancestorModel(Root.class).orElseThrow());
        assertTrue(repository.sourceLoaded);
        assertEquals(2, repository.identityLookups);
    }

    @Test
    void identityOnlyGraphFallsBackToAliasLoadingForAncestorNavigation() {
        Root rootValue = new Root(new RootId("alias-root"), "root");
        AliasedChild sourceValue = new AliasedChild(
                "actual-child", "child-alias", rootValue.id());
        @SuppressWarnings("unchecked")
        Graph<Root> ancestor = mock(Graph.class);
        AliasAncestorRepository repository = new AliasAncestorRepository(
                entity(sourceValue.id(), AliasedChild.class, sourceValue), ancestor);

        Graph<AliasedChild> graph = Graphs.lazy(
                sourceValue.alias(), AliasedChild.class, repository);

        assertEquals(sourceValue.id(), graph.id());
        assertSame(ancestor, graph.ancestor(Root.class).orElseThrow());
        assertTrue(repository.sourceLoaded);
        assertEquals(List.of("child-alias", "actual-child"), repository.identityLookups);
    }

    @Test
    void commitContextAncestorNavigationRetainsItsExactBoundary() {
        @SuppressWarnings("unchecked")
        Entity<Grandchild> source = mock(Entity.class);
        @SuppressWarnings("unchecked")
        Graph<Root> ancestor = mock(Graph.class);
        when(source.id()).thenReturn("grandchild");
        when(source.type()).thenReturn(Grandchild.class);
        MutationPlan.ResolvedModel target =
                new MutationPlan.ResolvedModel(
                        "grandchild", Grandchild.class,
                        READ_ONLY, List.of("id"));
        CommitAttempt context = CommitAttempt.create(
                42L,
                new MutationPlan.Resolution(
                        List.of(target), List.of(), List.of()),
                Map.of("grandchild", source));
        AncestorRepository repository = new AncestorRepository(ancestor);

        Graph<Grandchild> graph = Graphs.lazy(source, context, repository);

        assertSame(ancestor, graph.ancestor(Root.class).orElseThrow());
        assertEquals(42L, repository.boundary.stateIndex());
        assertTrue(repository.boundary.includeMessageBatch());
        verify(source, never()).get();
    }

    @Test
    void modelEventAncestorNavigationRetainsItsExactCommitSubstep() {
        @SuppressWarnings("unchecked")
        Entity<Grandchild> source = mock(Entity.class);
        @SuppressWarnings("unchecked")
        Graph<Root> ancestor = mock(Graph.class);
        when(source.id()).thenReturn("grandchild");
        when(source.type()).thenReturn(Grandchild.class);
        MutationPlan.ResolvedModel target =
                new MutationPlan.ResolvedModel(
                        "grandchild", Grandchild.class,
                        READ_ONLY, List.of("id"));
        CommitAttempt context = CommitAttempt.create(
                42L,
                new MutationPlan.Resolution(
                        List.of(target), List.of(), List.of()),
                Map.of("grandchild", source));
        AncestorRepository repository = new AncestorRepository(ancestor);
        Metadata metadata = Metadata.of(
                ModelEventMetadata.COMMIT_ID, "commit-1",
                ModelEventMetadata.SUBSTEP, 3);
        DeserializingMessage event = new DeserializingMessage(
                new Message(new Object(), metadata),
                MessageType.EVENT, null);

        Graph<Grandchild> graph = event.apply(
                ignored -> Graphs.lazy(source, context, repository));

        assertSame(ancestor, graph.ancestor(Root.class).orElseThrow());
        assertEquals("commit-1", repository.boundary.commitId());
        assertEquals(3, repository.boundary.substep());
        assertFalse(repository.boundary.includeMessageBatch());
        verify(source, never()).get();
    }

    @Test
    void deleteIsTheNullUpdateConvenience() {
        Root value = new Root(new RootId("delete"), "before");
        Entity<Root> current = entity(value.id().toString(), Root.class, value);
        Entity<Root> deleted = entity(value.id().toString(), Root.class, null);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<UnaryOperator<Root>> update =
                org.mockito.ArgumentCaptor.forClass(UnaryOperator.class);
        when(current.update(any())).thenReturn(deleted);

        Graph<Root> result = Graphs.lazy(
                current, 42L, mock(ModelRepository.class)).delete();

        verify(current).update(update.capture());
        assertNull(update.getValue().apply(value));
        assertTrue(result.isEmpty());
    }

    @Test
    void deletingAChildRetainsTheContainingGraphStateBoundary() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("delete-child"), "root");
        Child childValue = new Child(
                new ChildId("delete-child"), rootValue.id(), "child");
        Entity<Root> root = entity(
                rootValue.id().toString(), Root.class, rootValue);
        @SuppressWarnings("unchecked")
        ModelRoot<Child> child = mock(ModelRoot.class);
        @SuppressWarnings("unchecked")
        ModelRoot<Child> deleted = mock(ModelRoot.class);
        when(child.id()).thenReturn(childValue.id().toString());
        when(child.type()).thenReturn(Child.class);
        when(child.get()).thenReturn(childValue);
        when(child.stateIndex()).thenReturn(41L);
        when(child.update(any())).thenReturn(deleted);
        when(deleted.id()).thenReturn(childValue.id().toString());
        when(deleted.type()).thenReturn(Child.class);
        when(deleted.get()).thenReturn(null);
        when(deleted.stateIndex()).thenReturn(41L);
        Graph<Root> graph = Graphs.compose(
                rootValue.id().toString(), 42L,
                Map.of(rootValue.id().toString(), root,
                       childValue.id().toString(), child),
                List.of(new ModelGraphEdge(
                        childValue.id().toString(), rootValue.id().toString(),
                        Root.class.getName(), "children", 0L, null)),
                repository, false);

        Graph<Child> stagedDeletion = graph.children(
                "children", Child.class).getFirst().delete();

        assertTrue(stagedDeletion.isEmpty());
        assertEquals(42L, stagedDeletion.stateIndex());
        assertEquals(
                41L,
                Graphs.stagedChanges(stagedDeletion).getFirst()
                        .expectedStateIndex());
    }

    @Test
    void stagedUpdatesRetainAReplayableOperation() {
        ModelRepository repository = mock(ModelRepository.class);
        RootId id = new RootId("staged-update");
        Graph<Root> graph = Graphs.lazy(
                ImmutableEntity.<Root>builder()
                        .id(id.toString()).type(Root.class)
                        .value(new Root(id, "before")).build(),
                42L, repository);

        Graph<Root> staged = graph
                .update(value -> new Root(value.id(), value.name() + "-one"))
                .update(value -> new Root(value.id(), value.name() + "-two"));
        Change change = Graphs.stagedChanges(staged).getFirst();
        Entity<Root> concurrent = ImmutableEntity.<Root>builder()
                .id(id.toString()).type(Root.class)
                .value(new Root(id, "concurrent")).build();

        assertEquals(new Root(id, "before-one-two"), staged.get());
        assertEquals(42L, change.expectedStateIndex());
        assertEquals(
                new Root(id, "concurrent-one-two"),
                change.replay().apply(concurrent).get());
    }

    @Test
    void committingAStagedGraphClearsItsReplayMetadata() {
        Root value = new Root(new RootId("committed-update"), "before");
        Entity<Root> current = entity(value.id().toString(), Root.class, value);
        Entity<Root> stagedEntity = entity(
                value.id().toString(), Root.class,
                new Root(value.id(), "after"));
        Entity<Root> committedEntity = entity(
                value.id().toString(), Root.class,
                new Root(value.id(), "after"));
        when(current.update(any())).thenReturn(stagedEntity);
        when(stagedEntity.commit()).thenReturn(committedEntity);

        Graph<Root> staged = Graphs.lazy(
                current, 42L, mock(ModelRepository.class))
                .update(ignored -> new Root(value.id(), "after"));
        Graph<Root> committed = staged.commit();

        assertEquals(1, Graphs.stagedChanges(staged).size());
        assertTrue(Graphs.stagedChanges(committed).isEmpty());
        assertEquals(new Root(value.id(), "after"), committed.get());
    }

    @Test
    void missingModelRetainsAnEmptyGraphWithoutExposingEntity() {
        ModelRepository repository = mock(ModelRepository.class);
        Entity<Root> missing = entity("root-missing", Root.class, null);

        Graph<Root> graph = Graphs.lazy(missing, -1L, repository);

        assertNull(graph.get());
        assertTrue(graph.isEmpty());
        assertFalse(graph.isPresent());
        assertTrue(List.of(Graph.class.getMethods()).stream()
                           .noneMatch(method -> method.getName().equals("entity")));
    }

    @Test
    void missingUntypedModelHasNoAncestors() {
        FallbackAncestorRepository repository =
                new FallbackAncestorRepository(Map.of());
        Entity<Object> missing = entity("unknown", Object.class, null);

        Graph<Object> graph = Graphs.lazy(missing, -1L, repository);

        assertTrue(graph.ancestor(Root.class).isEmpty());
        assertFalse(repository.sourceLoaded);
        assertEquals(0, repository.identityLookups);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> entity(Object id, Class<T> type, T value) {
        return entity(id, type, value, List.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> entity(Object id, Class<T> type, T value, List<?> aliases) {
        Entity<T> entity = mock(Entity.class);
        when(entity.id()).thenReturn(id);
        when(entity.type()).thenReturn(type);
        when(entity.get()).thenReturn(value);
        when(entity.isPresent()).thenReturn(value != null);
        when(entity.isEmpty()).thenReturn(value == null);
        doReturn(aliases).when(entity).aliases();
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> modelEntity(
            Object id, Class<T> type, T value,
            long stateIndex, Instant timestamp) {
        ModelRoot<T> entity = mock(ModelRoot.class);
        when(entity.id()).thenReturn(id);
        when(entity.type()).thenReturn(type);
        when(entity.get()).thenReturn(value);
        when(entity.isPresent()).thenReturn(value != null);
        when(entity.isEmpty()).thenReturn(value == null);
        when(entity.aliases()).thenReturn(List.of());
        when(entity.stateIndex()).thenReturn(stateIndex);
        when(entity.timestamp()).thenReturn(timestamp);
        return entity;
    }

    @Model
    private record Root(@EntityId RootId id, String name) {
    }

    private static final class RootId extends Id<Root> {
        private RootId(String id) {
            super(id, "graph-root-");
        }
    }

    @Model
    private record Child(
            @EntityId ChildId id,
            @ParentId(path = "children") RootId rootId,
            String name) {
    }

    @Model
    private record Grandchild(
            @EntityId String id,
            @ParentId(value = Child.class, path = "details/grandchildren") ChildId childId) {
    }

    @Model
    private record ScopedChild(
            @EntityId(parentScoped = true) String id,
            @ParentId(value = Root.class, path = "scopedChildren") RootId rootId) {
    }

    @Model
    private record ScopedLeaf(
            @EntityId(parentScoped = true) String id,
            @ParentId(value = Child.class, path = "scopedLeaves") ChildId childId) {
    }

    @Model
    private record AliasedChild(
            @EntityId String id,
            @Alias String alias,
            @ParentId(value = Root.class, path = "aliasedChildren") RootId rootId) {
    }

    @Model
    private record OtherRoot(@EntityId String id, String name) {
    }

    @Model
    private record MultiChild(
            @EntityId String id,
            @ParentId(path = "rootChildren") RootId rootId,
            @ParentId(value = OtherRoot.class, path = "otherChildren") String otherRootId) {
    }

    private static final class ChildId extends Id<Child> {
        private ChildId(String id) {
            super(id, "graph-child-");
        }
    }

    private static final class AncestorRepository
            implements ModelRepository, ModelAncestorResolver {
        private final Graph<?> result;
        private String modelId;
        private Class<?> modelType;
        private Class<?> ancestorType;
        private ModelReadBoundary boundary;
        private boolean sourceLoaded;

        private AncestorRepository(Graph<?> result) {
            this.result = result;
        }

        @Override
        public <T> Entity<T> load(String modelId, Class<T> modelType) {
            sourceLoaded = true;
            throw new AssertionError("Ancestor identity resolution must not load intermediate models");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> Optional<Graph<A>> loadAncestorGraph(
                String modelId, Class<?> modelType,
                Class<A> ancestorType,
                ModelReadBoundary boundary) {
            this.modelId = modelId;
            this.modelType = modelType;
            this.ancestorType = ancestorType;
            this.boundary = boundary;
            return Optional.of((Graph<A>) result);
        }
    }

    private static final class FallbackAncestorRepository
            implements ModelRepository, ModelAncestorResolver {
        private final Map<String, Entity<?>> models;
        private boolean sourceLoaded;
        private int identityLookups;

        private FallbackAncestorRepository(Map<String, Entity<?>> models) {
            this.models = models;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Entity<T> load(String modelId, Class<T> modelType) {
            sourceLoaded = true;
            return (Entity<T>) models.get(modelId);
        }

        @Override
        public <A> Optional<Graph<A>> loadAncestorGraph(
                String modelId, Class<?> modelType,
                Class<A> ancestorType,
                ModelReadBoundary boundary) {
            identityLookups++;
            return Optional.empty();
        }
    }

    private static final class AliasAncestorRepository
            implements ModelRepository, ModelAncestorResolver {
        private final Entity<AliasedChild> source;
        private final Graph<Root> ancestor;
        private final java.util.ArrayList<String> identityLookups = new java.util.ArrayList<>();
        private boolean sourceLoaded;

        private AliasAncestorRepository(
                Entity<AliasedChild> source,
                Graph<Root> ancestor) {
            this.source = source;
            this.ancestor = ancestor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Entity<T> load(String modelId, Class<T> modelType) {
            sourceLoaded = true;
            return (Entity<T>) source;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> Optional<Graph<A>> loadAncestorGraph(
                String modelId, Class<?> modelType,
                Class<A> ancestorType,
                ModelReadBoundary boundary) {
            identityLookups.add(modelId);
            return modelId.equals(source.id())
                    ? Optional.of((Graph<A>) ancestor)
                    : Optional.empty();
        }
    }
}
