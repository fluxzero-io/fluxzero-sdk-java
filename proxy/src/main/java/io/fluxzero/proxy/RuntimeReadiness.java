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

import java.time.Duration;
import java.util.function.LongSupplier;

/** Locally evaluated freshness of the single background Runtime probe, including while that probe is blocked. */
final class RuntimeReadiness {
    private final long graceNanos;
    private final long intervalMillis;
    private final LongSupplier nanoTime;
    private volatile boolean started;
    private volatile Long lastSuccess;

    RuntimeReadiness(Duration grace, Duration interval, LongSupplier nanoTime) {
        if (interval.toMillis() < 1 || grace.compareTo(interval) <= 0) {
            throw new IllegalArgumentException("Proxy readiness grace must exceed its positive probe interval");
        }
        this.graceNanos = grace.toNanos();
        this.intervalMillis = interval.toMillis();
        this.nanoTime = nanoTime;
    }

    void start() { started = true; }

    void succeeded() { lastSuccess = nanoTime.getAsLong(); }

    boolean isReady() {
        Long success = lastSuccess;
        return !started || success != null && nanoTime.getAsLong() - success < graceNanos;
    }

    long intervalMillis() { return intervalMillis; }
}
