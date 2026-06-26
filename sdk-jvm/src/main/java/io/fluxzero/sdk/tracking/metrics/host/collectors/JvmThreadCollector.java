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

package io.fluxzero.sdk.tracking.metrics.host.collectors;

import io.fluxzero.sdk.tracking.metrics.host.events.JvmThreadMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Collector for JVM thread metrics using JMX.
 */
public class JvmThreadCollector implements MetricCollector<JvmThreadMetrics> {

    private final ThreadMXBean threadMXBean;

    public JvmThreadCollector() {
        this.threadMXBean = ManagementFactory.getThreadMXBean();
    }

    @Override
    public Optional<JvmThreadMetrics> collect() {
        try {
            Map<String, Integer> stateDistribution = new HashMap<>();
            for (Thread.State state : Thread.State.values()) {
                stateDistribution.put(state.name(), 0);
            }

            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds());
            for (ThreadInfo info : threadInfos) {
                if (info != null) {
                    String stateName = info.getThreadState().name();
                    stateDistribution.merge(stateName, 1, Integer::sum);
                }
            }

            return Optional.of(JvmThreadMetrics.builder()
                    .threadCount(threadMXBean.getThreadCount())
                    .daemonCount(threadMXBean.getDaemonThreadCount())
                    .peakCount(threadMXBean.getPeakThreadCount())
                    .totalStartedCount(threadMXBean.getTotalStartedThreadCount())
                    .threadStateDistribution(stateDistribution)
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
