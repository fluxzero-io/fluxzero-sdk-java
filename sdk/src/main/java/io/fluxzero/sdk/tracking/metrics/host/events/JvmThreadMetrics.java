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

import java.util.Map;

/**
 * JVM thread metrics including counts and state distribution.
 */
@Value
@Builder
public class JvmThreadMetrics implements JsonType {

    /**
     * The current number of live threads (daemon and non-daemon).
     */
    int threadCount;

    /**
     * The current number of live daemon threads.
     */
    int daemonCount;

    /**
     * The peak thread count since the JVM started or since the peak was reset.
     */
    int peakCount;

    /**
     * The total number of threads created and started since the JVM started.
     */
    long totalStartedCount;

    /**
     * Distribution of threads by state (NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED).
     */
    Map<String, Integer> threadStateDistribution;
}
