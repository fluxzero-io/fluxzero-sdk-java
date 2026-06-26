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

import io.fluxzero.sdk.tracking.metrics.host.events.JvmMemoryMetrics;
import io.fluxzero.sdk.tracking.metrics.host.events.JvmMemoryMetrics.MemoryPoolMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Optional;

/**
 * Collector for JVM memory metrics using JMX.
 */
public class JvmMemoryCollector implements MetricCollector<JvmMemoryMetrics> {

    private final MemoryMXBean memoryMXBean;
    private final List<MemoryPoolMXBean> memoryPoolMXBeans;

    public JvmMemoryCollector() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
    }

    @Override
    public Optional<JvmMemoryMetrics> collect() {
        try {
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

            List<MemoryPoolMetrics> pools = memoryPoolMXBeans.stream()
                    .map(this::toPoolMetrics)
                    .toList();

            return Optional.of(JvmMemoryMetrics.builder()
                    .heapUsed(heapUsage.getUsed())
                    .heapMax(heapUsage.getMax())
                    .heapCommitted(heapUsage.getCommitted())
                    .nonHeapUsed(nonHeapUsage.getUsed())
                    .nonHeapMax(nonHeapUsage.getMax())
                    .nonHeapCommitted(nonHeapUsage.getCommitted())
                    .pools(pools)
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private MemoryPoolMetrics toPoolMetrics(MemoryPoolMXBean pool) {
        MemoryUsage usage = pool.getUsage();
        return MemoryPoolMetrics.builder()
                .name(pool.getName())
                .type(pool.getType().name())
                .used(usage.getUsed())
                .max(usage.getMax())
                .committed(usage.getCommitted())
                .build();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
