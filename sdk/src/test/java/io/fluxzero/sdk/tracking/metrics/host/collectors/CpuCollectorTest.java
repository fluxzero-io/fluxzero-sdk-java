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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CpuCollectorTest {

    @Test
    void isAvailable_returnsTrue() {
        var collector = new CpuCollector();
        assertTrue(collector.isAvailable());
    }

    @Test
    void collect_returnsCpuMetrics() {
        var collector = new CpuCollector();

        Optional<CpuMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        CpuMetrics metrics = result.get();

        assertTrue(metrics.getAvailableProcessors() > 0, "Available processors should be positive");
    }

    @Test
    void collect_cpuUsageIsInValidRange() {
        var collector = new CpuCollector();

        Optional<CpuMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        CpuMetrics metrics = result.get();

        if (metrics.getProcessCpuUsage() != null) {
            assertTrue(metrics.getProcessCpuUsage() >= 0.0 && metrics.getProcessCpuUsage() <= 1.0,
                    "Process CPU usage should be between 0 and 1");
        }

        if (metrics.getSystemCpuUsage() != null) {
            assertTrue(metrics.getSystemCpuUsage() >= 0.0 && metrics.getSystemCpuUsage() <= 1.0,
                    "System CPU usage should be between 0 and 1");
        }

        if (metrics.getProcessCpuTimeNanos() != null) {
            assertTrue(metrics.getProcessCpuTimeNanos() >= 0,
                    "Process CPU time should be non-negative");
        }
    }
}
