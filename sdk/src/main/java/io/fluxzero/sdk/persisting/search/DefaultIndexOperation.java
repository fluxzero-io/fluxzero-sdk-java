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

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static io.fluxzero.common.SearchUtils.parseTimeProperty;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.hasProperty;
import static io.fluxzero.common.reflection.ReflectionUtils.readProperty;
import static io.fluxzero.sdk.Fluxzero.currentIdentityProvider;
import static io.fluxzero.sdk.common.ClientUtils.determineSearchCollection;
import static io.fluxzero.sdk.common.ClientUtils.getSearchParameters;
import static io.fluxzero.sdk.modeling.SearchParameters.defaultSearchParameters;
import static java.util.Optional.ofNullable;

/**
 * Default implementation of the {@link IndexOperation} interface.
 * <p>
 * This class provides a mutable, builder-style implementation for preparing and executing document indexing operations
 * using a {@link DocumentStore}.
 *
 * <p>Instances of this class are typically created by calling {@link DocumentStore#prepareIndex(Object)}. Upon
 * construction, the document ID, collection name, and timestamps are automatically extracted from the object's class
 * using reflection.
 *
 * @see DocumentStore
 * @see IndexOperation
 */
@Data
@Accessors(chain = true, fluent = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DefaultIndexOperation implements IndexOperation {

    /**
     * Prepare a new {@code DefaultIndexOperation} instance for managing document indexing.
     *
     * @param documentStore the {@link DocumentStore} used to store and manage the index operations
     * @param object        the object to be indexed, which will be analyzed and converted for storage
     * @return an index operation initialized for the given object
     */
    public static DefaultIndexOperation prepare(DocumentStore documentStore, @NonNull Object object) {
        Class<?> objectType = object instanceof Entity<?> e ? e.type() : object.getClass();
        var searchParams = ofNullable(getSearchParameters(objectType)).orElse(defaultSearchParameters);
        String collection = ofNullable(searchParams.getCollection()).orElseGet(objectType::getSimpleName);
        String idPath = object instanceof Entity<?> e ? e.idProperty() : getAnnotatedPropertyName(object, EntityId.class).orElse(null);
        return prepare(documentStore, object, collection, idPath,
                       searchParams.getTimestampPath(), searchParams.getEndPath());
    }

    public static DefaultIndexOperation prepare(DocumentStore documentStore, Object object, @NonNull Object collection,
                                                String idPath, String beginPath, String endPath) {
        Function<Object, ?> idFunction = v -> StringUtils.hasText(idPath)
                ? readProperty(idPath, v).orElseThrow(() -> new IllegalArgumentException(
                "Could not determine the document id for path: %s".formatted(idPath)))
                : currentIdentityProvider().nextTechnicalId();
        Function<Object, Instant> beginFunction = v -> parseTimeProperty(beginPath, v, false);
        Function<Object, Instant> endFunction = v -> hasProperty(endPath, v)
                ? parseTimeProperty(endPath, v, false) : beginFunction.apply(v);
        return prepare(documentStore, object, collection, idFunction, beginFunction, endFunction);
    }

    public static DefaultIndexOperation prepare(DocumentStore documentStore, Object object, @NonNull Object collection,
                                                Function<Object, ?> idFunction, Function<Object, Instant> beginFunction,
                                                Function<Object, Instant> endFunction) {
        Entity<?> entity = object instanceof Entity<?> e ? e : null;
        if (entity != null) {
            Objects.requireNonNull(entity.get(), "Entity value cannot be null");
            object = entity.get();
        }
        Object id = idFunction.apply(object);
        Instant start = beginFunction.apply(object);
        Instant end = endFunction.apply(object);
        Metadata metadata = Metadata.empty();
        while (entity != null) {
            var parent = entity.parent();
            if (parent != null && parent.isPresent()) {
                metadata = metadata.with(parent.idProperty(), parent.id().toString());
            }
            entity = parent;
        }
        return new DefaultIndexOperation(documentStore, object, collection, id, metadata, start, end, false);
    }

    @JsonIgnore
    final transient DocumentStore documentStore;
    final Object value;
    @NonNull
    Object collection;
    @NonNull
    Object id;
    @NonNull
    Metadata metadata;
    Instant start, end;
    boolean ifNotExists;

    @Override
    public CompletableFuture<Void> index(Guarantee guarantee) {
        return documentStore.index(value, id, collection, start, end, metadata, guarantee, ifNotExists);
    }

    @Override
    public SerializedDocument toDocument() {
        return documentStore.getSerializer()
                .toDocument(value, id.toString(), determineSearchCollection(collection), start, end);
    }

    @Override
    public IndexOperation copy() {
        return new DefaultIndexOperation(documentStore, value, collection, id, metadata, start, end, ifNotExists);
    }
}
