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

import io.fluxzero.common.application.DefaultPropertySource;
import io.fluxzero.common.application.PropertySource;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Determines whether caches should proactively shed entries.
 */
@FunctionalInterface
public interface MemoryPressureController {
    /**
     * Heap usage percentage that triggers JVM-wide memory-aware cache trimming. Defaults to {@code 85}.
     */
    String MEMORY_PRESSURE_HEAP_THRESHOLD_PERCENT_PROPERTY =
            "fluxzero.cache.memoryPressure.heapThresholdPercent";
    /**
     * GC-time percentage over the sampling window that triggers JVM-wide memory-aware cache trimming. Defaults to
     * {@code 20}.
     */
    String MEMORY_PRESSURE_GC_TIME_THRESHOLD_PERCENT_PROPERTY =
            "fluxzero.cache.memoryPressure.gcTimeThresholdPercent";
    /**
     * Percentage of the registered memory-aware cache weight to evict per observed memory-pressure pass. Defaults to
     * {@code 20}.
     */
    String MEMORY_PRESSURE_TRIM_RATIO_PERCENT_PROPERTY =
            "fluxzero.cache.memoryPressure.trimRatioPercent";
    /**
     * Maximum cache weight to evict per observed memory-pressure pass. For byte-weighted caches this is bytes; for
     * count-weighted caches this is entries. Defaults to {@code 1 GiB}.
     */
    String MEMORY_PRESSURE_MAX_TRIM_WEIGHT_PROPERTY =
            "fluxzero.cache.memoryPressure.maxTrimWeight";

    MemoryPressureController NONE = (currentWeight, maxWeight) -> false;

    boolean shouldEvict(long currentWeight, long maxWeight);

    default int trimRatioPercent() {
        return JvmMemoryPressureController.DEFAULT_TRIM_RATIO_PERCENT;
    }

    default long maxTrimWeight() {
        return JvmMemoryPressureController.DEFAULT_MAX_TRIM_WEIGHT;
    }

    static MemoryPressureController none() {
        return NONE;
    }

    static MemoryPressureController jvm() {
        return new JvmMemoryPressureController();
    }

    static MemoryPressureController jvm(PropertySource propertySource) {
        return new JvmMemoryPressureController(propertySource);
    }

    class JvmMemoryPressureController implements MemoryPressureController {
        public static final int DEFAULT_HEAP_USAGE_THRESHOLD_PERCENT = 85;
        public static final int DEFAULT_GC_TIME_THRESHOLD_PERCENT = 20;
        public static final int DEFAULT_TRIM_RATIO_PERCENT = 20;
        public static final long DEFAULT_MAX_TRIM_WEIGHT = 1024L * 1024L * 1024L;
        private static final long MIN_GC_SAMPLE_WINDOW_MILLIS = 100L;

        private final LongSupplier nanoTimeSupplier;
        private final LongSupplier maxMemorySupplier;
        private final LongSupplier usedMemorySupplier;
        private final LongSupplier collectionMillisSupplier;
        private final int heapUsageThresholdPercent;
        private final int gcTimeThresholdPercent;
        private final int trimRatioPercent;
        private final long maxTrimWeight;
        private volatile long lastCheckNanos;
        private volatile long lastCollectionMillis;

        JvmMemoryPressureController() {
            this(DefaultPropertySource.getInstance());
        }

        JvmMemoryPressureController(PropertySource propertySource) {
            this(propertySource, System::nanoTime,
                 () -> Runtime.getRuntime().maxMemory(),
                 () -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                 collectionMillisSupplier(ManagementFactory.getGarbageCollectorMXBeans()));
        }

        JvmMemoryPressureController(PropertySource propertySource,
                                    LongSupplier nanoTimeSupplier, LongSupplier maxMemorySupplier,
                                    LongSupplier usedMemorySupplier, LongSupplier collectionMillisSupplier) {
            this(nanoTimeSupplier,
                 maxMemorySupplier,
                 usedMemorySupplier,
                 collectionMillisSupplier,
                 propertyPercent(propertySource, MEMORY_PRESSURE_HEAP_THRESHOLD_PERCENT_PROPERTY,
                                 DEFAULT_HEAP_USAGE_THRESHOLD_PERCENT),
                 propertyPercent(propertySource, MEMORY_PRESSURE_GC_TIME_THRESHOLD_PERCENT_PROPERTY,
                                 DEFAULT_GC_TIME_THRESHOLD_PERCENT),
                 propertyPercent(propertySource, MEMORY_PRESSURE_TRIM_RATIO_PERCENT_PROPERTY,
                                 DEFAULT_TRIM_RATIO_PERCENT),
                 propertyLong(propertySource, MEMORY_PRESSURE_MAX_TRIM_WEIGHT_PROPERTY,
                              DEFAULT_MAX_TRIM_WEIGHT));
        }

