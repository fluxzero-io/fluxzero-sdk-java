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

import lombok.AllArgsConstructor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

@AllArgsConstructor
public final class DefaultMemoization implements Memoization {
    private static final Object nullKey = new Object();
    private final Clock clock;
    private final ConcurrentHashMap<Object, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void put(Object key, Object value, Duration lifespan) {
        entries.put(normalizeKey(key), createEntry(value, lifespan));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> V compute(Object key, K suppliedKey, BiFunction<K, V, V> supplier, Duration lifespan) {
        Entry entry = entries.compute(normalizeKey(key), (k, current) -> {
            Entry active = getActiveEntry(k, current);
            V value = supplier.apply(suppliedKey, active == null ? null : (V) active.value);
            return createEntry(value, lifespan);
        });
        return (V) entry.value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> V computeIfAbsent(Object key, K suppliedKey, Function<K, V> supplier, Duration lifespan) {
        Entry entry = entries.compute(normalizeKey(key), (k, current) -> {
            Entry active = getActiveEntry(k, current);
            if (active != null) {
                return active;
            }
            return createEntry(supplier.apply(suppliedKey), lifespan);
        });
        return (V) entry.value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V get(Object key) {
        Object normalizedKey = normalizeKey(key);
        Entry entry = getActiveEntry(normalizedKey, entries.get(normalizedKey));
        return entry == null ? null : (V) entry.value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V remove(Object key) {
        Object normalizedKey = normalizeKey(key);
        Entry entry = entries.remove(normalizedKey);
        entry = getActiveEntry(normalizedKey, entry);
        return entry == null ? null : (V) entry.value;
    }

    @Override
    public void clear() {
        entries.clear();
    }

    private Entry getActiveEntry(Object key, Entry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.expiry != null && !entry.expiry.isAfter(clock.instant())) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    private Entry createEntry(Object value, Duration lifespan) {
        Instant expiry = lifespan == null ? null : clock.instant().plus(lifespan);
        return new Entry(value, expiry);
    }

    private Object normalizeKey(Object key) {
        return key == null ? nullKey : key;
    }

    private record Entry(Object value, Instant expiry) {
    }
}
