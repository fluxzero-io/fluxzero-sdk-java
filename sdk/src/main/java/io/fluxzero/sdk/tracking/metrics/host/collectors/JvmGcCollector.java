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

package io.fluxzero.sdk.tracking.metrics.host.collectors;

import io.fluxzero.sdk.tracking.metrics.host.events.JvmGcMetrics;
import io.fluxzero.sdk.tracking.metrics.host.events.JvmGcMetrics.GcCollectorMetrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Optional;

/**
 * Collector for JVM garbage collection metrics using JMX.
 */
public class JvmGcCollector implements MetricCollector<JvmGcMetrics> {

    private final List<GarbageCollectorMXBean> gcBeans;

    public JvmGcCollector() {
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    }

    @Override
    public Optional<JvmGcMetrics> collect() {
        try {
            List<GcCollectorMetrics> collectors = gcBeans.stream()
                    .map(this::toCollectorMetrics)
                    .toList();

            return Optional.of(JvmGcMetrics.builder()
                    .collectors(collectors)
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private GcCollectorMetrics toCollectorMetrics(GarbageCollectorMXBean gc) {
        return GcCollectorMetrics.builder()
                .name(gc.getName())
                .collectionCount(gc.getCollectionCount())
                .collectionTimeMs(gc.getCollectionTime())
                .build();
    }

    @Override
    public boolean isAvailable() {
        return !gcBeans.isEmpty();
    }
}
