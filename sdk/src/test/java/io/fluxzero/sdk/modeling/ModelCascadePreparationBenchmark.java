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
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

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
        boolean retained = Boolean.getBoolean("retained");
        boolean guarded = Boolean.getBoolean("guarded");
        int width = Integer.getInteger("width", 0), depth = Integer.getInteger("depth", 0);
        ThreadMXBean allocation = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (allocation != null) { allocation.setThreadAllocatedMemoryEnabled(true); }
        long thread = Thread.currentThread().threadId();
        CountingClient client = new CountingClient();
        System.out.printf("children=%d history=%d warmups=%d iterations=%d rounds=%d cascade=%s width=%d depth=%d retained=%s%n",
                          children, history, warmups, iterations, rounds, cascade, width, depth, retained);
        try (var fc = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            fc.registerHandlers(TreeNode.class);
            if (guarded) { fc.registerHandlers(GuardedChild.class); }
            seedHistory(fc, children, history);
            for (int round = -1; round < rounds; round++) {
                long nanos = 0, bytes = 0, graphRoots = 0, graphRequests = 0, streams = 0, events = 0, eventBytes = 0;
                long metadataModels = 0, metadataEdges = 0, metadataBytes = 0;
                int count = round < 0 ? warmups : iterations;
                long[] samples = new long[count];
                for (int i = 0; i < count; i++) {
                    String suffix = round + "-" + i;
                    Complete command = new Complete("scratch-" + suffix, "root", "receipt-" + suffix, false);
                    if (guarded) {
                        commit(fc, new PutBranch(command.scratchId(), null, null));
                        for (int event = 0; event < history; event++) {
                            commit(fc, new SeedGuarded(command.scratchId(), width, event));
                        }
                    } else {
                        commit(fc, new CreateScratch(command.scratchId()));
                        if (cascade) { commit(fc, new CreateOwned("owned-" + suffix, command.scratchId())); }
                        for (int event = 0; event < (width > 0 ? history : 0); event++) {
                            commit(fc, new SeedTree(command.scratchId(), width, depth, event, retained));
                        }
                    }
                    fc.cache().clear();
                    client.reset();
                    long allocatedBefore = allocation == null ? 0 : allocation.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    commit(fc, guarded ? new DeleteBranch(command.scratchId()) : command);
                    samples[i] = System.nanoTime() - start;
                    nanos += samples[i];
                    bytes += allocation == null ? 0 : allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
                    graphRoots += client.graphRoots.size(); streams += client.eventStreams.size();
                    graphRequests += client.graphRequests.size();
                    events += client.eventCount.get(); eventBytes += client.eventBytes.get();
                    metadataModels += client.graphResults.stream().mapToLong(r -> r.getEvents().getStreams().size()).sum();
                    metadataEdges += client.graphResults.stream().mapToLong(r -> r.getEdges().size()).sum();
                    // Serialize response metadata outside the timed/allocation window, including in warmups.
                    metadataBytes += client.graphResults.stream().mapToLong(r -> JsonUtils.asBytes(r).length).sum();
                    if (client.commits.size() != 1) { throw new AssertionError("Expected exactly one commit"); }
                    if (!guarded) { commit(fc, new DeleteReceipt(command.receiptId())); }
                }
                if (round >= 0) {
                    java.util.Arrays.sort(samples);
                    System.out.printf(java.util.Locale.ROOT, "round=%d us/op=%.1f p99-us/op=%.1f ops/second=%.1f caller-bytes/op=%.0f graph-roots/op=%.1f "
                                      + "graph-requests/op=%.1f stream-reads/op=%.1f events/op=%.1f event-bytes/op=%.1f "
                                      + "metadata-models/op=%.1f metadata-edges/op=%.1f metadata-bytes/op=%.1f commits/op=1%n",
                                      round, nanos / (count * 1000d), samples[(int) (count * .99)] / 1000d,
                                      count * 1_000_000_000d / nanos, bytes / (double) count,
                                      graphRoots / (double) count, graphRequests / (double) count, streams / (double) count,
                                      events / (double) count, eventBytes / (double) count,
                                      metadataModels / (double) count, metadataEdges / (double) count, metadataBytes / (double) count);
                }
            }
        }
    }

    record DeleteReceipt(String receiptId) {
        @Apply Receipt apply(Receipt before) { return null; }
    }

    record SeedGuarded(String rootId, int width, int version) {
        @io.fluxzero.sdk.persisting.eventsourcing.InterceptApply List<?> expand() {
            List<Object> changes = new ArrayList<>();
            for (int child = 0; child < width; child++) {
                changes.add(new PutGuardedChild(rootId + "/" + child, rootId, version));
            }
            return changes;
        }
    }

    @Model(searchable = false)
    record TreeNode(@EntityId String nodeId, @Parent(TreeNode.class) String parentId,
                    @Parent(Scratch.class) String scratchId,
                    @Parent(value = Scratch.class, deleteOnParentDeletion = false) String retainedScratchId, int version) {}

    record PutNode(String nodeId, String parentId, String scratchId, String retainedScratchId, int version) {
        @Apply TreeNode apply(@jakarta.annotation.Nullable TreeNode before) {
            return new TreeNode(nodeId, parentId, scratchId, retainedScratchId, version);
        }
    }

    record SeedTree(String scratchId, int width, int depth, int version, boolean retained) {
        @io.fluxzero.sdk.persisting.eventsourcing.InterceptApply List<?> expand() {
            List<Object> changes = new ArrayList<>();
            for (int branch = 0; branch < width; branch++) {
                String parent = null;
                for (int level = 0; level < depth; level++) {
                    String id = scratchId + "/" + branch + "/" + level;
                    changes.add(new PutNode(id, parent, parent == null && !retained ? scratchId : null,
                                            parent == null && retained ? scratchId : null, version));
                    parent = id;
                }
            }
            return changes;
        }
    }
}
