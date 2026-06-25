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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Explicit stress harness for memory-aware hard-reference cache support and object caches.
 * <p>
 * This class is intentionally not a normal unit test. Run it in a small heap to validate that cache-dominant memory
 * pressure triggers active eviction instead of relying on soft-reference clearing.
 * <pre>
 * ./mvnw -q -pl common -DskipTests test-compile dependency:build-classpath \
 *   -Dmdep.scope=test -Dmdep.outputFile=/tmp/fluxzero-common-test.cp
 * java -Xmx128m -cp "common/target/test-classes:common/target/classes:$(cat /tmp/fluxzero-common-test.cp)" \
 *   io.fluxzero.common.caching.MemoryAwareCacheSupportPressureBenchmark
 * </pre>
 */
public class MemoryAwareCacheSupportPressureBenchmark {
    private static final long KIB = 1024L;
    private static final long MIB = KIB * KIB;
    private static final int DEFAULT_PAYLOAD_BYTES = 10 * (int) MIB;
    private static final int DEFAULT_SMALL_PAYLOAD_BYTES = 256 * (int) KIB;
    private static final Duration MONITOR_INTERVAL = Duration.ofMillis(
            Long.getLong("pressureCheckMillis", 10L));

    private static volatile long blackhole;

    private final long maxHeap = Runtime.getRuntime().maxMemory();
    private final int payloadBytes = Integer.getInteger("payloadBytes", DEFAULT_PAYLOAD_BYTES);
    private final int smallPayloadBytes = Integer.getInteger("smallPayloadBytes", DEFAULT_SMALL_PAYLOAD_BYTES);
    private final int pressureIterations = Integer.getInteger(
            "pressureIterations", defaultPressureIterations());
    private final int boundedIterations = Integer.getInteger(
            "boundedIterations", Math.max(pressureIterations, 160));
    private final int concurrentIterations = Integer.getInteger("concurrentIterations", 2_000);
    private final int threadCount = Integer.getInteger("threadCount", 4);
    private final int nestedCacheCount = Integer.getInteger("nestedCacheCount", 15);

