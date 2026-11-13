/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

package io.fluxzero.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeReadinessTest {
    @Test
    void expiresDuringBlockedProbeAndRecoversWithoutPenalizingShortReconnects() {
        AtomicLong time = new AtomicLong();
        RuntimeReadiness readiness = new RuntimeReadiness(Duration.ofSeconds(5), Duration.ofSeconds(1), time::get);
        assertTrue(readiness.isReady()); // HTTP-only proxy has no owned Runtime probe.
        readiness.start();
        assertFalse(readiness.isReady());
        readiness.succeeded();
        time.set(Duration.ofSeconds(4).toNanos());
        assertTrue(readiness.isReady());
        readiness.succeeded();
        time.set(Duration.ofSeconds(8).toNanos());
        assertTrue(readiness.isReady());
        time.set(Duration.ofSeconds(9).toNanos());
        for (int i = 0; i < 100; i++) {
            assertFalse(readiness.isReady()); // Reads never refresh the last successful probe.
        }
        readiness.succeeded();
        assertTrue(readiness.isReady());
    }

    @Test
    void rejectsInvalidProbeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeReadiness(
                Duration.ofSeconds(1), Duration.ofSeconds(1), System::nanoTime));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeReadiness(
                Duration.ofSeconds(1), Duration.ZERO, System::nanoTime));
    }
}
