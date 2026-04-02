/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.common.TimingUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.stream.IntStream;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;

public class EventSourcedAggregateLoadBenchmark {
    private static final int BRANCH_COUNT = Integer.getInteger("branches", 8);
    private static final int LEAVES_PER_BRANCH = Integer.getInteger("leavesPerBranch", 8);
    private static final int EVENT_COUNT = Integer.getInteger("eventCount", 1_000);
    private static final int LOAD_ITERATIONS = Integer.getInteger("loadIterations", 1_000);
    private static final long PAUSE_AFTER_PREPARE_MS = Long.getLong("pauseAfterPrepareMs", 0L);
    private static final String SCENARIO = System.getProperty("scenario");

    private static final String ROOT_AGGREGATE_ID = "bench-root";
    private static final String BRANCH_AGGREGATE_ID = "bench-branch";
    private static final String LEAF_AGGREGATE_ID = "bench-leaf";

    private static final String TARGET_BRANCH_ID = "branch-7";
    private static final String TARGET_LEAF_ID = "leaf-7-7";

    public static void main(String[] args) {
        try (Fluxzero flux = DefaultFluxzero.builder()
                .disableAutomaticAggregateCaching()
                .build(LocalClient.newInstance())) {
            Fluxzero.applicationInstance.set(flux);
            System.out.printf(
                    "Load benchmark config: pid=%d, scenario=%s, branches=%d, leavesPerBranch=%d, storedEvents=%d, "
                    + "loadIterations=%d, cache=disabled%n",
                    ProcessHandle.current().pid(), SCENARIO == null ? "all" : SCENARIO,
                    BRANCH_COUNT, LEAVES_PER_BRANCH, EVENT_COUNT + 1, LOAD_ITERATIONS);

            benchmarkIfSelected("root-only", ROOT_AGGREGATE_ID, new RootOnlyUpdate(ROOT_AGGREGATE_ID, "payload"));
            benchmarkIfSelected("branch-only", BRANCH_AGGREGATE_ID, new BranchOnlyUpdate(TARGET_BRANCH_ID, "payload"));
            benchmarkIfSelected("leaf-only", LEAF_AGGREGATE_ID, new LeafOnlyUpdate(TARGET_LEAF_ID, "payload"));
        } finally {
            Fluxzero.applicationInstance.set(null);
        }
    }

    private static void benchmarkIfSelected(String name, String aggregateId, Object event) {
        if (SCENARIO != null && !SCENARIO.equals(name)) {
            return;
        }
        benchmark(name, aggregateId, event);
    }

    private static void benchmark(String name, String aggregateId, Object event) {
        prepareAggregate(aggregateId, event);
        if (PAUSE_AFTER_PREPARE_MS > 0) {
            System.out.printf("%s: prepared, pausing for %dms before timing%n", name, PAUSE_AFTER_PREPARE_MS);
            sleep(PAUSE_AFTER_PREPARE_MS);
        }
        TimingUtils.time(() -> IntStream.range(0, LOAD_ITERATIONS).forEach(i -> {
            BenchAggregate aggregate = loadAggregate(aggregateId, BenchAggregate.class).get();
            if (aggregate == null || aggregate.getBranches().isEmpty()) {
                throw new IllegalStateException("Aggregate not loaded");
            }
        }), duration -> {
            long loadsPerSecond = duration == 0 ? LOAD_ITERATIONS : LOAD_ITERATIONS * 1000L / duration;
            System.out.printf(
                    "%s: %d loads in %dms (%d loads/s)%n",
                    name, LOAD_ITERATIONS, duration, loadsPerSecond);
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start benchmark", e);
        }
    }

    private static void prepareAggregate(String aggregateId, Object event) {
        loadAggregate(aggregateId, BenchAggregate.class).apply(new CreateAggregate(aggregateId, createAggregate(aggregateId))).commit();
        for (int i = 0; i < EVENT_COUNT; i++) {
            loadAggregate(aggregateId, BenchAggregate.class).apply(event).commit();
        }
    }

    private static BenchAggregate createAggregate(String aggregateId) {
        return BenchAggregate.builder().id(aggregateId).branches(IntStream.range(0, BRANCH_COUNT).mapToObj(branch ->
                BenchBranch.builder().branchId("branch-" + branch).leaves(IntStream.range(0, LEAVES_PER_BRANCH)
                        .mapToObj(leaf -> BenchLeaf.builder()
                                .leafId("leaf-" + branch + "-" + leaf)
                                .payload("v" + leaf)
                                .build())
                        .toList()).build()).toList()).build();
    }

    @Value
    @Builder(toBuilder = true)
    @Aggregate(cached = false, snapshotPeriod = 0)
    static class BenchAggregate {
        @EntityId
        String id;

        @Member
        @Singular
        List<BenchBranch> branches;

        @Default
        String lastTouched = "";
    }

    @Value
    @Builder(toBuilder = true)
    static class BenchBranch {
        @EntityId
        String branchId;

        @Member
        @Singular
        List<BenchLeaf> leaves;

        @Default
        String lastTouched = "";
    }

    @Value
    @Builder(toBuilder = true)
    static class BenchLeaf {
        @EntityId
        String leafId;
        String payload;
    }

    record CreateAggregate(@RoutingKey String aggregateId, BenchAggregate aggregate) {
        @Apply
        BenchAggregate apply() {
            return aggregate;
        }
    }

    record RootOnlyUpdate(@RoutingKey String aggregateId, String payload) {
        @Apply
        BenchAggregate apply(BenchAggregate aggregate) {
            return aggregate.toBuilder().lastTouched(payload).build();
        }
    }

    record BranchOnlyUpdate(@RoutingKey String branchId, String payload) {
        @Apply
        BenchBranch apply(BenchBranch branch) {
            return branch.toBuilder().lastTouched(payload).build();
        }
    }

    record LeafOnlyUpdate(@RoutingKey String leafId, String payload) {
        @Apply
        BenchLeaf apply(BenchLeaf leaf) {
            return leaf.toBuilder().payload(payload).build();
        }
    }
}
