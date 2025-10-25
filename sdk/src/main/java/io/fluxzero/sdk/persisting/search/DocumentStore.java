/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedPropertyValue;
import static io.fluxzero.sdk.Fluxzero.currentIdentityProvider;
import static java.util.Collections.singletonList;

/**
 * Interface for storing, updating, and querying documents in the Fluxzero Runtime.
 * <p>
 * This API allows objects to be indexed into searchable document collections, with support for metadata, timestamps,
 * audit trails, conditional indexing, and deletion. It also supports typed or faceted full-text search queries via
 * {@link Search}.
 * <p>
 * The interface provides various convenience methods for indexing single objects, collections, and for bulk update
 * operations. Indexing behavior can be configured by annotating model classes or customizing indexing strategies
 * manually.
 *
 * <p>Note that collection names are resolved using {@link #determineCollection(Object)} in many of this API's methods.
 *
 * @see Search
 * @see Fluxzero#search(Object)
 */
public interface DocumentStore {

    /**
     * Begins an index operation for the given object. Use the returned {@link IndexOperation} to set additional
     * indexing parameters before committing.
     */
    default IndexOperation prepareIndex(@NonNull Object object) {
        return DefaultIndexOperation.prepare(this, object);
    }

    /**
     * Indexes one or more objects using the default configuration. Timestamps, collection names, and IDs are inferred
     * from annotations or fallback strategies.
     */
    default CompletableFuture<Void> index(@NonNull Object object) {
        if (object.getClass().isArray()) {
            if (!object.getClass().arrayType().isPrimitive()) {
                object = Arrays.asList((Object[]) object);
            }
        }
        if (object instanceof Collection<?> collection) {
            return CompletableFuture.allOf(collection.stream().map(this::index).toArray(CompletableFuture[]::new));
        }
        if (object instanceof Entity<?> entity) {
            if (entity.isEmpty()) {
                Objects.requireNonNull(entity.id(), "Entity ID must not be null");
                Objects.requireNonNull(entity.type(), "Entity Type must not be null");
                return deleteDocument(entity.id(), entity.type());
            }
        }
        return prepareIndex(object).index();
    }

    /**
     * Indexes one or more objects into the specified collection.
     */
    default CompletableFuture<Void> index(@NonNull Object object, Object collection) {
        if (object.getClass().isArray()) {
            if (!object.getClass().arrayType().isPrimitive()) {
                object = Arrays.asList((Object[]) object);
            }
        }
        if (object instanceof Collection<?> col) {
            return CompletableFuture.allOf(
                    col.stream().map(v -> index(v, collection)).toArray(CompletableFuture[]::new));
        }
        if (object instanceof Entity<?> entity) {
            if (entity.isEmpty()) {
                Objects.requireNonNull(entity.id(), "Entity ID must not be null");
                return deleteDocument(entity.id(), collection);
            }
        }
        return prepareIndex(object).collection(collection).index();
    }

    /**
     * Indexes a single object with an explicitly provided ID and collection.
     */
    default CompletableFuture<Void> index(@NonNull Object object, Object id, Object collection) {
        if (object instanceof Entity<?> entity) {
            if (entity.isEmpty()) {
                return deleteDocument(id, collection);
            }
        }
        return prepareIndex(object).collection(collection).id(id).index();
    }

    /**
     * Indexes a single object at a specific timestamp.
     * <p>
     * The same value is used as both start and end time of the document.
     */
    @SneakyThrows
    default CompletableFuture<Void> index(@NonNull Object object, Object id, Object collection, Instant timestamp) {
        return index(object, id, collection, timestamp, timestamp, Guarantee.STORED, false);
    }

    /**
     * Indexes a single object with a specific start and end time.
     */
    @SneakyThrows
    default CompletableFuture<Void> index(@NonNull Object object, Object id, Object collection, Instant begin,
                                          Instant end) {
        return index(object, id, collection, begin, end, Guarantee.STORED, false);
    }

    /**
     * Indexes a document with the specified guarantees, metadata, and if-not-exists condition.
     */
    default CompletableFuture<Void> index(@NotNull Object object, Object id, Object collection, Instant begin,
                                          Instant end,
                                          Guarantee guarantee, boolean ifNotExists) {
        return index(object, id, collection, begin, end, Metadata.empty(), guarantee, ifNotExists);
    }

    /**
     * Indexes a document with the specified guarantees, metadata, and if-not-exists condition.
     */
    CompletableFuture<Void> index(@NotNull Object object, Object id, Object collection, Instant begin, Instant end,
                                  Metadata metadata, Guarantee guarantee, boolean ifNotExists);

