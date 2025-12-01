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

import java.util.List;

/**
 * Disk space metrics for monitored paths.
 */
@Value
@Builder
public class DiskMetrics implements JsonType {

    /**
     * Metrics for each monitored disk/path.
     */
    List<DiskSpaceMetrics> disks;

    /**
     * Metrics for an individual disk or filesystem path.
     */
    @Value
    @Builder
    public static class DiskSpaceMetrics implements JsonType {
        /**
         * The path being monitored.
         */
        String path;

        /**
         * The total size of the filesystem in bytes.
         */
        long totalBytes;

        /**
         * The number of free bytes on the filesystem.
         */
        long freeBytes;

        /**
         * The number of usable bytes (available to this JVM) on the filesystem.
         */
        long usableBytes;
    }
}
