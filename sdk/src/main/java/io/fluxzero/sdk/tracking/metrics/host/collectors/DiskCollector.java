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

import io.fluxzero.sdk.tracking.metrics.host.events.DiskMetrics;
import io.fluxzero.sdk.tracking.metrics.host.events.DiskMetrics.DiskSpaceMetrics;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collector for disk space metrics.
 */
public class DiskCollector implements MetricCollector<DiskMetrics> {

    private final List<Path> paths;

    public DiskCollector(List<Path> paths) {
        this.paths = paths.isEmpty() ? List.of(Path.of("/")) : paths;
    }

    @Override
    public Optional<DiskMetrics> collect() {
        try {
            List<DiskSpaceMetrics> disks = new ArrayList<>();

            for (Path path : paths) {
                File file = path.toFile();
                if (file.exists()) {
                    disks.add(DiskSpaceMetrics.builder()
                            .path(path.toString())
                            .totalBytes(file.getTotalSpace())
                            .freeBytes(file.getFreeSpace())
                            .usableBytes(file.getUsableSpace())
                            .build());
                }
            }

            if (disks.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(DiskMetrics.builder()
                    .disks(disks)
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable() {
        return paths.stream().anyMatch(p -> p.toFile().exists());
    }
}