    /**
     * Indexes a collection of objects into a named collection.
     */
    default CompletableFuture<Void> index(Collection<?> objects, Object collection) {
        return index(objects, collection, v -> getAnnotatedPropertyValue(v, EntityId.class).map(Object::toString)
                .orElseGet(() -> currentIdentityProvider().nextTechnicalId()));
    }

    /**
     * Indexes a collection of objects using a function to extract the object ID.
     */
    default <T> CompletableFuture<Void> index(Collection<? extends T> objects, Object collection,
                                              Function<? super T, ?> idFunction) {
        return index(objects, collection, idFunction, v -> null);
    }

    /**
     * Indexes a collection of objects using property paths for ID and timestamps.
     */
    @SneakyThrows
    default CompletableFuture<Void> index(Collection<?> objects, Object collection, String idPath,
                                          String timestampPath) {
        return index(objects, collection, idPath, timestampPath, timestampPath, Guarantee.STORED, false);
    }

    /**
     * Indexes a collection of objects using property paths for ID, start, and end timestamps.
     */
    @SneakyThrows
    default CompletableFuture<Void> index(Collection<?> objects, Object collection, String idPath,
                                          String beginPath, String endPath) {
        return index(objects, collection, idPath, beginPath, endPath, Guarantee.STORED, false);
    }

    /**
     * Indexes a collection of objects using functional accessors for ID and time intervals.
     */
    CompletableFuture<Void> index(Collection<?> objects, Object collection, String idPath,
                                  String beginPath, String endPath, Guarantee guarantee,
                                  boolean ifNotExists);

    /**
     * Indexes a collection of objects using property paths for ID, start, and end timestamps.
     */
    @SneakyThrows
    default <T> CompletableFuture<Void> index(Collection<? extends T> objects, Object collection,
                                              Function<? super T, ?> idFunction,
                                              Function<? super T, Instant> timestampFunction) {
        return index(objects, collection, idFunction, timestampFunction, timestampFunction, Guarantee.STORED, false);
    }

    /**
     * Indexes a collection of objects using property paths for ID, start, and end timestamps.
     */
    @SneakyThrows
    default <T> CompletableFuture<Void> index(Collection<? extends T> objects, Object collection,
                                              Function<? super T, ?> idFunction,
                                              Function<? super T, Instant> beginFunction,
                                              Function<? super T, Instant> endFunction) {
        return index(objects, collection, idFunction, beginFunction, endFunction, Guarantee.STORED, false);
    }

    /**
     * Indexes a collection of objects using functional accessors for ID and time intervals.
     */
    <T> CompletableFuture<Void> index(Collection<? extends T> objects, Object collection,
                                      Function<? super T, ?> idFunction,
                                      Function<? super T, Instant> beginFunction,
                                      Function<? super T, Instant> endFunction, Guarantee guarantee,
                                      boolean ifNotExists);

    /**
     * Indexes a document only if it is not already present in the index. The given object may be a collection of
     * objects.
     */
    default CompletableFuture<Void> indexIfNotExists(Object object, Object collection) {
        return indexIfNotExists(object instanceof Collection<?> ? (Collection<?>) object : singletonList(object),
                                collection);
    }

    /**
     * Indexes a document only if it is not already present in the index.
     */
    default CompletableFuture<Void> indexIfNotExists(Object object, Object id, Object collection) {
        return indexIfNotExists(object, id, collection, null);
    }

    /**
     * Indexes a document only if it is not already present in the index.
     */
    @SneakyThrows
    default CompletableFuture<Void> indexIfNotExists(Object object, Object id, Object collection, Instant timestamp) {
        return index(object, id, collection, timestamp, timestamp, Guarantee.STORED, true);
    }

    /**
     * Indexes a document only if it is not already present in the index.
     */
    @SneakyThrows
    default CompletableFuture<Void> indexIfNotExists(Object object, Object id, Object collection, Instant begin,
                                                     Instant end) {
        return index(object, id, collection, begin, end, Guarantee.STORED, true);
    }

    /**
     * Indexes documents only if there are not already present in the index.
     */
    default <T> CompletableFuture<Void> indexIfNotExists(Collection<? extends T> objects, Object collection) {
        return indexIfNotExists(objects, collection,
                                v -> getAnnotatedPropertyValue(v, EntityId.class).map(Object::toString)
                                        .orElseGet(() -> currentIdentityProvider().nextTechnicalId()));
    }

    /**
     * Indexes documents only if there are not already present in the index.
     */
    default <T> CompletableFuture<Void> indexIfNotExists(Collection<? extends T> objects, Object collection,
                                                         Function<? super T, ?> idFunction) {
        return indexIfNotExists(objects, collection, idFunction, v -> null);
    }

