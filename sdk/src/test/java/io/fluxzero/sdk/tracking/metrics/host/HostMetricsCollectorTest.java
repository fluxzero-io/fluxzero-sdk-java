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

import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.metrics.host.events.HostMetrics;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostMetricsCollectorTest {

    HostMetricsConfiguration configuration = HostMetricsConfiguration.builder()
            .collectionInterval(Duration.ofSeconds(1))
            .build();
    HostMetricsCollector collector = new HostMetricsCollector(configuration, DefaultFluxzero.builder().build(
            LocalClient.newInstance()));

    @Test
    void start_beginsCollection() {
        assertFalse(collector.isRunning());
        collector.start();
        assertTrue(collector.isRunning());
    }

    @Test
    void stop_stopsCollection() {
        collector.start();
        assertTrue(collector.isRunning());

        collector.stop();
        assertFalse(collector.isRunning());
    }

    @Test
    void start_isIdempotent() {
        collector.start();
        collector.start();
        collector.start();
        assertTrue(collector.isRunning());
    }

    @Test
    void stop_isIdempotent() {
        collector.start();
        collector.stop();
        collector.stop();
        collector.stop();
        assertFalse(collector.isRunning());
    }

    @Nested
    class IntegrationTest {
        TestFixture testFixture = TestFixture.create(DefaultFluxzero.builder().enableHostMetrics(configuration));

        @Test
        void metricsArePublishedAfterDelay() {
            Instant start = testFixture.getCurrentTime();
            testFixture.whenTimeElapses(Duration.ofSeconds(1))
                    .<HostMetrics>expectMetric(m -> {
                        assertNotNull(m.getTimestamp());
                        assertNotNull(m.getMemory());
                        assertNotNull(m.getGc());
                        assertNotNull(m.getThreads());
                        assertNotNull(m.getClasses());
                        assertNotNull(m.getCpu());
                        assertNotNull(m.getUptime());
                        return m.getTimestamp().equals(start.plusSeconds(1));
                    })
                    .andThen()
                    .whenTimeElapses(Duration.ofSeconds(1))
                    .<HostMetrics>expectMetric(m -> m.getTimestamp().equals(start.plusSeconds(2)));
        }

        @Test
        void disabledCollectorsAreRespected() {
            configuration = configuration.toBuilder()
                    .collectJvmMemory(false)
                    .collectJvmGc(false)
                    .collectJvmThreads(false)
                    .collectJvmClasses(false)
                    .collectCpu(false)
                    .collectFileDescriptors(false)
                    .collectUptime(false)
                    .collectDisk(false)
                    .collectContainerMetrics(false)
                    .build();
            testFixture = TestFixture.create(DefaultFluxzero.builder().enableHostMetrics(configuration));
            Instant start = testFixture.getCurrentTime();
            testFixture.whenTimeElapses(Duration.ofSeconds(1))
                    .<HostMetrics>expectMetric(m -> {
                        assertNotNull(m.getTimestamp());
                        assertNull(m.getMemory(), "Memory should be null when disabled");
                        assertNull(m.getGc(), "GC should be null when disabled");
                        assertNull(m.getThreads(), "Threads should be null when disabled");
                        assertNull(m.getClasses(), "Classes should be null when disabled");
                        assertNull(m.getCpu(), "CPU should be null when disabled");
                        assertNull(m.getFileDescriptors(), "File descriptors should be null when disabled");
                        assertNull(m.getUptime(), "Uptime should be null when disabled");
                        assertNull(m.getDisk(), "Disk should be null when disabled");
                        assertNull(m.getContainer(), "Container should be null when disabled");
                        return m.getTimestamp().equals(start.plusSeconds(1));
                    });
        }
    }
}
