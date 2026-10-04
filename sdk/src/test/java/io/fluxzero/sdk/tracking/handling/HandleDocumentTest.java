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

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.search.SearchTest.SomeDocument;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;

import lombok.Builder;
import lombok.Value;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final Metadata SOURCE_METADATA = Metadata.of(
            "custom.key/with~characters", Map.of("nested", List.of(1, 2)),
            "$start", "custom-start", "$end", "custom-end", "remove", "old", "owner", "source", "\"quoted\"", "quoted value", "slash\\key", "slash value");
    private static final Instant SOURCE_START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant SOURCE_END = SOURCE_START.plusSeconds(60);

    @Test
    void replacementPreservesStoredMetadataAndTimesAcrossUpcasting() {
        testFixture.registerCasters(new RenameValue()).registerHandlers(new Object() {
            @HandleDocument
            MyDocument migrate(MyDocument value) { return value; }
        }).whenExecuting(fc -> {
            var json = new JacksonSerializer().getObjectMapper().createObjectNode().put("oldValue", "migrated");
            var original = fc.documentStore().getSerializer().toDocument(
                    json, "metadata", MyDocument.class.getSimpleName(), SOURCE_START, SOURCE_END, SOURCE_METADATA);
            var data = original.getDocument().withType(MyDocument.class.getName()).withRevision(0);
            fc.client().getSearchClient().index(List.of(original.withData(() -> data)), Guarantee.STORED, false).join();
        }).expectNoErrors().expectThat(fc -> {
            var stored = storedMetadataDocument(fc);
            assertEquals(SOURCE_METADATA, stored.getMetadata());
            assertEquals(SOURCE_START.toEpochMilli(), stored.getTimestamp());
            assertEquals(SOURCE_END.toEpochMilli(), stored.getEnd());
            assertEquals(1, stored.getDocument().getRevision());
            assertEquals(new MyDocument("migrated"), fc.documentStore().getSerializer().fromDocument(stored));
        });
    }

    @Test
    void messageResultExplicitlyReplacesMetadata() {
        assertReplacementMetadata(Metadata.of("owner", "replacement"));
    }

    @Test
    void messageResultExplicitlyRemovesMetadata() {
        assertReplacementMetadata(Metadata.empty());
    }

    private void assertReplacementMetadata(Metadata replacement) {
        testFixture.registerHandlers(new Object() {
            @HandleDocument
            Message migrate(MyDocument value) {
                return new Message(new MyDocument("replaced"), replacement, "ignored-id", SOURCE_END);
            }
        }).whenExecuting(fc -> indexMetadataDocument(fc, new MyDocument("old"), MyDocument.class))
                .expectNoErrors().expectThat(fc -> {
                    var stored = storedMetadataDocument(fc);
                    assertEquals(replacement, stored.getMetadata());
                    assertEquals("metadata", stored.getId());
                    assertEquals(SOURCE_START.toEpochMilli(), stored.getTimestamp());
                    assertEquals(SOURCE_END.toEpochMilli(), stored.getEnd());
                    assertEquals(1, stored.getDocument().getRevision());
                });
    }

    @Test
    void annotatedTimesKeepTheirExistingPrecedenceWhileMetadataSurvives() {
        testFixture.registerHandlers(new Object() {
            @HandleDocument
            TimedDocument migrate(TimedDocument value) {
                return new TimedDocument("new", SOURCE_END, SOURCE_END.plusSeconds(1));
            }
        }).whenExecuting(fc -> indexMetadataDocument(fc,
                        new TimedDocument("old", SOURCE_START, SOURCE_END), TimedDocument.class))
                .expectNoErrors().expectThat(fc -> {
                    var stored = fc.client().getSearchClient().fetch(new io.fluxzero.common.api.search.GetDocument(
                            "metadata", TimedDocument.class.getSimpleName())).orElseThrow();
                    assertEquals(SOURCE_METADATA, stored.getMetadata());
                    assertEquals(SOURCE_END.toEpochMilli(), stored.getTimestamp());
                    assertEquals(SOURCE_END.plusSeconds(1).toEpochMilli(), stored.getEnd());
                });
    }

    private static void indexMetadataDocument(Fluxzero fc, Object value, Class<?> type) {
        var source = fc.documentStore().getSerializer().toDocument(
                value, "metadata", type.getSimpleName(), SOURCE_START, SOURCE_END, SOURCE_METADATA);
        var data = source.getDocument().withRevision(0);
        fc.client().getSearchClient().index(List.of(source.withData(() -> data)), Guarantee.STORED, false).join();
    }

    private static SerializedDocument storedMetadataDocument(Fluxzero fc) {
        return fc.client().getSearchClient().fetch(new io.fluxzero.common.api.search.GetDocument(
                "metadata", MyDocument.class.getSimpleName())).orElseThrow();
    }

    static class RenameValue {
        @Upcast(type = "io.fluxzero.sdk.tracking.handling.HandleDocumentTest$MyDocument", revision = 0)
        ObjectNode upcast(ObjectNode value) {
            value.set("value", value.remove("oldValue"));
            return value;
        }
    }

    @Revision(1)
    @Searchable(timestampPath = "start", endPath = "end")
    record TimedDocument(String value, Instant start, Instant end) { }

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
                .expectFalse(fc -> Fluxzero.search(MyDocument.class).<MyDocument>fetchFirst().orElseThrow()
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

    static class NamespacedDocumentHandler {
        @HandleDocument
        MyDocument delete(MyDocument document) {
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

}