    /**
     * Indexes documents only if there are not already present in the index.
     */
    @SneakyThrows
    default <T> CompletableFuture<Void> indexIfNotExists(Collection<? extends T> objects, Object collection,
                                                         String idPath,
                                                         String timestampPath) {
        return index(objects, collection, idPath, timestampPath, timestampPath, Guarantee.STORED, true);
    }

    /**
     * Indexes documents only if there are not already present in the index.
     */
    @SneakyThrows
    default <T> CompletableFuture<Void> indexIfNotExists(Collection<? extends T> objects, Object collection,
                                                         String idPath,
                                                         String beginPath, String endPath) {
        return index(objects, collection, idPath, beginPath, endPath, Guarantee.STORED, true);
    }

    /**
     * Indexes documents only if there are not already present in the index.
     */
    @SneakyThrows
    default <T> CompletableFuture<Void> indexIfNotExists(Collection<? extends T> objects, Object collection,
                                                         Function<? super T, ?> idFunction,
                                                         Function<? super T, Instant> timestampFunction) {
        return index(objects, collection, idFunction, timestampFunction, timestampFunction, Guarantee.STORED, true);
    }

    /**
     * Indexes documents only if there are not already present in the index.
     */
    @SneakyThrows
    default <T> CompletableFuture<Void> indexIfNotExists(Collection<? extends T> objects, Object collection,
                                                         Function<? super T, ?> idFunction,
                                                         Function<? super T, Instant> beginFunction,
                                                         Function<? super T, Instant> endFunction) {
        return index(objects, collection, idFunction, beginFunction, endFunction, Guarantee.STORED, true);
    }

    /**
     * Applies a batch of document updates, using {@link Guarantee#STORED}.
     */
    @SneakyThrows
    default CompletableFuture<Void> bulkUpdate(Collection<? extends BulkUpdate> updates) {
        return bulkUpdate(updates, Guarantee.STORED);
    }

    /**
     * Applies a batch of document updates, using given {@link Guarantee}.
     */
    CompletableFuture<Void> bulkUpdate(Collection<? extends BulkUpdate> updates, Guarantee guarantee);

    /**
     * Prepares a search query for one or more document collections. {@code  collections} may be a
     * {@link Collection list} of collections. Collection names are resolved using
     * {@link #determineCollection(Object)}.
     */
    default Search search(@NonNull Object collection) {
        List<String> collections = (collection instanceof Collection<?> list ? list.stream() : Stream.of(collection))
                .map(this::determineCollection).toList();
        return search(SearchQuery.builder().collections(collections));
    }

    /**
     * Prepares a search query based on the specified {@link SearchQuery.Builder}.
     */
    Search search(SearchQuery.Builder queryBuilder);

    /**
     * Checks whether a document exists for the given identifier and its associated type. The type is used to determine
     * the document collection.
     */
    default boolean hasDocument(Id<?> id) {
        return hasDocument(id, id.getType());
    }

    /**
     * Checks if a document exists in the specified collection.
     */
    boolean hasDocument(Object id, Object collection);

    /**
     * Fetches a document by id using the associated type to determine the collection. The result is deserialized into
     * the stored type.
     */
    default <T> Optional<T> fetchDocument(Id<T> id) {
        return fetchDocument(id, id.getType());
    }

    /**
     * Fetches a document by ID and deserializes it into the stored type.
     */
    <T> Optional<T> fetchDocument(Object id, Object collection);

    /**
     * Fetches a document by ID and deserializes it into the provided type.
     */
    <T> Optional<T> fetchDocument(Object id, Object collection, Class<T> type);

    /**
     * Fetches a collection of documents by their IDs and deserializes them into the stored type.
     */
    <T> Collection<T> fetchDocuments(Collection<?> ids, Object collection);

    /**
     * Fetches a collection of documents by their IDs and deserializes them into the provided type.
     */
    <T> Collection<T> fetchDocuments(Collection<?> ids, Object collection, Class<T> type);

    /**
     * Deletes a document from the collection.
     */
    CompletableFuture<Void> deleteDocument(Object id, Object collection);

    /**
     * Deletes a document from the collection.
     */
    CompletableFuture<Void> moveDocument(Object id, Object collection, Object targetCollection);

    /**
     * Deletes an entire collection of documents.
     */
    CompletableFuture<Void> deleteCollection(Object collection);

    /**
     * Configures Fluxzero to use a search collection as a searchable audit trail with the given retention time.
     */
    CompletableFuture<Void> createAuditTrail(Object collection, Duration retentionTime);

    /**
     * Resolves a given collection specifier to a collection name, using {@link ClientUtils#determineSearchCollection}.
     */
    default String determineCollection(@NonNull Object c) {
        return ClientUtils.determineSearchCollection(c);
    }

    /**
     * Retrieves the serializer used for document operations within the document store.
     */
    DocumentSerializer getSerializer();

}
