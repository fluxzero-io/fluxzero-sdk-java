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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;

public class AggregateApplyBenchmark {
    private static final int BRANCH_COUNT = Integer.getInteger("branches", 64);
    private static final int LEAVES_PER_BRANCH = Integer.getInteger("leavesPerBranch", 32);
    private static final int ITERATIONS = Integer.getInteger("iterations", 20_000);
    private static final String TARGET_AGGREGATE_ID = "aggregate-31";
    private static final String TARGET_BRANCH_ID = "branch-31";
    private static final String TARGET_LEAF_ID = "leaf-31-15";

    public static void main(String[] args) {
        try (Fluxzero flux = DefaultFluxzero.builder().build(LocalClient.newInstance())) {
            Fluxzero.applicationInstance.set(flux);
            System.out.printf(
                    "Benchmark config: branches=%d, leavesPerBranch=%d, iterations=%d, operation=load+apply+commit%n",
                    BRANCH_COUNT, LEAVES_PER_BRANCH, ITERATIONS);
            benchmarkCommands().forEach((name, command) -> runScenario(name, command));
        } finally {
            Fluxzero.applicationInstance.set(null);
        }
    }

    private static Map<String, Object> benchmarkCommands() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root-only", new UpdateRootOnly(TARGET_AGGREGATE_ID, "payload"));
        result.put("branch-only", new UpdateBranchOnly(TARGET_BRANCH_ID, "payload"));
        result.put("leaf-only", new UpdateLeafOnly(TARGET_LEAF_ID, "payload"));
        result.put("leaf-and-root", new UpdateLeafAndRoot(TARGET_LEAF_ID, "payload"));
        result.put("leaf-branch-root", new UpdateLeafBranchAndRoot(TARGET_LEAF_ID, "payload"));
        return result;
    }

    private static void runScenario(String name, Object command) {
        loadAggregate(TARGET_AGGREGATE_ID, BenchAggregate.class).update(aggregate -> createAggregate()).commit();
        TimingUtils.time(() -> IntStream.range(0, ITERATIONS).forEach(i ->
                loadAggregate(TARGET_AGGREGATE_ID, BenchAggregate.class).apply(command).commit()), duration -> {
            long operationsPerSecond = duration == 0 ? ITERATIONS : ITERATIONS * 1000L / duration;
            System.out.printf(
                    "%s: %d iterations in %dms (%d ops/s)%n",
                    name, ITERATIONS, duration, operationsPerSecond);
        });
    }

    private static BenchAggregate createAggregate() {
        return BenchAggregate.builder().id(TARGET_AGGREGATE_ID).branches(IntStream.range(0, BRANCH_COUNT).mapToObj(branch ->
                BenchBranch.builder().branchId("branch-" + branch).leaves(IntStream.range(0, LEAVES_PER_BRANCH)
                        .mapToObj(leaf -> BenchLeaf.builder()
                                .leafId("leaf-" + branch + "-" + leaf)
                                .payload("v" + leaf)
                                .build())
                        .toList()).build()).toList()).build();
    }

    @Value
    @Builder(toBuilder = true)
    @Aggregate(eventSourced = false, searchable = true)
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

    record UpdateRootOnly(@RoutingKey String aggregateId, String payload) {
        @Apply
        BenchAggregate apply(BenchAggregate aggregate) {
            return aggregate.toBuilder().lastTouched(payload).build();
        }
    }

    record UpdateBranchOnly(@RoutingKey String branchId, String payload) {
        @Apply
        BenchBranch apply(BenchBranch branch) {
            return branch.toBuilder().lastTouched(payload).build();
        }
    }

    record UpdateLeafOnly(@RoutingKey String leafId, String payload) {
        @Apply
        BenchLeaf apply(BenchLeaf leaf) {
            return leaf.toBuilder().payload(payload).build();
        }
    }

    record UpdateLeafAndRoot(@RoutingKey String leafId, String payload) {
        @Apply
        BenchLeaf apply(BenchLeaf leaf) {
            return leaf.toBuilder().payload(payload).build();
        }

        @Apply
        BenchAggregate apply(BenchAggregate aggregate) {
            return aggregate.toBuilder().lastTouched(leafId).build();
        }
    }

    record UpdateLeafBranchAndRoot(@RoutingKey String leafId, String payload) {
        @Apply
        BenchLeaf apply(BenchLeaf leaf) {
            return leaf.toBuilder().payload(payload).build();
        }

        @Apply
        BenchBranch apply(BenchBranch branch) {
            return branch.toBuilder().lastTouched(leafId).build();
        }

        @Apply
        BenchAggregate apply(BenchAggregate aggregate) {
            return aggregate.toBuilder().lastTouched(leafId).build();
        }
    }
}
