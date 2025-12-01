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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JvmMemoryCollectorTest {

    @Test
    void isAvailable_returnsTrue() {
        var collector = new JvmMemoryCollector();
        assertTrue(collector.isAvailable());
    }

    @Test
    void collect_returnsMemoryMetrics() {
        var collector = new JvmMemoryCollector();

        Optional<JvmMemoryMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        JvmMemoryMetrics metrics = result.get();

        assertTrue(metrics.getHeapUsed() > 0, "Heap used should be positive");
        assertTrue(metrics.getHeapCommitted() > 0, "Heap committed should be positive");
        assertNotNull(metrics.getPools(), "Memory pools should not be null");
        assertFalse(metrics.getPools().isEmpty(), "Memory pools should not be empty");
    }

    @Test
    void collect_memoryPoolsHaveValidData() {
        var collector = new JvmMemoryCollector();

        Optional<JvmMemoryMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        for (var pool : result.get().getPools()) {
            assertNotNull(pool.getName(), "Pool name should not be null");
            assertNotNull(pool.getType(), "Pool type should not be null");
            assertTrue("HEAP".equals(pool.getType()) || "NON_HEAP".equals(pool.getType()),
                    "Pool type should be HEAP or NON_HEAP");
            assertTrue(pool.getUsed() >= 0, "Pool used should be non-negative");
            assertTrue(pool.getCommitted() >= 0, "Pool committed should be non-negative");
        }
    }
}
