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

import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.fluxzero.sdk.modeling.ModelTargetResolver.Access.READ_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertSame(graph.children("children", Child.class).getFirst().root().get(), graph.get());
        verify(repository).loadGraph(
                rootValue.id().toString(), Root.class, Graph.Options.DEFAULT);
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
        ModelTargetResolver.ResolvedModel rootTarget = new ModelTargetResolver.ResolvedModel(
                rootValue.id().toString(), Root.class, READ_ONLY, List.of("id"));
        ModelTargetResolver.ResolvedModel childTarget = new ModelTargetResolver.ResolvedModel(
                childValue.id().toString(), Child.class, READ_ONLY, List.of("id"));
        ModelCommitContext commitContext = ModelCommitContext.create(
                11L,
                new ModelTargetResolver.Resolution(
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
    void typedAncestorNavigationDisambiguatesMultipleParentBranches() {
        ModelRepository repository = mock(ModelRepository.class);
        Root rootValue = new Root(new RootId("multi"), "root");
        OtherRoot otherValue = new OtherRoot("other", "other");
        MultiChild childValue = new MultiChild("multi-child", rootValue.id(), otherValue.id());
        Entity<Root> root = entity(rootValue.id().toString(), Root.class, rootValue);
        Entity<OtherRoot> other = entity(otherValue.id(), OtherRoot.class, otherValue);
        Entity<MultiChild> child = entity(childValue.id(), MultiChild.class, childValue);
        ModelCommitContext context = ModelCommitContext.create(
                12L,
                new ModelTargetResolver.Resolution(
                        List.of(
                                new ModelTargetResolver.ResolvedModel(
                                        rootValue.id().toString(), Root.class, READ_ONLY, List.of("id")),
                                new ModelTargetResolver.ResolvedModel(
                                        otherValue.id(), OtherRoot.class, READ_ONLY, List.of("id")),
                                new ModelTargetResolver.ResolvedModel(
                                        childValue.id(), MultiChild.class, READ_ONLY, List.of("id"))),
                        List.of(), List.of()),
                Map.of(rootValue.id().toString(), root, otherValue.id(), other, childValue.id(), child));

        Graph<MultiChild> graph = Graphs.lazy(child, context, repository);

        assertEquals(rootValue, graph.ancestor(Root.class).orElseThrow().get());
        assertEquals(otherValue, graph.ancestor(OtherRoot.class).orElseThrow().get());
        assertThrows(IllegalStateException.class, graph::parent);
        verifyNoInteractions(repository);
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

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> entity(Object id, Class<T> type, T value) {
        Entity<T> entity = mock(Entity.class);
        when(entity.id()).thenReturn(id);
        when(entity.type()).thenReturn(type);
        when(entity.get()).thenReturn(value);
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
}
