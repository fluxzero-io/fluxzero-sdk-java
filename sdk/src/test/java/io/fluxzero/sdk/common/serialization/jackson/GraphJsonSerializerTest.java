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
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ParentId;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(document.has("stateIndex"));
        assertFalse(document.has("present"));
    }

    @Test
    void filtersEveryModelWithItsOwnAndItsParentGraphContext() {
        Graph<Root> filtered = serializer.filterContent(graph(), new MockUser("normal"));

        JsonNode document = serializer.getObjectMapper().valueToTree(filtered);

        assertEquals("root/visible", document.path("children").get(0).path("value").asText());
        assertEquals(0, document.path("children").get(0)
                .path("details").path("leaves").size());
    }

    private static Graph<Root> graph() {
        Root rootValue = new Root("root-id", "root");
        Child childValue = new Child("child-id", rootValue.id(), "visible");
        Leaf leafValue = new Leaf("leaf-id", childValue.id(), "leaf");
        Entity<Root> root = entity(rootValue.id(), Root.class, rootValue);
        Entity<Child> child = entity(childValue.id(), Child.class, childValue);
        Entity<Leaf> leaf = entity(leafValue.id(), Leaf.class, leafValue);
        return Graphs.compose(
                rootValue.id(), 5L,
                Map.of(rootValue.id(), root, childValue.id(), child, leafValue.id(), leaf),
                List.of(
                        new ModelGraphEdge(
                                childValue.id(), rootValue.id(), Root.class.getName(),
                                "children", 0L, null),
                        new ModelGraphEdge(
                                leafValue.id(), childValue.id(), Child.class.getName(),
                                "details/leaves", 0L, null)),
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
}
