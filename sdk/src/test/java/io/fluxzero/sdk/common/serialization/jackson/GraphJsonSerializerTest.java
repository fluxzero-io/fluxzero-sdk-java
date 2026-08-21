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

package io.fluxzero.sdk.common.serialization.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProperty;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ParentId;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphJsonSerializerTest {
    private final JacksonSerializer serializer = new JacksonSerializer();

    @Test
    void serializesGraphAsModelDocumentWithRecursiveRelationshipPaths() {
        Graph<Root> graph = graph();

        JsonNode document = serializer.getObjectMapper().valueToTree(graph);

        assertEquals("root", document.path("name").asText());
        assertEquals("visible", document.path("children").get(0).path("value").asText());
        assertEquals("leaf", document.path("children").get(0)
                .path("details").path("leaves").get(0).path("value").asText());
        assertEquals(2, document.path("childCount").asInt());
        assertEquals("root/visible", document.path("children").get(0).path("qualifiedValue").asText());
        assertFalse(serializer.getObjectMapper().valueToTree(graph.get()).has("childCount"));
        assertFalse(document.has("stateIndex"));
        assertFalse(document.has("present"));
    }

    @Test
    void filtersEveryModelWithItsOwnAndItsParentGraphContext() {
        Graph<Root> graph = graph();
        Graph<Root> filtered = serializer.filterContent(graph, new MockUser("normal"));

        JsonNode document = serializer.getObjectMapper().valueToTree(filtered);

        assertSame(graph.get(), filtered.get());
        assertEquals("root/visible", document.path("children").get(0).path("value").asText());
        assertEquals(0, document.path("children").get(0)
                .path("details").path("leaves").size());
    }

    @Test
    void removesAGraphWhoseRootContentFilterRejectsItFromACollection() {
        Graph<Root> filtered = serializer.filterContent(graph(), new MockUser("hidden"));

        assertEquals(null, filtered);
        assertEquals(List.of(), serializer.filterContent(List.of(graph()), new MockUser("hidden")));
    }

    @Test
    void rootFilterCanRemoveDescendantBranches() {
        Graph<Root> filtered = serializer.filterContent(graph(), new MockUser("limited"));

        assertEquals(List.of("root/visible"), filtered.childModels(Child.class).stream()
                .map(Child::value).toList());
    }

    @Test
    void rootFilterCanSelectAnImmutableGraphViewOnce() {
        Graph<ViewRoot> graph = graphView();
        ViewRoot.filterInvocations.set(0);

        Graph<ViewRoot> filtered = serializer.filterContent(graph, new MockUser("limited"));

        assertSame(graph.get(), filtered.get());
        assertEquals(1, ViewRoot.filterInvocations.get());
        assertEquals(List.of("visible/filtered"), filtered.childModels(ViewChild.class).stream()
                .map(ViewChild::value).toList());
    }

    @Test
    void selectsAnImmutableRelationshipViewWithoutCopyingModels() {
        Graph<Root> graph = graph();

        Graph<Root> directChildren = graph.selectPaths("children");
        Graph<Root> completeBranch = graph.selectPaths(
                "children/details/leaves");

        assertSame(graph.get(), directChildren.get());
        assertSame(graph.childModels(Child.class).getFirst(),
                   directChildren.childModels(Child.class).getFirst());
        assertSame(directChildren,
                   directChildren.children().getFirst().root());
        assertSame(directChildren,
                   directChildren.children().getFirst().parent().orElseThrow());

        JsonNode directDocument = serializer.getObjectMapper()
                .valueToTree(directChildren);
        assertEquals(2, directDocument.path("children").size());
        assertFalse(directDocument.path("children").get(0)
                            .path("details").has("leaves"));

        JsonNode completeDocument = serializer.getObjectMapper()
                .valueToTree(completeBranch);
        assertEquals("leaf", completeDocument.path("children").get(0)
                .path("details").path("leaves").get(0)
                .path("value").asText());
    }

    @Test
    void filtersGraphNodesLazilyWithoutCopyingAcceptedModels() {
        Graph<Root> graph = graph();

        Graph<Root> filtered = graph.filterNodes(node -> node.type() != Leaf.class);

        assertSame(graph.get(), filtered.get());
        assertSame(graph.childModels(Child.class).getFirst(), filtered.childModels(Child.class).getFirst());
        assertEquals(1, filtered.descendants(Leaf.class).size());
        assertTrue(filtered.descendants(Leaf.class).getFirst().isEmpty());
        assertEquals(List.of(), filtered.descendantModels(Leaf.class));
        JsonNode document = serializer.getObjectMapper().valueToTree(filtered);
        assertEquals(0, document.path("children").get(0).path("details").path("leaves").size());
    }

    @Test
    void filtersCompleteBranchesAndRetainsTheirAncestors() {
        Graph<Root> graph = graph();
        AtomicInteger evaluations = new AtomicInteger();

        Graph<Root> filtered = graph.filterBranches(node -> {
            evaluations.incrementAndGet();
            return node.type() == Leaf.class;
        });

        assertEquals(4, evaluations.get());
        assertSame(graph.get(), filtered.get());
        assertSame(graph.childModels(Child.class).getFirst(), filtered.childModels(Child.class).getFirst());
        assertEquals(1, filtered.children(Child.class).size());
        assertEquals(1, filtered.childModels(Child.class).size());
        assertEquals(1, filtered.descendants(Child.class).size());
        assertEquals(3, filtered.stream().count());
        assertTrue(filtered.find("sibling-id").isEmpty());
        assertSame(graph.descendantModels(Leaf.class).getFirst(), filtered.descendantModels(Leaf.class).getFirst());
        assertTrue(graph.filterBranches(node -> false).isEmpty());
    }

    @Test
    void sharesTypedResponseContextAcrossAFilteredGraphWithoutCopyingModels() {
        Graph<Root> graph = graph().withContext(new DerivedContext("response"));

        Graph<Root> filtered = serializer.filterContent(graph, new MockUser("normal"));
        Graph<Child> child = filtered.children(Child.class).getFirst();

        assertEquals("response", filtered.context(DerivedContext.class).orElseThrow().value());
        assertEquals("response", child.context(DerivedContext.class).orElseThrow().value());
        assertSame(graph.get(), filtered.get());
    }

    @Test
    void doesNotSerializePathlessRelationships() {
        Root rootValue = new Root("root-id", "root");
        Child childValue = new Child("child-id", rootValue.id(), "hidden");
        Graph<Root> graph = Graphs.compose(
                rootValue.id(), 5L,
                Map.of(rootValue.id(), entity(rootValue.id(), Root.class, rootValue),
                       childValue.id(), entity(childValue.id(), Child.class, childValue)),
                List.of(new ModelGraphEdge(
                        childValue.id(), rootValue.id(), Root.class.getName(), null, 0L, null, false)),
                mock(ModelRepository.class), false);

        JsonNode document = serializer.getObjectMapper().valueToTree(graph);

        assertEquals(List.of(childValue), graph.childModels(Child.class));
        assertFalse(document.has("children"));
    }

    private static Graph<Root> graph() {
        Root rootValue = new Root("root-id", "root");
        Child childValue = new Child("child-id", rootValue.id(), "visible");
        Child siblingValue = new Child("sibling-id", rootValue.id(), "sibling");
        Leaf leafValue = new Leaf("leaf-id", childValue.id(), "leaf");
        Entity<Root> root = entity(rootValue.id(), Root.class, rootValue);
        Entity<Child> child = entity(childValue.id(), Child.class, childValue);
        Entity<Child> sibling = entity(siblingValue.id(), Child.class, siblingValue);
        Entity<Leaf> leaf = entity(leafValue.id(), Leaf.class, leafValue);
        return Graphs.compose(
                rootValue.id(), 5L,
                Map.of(rootValue.id(), root, childValue.id(), child,
                       siblingValue.id(), sibling, leafValue.id(), leaf),
                List.of(
                        new ModelGraphEdge(
                                childValue.id(), rootValue.id(), Root.class.getName(),
                                "children", 0L, null, false),
                        new ModelGraphEdge(
                                leafValue.id(), childValue.id(), Child.class.getName(),
                                "details/leaves", 0L, null, false),
                        new ModelGraphEdge(
                                siblingValue.id(), rootValue.id(), Root.class.getName(),
                                "children", 0L, null, false)),
                mock(ModelRepository.class), false);
    }

    private static Graph<ViewRoot> graphView() {
        ViewRoot rootValue = new ViewRoot("view-root");
        ViewChild visible = new ViewChild("visible-child", rootValue.id(), "visible");
        ViewChild hidden = new ViewChild("hidden-child", rootValue.id(), "hidden");
        return Graphs.compose(
                rootValue.id(), 7L,
                Map.of(rootValue.id(), entity(rootValue.id(), ViewRoot.class, rootValue),
                       visible.id(), entity(visible.id(), ViewChild.class, visible),
                       hidden.id(), entity(hidden.id(), ViewChild.class, hidden)),
                List.of(
                        new ModelGraphEdge(visible.id(), rootValue.id(), ViewRoot.class.getName(),
                                           "children", 0L, null, false),
                        new ModelGraphEdge(hidden.id(), rootValue.id(), ViewRoot.class.getName(),
                                           "children", 0L, null, false)),
                mock(ModelRepository.class), false);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> entity(String id, Class<T> type, T value) {
        Entity<T> entity = mock(Entity.class);
        when(entity.id()).thenReturn(id);
        when(entity.type()).thenReturn(type);
        when(entity.get()).thenReturn(value);
        return entity;
    }

    @Model
    private record Root(@EntityId String id, String name) {

        @GraphProperty
        int childCount(Graph<Root> graph) {
            return graph.childModels(Child.class).size();
        }

        @FilterContent(descendants = true)
        Object filter(User user, Graph<?> graph) {
            return user.hasRole("hidden")
                    || user.hasRole("limited") && "sibling-id".equals(graph.id())
                    ? null : graph.get();
        }
    }

    @Model
    private record Child(
            @EntityId String id,
            @ParentId(value = Root.class, path = "children") String rootId,
            String value) {

        @FilterContent
        Child filter(Graph<Child> child, Graph<Root> root) {
            return new Child(id, rootId, root.get().name() + "/" + child.get().value());
        }

        @GraphProperty
        String qualifiedValue(Graph<Child> child, Graph<Root> root) {
            return root.get().name() + "/" + child.get().value();
        }
    }

    @Model
    private record Leaf(
            @EntityId String id,
            @ParentId(value = Child.class, path = "details/leaves") String childId,
            String value) {

        @FilterContent
        Leaf filter(User user) {
            return user.hasRole("admin") ? this : null;
        }
    }

    private record DerivedContext(String value) {
    }

    @Model
    private record ViewRoot(@EntityId String id) {
        private static final AtomicInteger filterInvocations = new AtomicInteger();

        @FilterContent
        Graph<ViewRoot> filter(User user, Graph<ViewRoot> graph) {
            filterInvocations.incrementAndGet();
            return user.hasRole("all") ? graph : graph.filterBranches(
                    node -> node.get() instanceof ViewChild child && "visible".equals(child.value()));
        }
    }

    @Model
    private record ViewChild(
            @EntityId String id,
            @ParentId(value = ViewRoot.class, path = "children") String rootId,
            String value) {

        @FilterContent
        ViewChild filter() {
            return new ViewChild(id, rootId, value + "/filtered");
        }
    }
}
