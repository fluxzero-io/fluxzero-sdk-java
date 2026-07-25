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

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryEventStoreModelActionTest {

    @Test
    void publishesOneEventAndStoresSharedMembershipsForEveryTarget() {
        InMemoryEventStore store = new InMemoryEventStore();
        SerializedMessage event = event("event-1");
        CommitModelAction action = action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event)
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1"), storedTarget("inventory-1")))
                        .build());

        CommitModelActionResult result = store.commitModelAction(action).join();

        assertEquals(0L, result.getSubsteps().getFirst().getStateIndex());
        assertNotNull(result.getSubsteps().getFirst().getEventIndex());
        assertEquals(0L, result.getSubsteps().getFirst().getTargets().get(0).getSequenceNumber());
        assertEquals(0L, result.getSubsteps().getFirst().getTargets().get(1).getSequenceNumber());
        assertEquals(1, store.getBatch(null, 10, true).size());
        assertSame(event, store.getEvents("order-1").findFirst().orElseThrow());
        assertSame(event, store.getEvents("inventory-1").findFirst().orElseThrow());
    }

    @Test
    void duplicateActionReturnsDurableResultWithoutWritingAgain() {
        InMemoryEventStore store = new InMemoryEventStore();
        CommitModelAction first = action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("event-1"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build());
        CommitModelAction retry = action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("event-2"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build());

        CommitModelActionResult firstResult = store.commitModelAction(first).join();
        CommitModelActionResult retryResult = store.commitModelAction(retry).join();

        assertEquals(firstResult.getSubsteps(), retryResult.getSubsteps());
        assertEquals(retry.getRequestId(), retryResult.getRequestId());
        assertEquals(1, store.getBatch(null, 10, true).size());
        assertEquals(1, store.getEvents("order-1").count());
    }

    @Test
    void nonStoredStateUpdateMarksHistoryIncompleteWithoutAdvancingStream() {
        InMemoryEventStore store = new InMemoryEventStore();
        CommitModelAction action = action(
                "action-1",
                ModelActionSubstep.builder()
                        .targets(List.of(ModelActionTarget.builder()
                                                 .modelId("document-1")
                                                 .updateState(true)
                                                 .relationships(List.of())
                                                 .build()))
                        .build());

        CommitModelActionResult result = store.commitModelAction(action).join();

        var target = result.getSubsteps().getFirst().getTargets().getFirst();
        assertEquals(-1L, target.getSequenceNumber());
        assertFalse(target.isHistoryComplete());
        assertEquals(0, store.getEvents("document-1").count());
        assertEquals(0, store.getBatch(null, 10, true).size());
    }

    @Test
    void batchReadDeduplicatesSharedPayloadAcrossTargetStreams() {
        InMemoryEventStore store = new InMemoryEventStore();
        SerializedMessage event = event("event-1");
        store.commitModelAction(action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event)
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1"), storedTarget("inventory-1")))
                        .build())).join();

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 10),
                        new ModelEventStreamRequest("inventory-1", -1L, 10)),
                null));

        assertEquals(0L, result.getStateIndex());
        assertEquals(1, result.getPayloads().size());
        assertSame(event, result.getPayloads().getFirst().getEvent());
        assertEquals(2, result.getStreams().size());
        assertEquals(0L, result.getStreams().get(0).getHead().getSequenceNumber());
        assertEquals(0L, result.getStreams().get(1).getHead().getSequenceNumber());
        assertEquals(0L, result.getStreams().get(0).getMemberships().getFirst().getStateIndex());
        assertEquals(0L, result.getStreams().get(1).getMemberships().getFirst().getStateIndex());
    }

    @Test
    void headOnlyAndHistoricalReadsUseTheRequestedStateBoundary() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.commitModelAction(action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("event-1"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();
        store.commitModelAction(new CommitModelAction(
                "action-2", 0L, List.of("order-1"),
                List.of(ModelActionSubstep.builder()
                                .event(event("event-2"))
                                .targets(List.of(storedTarget("order-1")))
                                .build()),
                Guarantee.STORED)).join();

        var historical = store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 0)), 0L));
        var current = store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 0)), null));

        assertEquals(0L, historical.getStateIndex());
        assertEquals(0L, historical.getStreams().getFirst().getHead().getSequenceNumber());
        assertEquals(1L, current.getStateIndex());
        assertEquals(1L, current.getStreams().getFirst().getHead().getSequenceNumber());
        assertTrue(historical.getPayloads().isEmpty());
        assertTrue(current.getPayloads().isEmpty());
    }

    @Test
    void publicationFailureLeavesEveryModelStreamUntouched() {
        InMemoryEventStore store = new InMemoryEventStore() {
            @Override
            public CompletableFuture<Void> append(List<SerializedMessage> messages) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated"));
            }
        };
        CommitModelAction action = action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("event-1"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build(),
                ModelActionSubstep.builder()
                        .event(event("event-2"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("inventory-1")))
                        .build());

        assertThrows(CompletionException.class, () -> store.commitModelAction(action).join());

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 10),
                        new ModelEventStreamRequest("inventory-1", -1L, 10)),
                null));
        assertEquals(-1L, result.getStateIndex());
        assertTrue(result.getPayloads().isEmpty());
        assertTrue(result.getStreams().stream().allMatch(stream -> stream.getHead() == null));
        assertEquals(0, store.getBatch(null, 10, true).size());
    }

    @Test
    void validatesWholeActionBeforePublishingAnything() {
        InMemoryEventStore store = new InMemoryEventStore();
        CommitModelAction action = new CommitModelAction(
                "invalid", -1L, List.of("order-1"),
                List.of(
                        ModelActionSubstep.builder()
                                .event(event("event-1"))
                                .publishEvent(true)
                                .targets(List.of(storedTarget("order-1")))
                                .build(),
                        ModelActionSubstep.builder()
                                .event(event("event-2"))
                                .publishEvent(true)
                                .targets(List.of(storedTarget("missing-read-model")))
                                .build()),
                Guarantee.STORED);

        assertThrows(CompletionException.class, () -> store.commitModelAction(action).join());
        assertEquals(0, store.getBatch(null, 10, true).size());
        assertEquals(-1L, store.getModelEvents(
                new GetModelEvents(List.of(), null)).getStateIndex());
    }

    @Test
    void rejectsDuplicateAndNegativeBatchRequests() {
        InMemoryEventStore store = new InMemoryEventStore();

        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 1),
                        new ModelEventStreamRequest("order-1", -1L, 1)),
                null)));
        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, -1)),
                null)));
    }

    private static CommitModelAction action(String actionId, ModelActionSubstep... substeps) {
        List<String> readModelIds = List.of(substeps).stream()
                .flatMap(substep -> substep.getTargets().stream())
                .map(ModelActionTarget::getModelId)
                .distinct()
                .toList();
        return new CommitModelAction(actionId, -1L, readModelIds, List.of(substeps), Guarantee.STORED);
    }

    private static ModelActionTarget storedTarget(String modelId) {
        return ModelActionTarget.builder()
                .modelId(modelId)
                .storeEvent(true)
                .updateState(true)
                .relationships(List.of())
                .build();
    }

    private static SerializedMessage event(String messageId) {
        return new SerializedMessage(
                new Data<>(messageId.getBytes(), "event", 0),
                Metadata.empty(), messageId, 1L);
    }
}
