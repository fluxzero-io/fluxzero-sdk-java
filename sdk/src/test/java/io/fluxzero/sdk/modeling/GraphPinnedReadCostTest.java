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
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Characterizes current I/O, not a required cost for future implementations. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedPinnedReadsReplayUnchangedDependencies(boolean pinned) {
        var client = new ObservedClient();
        var fixture = TestFixture.createAsync(DefaultFluxzero.builder(), client)
                .givenCommands(VALUES.stream().map(v -> new PutValue(v.valueId(), v.revision())).toList())
                .given(fc -> fc.cache().clear());
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
            assertEquals(pinned ? 6 : 3, client.reads.size());
            assertEquals(Map.of("first", pinned ? 2L : 1L,
                               "second", pinned ? 2L : 1L, "third", pinned ? 2L : 1L),
                    client.reads.stream().flatMap(r -> r.request().getRequests().stream())
                            .collect(groupingBy(r -> r.getModelId(), counting())));
            assertEquals(pinned ? 3 : 0, client.reads.stream()
                    .mapToInt(r -> r.result().getPayloadCount()).sum());
            long bytes = client.reads.stream().mapToLong(r -> r.result().getBytes()).sum();
            if (pinned) {
                assertTrue(bytes > 0, "Unchanged historical values are reconstructed again");
            } else {
                assertEquals(0L, bytes);
            }
        }
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
        Value apply() { return new Value(valueId, revision); }
    }

    record Read(GetModelEvents request, GetModelEventsResult.Metric result) {}

    /** Observes real in-memory storage; model responses are never synthesized. */
    private static final class ObservedClient extends LocalClient {
        final List<Read> reads = new CopyOnWriteArrayList<>();

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
            return observed;
        }
    }
}
