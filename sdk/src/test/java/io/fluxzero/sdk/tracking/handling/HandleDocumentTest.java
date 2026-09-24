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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.search.ModelGraphDocumentStitcher;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.DocumentProjection;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProjection;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.search.SearchTest.SomeDocument;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HandleDocumentTest {

    protected TestFixture testFixture = TestFixture.create();

    @Test
    void handleDocument_class() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument(documentClass = SomeDocument.class)
                    void handleClass() {
                        Fluxzero.publishEvent("someDocument");
                    }

                    @HandleDocument("otherDoc")
                    void handleName() {
                        Fluxzero.publishEvent("otherDocument");
                    }
                }).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectOnlyEvents("someDocument");
    }

    @Test
    void handleDocument_collectionName() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument("someDoc")
                    void handleName() {
                        Fluxzero.publishEvent("someDocument");
                    }

                    @HandleDocument("otherDoc")
                    void handleOther() {
                        Fluxzero.publishEvent("otherDocument");
                    }
                }).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectOnlyEvents("someDocument")
                .andThen()
                .whenExecuting(fc -> Fluxzero.index("foo", "otherDoc").get())
                .expectOnlyEvents("otherDocument");
    }

    @Test
    void handleMaterializedModelGraph() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument(modelGraph = GraphRoot.class)
                    void handleGraph(Object document) {
                        Fluxzero.publishEvent("modelGraph");
                    }

                    @HandleDocument(documentClass = GraphRoot.class)
                    void handleDirectModel(Object document) {
                        Fluxzero.publishEvent("directModel");
                    }
                }).whenExecuting(fc -> Fluxzero.index("graph", "graph-roots-graphs").get())
                .expectOnlyEvents("modelGraph")
                .andThen()
                .whenExecuting(fc -> Fluxzero.index("direct", "graph-roots").get())
                .expectOnlyEvents("directModel");
    }

    @Test
    void returnedMaterializedGraphMigratesThroughTheLocalRuntimeBoundary() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument(modelGraph = GraphRoot.class)
                    Graph<GraphRoot> migrate(Graph<GraphRoot> graph) {
                        return graph;
                    }
                }).whenExecuting(fc -> {
                    var serializer = (JacksonSerializer) fc.documentStore().getSerializer();
                    var json = serializer
                            .getObjectMapper().createObjectNode().put("id", "root");
                    SerializedDocument direct = new SerializedDocument(
                            serializer.toDocument(
                                            json, "root", "graph-roots", null,
                                            null, Metadata.empty())
                                    .deserializeDocument().toBuilder()
                                    .type(GraphRoot.class.getName())
                                    .revision(0)
                                    .build());
                    SerializedDocument oldGraph = ModelGraphDocumentStitcher.stitch(
                            List.of(direct), List.of(), Map.of("root", direct),
                            Map.of("root", "GraphRoot"),
                            ModelGraphComposition.builder().build(), 41L)
                            .getFirst().withCollection("graph-roots-graphs");
                    fc.client().getSearchClient().index(
                            List.of(oldGraph), Guarantee.STORED, false).join();
                })
                .expectNoErrors()
                .expectTrue(fc -> {
                    SerializedDocument migrated = fc.client().getSearchClient().fetch(
                            new io.fluxzero.common.api.search.GetDocument(
                                    "root", "graph-roots-graphs"))
                            .orElseThrow();
                    return migrated.getDocument().getRevision() == 1
                           && ModelGraphDocumentManifest.from(migrated)
                                   .orElseThrow().nodes().getFirst().revision() == 1;
                });
    }

    @Test
    void explicitCollectionOverridesModelGraph() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument(value = "overridden", modelGraph = GraphRoot.class)
                    void handle(Object document) {
                        Fluxzero.publishEvent("overridden");
                    }
                }).whenExecuting(fc -> Fluxzero.index("graph", "overridden").get())
                .expectOnlyEvents("overridden");
    }

    @Test
    void rejectsModelWithoutMaterializedGraph() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> testFixture.registerHandlers(new Object() {
                    @HandleDocument(modelGraph = DirectOnlyModel.class)
                    void handle(Object document) {
                    }
                }));
    }

    @Test
    void handleDocument_firstParam() {
        testFixture
                .registerHandlers(new Object() {
                    @HandleDocument
                    void handle(SomeDocument document) {
                        Fluxzero.publishEvent("someDocument");
                    }
                })
                .whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectTrue(fc -> Fluxzero.search(SomeDocument.class).count() == 1)
                .expectEvents("someDocument");
    }

    @Test
    void deletingDocument() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument
                    Object handle(SomeDocument doc) {
                        return null;
                    }
                }).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectNoErrors()
                .expectTrue(fc -> Fluxzero.search(SomeDocument.class).count() == 0);
    }

    @Test
    void notDeletingDocumentIfReturnTypeIsWrong() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument
                    String handle(SomeDocument doc) {
                        return null;
                    }
                }).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectNoErrors()
                .expectTrue(fc -> Fluxzero.search(SomeDocument.class).count() == 1);
    }

    @Test
    void updateRevision() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument
                    MyDocument handleClass(MyDocument document) {
                        return document.toBuilder().value("bar").build();
                    }
                }).whenExecuting(fc -> {
                    var serializedDocument = fc.documentStore().getSerializer().toDocument(
                            new MyDocument("foo"), "123", MyDocument.class.getSimpleName(), null, null);
                    Data<byte[]> data = serializedDocument.getDocument();
                    serializedDocument = serializedDocument.withData(() -> data.withRevision(0));
                    fc.client().getSearchClient().index(List.of(serializedDocument), Guarantee.STORED, false).get();
                })
                .expectTrue(fc -> {
                    var hit =
                            Fluxzero.search(MyDocument.class).streamHits(SerializedDocument.class).findFirst()
                                    .orElseThrow();
                    MyDocument document = fc.documentStore().getSerializer().fromDocument(hit.getValue());
                    return hit.getValue().getDocument().getRevision() == 1 && document.getValue().equals("bar");
                });
    }

    @Test
    void noUpdateIfSameRevision() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument
                    MyDocument handleClass(MyDocument document) {
                        Fluxzero.publishEvent("got here");
                        return document.toBuilder().value("bar").build();
                    }
                }).whenExecuting(fc -> Fluxzero.index(new MyDocument("foo")).get())
                .expectEvents("got here")
                .expectFalse(fc -> Fluxzero.search(MyDocument.class).fetchFirst().orElseThrow()
                        .getValue().equals("bar"));
    }

    @Test
    void handleDocumentWithIdSubtype() {
        testFixture.registerHandlers(new Object() {
                    @HandleDocument
                    void handle(DocumentWithId document) {
                        Fluxzero.publishEvent(document.identifier().getFunctionalId());
                    }
                })
                .whenExecuting(fc -> Fluxzero.index(new DocumentWithId(new DocumentId("CMA"))).get())
                .expectOnlyEvents("CMA");
    }

    @Test
    void handlerResultUpdatesDocumentInApplicationNamespace() {
        DocumentStore defaultStore = mock(DocumentStore.class);
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new NamespacedDocumentHandler(), HandleDocument.class, List.of(new PayloadParameterResolver()));
        Handler<DeserializingMessage> wrapped = new DocumentHandlerDecorator(() -> defaultStore).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new MyDocument("tenant")), MessageType.DOCUMENT, new JacksonSerializer())
                .putContext(ConsumerConfiguration.class, ConsumerConfiguration.builder()
                        .name("namespaced-document-handler").namespace("tenant").build());

        wrapped.getInvokerOrNull(message).invoke();

        verify(defaultStore).deleteDocument(any(), any());
        verify(defaultStore, never()).forNamespace(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphResultMigratesOnlyTheHandledProjection() {
        JacksonSerializer serializer = new JacksonSerializer();
        DocumentStore store = mock(DocumentStore.class);
        when(store.getSerializer()).thenReturn(serializer);
        Graph<GraphRoot> graph = mock(Graph.class);
        when(graph.isRoot()).thenReturn(true);
        when(graph.id()).thenReturn("root");
        when(graph.type()).thenReturn(GraphRoot.class);
        when(graph.stateIndex()).thenReturn(41L);
        when(graph.get()).thenReturn(new GraphRoot("root"));
        when(graph.children()).thenReturn(List.of());
        AtomicReference<io.fluxzero.sdk.persisting.search.MaterializedGraphDocumentMigration.Migration>
                migration = new AtomicReference<>();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new GraphMigrationHandler(graph), HandleDocument.class, List.of());
        Handler<DeserializingMessage> wrapped = new DocumentHandlerDecorator(
                () -> store, value -> {
                    migration.set(value);
                    return CompletableFuture.completedFuture(null);
                }).wrap(handler);
        DeserializingMessage message = graphDocumentMessage(serializer);

        Object result = wrapped.getInvokerOrNull(message).invoke();

        assertSame(graph, result);
        assertEquals(1, ModelGraphDocumentManifest.from(
                        migration.get().replacement()).orElseThrow()
                             .nodes().getFirst().revision());
        verify(store).getSerializer();
        verify(store, never()).deleteDocument(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphMigrationFailureFailsHandling() {
        JacksonSerializer serializer = new JacksonSerializer();
        DocumentStore store = mock(DocumentStore.class);
        when(store.getSerializer()).thenReturn(serializer);
        Graph<GraphRoot> graph = mock(Graph.class);
        when(graph.isRoot()).thenReturn(true);
        when(graph.id()).thenReturn("root");
        when(graph.type()).thenReturn(GraphRoot.class);
        when(graph.stateIndex()).thenReturn(41L);
        when(graph.get()).thenReturn(new GraphRoot("root"));
        when(graph.children()).thenReturn(List.of());
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new GraphMigrationHandler(graph), HandleDocument.class, List.of());
        Handler<DeserializingMessage> wrapped = new DocumentHandlerDecorator(
                () -> store, ignored -> CompletableFuture.failedFuture(
                        new UnsupportedOperationException("old runtime"))).wrap(handler);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> wrapped.getInvokerOrNull(graphDocumentMessage(serializer)).invoke());

        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }

    @Test
    void nonGraphResultFromModelGraphHandlerDoesNotMutateTheProjection() {
        DocumentStore store = mock(DocumentStore.class);
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new ObjectGraphHandler(), HandleDocument.class, List.of());
        Handler<DeserializingMessage> wrapped = new DocumentHandlerDecorator(
                () -> store, ignored -> {
                    throw new AssertionError("Unexpected graph rewrite");
                }).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new Object()), MessageType.DOCUMENT,
                "graph-roots-graphs", new JacksonSerializer());

        wrapped.getInvokerOrNull(message).invoke();

        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void namespacedDocumentStoreInvokesLocalHandlerInThatNamespace() {
        AtomicReference<String> handled = new AtomicReference<>();
        TestFixture fixture = TestFixture.create(new Object() {
            @HandleDocument
            void handle(MyDocument document) {
                handled.set(document.getValue());
            }
        });

        fixture.whenExecuting(fc -> fc.documentStore().forNamespace("customer")
                        .index(new MyDocument("customer"), "document", MyDocument.class).join())
                .expectThat(fc -> assertEquals("customer", handled.get()));
    }

    private static DeserializingMessage graphDocumentMessage(
            JacksonSerializer serializer) {
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L, List.of("GraphRoot"), List.of(GraphRoot.class.getName()), List.of(),
                List.of(new ModelGraphDocumentManifest.Node(
                        "root", 0, 0, 0, -1, -1, 0)));
        var payload = serializer.getObjectMapper().createObjectNode()
                .put("id", "root");
        return new DeserializingMessage(
                new Message(payload, Metadata.of(
                        ModelGraphDocumentManifest.METADATA_KEY,
                        manifest.serialize()), "root", null),
                MessageType.DOCUMENT, "graph-roots-graphs", serializer);
    }

    static class NamespacedDocumentHandler {
        @HandleDocument
        MyDocument delete(MyDocument document) {
            return null;
        }
    }

    record GraphMigrationHandler(Graph<GraphRoot> graph) {
        @HandleDocument(modelGraph = GraphRoot.class)
        Graph<GraphRoot> migrate() {
            return graph;
        }
    }

    static class ObjectGraphHandler {
        @HandleDocument(modelGraph = GraphRoot.class)
        Object observe() {
            return null;
        }
    }

    @Revision(1)
    @Value
    @Builder(toBuilder = true)
    static class MyDocument {
        String value;
    }

    record DocumentWithId(DocumentId identifier) {
    }

    static class DocumentId extends Id<DocumentWithId> {
        public DocumentId(String functionalId) {
            super(functionalId);
        }
    }

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT}, document = @DocumentProjection(collection = "graph-roots"),
            materializeGraph = true)
    @Revision(1)
    record GraphRoot(@EntityId String id) {
    }

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT})
    record DirectOnlyModel(@EntityId String id) {
    }

}
