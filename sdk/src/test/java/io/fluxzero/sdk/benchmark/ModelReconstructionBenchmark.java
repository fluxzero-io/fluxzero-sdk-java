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
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic for the cold independent-model reconstruction path.
 */
public class ModelReconstructionBenchmark {
    private static final int EVENT_COUNT =
            Integer.getInteger("eventCount", 10_000);
    private static final int ITERATIONS =
            Integer.getInteger("iterations", 5);

    public static void main(String[] args) {
        CounterId id = new CounterId("benchmark");
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null))) {
            prepare(fluxzero, id);
            for (int i = 0; i < 2; i++) {
                load(fluxzero, id);
            }
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                load(fluxzero, id);
            }
            long nanos = System.nanoTime() - start;
            double seconds = nanos / 1_000_000_000d;
            long replayedEvents = (long) EVENT_COUNT * ITERATIONS;
            System.out.printf(
                    "model reconstruction: %,d events/load, %d loads, %.1f ms/load, "
                    + "%,.0f events/s%n",
                    EVENT_COUNT, ITERATIONS,
                    nanos / 1_000_000d / ITERATIONS,
                    replayedEvents / seconds);
        }
    }

    private static void load(Fluxzero fluxzero, CounterId id) {
        Counter value = fluxzero.modelRepository().load(id).get();
        if (value == null || value.value() != EVENT_COUNT) {
            throw new IllegalStateException(
                    "Expected " + EVENT_COUNT + " but got " + value);
        }
    }

    private static void prepare(Fluxzero fluxzero, CounterId id) {
        List<ModelCommitStep> substeps = new ArrayList<>(EVENT_COUNT);
        for (int i = 0; i < EVENT_COUNT; i++) {
            Object event = i == 0 ? new CreateCounter(id) : new Increment(id);
            substeps.add(ModelCommitStep.builder()
                                 .event(new Message(event)
                                                .serialize(fluxzero.serializer()))
                                 .targets(List.of(ModelCommitTarget.builder()
                                                          .modelId(id.toString())
                                                          .storeEvent(true)
                                                          .updateState(true)
                                                          .relationships(List.of())
                                                          .build()))
                                 .build());
        }
        fluxzero.client().getEventStoreClient().commitModels(
                new CommitModels(
                        "benchmark-action", -1L, List.of(id.toString()),
                        substeps, ModelConflictPolicy.ACCEPT,
                        Guarantee.STORED, true)).join();
    }

    @Model(cached = false, snapshotPeriod = 0)
    private record Counter(@EntityId CounterId counterId, int value) {
    }

    private static final class CounterId extends Id<Counter> {
        private CounterId(String id) {
            super(id, "counter-");
        }
    }

    private record Increment(CounterId counterId) {
        @Apply
        Counter apply(Counter counter) {
            return new Counter(
                    counterId, counter == null ? 1 : counter.value() + 1);
        }
    }

    private record CreateCounter(CounterId counterId) {
        @Apply
        Counter apply() {
            return new Counter(counterId, 1);
        }
    }
}
