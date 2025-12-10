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

package io.fluxzero.sdk.tracking.metrics.host.events;

import io.fluxzero.common.api.JsonType;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * JVM memory metrics including heap, non-heap, and individual memory pool statistics.
 */
@Value
@Builder
public class JvmMemoryMetrics implements JsonType {

    /**
     * Current heap memory used in bytes.
     */
    long heapUsed;

    /**
     * Maximum heap memory in bytes, or -1 if undefined.
     */
    long heapMax;

    /**
     * Heap memory committed (guaranteed to be available) in bytes.
     */
    long heapCommitted;

    /**
     * Current non-heap memory used in bytes.
     */
    long nonHeapUsed;

    /**
     * Maximum non-heap memory in bytes, or -1 if undefined.
     */
    long nonHeapMax;

    /**
     * Non-heap memory committed in bytes.
     */
    long nonHeapCommitted;

    /**
     * Metrics for individual memory pools (e.g., Eden, Survivor, Old Gen, Metaspace).
     */
    List<MemoryPoolMetrics> pools;

    /**
     * Metrics for an individual JVM memory pool.
     */
    @Value
    @Builder
    public static class MemoryPoolMetrics implements JsonType {
        /**
         * The name of the memory pool (e.g., "G1 Eden Space", "Metaspace").
         */
        String name;

        /**
         * The type of the memory pool: "HEAP" or "NON_HEAP".
         */
        String type;

        /**
         * Current memory used in this pool in bytes.
         */
        long used;

        /**
         * Maximum memory for this pool in bytes, or -1 if undefined.
         */
        long max;

        /**
         * Memory committed for this pool in bytes.
         */
        long committed;
    }
}
