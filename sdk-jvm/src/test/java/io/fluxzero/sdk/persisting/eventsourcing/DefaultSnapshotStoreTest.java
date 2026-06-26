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

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSnapshotStoreTest {

    private final TestFixture testFixture = TestFixture.create(new Handler()).spy();

    @Test
    void noSnapshotStoredBeforeThreshold() {
        var aggregateId = new MultiSnapshotCounterId("test");
        testFixture.givenCommands(new CreateMultiSnapshotCounter(aggregateId))
                .whenCommand(new IncrementMultiSnapshotCounter(aggregateId))
                .expectThat(fc -> assertTrue(fc.documentStore()
                        .search(DefaultSnapshotStore.SNAPSHOT_COLLECTION)
                        .fetchAll().isEmpty()));
    }

    @Test
    void snapshotStoredAfterThreshold() {
        var aggregateId = new MultiSnapshotCounterId("test");
        testFixture.givenCommands(new CreateMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId))
                .whenCommand(new IncrementMultiSnapshotCounter(aggregateId))
                .expectThat(fc -> assertEquals(1, fc.documentStore()
                        .search(DefaultSnapshotStore.SNAPSHOT_COLLECTION)
                        .match(aggregateId.toString(), true, "aggregateId")
                        .fetchAll().size()));
    }

    @Test
    void getSnapshotBeforeReturnsOlderSnapshot() {
        var aggregateId = new MultiSnapshotCounterId("counter");
        testFixture.givenCommands(new CreateMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId))
                .whenApplying(fc -> {
                    Entity<?> latest = fc.snapshotStore().getSnapshot(aggregateId)
                            .orElseThrow();
                    Entity<?> previous = fc.snapshotStore()
                            .getSnapshotBefore(aggregateId, latest.sequenceNumber())
                            .orElseThrow();
                    return List.of(latest.sequenceNumber(), ((MultiSnapshotCounterAggregate) latest.get()).value,
                                   previous.sequenceNumber(), ((MultiSnapshotCounterAggregate) previous.get()).value);
                })
                .expectResult(List.of(5L, 5, 2L, 2));
    }

    @Test
    void trimKeepsConfiguredMaximumNumberOfSnapshots() {
        var aggregateId = new MultiSnapshotCounterId("counter");
        testFixture.givenCommands(new CreateMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId),
                                  new IncrementMultiSnapshotCounter(aggregateId))
                .whenApplying(fc -> fc.documentStore().search(DefaultSnapshotStore.SNAPSHOT_COLLECTION)
                        .match(aggregateId.toString(), true, "aggregateId")
                        .sortBy("sequenceNumber", true)
                        .fetchAll(DefaultSnapshotStore.SnapshotDocument.class).stream()
                        .map(DefaultSnapshotStore.SnapshotDocument::sequenceNumber)
                        .toList())
                .expectResult(List.of(5L, 2L));
    }

    @Test
    void snapshotLookupAndDeleteMatchAggregateIdStrictly() {
        var shortId = new MultiSnapshotCounterId("alpha");
        var longId = new MultiSnapshotCounterId("alpha-extended");
        testFixture.givenCommands(new CreateMultiSnapshotCounter(shortId),
                                  new IncrementMultiSnapshotCounter(shortId),
                                  new IncrementMultiSnapshotCounter(shortId),
                                  new CreateMultiSnapshotCounter(longId),
                                  new IncrementMultiSnapshotCounter(longId),
                                  new IncrementMultiSnapshotCounter(longId),
                                  new IncrementMultiSnapshotCounter(longId),
                                  new IncrementMultiSnapshotCounter(longId),
                                  new IncrementMultiSnapshotCounter(longId))
                .whenApplying(fc -> {
                    var store = fc.snapshotStore();
                    long shortSequence = store.<MultiSnapshotCounterAggregate>getSnapshot(shortId)
                            .orElseThrow().sequenceNumber();
                    long longSequenceBeforeDelete = store.<MultiSnapshotCounterAggregate>getSnapshot(longId)
                            .orElseThrow().sequenceNumber();
                    store.deleteSnapshot(shortId).join();
                    boolean shortExistsAfterDelete = store.getSnapshot(shortId).isPresent();
                    long longSequenceAfterDelete = store.<MultiSnapshotCounterAggregate>getSnapshot(longId)
                            .orElseThrow().sequenceNumber();
                    return List.of(shortSequence, longSequenceBeforeDelete,
                                   shortExistsAfterDelete ? 1L : 0L, longSequenceAfterDelete);
                })
                .expectResult(List.of(2L, 5L, 0L, 5L));
    }

    private static class Handler {
        @HandleCommand
        void handle(CreateMultiSnapshotCounter command) {
            loadAggregate(command.id(), MultiSnapshotCounterAggregate.class).assertAndApply(command);
        }

        @HandleCommand
        void handle(IncrementMultiSnapshotCounter command) {
            loadAggregate(command.id(), MultiSnapshotCounterAggregate.class).assertAndApply(command);
        }
    }

    static class MultiSnapshotCounterId extends Id<MultiSnapshotCounterAggregate> {
        MultiSnapshotCounterId(String functionalId) {
            super(functionalId, MultiSnapshotCounterAggregate.class, "counter-", true);
        }
    }

    record CreateMultiSnapshotCounter(MultiSnapshotCounterId id) {
    }

    record IncrementMultiSnapshotCounter(MultiSnapshotCounterId id) {
    }

    @Aggregate(snapshotPeriod = 3, maxSnapshotCount = 2, cached = false)
    @Value
    static class MultiSnapshotCounterAggregate {
        MultiSnapshotCounterId id;
        int value;

        @io.fluxzero.sdk.persisting.eventsourcing.Apply
        static MultiSnapshotCounterAggregate create(CreateMultiSnapshotCounter event) {
            return new MultiSnapshotCounterAggregate(event.id(), 0);
        }

        @io.fluxzero.sdk.persisting.eventsourcing.Apply
        MultiSnapshotCounterAggregate apply(IncrementMultiSnapshotCounter event) {
            return new MultiSnapshotCounterAggregate(id, value + 1);
        }
    }
}
