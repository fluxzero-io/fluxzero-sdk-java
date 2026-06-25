/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.caching;

import io.fluxzero.common.Registration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongBiFunction;

/**
 * {@link Cache} implementation backed by the common hard-reference memory-aware cache support.
 * <p>
 * The default constructors are intentionally count-bounded: every cached value counts as one entry, regardless of the
 * size of its object graph. Use the weighted constructor only for values with a reliable size estimate, such as
 * serialized payloads.
 */
public class AdaptiveObjectCache implements Cache {
    public static final int DEFAULT_MAX_SIZE = 1_000_000;

    private static final Duration DEFAULT_EXPIRY_CHECK_DELAY = Duration.ofMinutes(1);
    private static final Object NULL_VALUE = new Object();

    private final long maxWeight;
    private final long maxEntryWeight;
    private final ToLongBiFunction<? super Object, ? super Object> weigher;
    private final MemoryPressureController memoryPressureController;
    private final Duration expiry;
    private final Duration expiryCheckDelay;
    private final Clock clock;
    private final MemoryAwareCacheSupport<Object, CacheEntry> delegate;
    private final ConcurrentHashMap<Object, Instant> deadlines = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryPurger;

    public AdaptiveObjectCache() {
        this(DEFAULT_MAX_SIZE);
    }

    public AdaptiveObjectCache(int maxSize) {
        this(maxSize, MemoryPressureController.jvm());
    }

    /**
     * Constructs a count-bounded cache with a maximum entry age.
     */
    public AdaptiveObjectCache(int maxSize, Duration expiry) {
        this(maxSize, MemoryPressureController.jvm(), expiry);
    }

    public AdaptiveObjectCache(int maxSize, MemoryPressureController memoryPressureController) {
        this(maxSize, memoryPressureController, null);
    }

    /**
     * Constructs a count-bounded cache with memory-pressure trimming and a maximum entry age.
     */
    public AdaptiveObjectCache(int maxSize, MemoryPressureController memoryPressureController, Duration expiry) {
        this(maxSize, 1, (key, value) -> 1, memoryPressureController, expiry);
    }

    public AdaptiveObjectCache(long maxWeight, long maxEntryWeight,
                               ToLongBiFunction<? super Object, ? super Object> weigher,
                               MemoryPressureController memoryPressureController) {
        this(maxWeight, maxEntryWeight, weigher, memoryPressureController, null);
    }

    /**
     * Constructs a weighted cache with memory-pressure trimming and a maximum entry age.
     */
    public AdaptiveObjectCache(long maxWeight, long maxEntryWeight,
                               ToLongBiFunction<? super Object, ? super Object> weigher,
                               MemoryPressureController memoryPressureController, Duration expiry) {
        this(maxWeight, maxEntryWeight, weigher, memoryPressureController, expiry, currentClock());
    }

    public AdaptiveObjectCache(int maxSize, MemoryPressureController memoryPressureController, Duration expiry,
                               Clock clock) {
        this(maxSize, 1, (key, value) -> 1, memoryPressureController, expiry, clock);
    }

    public AdaptiveObjectCache(long maxWeight, long maxEntryWeight,
                               ToLongBiFunction<? super Object, ? super Object> weigher,
                               MemoryPressureController memoryPressureController, Duration expiry, Clock clock) {
        this(maxWeight, maxEntryWeight, weigher, memoryPressureController, expiry, DEFAULT_EXPIRY_CHECK_DELAY,
             clock);
    }

    AdaptiveObjectCache(long maxWeight, long maxEntryWeight,
                        ToLongBiFunction<? super Object, ? super Object> weigher,
                        MemoryPressureController memoryPressureController, Duration expiry, Duration expiryCheckDelay,
                        Clock clock) {
        this.maxWeight = maxWeight;
        this.maxEntryWeight = maxEntryWeight;
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        this.memoryPressureController = Objects.requireNonNull(memoryPressureController, "memoryPressureController");
        this.expiry = expiry;
        this.expiryCheckDelay = expiryCheckDelay;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delegate = new MemoryAwareCacheSupport<>(maxWeight, maxEntryWeight, this::weigh, null,
                                                      memoryPressureController);
        this.delegate.registerEvictionListener(event -> deadlines.remove(event.key()));
        this.expiryPurger = startExpiryPurger();
    }

    @Override
    public synchronized Object put(Object id, Object value) {
        Object previous = get(id);
        putEntry(id, newEntry(value));
        return previous;
    }

    @Override
    public synchronized Object putIfAbsent(Object id, Object value) {
        Object previous = get(id);
        if (previous == null) {
            putEntry(id, newEntry(value));
        }
        return previous;
    }

    @Override
    public synchronized <T> T computeIfAbsent(Object id, Function<? super Object, T> mappingFunction) {
        T current = get(id);
        if (current != null) {
            return current;
        }
        T next = mappingFunction.apply(id);
        if (next == null) {
            return null;
        }
        CacheEntry nextEntry = newEntry(next);
        putEntry(id, nextEntry);
        return unwrap(nextEntry);
    }

