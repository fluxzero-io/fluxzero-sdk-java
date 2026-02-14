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

package io.fluxzero.sdk.tracking.metrics.host;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostMetricsConfigurationTest {

    @Test
    void defaultConfiguration_hasCorrectDefaults() {
        var config = HostMetricsConfiguration.builder().build();

        assertEquals(Duration.ofSeconds(60), config.getCollectionInterval());
        assertTrue(config.isCollectJvmMemory());
        assertTrue(config.isCollectJvmGc());
        assertTrue(config.isCollectJvmThreads());
        assertTrue(config.isCollectJvmClasses());
        assertTrue(config.isCollectCpu());
        assertTrue(config.isCollectFileDescriptors());
        assertTrue(config.isCollectUptime());
        assertFalse(config.isCollectDisk());
        assertTrue(config.isCollectContainerMetrics());
        assertTrue(config.getDiskPaths().isEmpty());
    }

    @Test
    void customConfiguration_overridesDefaults() {
        var config = HostMetricsConfiguration.builder()
                .collectionInterval(Duration.ofMinutes(2))
                .collectJvmMemory(false)
                .collectDisk(true)
                .build();

        assertEquals(Duration.ofMinutes(2), config.getCollectionInterval());
        assertFalse(config.isCollectJvmMemory());
        assertTrue(config.isCollectDisk());
    }
}
