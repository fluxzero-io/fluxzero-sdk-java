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
import io.fluxzero.sdk.modeling.ModelRoot;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Keeps model cache visibility separate from a delegate's physical publication. No delegate operation runs under a
 * bookkeeping lock. Overlapping writes conservatively discard their cache result; ordinary reads validate the same
 * publication before and after accessing the delegate. Values and keys passed to configured caches are unchanged.
 */
@Slf4j
final class ModelCache implements Cache {
    // Construction-only registry. Weak references break the cache/listener/view cycle; equality is by cache identity.
    private static final List<SharedView> SHARED = new ArrayList<>();

    static ModelCache shared(Cache source, String namespace) {
        synchronized (SHARED) {
            ModelCache existing = findShared(source, namespace);
            if (existing != null) {
                existing.sharedUsers++;
                return existing;
            }
        }
        ModelCache candidate = new ModelCache(new RepositoryCache(source, "$Model", namespace));
        ModelCache result;
        synchronized (SHARED) {
            result = findShared(source, namespace);
            if (result == null) {
                candidate.sharedUsers = 1;
                candidate.shared = true;
                SHARED.add(new SharedView(new WeakReference<>(source), namespace, new WeakReference<>(candidate)));
                return candidate;
            }
            result.sharedUsers++;
        }
        candidate.evictionRegistration.cancel();
        return result;
    }

    private static ModelCache findShared(Cache source, String namespace) {
        SHARED.removeIf(view -> view.source.get() == null || view.cache.get() == null);
        for (SharedView view : SHARED) {
            if (view.source.get() == source && Objects.equals(view.namespace, namespace)) {
                ModelCache cached = view.cache.get();
                if (cached != null) {
                    return cached;
                }
            }
        }
        return null;
    }

    private final Cache delegate;
    private final ConcurrentHashMap<Object, State> states = new ConcurrentHashMap<>();
    private final Registration evictionRegistration;
    private volatile Epoch epoch = new Epoch(false);
    private int clearing;
    private volatile int sharedUsers;
    private boolean shared;
    private final AtomicInteger activeOperations = new AtomicInteger();
    private final java.util.Set<Object> pendingRemovals = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pendingClear = new AtomicBoolean();
    private final AtomicBoolean cleaning = new AtomicBoolean();

    ModelCache(Cache delegate) {
        this.delegate = delegate;
        evictionRegistration = delegate.registerEvictionListener(event -> {
            if (event.getId() == null) {
                invalidateAll();
            } else if (publishing(event.getId()) || !delegate.containsKey(event.getId())) {
                // Delayed eviction notifications must not retire a successfully loaded replacement.
                invalidate(event.getId());
            }
            releaseUnusedView();
        });
    }

    void releaseShared() {
        synchronized (SHARED) {
            if (--sharedUsers == 0) {
                synchronized (this) {
                    epoch = new Epoch(clearing > 0);
                }
            }
        }
        pruneObsoleteStates();
        releaseUnusedView();
    }

    private void releaseUnusedView() {
        if (shared && sharedUsers == 0 && states.isEmpty() && activeOperations.get() == 0
            && !cleaning.get() && !pendingClear.get() && pendingRemovals.isEmpty()) {
            boolean release = false;
            synchronized (SHARED) {
                if (sharedUsers == 0 && states.isEmpty() && activeOperations.get() == 0
                    && !cleaning.get() && !pendingClear.get() && pendingRemovals.isEmpty()
                    && activeOperations.compareAndSet(0, -1)) {
                    release = SHARED.removeIf(view -> view.cache.get() == this);
                }
            }
            if (release) {
                evictionRegistration.cancel();
            }
        }
    }

    private boolean enterOperation() {
        int active;
        do {
            active = activeOperations.get();
            if (active < 0) {
                return false;
            }
        } while (!activeOperations.compareAndSet(active, active + 1));
        return true;
    }

    private void exitOperation() {
        activeOperations.decrementAndGet();
        releaseUnusedView();
    }

