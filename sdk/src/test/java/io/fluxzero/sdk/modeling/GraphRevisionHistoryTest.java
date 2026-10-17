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
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Creation terminates Graph history without removing real deletion revisions or legacy Entity state. */
class GraphRevisionHistoryTest {
    @Test
    void revisionsExcludePreCreationAcrossCacheAndLifecyclePaths() {
        var creationPredecessors = new CopyOnWriteArrayList<Boolean>();
        TestFixture.create(new Object() {
            @HandleEvent
            void observe(Graph<Item> graph) {
                if (graph.sequenceNumber() == 0) creationPredecessors.add(graph.previous() == null);
            }
        }).givenCommands(new SetItem("ordinary", "first"), new SetItem("ordinary", "second"),
                         new SetItem("ordinary", "third"), new SetNoRetention("uncached", "first"),
                         new SetNoRetention("uncached", "second"), new SetNoRetention("uncached", "third"),
                         new SetSnapshot("snapshot", "first"), new SetSnapshot("snapshot", "second"),
                         new SetSnapshot("snapshot", "third"))
                .whenExecuting(fc -> {
                    assertEquals(List.of(true), creationPredecessors);
                    assertEquals(3, fc.client().getEventStoreClient().getModelEvents(new GetModelEvents(
                            List.of(new ModelEventStreamRequest("ordinary", -1, 10)), ModelReadBoundary.current(), 0))
                            .getStreams().getFirst().getMemberships().size());
                    inspect("warm ordinary", Fluxzero.loadGraph("ordinary", Item.class), List.of(2L, 1L, 0L));
                    inspect("warm depth zero", Fluxzero.loadGraph("uncached", NoRetention.class), List.of(2L, 1L, 0L));
                    inspect("warm snapshot", Fluxzero.loadGraph("snapshot", Snapshotted.class), List.of(2L, 1L, 0L));
                    fc.cache().clear();
                    inspect("cold ordinary", Fluxzero.loadGraph("ordinary", Item.class), List.of(2L, 1L, 0L));
                    inspect("cold depth zero", Fluxzero.loadGraph("uncached", NoRetention.class), List.of(2L, 1L, 0L));
                    inspect("cold snapshot", Fluxzero.loadGraph("snapshot", Snapshotted.class), List.of(2L, 1L, 0L));
                    inspect("never created", Fluxzero.loadGraph("absent", Item.class), List.of());
                }).expectNoErrors()
                .andThen().whenCommand(new DeleteItem("ordinary")).expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    inspect("deleted", Fluxzero.loadGraph("ordinary", Item.class), List.of(3L, 2L, 1L, 0L));
                    fc.cache().clear();
                    inspect("cold deleted", Fluxzero.loadGraph("ordinary", Item.class), List.of(3L, 2L, 1L, 0L));
                }).expectNoErrors()
                .andThen().whenCommand(new SetItem("ordinary", "recreated")).expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    inspect("recreated", Fluxzero.loadGraph("ordinary", Item.class), List.of(4L, 3L, 2L, 1L, 0L));
                    assertNull(Fluxzero.loadGraph("ordinary", Item.class).previous().get());
                }).expectNoErrors();
    }

    @Test
    void nonEmptyProvisionalGraphRemainsInHistory() {
        TestFixture.create().whenExecuting(fc -> {
            Item value = new Item("provisional", "draft");
            Graph<Item> provisional = Fluxzero.loadGraph(value.itemId(), Item.class).update(ignored -> value);
            assertEquals(-1, provisional.sequenceNumber());
            assertEquals(-1, provisional.revisionStateIndex());
            assertEquals(List.of(value), provisional.revisions().map(Graph::get).toList());
        }).expectNoErrors();
    }

    @Test
    void nonEmptyUncommittedPredecessorRemainsInHistory() {
        TestFixture.create().whenExecuting(fc -> {
            Item before = new Item("provisional", "draft");
            Item after = new Item("provisional", "updated");
            var predecessor = ImmutableModelRoot.initial(before.itemId(), Item.class, "itemId", before);
            var current = ImmutableModelRoot.initial(after.itemId(), Item.class, "itemId", after)
                    .withPrevious(predecessor);
            Graph<Item> graph = Graphs.lazy(current, -1, fc.modelRepository());
            assertEquals(before, graph.previous().get());
            assertEquals(List.of(after, before), graph.revisions().map(Graph::get).toList());
        }).expectNoErrors();
    }

    @Test
    void legacyEntityRetainsItsEmptyPreCreationState() {
        TestFixture.create().whenExecuting(fc -> {
            Entity<Legacy> created = Fluxzero.loadAggregate("legacy", Legacy.class).apply(new CreateLegacy());
            assertEquals(0, created.sequenceNumber());
            assertNotNull(created.previous());
            assertEquals(-1, created.previous().sequenceNumber());
            assertNull(created.previous().get());
        }).expectNoErrors();
    }

    @Aggregate record Legacy(String value) {
        @Apply static Legacy create(CreateLegacy event) { return new Legacy("created"); }
    }
    record CreateLegacy() {}

    private void inspect(String label, Graph<?> graph, List<Long> expectedSequences) {
        var rows = graph.revisions().limit(10).map(g -> new Revision(g.sequenceNumber(),
                g.revisionStateIndex(), g.get() == null, g.lastEventId())).toList();
        assertEquals(expectedSequences, rows.stream().map(Revision::sequence).toList(), label);
        if (expectedSequences.isEmpty()) {
            assertNull(graph.get());
            assertNull(graph.previous());
        } else {
            Graph<?> creation = graph;
            while (creation.previous() != null) creation = creation.previous();
            assertEquals(0, creation.sequenceNumber());
            assertNotNull(creation.get());
        }
        if (label.contains("deleted")) assertTrue(rows.getFirst().empty());
    }

    record Revision(long sequence, long stateIndex, boolean empty, String eventId) {}
    @Model record Item(@EntityId String itemId, String name) {}
    @Model(cachingDepth = 0) record NoRetention(@EntityId String itemId, String name) {}
    @Model(snapshotPeriod = 1, cachingDepth = 0) record Snapshotted(@EntityId String itemId, String name) {}
    record SetItem(String itemId, String name) {
        @Apply Item apply(@Nullable Item current) { return new Item(itemId, name); }
    }
    record SetNoRetention(String itemId, String name) {
        @Apply NoRetention apply(@Nullable NoRetention current) { return new NoRetention(itemId, name); }
    }
    record SetSnapshot(String itemId, String name) {
        @Apply Snapshotted apply(@Nullable Snapshotted current) { return new Snapshotted(itemId, name); }
    }
    record DeleteItem(String itemId) { @Apply Item apply(Item item) { return null; } }
}
