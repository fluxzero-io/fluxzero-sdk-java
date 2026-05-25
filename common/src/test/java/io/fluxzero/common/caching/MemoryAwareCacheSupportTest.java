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

import io.fluxzero.common.application.SimplePropertySource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.entryTooLarge;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.expiry;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.memoryPressure;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.manual;
import static io.fluxzero.common.caching.MemoryAwareCacheSupportEviction.Reason.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAwareCacheSupportTest {
    private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryAwareCacheSupport<>(
                -1, 1, (key, value) -> 1, null, MemoryPressureController.none()));
        assertThrows(IllegalArgumentException.class, () -> new MemoryAwareCacheSupport<>(
                1, -1, (key, value) -> 1, null, MemoryPressureController.none()));
        assertThrows(NullPointerException.class, () -> new MemoryAwareCacheSupport<>(
                1, 1, null, null, MemoryPressureController.none()));
        assertThrows(NullPointerException.class, () -> new MemoryAwareCacheSupport<>(
                1, 1, (key, value) -> 1, null, null));
    }

    @Test
    void evictsLeastRecentlyUsedEntriesByWeight() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                10, 10, (key, value) -> 4, null, MemoryPressureController.none(), null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        cache.put("a", "A");
        cache.put("b", "B");
        assertEquals("A", cache.get("a"));
        cache.put("c", "C");

        assertEquals("A", cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals("C", cache.get("c"));
        assertEquals(List.of("b"), evictions.stream().map(MemoryAwareCacheSupportEviction::key).toList());
        assertEquals(size, evictions.getFirst().reason());
    }

    @Test
    void rejectsEntriesThatExceedEntryBudget() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                100, 10, (key, value) -> 11, null, MemoryPressureController.none(), null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        assertFalse(cache.put("too-large", "value"));

        assertNull(cache.get("too-large"));
        assertEquals(entryTooLarge, evictions.getFirst().reason());
    }

    @Test
    void zeroBudgetRejectsEntriesWithoutRetainingReferences() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                0, 0, (key, value) -> 1, null, MemoryPressureController.none(), null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        assertFalse(cache.put("too-large", "value"));

        assertEquals(0, cache.size());
        assertEquals(0L, cache.weight());
        assertNull(cache.get("too-large"));
        assertEquals(entryTooLarge, evictions.getFirst().reason());
    }

    @Test
    void rejectingReplacementRemovesOldValueToAvoidStaleCache() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                100, 10, (key, value) -> value.length(), null, MemoryPressureController.none(), null);
        cache.put("key", "small");

        assertFalse(cache.put("key", "too-large-value"));

        assertNull(cache.get("key"));
        assertEquals(0L, cache.weight());
    }

    @Test
    void replacingRemovingAndClearingMaintainWeight() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                100, 100, (key, value) -> value.length(), null, MemoryPressureController.none(), null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        assertTrue(cache.put("a", "1234"));
        assertEquals(4L, cache.weight());
        assertTrue(cache.put("a", "12"));
        assertEquals(2L, cache.weight());
        assertEquals("12", cache.remove("a"));
        assertEquals(0L, cache.weight());
        assertTrue(cache.put("b", "123"));
        assertTrue(cache.put("c", "12345"));
        cache.clear();

        assertEquals(0L, cache.weight());
        assertEquals(List.of(manual, manual, manual),
                     evictions.stream().map(MemoryAwareCacheSupportEviction::reason).toList());
    }

    @Test
    void evictsWithExplicitReason() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                100, 100, (key, value) -> value.length(), null, MemoryPressureController.none(), null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        cache.put("a", "1234");

        assertEquals("1234", cache.evict("a", expiry));
        assertEquals(0, cache.size());
        assertEquals(0L, cache.weight());
        assertEquals(expiry, evictions.getFirst().reason());
    }

    @Test
    void nonNullEntriesHaveMinimumWeightOfOne() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                2, 2, (key, value) -> -100, null, MemoryPressureController.none(), null);

        assertTrue(cache.put("a", "A"));
        assertTrue(cache.put("b", "B"));
        assertTrue(cache.put("c", "C"));

        assertEquals(2L, cache.weight());
        assertNull(cache.get("a"));
        assertEquals("B", cache.get("b"));
        assertEquals("C", cache.get("c"));
    }

    @Test
    void returnsValuesInKeyOrderAndEvictsBeforeFrontier() {
        MemoryAwareCacheSupport<Long, String> cache = new MemoryAwareCacheSupport<>(
                100, 100, (key, value) -> 1, Comparator.naturalOrder(), MemoryPressureController.none(), null);
        cache.put(1L, "one");
        cache.put(2L, "two");
        cache.put(3L, "three");

        assertEquals(List.of("two", "three"), cache.valuesFrom(2L, true, 10));
        assertEquals(List.of("three"), cache.valuesFrom(2L, false, 10));
        assertEquals(List.of("one", "two"), cache.valuesFrom(1L, true, 2));
        assertEquals(1L, cache.firstKey());
        assertEquals(3L, cache.lastKey());

        cache.evictBefore(3L, false);

        assertNull(cache.get(1L));
        assertNull(cache.get(2L));
        assertEquals("three", cache.get(3L));
        assertEquals(3L, cache.firstKey());
        assertEquals(3L, cache.lastKey());
    }

    @Test
    void orderedMethodsRequireKeyOrdering() {
        MemoryAwareCacheSupport<Long, String> cache = new MemoryAwareCacheSupport<>(
                100, 100, (key, value) -> 1, null, MemoryPressureController.none(), null);

        assertThrows(IllegalStateException.class, () -> cache.valuesFrom(1L, true, 10));
        assertThrows(IllegalStateException.class, () -> cache.evictBefore(1L, true));
    }

    @Test
    void trimsConfiguredRatioWhenMemoryPressureControllerRequestsEviction() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                100, 100, (key, value) -> 30, null, (currentWeight, maxWeight) -> currentWeight > 60, null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        cache.put("a", "A");
        cache.put("b", "B");
        assertTrue(cache.put("c", "C"));

        assertNull(cache.get("a"));
        assertEquals("B", cache.get("b"));
        assertEquals("C", cache.get("c"));
        assertEquals(List.of(memoryPressure),
                     evictions.stream().map(MemoryAwareCacheSupportEviction::reason).toList());
    }

    @Test
    void memoryPressureTrimsConfiguredRatioEvenWhenBelowMaximumWeight() {
        AtomicBoolean pressure = new AtomicBoolean();
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, (currentWeight, maxWeight) -> pressure.get(), null);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.put("d", "D");

        pressure.set(true);

        assertTrue(cache.trimForMemoryPressure());
        assertEquals(30L, cache.weight());
        assertNull(cache.get("a"));
        assertEquals("B", cache.get("b"));
    }

    @Test
    void backgroundMemoryPressureMonitorTrimsWithoutNewWrites() throws Throwable {
        AtomicBoolean pressure = new AtomicBoolean();
        try (MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, (currentWeight, maxWeight) -> pressure.get(),
                Duration.ofMillis(5))) {
            cache.put("a", "A");
            cache.put("b", "B");
            cache.put("c", "C");
            cache.put("d", "D");

            pressure.set(true);

            assertEventually(() -> assertTrue(cache.weight() <= 30L));
        }
    }

    @Test
    void jvmMemoryPressureTrimsSiblingCachesImmediately() {
        AtomicLong usedMemory = new AtomicLong(1L);
        MemoryAwareCacheSupport<String, String> cache1 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, jvmController(usedMemory), null);
        MemoryAwareCacheSupport<String, String> cache2 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, jvmController(usedMemory), null);
        try {
            fill(cache1, "a", 4);
            fill(cache2, "b", 4);

            usedMemory.set(85L);

            assertTrue(cache1.trimForMemoryPressure());
            assertEquals(20L, cache1.weight());
            assertEquals(40L, cache2.weight());
        } finally {
            cache1.close();
            cache2.close();
        }
    }

    @Test
    void jvmMemoryPressureEvictsGloballyOldestEntriesFirst() {
        AtomicLong usedMemory = new AtomicLong(1L);
        MemoryPressureController controller = jvmController(usedMemory, 40, 1_000);
        MemoryAwareCacheSupport<String, String> coldCache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 1, null, controller, null);
        MemoryAwareCacheSupport<String, String> hotCache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 1, null, controller, null);
        List<String> coldEvictions = new ArrayList<>();
        coldCache.registerEvictionListener(event -> coldEvictions.add(event.key()));
        try {
            fill(coldCache, "cold", 10);
            fill(hotCache, "hot", 5);

            usedMemory.set(85L);

            assertTrue(hotCache.trimForMemoryPressure());
            assertEquals(4L, coldCache.weight());
            assertEquals(5L, hotCache.weight());
            assertEquals(List.of("cold0", "cold1", "cold2", "cold3", "cold4", "cold5"), coldEvictions);
        } finally {
            coldCache.close();
            hotCache.close();
        }
    }

    @Test
    void globalMemoryPressureListenersCanUseSiblingCaches() {
        AtomicLong usedMemory = new AtomicLong(1L);
        MemoryPressureController controller = jvmController(usedMemory, 50, 1);
        MemoryAwareCacheSupport<String, String> coldCache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 1, null, controller, null);
        MemoryAwareCacheSupport<String, String> hotCache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 1, null, controller, null);
        AtomicBoolean listenerReachedSibling = new AtomicBoolean();
        coldCache.registerEvictionListener(event -> listenerReachedSibling.set("value".equals(hotCache.get("hot0"))));
        try {
            fill(coldCache, "cold", 1);
            fill(hotCache, "hot", 1);

            usedMemory.set(85L);

            assertTrue(hotCache.trimForMemoryPressure());
            assertTrue(listenerReachedSibling.get());
            assertEquals("value", hotCache.get("hot0"));
        } finally {
            coldCache.close();
            hotCache.close();
        }
    }

    @Test
    void jvmMemoryPressureTrimsNestedSingletonCachesByGlobalTarget() {
        AtomicLong usedMemory = new AtomicLong(1L);
        List<MemoryAwareCacheSupport<String, String>> caches = new ArrayList<>();
        try {
            for (int i = 0; i < 15; i++) {
                MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                        1_000, 1_000, (key, value) -> 1, null, jvmController(usedMemory), null);
                assertTrue(cache.put("key-" + i, "value"));
                caches.add(cache);
            }

            usedMemory.set(85L);

            assertTrue(caches.getFirst().trimForMemoryPressure());
            assertEquals(12L, caches.stream().mapToLong(MemoryAwareCacheSupport::weight).sum());
        } finally {
            caches.forEach(MemoryAwareCacheSupport::close);
        }
    }

    @Test
    void jvmMemoryPressureTrimIsCappedByConfiguredMaximumWeight() {
        AtomicLong usedMemory = new AtomicLong(1L);
        MemoryPressureController controller = new MemoryPressureController.JvmMemoryPressureController(
                () -> 0L, () -> 100L, usedMemory::get, () -> 0L, 85, 20, 50, 30);
        MemoryAwareCacheSupport<String, String> cache1 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, controller, null);
        MemoryAwareCacheSupport<String, String> cache2 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, controller, null);
        try {
            fill(cache1, "a", 10);
            fill(cache2, "b", 10);

            usedMemory.set(85L);

            assertTrue(cache1.trimForMemoryPressure());
            assertEquals(170L, cache1.weight() + cache2.weight());
        } finally {
            cache1.close();
            cache2.close();
        }
    }

    @Test
    void globalMemoryPressureTrimmingToleratesConcurrentAccess() throws Exception {
        AtomicLong usedMemory = new AtomicLong(1L);
        MemoryPressureController controller = jvmController(usedMemory, 20, 1_000);
        List<MemoryAwareCacheSupport<String, String>> caches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                    500, 500, (key, value) -> 1, null, controller, null);
            fill(cache, "seed-" + i + "-", 100);
            caches.add(cache);
        }
        usedMemory.set(85L);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                int threadId = thread;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        MemoryAwareCacheSupport<String, String> cache = caches.get((threadId + i) % caches.size());
                        cache.get("seed-" + (i % caches.size()) + "-" + (i % 100));
                        cache.put("thread-" + threadId + "-" + i, "value");
                        if (i % 11 == 0) {
                            cache.remove("thread-" + threadId + "-" + (i - 33));
                        }
                    }
                    return null;
                }));
            }
            futures.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < 250; i++) {
                    caches.get(i % caches.size()).trimForMemoryPressure();
                }
                return null;
            }));

            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertTrue(caches.stream().allMatch(cache -> cache.weight() <= cache.maxWeight()));
        } finally {
            caches.forEach(MemoryAwareCacheSupport::close);
        }
    }

    @Test
    void customMemoryPressureControllerTrimsOnlyCurrentCache() {
        AtomicBoolean pressure = new AtomicBoolean();
        MemoryAwareCacheSupport<String, String> cache1 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, (currentWeight, maxWeight) -> pressure.get(), null);
        MemoryAwareCacheSupport<String, String> cache2 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, (currentWeight, maxWeight) -> pressure.get(), null);
        try {
            fill(cache1, "a", 4);
            fill(cache2, "b", 4);

            pressure.set(true);

            assertTrue(cache1.trimForMemoryPressure());
            assertEquals(30L, cache1.weight());
            assertEquals(40L, cache2.weight());
        } finally {
            cache1.close();
            cache2.close();
        }
    }

    @Test
    void memoryPressureOptOutCachesDoNotParticipateInGlobalTrimming() {
        AtomicLong usedMemory = new AtomicLong(1L);
        MemoryAwareCacheSupport<String, String> pressureAwareCache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, jvmController(usedMemory), null);
        MemoryAwareCacheSupport<String, String> optedOutCache = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, MemoryPressureController.none(), null);
        try {
            fill(pressureAwareCache, "a", 4);
            fill(optedOutCache, "b", 4);

            usedMemory.set(85L);

            assertTrue(pressureAwareCache.trimForMemoryPressure());
            assertEquals(30L, pressureAwareCache.weight());
            assertEquals(40L, optedOutCache.weight());
        } finally {
            pressureAwareCache.close();
            optedOutCache.close();
        }
    }

    @Test
    void backgroundJvmPressureMonitorTrimsSiblingCachesWithoutWrites() throws Throwable {
        AtomicLong usedMemory = new AtomicLong(1L);
        try (MemoryAwareCacheSupport<String, String> cache1 = new MemoryAwareCacheSupport<>(
                1_000, 1_000, (key, value) -> 10, null, jvmController(usedMemory), Duration.ofMillis(5));
             MemoryAwareCacheSupport<String, String> cache2 = new MemoryAwareCacheSupport<>(
                     1_000, 1_000, (key, value) -> 10, null, jvmController(usedMemory), null)) {
            fill(cache1, "a", 4);
            fill(cache2, "b", 4);

            usedMemory.set(85L);

            assertEventually(() -> assertTrue(cache1.weight() + cache2.weight() <= 60L));
        }
    }

    @Test
    void listenerRegistrationCanBeCancelledAndListenerFailuresAreIsolated() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                2, 2, (key, value) -> 1, null, MemoryPressureController.none(), null);
        List<String> observed = new ArrayList<>();
        var cancelled = cache.registerEvictionListener(event -> observed.add("cancelled"));
        cache.registerEvictionListener(event -> {
            throw new IllegalStateException("boom");
        });
        cache.registerEvictionListener(event -> observed.add(event.key()));
        cancelled.cancel();

        assertTrue(cache.put("a", "A"));
        assertTrue(cache.put("b", "B"));
        assertTrue(cache.put("c", "C"));

        assertEquals(List.of("a"), observed);
    }

    @Test
    void closeReleasesEntriesWithoutPublishingEvictions() {
        MemoryAwareCacheSupport<String, String> cache = new MemoryAwareCacheSupport<>(
                100, 100, (key, value) -> 1, null, MemoryPressureController.none(), null);
        List<MemoryAwareCacheSupportEviction<String, String>> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);
        cache.put("a", "A");

        cache.close();

        assertEquals(0, cache.size());
        assertTrue(evictions.isEmpty());
        assertFalse(cache.put("b", "B"));
        assertNull(cache.get("b"));
    }

    @Test
    void concurrentAccessMaintainsWeightInvariant() throws Exception {
        MemoryAwareCacheSupport<Integer, String> cache = new MemoryAwareCacheSupport<>(
                50, 1, (key, value) -> 1, Comparator.naturalOrder(), MemoryPressureController.none(), null);
        int threadCount = 8;
        int iterations = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threadCount)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int thread = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        int key = (thread * iterations + i) % 100;
                        cache.put(key, "value");
                        cache.get((key + 13) % 100);
                        if (i % 7 == 0) {
                            cache.remove((key + 29) % 100);
                        }
                        if (i % 17 == 0) {
                            cache.valuesFrom(0, true, 10);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertTrue(cache.weight() <= 50L);
        assertTrue(cache.size() <= 50);
    }

    @Test
    void jvmMemoryPressureControllerUsesIndependentSamplingState() {
        assertNotSame(MemoryPressureController.jvm(), MemoryPressureController.jvm());
    }

    @Test
    void jvmMemoryPressureControllerDetectsHeapUsageThreshold() {
        AtomicLong nanos = new AtomicLong();
        MemoryPressureController controller = new MemoryPressureController.JvmMemoryPressureController(
                nanos::get, () -> 100L, () -> 85L, () -> 0L);

        assertTrue(controller.shouldEvict(1, 100));
    }

    @Test
    void jvmMemoryPressureControllerCanBeConfiguredByProperties() {
        AtomicLong nanos = new AtomicLong();
        AtomicLong collectionMillis = new AtomicLong();
        MemoryPressureController controller = new MemoryPressureController.JvmMemoryPressureController(
                new SimplePropertySource(Map.of(
                        MemoryPressureController.MEMORY_PRESSURE_HEAP_THRESHOLD_PERCENT_PROPERTY, "70",
                        MemoryPressureController.MEMORY_PRESSURE_GC_TIME_THRESHOLD_PERCENT_PROPERTY, "10",
                        MemoryPressureController.MEMORY_PRESSURE_TRIM_RATIO_PERCENT_PROPERTY, "5",
                        MemoryPressureController.MEMORY_PRESSURE_MAX_TRIM_WEIGHT_PROPERTY, "17")),
                nanos::get, () -> 100L, () -> 70L, collectionMillis::get);

        assertTrue(controller.shouldEvict(1, 100));
        assertEquals(5, controller.trimRatioPercent());
        assertEquals(17L, controller.maxTrimWeight());
    }

    @Test
    void jvmMemoryPressureControllerRejectsInvalidPropertyPercentages() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryPressureController.JvmMemoryPressureController(
                new SimplePropertySource(Map.of(
                        MemoryPressureController.MEMORY_PRESSURE_TRIM_RATIO_PERCENT_PROPERTY, "0")),
                () -> 0L, () -> 100L, () -> 1L, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPressureController.JvmMemoryPressureController(
                new SimplePropertySource(Map.of(
                        MemoryPressureController.MEMORY_PRESSURE_MAX_TRIM_WEIGHT_PROPERTY, "0")),
                () -> 0L, () -> 100L, () -> 1L, () -> 0L));
    }

    @Test
    void jvmMemoryPressureControllerDetectsWeightOverflow() {
        MemoryPressureController controller = new MemoryPressureController.JvmMemoryPressureController(
                () -> 0L, () -> Long.MAX_VALUE, () -> 1L, () -> 0L);

        assertTrue(controller.shouldEvict(11, 10));
    }

    @Test
    void jvmMemoryPressureControllerDetectsGcTimeThreshold() {
        AtomicLong nanos = new AtomicLong();
        AtomicLong collectionMillis = new AtomicLong();
        MemoryPressureController controller = new MemoryPressureController.JvmMemoryPressureController(
                nanos::get, () -> 100L, () -> 1L, collectionMillis::get);
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        collectionMillis.addAndGet(20L);

        assertTrue(controller.shouldEvict(1, 100));
    }

    private static void assertEventually(Executable assertion) throws Throwable {
        AssertionError lastError = null;
        long deadline = System.nanoTime() + EVENTUALLY_TIMEOUT.toNanos();
        do {
            try {
                assertion.execute();
                return;
            } catch (AssertionError e) {
                lastError = e;
                Thread.sleep(10);
            }
        } while (System.nanoTime() < deadline);
        throw lastError;
    }

    private static MemoryPressureController jvmController(AtomicLong usedMemory) {
        return new MemoryPressureController.JvmMemoryPressureController(
                () -> 0L, () -> 100L, usedMemory::get, () -> 0L);
    }

    private static MemoryPressureController jvmController(AtomicLong usedMemory, int trimRatioPercent,
                                                          long maxTrimWeight) {
        return new MemoryPressureController.JvmMemoryPressureController(
                () -> 0L, () -> 100L, usedMemory::get, () -> 0L, 85, 20, trimRatioPercent, maxTrimWeight);
    }

    private static void fill(MemoryAwareCacheSupport<String, String> cache, String prefix, int count) {
        for (int i = 0; i < count; i++) {
            assertTrue(cache.put(prefix + i, "value"));
        }
    }
}
