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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic for independent-model cache hit, suffix catch-up, and explicit invalidation paths.
 */
public class ModelCacheBenchmark {
    private static final int EVENT_COUNT =
            Integer.getInteger("eventCount", 10_000);
    private static final int WARM_LOADS =
            Integer.getInteger("warmLoads", 10_000);
    private static final int RUNS =
            Integer.getInteger("runs", 5);
    private static final int MEMORY_KEY_COUNT =
            Integer.getInteger("keyCount", 250_000);
    private static final int MEMORY_CACHING_DEPTH =
            Integer.getInteger("cachingDepth", 1);

    public static void main(String[] args) {
        if ("memory".equalsIgnoreCase(
                System.getProperty("mode", "load"))) {
            measureRetainedModelRevisions();
            return;
        }
        if (RUNS <= 0
            || WARM_LOADS <= 0) {
            throw new IllegalArgumentException(
                    "runs and warmLoads must be positive");
        }
        CounterId id = new CounterId("cache-benchmark");
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null))) {
            prepare(fluxzero, id);
            DefaultModelRepository repository =
                    (DefaultModelRepository)
                            fluxzero.modelRepository();
            repository.invalidateModels(
                    List.of(id.toString()));
            load(fluxzero, id, EVENT_COUNT);
            repository.invalidateModels(
                    List.of(id.toString()));

            long[] cold = new long[RUNS];
            long[] warm = new long[RUNS];
            long[] propagation =
                    new long[RUNS];
            long[] refreshedHit =
                    new long[RUNS];
            long[] invalidated =
                    new long[RUNS];
            int expected = EVENT_COUNT;
            for (int run = 0; run < RUNS; run++) {
                int current = expected;
                cold[run] = timed(() ->
                                          load(
                                                  fluxzero, id,
                                                  current));
                warm[run] = timed(() -> {
                    for (int i = 0;
                         i < WARM_LOADS; i++) {
                        load(
                                fluxzero, id,
                                current);
                    }
                });
                long readStateIndex =
                        ((ModelRoot<?>) fluxzero
                                .modelRepository()
                                .load(id))
                                .stateIndex();
                long propagationStarted =
                        System.nanoTime();
                append(
                        fluxzero, id,
                        readStateIndex);
                expected++;
                awaitValue(
                        fluxzero, id,
                        expected);
                propagation[run] =
                        System.nanoTime()
                        - propagationStarted;
                int refreshed = expected;
                refreshedHit[run] =
                        timed(() ->
                                      load(
                                              fluxzero, id,
                                              refreshed));
                repository.invalidateModels(
                        List.of(id.toString()));
                invalidated[run] =
                        timed(() ->
                                      load(
                                              fluxzero, id,
                                              refreshed));
                repository.invalidateModels(
                        List.of(id.toString()));
            }

            print(
                    "cold reconstruction",
                    cold, 1_000_000d,
                    "ms");
            print(
                    "local cache hit",
                    java.util.Arrays.stream(
                                    warm)
                            .map(value ->
                                         value
                                         / WARM_LOADS)
                            .toArray(),
                    1_000d, "us");
            print(
                    "remote update-to-visible",
                    propagation,
                    1_000_000d, "ms");
            print(
                    "refreshed cache hit",
                    refreshedHit,
                    1_000d, "us");
            print(
                    "invalidated full reconstruction",
                    invalidated,
                    1_000_000d, "ms");
        }
    }

    private static void print(
            String label,
            long[] samples,
            double divisor,
            String unit) {
        long[] sorted =
                samples.clone();
        java.util.Arrays.sort(
                sorted);
        System.out.printf(
                "model cache %s: %,d-event history; %,d runs; p50/p95/max %.3f / %.3f / %.3f %s%n",
                label, EVENT_COUNT,
                sorted.length,
                sorted[sorted.length / 2]
                / divisor,
                sorted[Math.min(
                        sorted.length - 1,
                        (int) Math.ceil(
                                sorted.length
                                * 0.95)
                        - 1)]
                / divisor,
                sorted[sorted.length - 1]
                / divisor,
                unit);
    }

    private static void measureRetainedModelRevisions() {
        if (MEMORY_CACHING_DEPTH < 0
            || MEMORY_CACHING_DEPTH > 1) {
            throw new IllegalArgumentException(
                    "Memory benchmark supports cachingDepth 0 or 1");
        }
        forceGc();
        long before = usedHeap();
        Object[] entries =
                new Object[MEMORY_KEY_COUNT];
        for (int i = 0; i < entries.length; i++) {
            CounterId id =
                    new CounterId("memory-" + i);
            Entity<Counter> previous =
                    MEMORY_CACHING_DEPTH == 0
                            ? null
                            : revision(
                                    id,
                                    new Counter(id, 1),
                                    0L, null);
            entries[i] = revision(
                    id, new Counter(id, 2),
                    1L, previous);
        }
        forceGc();
        long retained = usedHeap() - before;
        System.out.printf(
                "model cache retained heap: %,d keys; cachingDepth=%d; "
                + "%.2f MiB; %.1f bytes/key%n",
                entries.length, MEMORY_CACHING_DEPTH,
                retained / 1_048_576d,
                (double) retained / entries.length);
        Reference.reachabilityFence(entries);
    }

    private static ImmutableModelRoot<Counter> revision(
            CounterId id,
            Counter value,
            long sequenceNumber,
            Entity<Counter> previous) {
        return ImmutableModelRoot.<Counter>builder()
                .id(id.toString())
                .type(Counter.class)
                .idProperty("counterId")
                .value(value)
                .sequenceNumber(sequenceNumber)
                .stateIndex(sequenceNumber)
                .previous(previous)
                .build();
    }

    private static void forceGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while stabilizing heap",
                        e);
            }
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory()
               - runtime.freeMemory();
    }

    private static long timed(Runnable task) {
        long started = System.nanoTime();
        task.run();
        return System.nanoTime() - started;
    }

    private static void load(
            Fluxzero fluxzero,
            CounterId id,
            int expected) {
        Counter value =
                fluxzero.modelRepository()
                        .load(id).get();
        if (value == null
            || value.value() != expected) {
            throw new IllegalStateException(
                    "Expected " + expected
                    + " but got " + value);
        }
    }

    private static void awaitValue(
            Fluxzero fluxzero,
            CounterId id,
            int expected) {
        long deadline =
                System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(10L);
        while (System.nanoTime()
               < deadline) {
            Counter value =
                    fluxzero.modelRepository()
                            .load(id).get();
            if (value != null
                && value.value()
                   == expected) {
                return;
            }
            Thread.onSpinWait();
        }
        load(fluxzero, id, expected);
    }

    private static void prepare(
            Fluxzero fluxzero, CounterId id) {
        List<ModelActionSubstep> substeps =
                new ArrayList<>(EVENT_COUNT);
        for (int i = 0; i < EVENT_COUNT; i++) {
            Object event = i == 0
                    ? new CreateCounter(id)
                    : new Increment(id);
            substeps.add(substep(
                    fluxzero, id, event));
        }
        fluxzero.client().getEventStoreClient()
                .commitModelAction(
                        new CommitModelAction(
                                "cache-benchmark-prepare",
                                -1L,
                                List.of(id.toString()),
                                substeps,
                                ModelConflictPolicy.ACCEPT,
                                Guarantee.STORED))
                .join();
    }

    private static void append(
            Fluxzero fluxzero,
            CounterId id,
            long readStateIndex) {
        fluxzero.client().getEventStoreClient()
                .commitModelAction(
                        new CommitModelAction(
                                "cache-benchmark-append-"
                                + readStateIndex,
                                readStateIndex,
                                List.of(id.toString()),
                                List.of(substep(
                                        fluxzero, id,
                                        new Increment(id))),
                                ModelConflictPolicy.ACCEPT,
                                Guarantee.STORED))
                .join();
    }

    private static ModelActionSubstep substep(
            Fluxzero fluxzero,
            CounterId id,
            Object event) {
        return ModelActionSubstep.builder()
                .event(new Message(event)
                               .serialize(
                                       fluxzero.serializer()))
                .targets(List.of(
                        ModelActionTarget.builder()
                                .modelId(id.toString())
                                .storeEvent(true)
                                .updateState(true)
                                .relationships(List.of())
                                .build()))
                .build();
    }

    @Model(snapshotPeriod = 0)
    private record Counter(
            @EntityId CounterId counterId,
            int value) {
    }

    private static final class CounterId
            extends Id<Counter> {
        private CounterId(String id) {
            super(id, "cached-counter-");
        }
    }

    private record Increment(CounterId counterId) {
        @Apply
        Counter apply(Counter counter) {
            return new Counter(
                    counterId,
                    counter == null
                            ? 1
                            : counter.value() + 1);
        }
    }

    private record CreateCounter(CounterId counterId) {
        @Apply
        Counter apply() {
            return new Counter(counterId, 1);
        }
    }
}
