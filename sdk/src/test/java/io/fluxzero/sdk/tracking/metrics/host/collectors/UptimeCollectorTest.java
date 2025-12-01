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

import io.fluxzero.sdk.tracking.metrics.host.events.UptimeMetrics;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UptimeCollectorTest {

    @Test
    void isAvailable_returnsTrue() {
        var collector = new UptimeCollector();
        assertTrue(collector.isAvailable());
    }

    @Test
    void collect_returnsUptimeMetrics() {
        var collector = new UptimeCollector();

        Optional<UptimeMetrics> result = collector.collect();

        assertTrue(result.isPresent());
        UptimeMetrics metrics = result.get();

        assertTrue(metrics.getUptimeMs() > 0, "Uptime should be positive");
        assertTrue(metrics.getStartTimeMs() > 0, "Start time should be positive");
        assertTrue(metrics.getStartTimeMs() < System.currentTimeMillis(),
                "Start time should be before current time");
    }
}
