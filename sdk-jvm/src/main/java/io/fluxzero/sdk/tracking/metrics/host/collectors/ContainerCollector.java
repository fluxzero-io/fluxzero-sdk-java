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

import io.fluxzero.sdk.tracking.metrics.host.events.ContainerMetrics;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Collector for container metrics from cgroups (v1 and v2).
 * <p>
 * Automatically detects cgroup version and collects CPU and memory metrics
 * when running in a containerized environment.
 */
@Slf4j
public class ContainerCollector implements MetricCollector<ContainerMetrics> {

    // Cgroups v2 paths
    private static final Path CGROUP_V2_BASE = Path.of("/sys/fs/cgroup");
    private static final Path CGROUP_V2_CPU_MAX = CGROUP_V2_BASE.resolve("cpu.max");
    private static final Path CGROUP_V2_CPU_STAT = CGROUP_V2_BASE.resolve("cpu.stat");
    private static final Path CGROUP_V2_MEMORY_MAX = CGROUP_V2_BASE.resolve("memory.max");
    private static final Path CGROUP_V2_MEMORY_CURRENT = CGROUP_V2_BASE.resolve("memory.current");

    // Cgroups v1 paths
    private static final Path CGROUP_V1_CPU_QUOTA = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
    private static final Path CGROUP_V1_CPU_PERIOD = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
    private static final Path CGROUP_V1_CPU_USAGE = Path.of("/sys/fs/cgroup/cpuacct/cpuacct.usage");
    private static final Path CGROUP_V1_MEMORY_LIMIT = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
    private static final Path CGROUP_V1_MEMORY_USAGE = Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes");

    private final String cgroupVersion;

    public ContainerCollector() {
        this.cgroupVersion = detectCgroupVersion();
    }

    @Override
    public Optional<ContainerMetrics> collect() {
        if (cgroupVersion == null) {
            return Optional.empty();
        }

        try {
            return "v2".equals(cgroupVersion) ? collectCgroupV2() : collectCgroupV1();
        } catch (Exception e) {
            log.debug("Failed to collect container metrics", e);
            return Optional.empty();
        }
    }

    private Optional<ContainerMetrics> collectCgroupV2() {
        var builder = ContainerMetrics.builder().cgroupVersion("v2");

        // CPU limits: cpu.max contains "quota period" or "max period"
        readFile(CGROUP_V2_CPU_MAX).ifPresent(content -> {
            String[] parts = content.trim().split("\\s+");
            if (parts.length >= 2) {
                long period = parseLongOrDefault(parts[1], -1);
                if (period > 0) {
                    builder.cpuPeriodNanos(period * 1000); // Convert microseconds to nanoseconds
                    if (!"max".equals(parts[0])) {
                        long quota = parseLongOrDefault(parts[0], -1);
                        if (quota > 0) {
                            builder.cpuLimitNanos(quota * 1000);
                        }
                    }
                }
            }
        });

        // CPU usage: from cpu.stat, look for usage_usec
        readFile(CGROUP_V2_CPU_STAT).ifPresent(content -> {
            for (String line : content.split("\n")) {
                if (line.startsWith("usage_usec")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long usageUsec = parseLongOrDefault(parts[1], -1);
                        if (usageUsec >= 0) {
                            builder.cpuUsageNanos(usageUsec * 1000);
                        }
                    }
                    break;
                }
            }
        });

        // Memory limit
        readFile(CGROUP_V2_MEMORY_MAX).ifPresent(content -> {
            String trimmed = content.trim();
            if (!"max".equals(trimmed)) {
                long limit = parseLongOrDefault(trimmed, -1);
                if (limit > 0) {
                    builder.memoryLimitBytes(limit);
                }
            }
        });

        // Memory usage
        readFile(CGROUP_V2_MEMORY_CURRENT).ifPresent(content -> {
            long usage = parseLongOrDefault(content.trim(), -1);
            if (usage >= 0) {
                builder.memoryUsageBytes(usage);
            }
        });

        return Optional.of(builder.build());
    }

    private Optional<ContainerMetrics> collectCgroupV1() {
        var builder = ContainerMetrics.builder().cgroupVersion("v1");

        // CPU quota and period
        readFile(CGROUP_V1_CPU_PERIOD).ifPresent(content -> {
            long period = parseLongOrDefault(content.trim(), -1);
            if (period > 0) {
                builder.cpuPeriodNanos(period * 1000);
            }
        });

        readFile(CGROUP_V1_CPU_QUOTA).ifPresent(content -> {
            long quota = parseLongOrDefault(content.trim(), -1);
            if (quota > 0) {
                builder.cpuLimitNanos(quota * 1000);
            }
        });

        // CPU usage
        readFile(CGROUP_V1_CPU_USAGE).ifPresent(content -> {
            long usage = parseLongOrDefault(content.trim(), -1);
            if (usage >= 0) {
                builder.cpuUsageNanos(usage);
            }
        });

        // Memory limit (check for very large value indicating no limit)
        readFile(CGROUP_V1_MEMORY_LIMIT).ifPresent(content -> {
            long limit = parseLongOrDefault(content.trim(), -1);
            // Very large values (close to Long.MAX_VALUE) indicate no limit
            if (limit > 0 && limit < Long.MAX_VALUE / 2) {
                builder.memoryLimitBytes(limit);
            }
        });

        // Memory usage
        readFile(CGROUP_V1_MEMORY_USAGE).ifPresent(content -> {
            long usage = parseLongOrDefault(content.trim(), -1);
            if (usage >= 0) {
                builder.memoryUsageBytes(usage);
            }
        });

        return Optional.of(builder.build());
    }

    private String detectCgroupVersion() {
        // Check for cgroups v2 (unified hierarchy)
        if (Files.exists(CGROUP_V2_CPU_MAX) || Files.exists(CGROUP_V2_MEMORY_CURRENT)) {
            return "v2";
        }
        // Check for cgroups v1
        if (Files.exists(CGROUP_V1_CPU_QUOTA) || Files.exists(CGROUP_V1_MEMORY_USAGE)) {
            return "v1";
        }
        return null;
    }

    private Optional<String> readFile(Path path) {
        try {
            if (Files.exists(path) && Files.isReadable(path)) {
                return Optional.of(Files.readString(path));
            }
        } catch (IOException e) {
            log.trace("Failed to read {}", path, e);
        }
        return Optional.empty();
    }

    private long parseLongOrDefault(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean isAvailable() {
        return cgroupVersion != null;
    }
}
