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

package io.fluxzero.sdk.persisting.keyvalue;

import io.fluxzero.common.Guarantee;
import io.fluxzero.sdk.common.Namespaced;

import java.util.concurrent.CompletableFuture;

/**
 * A simple interface for storing, retrieving, and removing key-value pairs.
 * <p>
 * This interface provides basic persistence operations such as storing values (with optional guarantees),
 * retrieving them by key, conditionally storing only if absent, and deleting by key.
 * Writes return futures and do not block. Use an explicit {@link Guarantee#STORED} and await its future when
 * another operation depends on durable storage. Active tracking batches await registered write completions.
 *
 * <p><strong>Note:</strong> This API is considered legacy in the Fluxzero Runtime. It is recommended
 * to use the more advanced and flexible {@code DocumentStore} instead, which supports structured querying,
 * indexing, updates, and document lifecycle features.
 *
 * <p>This interface is still supported internally for backward compatibility and simple data use cases.
 *
 * @see io.fluxzero.sdk.persisting.search.DocumentStore
 */
public interface KeyValueStore extends Namespaced<KeyValueStore> {

    /**
     * Returns this key-value store scoped to the requested namespace.
     *
     * @param namespace the namespace to which the returned store is scoped
     * @return the key-value store associated with the specified namespace
     */
    @Override
    default KeyValueStore forNamespace(String namespace) {
        return this;
    }

    /**
     * Stores a value under the given key with the default {@link Guarantee#DEFAULT} delivery guarantee.
     *
     * @param key   the key to store the value under
     * @param value the value to store
     * @return delivery completion according to the application default
     */
    default CompletableFuture<Void> store(String key, Object value) {
        return store(key, value, Guarantee.DEFAULT);
    }

    /**
     * Stores a value under the given key with the specified delivery guarantee.
     *
     * @param key       the key to store the value under
     * @param value     the value to store
     * @param guarantee the delivery guarantee (DEFAULT, NONE, SENT, or STORED)
     * @return completion according to the selected guarantee
     */
    CompletableFuture<Void> store(String key, Object value, Guarantee guarantee);

    /**
     * Stores a value only if there is no existing value for the specified key. This always requests a stored
     * result; the application delivery default does not weaken the conditional operation.
     *
     * @param key   the key to store the value under
     * @param value the value to store
     * @return a future with {@code true} if the value was stored, {@code false} if the key already had a value
     */
    CompletableFuture<Boolean> storeIfAbsent(String key, Object value);

    /**
     * Retrieves the value associated with the given key.
     *
     * @param key the key to retrieve
     * @param <R> the expected result type
     * @return the stored value, or {@code null} if not found
     */
    <R> R get(String key);

    /**
     * Removes the value associated with the given key.
     *
     * @param key the key to delete
     * @return completion according to the application default
     */
    default CompletableFuture<Void> delete(String key) {
        return delete(key, Guarantee.DEFAULT);
    }

    /**
     * Removes a value asynchronously with the specified delivery guarantee.
     * @param key the key to delete
     * @param guarantee concrete guarantee or DEFAULT
     * @return completion according to the selected guarantee
     */
    CompletableFuture<Void> delete(String key, Guarantee guarantee);
}
