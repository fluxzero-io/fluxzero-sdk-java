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
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.CacheEviction;
import io.fluxzero.common.tracking.HasMessageStore;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static io.fluxzero.sdk.modeling.GraphPinnedReadBenchmark.*;

/**
 * Standalone comparison of updating, alternating historical, and capacity-limited Graph reads.
 * Run identical test bytecode against before/after SDK classes. Measured reader requests and all values are validated.
 * Properties: scenario=updates|mixed|pressure, dependencies=20, workingSet=1000 (pressure only), history=20
 * (pressure: 5), payloadSize=128 (pressure: 4096), cacheCapacity=1000 (pressure: 100), warmups=400,
 * iterations=100, rounds=5. The separate writer disables its Model cache; only the reader is observed.
 * Writes, selection, counters and post-GC heap probes are outside read timing and calling-thread allocation.
 * Eviction-metric publication stays measured; its LocalClient log is discarded between reads, outside the window.
 * Heap includes both SDKs and their shared in-memory store; retained deltas compare before/after reader-cache clear.
 */
public class GraphPinnedReadWorkloadBenchmark {
    public static void main(String[] args) throws Exception {
        int warmups = Integer.getInteger("warmups", 400), iterations = Integer.getInteger("iterations", 100);
        int rounds = Integer.getInteger("rounds", 5), step = 0;
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        try (var workload = new Workload()) {
            for (int round = -1; round < rounds; round++) {
                long nanos = 0, bytes = 0, calls = 0, payloads = 0, eventBytes = 0, graphs = 0, evictions = 0;
                long gcCount = 0, gcMillis = 0;
                int operations = round < 0 ? warmups : iterations;
                for (int i = 0; i < operations; i++) {
                    Selection selected = workload.prepare(step++);
                    workload.probe.begin(selected.boundary());
                    long evictionsBefore = workload.evictions.get();
                    long countBefore = gcCount(collectors), millisBefore = gcMillis(collectors);
                    long allocatedBefore = allocation.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    workload.read(selected);
                    nanos += System.nanoTime() - start;
                    bytes += allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
                    workload.probe.recording = false;
                    gcCount += gcCount(collectors) - countBefore;
                    gcMillis += gcMillis(collectors) - millisBefore;
                    evictions += workload.evictions.get() - evictionsBefore;
                    calls += workload.probe.calls.get(); payloads += workload.probe.payloads.get();
                    eventBytes += workload.probe.bytes.get(); graphs += workload.probe.graphs.get();
                    if (workload.probe.calls.get() == 0) { throw new AssertionError("Unobserved reader"); }
                    workload.checkCapacity();
                    workload.discardMetricLog();
                }
                if (round >= 0) {
                    System.out.printf(Locale.ROOT,
                            "RESULT round=%d us=%.3f callerBytes=%.1f eventCalls=%.2f payloads=%.2f eventBytes=%.1f "
                            + "graphCalls=%.1f sizeEvictions=%.2f cacheEntries=%d gcCollections=%d gcMillis=%d%n",
                            round, nanos / (operations * 1000d), bytes / (double) operations,
                            calls / (double) operations, payloads / (double) operations, eventBytes / (double) operations,
                            graphs / (double) operations, evictions / (double) operations, workload.cache.size(),
                            gcCount, gcMillis);
                }
            }
            workload.measureMemory();
        }
    }

