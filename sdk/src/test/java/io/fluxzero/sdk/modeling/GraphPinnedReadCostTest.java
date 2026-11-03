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

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitTrackedThrough;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class GraphPinnedReadCostTest {
    private static final ValueId ROOT = new ValueId("root");
    private static final List<ValueId> DEPENDENCIES = List.of(
            new ValueId("first"), new ValueId("second"), new ValueId("third"));
    private static final List<Value> VALUES = List.of(
            new Value(ROOT, 1), new Value(DEPENDENCIES.get(0), 1),
            new Value(DEPENDENCIES.get(1), 1), new Value(DEPENDENCIES.get(2), 1));

    /** Warm snapshot reads retain head validation without replaying unchanged values. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedPinnedReadsReuseUnchangedDependencies(boolean pinned) {
        var client = new ObservedClient();
        var fixture = TestFixture.createAsync(DefaultFluxzero.builder(), client)
                .givenCommands(VALUES.stream().map(v -> new PutValue(v.valueId(), v.revision())).toList())
                .given(fc -> {
                    awaitCacheTracking(fc);
                    awaitTrackedThrough(client.polls, fc.apply(ignored -> Fluxzero.loadCurrentGraph(ROOT).stateIndex()));
                    clearCacheAndAwait(fc);
                });
        var query = new GetValues(ROOT, DEPENDENCIES, pinned);

        // Warm the same path from an empty SDK cache. No writes occur between reads.
        fixture.whenQuery(query).expectResult((Snapshot result) -> result.values().equals(VALUES))
                .expectNoEvents().expectNoCommands();
        for (int repeat = 0; repeat < 2; repeat++) {
            client.reads.clear();
            fixture.whenQuery(query)
                    .expectResult((Snapshot result) -> {
                        assertEquals(VALUES, result.values());
                        if (pinned) {
                            assertTrue(client.reads.stream()
                                    .filter(r -> r.request().getRequests().stream()
                                            .noneMatch(s -> s.getModelId().equals(ROOT.toString())))
                                    .allMatch(r -> Long.valueOf(result.stateIndex())
                                            .equals(r.request().getBoundary().stateIndex())));
                        }
                        return true;
                    }).expectNoEvents().expectNoCommands();
            // The injected root needs no getModelEvents call in this minimal fixture.
            assertEquals(3, client.reads.size());
            assertEquals(Map.of("first", 1L,
                               "second", 1L, "third", 1L),
                    client.reads.stream().flatMap(r -> r.request().getRequests().stream())
                            .collect(groupingBy(r -> r.getModelId(), counting())));
            assertEquals(0, client.reads.stream()
                    .mapToInt(r -> r.result().getPayloadCount()).sum());
            long bytes = client.reads.stream().mapToLong(r -> r.result().getBytes()).sum();
            assertEquals(0L, bytes);
        }
    }

    @Test
    void olderSnapshotCannotReplaceANewerCachedRevision() {
        try (Fluxzero app = app(new ObservedClient())) {
            commit(app, new PutValue(ROOT, 1));
            long first = app.apply(fc -> Fluxzero.loadCurrentGraph(ROOT).stateIndex());
            clearCacheAndAwait(app);
            assertEquals(1, app.apply(fc -> Fluxzero.loadGraph(ROOT).atStateIndex(first).get()).revision());
            commit(app, new PutValue(ROOT, 2));
            Value current = app.apply(fc -> Fluxzero.loadCurrentGraph(ROOT).get());
            for (int repeat = 0; repeat < 2; repeat++) {
                assertEquals(1, app.apply(fc -> Fluxzero.loadGraph(ROOT).atStateIndex(first).get()).revision());
                assertSame(current, app.apply(fc -> Fluxzero.loadCurrentGraph(ROOT).get()));
            }
            Graph<Value> latest = app.apply(fc -> Fluxzero.loadCurrentGraph(ROOT));
            assertEquals(1, latest.previous().get().revision());
            clearCacheAndAwait(app);
            assertEquals(1, app.apply(fc -> Fluxzero.loadGraph(ROOT).atStateIndex(first).get()).revision());
            assertEquals(2, app.apply(fc -> Fluxzero.loadCurrentGraph(ROOT).get()).revision());
        }
    }

    @Test
    void unchangedCachedValueRetainsTheRequestedRelationshipSnapshot() {
        try (Fluxzero app = app(new ObservedClient())) {
            commit(app, new PutRelated("parent", null));
            long first = app.apply(fc -> Fluxzero.loadCurrentGraph("parent", Related.class).stateIndex());
            clearCacheAndAwait(app);
            Related value = app.apply(fc -> Fluxzero.loadGraph("parent", Related.class).atStateIndex(first).get());
            commit(app, new PutRelated("child", "parent"));
            long second = app.apply(fc -> Fluxzero.loadCurrentGraph("parent", Related.class).stateIndex());

            Graph<Related> old = app.apply(fc -> Fluxzero.loadGraph("parent", Related.class).atStateIndex(first));
            Graph<Related> fresh = app.apply(fc -> Fluxzero.loadGraph("parent", Related.class).atStateIndex(second));
            assertSame(value, old.get());
            assertSame(value, fresh.get());
            assertEquals(first, old.stateIndex());
            assertEquals(second, fresh.stateIndex());
            assertTrue(old.children().isEmpty());
            assertEquals(List.of("child"), fresh.children(Related.class).stream().map(Graph::id).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historicalReadsHonorBothCacheOptOuts(boolean applicationOptOut) {
        var client = new ObservedClient();
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
        if (applicationOptOut) { builder.disableAutomaticModelCaching(); }
        try (Fluxzero app = builder.build(client)) {
            Class<?> type = applicationOptOut ? Value.class : Uncached.class;
            commit(app, applicationOptOut ? new PutValue(ROOT, 1) : new PutUncached(ROOT.toString()));
            long boundary = app.apply(fc -> Fluxzero.loadCurrentGraph(ROOT.toString(), type).stateIndex());
            clearCacheAndAwait(app);
            for (int repeat = 0; repeat < 2; repeat++) {
                client.reads.clear();
                Object value = app.apply(fc -> Fluxzero.loadGraph(ROOT.toString(), type).atStateIndex(boundary).get());
                assertNotNull(value);
                assertEquals(1, client.reads.stream().mapToInt(r -> r.result().getPayloadCount()).sum());
            }
        }
    }

    @Test
    void historicalCacheDoesNotCrossNamespacesOrHideErasure() {
        LocalClient client = LocalClient.newInstance();
        try (Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(client.forNamespace("blue"));
             Fluxzero other = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(client.forNamespace("red"));
             Fluxzero app = app(client)) {
            var blue = app.modelRepository().forNamespace("blue");
            var red = app.modelRepository().forNamespace("red");
            commit(writer, new PutValue(ROOT, 1));
            commit(other, new PutValue(ROOT, 9));
            long blueBoundary = Graphs.lazyCurrent(ROOT, Value.class, blue).stateIndex();
            long redBoundary = Graphs.lazyCurrent(ROOT, Value.class, red).stateIndex();
            clearCacheAndAwait(app);
            for (int repeat = 0; repeat < 2; repeat++) {
                assertEquals(1, Graphs.lazy(ROOT, Value.class, blue).atStateIndex(blueBoundary).get().revision());
                assertEquals(9, Graphs.lazy(ROOT, Value.class, red).atStateIndex(redBoundary).get().revision());
            }
            writer.modelRepository().deleteModel(ROOT.toString(), ModelDeletionCascade.NONE).join();
            assertNull(Graphs.lazy(ROOT, Value.class, blue).atStateIndex(blueBoundary).get());
            assertEquals(9, Graphs.lazy(ROOT, Value.class, red).atStateIndex(redBoundary).get().revision());
        }
    }

    private static Fluxzero app(LocalClient client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
    }

    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    @Model(searchable = false)
    record Related(@EntityId String id, @Parent(value = Related.class, pathInParent = "children") String parentId) {}

    record PutRelated(String id, String parentId) {
        @Apply Related apply() { return new Related(id, parentId); }
    }

    @Model(searchable = false, cached = false)
    record Uncached(@EntityId String id) {}

    record PutUncached(String id) {
        @Apply Uncached apply() { return new Uncached(id); }
    }

    record GetValues(ValueId valueId, List<ValueId> dependencies, boolean pinned) {
        @HandleQuery
        Snapshot handle(Graph<Value> root) {
            var values = new ArrayList<Value>();
            values.add(root.get());
            long stateIndex = root.stateIndex();
            for (ValueId id : dependencies) {
                values.add(pinned ? Fluxzero.loadGraph(id).atStateIndex(stateIndex).get()
                                  : Fluxzero.loadCurrentGraph(id).get());
            }
            return new Snapshot(stateIndex, values);
        }
    }

    record Snapshot(long stateIndex, List<Value> values) {}

    @Model(searchable = false)
    record Value(@EntityId ValueId valueId, int revision) {}

    static final class ValueId extends Id<Value> {
        ValueId(String id) { super(id); }
    }

    record PutValue(ValueId valueId, int revision) {
        @Apply
        Value apply(@jakarta.annotation.Nullable Value before) { return new Value(valueId, revision); }
    }

    record Read(GetModelEvents request, GetModelEventsResult.Metric result) {}

    /** Observes real in-memory storage; model responses are never synthesized. */
    private static final class ObservedClient extends LocalClient {
        final List<Read> reads = new CopyOnWriteArrayList<>();
        final BlockingQueue<TrackModelUpdates> polls = new LinkedBlockingQueue<>();

        ObservedClient() { super(null); }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient storage = super.createEventStoreClient();
            EventStoreClient observed = mock(EventStoreClient.class, delegatesTo(storage));
            doAnswer(invocation -> {
                GetModelEvents request = invocation.getArgument(0);
                GetModelEventsResult result = storage.getModelEvents(request);
                reads.add(new Read(request, result.toMetric()));
                return result;
            }).when(observed).getModelEvents(any());
            doAnswer(invocation -> {
                TrackModelUpdates request = invocation.getArgument(0);
                polls.add(request);
                return storage.trackModelUpdates(request);
            }).when(observed).trackModelUpdates(any());
            return observed;
        }
    }
}
