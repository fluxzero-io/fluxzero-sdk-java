/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import com.sun.management.ThreadMXBean;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import java.lang.management.ManagementFactory;

import static io.fluxzero.sdk.modeling.ModelCascadePreparationTest.*;

/**
 * Standalone cold-cache commit benchmark. Setup and receipt cleanup are outside each measurement.
 * Run with the SDK test classpath; tune -Dchildren, -Dhistory, -Dwarmups, -Diterations and -Drounds.
 * Reports real LocalClient storage calls and calling-thread allocation, not network or server latency.
 */
public class ModelCascadePreparationBenchmark {
    public static void main(String[] args) {
        int children = Integer.getInteger("children", 50), history = Integer.getInteger("history", 35);
        int warmups = Integer.getInteger("warmups", 30), iterations = Integer.getInteger("iterations", 30);
        int rounds = Integer.getInteger("rounds", 5);
        boolean cascade = Boolean.getBoolean("cascade");
        ThreadMXBean allocation = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (allocation != null) { allocation.setThreadAllocatedMemoryEnabled(true); }
        long thread = Thread.currentThread().threadId();
        CountingClient client = new CountingClient();
        System.out.printf("children=%d history=%d warmups=%d iterations=%d rounds=%d cascade=%s%n",
                          children, history, warmups, iterations, rounds, cascade);
        try (var fc = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            seedHistory(fc, children, history);
            for (int round = -1; round < rounds; round++) {
                long nanos = 0, bytes = 0, graphRoots = 0, streams = 0, events = 0, eventBytes = 0;
                int count = round < 0 ? warmups : iterations;
                for (int i = 0; i < count; i++) {
                    String suffix = round + "-" + i;
                    Complete command = new Complete("scratch-" + suffix, "root", "receipt-" + suffix, false);
                    commit(fc, new CreateScratch(command.scratchId()));
                    if (cascade) { commit(fc, new CreateOwned("owned-" + suffix, command.scratchId())); }
                    fc.cache().clear();
                    client.reset();
                    long allocatedBefore = allocation == null ? 0 : allocation.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    commit(fc, command);
                    nanos += System.nanoTime() - start;
                    bytes += allocation == null ? 0 : allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
                    graphRoots += client.graphRoots.size(); streams += client.eventStreams.size();
                    events += client.eventCount.get(); eventBytes += client.eventBytes.get();
                    if (client.commits.size() != 1) { throw new AssertionError("Expected exactly one commit"); }
                    commit(fc, new DeleteReceipt(command.receiptId()));
                }
                if (round >= 0) {
                    System.out.printf("round=%d us/op=%.1f caller-bytes/op=%.0f graph-roots/op=%.1f "
                                      + "stream-reads/op=%.1f events/op=%.1f event-bytes/op=%.1f%n",
                                      round, nanos / (count * 1000d), bytes / (double) count,
                                      graphRoots / (double) count, streams / (double) count,
                                      events / (double) count, eventBytes / (double) count);
                }
            }
        }
    }

    record DeleteReceipt(String receiptId) {
        @Apply Receipt apply(Receipt before) { return null; }
    }
}
