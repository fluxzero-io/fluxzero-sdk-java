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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.Registration;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.CacheEviction;
import lombok.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * A namespace-specific view of a shared cache used by aggregate repositories.
 */
final class RepositoryCache implements Cache {

    private static final ThreadLocal<LookupKeyPool> LOOKUP_KEYS =
            ThreadLocal.withInitial(LookupKeyPool::new);

    private final Cache delegate;
    private final String component;
    private final String namespace;
    private boolean populated;
    private boolean writeStarted;
    private long replayMinIndex;
    private LongSupplier replayMinIndexSupplier;
    private LongConsumer firstWriteListener;

    RepositoryCache(Cache delegate, String component, String namespace) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.component = Objects.requireNonNull(component, "component");
        this.namespace = namespace;
    }

    @Override
    public Object put(Object id, @NonNull Object value) {
        markWriteStarted();
        Object result = delegate.put(key(id), value);
        markPopulated(value);
        return result;
    }

    @Override
    public Object putIfAbsent(Object id, @NonNull Object value) {
        markWriteStarted();
        Object result = delegate.putIfAbsent(key(id), value);
        markPopulated(value);
        return result;
    }

    @Override
    public <T> T computeIfAbsent(Object id, Function<? super Object, T> mappingFunction) {
        markWriteStarted();
        T result = delegate.computeIfAbsent(key(id), ignored -> mappingFunction.apply(id));
        markPopulated(result);
        return result;
    }

    @Override
    public <T> T computeIfPresent(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        T result = delegate.computeIfPresent(key(id), (ignored, value) -> mappingFunction.apply(id, value));
        markPopulated(result);
        return result;
    }

    @Override
    public <T> T compute(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        markWriteStarted();
        T result = delegate.compute(key(id), (ignored, value) -> mappingFunction.apply(id, value));
        markPopulated(result);
        return result;
    }

    @Override
    public <T> void mergeAll(
            Map<?, ? extends T> values,
            BiFunction<? super T, ? super T, ? extends T> mergeFunction) {
        if (values.isEmpty()) {
            return;
        }
        markWriteStarted();
        LinkedHashMap<CacheKey, T> namespaced =
                new LinkedHashMap<>(
                        (int) Math.min(
                                Integer.MAX_VALUE,
                                (long) values.size()
                                * 4L / 3L + 1L));
        values.forEach(
                (id, value) ->
                        namespaced.put(
                                key(id), value));
        delegate.mergeAll(
                namespaced,
                mergeFunction);
        for (CacheKey cacheKey : namespaced.keySet()) {
            Object retained =
                    delegate.get(cacheKey);
            if (retained != null) {
                markPopulated(retained);
                break;
            }
        }
    }

    @Override
    public <T> void updateAll(
            Map<?, ? extends Function<? super T, ? extends T>> updates) {
        if (updates.isEmpty()) {
            return;
        }
        markWriteStarted();
        LinkedHashMap<CacheKey, Function<? super T, ? extends T>> namespaced =
                new LinkedHashMap<>(
                        (int) Math.min(
                                Integer.MAX_VALUE,
                                (long) updates.size()
                                * 4L / 3L + 1L));
        updates.forEach(
                (id, update) ->
                        namespaced.put(
                                key(id), update));
        delegate.updateAll(namespaced);
        for (CacheKey cacheKey : namespaced.keySet()) {
            Object retained = delegate.get(cacheKey);
            if (retained != null) {
                markPopulated(retained);
                break;
            }
        }
    }

    @Override
    public <U, T> void updateAll(
            Iterable<? extends U> updates,
            Function<? super U, ?> keyFunction,
            BiFunction<? super U, ? super T, ? extends T> updateFunction) {
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(keyFunction, "keyFunction");
        Objects.requireNonNull(updateFunction, "updateFunction");
        markWriteStarted();
        CacheKey lookupKey =
                new CacheKey(
                        component, namespace, null);
        Object[] populatedValue = new Object[1];
        delegate.<U, T>updateAll(
                updates,
                update -> lookupKey.forId(
                        keyFunction.apply(update)),
                update -> key(
                        keyFunction.apply(update)),
                (update, current) -> {
                    T next = updateFunction.apply(update, current);
                    if (next != null && populatedValue[0] == null) {
                        populatedValue[0] = next;
                    }
                    return next;
                });
        markPopulated(populatedValue[0]);
    }

    @Override
    public <T> void modifyEach(BiFunction<? super Object, ? super T, ? extends T> modifierFunction) {
        delegate.<T>modifyEach((id, value) -> isOwnKey(id)
                ? modifierFunction.apply(((CacheKey) id).id(), value) : value);
    }

    @Override
    public <T> T get(Object id) {
        LookupKeyPool pool = LOOKUP_KEYS.get();
        CacheKey lookupKey = pool.acquire(component, namespace, id);
        try {
            return delegate.get(lookupKey);
        } finally {
            pool.release(lookupKey);
        }
    }

    @Override
    public <U, T> void supplyAll(
            Iterable<? extends U> lookups,
            Function<? super U, ?> keyFunction,
            BiConsumer<? super U, ? super T> valueConsumer) {
        LookupKeyPool pool = LOOKUP_KEYS.get();
        CacheKey lookupKey = pool.acquire(component, namespace, null);
        try {
            delegate.supplyAll(
                    lookups,
                    lookup -> lookupKey.forId(
                            keyFunction.apply(lookup)),
                    valueConsumer);
        } finally {
            pool.release(lookupKey);
        }
    }

    @Override
    public boolean containsKey(Object id) {
        LookupKeyPool pool = LOOKUP_KEYS.get();
        CacheKey lookupKey = pool.acquire(component, namespace, id);
        try {
            return delegate.containsKey(lookupKey);
        } finally {
            pool.release(lookupKey);
        }
    }

    @Override
    public <T> T remove(Object id) {
        return delegate.remove(key(id));
    }

    @Override
    public void clear() {
        delegate.<Object>modifyEach((id, value) -> isOwnKey(id) ? null : value);
    }

    @Override
    public int size() {
        AtomicInteger result = new AtomicInteger();
        delegate.<Object>modifyEach((id, value) -> {
            if (isOwnKey(id)) {
                result.incrementAndGet();
            }
            return value;
        });
        return result.get();
    }

    @Override
    public Registration registerEvictionListener(Consumer<CacheEviction> listener) {
        return delegate.registerEvictionListener(event -> {
            if (isOwnKey(event.getId())) {
                listener.accept(new CacheEviction(((CacheKey) event.getId()).id(), event.getValue(), event.getReason()));
            } else if (event.getId() == null) {
                listener.accept(event);
            }
        });
    }

    @Override
    public Cache rebuild() {
        return new RepositoryCache(delegate.rebuild(), component, namespace);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private CacheKey key(Object id) {
        return new CacheKey(component, namespace, id);
    }

    private boolean isOwnKey(Object id) {
        return id instanceof CacheKey key
               && component.equals(key.component())
               && Objects.equals(namespace, key.namespace());
    }

    void onFirstWrite(LongSupplier replayMinIndexSupplier, LongConsumer listener) {
        boolean runImmediately;
        long minIndex;
        synchronized (this) {
            this.replayMinIndexSupplier = replayMinIndexSupplier;
            runImmediately = populated;
            if (runImmediately && !writeStarted) {
                writeStarted = true;
                replayMinIndex = replayMinIndexSupplier.getAsLong();
            }
            minIndex = replayMinIndex;
            if (!runImmediately) {
                LongConsumer previous = firstWriteListener;
                firstWriteListener = previous == null ? listener : index -> {
                    previous.accept(index);
                    listener.accept(index);
                };
            }
        }
        if (runImmediately) {
            listener.accept(minIndex);
        }
    }

    private void markWriteStarted() {
        synchronized (this) {
            if (!writeStarted) {
                writeStarted = true;
                replayMinIndex = replayMinIndexSupplier == null ? -1L : replayMinIndexSupplier.getAsLong();
            }
        }
    }

    private void markPopulated(Object value) {
        if (value == null) {
            return;
        }
        markWriteStarted();
        LongConsumer listener;
        long minIndex;
        synchronized (this) {
            if (populated) {
                return;
            }
            populated = true;
            listener = firstWriteListener;
            firstWriteListener = null;
            minIndex = replayMinIndex;
        }
        if (listener != null) {
            listener.accept(minIndex);
        }
    }

    private static final class CacheKey {
        private String component;
        private String namespace;
        private Object id;
        private int hashCode;

        private CacheKey(String component, String namespace, Object id) {
            forLookup(component, namespace, id);
        }

        private CacheKey forLookup(String component, String namespace, Object id) {
            this.component = component;
            this.namespace = namespace;
            return forId(id);
        }

        private CacheKey forId(Object id) {
            this.id = id;
            int hash = Objects.hashCode(component);
            hash = 31 * hash + Objects.hashCode(namespace);
            this.hashCode = 31 * hash + Objects.hashCode(id);
            return this;
        }

        private String component() {
            return component;
        }

        private String namespace() {
            return namespace;
        }

        private Object id() {
            return id;
        }

        private void clear() {
            component = null;
            namespace = null;
            id = null;
            hashCode = 0;
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof CacheKey other
                   && hashCode == other.hashCode
                   && same(component, other.component)
                   && same(namespace, other.namespace)
                   && same(id, other.id);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        private static boolean same(
                Object first,
                Object second) {
            return first == second
                   || first != null
                      && first.equals(second);
        }
    }

    /**
     * Provides reusable lookup keys without retaining repository scopes or identifiers in handler threads. A small
     * stack keeps nested cache access from mutating an outer lookup key while a custom key is computing equality.
     */
    private static final class LookupKeyPool {
        private CacheKey[] keys = new CacheKey[1];
        private int depth;

        private CacheKey acquire(String component, String namespace, Object id) {
            if (depth == keys.length) {
                CacheKey[] expanded = new CacheKey[keys.length << 1];
                System.arraycopy(keys, 0, expanded, 0, keys.length);
                keys = expanded;
            }
            int index = depth++;
            CacheKey result = keys[index];
            if (result == null) {
                result = new CacheKey(null, null, null);
                keys[index] = result;
            }
            try {
                return result.forLookup(component, namespace, id);
            } catch (RuntimeException | Error e) {
                release(result);
                throw e;
            }
        }

        private void release(CacheKey key) {
            int index = --depth;
            if (keys[index] != key) {
                throw new IllegalStateException("Lookup keys must be released in reverse acquisition order");
            }
            key.clear();
        }
    }
}
