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
 * CPU metrics for the process and system.
 */
@Value
@Builder
public class CpuMetrics implements JsonType {

    /**
     * The CPU usage of this JVM process as a value between 0.0 and 1.0.
     * May be null if not available on the current platform.
     */
    Double processCpuUsage;

    /**
     * The system-wide CPU usage as a value between 0.0 and 1.0.
     * May be null if not available on the current platform.
     */
    Double systemCpuUsage;

    /**
     * The number of processors available to the JVM.
     */
    int availableProcessors;

    /**
     * The system load average for the last minute.
     * May be null if not available (e.g., on Windows).
     */
    Double systemLoadAverage;

    /**
     * The total CPU time used by this process in nanoseconds.
     * May be null if not available on the current platform.
     */
    Long processCpuTimeNanos;
}
