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

import io.fluxzero.sdk.tracking.metrics.host.events.CpuMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Optional;

/**
 * Collector for CPU metrics using JMX.
 * <p>
 * Uses the com.sun.management extension when available for detailed CPU metrics.
 */
public class CpuCollector implements MetricCollector<CpuMetrics> {

    private final OperatingSystemMXBean osMXBean;
    private final boolean hasSunExtension;

    public CpuCollector() {
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.hasSunExtension = osMXBean instanceof com.sun.management.OperatingSystemMXBean;
    }

    @Override
    public Optional<CpuMetrics> collect() {
        try {
            var builder = CpuMetrics.builder()
                    .availableProcessors(osMXBean.getAvailableProcessors());

            double loadAverage = osMXBean.getSystemLoadAverage();
            if (loadAverage >= 0) {
                builder.systemLoadAverage(loadAverage);
            }

            if (hasSunExtension) {
                var sunOsMXBean = (com.sun.management.OperatingSystemMXBean) osMXBean;

                double processCpuLoad = sunOsMXBean.getProcessCpuLoad();
                if (processCpuLoad >= 0) {
                    builder.processCpuUsage(processCpuLoad);
                }

                double systemCpuLoad = sunOsMXBean.getCpuLoad();
                if (systemCpuLoad >= 0) {
                    builder.systemCpuUsage(systemCpuLoad);
                }

                long processCpuTime = sunOsMXBean.getProcessCpuTime();
                if (processCpuTime >= 0) {
                    builder.processCpuTimeNanos(processCpuTime);
                }
            }

            return Optional.of(builder.build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
