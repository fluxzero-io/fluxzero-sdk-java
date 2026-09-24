/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.search.ModelGraphDocumentStitcher;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProjection;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocumentGraphUpcastingTest {
    private static final String COLLECTION = "upcast-graphs";
    private static final String ROOT_TYPE = "io.fluxzero.sdk.persisting.search.DocumentGraphUpcastingTest$Root";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void graphRootIsUpcastExactlyOnce(boolean async) {
        var caster = new MoveName();
        fixture(async).registerCasters(caster).registerHandlers(new GraphReader())
                .whenExecuting(DocumentGraphUpcastingTest::indexLegacyGraph)
                .expectOnlyEvents("graph:Legacy name")
                .expectNoErrors();
        // The async fixture additionally decodes the indexed document for its own dispatch trace.
        assertEquals(async ? 2 : 1, caster.calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedMethodsRetainSingleMethodSelection(boolean async) {
        var caster = new MoveName();
        fixture(async).registerCasters(caster).registerHandlers(new MixedReader())
                .whenExecuting(DocumentGraphUpcastingTest::indexLegacyGraph)
                .expectOnlyEvents((Predicate<String>) value -> Set.of("ordinary:Legacy name", "graph:Legacy name")
                        .contains(value))
                .expectNoErrors();
        assertEquals(async ? 2 : 1, caster.calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void separateConsumersDoNotChangeOrdinaryDocuments(boolean async) {
        fixture(async).registerCasters(new MoveName()).registerHandlers(new GraphReader(), new OrdinaryReader())
                .whenExecuting(DocumentGraphUpcastingTest::indexLegacyGraph)
                .expectOnlyEvents("graph:Legacy name", "ordinary:Legacy name")
                .expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void splitAndDropRemainOrdinarySemanticsButCannotSilentlyRemoveOrMultiplyGraphs(int outputs) {
        var serializer = new JacksonSerializer(List.of(new SplitRoot(outputs)));
        var reader = new DocumentMessageReader();
        reader.register(new OrdinaryReader(), HandlerFilter.ALWAYS_HANDLE);
        var document = legacyGraph(serializer);
        SerializedMessage source = new SerializedMessage(document.getDocument(), document.getMetadata(), "root", 0L);
        var messages = reader.read(List.of(source), COLLECTION, serializer).toList();
        assertEquals(outputs, messages.size());
        reader.register(new GraphReader(), HandlerFilter.ALWAYS_HANDLE);
        assertThrows(DeserializationException.class, () -> reader.read(List.of(source), COLLECTION, serializer).toList());
    }

    @Test
    void cancellationAndFilteredRegistrationRestoreOrdinaryStream() {
        var reader = new DocumentMessageReader();
        var first = reader.register(new GraphReader(), HandlerFilter.ALWAYS_HANDLE);
        var second = reader.register(new GraphReader(), HandlerFilter.ALWAYS_HANDLE);
        assertTrue(reader.readsGraphs(COLLECTION));
        first.cancel();
        first.cancel();
        assertTrue(reader.readsGraphs(COLLECTION));
        second.cancel();
        assertFalse(reader.readsGraphs(COLLECTION));
        reader.register(new MixedReader(), (type, method) -> method.getName().equals("ordinary"));
        assertFalse(reader.readsGraphs(COLLECTION));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void sourceHandlersRejectSplitOrDroppedStatesWithoutChangingOrdinaryReaders(int outputs) {
        var serializer = new JacksonSerializer(List.of(new SplitRoot(outputs)));
        var reader = new DocumentMessageReader();
        var document = legacyGraph(serializer);
        var source = new SerializedMessage(document.getDocument(), Metadata.empty(), "root", 0L);
        String topic = "$modelGraphComponents/UpcastRoot";
        Object handler = new Object() {
            @HandleDocument(modelState = Root.class)
            Root rewrite(Root root) { return root; }
        };
        var first = reader.register(handler, HandlerFilter.ALWAYS_HANDLE);
        var second = reader.register(handler, HandlerFilter.ALWAYS_HANDLE);
        assertThrows(DeserializationException.class, () -> reader.read(List.of(source), topic, serializer).toList());
        first.cancel();
        first.cancel();
        assertThrows(DeserializationException.class, () -> reader.read(List.of(source), topic, serializer).toList());
        second.cancel();
        assertEquals(outputs, reader.read(List.of(source), topic, serializer).count());
        reader.register(handler, (type, method) -> false);
        assertEquals(outputs, reader.read(List.of(source), topic, serializer).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void graphHandlerRetainsAdditionalLogicalPayloadAndMessageParameters(boolean async) {
        fixture(async).registerCasters(new MoveName()).registerHandlers(new Object() {
            @HandleDocument(modelGraph = Root.class)
            void read(Graph<Root> graph, Root root, io.fluxzero.sdk.common.serialization.DeserializingMessage message) {
                assertEquals(root, graph.get());
                assertEquals(ROOT_TYPE, message.getType());
                assertEquals(1, message.getSerializedObject().getData().getRevision());
                assertInstanceOf(Root.class, message.getPayload());
                Fluxzero.publishEvent(root.details().name());
            }
        }).whenExecuting(DocumentGraphUpcastingTest::indexLegacyGraph)
                .expectOnlyEvents("Legacy name").expectNoErrors();
    }

    @Test
    void descendantUsesOriginalCompositionAfterRootCasterRemovesItsPath() throws Exception {
        var rootCaster = new MoveName();
        var childCaster = new MoveValue();
        var serializer = new JacksonSerializer(List.of(rootCaster, childCaster));
        ObjectNode json = serializer.getObjectMapper().createObjectNode().put("id", "root").put("name", "Legacy name");
        json.putArray("children").addObject().put("id", "child").put("oldValue", "Legacy child");
        var manifest = new ModelGraphDocumentManifest(41L, List.of("UpcastRoot", "UpcastChild"),
                List.of(ROOT_TYPE, Child.class.getName()), List.of("children"), List.of(
                new ModelGraphDocumentManifest.Node("root", 0, 0, 0, -1, -1, 0),
                new ModelGraphDocumentManifest.Node("child", 1, 1, 0, 0, 0, 0)));
        SerializedMessage source = new SerializedMessage(serializer.serialize(json).withType(ROOT_TYPE).withRevision(0),
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY, manifest.serialize()), "root", 0L);
        var reader = new DocumentMessageReader();
        reader.register(new GraphReader(), HandlerFilter.ALWAYS_HANDLE);
        var message = reader.read(List.of(source), COLLECTION, serializer).findFirst().orElseThrow();
        var resolver = new MaterializedGraphParameterResolver(serializer, () -> mock(ModelRepository.class),
                () -> List.of(Root.class, Child.class));
        var method = GraphReader.class.getDeclaredMethod("read", Graph.class);
        Graph<?> graph = (Graph<?>) resolver.resolve(method.getParameters()[0], method.getAnnotation(HandleDocument.class))
                .apply(message);
        assertEquals(new Root("root", new Details("Legacy name")), graph.get());
        assertEquals(new Child("child", "Legacy child"), graph.children(Child.class).getFirst().get());
        assertEquals(1, rootCaster.calls.get());
        assertEquals(1, childCaster.calls.get());
        assertEquals("Legacy child", graph.children(Child.class).getFirst().get().value());
        assertEquals(1, childCaster.calls.get());
    }

    private static TestFixture fixture(boolean async) {
        return async ? TestFixture.createAsync() : TestFixture.create();
    }

    private static void indexLegacyGraph(Fluxzero fluxzero) {
        var serializer = (JacksonSerializer) fluxzero.documentStore().getSerializer();
        fluxzero.client().getSearchClient().index(List.of(legacyGraph(serializer)), Guarantee.STORED, false).join();
    }

    private static SerializedDocument legacyGraph(JacksonSerializer serializer) {
        ObjectNode json = serializer.getObjectMapper().createObjectNode().put("id", "root").put("name", "Legacy name");
        var root = new SerializedDocument(serializer.toDocument(json, "root", "roots", null, null, Metadata.empty())
                .deserializeDocument().toBuilder().type(ROOT_TYPE).revision(0).build());
        return ModelGraphDocumentStitcher.stitch(List.of(root), List.of(), Map.of("root", root),
                Map.of("root", "UpcastRoot"), ModelGraphComposition.builder().build(), 41L)
                .getFirst().withCollection(COLLECTION);
    }

    @Model(name = "UpcastRoot", materializeGraph = true,
            graphProjection = @GraphProjection(collection = COLLECTION))
    @Revision(1)
    record Root(@EntityId String id, Details details) {}
    record Details(String name) {}

    @Model(name = "UpcastChild")
    @Revision(1)
    record Child(@EntityId String id, String value) {}

    static class MoveValue {
        final AtomicInteger calls = new AtomicInteger();
        @Upcast(type = "io.fluxzero.sdk.persisting.search.DocumentGraphUpcastingTest$Child", revision = 0)
        JsonNode move(ObjectNode child) {
            calls.incrementAndGet();
            child.set("value", child.remove("oldValue"));
            return child;
        }
    }

    static class MoveName {
        final AtomicInteger calls = new AtomicInteger();
        @Upcast(type = ROOT_TYPE, revision = 0)
        JsonNode move(ObjectNode root) {
            calls.incrementAndGet();
            JsonNode name = root.remove("name");
            if (name == null) { throw new IllegalArgumentException("Legacy name was already consumed"); }
            root.remove("children");
            root.putObject("details").set("name", name);
            return root;
        }
    }

    record SplitRoot(int outputs) {
        @Upcast(type = ROOT_TYPE, revision = 0)
        Stream<Data<JsonNode>> split(Data<ObjectNode> data) {
            ObjectNode root = data.getValue();
            root.putObject("details").set("name", root.remove("name"));
            return Stream.generate(() -> data.<JsonNode>map(ignored -> root).withRevision(1)).limit(outputs);
        }
    }

    @Consumer(name = "graph-reader")
    static class GraphReader {
        @HandleDocument(modelGraph = Root.class)
        void read(Graph<Root> graph) { Fluxzero.publishEvent("graph:" + graph.get().details().name()); }
    }

    @Consumer(name = "ordinary-reader")
    static class OrdinaryReader {
        @HandleDocument(COLLECTION)
        void ordinary(Root root) { Fluxzero.publishEvent("ordinary:" + root.details().name()); }
    }

    @Consumer(name = "mixed-reader")
    static class MixedReader {
        @HandleDocument(modelGraph = Root.class)
        void graph(Graph<Root> graph) { Fluxzero.publishEvent("graph:" + graph.get().details().name()); }
        @HandleDocument(COLLECTION)
        void ordinary(Root root) { Fluxzero.publishEvent("ordinary:" + root.details().name()); }
    }
}
