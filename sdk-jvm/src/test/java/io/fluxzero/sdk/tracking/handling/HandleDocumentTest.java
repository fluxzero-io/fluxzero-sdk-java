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
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.search.SearchTest.SomeDocument;
import io.fluxzero.sdk.test.TestFixture;
import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

public class HandleDocumentTest {

    protected TestFixture testFixture = TestFixture.create();

    @Test
    void handleDocument_class() {
        testFixture.registerHandlers(new DocumentClassHandler()).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectOnlyEvents("someDocument");
    }

    @Test
    void handleDocument_collectionName() {
        testFixture.registerHandlers(new DocumentCollectionHandler()).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectOnlyEvents("someDocument")
                .andThen()
                .whenExecuting(fc -> Fluxzero.index("foo", "otherDoc").get())
                .expectOnlyEvents("otherDocument");
    }

    @Test
    void handleDocument_firstParam() {
        testFixture
                .registerHandlers(new DocumentFirstParamHandler())
                .whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectTrue(fc -> Fluxzero.search(SomeDocument.class).count() == 1)
                .expectEvents("someDocument");
    }

    @Test
    void deletingDocument() {
        testFixture.registerHandlers(new DeletingDocumentHandler()).whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectNoErrors()
                .expectTrue(fc -> Fluxzero.search(SomeDocument.class).count() == 0);
    }

    @Test
    void notDeletingDocumentIfReturnTypeIsWrong() {
        testFixture.registerHandlers(new WrongReturnTypeDocumentHandler())
                .whenExecuting(fc -> Fluxzero.index(new SomeDocument()).get())
                .expectNoErrors()
                .expectTrue(fc -> Fluxzero.search(SomeDocument.class).count() == 1);
    }

    @Test
    void updateRevision() {
        testFixture.registerHandlers(new RevisionUpdatingDocumentHandler()).whenExecuting(fc -> {
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
        testFixture.registerHandlers(new SameRevisionDocumentHandler())
                .whenExecuting(fc -> Fluxzero.index(new MyDocument("foo")).get())
                .expectEvents("got here")
                .expectFalse(fc -> Fluxzero.search(MyDocument.class).<MyDocument>fetchFirst().orElseThrow()
                        .getValue().equals("bar"));
    }

    @Test
    void handleDocumentWithIdSubtype() {
        testFixture.registerHandlers(new DocumentWithIdSubtypeHandler())
                .whenExecuting(fc -> Fluxzero.index(new DocumentWithId(new DocumentId("CMA"))).get())
                .expectOnlyEvents("CMA");
    }

    static class DocumentClassHandler {
        @HandleDocument(documentClass = SomeDocument.class)
        void handleClass() {
            Fluxzero.publishEvent("someDocument");
        }

        @HandleDocument("otherDoc")
        void handleName() {
            Fluxzero.publishEvent("otherDocument");
        }
    }

    static class DocumentCollectionHandler {
        @HandleDocument("someDoc")
        void handleName() {
            Fluxzero.publishEvent("someDocument");
        }

        @HandleDocument("otherDoc")
        void handleOther() {
            Fluxzero.publishEvent("otherDocument");
        }
    }

    static class DocumentFirstParamHandler {
        @HandleDocument
        void handle(SomeDocument document) {
            Fluxzero.publishEvent("someDocument");
        }
    }

    static class DeletingDocumentHandler {
        @HandleDocument
        Object handle(SomeDocument doc) {
            return null;
        }
    }

    static class WrongReturnTypeDocumentHandler {
        @HandleDocument
        String handle(SomeDocument doc) {
            return null;
        }
    }

    static class RevisionUpdatingDocumentHandler {
        @HandleDocument
        MyDocument handleClass(MyDocument document) {
            return document.toBuilder().value("bar").build();
        }
    }

    static class SameRevisionDocumentHandler {
        @HandleDocument
        MyDocument handleClass(MyDocument document) {
            Fluxzero.publishEvent("got here");
            return document.toBuilder().value("bar").build();
        }
    }

    static class DocumentWithIdSubtypeHandler {
        @HandleDocument
        void handle(DocumentWithId document) {
            Fluxzero.publishEvent(document.identifier().getFunctionalId());
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
