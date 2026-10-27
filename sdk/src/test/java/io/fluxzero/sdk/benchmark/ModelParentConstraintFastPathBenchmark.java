/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.benchmark;

import com.sun.management.ThreadMXBean;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import java.lang.management.ManagementFactory;

/**
 * Measures the ordinary Model commit path for a Model without Parent validation shorthands.
 */
public class ModelParentConstraintFastPathBenchmark {
    private static final int warmups = Integer.getInteger("warmups", 3);
    private static final int warmupIterations = Integer.getInteger("warmupIterations", 2_000);
    private static final int iterations = Integer.getInteger("iterations", 10_000);
    private static final ThreadMXBean allocationBean = allocationBean();
    private static volatile int blackhole;

    public static void main(String[] args) {
        System.out.printf("config iterations=%d warmupIterations=%d warmups=%d%n",
                          iterations, warmupIterations, warmups);
        try (var fluxzero = DefaultFluxzero.builder()
                .disableKeepalive().disableShutdownHook().build(LocalClient.newInstance())) {
            fluxzero.executeModelCommit(new io.fluxzero.sdk.common.Message(new Create("counter"))).join();
            for (int i = 0; i < warmups; i++) {
                update(fluxzero, warmupIterations);
            }
            for (int i = 0; i < 5; i++) {
                measure(fluxzero);
            }
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static void update(io.fluxzero.sdk.Fluxzero fluxzero, int count) {
        for (int i = 0; i < count; i++) {
            fluxzero.executeModelCommit(new io.fluxzero.sdk.common.Message(new Increment("counter"))).join();
        }
        blackhole += count;
    }

    private static void measure(io.fluxzero.sdk.Fluxzero fluxzero) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        update(fluxzero, iterations);
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("unconstrained-model-commit: %.2f ns/op, %.2f bytes/op, %.1f ops/s%n",
                          (double) elapsed / iterations, (double) allocated / iterations,
                          iterations * 1_000_000_000d / elapsed);
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean threadBean) || !threadBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }
        return threadBean;
    }

    @Model(persistence = ModelPersistence.DOCUMENT, searchable = false)
    private record Counter(@EntityId String id, int value) {}

    private record Create(String id) {
        @Apply Counter apply() {
            return new Counter(id, 0);
        }
    }

    private record Increment(String id) {
        @Apply Counter apply(Counter before) {
            return new Counter(id, before.value() + 1);
        }
    }
}