    ReadToken beginRead(Object id) {
        if (!enterOperation()) {
            return new ReadToken(new State(id), -1L, epoch);
        }
        try {
            ReadToken[] result = new ReadToken[1];
            states.compute(id, (key, current) -> {
                State state = current == null ? new State(key) : current;
                state.readers++;
                result[0] = new ReadToken(state, state.version, epoch);
                return state;
            });
            return result[0];
        } finally {
            exitOperation();
        }
    }

    /** Publishes only if no writer or invalidation superseded this read after it began. */
    Stamp publish(ReadToken token, Object value, long readBoundary) {
        Write write = beginWrite(token.state.id, token, false, true, Math.max(readBoundary, stateIndex(value)));
        if (write == null) {
            return null;
        }
        try {
            delegate.put(token.state.id, value);
            write.boundary = Math.max(readBoundary, stateIndex(value));
            write.success = true;
        } finally {
            finish(write);
        }
        return token.published = write.published;
    }

    Stamp stamp(Object id) {
        State state = states.get(id);
        return state == null ? null : current(state.stamp);
    }

    private Stamp current(Stamp stamp) {
        return stamp != null && stamp.epoch == epoch && !stamp.epoch.blocked
               && stamp.state.stamp == stamp ? stamp : null;
    }

    boolean isCurrent(Stamp stamp) {
        return current(stamp) != null;
    }

    boolean publishing(Object id) {
        State state = states.get(id);
        return epoch.blocked || state != null && state.active;
    }

    <T> T get(Object id, Stamp expected) {
        if (current(expected) == null) {
            return null;
        }
        T result = delegate.get(id);
        if (current(expected) == null) {
            return null;
        }
        if (result == null) {
            invalidate(id, expected);
        }
        return result;
    }

    @Override
    public <T> T get(Object id) {
        return get(id, stamp(id));
    }

    @Override
    public boolean containsKey(Object id) {
        Stamp expected = stamp(id);
        if (expected == null) {
            return false;
        }
        boolean present = delegate.containsKey(id);
        if (current(expected) == null) {
            return false;
        }
        if (!present) {
            invalidate(id, expected);
        }
        return present;
    }

    void invalidate(Object id) {
        invalidate(id, null);
    }

    private void invalidate(Object id, Stamp expected) {
        states.computeIfPresent(id, (key, state) -> {
            if (expected == null || state.stamp == expected) {
                state.stamp = null;
                state.version++;
                state.invalid = true;
            }
            return retain(state);
        });
    }

    void invalidateAll() {
        synchronized (this) {
            epoch = new Epoch(clearing > 0);
        }
        pruneObsoleteStates();
    }

    private void pruneObsoleteStates() {
        states.forEach((id, ignored) -> states.computeIfPresent(id, (key, state) -> {
            if (state.stamp != null && state.stamp.epoch != epoch) {
                state.stamp = null;
                state.version++;
                state.invalid = true;
            }
            return retain(state);
        }));
    }

    private Write beginWrite(Object id, ReadToken expected) {
        return beginWrite(id, expected, false, true, Long.MAX_VALUE);
    }

    private Write beginWrite(Object id, ReadToken expected, boolean cleanup) {
        return beginWrite(id, expected, cleanup, true, Long.MAX_VALUE);
    }

    private Write beginWrite(Object id, ReadToken expected, boolean cleanup, boolean counted, long readBoundary) {
        if (counted && !enterOperation()) {
            return null;
        }
        Write[] result = new Write[1];
        try {
        states.compute(id, (key, current) -> {
            State state = current == null ? new State(key) : current;
            Epoch observed = epoch;
            if (cleanup && current(state.stamp) != null) {
                return state;
            }
            if (expected != null && (expected.state != state || expected.version != state.version
                                     || expected.epoch != observed || observed.blocked || state.writers != 0)) {
                return retain(state);
            }
            Stamp previous = current(state.stamp);
            if (expected != null && previous != null && previous.boundary > readBoundary) {
                return state;
            }
            if (state.writers++ == 0) {
                state.invalid = observed.blocked;
                state.needsCleanup = false;
                state.active = true;
            } else {
                state.invalid = true;
            }
            state.version++;
            state.stamp = null;
            result[0] = new Write(state, observed, previous);
            result[0].cleanup = cleanup;
            result[0].counted = counted;
            return state;
        });
        return result[0];
        } finally {
            if (counted && result[0] == null) {
                exitOperation();
            }
        }
    }

