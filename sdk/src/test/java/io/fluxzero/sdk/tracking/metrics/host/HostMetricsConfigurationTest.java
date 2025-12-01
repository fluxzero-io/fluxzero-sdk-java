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

import static org.junit.jupiter.api.Assertions.*;

class HostMetricsConfigurationTest {

    @Test
    void defaultConfiguration_hasCorrectDefaults() {
        var config = HostMetricsConfiguration.builder().build();

        assertEquals(Duration.ofSeconds(30), config.getCollectionInterval());
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
                .collectionInterval(Duration.ofMinutes(1))
                .collectJvmMemory(false)
                .collectDisk(true)
                .applicationName("test-app")
                .hostname("test-host")
                .instanceId("test-instance")
                .build();

        assertEquals(Duration.ofMinutes(1), config.getCollectionInterval());
        assertFalse(config.isCollectJvmMemory());
        assertTrue(config.isCollectDisk());
        assertEquals("test-app", config.getApplicationName());
        assertEquals("test-host", config.getHostname());
        assertEquals("test-instance", config.getInstanceId());
    }

    @Test
    void detectHostname_returnsNonEmptyString() {
        String hostname = HostMetricsConfiguration.detectHostname();
        assertNotNull(hostname);
        assertFalse(hostname.isBlank());
    }

    @Test
    void generateInstanceId_returnsUniqueIds() {
        String id1 = HostMetricsConfiguration.generateInstanceId();
        String id2 = HostMetricsConfiguration.generateInstanceId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2, "Generated instance IDs should be unique");
    }
}
