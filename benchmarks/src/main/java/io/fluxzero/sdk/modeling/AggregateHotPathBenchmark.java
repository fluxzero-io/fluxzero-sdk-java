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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.routing.MessageRoutingInterceptor;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.handling.Invocation;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A deterministic load, replay, nested apply, event staging, and commit aggregate lifecycle. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms384m", "-Xmx384m", "-XX:+AlwaysPreTouch"})
public class AggregateHotPathBenchmark {
    private static final String AGGREGATE_ID = "aggregate-1";
    private static final String BRANCH_ID = "branch-7";
    private static final String LEAF_ID = "leaf-7-7";
    private static final int HISTORICAL_EVENT_COUNT = 50;

    private JacksonSerializer serializer;
    private DefaultEntityHelper entityHelper;
    private List<DeserializingMessage> historicalEvents;
    private long commitChecksum;
    private boolean verifyCommit;

    @Setup(Level.Trial)
    public void setUp() {
        serializer = new JacksonSerializer();
        List<ParameterResolver<? super DeserializingMessage>> parameterResolvers =
                List.of(new EntityParameterResolver());
        entityHelper = new DefaultEntityHelper(parameterResolvers, false);
        historicalEvents = createHistoricalEvents();

        verifyCommit = true;
        aggregateLifecycle();
        verifyCommit = false;
    }

    /**
     * Reconstructs a nested aggregate from 50 events, applies three new events to root/branch/leaf targets, serializes
     * and stages them, and commits through an immediately completed storage boundary.
     */
    @Benchmark
    public long aggregateLifecycle() {
        Entity<BenchmarkAggregate> aggregate = ModifiableAggregateRoot.load(
                AGGREGATE_ID, this::replayAggregate, AggregateCommitPolicy.SYNC_AFTER_HANDLER,
                EventPublication.ALWAYS, EventPublicationStrategy.STORE_AND_PUBLISH,
                AggregateEventRouting.MESSAGE_ROUTING_KEY, true, entityHelper, serializer,
                new MessageRoutingInterceptor(), this::commit);

        Invocation.performInvocation(() -> {
            aggregate.apply(new RootUpdated(AGGREGATE_ID, 101));
            aggregate.apply(new BranchUpdated(BRANCH_ID, 102));
            aggregate.apply(new LeafUpdated(LEAF_ID, "current"));
            return null;
        });
        return commitChecksum + aggregate.get().version();
    }

    private Entity<BenchmarkAggregate> replayAggregate() {
        Entity<BenchmarkAggregate> model = ImmutableAggregateRoot.<BenchmarkAggregate>builder()
                .id(AGGREGATE_ID)
                .idProperty("id")
                .type(BenchmarkAggregate.class)
                .value(createAggregate())
                .entityHelper(entityHelper)
                .serializer(serializer)
                .sequenceNumber(0L)
                .build();

        boolean wasLoading = Entity.isLoading();
        Map<?, String> previousRouteCache = new LinkedHashMap<>(ImmutableEntity.snapshotLoadingRouteCache());
        Map<?, ?> previousEntityCache = new LinkedHashMap<>(AnnotatedEntityHolder.snapshotLoadingEntityCache());
        Map<?, ?> previousRouteValuesCache =
                new LinkedHashMap<>(AnnotatedEntityHolder.snapshotLoadingRouteValuesCache());
        try {
            Entity.loading.set(true);
            ImmutableEntity.clearLoadingRouteCache();
            AnnotatedEntityHolder.clearLoadingEntityCache();
            AnnotatedEntityHolder.clearLoadingRouteValuesCache();
            for (DeserializingMessage event : historicalEvents) {
                model = model.apply(event);
            }
            return model;
        } finally {
            AnnotatedEntityHolder.restoreLoadingRouteValuesCache(previousRouteValuesCache);
            AnnotatedEntityHolder.restoreLoadingEntityCache(previousEntityCache);
            ImmutableEntity.restoreLoadingRouteCache(previousRouteCache);
            Entity.loading.set(wasLoading);
        }
    }