    private void finish(Write write) {
        boolean admitted = false;
        boolean presenceKnown = false;
        try {
            // Keep this writer visible during admission checks and cleanup, including callbacks on other threads.
            admitted = delegate.containsKey(write.state.id);
            presenceKnown = true;
        } finally {
            boolean accepted = write.success && admitted;
            boolean mayContainObsoleteValue = admitted || !presenceKnown;
            boolean[] cleanupNeeded = new boolean[1];
            states.computeIfPresent(write.state.id, (key, state) -> {
                if (!accepted || write.epoch != epoch) {
                    state.invalid = true;
                }
                if (state.invalid && !write.cleanup && mayContainObsoleteValue) {
                    state.needsCleanup = true;
                }
                if (--state.writers == 0) {
                    // A read started while this group was active cannot publish after the group finishes.
                    state.version++;
                    state.active = false;
                    if (!state.invalid) {
                        write.published = state.stamp = new Stamp(state, epoch, write.boundary);
                    } else if (state.needsCleanup) {
                        cleanupNeeded[0] = true;
                    }
                }
                return retain(state);
            });
            if (cleanupNeeded[0]) {
                pendingRemovals.add(write.state.id);
                scheduleCleanup();
            }
            if (write.counted) {
                exitOperation();
            }
            releaseUnusedView();
        }
    }

    private State retain(State state) {
        return state.writers > 0 || state.readers > 0 || state.stamp != null ? state : null;
    }

    private static long stateIndex(Object value) {
        return value instanceof ModelRoot<?> model ? model.stateIndex() : -1L;
    }

    private <T> T select(Write write, T physical, Function<? super T, ? extends T> update, long boundary) {
        T previous = !write.selected && write.previous == null || write.state.invalid || write.epoch != epoch
                ? null : physical;
        T selected = update.apply(previous);
        if (previous != null && write.previous != null && stateIndex(previous) < 0L
            && stateIndex(selected) >= 0L && stateIndex(selected) < write.previous.boundary) {
            selected = previous;
        }
        write.boundary = Math.max(stateIndex(selected), selected == previous
                ? write.selected ? write.boundary : write.previous == null ? boundary : write.previous.boundary
                : boundary);
        write.selected = true;
        return selected;
    }

    @Override
    public Object put(Object id, Object value) {
        Write write = beginWrite(id, null);
        if (write == null) {
            return null;
        }
        try {
            Object previous = delegate.put(id, value);
            write.boundary = stateIndex(value);
            write.success = true;
            return previous;
        } finally {
            finish(write);
        }
    }

    @Override
    public Object putIfAbsent(Object id, Object value) {
        Object[] previous = new Object[1];
        compute(id, (key, current) -> {
            previous[0] = current;
            return current == null ? value : current;
        });
        return previous[0];
    }

    @Override
    public <T> T computeIfAbsent(Object id, Function<? super Object, T> mappingFunction) {
        return this.<T>compute(id, (key, current) -> current == null ? mappingFunction.apply(key) : current);
    }

    @Override
    public <T> T computeIfPresent(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        return this.<T>compute(id, (key, current) -> current == null ? null : mappingFunction.apply(key, current));
    }

    @Override
    public <T> T compute(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        Write write = beginWrite(id, null);
        if (write == null) {
            return null;
        }
        try {
            T result = delegate.<T>compute(id, (key, value) -> select(
                    write, value, current -> mappingFunction.apply(key, current), -1L));
            write.success = true;
            return result;
        } finally {
            finish(write);
        }
    }

