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

/**
 * Container metrics collected from cgroups (v1 or v2).
 * <p>
 * These metrics are only available when running inside a container (Docker, Kubernetes, etc.).
 */
@Value
@Builder
public class ContainerMetrics implements JsonType {

    /**
     * The cgroup version detected: "v1", "v2", or null if not in a container.
     */
    String cgroupVersion;

    /**
     * The CPU quota in nanoseconds per period, or null if no limit is set.
     */
    Long cpuLimitNanos;

    /**
     * The CPU period in nanoseconds (typically 100ms = 100_000_000ns).
     */
    Long cpuPeriodNanos;

    /**
     * The total CPU usage in nanoseconds since container start.
     */
    Long cpuUsageNanos;

    /**
     * The memory limit in bytes, or null if no limit is set.
     */
    Long memoryLimitBytes;

    /**
     * The current memory usage in bytes.
     */
    Long memoryUsageBytes;
}
