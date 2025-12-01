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

package io.fluxzero.sdk.tracking.metrics.host.events;

import io.fluxzero.common.api.JsonType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * A combined snapshot of host metrics collected at a specific point in time.
 * <p>
 * This event contains all enabled metrics as an atomic snapshot of the system state.
 * Individual metric categories may be null if collection is disabled or unavailable.
 */
@Value
@Builder
public class HostMetrics implements JsonType {

    /**
     * The timestamp when these metrics were collected.
     */
    Instant timestamp;

    /**
     * JVM memory metrics. May be null if collection is disabled.
     */
    JvmMemoryMetrics memory;

    /**
     * JVM garbage collection metrics. May be null if collection is disabled.
     */
    JvmGcMetrics gc;

    /**
     * JVM thread metrics. May be null if collection is disabled.
     */
    JvmThreadMetrics threads;

    /**
     * JVM class loading metrics. May be null if collection is disabled.
     */
    JvmClassMetrics classes;

    /**
     * CPU metrics. May be null if collection is disabled or unavailable.
     */
    CpuMetrics cpu;

    /**
     * File descriptor metrics. May be null if collection is disabled or unavailable (Windows).
     */
    FileDescriptorMetrics fileDescriptors;

    /**
     * JVM uptime metrics. May be null if collection is disabled.
     */
    UptimeMetrics uptime;

    /**
     * Disk space metrics. May be null if collection is disabled.
     */
    DiskMetrics disk;

    /**
     * Container/cgroup metrics. May be null if not running in a container.
     */
    ContainerMetrics container;
}