    @Override
    public <T> void mergeAll(Map<?, ? extends T> values, BiFunction<? super T, ? super T, ? extends T> mergeFunction) {
        this.<Map.Entry<?, ? extends T>, T>updateAll(values.entrySet(), Map.Entry::getKey,
                  (candidate, current) -> mergeFunction.apply(current, candidate.getValue()));
    }

    @Override
    public <T> void updateAll(Map<?, ? extends Function<? super T, ? extends T>> updates) {
        this.<Map.Entry<?, ? extends Function<? super T, ? extends T>>, T>updateAll(
                updates.entrySet(), Map.Entry::getKey, (update, current) -> update.getValue().apply(current));
    }

    @Override
    public <U, T> void updateAll(Iterable<? extends U> updates, Function<? super U, ?> keyFunction,
                               BiFunction<? super U, ? super T, ? extends T> updateFunction) {
        updateAll(updates, keyFunction, updateFunction, null, -1L);
    }

    <U, T> void updateAll(Iterable<? extends U> updates, Function<? super U, ?> keyFunction,
                         BiFunction<? super U, ? super T, ? extends T> updateFunction,
                         Map<?, ReadToken> expected, long readBoundary) {
        List<U> batch = new ArrayList<>();
        Map<Object, Write> writes = new LinkedHashMap<>();
        if (!enterOperation()) {
            return;
        }
        try {
            for (U update : updates) {
                Object id = keyFunction.apply(update);
                if (!writes.containsKey(id)) {
                    if (expected != null && !expected.containsKey(id)) {
                        throw new IllegalArgumentException("Missing read token for " + id);
                    }
                    writes.put(id, beginWrite(id, expected == null ? null : expected.get(id), false, false,
                                              expected == null ? Long.MAX_VALUE : readBoundary));
                }
                if (writes.get(id) != null) {
                    batch.add(update);
                }
            }
            delegate.<U, T>updateAll(batch, keyFunction, (update, physical) -> {
                Write write = writes.get(keyFunction.apply(update));
                return select(write, physical, current -> updateFunction.apply(update, current), readBoundary);
            });
            writes.values().forEach(write -> {
                if (write != null) {
                    write.success = true;
                }
            });
        } finally {
            Throwable failure = null;
            for (Map.Entry<Object, Write> entry : writes.entrySet()) {
                Write write = entry.getValue();
                if (write != null) {
                    try {
                        finish(write);
                        if (expected != null) {
                            expected.get(entry.getKey()).published = write.published;
                        }
                    } catch (RuntimeException | Error e) {
                        if (failure == null) {
                            failure = e;
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                }
            }
            exitOperation();
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    @Override
    public <T> void modifyEach(BiFunction<? super Object, ? super T, ? extends T> modifierFunction) {
        // This path is not used for model publication. Keep the delegate traversal while fencing all its effects.
        if (!enterOperation()) {
            return;
        }
        beginClear();
        try {
            delegate.modifyEach(modifierFunction);
        } finally {
            endClear();
            exitOperation();
        }
    }

    @Override
    public <T> T remove(Object id) {
        invalidate(id);
        if (activeOperations.get() > 0) {
            pendingRemovals.add(id);
            scheduleCleanup();
            return null;
        }
        return removeNow(id);
    }

    private <T> T removeNow(Object id) {
        Write write = beginWrite(id, null);
        if (write == null) {
            return null;
        }
        write.cleanup = true;
        try {
            return delegate.remove(id);
        } finally {
            finish(write);
        }
    }

    @Override
    public void clear() {
        invalidateAll();
        if (activeOperations.get() > 0) {
            pendingClear.set(true);
            scheduleCleanup();
        } else {
            clearNow();
        }
    }

    private void clearNow() {
        if (!enterOperation()) {
            return;
        }
        beginClear();
        try {
            delegate.clear();
        } finally {
            endClear();
            exitOperation();
        }
    }

    private void clearInvalidatedNow() {
        if (!enterOperation()) {
            return;
        }
        java.util.Queue<Write> cleanups = new java.util.concurrent.ConcurrentLinkedQueue<>();
        try {
            delegate.modifyEach((id, value) -> {
                Write cleanup = beginWrite(id, null, true, false, Long.MAX_VALUE);
                if (cleanup == null) {
                    return value;
                }
                cleanups.add(cleanup);
                return null;
            });
        } finally {
            try {
                finishAll(cleanups);
            } finally {
                exitOperation();
            }
        }
    }

    private void finishAll(Iterable<Write> writes) {
        Throwable failure = null;
        for (Write write : writes) {
            try {
                finish(write);
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void scheduleCleanup() {
        if (cleaning.compareAndSet(false, true)) {
            Thread.ofVirtual().name("fluxzero-model-cache-cleanup").start(() -> {
                try {
                    if (pendingClear.getAndSet(false)) {
                        clearInvalidatedNow();
                    }
                    for (Object id : pendingRemovals) {
                        if (pendingRemovals.remove(id)) {
                            Write cleanup = beginWrite(id, null, true);
                            if (cleanup != null) {
                                try {
                                    delegate.remove(id);
                                } finally {
                                    finish(cleanup);
                                }
                            }
                        }
                    }
                } catch (Throwable failure) {
                    // Visibility is already invalidated. Do not spin forever if a configured cache fails cleanup.
                    pendingClear.set(false);
                    pendingRemovals.clear();
                    log.warn("Failed to remove invalidated model cache values", failure);
                } finally {
                    cleaning.set(false);
                    if (pendingClear.get() || !pendingRemovals.isEmpty()) {
                        scheduleCleanup();
                    } else {
                        releaseUnusedView();
                    }
                }
            });
        }
    }

    private synchronized void beginClear() {
        clearing++;
        epoch = new Epoch(true);
    }

    private void endClear() {
        synchronized (this) {
            epoch = new Epoch(--clearing > 0);
        }
        pruneObsoleteStates();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Registration registerEvictionListener(Consumer<CacheEviction> listener) {
        return delegate.registerEvictionListener(listener);
    }

    @Override
    public Cache rebuild() {
        return new ModelCache(delegate.rebuild());
    }

    @Override
    public void close() {
        invalidateAll();
        evictionRegistration.cancel();
        delegate.close();
    }

    final class ReadToken implements AutoCloseable {
        private final State state;
        private final long version;
        private final Epoch epoch;
        private boolean closed;
        private Stamp published;

        Stamp published() {
            return published;
        }

        boolean forId(Object id) {
            return state.id.equals(id);
        }

        private ReadToken(State state, long version, Epoch epoch) {
            this.state = state;
            this.version = version;
            this.epoch = epoch;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                states.computeIfPresent(state.id, (key, current) -> {
                    if (current == state) {
                        current.readers--;
                    }
                    return retain(current);
                });
                releaseUnusedView();
            }
        }
    }

    static final class Stamp {
        private final State state;
        private final Epoch epoch;
        final long boundary;

        private Stamp(State state, Epoch epoch, long boundary) {
            this.state = state;
            this.epoch = epoch;
            this.boundary = boundary;
        }
    }

    private static final class State {
        private final Object id;
        private volatile Stamp stamp;
        private volatile boolean active;
        private volatile boolean invalid;
        private long version;
        private int readers;
        private int writers;
        private boolean needsCleanup;

        private State(Object id) {
            this.id = id;
        }
    }

    private static final class Write {
        private final State state;
        private final Epoch epoch;
        private final Stamp previous;
        private long boundary = -1L;
        private boolean success;
        private boolean selected;
        private boolean cleanup;
        private boolean counted;
        private Stamp published;

        private Write(State state, Epoch epoch, Stamp previous) {
            this.state = state;
            this.epoch = epoch;
            this.previous = previous;
        }
    }

    private record Epoch(boolean blocked) {
    }

    private record SharedView(WeakReference<Cache> source, String namespace, WeakReference<ModelCache> cache) {
    }
}
