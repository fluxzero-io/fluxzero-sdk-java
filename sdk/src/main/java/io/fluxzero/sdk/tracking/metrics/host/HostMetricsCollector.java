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

import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.publishing.MetricsGateway;
import io.fluxzero.sdk.tracking.metrics.host.collectors.ContainerCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.CpuCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.DiskCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.FileDescriptorCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.JvmClassCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.JvmGcCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.JvmMemoryCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.JvmThreadCollector;
import io.fluxzero.sdk.tracking.metrics.host.collectors.UptimeCollector;
import io.fluxzero.sdk.tracking.metrics.host.events.HostMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates periodic collection and publication of host metrics.
 * <p>
 * This class manages the lifecycle of host metrics collection, scheduling periodic
 * collections and publishing the results via the {@link MetricsGateway}.
 */
@Slf4j
public class HostMetricsCollector {

    private final HostMetricsConfiguration configuration;
    private final Fluxzero fluxzero;

    // Collectors
    private final JvmMemoryCollector memoryCollector;
    private final JvmGcCollector gcCollector;
    private final JvmThreadCollector threadCollector;
    private final JvmClassCollector classCollector;
    private final CpuCollector cpuCollector;
    private final FileDescriptorCollector fileDescriptorCollector;
    private final UptimeCollector uptimeCollector;
    private final DiskCollector diskCollector;
    private final ContainerCollector containerCollector;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Registration> scheduledTask = new AtomicReference<>();

    public HostMetricsCollector(HostMetricsConfiguration configuration, Fluxzero fluxzero) {
        this.configuration = configuration;
        this.fluxzero = fluxzero;

        // Initialize collectors
        this.memoryCollector = new JvmMemoryCollector();
        this.gcCollector = new JvmGcCollector();
        this.threadCollector = new JvmThreadCollector();
        this.classCollector = new JvmClassCollector();
        this.cpuCollector = new CpuCollector();
        this.fileDescriptorCollector = new FileDescriptorCollector();
        this.uptimeCollector = new UptimeCollector();
        this.diskCollector = new DiskCollector(configuration.getDiskPaths());
        this.containerCollector = new ContainerCollector();

        fluxzero.beforeShutdown(this::stop);
    }

    /**
     * Starts periodic metrics collection.
     * <p>
     * If already running, this method does nothing.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.debug("Starting host metrics collection with interval {}", configuration.getCollectionInterval());
            scheduleNextCollection();
        }
    }

    /**
     * Stops metrics collection.
     * <p>
     * Cancels any pending scheduled collection. This method is safe to call multiple times.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.debug("Stopping host metrics collection");
            Registration task = scheduledTask.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
    }

    /**
     * Manually triggers a metrics collection and publication.
     * <p>
     * This method can be used for on-demand metrics collection independent of the
     * periodic schedule.
     */
    public void collectAndPublishNow() {
        collectAndPublish();
    }

    private void scheduleNextCollection() {
        if (running.get()) {
            Registration task = fluxzero.apply(fz -> fz.taskScheduler().schedule(configuration.getCollectionInterval(), this::collectAndPublish));
            scheduledTask.set(task);
        }
    }

    private void collectAndPublish() {
        fluxzero.execute(fz -> {
            try {
                HostMetrics metrics = collectMetrics();
                Fluxzero.publishMetrics(metrics);
            } catch (Exception e) {
                log.warn("Failed to collect or publish host metrics", e);
            } finally {
                scheduleNextCollection();
            }
        });
    }

    private HostMetrics collectMetrics() {
        Instant timestamp = fluxzero.clock().instant();

        var builder = HostMetrics.builder().timestamp(timestamp);

        if (configuration.isCollectJvmMemory() && memoryCollector.isAvailable()) {
            memoryCollector.collect().ifPresent(builder::memory);
        }

        if (configuration.isCollectJvmGc() && gcCollector.isAvailable()) {
            gcCollector.collect().ifPresent(builder::gc);
        }

        if (configuration.isCollectJvmThreads() && threadCollector.isAvailable()) {
            threadCollector.collect().ifPresent(builder::threads);
        }

        if (configuration.isCollectJvmClasses() && classCollector.isAvailable()) {
            classCollector.collect().ifPresent(builder::classes);
        }

        if (configuration.isCollectCpu() && cpuCollector.isAvailable()) {
            cpuCollector.collect().ifPresent(builder::cpu);
        }

        if (configuration.isCollectFileDescriptors() && fileDescriptorCollector.isAvailable()) {
            fileDescriptorCollector.collect().ifPresent(builder::fileDescriptors);
        }

        if (configuration.isCollectUptime() && uptimeCollector.isAvailable()) {
            uptimeCollector.collect().ifPresent(builder::uptime);
        }

        if (configuration.isCollectDisk() && diskCollector.isAvailable()) {
            diskCollector.collect().ifPresent(builder::disk);
        }

        if (configuration.isCollectContainerMetrics() && containerCollector.isAvailable()) {
            containerCollector.collect().ifPresent(builder::container);
        }

        return builder.build();
    }

    /**
     * Returns whether the collector is currently running.
     *
     * @return true if metrics collection is active
     */
    public boolean isRunning() {
        return running.get();
    }
}
