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

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for host metrics collection.
 * <p>
 * Use the builder to customize which metrics are collected, the collection interval,
 * and metadata such as hostname and application name.
 */
@Value
@Builder(toBuilder = true)
public class HostMetricsConfiguration {

    /**
     * The interval between metric collection cycles. Defaults to 60 seconds.
     */
    @Builder.Default
    Duration collectionInterval = Duration.ofSeconds(60);

    /**
     * Whether to collect JVM memory metrics (heap, non-heap, memory pools).
     */
    @Builder.Default
    boolean collectJvmMemory = true;

    /**
     * Whether to collect JVM garbage collection metrics.
     */
    @Builder.Default
    boolean collectJvmGc = true;

    /**
     * Whether to collect JVM thread metrics.
     */
    @Builder.Default
    boolean collectJvmThreads = true;

    /**
     * Whether to collect JVM class loading metrics.
     */
    @Builder.Default
    boolean collectJvmClasses = true;

    /**
     * Whether to collect CPU metrics.
     */
    @Builder.Default
    boolean collectCpu = true;

    /**
     * Whether to collect file descriptor metrics (Unix only).
     */
    @Builder.Default
    boolean collectFileDescriptors = true;

    /**
     * Whether to collect JVM uptime metrics.
     */
    @Builder.Default
    boolean collectUptime = true;

    /**
     * Whether to collect disk space metrics. Disabled by default.
     */
    @Builder.Default
    boolean collectDisk = false;

    /**
     * Paths to monitor for disk metrics. If empty, uses the root filesystem.
     */
    @Builder.Default
    List<Path> diskPaths = List.of();

    /**
     * Whether to collect container metrics (cgroups v1/v2). Auto-detects if running in a container.
     */
    @Builder.Default
    boolean collectContainerMetrics = true;
}
