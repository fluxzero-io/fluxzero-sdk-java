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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.search.SearchTest.SomeDocument;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
