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

import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.publishing.MetricsGateway;
import io.fluxzero.sdk.tracking.metrics.host.events.HostMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HostMetricsCollectorTest {

    private MetricsGateway metricsGateway;
    private InMemoryTaskScheduler taskScheduler;
    private HostMetricsConfiguration configuration;

    @BeforeEach
    void setUp() {
        metricsGateway = mock(MetricsGateway.class);
        taskScheduler = new InMemoryTaskScheduler("test", Clock.systemUTC(),
                Executors.newSingleThreadExecutor());
        configuration = HostMetricsConfiguration.builder()
                .collectionInterval(Duration.ofSeconds(1))
                .applicationName("test-app")
                .hostname("test-host")
                .instanceId("test-instance")
                .build();
    }

    @Test
    void start_beginsCollection() {
        var collector = new HostMetricsCollector(configuration, metricsGateway, taskScheduler);

        assertFalse(collector.isRunning());
        collector.start();
        assertTrue(collector.isRunning());
    }

    @Test
    void stop_stopsCollection() {
        var collector = new HostMetricsCollector(configuration, metricsGateway, taskScheduler);

        collector.start();
        assertTrue(collector.isRunning());

        collector.stop();
        assertFalse(collector.isRunning());
    }

    @Test
    void collectAndPublishNow_publishesMetrics() {
        var collector = new HostMetricsCollector(configuration, metricsGateway, taskScheduler);

        collector.collectAndPublishNow();

        var metricsCaptor = ArgumentCaptor.forClass(Object.class);
        var metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
        verify(metricsGateway).publish(metricsCaptor.capture(), metadataCaptor.capture());

        assertTrue(metricsCaptor.getValue() instanceof HostMetrics);
        HostMetrics metrics = (HostMetrics) metricsCaptor.getValue();

        assertNotNull(metrics.getTimestamp());
        assertNotNull(metrics.getMemory());
        assertNotNull(metrics.getGc());
        assertNotNull(metrics.getThreads());
        assertNotNull(metrics.getClasses());
        assertNotNull(metrics.getCpu());
        assertNotNull(metrics.getUptime());

        Metadata metadata = metadataCaptor.getValue();
        assertEquals("test-host", metadata.get("hostname", String.class));
        assertEquals("test-app", metadata.get("applicationName", String.class));
        assertEquals("test-instance", metadata.get("instanceId", String.class));
    }

    @Test
    void collect_respectsDisabledCollectors() {
        var config = HostMetricsConfiguration.builder()
                .collectionInterval(Duration.ofSeconds(1))
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

        var collector = new HostMetricsCollector(config, metricsGateway, taskScheduler);

        collector.collectAndPublishNow();

        var metricsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(metricsGateway).publish(metricsCaptor.capture(), any(Metadata.class));

        HostMetrics metrics = (HostMetrics) metricsCaptor.getValue();

        assertNotNull(metrics.getTimestamp());
        assertNull(metrics.getMemory(), "Memory should be null when disabled");
        assertNull(metrics.getGc(), "GC should be null when disabled");
        assertNull(metrics.getThreads(), "Threads should be null when disabled");
        assertNull(metrics.getClasses(), "Classes should be null when disabled");
        assertNull(metrics.getCpu(), "CPU should be null when disabled");
        assertNull(metrics.getFileDescriptors(), "File descriptors should be null when disabled");
        assertNull(metrics.getUptime(), "Uptime should be null when disabled");
        assertNull(metrics.getDisk(), "Disk should be null when disabled");
        assertNull(metrics.getContainer(), "Container should be null when disabled");
    }

    @Test
    void start_isIdempotent() {
        var collector = new HostMetricsCollector(configuration, metricsGateway, taskScheduler);

        collector.start();
        collector.start();
        collector.start();

        assertTrue(collector.isRunning());
    }

    @Test
    void stop_isIdempotent() {
        var collector = new HostMetricsCollector(configuration, metricsGateway, taskScheduler);

        collector.start();
        collector.stop();
        collector.stop();
        collector.stop();

        assertFalse(collector.isRunning());
    }
}
