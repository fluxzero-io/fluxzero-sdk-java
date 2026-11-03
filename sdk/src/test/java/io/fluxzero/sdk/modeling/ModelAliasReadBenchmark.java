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
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import jakarta.annotation.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitTrackedThrough;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;

/**
 * Standalone current-code cost study, not a benchmark of an implemented fix.
 * Identity-first Graph access explores the head-only mechanism using an existing public API.
 * Seeding, cache resets and assertions are outside timing/allocation windows. Worker allocations are excluded.
 * Properties: history, warmups, warmupMillis, iterations, rounds, reverse. No Runtime/network latency is simulated.
 */
public class ModelAliasReadBenchmark {
    private static final String PRIMARY = "custom-owner", ALIAS = "friendly-name";
    private enum Route { MODEL_PRIMARY, MODEL_ALIAS, GRAPH_IDENTITY_ALIAS }

    public static void main(String[] args) {
        int history = Integer.getInteger("history", 1);
        int iterations = Integer.getInteger("iterations", 200), rounds = Integer.getInteger("rounds", 5);
        boolean reverse = Boolean.getBoolean("reverse");
        List<Route> routes = reverse ? List.of(Route.GRAPH_IDENTITY_ALIAS, Route.MODEL_ALIAS, Route.MODEL_PRIMARY)
                                     : List.of(Route.MODEL_PRIMARY, Route.MODEL_ALIAS, Route.GRAPH_IDENTITY_ALIAS);
        for (boolean cold : reverse ? List.of(true, false) : List.of(false, true)) {
            for (Route route : routes) { measure(route, cold, history, iterations, rounds); }
        }
    }

    private static void measure(Route route, boolean cold, int history, int iterations, int rounds) {
        var client = new ObservedClient();
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            awaitCacheTracking(app);
            for (int version = 0; version < history; version++) {
                int current = version;
                app.apply(fc -> fc.executeModelCommit(new Message(new Put(PRIMARY, ALIAS, current, "x".repeat(128)))).join());
            }
            long boundary = app.apply(fc -> Fluxzero.loadCurrentGraph(PRIMARY, Item.class).stateIndex());
            awaitTrackedThrough(client.polls, boundary);
            clearCacheAndAwait(app);
            Item expected = app.apply(fc -> Fluxzero.loadModel(PRIMARY, Item.class, false).get());
            int warmups = Integer.getInteger("warmups", cold ? 300 : 2_000);
            for (int round = -1; round < rounds; round++) {
                int count = round < 0 ? warmups : iterations;
                long[] nanos = new long[count];
                long allocated = 0, calls = 0, payloads = 0, bytes = 0;
                long jit = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
                long gc = gcCount();
                long warmupStart = System.nanoTime();
                long warmupNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        Long.getLong("warmupMillis", 2_000L));
                for (int i = 0; i < count || round < 0 && System.nanoTime() - warmupStart < warmupNanos; i++) {
                    if (cold) { clearCacheAndAwait(app); }
                    client.reset();
                    long beforeBytes = allocation.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    Item actual = app.apply(fc -> read(route));
                    long elapsed = System.nanoTime() - start;
                    if (round >= 0) { nanos[i] = elapsed; }
                    allocated += allocation.getThreadAllocatedBytes(thread) - beforeBytes;
                    if (!expected.equals(actual)) { throw new AssertionError("Wrong value for " + route); }
                    long expectedCalls = !cold && route == Route.MODEL_PRIMARY ? 0
                            : cold && route == Route.GRAPH_IDENTITY_ALIAS ? 2 : 1;
                    long expectedPayloads = cold || route == Route.MODEL_ALIAS ? history : 0;
                    if (client.calls != expectedCalls || client.payloads != expectedPayloads || client.graphCalls != 0) {
                        throw new AssertionError("Unexpected storage cost: " + route + "/" + cold + ": "
                                + client.calls + "/" + client.payloads + "/" + client.graphCalls);
                    }
                    calls += client.calls; payloads += client.payloads; bytes += client.bytes;
                }
                jit = ManagementFactory.getCompilationMXBean().getTotalCompilationTime() - jit;
                gc = gcCount() - gc;
                if (round >= 0) {
                    Arrays.sort(nanos);
                    System.out.printf(Locale.ROOT,
                            "RESULT route=%s cold=%s history=%d round=%d medianNs=%d p95Ns=%d callerBytes=%.1f "
                            + "calls=%.1f payloads=%.1f payloadBytes=%.1f jitMs=%d gc=%d%n",
                            route, cold, history, round, nanos[count / 2], nanos[(int) (count * .95)],
                            allocated / (double) count, calls / (double) count, payloads / (double) count,
                            bytes / (double) count, jit, gc);
                }
            }
        }
    }

    private static Item read(Route route) {
        if (route == Route.MODEL_PRIMARY) { return Fluxzero.loadModel(PRIMARY, Item.class, false).get(); }
        if (route == Route.MODEL_ALIAS) { return Fluxzero.loadModel(ALIAS, Item.class, false).get(); }
        Graph<Item> graph = Fluxzero.loadGraph(ALIAS, Item.class, false);
        graph.id();
        return graph.get();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
    }

    @Model(searchable = false)
    record Item(@EntityId String id, @Alias String alias, int version, String payload) {}
    record Put(String id, String alias, int version, String payload) {
        @Apply Item apply(@Nullable Item before) { return new Item(id, alias, version, payload); }
    }

    private static final class ObservedClient extends LocalClient {
        final BlockingQueue<TrackModelUpdates> polls = new LinkedBlockingQueue<>();
        long calls, payloads, bytes, graphCalls;
        ObservedClient() { super(null); }
        void reset() { calls = 0; payloads = 0; bytes = 0; graphCalls = 0; }
        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient storage = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("trackModelUpdates")) { polls.add((TrackModelUpdates) arguments[0]); }
                        Object result;
                        try { result = method.invoke(storage, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                        if (method.getName().equals("getModelEvents")) {
                            var metric = ((GetModelEventsResult) result).toMetric();
                            calls++; payloads += metric.getPayloadCount(); bytes += metric.getBytes();
                        } else if (method.getName().equals("getModelGraph")) { graphCalls++; }
                        return result;
                    });
        }
    }
}