    private CompletableFuture<Void> commit(
            Entity<?> model, List<AppliedEvent> unpublished, Entity<?> beforeUpdate) {
        if (verifyCommit) {
            verifyCommittedModel(model, unpublished);
        }
        long updated = commitChecksum + model.sequenceNumber();
        for (AppliedEvent event : unpublished) {
            updated += event.getEvent().getSerializedObject().getBytes();
            updated += event.getPublicationStrategy().ordinal();
        }
        commitChecksum = updated;
        return CompletableFuture.completedFuture(null);
    }

    private static void verifyCommittedModel(Entity<?> model, List<AppliedEvent> unpublished) {
        BenchmarkAggregate aggregate = (BenchmarkAggregate) model.get();
        BenchmarkBranch branch = aggregate.branches().stream()
                .filter(candidate -> BRANCH_ID.equals(candidate.branchId())).findFirst().orElseThrow();
        BenchmarkLeaf leaf = branch.leaves().stream()
                .filter(candidate -> LEAF_ID.equals(candidate.leafId())).findFirst().orElseThrow();
        if (unpublished.size() != 3 || aggregate.version() != 101 || branch.version() != 102
            || !"current".equals(leaf.value())) {
            throw new IllegalStateException("Aggregate scenario did not replay, apply, stage, and commit as expected");
        }
    }

    private List<DeserializingMessage> createHistoricalEvents() {
        List<DeserializingMessage> result = new ArrayList<>(HISTORICAL_EVENT_COUNT);
        for (int i = 0; i < HISTORICAL_EVENT_COUNT; i++) {
            Object event = switch (i % 3) {
                case 0 -> new RootUpdated(AGGREGATE_ID, i);
                case 1 -> new BranchUpdated(BRANCH_ID, i);
                default -> new LeafUpdated(LEAF_ID, "historical-" + i);
            };
            result.add(new DeserializingMessage(new Message(event), MessageType.EVENT, serializer));
        }
        return List.copyOf(result);
    }

    private static BenchmarkAggregate createAggregate() {
        List<BenchmarkBranch> branches = new ArrayList<>(8);
        for (int branch = 0; branch < 8; branch++) {
            List<BenchmarkLeaf> leaves = new ArrayList<>(8);
            for (int leaf = 0; leaf < 8; leaf++) {
                leaves.add(new BenchmarkLeaf("leaf-" + branch + "-" + leaf, "value-" + leaf));
            }
            branches.add(new BenchmarkBranch("branch-" + branch, List.copyOf(leaves), 0));
        }
        return new BenchmarkAggregate(AGGREGATE_ID, List.copyOf(branches), 0);
    }

    @Aggregate(cached = false)
    record BenchmarkAggregate(@EntityId String id, @Member List<BenchmarkBranch> branches, int version) {
    }

    record BenchmarkBranch(@EntityId String branchId, @Member List<BenchmarkLeaf> leaves, int version) {
    }

    record BenchmarkLeaf(@EntityId String leafId, String value) {
    }

    record RootUpdated(@RoutingKey String aggregateId, int version) {
        @Apply
        BenchmarkAggregate apply(BenchmarkAggregate aggregate) {
            return new BenchmarkAggregate(aggregate.id(), aggregate.branches(), version);
        }
    }

    record BranchUpdated(@RoutingKey String branchId, int version) {
        @Apply
        BenchmarkBranch apply(BenchmarkBranch branch) {
            return new BenchmarkBranch(branch.branchId(), branch.leaves(), version);
        }
    }

    record LeafUpdated(@RoutingKey String leafId, String value) {
        @Apply
        BenchmarkLeaf apply(BenchmarkLeaf leaf) {
            return new BenchmarkLeaf(leaf.leafId(), value);
        }
    }
}
