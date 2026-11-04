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
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.tracking.HasMessageStore;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import jakarta.annotation.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;

/**
 * Standalone Model alias workload qualification. Run this identical test bytecode against both SDK versions;
 * {@code variant=baseline|candidate} changes cost assertions only. No JUnit test is discovered from this main.
 * <p>
 * Properties: {@code scenario=low-hit|pressure|concurrent|stale}, {@code history=1}, {@code payloadSize=128},
 * {@code warmups=1000} (pressure: workingSet, stale: 100), {@code iterations=200}, {@code rounds=5},
 * {@code aliasEvery=10}, {@code workingSet=1536}, {@code workers=4}, {@code catalogSize=32} (0..32).
 * Both variants execute the same reads and histories and validate the same values; only expected storage costs differ.
 * Warmup uses fixed operation counts.
 * <p>
 * Low-hit reads use a never-observed alias every aliasEvery operations; the other reads are a warm canonical control.
 * Pressure cycles over more than 1024 distinct aliases while their canonical roots remain resident. Concurrent reads
 * use independently warmed keys and a fixed platform-thread pool; batching remains enabled. Stale reads use a new
 * owner per operation, with exactly history seed events and one update while the reader's tracker response is held.
 * The live canonical proof is checked before measuring the foreground read; no waiting duration establishes state.
 * <p>
 * Setup, writes, tracker gates, counter resets, task submission, value checks and metrics-log truncation are outside
 * individual read timing and calling-thread allocation. Caller allocations exclude SDK transport/refresh workers.
 * Response metric traversal and atomic counter updates are inside the observed read. Their cost can vary with payload
 * volume, so latency/allocation results include instrumentation and are not pure SDK overhead.
 * Concurrent batch wall time includes worker scheduling, in addition to the individual read latency distributions.
 * This uses real LocalClient storage behind the same proxy in both variants, not a network-latency simulation.
 */
