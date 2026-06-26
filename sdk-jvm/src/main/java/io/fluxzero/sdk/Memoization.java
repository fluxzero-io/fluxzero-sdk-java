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

package io.fluxzero.sdk;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Instance-bound memoization store used by {@link Fluxzero} for lightweight caching of values.
 * <p>
 * A {@code Memoization} is scoped to a single {@link Fluxzero} instance. Higher-level concerns such as whether entries
 * are additionally scoped to the calling class or shared globally within that instance are handled by the
 * {@link Fluxzero} static helper methods that construct the effective cache key.
 * <p>
 * Implementations may support expiring entries by lifespan. Expired values are no longer returned, and may be removed
 * eagerly or lazily depending on the implementation.
 */
public interface Memoization {

    /**
     * Stores the given value for the given key, optionally with a lifespan after which it expires.
     *
     * @param key the cache key
     * @param value the value to store, which may be {@code null}
     * @param lifespan the optional lifespan of the value, or {@code null} to keep it until overwritten or cleared
     */
    void put(Object key, Object value, Duration lifespan);

    /**
     * Computes a new value using the current cached value, stores the result, and returns it.
     * <p>
     * If no value is currently cached for the key, or if the cached value has expired, the second argument passed to
     * the supplier will be {@code null}.
     *
     * @param key the internal cache key
     * @param suppliedKey the original key value to pass to the supplier
     * @param supplier computes the next value from the key and current cached value
     * @param lifespan the optional lifespan of the computed value, or {@code null} for no expiry
     * @param <K> the type of the supplied key
     * @param <V> the type of the memoized value
     * @return the stored value
     */
    <K, V> V compute(Object key, K suppliedKey, BiFunction<K, V, V> supplier, Duration lifespan);

    /**
     * Returns the cached value for the given key if present and not expired, otherwise computes, stores, and returns a
     * new value.
     *
     * @param key the internal cache key
     * @param suppliedKey the original key value to pass to the supplier
     * @param supplier computes the value when no valid cached value exists
     * @param lifespan the optional lifespan of the computed value, or {@code null} for no expiry
     * @param <K> the type of the supplied key
     * @param <V> the type of the memoized value
     * @return the cached or newly computed value
     */
    <K, V> V computeIfAbsent(Object key, K suppliedKey, Function<K, V> supplier, Duration lifespan);

    /**
     * Returns the currently cached value for the given key, or {@code null} if none exists or the value has expired.
     *
     * @param key the internal cache key
     * @param <V> the type of the memoized value
     * @return the cached value, or {@code null}
     */
    <V> V get(Object key);

    /**
     * Removes the cached value for the given key and returns it.
     *
     * @param key the internal cache key
     * @param <V> the type of the memoized value
     * @return the removed cached value, or {@code null} if none exists or the value has expired
     */
    <V> V remove(Object key);

    /**
     * Removes all cached values from this memoization store.
     */
    void clear();
}
