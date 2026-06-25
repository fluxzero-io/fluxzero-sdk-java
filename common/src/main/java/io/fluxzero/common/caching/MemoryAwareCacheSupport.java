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
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToLongBiFunction;

import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.entryTooLarge;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.manual;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.memoryPressure;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.size;

/**
 * A hard-reference, weighted cache with LRU eviction and optional key ordering.
 *
 * <p>The cache intentionally avoids soft references. Memory ownership is explicit through entry weights and a maximum
 * total weight. A {@link MemoryPressureController} may request extra trimming when heap or GC pressure is observed.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
@Slf4j
public class MemoryAwareCacheSupport<K, V> implements AutoCloseable {
    public static final Duration DEFAULT_MEMORY_PRESSURE_CHECK_INTERVAL = Duration.ofSeconds(1);
    private static final ScheduledExecutorService memoryPressureMonitor =
            Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    private static final ReferenceQueue<MemoryAwareCacheSupport<?, ?>> cacheReferenceQueue = new ReferenceQueue<>();
    private static final Collection<WeakReference<MemoryAwareCacheSupport<?, ?>>> memoryPressureCaches =
            new CopyOnWriteArrayList<>();
    private static final AtomicLong accessSequence = new AtomicLong();

    private final long maxWeight;
    private final long maxEntryWeight;
    private final ToLongBiFunction<? super K, ? super V> weigher;
    private final MemoryPressureController memoryPressureController;
    private final ScheduledFuture<?> memoryPressureMonitorTask;
    private final WeakReference<MemoryAwareCacheSupport<?, ?>> memoryPressureReference;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(128, 0.75f, true);
    private final NavigableMap<K, K> orderedKeys;
    private final Collection<Consumer<MemoryAwareCacheSupportEviction<K, V>>> evictionListeners = new CopyOnWriteArrayList<>();

    private long weight;
    private boolean closed;

    public MemoryAwareCacheSupport(long maxWeight, long maxEntryWeight,
                         ToLongBiFunction<? super K, ? super V> weigher) {
        this(maxWeight, maxEntryWeight, weigher, null, MemoryPressureController.jvm());
    }

    public MemoryAwareCacheSupport(long maxWeight, long maxEntryWeight,
                         ToLongBiFunction<? super K, ? super V> weigher,
                         Comparator<? super K> keyComparator) {
        this(maxWeight, maxEntryWeight, weigher, keyComparator, MemoryPressureController.jvm());
    }

    public MemoryAwareCacheSupport(long maxWeight, long maxEntryWeight,
                         ToLongBiFunction<? super K, ? super V> weigher,
                         Comparator<? super K> keyComparator,
                         MemoryPressureController memoryPressureController) {
        this(maxWeight, maxEntryWeight, weigher, keyComparator, memoryPressureController,
             DEFAULT_MEMORY_PRESSURE_CHECK_INTERVAL);
    }

    public MemoryAwareCacheSupport(long maxWeight, long maxEntryWeight,
                         ToLongBiFunction<? super K, ? super V> weigher,
                         Comparator<? super K> keyComparator,
                         MemoryPressureController memoryPressureController,
                         Duration memoryPressureCheckInterval) {
        if (maxWeight < 0) {
            throw new IllegalArgumentException("maxWeight must be non-negative");
        }
        if (maxEntryWeight < 0) {
            throw new IllegalArgumentException("maxEntryWeight must be non-negative");
        }
        this.maxWeight = maxWeight;
        this.maxEntryWeight = maxEntryWeight;
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        this.memoryPressureController = Objects.requireNonNull(memoryPressureController, "memoryPressureController");
        this.orderedKeys = keyComparator == null ? null : new TreeMap<>(keyComparator);
        this.memoryPressureReference = registerMemoryPressureCache();
        this.memoryPressureMonitorTask = startMemoryPressureMonitor(memoryPressureCheckInterval);
    }

    /**
     * Stores a value when it fits the configured entry and total weight constraints.
     *
     * @return {@code true} if the value remains cached after admission and eviction
     */
    public boolean put(K key, V value) {
        synchronized (this) {
            if (closed) {
                return false;
            }
            long entryWeight = value == null ? 0L : Math.max(1L, weigher.applyAsLong(key, value));
            removeEntry(key, manual, false);
            if (value == null || maxWeight == 0 || entryWeight > maxEntryWeight || entryWeight > maxWeight) {
                notifyEviction(key, value, entryWeight, entryTooLarge);
                return false;
            }
            entries.put(key, new Entry<>(value, entryWeight, nextAccessSequence()));
            if (orderedKeys != null) {
                orderedKeys.put(key, key);
            }
            weight += entryWeight;
            trimToWeight(maxWeight, size);
        }
        trimForMemoryPressure();
        return containsKey(key);
    }

    public synchronized V get(K key) {
        Entry<V> entry = getEntryForAccess(key);
        return entry == null ? null : entry.value();
    }

    public synchronized boolean containsKey(K key) {
        return entries.containsKey(key);
    }

    public synchronized V remove(K key) {
        Entry<V> removed = removeEntry(key, manual, true);
        return removed == null ? null : removed.value();
    }

    /**
     * Removes a value and publishes the supplied eviction reason when the key was present.
     */
    public synchronized V evict(K key, MemoryAwareCacheSupportEviction.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        Entry<V> removed = removeEntry(key, reason, true);
        return removed == null ? null : removed.value();
    }

    public synchronized void clear() {
        clear(true);
    }

    private void clear(boolean notify) {
        if (!entries.isEmpty()) {
            if (notify) {
                entries.forEach((key, entry) -> notifyEviction(key, entry.value(), entry.weight(), manual));
            }
            entries.clear();
            if (orderedKeys != null) {
                orderedKeys.clear();
            }
            weight = 0L;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long weight() {
        return weight;
    }

    public synchronized List<K> keys() {
        return new ArrayList<>(entries.keySet());
    }

    public synchronized K firstKey() {
        return orderedKeys == null || orderedKeys.isEmpty() ? null : orderedKeys.firstKey();
    }

    public synchronized K lastKey() {
        return orderedKeys == null || orderedKeys.isEmpty() ? null : orderedKeys.lastKey();
    }

    public long maxWeight() {
        return maxWeight;
    }

    public long maxEntryWeight() {
        return maxEntryWeight;
    }

    /**
     * Returns cached values ordered by key, starting at {@code minKey}.
     */
    public synchronized List<V> valuesFrom(K minKey, boolean inclusive, int maxSize) {
        if (orderedKeys == null) {
            throw new IllegalStateException("This cache was not constructed with key ordering");
        }
        if (maxSize <= 0) {
            return List.of();
        }
        List<V> result = new ArrayList<>(Math.min(maxSize, entries.size()));
        for (K key : orderedKeys.tailMap(minKey, inclusive).values()) {
            Entry<V> entry = getEntryForAccess(key);
            if (entry != null) {
                result.add(entry.value());
                if (result.size() == maxSize) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Evicts all ordered keys before the supplied frontier.
     */
    public synchronized void evictBefore(K frontier, boolean inclusive) {
        if (orderedKeys == null) {
            throw new IllegalStateException("This cache was not constructed with key ordering");
        }
        List<K> keys = new ArrayList<>(orderedKeys.headMap(frontier, inclusive).keySet());
        keys.forEach(key -> removeEntry(key, size, true));
    }

    public Registration registerEvictionListener(Consumer<MemoryAwareCacheSupportEviction<K, V>> listener) {
        evictionListeners.add(listener);
        return () -> evictionListeners.remove(listener);
    }

    /**
     * Proactively sheds cached weight according to the configured trim ratio and maximum trim weight when the controller
     * observes memory pressure.
     *
     * @return {@code true} if pressure was observed
     */
    public boolean trimForMemoryPressure() {
        long currentWeight;
        synchronized (this) {
            if (closed || entries.isEmpty()) {
                return false;
            }
            currentWeight = weight;
        }
        if (memoryPressureController instanceof MemoryPressureController.JvmMemoryPressureController jvmController
            && jvmController.shouldEvictAll(currentWeight, maxWeight)) {
            return trimAllForObservedMemoryPressure(jvmController.trimRatioPercent(), jvmController.maxTrimWeight());
        }
        if (!memoryPressureController.shouldEvict(currentWeight, maxWeight)) {
            return false;
        }
        return trimForObservedMemoryPressure();
    }

    private synchronized boolean trimForObservedMemoryPressure() {
        if (closed || entries.isEmpty()) {
            return false;
        }
        trimToWeight(targetWeightAfterPressureTrim(weight, memoryPressureController.trimRatioPercent(),
                                                   memoryPressureController.maxTrimWeight()),
                     memoryPressure);
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (memoryPressureMonitorTask != null) {
            memoryPressureMonitorTask.cancel(false);
        }
        memoryPressureCaches.remove(memoryPressureReference);
        clear(false);
    }

    private WeakReference<MemoryAwareCacheSupport<?, ?>> registerMemoryPressureCache() {
        cleanupMemoryPressureCaches();
        if (!(memoryPressureController instanceof MemoryPressureController.JvmMemoryPressureController)) {
            return null;
        }
        WeakReference<MemoryAwareCacheSupport<?, ?>> reference = new WeakReference<>(this, cacheReferenceQueue);
        memoryPressureCaches.add(reference);
        return reference;
    }

    private static boolean trimAllForObservedMemoryPressure(int trimRatioPercent, long maxTrimWeight) {
        cleanupMemoryPressureCaches();
        List<MemoryAwareCacheSupport<?, ?>> caches = new ArrayList<>();
        long totalWeight = 0L;
        for (WeakReference<MemoryAwareCacheSupport<?, ?>> reference : memoryPressureCaches) {
            MemoryAwareCacheSupport<?, ?> cache = reference.get();
            if (cache == null) {
                memoryPressureCaches.remove(reference);
            } else {
                long cacheWeight = cache.currentWeightForMemoryPressure();
                if (cacheWeight > 0L) {
                    caches.add(cache);
                    totalWeight = saturatedAdd(totalWeight, cacheWeight);
                }
            }
        }
        if (caches.isEmpty() || totalWeight <= 0L) {
            return false;
        }
        long weightToEvict = weightToEvictForPressureTrim(totalWeight, trimRatioPercent, maxTrimWeight);
        long evictedWeight = 0L;
        int staleCandidates = 0;
        int maxStaleCandidates = Math.max(1, caches.size() * 2);
        while (evictedWeight < weightToEvict) {
            MemoryPressureEvictionCandidate candidate = oldestMemoryPressureCandidate(caches);
            if (candidate == null) {
                return evictedWeight > 0L;
            }
            long removedWeight = candidate.cache().evictCandidateForObservedMemoryPressure(candidate);
            if (removedWeight > 0L) {
                staleCandidates = 0;
                evictedWeight = saturatedAdd(evictedWeight, removedWeight);
            } else if (++staleCandidates >= maxStaleCandidates) {
                return evictedWeight > 0L;
            }
        }
        return true;
    }

    private static MemoryPressureEvictionCandidate oldestMemoryPressureCandidate(
            List<MemoryAwareCacheSupport<?, ?>> caches) {
        MemoryPressureEvictionCandidate result = null;
        for (MemoryAwareCacheSupport<?, ?> cache : caches) {
            MemoryPressureEvictionCandidate candidate = cache.oldestMemoryPressureCandidate();
            if (candidate != null
                && (result == null || Long.compare(candidate.lastAccess(), result.lastAccess()) < 0)) {
                result = candidate;
            }
        }
        return result;
    }

    private static long targetWeightAfterPressureTrim(long currentWeight, int trimRatioPercent, long maxTrimWeight) {
        return Math.max(0L, currentWeight - weightToEvictForPressureTrim(currentWeight, trimRatioPercent,
                                                                         maxTrimWeight));
    }

    private static long weightToEvictForPressureTrim(long currentWeight, int trimRatioPercent, long maxTrimWeight) {
        if (currentWeight <= 0L) {
            return 0L;
        }
        long quotient = currentWeight / 100L;
        long remainder = currentWeight % 100L;
        long weightToEvict = saturatedAdd(quotient * trimRatioPercent,
                                          (remainder * trimRatioPercent + 99L) / 100L);
        return Math.max(1L, Math.min(Math.min(currentWeight, maxTrimWeight), weightToEvict));
    }

    private static void cleanupMemoryPressureCaches() {
        Reference<? extends MemoryAwareCacheSupport<?, ?>> reference;
        while ((reference = cacheReferenceQueue.poll()) != null) {
            memoryPressureCaches.remove(reference);
        }
    }

    private void trimToWeight(long targetWeight, MemoryAwareCacheSupportEviction.Reason reason) {
        while (weight > targetWeight && !entries.isEmpty()) {
            Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
            removeEntry(eldest.getKey(), reason, true);
        }
    }

    private synchronized long currentWeightForMemoryPressure() {
        return closed ? 0L : weight;
    }

    private synchronized MemoryPressureEvictionCandidate oldestMemoryPressureCandidate() {
        if (closed || entries.isEmpty()) {
            return null;
        }
        Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
        return new MemoryPressureEvictionCandidate(this, eldest.getKey(), eldest.getValue().lastAccess());
    }

    @SuppressWarnings("unchecked")
    private synchronized long evictCandidateForObservedMemoryPressure(MemoryPressureEvictionCandidate candidate) {
        if (closed || entries.isEmpty()) {
            return 0L;
        }
        K key = (K) candidate.key();
        Entry<V> removed = entries.remove(key);
        if (removed == null) {
            return 0L;
        }
        if (removed.lastAccess() != candidate.lastAccess()) {
            entries.put(key, removed);
            return 0L;
        }
        weight -= removed.weight();
        if (orderedKeys != null) {
            orderedKeys.remove(key);
        }
        notifyEviction(key, removed.value(), removed.weight(), memoryPressure);
        return removed.weight();
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private Entry<V> removeEntry(K key, MemoryAwareCacheSupportEviction.Reason reason, boolean notify) {
        Entry<V> removed = entries.remove(key);
        if (removed != null) {
            weight -= removed.weight();
            if (orderedKeys != null) {
                orderedKeys.remove(key);
            }
            if (notify) {
                notifyEviction(key, removed.value(), removed.weight(), reason);
            }
        }
        return removed;
    }

    private void notifyEviction(K key, V value, long entryWeight, MemoryAwareCacheSupportEviction.Reason reason) {
        if (!evictionListeners.isEmpty()) {
            MemoryAwareCacheSupportEviction<K, V> event = new MemoryAwareCacheSupportEviction<>(key, value, entryWeight, reason);
            evictionListeners.forEach(listener -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    log.error("Cache eviction listener {} failed", listener, e);
                }
            });
        }
    }

    private Entry<V> getEntryForAccess(K key) {
        Entry<V> entry = entries.get(key);
        if (entry != null) {
            entry.recordAccess(nextAccessSequence());
        }
        return entry;
    }

    private static long nextAccessSequence() {
        return accessSequence.incrementAndGet();
    }

    private ScheduledFuture<?> startMemoryPressureMonitor(Duration memoryPressureCheckInterval) {
        if (memoryPressureController == MemoryPressureController.NONE || memoryPressureCheckInterval == null
            || memoryPressureCheckInterval.isZero() || memoryPressureCheckInterval.isNegative()) {
            return null;
        }
        long delayMillis = Math.max(1L, memoryPressureCheckInterval.toMillis());
        return memoryPressureMonitor.scheduleWithFixedDelay(() -> {
            try {
                trimForMemoryPressure();
            } catch (Exception e) {
                log.error("Memory-aware cache support memory-pressure monitor failed", e);
            }
        }, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("MemoryAwareCacheSupport-memoryPressureMonitor-", 0L)
                .unstarted(runnable);
    }

    private record MemoryPressureEvictionCandidate(MemoryAwareCacheSupport<?, ?> cache, Object key, long lastAccess) {
    }

    private static final class Entry<V> {
        private final V value;
        private final long weight;
        private long lastAccess;

        private Entry(V value, long weight, long lastAccess) {
            this.value = value;
            this.weight = weight;
            this.lastAccess = lastAccess;
        }

        private V value() {
            return value;
        }

        private long weight() {
            return weight;
        }

        private long lastAccess() {
            return lastAccess;
        }

        private void recordAccess(long lastAccess) {
            this.lastAccess = lastAccess;
        }
    }
}
