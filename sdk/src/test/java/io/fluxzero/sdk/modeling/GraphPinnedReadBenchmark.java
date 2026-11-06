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
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Standalone LocalClient comparison of explicit snapshot and current Graph reads.
 * Setup and cache clearing are outside timing/allocation windows. Run identical bytecode against each SDK version.
 * Calling-thread allocations exclude workers. Memory mode measures approximate post-GC live heap and cache entries.
 * Properties: dependencies, history, payloadSize, warmups, iterations, rounds, pinned, cold, memory and snapshots.
 */
public class GraphPinnedReadBenchmark {
    public static void main(String[] args) throws Exception {
        int count = Integer.getInteger("dependencies", 3), history = Integer.getInteger("history", 1);
        int warmups = Integer.getInteger("warmups", 1_000), iterations = Integer.getInteger("iterations", 300);
        int rounds = Integer.getInteger("rounds", 5), payloadSize = Integer.getInteger("payloadSize", 128);
        boolean pinned = Boolean.parseBoolean(System.getProperty("pinned", "true"));
        boolean cold = Boolean.getBoolean("cold"), memory = Boolean.getBoolean("memory");
        List<ItemId> ids = IntStream.range(0, count).mapToObj(i -> new ItemId("item-" + i)).toList();
        String payload = "x".repeat(payloadSize);
        var client = new CountingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            for (int version = 0; version < history; version++) {
                for (ItemId id : ids) { commit(app, new Put(id, version, payload)); }
            }
            long boundary = app.apply(fc -> Fluxzero.loadCurrentGraph(ids.getFirst()).stateIndex());
            if (memory) {
                measureMemory(app, ids, boundary, history, payload);
                return;
            }
            app.cache().clear();
            ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            for (int round = -1; round < rounds; round++) {
                long nanos = 0, bytes = 0, eventCalls = 0, payloads = 0, eventBytes = 0, graphCalls = 0;
                int operations = round < 0 ? warmups : iterations;
                long[] samples = new long[operations];
                for (int i = 0; i < operations; i++) {
                    if (cold) { app.cache().clear(); }
                    client.reset();
                    long allocatedBefore = allocation.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    read(app, ids, boundary, pinned, history);
                    samples[i] = System.nanoTime() - start;
                    nanos += samples[i];
                    bytes += allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
                    eventCalls += client.eventCalls.get(); payloads += client.payloads.get();
                    eventBytes += client.bytes.get(); graphCalls += client.graphCalls.get();
                }
                if (round >= 0) {
                    java.util.Arrays.sort(samples);
                    System.out.printf(Locale.ROOT, "RESULT round=%d us=%.3f p99Us=%.3f readOpsPerSecond=%.1f callerBytes=%.1f eventCalls=%.1f payloads=%.1f "
                                      + "eventBytes=%.1f graphCalls=%.1f cacheEntries=%d%n", round,
                                      nanos / (operations * 1000d), samples[(int) (operations * .99)] / 1000d,
                                      operations * 1_000_000_000d / nanos, bytes / (double) operations,
                                      eventCalls / (double) operations, payloads / (double) operations,
                                      eventBytes / (double) operations, graphCalls / (double) operations, app.cache().size());
                }
            }
        }
    }

    private static void measureMemory(Fluxzero app, List<ItemId> ids, long boundary, int history, String payload)
            throws InterruptedException {
        int snapshots = Integer.getInteger("snapshots", 50);
        long[] boundaries = new long[snapshots];
        ItemId fence = new ItemId("fence");
        for (int i = 0; i < snapshots; i++) {
            commit(app, new Put(fence, i, payload));
            boundaries[i] = app.apply(fc -> Fluxzero.loadCurrentGraph(fence).stateIndex());
        }
        // Exercise reflection, serializers and the same cache paths before establishing the empty-cache baseline.
        for (int i = 0; i < 3; i++) { read(app, ids, boundary, true, history); }
        app.cache().clear();
        long before = liveHeap();
        read(app, ids, boundary, true, history);
        read(app, ids, boundary, true, history);
        long after = liveHeap();
        int entries = app.cache().size();
        for (long selected : boundaries) { read(app, ids, selected, true, history); }
        long afterSnapshots = liveHeap();
        int snapshotEntries = app.cache().size();
        app.cache().clear();
        long afterClear = liveHeap();
        System.out.printf(Locale.ROOT, "MEMORY keys=%d history=%d snapshots=%d emptyHeap=%d warmHeap=%d warmDelta=%d "
                          + "cacheEntries=%d snapshotHeap=%d snapshotEntries=%d clearedHeap=%d%n",
                          ids.size(), history, snapshots, before, after, after - before, entries,
                          afterSnapshots, snapshotEntries, afterClear);
    }

    private static long liveHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(75); }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void read(Fluxzero app, List<ItemId> ids, long boundary, boolean pinned, int history) {
        app.apply(fc -> {
            for (ItemId id : ids) {
                Item value = pinned ? Fluxzero.loadGraph(id).atStateIndex(boundary).get()
                                    : Fluxzero.loadCurrentGraph(id).get();
                if (value.version() != history - 1) { throw new AssertionError("Incorrect snapshot value"); }
            }
            return null;
        });
    }

    private static void commit(Fluxzero app, Put value) {
        app.apply(fc -> fc.executeModelCommit(new Message(value)).join());
    }

    @Model(searchable = false)
    record Item(@EntityId ItemId itemId, int version, String payload) {}

    static final class ItemId extends Id<Item> {
        ItemId(String value) { super(value); }
    }

    record Put(ItemId itemId, int version, String payload) {
        @Apply Item apply(@jakarta.annotation.Nullable Item before) { return new Item(itemId, version, payload); }
    }

    private static final class CountingClient extends LocalClient {
        final AtomicLong eventCalls = new AtomicLong(), payloads = new AtomicLong(), bytes = new AtomicLong();
        final AtomicLong graphCalls = new AtomicLong();

        CountingClient() { super(null); }

        void reset() { eventCalls.set(0); payloads.set(0); bytes.set(0); graphCalls.set(0); }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient storage = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        Object result;
                        try { result = method.invoke(storage, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                        if (method.getName().equals("getModelEvents")) {
                            var metric = ((GetModelEventsResult) result).toMetric();
                            eventCalls.incrementAndGet(); payloads.addAndGet(metric.getPayloadCount());
                            bytes.addAndGet(metric.getBytes());
                        } else if (method.getName().equals("getModelGraph")) { graphCalls.incrementAndGet(); }
                        return result;
                    });
        }
    }
}
