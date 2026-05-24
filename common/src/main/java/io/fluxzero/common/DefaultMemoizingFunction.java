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

package io.fluxzero.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * A default implementation of the {@link MemoizingFunction} interface that provides caching functionality for computed
 * function results. The results are cached by key, and can be optionally configured with a time-based expiration
 * policy.
 * <p>
 * This class uses a {@link ConcurrentHashMap} internally for thread-safe storage of cached entries.
 *
 * @param <K> the type of input keys
 * @param <V> the type of values produced by applying the delegate function
 */
public class DefaultMemoizingFunction<K, V> implements MemoizingFunction<K, V> {
    private static final Entry nullValue = new Entry(null);
    private final Function<K, V> delegate;
    private final Duration lifespan;
    private final Clock clock;
    private volatile ConcurrentHashMap<Object, Entry> map;

    public DefaultMemoizingFunction(Function<K, V> delegate) {
        this(delegate, null, null);
    }

    public DefaultMemoizingFunction(Function<K, V> delegate, Duration lifespan, Clock clock) {
        this.delegate = delegate;
        this.lifespan = lifespan;
        this.clock = clock;
    }

    public DefaultMemoizingFunction(ConcurrentHashMap<Object, Entry> map, Function<K, V> delegate, Duration lifespan,
                                    Clock clock) {
        this(delegate, lifespan, clock);
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public V apply(K key) {
        Object normalizedKey = normalizeKey(key);
        ConcurrentHashMap<Object, Entry> currentMap = map;
        if (currentMap != null) {
            Entry cached = currentMap.get(normalizedKey);
            if (cached != null && !isExpired(cached)) {
                return (V) cached.value();
            }
        }
        if (lifespan == null) {
            return (V) map().computeIfAbsent(normalizedKey, k -> loadEntry(key)).value();
        }
        Entry result = map().compute(normalizedKey, (k, current) ->
                current == null || isExpired(current) ? loadEntry(key) : current);
        return (V) result.value();
    }

    private ConcurrentHashMap<Object, Entry> map() {
        ConcurrentHashMap<Object, Entry> result = map;
        if (result == null) {
            synchronized (this) {
                result = map;
                if (result == null) {
                    result = new ConcurrentHashMap<>();
                    map = result;
                }
            }
        }
        return result;
    }

    private Object normalizeKey(K key) {
        return key == null ? nullValue : key;
    }

    private boolean isExpired(Entry entry) {
        return entry.expiry != null && entry.expiry.isBefore(clock.instant());
    }

    private Entry loadEntry(K key) {
        return ofNullable(delegate.apply(key))
                .map(value -> new Entry(value, lifespan == null ? null : clock.instant().plus(lifespan)))
                .orElse(nullValue);
    }

    @Override
    public void clear() {
        ConcurrentHashMap<Object, Entry> currentMap = map;
        if (currentMap != null) {
            currentMap.clear();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(K key) {
        ConcurrentHashMap<Object, Entry> currentMap = map;
        return currentMap == null ? null
                : (V) Optional.ofNullable(currentMap.remove(normalizeKey(key))).map(Entry::value).orElse(null);
    }

    @Override
    public boolean isCached(K key) {
        ConcurrentHashMap<Object, Entry> currentMap = map;
        return currentMap != null && currentMap.containsKey(normalizeKey(key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super V> consumer) {
        ConcurrentHashMap<Object, Entry> currentMap = map;
        if (currentMap != null) {
            currentMap.values().forEach(e -> consumer.accept((V) e.value()));
        }
    }

    record Entry(Object value, Instant expiry) {
        Entry(Object value) {
            this(value, null);
        }
    }
}
