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

import io.fluxzero.sdk.tracking.metrics.host.events.FileDescriptorMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Optional;

/**
 * Collector for file descriptor metrics (Unix/Linux only).
 * <p>
 * Uses the com.sun.management.UnixOperatingSystemMXBean extension.
 */
public class FileDescriptorCollector implements MetricCollector<FileDescriptorMetrics> {

    private final OperatingSystemMXBean osMXBean;
    private final boolean isUnix;

    public FileDescriptorCollector() {
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.isUnix = osMXBean instanceof com.sun.management.UnixOperatingSystemMXBean;
    }

    @Override
    public Optional<FileDescriptorMetrics> collect() {
        if (!isUnix) {
            return Optional.empty();
        }

        try {
            var unixOsMXBean = (com.sun.management.UnixOperatingSystemMXBean) osMXBean;

            return Optional.of(FileDescriptorMetrics.builder()
                    .openCount(unixOsMXBean.getOpenFileDescriptorCount())
                    .maxCount(unixOsMXBean.getMaxFileDescriptorCount())
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable() {
        return isUnix;
    }
}
