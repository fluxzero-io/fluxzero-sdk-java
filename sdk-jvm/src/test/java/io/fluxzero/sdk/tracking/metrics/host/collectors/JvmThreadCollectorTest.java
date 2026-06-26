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

import io.fluxzero.sdk.tracking.metrics.host.events.JvmThreadMetrics;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JvmThreadCollectorTest {

    @Test
    void isAvailable_returnsTrue() {
        var collector = new JvmThreadCollector();
        assertTrue(collector.isAvailable());
    }

    @Test
    void collect_returnsThreadMetrics() {
        var collector = new JvmThreadCollector();

        Optional<JvmThreadMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        JvmThreadMetrics metrics = result.get();

        assertTrue(metrics.getThreadCount() > 0, "Thread count should be positive");
        assertTrue(metrics.getDaemonCount() >= 0, "Daemon count should be non-negative");
        assertTrue(metrics.getPeakCount() >= metrics.getThreadCount(),
                "Peak count should be >= current count");
        assertTrue(metrics.getTotalStartedCount() >= metrics.getThreadCount(),
                "Total started should be >= current count");
    }

    @Test
    void collect_threadStateDistributionIsComplete() {
        var collector = new JvmThreadCollector();

        Optional<JvmThreadMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        var distribution = result.get().getThreadStateDistribution();

        assertNotNull(distribution, "Thread state distribution should not be null");
        assertEquals(6, distribution.size(), "Should have all 6 thread states");

        for (Thread.State state : Thread.State.values()) {
            assertTrue(distribution.containsKey(state.name()),
                    "Distribution should contain state: " + state.name());
            assertTrue(distribution.get(state.name()) >= 0,
                    "State count should be non-negative");
        }
    }
}
