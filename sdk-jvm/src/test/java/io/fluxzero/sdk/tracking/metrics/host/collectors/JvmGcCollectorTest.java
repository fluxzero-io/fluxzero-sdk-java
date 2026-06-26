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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JvmGcCollectorTest {

    @Test
    void isAvailable_returnsTrue() {
        var collector = new JvmGcCollector();
        assertTrue(collector.isAvailable());
    }

    @Test
    void collect_returnsGcMetrics() {
        var collector = new JvmGcCollector();

        Optional<JvmGcMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        JvmGcMetrics metrics = result.get();

        assertNotNull(metrics.getCollectors(), "GC collectors should not be null");
        assertFalse(metrics.getCollectors().isEmpty(), "GC collectors should not be empty");
    }

    @Test
    void collect_gcCollectorsHaveValidData() {
        var collector = new JvmGcCollector();

        Optional<JvmGcMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        for (var gc : result.get().getCollectors()) {
            assertNotNull(gc.getName(), "GC name should not be null");
            assertTrue(gc.getCollectionCount() >= 0, "Collection count should be non-negative");
            assertTrue(gc.getCollectionTimeMs() >= 0, "Collection time should be non-negative");
        }
    }
}
