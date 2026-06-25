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

import io.fluxzero.common.DelegatingClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.caching.CacheEviction.Reason.expiry;
import static io.fluxzero.common.caching.CacheEviction.Reason.manual;
import static io.fluxzero.common.caching.CacheEviction.Reason.memoryPressure;
import static io.fluxzero.common.caching.CacheEviction.Reason.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveObjectCacheTest {
    private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void keepsBasicCacheContract() {
        Cache cache = new AdaptiveObjectCache(2, MemoryPressureController.none());

        assertNull(cache.put("id1", "test1"));
        assertEquals("test1", cache.get("id1"));
        assertEquals("test1", cache.put("id1", "test1-2"));
        assertEquals("test1-2", cache.get("id1"));

        assertNull(cache.putIfAbsent("id2", "test2"));
        assertEquals("test2", cache.putIfAbsent("id2", "ignored"));
        assertEquals("test2", cache.get("id2"));

        cache.put("id3", "test3");
        assertEquals(2, cache.size());
        assertNull(cache.get("id1"));
    }

    @Test
    void keepsCacheContractForNullValuesAndRebuild() {
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());

        assertNull(cache.put("null", null));

        assertTrue(cache.containsKey("null"));
        assertNull(cache.get("null"));
        assertEquals("value", cache.computeIfAbsent("null", key -> "value"));
        assertEquals("value", cache.get("null"));
        Cache rebuilt = cache.rebuild();
        assertTrue(rebuilt.isEmpty());
    }

    @Test
    void keepsCacheContractForEmptyOptionalValues() {
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());

        assertNull(cache.computeIfAbsent("foo", key -> Optional.empty()));

        assertTrue(cache.containsKey("foo"));
        assertNull(cache.get("foo"));
    }

    @Test
    void computeIfAbsentDoesNotStoreNullMappingResults() {
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());

        assertNull(cache.computeIfAbsent("foo", key -> null));

        assertFalse(cache.containsKey("foo"));
    }

    @Test
    void expiresEntriesAfterConfiguredDuration() {
        DelegatingClock clock = fixedClock("2026-05-28T00:00:00Z");
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none(), Duration.ofSeconds(10), clock);
        List<CacheEviction> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        try {
            cache.put("foo", "bar");
            assertEquals("bar", cache.get("foo"));

            clock.setDelegate(fixed("2026-05-28T00:00:11Z"));

            assertNull(cache.get("foo"));
            assertFalse(cache.containsKey("foo"));
            assertEquals(0, cache.size());
            assertEquals("foo", evictions.getFirst().getId());
            assertEquals("bar", evictions.getFirst().getValue());
            assertEquals(List.of(expiry), evictions.stream().map(CacheEviction::getReason).toList());
        } finally {
            cache.close();
        }
    }

    @Test
    void rebuildKeepsExpiryConfiguration() {
        DelegatingClock clock = fixedClock("2026-05-28T00:00:00Z");
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none(), Duration.ofSeconds(10), clock);
        Cache rebuilt = cache.rebuild();

        try {
            rebuilt.put("foo", "bar");
            clock.setDelegate(fixed("2026-05-28T00:00:11Z"));

            assertNull(rebuilt.get("foo"));
            assertEquals(0, rebuilt.size());
        } finally {
            rebuilt.close();
            cache.close();
        }
    }

    @Test
    void expiryPurgerRemovesEntriesWithoutAccess() throws Throwable {
        DelegatingClock clock = fixedClock("2026-05-28T00:00:00Z");
        AdaptiveObjectCache cache = new AdaptiveObjectCache(10, 1, (key, value) -> 1,
                                                            MemoryPressureController.none(), Duration.ofSeconds(10),
                                                            Duration.ofMillis(1), clock);
        List<CacheEviction> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        try {
            cache.put("foo", "bar");
            clock.setDelegate(fixed("2026-05-28T00:00:11Z"));

            assertEventually(() -> assertEquals(List.of(expiry),
                                                evictions.stream().map(CacheEviction::getReason).toList()));
            assertFalse(cache.containsKey("foo"));
        } finally {
            cache.close();
        }
    }

    @Test
    void supportsComputeOperationsAndModifyEach() {
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());
        assertEquals("bar", cache.computeIfAbsent("foo", key -> "bar"));
        assertEquals("bar2", cache.computeIfPresent("foo", (key, value) -> value + "2"));
        assertEquals("created", cache.compute("missing", (key, value) -> "created"));

        cache.modifyEach((key, value) -> value + "!");

        assertEquals("bar2!", cache.get("foo"));
        assertEquals("created!", cache.get("missing"));
        assertNull(cache.computeIfPresent("foo", (key, value) -> null));
        assertFalse(cache.containsKey("foo"));
    }

    @Test
    void allowsNestedComputeCallsOnSameThread() {
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());

        assertEquals("foo", cache.compute("id1", (key, value) -> {
            cache.compute("id2", (innerKey, innerValue) -> "bar");
            cache.compute("id1", (innerKey, innerValue) -> "inner");
            return "foo";
        }));

        assertEquals("foo", cache.get("id1"));
        assertEquals("bar", cache.get("id2"));
    }

    @Test
    void publishesEvictionEventsUsingSdkReasons() {
        Cache cache = new AdaptiveObjectCache(2, MemoryPressureController.none());
        List<CacheEviction> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");

        assertEquals("a", evictions.getFirst().getId());
        assertEquals(size, evictions.getFirst().getReason());
    }

    @Test
    void publishesManualEvictionEventsAndSupportsListenerCancellation() {
        Cache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());
        List<CacheEviction> evictions = new ArrayList<>();
        var cancelled = cache.registerEvictionListener(evictions::add);
        cancelled.cancel();
        cache.registerEvictionListener(evictions::add);

        cache.put("a", "A");
        assertEquals("A", cache.remove("a"));
        cache.put("b", "B");
        cache.clear();

        assertEquals(List.of(manual, manual), evictions.stream().map(CacheEviction::getReason).toList());
    }

    @Test
    void listenerFailuresDoNotBreakCacheOperations() {
        Cache cache = new AdaptiveObjectCache(1, MemoryPressureController.none());
        AtomicInteger observed = new AtomicInteger();
        cache.registerEvictionListener(event -> {
            throw new IllegalStateException("boom");
        });
        cache.registerEvictionListener(event -> observed.incrementAndGet());

        cache.put("a", "A");
        cache.put("b", "B");

        assertEquals(1, observed.get());
        assertEquals("B", cache.get("b"));
    }

    @Test
    void rejectsEntriesThatExceedEntryBudget() {
        Cache cache = new AdaptiveObjectCache(100, 10, (key, value) -> 11, MemoryPressureController.none());
        List<CacheEviction> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        cache.put("too-large", "value");

        assertFalse(cache.containsKey("too-large"));
        assertEquals(size, evictions.getFirst().getReason());
    }

    @Test
    void weightedModeRemovesOldValueWhenReplacementIsRejected() {
        Cache cache = new AdaptiveObjectCache(100, 10, (key, value) -> value.toString().length(),
                                              MemoryPressureController.none());
        cache.put("key", "small");

        cache.put("key", "too-large-value");

        assertFalse(cache.containsKey("key"));
        assertNull(cache.get("key"));
    }

    @Test
    void defaultModeIsCountBasedForArbitraryObjects() {
        AdaptiveObjectCache cache = new AdaptiveObjectCache(2, MemoryPressureController.none());
        Object largeObject = new byte[1024 * 1024];

        cache.put("a", largeObject);
        cache.put("b", largeObject);
        cache.put("c", largeObject);

        assertEquals(2, cache.weight());
        assertEquals(2, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    void mapsMemoryPressureEvictions() {
        Cache cache = new AdaptiveObjectCache(100, 100, (key, value) -> 30,
                                              (currentWeight, maxWeight) -> currentWeight > 60);
        List<CacheEviction> evictions = new ArrayList<>();
        cache.registerEvictionListener(evictions::add);

        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");

        assertEquals(List.of(memoryPressure),
                     evictions.stream().map(CacheEviction::getReason).toList());
    }

    @Test
    void closedCacheDoesNotRetainNewValues() {
        AdaptiveObjectCache cache = new AdaptiveObjectCache(10, MemoryPressureController.none());
        cache.put("a", "A");

        cache.close();
        cache.put("b", "B");

        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
        assertNull(cache.get("b"));
    }

    @Test
    void canTrimCountBasedCacheUnderMemoryPressureWithoutWrites() {
        AtomicBoolean pressure = new AtomicBoolean();
        AdaptiveObjectCache cache = new AdaptiveObjectCache(100, 1, (key, value) -> 1,
                                                            (currentWeight, maxWeight) -> pressure.get());
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.put("d", "D");

        pressure.set(true);

        assertTrue(cache.trimForMemoryPressure());
        assertEquals(3, cache.size());
    }

    private static DelegatingClock fixedClock(String instant) {
        DelegatingClock clock = new DelegatingClock();
        clock.setDelegate(fixed(instant));
        return clock;
    }

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
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
}
