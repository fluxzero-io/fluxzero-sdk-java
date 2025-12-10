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
 * JVM garbage collection metrics for all available GC collectors.
 */
@Value
@Builder
public class JvmGcMetrics implements JsonType {

    /**
     * Metrics for each garbage collector.
     */
    List<GcCollectorMetrics> collectors;

    /**
     * Metrics for an individual garbage collector.
     */
    @Value
    @Builder
    public static class GcCollectorMetrics implements JsonType {
        /**
         * The name of the garbage collector (e.g., "G1 Young Generation", "G1 Old Generation").
         */
        String name;

        /**
         * The total number of collections that have occurred.
         */
        long collectionCount;

        /**
         * The approximate accumulated collection time in milliseconds.
         */
        long collectionTimeMs;
    }
}
