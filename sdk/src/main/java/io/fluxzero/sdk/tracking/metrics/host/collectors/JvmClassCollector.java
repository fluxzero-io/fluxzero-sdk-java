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

import io.fluxzero.sdk.tracking.metrics.host.events.JvmClassMetrics;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.util.Optional;

/**
 * Collector for JVM class loading metrics using JMX.
 */
public class JvmClassCollector implements MetricCollector<JvmClassMetrics> {

    private final ClassLoadingMXBean classLoadingMXBean;

    public JvmClassCollector() {
        this.classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
    }

    @Override
    public Optional<JvmClassMetrics> collect() {
        try {
            return Optional.of(JvmClassMetrics.builder()
                    .loadedCount(classLoadingMXBean.getLoadedClassCount())
                    .unloadedCount(classLoadingMXBean.getUnloadedClassCount())
                    .totalLoadedCount(classLoadingMXBean.getTotalLoadedClassCount())
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
