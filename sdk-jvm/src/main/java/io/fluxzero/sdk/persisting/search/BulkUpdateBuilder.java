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
import io.fluxzero.common.api.search.BulkUpdate;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fluent builder for applying multiple updates to a single document collection.
 * <p>
 * The builder stores update operations in order and applies them through the document store's
 * {@link DocumentStore#bulkUpdate(java.util.Collection, Guarantee) bulk update API} when {@link #execute()} is called.
 * Document IDs, timestamps, and collection metadata are inferred in the same way as
 * {@link DocumentStore#prepareIndex(Object)}, while the collection is fixed by the builder.
 *
 * <pre>{@code
 * Fluxzero.bulkUpdate("my_collection")
 *         .index(doc1)
 *         .delete(doc2Id)
 *         .execute();
 * }</pre>
 *
 * @see DocumentStore#bulkUpdate(Object)
 */
public interface BulkUpdateBuilder {

    /**
     * Adds one or more index operations. If {@code object} is a collection or object array, each element is indexed
     * separately.
     */
    BulkUpdateBuilder index(@NonNull Object object);

    /**
     * Adds an index operation using the supplied document ID.
     */
    default BulkUpdateBuilder index(@NonNull Object object, @NonNull Object id) {
        return index(object, id, null, null);
    }

    /**
     * Adds an index operation using the supplied document ID and timestamp.
     */
    default BulkUpdateBuilder index(@NonNull Object object, @NonNull Object id, Instant timestamp) {
        return index(object, id, timestamp, timestamp);
    }

    /**
     * Adds an index operation using the supplied document ID and time range.
     */
    BulkUpdateBuilder index(@NonNull Object object, @NonNull Object id, Instant begin, Instant end);

    /**
     * Adds one or more conditional index operations. Existing documents with the same ID will not be overwritten.
     */
    BulkUpdateBuilder indexIfNotExists(@NonNull Object object);

    /**
     * Adds a conditional index operation using the supplied document ID.
     */
    default BulkUpdateBuilder indexIfNotExists(@NonNull Object object, @NonNull Object id) {
        return indexIfNotExists(object, id, null, null);
    }

    /**
     * Adds a conditional index operation using the supplied document ID and timestamp.
     */
    default BulkUpdateBuilder indexIfNotExists(@NonNull Object object, @NonNull Object id, Instant timestamp) {
        return indexIfNotExists(object, id, timestamp, timestamp);
    }

    /**
     * Adds a conditional index operation using the supplied document ID and time range.
     */
    BulkUpdateBuilder indexIfNotExists(@NonNull Object object, @NonNull Object id, Instant begin, Instant end);

    /**
     * Adds a delete operation for the given document ID.
     */
    BulkUpdateBuilder delete(@NonNull Object id);

    /**
     * Returns the currently configured update operations.
     */
    List<BulkUpdate> toBulkUpdates();

    /**
     * Applies the configured update operations using {@link Guarantee#STORED}.
     */
    default CompletableFuture<Void> execute() {
        return execute(Guarantee.STORED);
    }

    /**
     * Applies the configured update operations using the given guarantee.
     */
    CompletableFuture<Void> execute(Guarantee guarantee);

    /**
     * Applies the configured update operations using {@link Guarantee#NONE}.
     */
    default void executeAndForget() {
        execute(Guarantee.NONE);
    }

    /**
     * Applies the configured update operations using {@link Guarantee#STORED} and waits for completion.
     */
    @SneakyThrows
    default void executeAndWait() {
        executeAndWait(Guarantee.STORED);
    }

    /**
     * Applies the configured update operations using the given guarantee and waits for completion.
     */
    @SneakyThrows
    default void executeAndWait(Guarantee guarantee) {
        execute(guarantee).get();
    }
}