    public static void main(String[] args) {
        try {
            new MemoryAwareCacheSupportPressureBenchmark().run();
        } catch (OutOfMemoryError e) {
            System.err.printf("%nFAILED: cache pressure benchmark ran out of heap: %s%n", e);
            e.printStackTrace(System.err);
            System.exit(1);
        } catch (Throwable e) {
            System.err.printf("%nFAILED: %s%n", e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run() throws Exception {
        System.out.printf(Locale.ROOT,
                          "MemoryAwareCacheSupportPressureBenchmark: maxHeap=%s, payload=%s, smallPayload=%s, "
                          + "pressureIterations=%d, boundedIterations=%d, concurrentIterations=%d, threads=%d, "
                          + "nestedCaches=%d%n",
                          formatBytes(maxHeap), formatBytes(payloadBytes), formatBytes(smallPayloadBytes),
                          pressureIterations, boundedIterations, concurrentIterations, threadCount, nestedCacheCount);
        if (maxHeap > 512L * MIB) {
            System.out.println("WARNING: this benchmark is most useful with -Xmx128m or -Xmx256m");
        }

        long started = System.nanoTime();
        Result weighted = runScenario("weighted bounded payload churn", this::weightedBoundedPayloadChurn);
        Result pressure = runScenario("weighted JVM-pressure eviction", this::weightedJvmPressureEviction);
        Result countBased = runScenario("count-based object JVM-pressure eviction", this::countBasedObjectPressure);
        Result nestedCountBased = runScenario("nested count-based JVM-pressure eviction",
                                              this::nestedCountBasedObjectPressure);
        Result nestedWeighted = runScenario("nested weighted JVM-pressure eviction",
                                            this::nestedWeightedPressure);
        Result concurrent = runScenario("concurrent weighted churn", this::concurrentWeightedChurn);

        require(weighted.sizeEvictions() > 0, "bounded weighted scenario did not evict by size");
        require(pressure.memoryPressureEvictions() > 0,
                "weighted pressure scenario did not observe memory-pressure eviction; rerun with a smaller heap or "
                + "larger payload");
        require(countBased.memoryPressureEvictions() > 0,
                "count-based object scenario did not observe memory-pressure eviction; rerun with a smaller heap or "
                + "larger payload");
        require(nestedCountBased.memoryPressureEvictions() > 0,
                "nested count-based scenario did not observe memory-pressure eviction; rerun with a smaller heap or "
                + "larger payload");
        require(nestedWeighted.memoryPressureEvictions() > 0,
                "nested weighted scenario did not observe memory-pressure eviction; rerun with a smaller heap or "
                + "larger payload");
        require(concurrent.sizeEvictions() > 0, "concurrent weighted scenario did not evict by size");

        System.out.printf(Locale.ROOT, "PASS all scenarios in %.0f ms%n", elapsedMillis(started));
    }

    private Result runScenario(String name, Scenario scenario) throws Exception {
        System.gc();
        sleep(50);
        long started = System.nanoTime();
        long gcBefore = totalGcMillis();
        long usedBefore = usedHeap();

        Result result = scenario.run();

        long elapsedMillis = Math.max(1L, Math.round(elapsedMillis(started)));
        long gcMillis = Math.max(0L, totalGcMillis() - gcBefore);
        long usedAfter = usedHeap();
        System.out.printf(Locale.ROOT,
                          "%-44s %6d ms  ops=%7d  finalSize=%6d  finalWeight=%10s  "
                          + "sizeEvictions=%6d  pressureEvictions=%6d  gc=%5d ms  heap=%s -> %s%n",
                          name, elapsedMillis, result.operations(), result.finalSize(),
                          formatBytes(result.finalWeight()), result.sizeEvictions(),
                          result.memoryPressureEvictions(), gcMillis,
                          formatBytes(usedBefore), formatBytes(usedAfter));
        return result;
    }

    private Result weightedBoundedPayloadChurn() {
        long maxWeight = Long.getLong("boundedMaxWeightBytes", boundedMaxWeight());
        long maxEntryWeight = Long.getLong("maxEntryBytes", Math.max(payloadBytes, 16L * MIB));
        require(maxWeight >= payloadBytes * 2L,
                "boundedMaxWeightBytes must fit at least two payloads for this scenario");
        EvictionCounters counters = new EvictionCounters();
        try (MemoryAwareCacheSupport<Integer, byte[]> cache = new MemoryAwareCacheSupport<>(
                maxWeight, maxEntryWeight, (key, value) -> value.length, null,
                MemoryPressureController.jvm(), MONITOR_INTERVAL)) {
            cache.registerEvictionListener(counters::record);
            for (int i = 0; i < boundedIterations; i++) {
                require(cache.put(i, payload(payloadBytes, i)), "weighted bounded cache rejected payload " + i);
                require(cache.weight() <= maxWeight, "weighted bounded cache exceeded max weight");
            }
            return counters.result(boundedIterations, cache.size(), cache.weight());
        }
    }

    private Result weightedJvmPressureEviction() {
        long maxWeight = Long.getLong("pressureMaxWeightBytes",
                                      Math.max(maxHeap * 4L, payloadBytes * 32L));
        long maxEntryWeight = Long.getLong("maxEntryBytes", Math.max(payloadBytes, 16L * MIB));
        EvictionCounters counters = new EvictionCounters();
        try (MemoryAwareCacheSupport<Integer, byte[]> cache = new MemoryAwareCacheSupport<>(
                maxWeight, maxEntryWeight, (key, value) -> value.length, null,
                MemoryPressureController.jvm(), MONITOR_INTERVAL)) {
            cache.registerEvictionListener(counters::record);
            for (int i = 0; i < pressureIterations; i++) {
                require(cache.put(i, payload(payloadBytes, i)), "weighted pressure cache rejected payload " + i);
                require(cache.weight() <= maxWeight, "weighted pressure cache exceeded max weight");
            }
            waitForBackgroundPressureIfNeeded(cache, counters);
            return counters.result(pressureIterations, cache.size(), cache.weight());
        }
    }

    private Result countBasedObjectPressure() {
        EvictionCounters counters = new EvictionCounters();
        int objectMaxSize = Integer.getInteger("objectMaxSize", 1_000_000);
        AdaptiveObjectCache cache = new AdaptiveObjectCache(objectMaxSize, MemoryPressureController.jvm());
        try {
            cache.registerEvictionListener(counters::record);
            for (int i = 0; i < pressureIterations; i++) {
                cache.put(i, payload(payloadBytes, i));
                require(cache.size() <= objectMaxSize, "count-based object cache exceeded max size");
            }
            waitForBackgroundPressureIfNeeded(cache, counters);
            return counters.result(pressureIterations, cache.size(), cache.weight());
        } finally {
            cache.close();
        }
    }

    private Result nestedCountBasedObjectPressure() {
        EvictionCounters counters = new EvictionCounters();
        int objectMaxSize = Integer.getInteger("objectMaxSize", 1_000_000);
        List<AdaptiveObjectCache> caches = new ArrayList<>(nestedCacheCount);
        try {
            for (int i = 0; i < nestedCacheCount; i++) {
                AdaptiveObjectCache cache = new AdaptiveObjectCache(objectMaxSize, MemoryPressureController.jvm());
                cache.registerEvictionListener(counters::record);
                caches.add(cache);
            }
            for (int i = 0; i < pressureIterations; i++) {
                AdaptiveObjectCache cache = caches.get(i % nestedCacheCount);
                cache.put(i, payload(payloadBytes, i));
                require(cache.size() <= objectMaxSize, "nested count-based object cache exceeded max size");
            }
            waitForBackgroundPressureIfNeeded(caches, counters);
            return counters.result(pressureIterations, totalSize(caches), totalWeight(caches));
        } finally {
            caches.forEach(AdaptiveObjectCache::close);
        }
    }

    private Result nestedWeightedPressure() {
        long maxWeight = Long.getLong("nestedWeightedMaxWeightBytes",
                                      Math.max(maxHeap * 4L, payloadBytes * 32L));
        long maxEntryWeight = Long.getLong("maxEntryBytes", Math.max(payloadBytes, 16L * MIB));
        require(maxEntryWeight >= payloadBytes, "maxEntryBytes must fit the configured payload");
        EvictionCounters counters = new EvictionCounters();
        List<MemoryAwareCacheSupport<Integer, byte[]>> caches = new ArrayList<>(nestedCacheCount);
        try {
            for (int i = 0; i < nestedCacheCount; i++) {
                MemoryAwareCacheSupport<Integer, byte[]> cache = new MemoryAwareCacheSupport<>(
                        maxWeight, maxEntryWeight, (key, value) -> value.length, null,
                        MemoryPressureController.jvm(), MONITOR_INTERVAL);
                cache.registerEvictionListener(counters::record);
                caches.add(cache);
            }
            for (int i = 0; i < pressureIterations; i++) {
                MemoryAwareCacheSupport<Integer, byte[]> cache = caches.get(i % nestedCacheCount);
                require(cache.put(i, payload(payloadBytes, i)), "nested weighted cache rejected payload " + i);
                require(cache.weight() <= maxWeight, "nested weighted cache exceeded max weight");
            }
            waitForBackgroundPressureIfNeededSupport(caches, counters);
            return counters.result(pressureIterations, totalSupportSize(caches), totalSupportWeight(caches));
        } finally {
            caches.forEach(MemoryAwareCacheSupport::close);
        }
    }

    private Result concurrentWeightedChurn() throws Exception {
        long maxWeight = Long.getLong("concurrentMaxWeightBytes",
                                      Math.min(64L * MIB, Math.max(8L * MIB, maxHeap / 4L)));
        long maxEntryWeight = Long.getLong("concurrentMaxEntryBytes", Math.max(smallPayloadBytes, 1L * MIB));
        require(maxWeight >= smallPayloadBytes * 2L,
                "concurrentMaxWeightBytes must fit at least two payloads for this scenario");
        require(maxEntryWeight >= smallPayloadBytes,
                "concurrentMaxEntryBytes must fit the configured small payload");
        EvictionCounters counters = new EvictionCounters();
        try (MemoryAwareCacheSupport<Integer, byte[]> cache = new MemoryAwareCacheSupport<>(
                maxWeight, maxEntryWeight, (key, value) -> value.length, null,
                MemoryPressureController.jvm(), MONITOR_INTERVAL)) {
            cache.registerEvictionListener(counters::record);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(threadCount)) {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int thread = 0; thread < threadCount; thread++) {
                    int threadId = thread;
                    futures.add(executor.submit(() -> {
                        start.await();
                        for (int i = 0; i < concurrentIterations; i++) {
                            int key = threadId * concurrentIterations + i;
                            cache.put(key, payload(smallPayloadBytes, key));
                            cache.get(key - threadCount);
                            if ((i & 31) == 0) {
                                cache.remove(key - 64);
                            }
                            require(cache.weight() <= maxWeight, "concurrent cache exceeded max weight");
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            }
            return counters.result((long) threadCount * concurrentIterations, cache.size(), cache.weight());
        }
    }

    private void waitForBackgroundPressureIfNeeded(MemoryAwareCacheSupport<?, ?> cache, EvictionCounters counters) {
        if (counters.memoryPressureEvictions.sum() > 0 || usedHeap() < maxHeap * 85L / 100L) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            cache.trimForMemoryPressure();
            if (counters.memoryPressureEvictions.sum() > 0) {
                return;
            }
            sleep(25);
        } while (System.nanoTime() < deadline);
    }

    private void waitForBackgroundPressureIfNeeded(AdaptiveObjectCache cache, EvictionCounters counters) {
        if (counters.memoryPressureEvictions.sum() > 0 || usedHeap() < maxHeap * 85L / 100L) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            cache.trimForMemoryPressure();
            if (counters.memoryPressureEvictions.sum() > 0) {
                return;
            }
            sleep(25);
        } while (System.nanoTime() < deadline);
    }

    private void waitForBackgroundPressureIfNeeded(List<AdaptiveObjectCache> caches, EvictionCounters counters) {
        if (counters.memoryPressureEvictions.sum() > 0 || usedHeap() < maxHeap * 85L / 100L || caches.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            caches.getFirst().trimForMemoryPressure();
            if (counters.memoryPressureEvictions.sum() > 0) {
                return;
            }
            sleep(25);
        } while (System.nanoTime() < deadline);
    }

    private void waitForBackgroundPressureIfNeededSupport(List<MemoryAwareCacheSupport<Integer, byte[]>> caches,
                                                          EvictionCounters counters) {
        if (counters.memoryPressureEvictions.sum() > 0 || usedHeap() < maxHeap * 85L / 100L || caches.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            caches.getFirst().trimForMemoryPressure();
            if (counters.memoryPressureEvictions.sum() > 0) {
                return;
            }
            sleep(25);
        } while (System.nanoTime() < deadline);
    }

    private static int totalSize(List<AdaptiveObjectCache> caches) {
        int result = 0;
        for (AdaptiveObjectCache cache : caches) {
            result += cache.size();
        }
        return result;
    }

    private static long totalWeight(List<AdaptiveObjectCache> caches) {
        long result = 0L;
        for (AdaptiveObjectCache cache : caches) {
            result += cache.weight();
        }
        return result;
    }

    private static int totalSupportSize(List<MemoryAwareCacheSupport<Integer, byte[]>> caches) {
        int result = 0;
        for (MemoryAwareCacheSupport<Integer, byte[]> cache : caches) {
            result += cache.size();
        }
        return result;
    }

    private static long totalSupportWeight(List<MemoryAwareCacheSupport<Integer, byte[]>> caches) {
        long result = 0L;
        for (MemoryAwareCacheSupport<Integer, byte[]> cache : caches) {
            result += cache.weight();
        }
        return result;
    }

    private int defaultPressureIterations() {
        long targetBytes = Math.max(128L * MIB, maxHeap * 3L / 2L);
        long iterations = Math.max(32L, targetBytes / Math.max(1L, payloadBytes));
        return (int) Math.min(240L, iterations);
    }

    private long boundedMaxWeight() {
        return Math.max(payloadBytes * 3L, Math.min(128L * MIB, maxHeap / 2L));
    }

    private static byte[] payload(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i += 4096) {
            bytes[i] = (byte) (seed + i);
        }
        bytes[bytes.length - 1] = (byte) seed;
        blackhole += bytes[0] + bytes[bytes.length - 1];
        return bytes;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long totalGcMillis() {
        long result = 0L;
        for (GarbageCollectorMXBean garbageCollector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long collectionTime = garbageCollector.getCollectionTime();
            if (collectionTime > 0L) {
                result += collectionTime;
            }
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static double elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000d;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= MIB) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) MIB);
        }
        if (bytes >= KIB) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / (double) KIB);
        }
        return bytes + " B";
    }

    @FunctionalInterface
    private interface Scenario {
        Result run() throws Exception;
    }

    private record Result(long operations, int finalSize, long finalWeight, long sizeEvictions,
                          long memoryPressureEvictions) {
    }

    private static class EvictionCounters {
        private final LongAdder sizeEvictions = new LongAdder();
        private final LongAdder memoryPressureEvictions = new LongAdder();

        void record(MemoryAwareCacheSupportEviction<?, ?> event) {
            switch (event.reason()) {
                case size, entryTooLarge -> sizeEvictions.increment();
                case memoryPressure -> memoryPressureEvictions.increment();
                case manual, expiry -> {
                }
            }
        }

        void record(CacheEviction event) {
            switch (event.getReason()) {
                case size -> sizeEvictions.increment();
                case memoryPressure -> memoryPressureEvictions.increment();
                case manual, expiry -> {
                }
            }
        }

        Result result(long operations, int finalSize, long finalWeight) {
            return new Result(operations, finalSize, finalWeight, sizeEvictions.sum(),
                              memoryPressureEvictions.sum());
        }
    }
}