        JvmMemoryPressureController(LongSupplier nanoTimeSupplier, LongSupplier maxMemorySupplier,
                                    LongSupplier usedMemorySupplier, LongSupplier collectionMillisSupplier) {
            this(nanoTimeSupplier, maxMemorySupplier, usedMemorySupplier, collectionMillisSupplier,
                 DEFAULT_HEAP_USAGE_THRESHOLD_PERCENT, DEFAULT_GC_TIME_THRESHOLD_PERCENT, DEFAULT_TRIM_RATIO_PERCENT,
                 DEFAULT_MAX_TRIM_WEIGHT);
        }

        JvmMemoryPressureController(LongSupplier nanoTimeSupplier, LongSupplier maxMemorySupplier,
                                    LongSupplier usedMemorySupplier, LongSupplier collectionMillisSupplier,
                                    int heapUsageThresholdPercent, int gcTimeThresholdPercent,
                                    int trimRatioPercent, long maxTrimWeight) {
            this.nanoTimeSupplier = nanoTimeSupplier;
            this.maxMemorySupplier = maxMemorySupplier;
            this.usedMemorySupplier = usedMemorySupplier;
            this.collectionMillisSupplier = collectionMillisSupplier;
            this.heapUsageThresholdPercent = validatePercent(MEMORY_PRESSURE_HEAP_THRESHOLD_PERCENT_PROPERTY,
                                                             heapUsageThresholdPercent);
            this.gcTimeThresholdPercent = validatePercent(MEMORY_PRESSURE_GC_TIME_THRESHOLD_PERCENT_PROPERTY,
                                                          gcTimeThresholdPercent);
            this.trimRatioPercent = validatePercent(MEMORY_PRESSURE_TRIM_RATIO_PERCENT_PROPERTY, trimRatioPercent);
            this.maxTrimWeight = validatePositiveLong(MEMORY_PRESSURE_MAX_TRIM_WEIGHT_PROPERTY, maxTrimWeight);
            this.lastCheckNanos = nanoTimeSupplier.getAsLong();
            this.lastCollectionMillis = collectionMillisSupplier.getAsLong();
        }

        @Override
        public boolean shouldEvict(long currentWeight, long maxWeight) {
            if (currentWeight > maxWeight) {
                return true;
            }
            return shouldEvictAll(currentWeight, maxWeight);
        }

        boolean shouldEvictAll(long currentWeight, long maxWeight) {
            return heapUsageLooksHigh() || gcTimeLooksHigh();
        }

        @Override
        public int trimRatioPercent() {
            return trimRatioPercent;
        }

        @Override
        public long maxTrimWeight() {
            return maxTrimWeight;
        }

        private boolean heapUsageLooksHigh() {
            long maxMemory = maxMemorySupplier.getAsLong();
            long usedMemory = usedMemorySupplier.getAsLong();
            return maxMemory > 0L && usedMemory * 100L / maxMemory >= heapUsageThresholdPercent;
        }

        private boolean gcTimeLooksHigh() {
            long nowNanos = nanoTimeSupplier.getAsLong();
            long collectionMillis = collectionMillisSupplier.getAsLong();
            long elapsedMillis = (nowNanos - lastCheckNanos) / 1_000_000L;
            long collectionDelta = Math.max(0L, collectionMillis - lastCollectionMillis);
            lastCheckNanos = nowNanos;
            lastCollectionMillis = collectionMillis;
            return elapsedMillis >= MIN_GC_SAMPLE_WINDOW_MILLIS
                   && collectionDelta * 100L / elapsedMillis >= gcTimeThresholdPercent;
        }

        private static LongSupplier collectionMillisSupplier(List<GarbageCollectorMXBean> garbageCollectors) {
            return () -> {
                long result = 0L;
                for (GarbageCollectorMXBean garbageCollector : garbageCollectors) {
                    long collectionTime = garbageCollector.getCollectionTime();
                    if (collectionTime > 0L) {
                        result += collectionTime;
                    }
                }
                return result;
            };
        }

        private static int propertyPercent(PropertySource propertySource, String property, int defaultValue) {
            String value = propertySource == null ? null : propertySource.get(property);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return validatePercent(property, Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Property `%s` must be an integer percentage between 1 and 100, but found `%s`"
                                .formatted(property, value), e);
            }
        }

        private static long propertyLong(PropertySource propertySource, String property, long defaultValue) {
            String value = propertySource == null ? null : propertySource.get(property);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return validatePositiveLong(property, Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Property `%s` must be a positive long, but found `%s`".formatted(property, value), e);
            }
        }

        private static int validatePercent(String property, int value) {
            if (value < 1 || value > 100) {
                throw new IllegalArgumentException("Property `%s` must be between 1 and 100, but found `%d`"
                                                           .formatted(property, value));
            }
            return value;
        }

        private static long validatePositiveLong(String property, long value) {
            if (value < 1L) {
                throw new IllegalArgumentException("Property `%s` must be positive, but found `%d`"
                                                           .formatted(property, value));
            }
            return value;
        }
    }
}