    private static long gcCount(List<GarbageCollectorMXBean> collectors) {
        return collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionCount())).sum();
    }

    private static long gcMillis(List<GarbageCollectorMXBean> collectors) {
        return collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionTime())).sum();
    }

    private static long liveHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(75); }
        long[] samples = new long[5];
        for (int i = 0; i < samples.length; i++) {
            System.gc(); Thread.sleep(75);
            samples[i] = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    enum Scenario { UPDATES, MIXED, PRESSURE }

    record Selection(long boundary, List<ItemId> ids, int[] versions) {}

    private static final class Workload implements AutoCloseable {
        final Scenario scenario = Scenario.valueOf(System.getProperty("scenario", "updates").toUpperCase(Locale.ROOT));
        final int querySize = Integer.getInteger("dependencies", 20);
        final int count = scenario == Scenario.PRESSURE ? Integer.getInteger("workingSet", 1000) : querySize;
        final int history = Integer.getInteger("history", scenario == Scenario.PRESSURE ? 5 : 20);
        final int capacity = Integer.getInteger("cacheCapacity", scenario == Scenario.PRESSURE ? 100 : 1000);
        final String payload = "x".repeat(Integer.getInteger("payloadSize", scenario == Scenario.PRESSURE ? 4096 : 128));
        final List<ItemId> ids = IntStream.range(0, count).mapToObj(i -> new ItemId("workload-" + i)).toList();
        final int[] versions = new int[count];
        final AdaptiveObjectCache cache = new AdaptiveObjectCache(capacity);
        final AtomicLong evictions = new AtomicLong();
        final ReaderProbe probe = new ReaderProbe();
        final LocalClient storage = LocalClient.newInstance(null);
        final Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .disableAutomaticModelCaching().build(storage);
        final Fluxzero reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .withModelCache(cache).build(probe.observe(storage));
        final Selection old;
        Selection current;
        int lastStep;

        Workload() {
            if (count < querySize || count % querySize != 0 || querySize < 1 || history < 1 || capacity < 1) {
                throw new IllegalArgumentException("Positive sizes required; workingSet must be divisible by dependencies");
            }
            cache.registerEvictionListener(event -> {
                if (event.getReason() == CacheEviction.Reason.size) { evictions.incrementAndGet(); }
            });
            for (int version = 0; version < history; version++) {
                for (ItemId id : ids) { put(id, version); }
            }
            Arrays.fill(versions, history - 1);
            old = snapshot(ids.subList(0, querySize));
            current = old;
            if (scenario == Scenario.MIXED) {
                read(old);
                update(0);
                current = snapshot(ids);
            }
            cache.clear();
        }

        Selection prepare(int step) {
            lastStep = step;
            return switch (scenario) {
                case UPDATES -> {
                    update(step);
                    yield current = snapshot(ids);
                }
                case MIXED -> step % 2 == 0 ? current : old;
                case PRESSURE -> pressureSelection(step);
            };
        }

        private void update(int step) {
            int changes = Math.max(1, querySize / 10);
            for (int i = 0; i < changes; i++) {
                int target = (step * changes + i) % count;
                put(ids.get(target), ++versions[target]);
            }
        }

        private void put(ItemId id, int version) {
            writer.apply(fc -> fc.executeModelCommit(new Message(new Put(id, version, payload))).join());
        }

        private Selection snapshot(List<ItemId> selected) {
            long boundary = writer.apply(fc -> Fluxzero.loadCurrentGraph(ids.getFirst()).stateIndex());
            int offset = ids.indexOf(selected.getFirst());
            return new Selection(boundary, selected, Arrays.copyOfRange(versions, offset, offset + selected.size()));
        }

        private Selection pressureSelection(int step) {
            int offset = (step % (count / querySize)) * querySize;
            return new Selection(current.boundary(), ids.subList(offset, offset + querySize),
                                 Arrays.copyOfRange(versions, offset, offset + querySize));
        }

        void read(Selection selected) {
            reader.apply(fc -> {
                for (int i = 0; i < selected.ids().size(); i++) {
                    ItemId id = selected.ids().get(i);
                    Graph<Item> graph = Fluxzero.loadGraph(id).atStateIndex(selected.boundary());
                    Item value = graph.get();
                    if (!id.equals(value.itemId()) || value.version() != selected.versions()[i]
                        || !payload.equals(value.payload()) || graph.stateIndex() != selected.boundary()) {
                        throw new AssertionError("Incorrect value or snapshot");
                    }
                }
                return null;
            });
        }

        void checkCapacity() {
            if (cache.size() > capacity) { throw new AssertionError("Cache capacity exceeded"); }
        }

        void discardMetricLog() {
            // The local server's retained telemetry is not reader-cache memory; Model/event storage stays intact.
            ((HasMessageStore) storage.getTrackingClient(MessageType.METRICS)).getMessageStore().truncate();
        }

        void measureMemory() throws InterruptedException {
            discardMetricLog();
            long warm = liveHeap();
            int entries = cache.size();
            // More reads, with no storage changes, separate retained cache growth from store-history growth.
            for (int i = 0; i < 50; i++) {
                Selection selected = switch (scenario) {
                    case UPDATES -> current;
                    case MIXED -> i % 2 == 0 ? current : old;
                    case PRESSURE -> pressureSelection(lastStep + i + 1);
                };
                read(selected);
                checkCapacity();
                discardMetricLog();
            }
            long repeated = liveHeap();
            int repeatedEntries = cache.size();
            cache.clear();
            discardMetricLog();
            long cleared = liveHeap();
            System.out.printf(Locale.ROOT,
                    "MEMORY keys=%d querySize=%d capacity=%d payloadSize=%d warmHeap=%d cacheEntries=%d "
                    + "repeatedHeap=%d repeatedEntries=%d clearedHeap=%d retainedDelta=%d%n",
                    count, querySize, capacity, payload.length(), warm, entries, repeated, repeatedEntries,
                    cleared, repeated - cleared);
        }

        @Override public void close() { reader.close(); writer.close(); }
    }

    private static final class ReaderProbe {
        final AtomicLong calls = new AtomicLong(), payloads = new AtomicLong(), bytes = new AtomicLong();
        final AtomicLong graphs = new AtomicLong();
        volatile long boundary;
        volatile boolean recording;

        void begin(long selected) {
            boundary = selected; calls.set(0); payloads.set(0); bytes.set(0); graphs.set(0); recording = true;
        }

        Client observe(Client storage) {
            EventStoreClient events = (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, args) -> {
                        if (recording && method.getName().equals("getModelEvents")) {
                            GetModelEvents request = (GetModelEvents) args[0];
                            if (!Long.valueOf(boundary).equals(request.getBoundary().stateIndex())) {
                                throw new AssertionError("Reader moved its requested snapshot");
                            }
                        }
                        Object result = invoke(storage.getEventStoreClient(), method, args);
                        if (recording && method.getName().equals("getModelEvents")) {
                            var metric = ((GetModelEventsResult) result).toMetric();
                            calls.incrementAndGet(); payloads.addAndGet(metric.getPayloadCount());
                            bytes.addAndGet(metric.getBytes());
                        } else if (recording && method.getName().equals("getModelGraph")) { graphs.incrementAndGet(); }
                        return result;
                    });
            return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                    (proxy, method, args) -> method.getName().equals("getEventStoreClient")
                            ? events : invoke(storage, method, args));
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
