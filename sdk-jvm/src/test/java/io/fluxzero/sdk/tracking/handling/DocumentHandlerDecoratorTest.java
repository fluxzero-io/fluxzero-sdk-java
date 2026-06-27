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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentHandlerDecoratorTest {

    @Test
    void decoratorDoesNotUseReflectionFallbackForHandleDocument() throws Exception {
        DocumentStore documentStore = documentStore();
        Executable executable = DocumentHandler.class.getDeclaredMethod("handle", Document.class);
        Handler<DeserializingMessage> handler = new DocumentHandlerDecorator(
                () -> documentStore, (e, a) -> Optional.empty()).wrap(singleInvoker(DocumentHandler.class, executable));

        handler.getInvoker(documentMessage()).orElseThrow().invoke();

        verifyNoInteractions(documentStore);
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForHandleDocument() throws Exception {
        DocumentStore documentStore = documentStore();
        Executable executable = DocumentHandler.class.getDeclaredMethod("handle", Document.class);
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(DocumentHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = new DocumentHandlerDecorator(() -> documentStore)
                        .wrap(singleInvoker(DocumentHandler.class, executable));

                handler.getInvoker(documentMessage()).orElseThrow().invoke();

                verify(documentStore).deleteDocument(eq("document-1"), eq("documents"), eq(Guarantee.STORED));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static DocumentStore documentStore() {
        DocumentStore documentStore = mock(DocumentStore.class, CALLS_REAL_METHODS);
        when(documentStore.deleteDocument(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return documentStore;
    }

    private static DeserializingMessage documentMessage() {
        return new DeserializingMessage(
                new Message(new Document(), Metadata.empty(), "document-1", null),
                MessageType.DOCUMENT, "documents", null);
    }

    private static Handler<DeserializingMessage> singleInvoker(Class<?> targetClass, Executable executable) {
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.of(HandlerInvoker.noOp(targetClass, executable));
            }
        };
    }

    static class DocumentHandler {
        @HandleDocument("documents")
        Document handle(Document document) {
            return document;
        }
    }

    record Document() {
    }
}
