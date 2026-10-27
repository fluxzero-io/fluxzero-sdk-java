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

package io.fluxzero.sdk.modeling;

import com.sun.management.ThreadMXBean;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/** Measures the complete shorthand gate for one ordinary unconstrained Model change. */
public class ParentConstraintFastPathBenchmark {
    private static final int iterations = Integer.getInteger("iterations", 10_000_000);
    private static final int warmups = Integer.getInteger("warmups", 3);
    private static final ThreadMXBean allocationBean = allocationBean();
    private static volatile int blackhole;

    public static void main(String[] args) {
        Plain before = new Plain("plain", 0);
        Plain after = new Plain("plain", 1);
        Change change = Change.applied("plain", Plain.class, 0L, null, before, after, null, null, false);
        CommitAttempt attempt = CommitAttempt.fromChanges(
                0L, List.of("plain"), Map.of("plain", Plain.class), null, List.of(change));
        Change deletion = Change.applied("plain", Plain.class, 0L, null, before, null, null, null, false);
        CommitAttempt deletionAttempt = CommitAttempt.fromChanges(
                0L, List.of("plain"), Map.of("plain", Plain.class), null, List.of(deletion));
        try (var fluxzero = DefaultFluxzero.builder()
                .disableKeepalive().disableShutdownHook().build(LocalClient.newInstance())) {
            ParentConstraintPlan plan = new ParentConstraintPlan(
                    (DefaultModelRepository) fluxzero.modelRepository(), List::of, () -> 0);
            for (int i = 0; i < warmups; i++) {
                scan(plan, attempt, iterations / 10);
                scan(plan, deletionAttempt, iterations / 10);
            }
            for (int i = 0; i < 5; i++) {
                measure("unconstrained-update-gate", plan, attempt);
                measure("unconstrained-deletion-gate", plan, deletionAttempt);
            }
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static void scan(ParentConstraintPlan plan, CommitAttempt attempt, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            result += plan.requiresValidation(attempt) ? 1 : 0;
        }
        blackhole = result;
    }

    private static void measure(String name, ParentConstraintPlan plan, CommitAttempt attempt) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        scan(plan, attempt, iterations);
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%s: %.2f ns/op, %.4f bytes/op%n", name,
                          (double) elapsed / iterations, (double) allocated / iterations);
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

    @Model(searchable = false)
    private record Plain(@EntityId String id, int value) {}
}
