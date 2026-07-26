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
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;

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

    public static void main(String[] args) {
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

            long cold = timed(() ->
                                      load(
                                              fluxzero, id,
                                              EVENT_COUNT));
            long warm = timed(() -> {
                for (int i = 0; i < WARM_LOADS; i++) {
                    load(
                            fluxzero, id,
                            EVENT_COUNT);
                }
            });
            long readStateIndex =
                    ((ModelRoot<?>) fluxzero
                            .modelRepository()
                            .load(id)).stateIndex();
            append(
                    fluxzero, id,
                    readStateIndex);
            long catchUp = timed(() ->
                                         load(
                                                 fluxzero, id,
                                                 EVENT_COUNT + 1));
            repository.invalidateModels(
                    List.of(id.toString()));
            long invalidated = timed(() ->
                                             load(
                                                     fluxzero, id,
                                                     EVENT_COUNT + 1));

            System.out.printf(
                    "model cache: %,d-event cold %.3f ms; %,d warm head-check loads %.3f us/load; "
                    + "one-event suffix %.3f ms; invalidated full reload %.3f ms%n",
                    EVENT_COUNT,
                    millis(cold),
                    WARM_LOADS,
                    micros(warm) / WARM_LOADS,
                    millis(catchUp),
                    millis(invalidated));
        }
    }

    private static long timed(Runnable task) {
        long started = System.nanoTime();
        task.run();
        return System.nanoTime() - started;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000d;
    }

    private static double micros(long nanos) {
        return nanos / 1_000d;
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
                                "cache-benchmark-append",
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