    @Override
    public synchronized <T> T computeIfPresent(Object id,
                                               BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        T current = get(id);
        if (current == null) {
            return null;
        }
        T next = mappingFunction.apply(id, current);
        if (next == null) {
            delegate.remove(id);
            return null;
        }
        CacheEntry nextEntry = newEntry(next);
        putEntry(id, nextEntry);
        return unwrap(nextEntry);
    }

    @Override
    public synchronized <T> T compute(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        T next = mappingFunction.apply(id, get(id));
        if (next == null) {
            delegate.remove(id);
            return null;
        }
        CacheEntry nextEntry = newEntry(next);
        putEntry(id, nextEntry);
        return unwrap(nextEntry);
    }

    @Override
    public synchronized <T> void modifyEach(BiFunction<? super Object, ? super T, ? extends T> modifierFunction) {
        purgeExpired();
        delegate.keys().forEach(key -> computeIfPresent(key, modifierFunction));
    }

    @Override
    public synchronized <T> T get(Object id) {
        return unwrap(currentEntry(id));
    }

    @Override
    public synchronized boolean containsKey(Object id) {
        return !expireIfNeeded(id) && delegate.containsKey(id);
    }

    @Override
    public synchronized <T> T remove(Object id) {
        return unwrap(delegate.remove(id));
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
        deadlines.clear();
    }

    @Override
    public synchronized int size() {
        purgeExpired();
        return delegate.size();
    }

    @Override
    public Registration registerEvictionListener(Consumer<CacheEviction> listener) {
        return delegate.registerEvictionListener(event -> listener.accept(new CacheEviction(
                event.key(), unwrap(event.value()), mapReason(event.reason()))));
    }

    @Override
    public Cache rebuild() {
        return new AdaptiveObjectCache(maxWeight, maxEntryWeight, weigher, memoryPressureController, expiry,
                                       expiryCheckDelay, clock);
    }

    @Override
    public void close() {
        if (expiryPurger != null) {
            expiryPurger.shutdownNow();
        }
        delegate.close();
        deadlines.clear();
    }

    public long weight() {
        purgeExpired();
        return delegate.weight();
    }

    public boolean trimForMemoryPressure() {
        purgeExpired();
        return delegate.trimForMemoryPressure();
    }

    private long weigh(Object key, CacheEntry value) {
        return weigher.applyAsLong(key, unwrap(value));
    }

    private CacheEntry newEntry(Object value) {
        return new CacheEntry(value == null ? NULL_VALUE : value);
    }

    private void putEntry(Object id, CacheEntry entry) {
        if (delegate.put(id, entry)) {
            Instant deadline = deadline();
            if (deadline == null) {
                deadlines.remove(id);
            } else {
                deadlines.put(id, deadline);
            }
        } else {
            deadlines.remove(id);
        }
    }

    private CacheEntry currentEntry(Object id) {
        if (expireIfNeeded(id)) {
            return null;
        }
        return delegate.get(id);
    }

    private Instant deadline() {
        return expiry == null ? null : clock.instant().plus(expiry);
    }

    private synchronized void purgeExpired() {
        if (deadlines.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        deadlines.forEach((id, deadline) -> expireIfNeeded(id, deadline, now));
    }

    private boolean expireIfNeeded(Object id) {
        Instant deadline = deadlines.get(id);
        return deadline != null && expireIfNeeded(id, deadline, clock.instant());
    }

    private boolean expireIfNeeded(Object id, Instant deadline, Instant now) {
        if (deadline.isBefore(now)) {
            deadlines.remove(id, deadline);
            delegate.evict(id, MemoryAwareCacheSupportEviction.Reason.expiry);
            return true;
        }
        return false;
    }

    private ScheduledExecutorService startExpiryPurger() {
        if (expiry == null || expiryCheckDelay == null || expiryCheckDelay.isZero()
            || expiryCheckDelay.isNegative()) {
            return null;
        }
        ScheduledExecutorService result = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        long delayMillis = Math.max(1L, expiryCheckDelay.toMillis());
        result.scheduleWithFixedDelay(this::purgeExpired, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T unwrap(CacheEntry entry) {
        if (entry == null) {
            return null;
        }
        Object value = entry.value();
        if (value == NULL_VALUE) {
            return null;
        }
        return (T) (value instanceof Optional<?> optional ? optional.orElse(null) : value);
    }

    private static CacheEviction.Reason mapReason(MemoryAwareCacheSupportEviction.Reason reason) {
        return switch (reason) {
            case manual -> CacheEviction.Reason.manual;
            case size, entryTooLarge -> CacheEviction.Reason.size;
            case memoryPressure -> CacheEviction.Reason.memoryPressure;
            case expiry -> CacheEviction.Reason.expiry;
        };
    }

    private static Clock currentClock() {
        return Clock.systemUTC();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("AdaptiveObjectCache-expiryPurger-", 0L)
                .unstarted(runnable);
    }

    private record CacheEntry(Object value) {
    }

}
