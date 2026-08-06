/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ParentId;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MaterializedGraphFactoryTest {

    @Test
    void materializesEachSelectedNodeAtMostOnce() {
        CountingRoot.constructions.set(0);
        CountingChild.constructions.set(0);
        JacksonSerializer serializer = new JacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "root");
        ArrayNode children = json.putArray("children");
        children.addObject().put("id", "child").put("rootId", "root");
        ModelGraphDocumentManifest manifest =
                new ModelGraphDocumentManifest(
                        41L,
                        List.of(CountingRoot.class.getName(),
                                CountingChild.class.getName()),
                        List.of("children"),
                        List.of(
                                new ModelGraphDocumentManifest.Node(
                                        "root", 0, -1, -1, 0),
                                new ModelGraphDocumentManifest.Node(
                                        "child", 1, 0, 0, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "root", "root-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));

        Graph<CountingRoot> graph = MaterializedGraphFactory.create(
                document, CountingRoot.class, serializer,
                () -> mock(ModelRepository.class),
                List.of(CountingRoot.class, CountingChild.class),
                Map.of());
        Graph<CountingChild> child = graph.children(
                "children", CountingChild.class).getFirst();

        assertEquals(0, CountingRoot.constructions.get());
        assertEquals(0, CountingChild.constructions.get());
        assertEquals("root", graph.get().id());
        assertEquals("root", graph.get().id());
        assertEquals(1, CountingRoot.constructions.get());
        assertEquals(0, CountingChild.constructions.get());
        assertEquals("child", child.get().id());
        assertEquals("child", child.get().id());
        assertEquals(1, CountingChild.constructions.get());
    }

    @Test
    void rejectsMismatchedMaterializedGraphHandlerType() throws Exception {
        var method = InvalidHandler.class.getDeclaredMethod(
                "handle", Graph.class);
        var resolver = new MaterializedGraphParameterResolver(
                new JacksonSerializer(),
                () -> mock(ModelRepository.class), List::of);

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        method.getParameters()[0],
                        method.getAnnotation(HandleDocument.class)));
    }

    @Model
    private record CountingRoot(@EntityId String id) {
        private static final AtomicInteger constructions =
                new AtomicInteger();

        private CountingRoot {
            constructions.incrementAndGet();
        }
    }

    @Model
    private record CountingChild(
            @EntityId String id,
            @ParentId(value = CountingRoot.class, path = "children")
            String rootId) {
        private static final AtomicInteger constructions =
                new AtomicInteger();

        private CountingChild {
            constructions.incrementAndGet();
        }
    }

    private static final class InvalidHandler {
        @HandleDocument(modelGraph = CountingRoot.class)
        void handle(Graph<CountingChild> graph) {
        }
    }
}
