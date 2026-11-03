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
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import jakarta.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitTrackedThrough;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ModelAliasReadCostTest {
    private static final ValueId ID = new ValueId("custom-owner");
    private static final String ALIAS = "friendly-name";
    private static final Value VALUE = new Value(ID, ALIAS, 1);
    private static final Lookup EXPECTED = new Lookup(ID.toString(), VALUE);

    /** Characterizes avoidable replay on current main, not a required cost for future implementations. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void literalAliasReplaysEvenWhenItsCanonicalModelIsWarm(boolean synchronous) {
        var client = new ObservedClient();
        var fixture = new ObservedFixture(client, synchronous)
                .givenCommands(new PutValue(ID, ALIAS, 1))
                .given(fc -> {
                    awaitCacheTracking(fc);
                    awaitTrackedThrough(client.polls, fc.apply(ignored -> Fluxzero.loadCurrentGraph(ID).stateIndex()));
                    clearCacheAndAwait(fc);
                });

        // First resolve the literal alias from an empty cache.
        client.reads.clear();
        read(fixture, ALIAS);
        assertReplay(client, ALIAS);

        // Warm the actual ID explicitly; it cannot be derived from this alias.
        read(fixture, ID.toString());
        client.reads.clear();
        read(fixture, ID.toString());
        assertEquals(0, client.reads.size(), "Canonical control must already be warm");

        for (int repeat = 0; repeat < 2; repeat++) {
            client.reads.clear();
            read(fixture, ALIAS);
            assertReplay(client, ALIAS);

            client.reads.clear();
            read(fixture, ID.toString());
            assertEquals(0, client.reads.size(), "Alias reads must not evict the warm canonical value");
        }
    }

    private static void read(TestFixture fixture, String key) {
        fixture.whenQuery(new ReadValue(key)).expectResult(EXPECTED).expectNoEvents().expectNoCommands();
    }

    private static void assertReplay(ObservedClient client, String key) {
        assertEquals(1, client.reads.size());
        assertEquals(List.of(key), client.reads.getFirst().request().getRequests().stream()
                .map(request -> request.getModelId()).toList());
        assertEquals(1, client.reads.stream().mapToInt(read -> read.result().getPayloadCount()).sum());
        assertTrue(client.reads.stream().mapToLong(read -> read.result().getBytes()).sum() > 0,
                   "The same stored event is fetched again");
    }

    record ReadValue(String key) {
        @HandleQuery
        Lookup handle() {
            Entity<Value> value = Fluxzero.loadModel(key, Value.class, false);
            return new Lookup(value.id().toString(), value.get());
        }
    }

    record Lookup(String id, Value value) {}

    @Model(searchable = false)
    record Value(@EntityId ValueId id, @Alias String alias, int revision) {}

    static final class ValueId extends Id<Value> {
        ValueId(String id) { super(id, "value-"); }
    }

    record PutValue(ValueId id, String alias, int revision) {
        @Apply Value apply(@Nullable Value before) { return new Value(id, alias, revision); }
    }

    record Read(GetModelEvents request, GetModelEventsResult.Metric result) {}

    /** Keep the observer on the same real store in both fixture execution modes. */
    private static final class ObservedFixture extends TestFixture {
        ObservedFixture(ObservedClient client, boolean synchronous) {
            super(DefaultFluxzero.builder(), ignored -> List.of(), client, synchronous);
        }
    }

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