public class ModelAliasCacheWorkloadBenchmark {
    private enum Scenario { LOW_HIT, PRESSURE, CONCURRENT, STALE }
    private enum Variant { BASELINE, CANDIDATE }
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        Scenario scenario = Scenario.valueOf(System.getProperty("scenario", "low-hit")
                .replace('-', '_').toUpperCase(Locale.ROOT));
        Variant variant = Variant.valueOf(System.getProperty("variant", "candidate").toUpperCase(Locale.ROOT));
        int workingSet = positive("workingSet", 1536), workers = positive("workers", 4);
        int history = positive("history", 1), iterations = positive("iterations", 200), rounds = positive("rounds", 5);
        int warmups = positive("warmups", scenario == Scenario.PRESSURE ? workingSet : scenario == Scenario.STALE ? 100 : 1000);
        int aliasEvery = positive("aliasEvery", 10), payloadSize = Integer.getInteger("payloadSize", 128);
        int catalogSize = Integer.getInteger("catalogSize", 32);
        if (payloadSize < 0 || workers > 64 || catalogSize < 0 || catalogSize > CATALOG.size()
            || scenario == Scenario.PRESSURE && workingSet <= 1024) {
            throw new IllegalArgumentException("Nonnegative payloadSize, workers <= 64, catalogSize 0..32; pressure workingSet > 1024");
        }
        if (!ALLOCATION.isThreadAllocatedMemorySupported()) { throw new IllegalStateException("Caller allocation unavailable"); }
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        try (var workload = new Workload(scenario, variant, history, payloadSize, aliasEvery, workingSet, workers, catalogSize)) {
            long step = 0;
            for (int round = -1; round < rounds; round++) {
                int operations = round < 0 ? warmups : iterations;
                Stats aliases = new Stats(Math.multiplyExact(operations, scenario == Scenario.CONCURRENT ? workers : 1));
                Stats canonical = new Stats(operations), batches = new Stats(operations);
                for (int i = 0; i < operations; i++, step++) {
                    Selection selected = workload.prepare(step);
                    if (scenario == Scenario.CONCURRENT) { workload.concurrentRead(aliases, batches); }
                    else { workload.singleRead(selected, selected.canonical() ? canonical : aliases); }
                    workload.finish(selected);
                }
                if (round >= 0) {
                    String label = "scenario=" + scenario + " variant=" + variant + " history=" + history
                                   + " catalogSize=" + catalogSize + " round=" + round;
                    aliases.print(label + " kind=alias");
                    canonical.print(label + " kind=canonical");
                    batches.print(label + " kind=batch-wall");
                }
            }
        }
    }

    private static int positive(String property, int defaultValue) {
        int value = Integer.getInteger(property, defaultValue);
        if (value < 1) { throw new IllegalArgumentException(property + " must be positive"); }
        return value;
    }

    private record Selection(String key, Item expected, boolean canonical, long updateBoundary) {}
    private record Sample(Item value, long nanos, long allocated) {}

    private static final class Workload implements AutoCloseable {
        final Scenario scenario;
        final Variant variant;
        final int history, aliasEvery, workingSet, workers;
        final String payload;
        final LocalClient storage = LocalClient.newInstance(null);
        final Probe probe = new Probe();
        final Fluxzero writer, reader;
        final ExecutorService executor;
        final List<Item> resident = new ArrayList<>();

        Workload(Scenario scenario, Variant variant, int history, int payloadSize, int aliasEvery,
                 int workingSet, int workers, int catalogSize) {
            this.scenario = scenario; this.variant = variant; this.history = history;
            this.aliasEvery = aliasEvery; this.workingSet = workingSet; this.workers = workers;
            payload = "x".repeat(payloadSize);
            executor = scenario == Scenario.CONCURRENT ? Executors.newFixedThreadPool(workers) : null;
            Fluxzero builtWriter = null, builtReader = null;
            try {
                writer = builtWriter = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                        .disableAutomaticModelCaching().build(storage);
                reader = builtReader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(probe.observe(storage));
                List<Class<?>> catalog = new ArrayList<>(CATALOG.subList(0, catalogSize));
                catalog.add(Item.class);
                ((DefaultModelRepository) reader.modelRepository()).configureModelTypes(() -> catalog);
                awaitCacheTracking(reader);
                int initial = scenario == Scenario.PRESSURE ? workingSet : scenario == Scenario.CONCURRENT ? workers
                        : scenario == Scenario.LOW_HIT ? 1 : 0;
                long boundary = -1;
                for (int i = 0; i < initial; i++) {
                    Item item = item("resident-" + i, history - 1);
                    boundary = seed(item); resident.add(item);
                }
                if (initial > 0) {
                    probe.awaitTrackedThrough(boundary);
                    for (Item item : resident) { check(item, read(item.id())); }
                    if (scenario != Scenario.LOW_HIT) {
                        for (Item item : resident) { check(item, read(item.alias())); }
                    }
                }
                discardMetrics();
            } catch (Throwable failure) {
                probe.gate.close();
                if (executor != null) { executor.shutdownNow(); }
                if (builtReader != null) { builtReader.close(); }
                if (builtWriter != null) { builtWriter.close(); }
                throw failure;
            }
        }

        Selection prepare(long step) {
            if (scenario == Scenario.CONCURRENT) { return null; }
            if (scenario == Scenario.PRESSURE) {
                Item value = resident.get((int) (step % workingSet));
                checkLiveCanonical(value);
                return new Selection(value.alias(), value, false, -1);
            }
            if (scenario == Scenario.LOW_HIT && step % aliasEvery != 0) {
                Item value = resident.getFirst();
                return new Selection(value.id(), value, true, -1);
            }
            Item before = item("operation-" + step, history - 1);
            long seeded = seed(before);
            probe.awaitTrackedThrough(seeded);
            if (scenario == Scenario.LOW_HIT) { return new Selection(before.alias(), before, false, -1); }
            check(before, read(before.id()));
            check(before, read(before.alias()));
            checkLiveCanonical(before);
            probe.gate.hold();
            Item after = item(before.id(), history);
            long updated = put(after);
            probe.gate.awaitCaptured();
            // The held tracker cannot invalidate or refresh this old, still-admitted canonical candidate.
            checkLiveCanonical(before);
            return new Selection(after.alias(), after, false, updated);
        }

        void singleRead(Selection selected, Stats stats) {
            probe.begin();
            Sample sample;
            Counts counts;
            try { sample = sample(selected.key()); }
            finally { counts = probe.end(); }
            check(selected.expected(), sample.value());
            long expectedPayloads = selected.canonical() ? 0 : scenario == Scenario.STALE
                    ? variant == Variant.CANDIDATE ? 1 : history + 1L : history;
            long expectedHeads = scenario == Scenario.STALE && variant == Variant.CANDIDATE ? 1 : 0;
            checkCounts(counts, expectedPayloads, expectedHeads, selected.canonical() ? 0
                    : scenario == Scenario.STALE && variant == Variant.CANDIDATE ? 2 : -1);
            stats.add(sample, counts);
        }

        void concurrentRead(Stats reads, Stats batches) throws Exception {
            CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
            List<Future<Sample>> pending = new ArrayList<>(workers);
            try {
                for (int worker = 0; worker < workers; worker++) {
                    String key = resident.get(worker).alias();
                    pending.add(executor.submit(() -> {
                        ready.countDown(); await(start, "concurrent start");
                        return sample(key);
                    }));
                }
                await(ready, "concurrent readers ready");
                probe.begin();
                long begin = System.nanoTime();
                start.countDown();
                List<Sample> samples = new ArrayList<>(workers);
                Counts counts;
                long elapsed;
                try {
                    for (Future<Sample> result : pending) { samples.add(result.get(10, TimeUnit.SECONDS)); }
                    elapsed = System.nanoTime() - begin;
                } finally { counts = probe.end(); }
                checkCounts(counts, variant == Variant.CANDIDATE ? 0 : (long) workers * history,
                            variant == Variant.CANDIDATE ? workers : 0, -1);
                for (int i = 0; i < workers; i++) {
                    check(resident.get(i), samples.get(i).value());
                    reads.add(samples.get(i), i == 0 ? counts : new Counts());
                }
                batches.add(new Sample(null, elapsed, 0), new Counts());
            } finally {
                start.countDown();
                for (Future<Sample> result : pending) { if (!result.isDone()) { result.cancel(true); } }
            }
        }

        void finish(Selection selected) {
            if (selected != null && selected.updateBoundary() >= 0) {
                probe.gate.release();
                probe.awaitTrackedThrough(selected.updateBoundary());
                // The baseline may now refresh the old canonical root. Complete that work before the next sample.
                check(selected.expected(), read(selected.expected().id()));
            }
            discardMetrics();
        }

        private Sample sample(String key) {
            long thread = Thread.currentThread().threadId();
            long beforeBytes = ALLOCATION.getThreadAllocatedBytes(thread), start = System.nanoTime();
            Item actual = read(key);
            long elapsed = System.nanoTime() - start;
            long allocated = ALLOCATION.getThreadAllocatedBytes(thread) - beforeBytes;
            return new Sample(actual, elapsed, allocated);
        }

        private Item read(String key) { return reader.apply(fc -> Fluxzero.loadModel(key, Item.class, false).get()); }
        private Item item(String id, int version) { return new Item(id, "alias-" + id, version, payload); }
        private long seed(Item item) {
            long boundary = -1;
            for (int version = 0; version < history; version++) { boundary = put(item(item.id(), version)); }
            return boundary;
        }
        private long put(Item item) {
            writer.apply(fc -> fc.executeModelCommit(new Message(new Put(item.id(), item.alias(), item.version(), payload))).join());
            return writer.apply(fc -> Fluxzero.loadCurrentGraph(item.id(), Item.class).stateIndex());
        }
        private void checkLiveCanonical(Item expected) {
            boolean current = ((DefaultModelRepository) reader.modelRepository()).supplyCurrentModel(expected.id(), Item.class,
                    (entity, through, state) -> check(expected, (Item) entity.get()));
            if (!current) { throw new AssertionError("Canonical candidate is not live: " + expected.id()); }
        }
        private void discardMetrics() {
            ((HasMessageStore) storage.getTrackingClient(MessageType.METRICS)).getMessageStore().truncate();
        }
        @Override public void close() {
            probe.gate.close();
            try {
                if (executor != null) {
                    executor.shutdownNow();
                    try {
                        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) { throw new AssertionError("Readers did not terminate"); }
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }
            } finally {
                try { reader.close(); } finally { writer.close(); }
            }
        }
    }

    private static void check(Item expected, Item actual) {
        if (!expected.equals(actual)) { throw new AssertionError("Incorrect Model: expected " + expected + ", got " + actual); }
    }

    private static void checkCounts(Counts counts, long payloads, long heads, long calls) {
        if (counts.payloads.get() != payloads || counts.headStreams.get() != heads || counts.graphCalls.get() != 0
            || calls >= 0 && counts.calls.get() != calls || calls < 0 && counts.calls.get() < 1) {
            throw new AssertionError("Unexpected read cost calls=" + counts.calls + " heads=" + counts.headStreams
                                     + " payloads=" + counts.payloads + " expected=" + calls + "/" + heads + "/" + payloads);
        }
    }

    private static final class Stats {
        final long[] nanos;
        int size;
        long totalNanos, allocated, calls, headStreams, payloads, bytes;
        Stats(int capacity) { nanos = new long[capacity]; }
        void add(Sample sample, Counts counts) {
            nanos[size++] = sample.nanos(); totalNanos += sample.nanos(); allocated += sample.allocated();
            calls += counts.calls.get(); headStreams += counts.headStreams.get();
            payloads += counts.payloads.get(); bytes += counts.bytes.get();
        }
        void print(String label) {
            if (size == 0) { return; }
            Arrays.sort(nanos, 0, size);
            System.out.printf(Locale.ROOT, "RESULT %s reads=%d meanNs=%.1f medianNs=%d p95Ns=%d p99Ns=%d "
                            + "callerBytes=%.1f eventCalls=%.3f headStreams=%.3f payloads=%.3f payloadBytes=%.1f%n",
                    label, size, totalNanos / (double) size, nanos[size / 2], nanos[Math.min(size - 1, (int) (size * .95))],
                    nanos[Math.min(size - 1, (int) (size * .99))], allocated / (double) size, calls / (double) size,
                    headStreams / (double) size, payloads / (double) size, bytes / (double) size);
        }
    }

    private static final class Counts {
        final AtomicLong calls = new AtomicLong(), headStreams = new AtomicLong(), payloads = new AtomicLong();
        final AtomicLong bytes = new AtomicLong(), graphCalls = new AtomicLong();
    }

    private static final class Probe {
        final AtomicReference<Counts> recording = new AtomicReference<>();
        final TrackerGate gate = new TrackerGate();
        long trackedThrough = -1;
        void begin() { if (!recording.compareAndSet(null, new Counts())) { throw new IllegalStateException("Overlapping windows"); } }
        Counts end() { return recording.getAndSet(null); }
        synchronized void observe(TrackModelUpdates request) {
            if (request.getMaxWaitMillis() > 0) { trackedThrough = Math.max(trackedThrough, request.getLastStateIndex()); notifyAll(); }
        }
        synchronized void awaitTrackedThrough(long boundary) {
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (trackedThrough < boundary) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { throw new AssertionError("Tracker did not process " + boundary); }
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        }
        Client observe(Client client) {
            EventStoreClient storage = client.getEventStoreClient();
            EventStoreClient events = (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("trackModelUpdates")) {
                            observe((TrackModelUpdates) args[0]);
                            @SuppressWarnings("unchecked")
                            CompletableFuture<TrackModelUpdatesResult> result =
                                    (CompletableFuture<TrackModelUpdatesResult>) invoke(storage, method, args);
                            return result.thenCompose(gate::accept);
                        }
                        Counts counts = recording.get();
                        Object result = invoke(storage, method, args);
                        if (counts != null && method.getName().equals("getModelEvents")) {
                            var metric = ((GetModelEventsResult) result).toMetric();
                            counts.calls.incrementAndGet(); counts.payloads.addAndGet(metric.getPayloadCount());
                            counts.bytes.addAndGet(metric.getBytes());
                            for (var stream : ((GetModelEvents) args[0]).getRequests()) {
                                if (stream.getMaxSize() == 0) { counts.headStreams.incrementAndGet(); }
                            }
                        } else if (counts != null && method.getName().equals("getModelGraph")) { counts.graphCalls.incrementAndGet(); }
                        return result;
                    });
            return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                    (proxy, method, args) -> method.getName().equals("getEventStoreClient") ? events : invoke(client, method, args));
        }
    }

    private static final class TrackerGate implements AutoCloseable {
        boolean holding;
        CompletableFuture<TrackModelUpdatesResult> pending;
        TrackModelUpdatesResult response;
        synchronized void hold() {
            if (holding || pending != null) { throw new IllegalStateException("Tracker gate already held"); }
            holding = true;
        }
        synchronized CompletableFuture<TrackModelUpdatesResult> accept(TrackModelUpdatesResult result) {
            if (!holding) { return CompletableFuture.completedFuture(result); }
            if (pending != null) { throw new AssertionError("More than one reader tracker response in flight"); }
            response = result; pending = new CompletableFuture<>(); notifyAll(); return pending;
        }
        synchronized void awaitCaptured() {
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (pending == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { throw new AssertionError("Tracker response was not captured"); }
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        }
        void release() {
            CompletableFuture<TrackModelUpdatesResult> completed;
            TrackModelUpdatesResult result;
            synchronized (this) { holding = false; completed = pending; result = response; pending = null; response = null; }
            if (completed != null) { completed.complete(result); }
        }
        @Override public void close() { release(); }
    }

    private static void await(CountDownLatch latch, String description) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Timed out: " + description); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Model(searchable = false)
    record Item(@EntityId String id, @Alias String alias, int version, String payload) {}
    record Put(String id, String alias, int version, String payload) {
        @Apply Item apply(@Nullable Item before) { return new Item(id, alias, version, payload); }
    }

    // Independent Model contracts make broad-catalog lookup costs visible without dynamic class generation.
    @Model(searchable = false) record Catalog00(@EntityId String id) {}
    @Model(searchable = false) record Catalog01(@EntityId String id) {}
    @Model(searchable = false) record Catalog02(@EntityId String id) {}
    @Model(searchable = false) record Catalog03(@EntityId String id) {}
    @Model(searchable = false) record Catalog04(@EntityId String id) {}
    @Model(searchable = false) record Catalog05(@EntityId String id) {}
    @Model(searchable = false) record Catalog06(@EntityId String id) {}
    @Model(searchable = false) record Catalog07(@EntityId String id) {}
    @Model(searchable = false) record Catalog08(@EntityId String id) {}
    @Model(searchable = false) record Catalog09(@EntityId String id) {}
    @Model(searchable = false) record Catalog10(@EntityId String id) {}
    @Model(searchable = false) record Catalog11(@EntityId String id) {}
    @Model(searchable = false) record Catalog12(@EntityId String id) {}
    @Model(searchable = false) record Catalog13(@EntityId String id) {}
    @Model(searchable = false) record Catalog14(@EntityId String id) {}
    @Model(searchable = false) record Catalog15(@EntityId String id) {}
    @Model(searchable = false) record Catalog16(@EntityId String id) {}
    @Model(searchable = false) record Catalog17(@EntityId String id) {}
    @Model(searchable = false) record Catalog18(@EntityId String id) {}
    @Model(searchable = false) record Catalog19(@EntityId String id) {}
    @Model(searchable = false) record Catalog20(@EntityId String id) {}
    @Model(searchable = false) record Catalog21(@EntityId String id) {}
    @Model(searchable = false) record Catalog22(@EntityId String id) {}
    @Model(searchable = false) record Catalog23(@EntityId String id) {}
    @Model(searchable = false) record Catalog24(@EntityId String id) {}
    @Model(searchable = false) record Catalog25(@EntityId String id) {}
    @Model(searchable = false) record Catalog26(@EntityId String id) {}
    @Model(searchable = false) record Catalog27(@EntityId String id) {}
    @Model(searchable = false) record Catalog28(@EntityId String id) {}
    @Model(searchable = false) record Catalog29(@EntityId String id) {}
    @Model(searchable = false) record Catalog30(@EntityId String id) {}
    @Model(searchable = false) record Catalog31(@EntityId String id) {}
    private static final List<Class<?>> CATALOG = List.of(Catalog00.class, Catalog01.class, Catalog02.class, Catalog03.class,
            Catalog04.class, Catalog05.class, Catalog06.class, Catalog07.class, Catalog08.class, Catalog09.class, Catalog10.class,
            Catalog11.class, Catalog12.class, Catalog13.class, Catalog14.class, Catalog15.class, Catalog16.class, Catalog17.class,
            Catalog18.class, Catalog19.class, Catalog20.class, Catalog21.class, Catalog22.class, Catalog23.class, Catalog24.class,
            Catalog25.class, Catalog26.class, Catalog27.class, Catalog28.class, Catalog29.class, Catalog30.class, Catalog31.class);
}
