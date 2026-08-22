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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.SearchParameters;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.MaterializedGraphDocumentMigration;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static io.fluxzero.sdk.common.ClientUtils.getSearchParameters;

/**
 * A {@link HandlerDecorator} that intercepts handler methods annotated with {@link HandleDocument} and synchronizes
 * their return values with a {@link DocumentStore}.
 * <p>
 * This decorator ensures that searchable document views (e.g. projections or read models) are automatically updated
 * when a message is handled. If the handler method returns an object of the same type as the incoming message payload
 * (and is non-passive), the decorator will:
 * <ul>
 *     <li>Index the return value into the configured document store, if non-null and its {@link Revision} is newer than the original version (before upcasting).</li>
 *     <li>Delete the corresponding document if the return value is {@code null}.</li>
 * </ul>
 * Materialized Model Graph handlers use a separate result contract: returning their complete typed {@link Graph}
 * may migrate only the derived graph document and never invokes this ordinary document update path.
 * <p>
 * The collection name is derived from the {@code message topic}. Timestamps for indexing can be determined in two ways:
 * <ul>
 *     <li>If {@link io.fluxzero.sdk.modeling.SearchParameters} are available, they are used to extract timestamps.</li>
 *     <li>Otherwise, the message metadata keys {@code "$start"} and {@code "$end"} are used (if present).</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * @HandleDocument
 * UserProfile update(UserProfile document) {
 *     return document.toBuilder().status(active).build(); //gives every existing user a status of active
 * }
 * }</pre>
 *
 * @see HandleDocument
 * @see DocumentStore
 * @see HandlerDecorator
 */
public class DocumentHandlerDecorator implements HandlerDecorator {
    private final Supplier<DocumentStore> documentStoreSupplier;
    private final GraphDocumentWriter graphDocumentWriter;

    public DocumentHandlerDecorator(Supplier<DocumentStore> documentStoreSupplier) {
        this(documentStoreSupplier, migration -> CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Materialized graph document migration has no configured writer")));
    }

    public DocumentHandlerDecorator(
            Supplier<DocumentStore> documentStoreSupplier,
            GraphDocumentWriter graphDocumentWriter) {
        this.documentStoreSupplier = documentStoreSupplier;
        this.graphDocumentWriter = graphDocumentWriter;
    }

    @Override
    public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        return new DocumentHandler(handler);
    }

    protected class DocumentHandler implements Handler<DeserializingMessage> {

        private final Handler<DeserializingMessage> delegate;

        protected DocumentHandler(Handler<DeserializingMessage> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return delegate.getInvoker(message).map(invoker -> decorate(invoker, message));
        }

        private HandlerInvoker decorate(
                HandlerInvoker invoker,
                DeserializingMessage message) {
            if (invoker.isPassive()
                || !(invoker.getMethod() instanceof Method method)) {
                return invoker;
            }
            HandleDocument annotation = ReflectionUtils
                    .<HandleDocument>getMethodAnnotation(method, HandleDocument.class)
                    .orElse(null);
            if (annotation == null) {
                return invoker;
            }
            if (annotation.modelGraph() != Void.class) {
                if (!Graph.class.isAssignableFrom(method.getReturnType())) {
                    return invoker;
                }
                return new ModelGraphDocumentHandlerInvoker(
                        invoker, message);
            }
            String collection = DocumentHandlerTopics.resolve(annotation, method);
            return method.getReturnType().isAssignableFrom(message.getPayloadClass())
                    ? new DocumentHandlerInvoker(invoker, collection, message)
                    : invoker;
        }

        @Override
        public Class<?> getTargetClass() {
            return delegate.getTargetClass();
        }

        protected class DocumentHandlerInvoker extends HandlerInvoker.DelegatingHandlerInvoker {
            private final DeserializingMessage message;
            private final String collection;

            public DocumentHandlerInvoker(HandlerInvoker delegate, String collection, DeserializingMessage message) {
                super(delegate);
                this.message = message;
                this.collection = collection;
            }

            @Override
            public Object invoke(BiFunction<Object, Object, Object> combiner) {
                Object result = delegate.invoke(combiner);
                handleResult(result);
                return result;
            }

            private void handleResult(Object result) {
                DocumentStore store = documentStoreSupplier.get();
                if (result == null) {
                    store.deleteDocument(message.getMessageId(), collection);
                } else {
                    if (ClientUtils.getRevisionNumber(result) > message.getSerializedObject().getOriginalRevision()) {
                        if (getSearchParameters(result.getClass()) instanceof SearchParameters searchParams
                            && (searchParams.getTimestampPath() != null || searchParams.getEndPath() != null)) {
                            store.index(result, message.getMessageId(), collection);
                        } else {
                            var start = Optional.ofNullable(message.getMetadata().get("$start")).map(Long::valueOf)
                                    .map(Instant::ofEpochMilli).orElse(null);
                            var end = Optional.ofNullable(message.getMetadata().get("$end")).map(Long::valueOf)
                                    .map(Instant::ofEpochMilli).orElse(null);
                            store.index(result, message.getMessageId(), collection, start, end);
                        }
                    }
                }
            }
        }

        protected class ModelGraphDocumentHandlerInvoker
                extends HandlerInvoker.DelegatingHandlerInvoker {
            private final DeserializingMessage message;

            public ModelGraphDocumentHandlerInvoker(
                    HandlerInvoker delegate,
                    DeserializingMessage message) {
                super(delegate);
                this.message = message;
            }

            @Override
            public Object invoke(
                    BiFunction<Object, Object, Object> combiner) {
                Object result = delegate.invoke(combiner);
                if (result instanceof Graph<?> graph) {
                    MaterializedGraphDocumentMigration.create(
                                    graph, message,
                                    documentStoreSupplier.get().getSerializer())
                            .ifPresent(migration -> graphDocumentWriter
                                    .rewrite(migration).join());
                }
                return result;
            }
        }
    }

    @FunctionalInterface
    public interface GraphDocumentWriter {
        CompletableFuture<Void> rewrite(
                MaterializedGraphDocumentMigration.Migration migration);
    }
}
