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
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelRelationship;
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
        assertTrue(retryResult.isDuplicate());
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
                null, 0L));

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
    void batchReadResolvesPublishedEventBoundaryInsideTheLoadRequest() {
        InMemoryEventStore store = new InMemoryEventStore();
        SerializedMessage published = event("published");
        store.commitModelAction(action(
                "published-action",
                ModelActionSubstep.builder()
                        .event(published)
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();
        store.commitModelAction(action(
                "later-store-only",
                ModelActionSubstep.builder()
                        .event(event("later"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();

        var result = store.getModelEvents(
                new GetModelEvents(
                        List.of(new ModelEventStreamRequest(
                                "order-1", -1L, 10)),
                        null, "published-action", 0, 0L));

        assertEquals(0L, result.getStateIndex());
        assertEquals(
                0L,
                result.getStreams().getFirst()
                        .getHead().getSequenceNumber());
        assertEquals(
                1,
                result.getStreams().getFirst()
                        .getMemberships().size());
    }

    @Test
    void batchReadBoundsUniquePayloadBytesAndAlwaysReturnsTheOldestPayload() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.commitModelAction(action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("shared"))
                        .targets(List.of(storedTarget("order-1"), storedTarget("inventory-1")))
                        .build(),
                ModelActionSubstep.builder()
                        .event(event("next"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 10),
                        new ModelEventStreamRequest("inventory-1", -1L, 10)),
                null, 1L));

        assertEquals(1, result.getPayloads().size());
        assertEquals(0L, result.getPayloads().getFirst().getStateIndex());
        assertEquals(1, result.getStreams().getFirst().getMemberships().size());
        assertEquals(1, result.getStreams().getLast().getMemberships().size());
    }

    @Test
    void batchReadDoesNotSkipAnExcludedEarlierStateFromAnotherStream() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.commitModelAction(action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("a0"))
                        .targets(List.of(storedTarget("a")))
                        .build(),
                ModelActionSubstep.builder()
                        .event(event("a1"))
                        .targets(List.of(storedTarget("a")))
                        .build(),
                ModelActionSubstep.builder()
                        .event(event("b0"))
                        .targets(List.of(storedTarget("b")))
                        .build())).join();

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("a", -1L, 1),
                        new ModelEventStreamRequest("b", -1L, 1)),
                null, 0L));

        assertEquals(List.of(0L), result.getPayloads().stream()
                .map(payload -> payload.getStateIndex()).toList());
        assertEquals(1, result.getStreams().getFirst().getMemberships().size());
        assertTrue(result.getStreams().getLast().getMemberships().isEmpty());
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
                ModelConflictPolicy.ACCEPT, Guarantee.STORED)).join();

        var historical = store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 0)), 0L, 0L));
        var current = store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 0)), null, 0L));

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
                null, 0L));
        assertEquals(-1L, result.getStateIndex());
        assertTrue(result.getPayloads().isEmpty());
        assertTrue(result.getStreams().stream().allMatch(stream -> stream.getHead() == null));
        assertEquals(0, store.getBatch(null, 10, true).size());
    }

    @Test
    void acceptPolicyRequestsRebaseBeforeCommittingAgainstAStaleReadBoundary() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.commitModelAction(action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("event-1"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();

        CommitModelAction stale = action(
                "action-2", -1L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder()
                        .event(event("event-2"))
                        .targets(List.of(storedTarget("order-1")))
                        .build());

        CommitModelActionResult rebase = store.commitModelAction(stale).join();

        assertTrue(rebase.isRebaseRequired());
        assertEquals(0L, rebase.getRebaseStateIndex());
        assertEquals(1, store.getEvents("order-1").count());

        CommitModelActionResult result = store.commitModelAction(action(
                stale.getActionId(), rebase.getRebaseStateIndex(),
                ModelConflictPolicy.ACCEPT, stale.getSubsteps().toArray(ModelActionSubstep[]::new))).join();

        assertTrue(result.isAccepted());
        assertEquals(1L, result.getSubsteps().getFirst().getStateIndex());
        assertEquals(2, store.getEvents("order-1").count());
    }

    @Test
    void failPolicyRejectsBeforePublicationAndDoesNotRetainTheActionId() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.commitModelAction(action(
                "action-1",
                ModelActionSubstep.builder()
                        .event(event("event-1"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();
        ModelActionSubstep staleSubstep = ModelActionSubstep.builder()
                .event(event("event-2"))
                .publishEvent(true)
                .targets(List.of(storedTarget("order-1")))
                .build();

        CommitModelActionResult rejected = store.commitModelAction(action(
                "retryable-action", -1L, ModelConflictPolicy.FAIL, staleSubstep)).join();

        assertFalse(rejected.isAccepted());
        assertFalse(rejected.isRetryAllowed());
        assertEquals("order-1", rejected.getConflicts().getFirst().getModelId());
        assertEquals(0L, rejected.getConflicts().getFirst().getCurrentStateIndex());
        assertEquals(-1L, rejected.getConflicts().getFirst().getCurrentRelationStateIndex());
        assertEquals(0, store.getBatch(null, 10, true).size());
        assertEquals(0L, store.getModelEvents(
                new GetModelEvents(List.of(), null, 0L)).getStateIndex());

        CommitModelActionResult rebase = store.commitModelAction(action(
                "retryable-action", -1L, ModelConflictPolicy.ACCEPT, staleSubstep)).join();
        assertTrue(rebase.isRebaseRequired());

        CommitModelActionResult accepted = store.commitModelAction(action(
                "retryable-action", rebase.getRebaseStateIndex(),
                ModelConflictPolicy.ACCEPT, staleSubstep)).join();
        assertTrue(accepted.isAccepted());
    }

    @Test
    void relationAwarePolicyAllowsRetryWhenOnlyModelStateChanged() {
        InMemoryEventStore store = new InMemoryEventStore();
        ModelActionTarget target = storedTarget("order-1").toBuilder()
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        store.commitModelAction(action(
                "action-1", -1L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-1"))
                        .targets(List.of(target)).build())).join();
        store.commitModelAction(action(
                "action-2", 0L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-2"))
                        .targets(List.of(target)).build())).join();

        CommitModelActionResult result = store.commitModelAction(action(
                "action-3", 0L, ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED,
                ModelActionSubstep.builder().event(event("event-3"))
                        .targets(List.of(target)).build())).join();

        assertFalse(result.isAccepted());
        assertTrue(result.isRetryAllowed());
        assertEquals(1L, result.getConflicts().getFirst().getCurrentStateIndex());
        assertEquals(0L, result.getConflicts().getFirst().getCurrentRelationStateIndex());
    }

    @Test
    void relationAwarePolicyForbidsRetryWhenARelevantRelationshipChanged() {
        InMemoryEventStore store = new InMemoryEventStore();
        ModelActionTarget initial = storedTarget("order-1").toBuilder()
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        ModelActionTarget moved = initial.toBuilder()
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-2")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        store.commitModelAction(action(
                "action-1", -1L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-1"))
                        .targets(List.of(initial)).build())).join();
        store.commitModelAction(action(
                "action-2", 0L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-2"))
                        .targets(List.of(moved)).build())).join();

        CommitModelActionResult result = store.commitModelAction(action(
                "action-3", 0L, ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED,
                ModelActionSubstep.builder().event(event("event-3"))
                        .targets(List.of(initial)).build())).join();

        assertFalse(result.isAccepted());
        assertFalse(result.isRetryAllowed());
        assertEquals(1L, result.getConflicts().getFirst().getCurrentRelationStateIndex());
    }

    @Test
    void staleUnchangedRelationshipDoesNotOverwriteTheCurrentEdge() {
        InMemoryEventStore store = new InMemoryEventStore();
        ModelActionTarget attached = storedTarget("order-1").toBuilder()
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        ModelActionTarget moved = attached.toBuilder()
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-2")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        store.commitModelAction(action(
                "attach", -1L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-1"))
                        .targets(List.of(attached)).build())).join();
        store.commitModelAction(action(
                "move", 0L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-2"))
                        .targets(List.of(moved)).build())).join();
        CommitModelActionResult rebase = store.commitModelAction(action(
                "stale-rename", 0L, ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-3"))
                        .targets(List.of(attached)).build())).join();
        assertTrue(rebase.isRebaseRequired());
        store.commitModelAction(action(
                "stale-rename", rebase.getRebaseStateIndex(), ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder().event(event("event-3"))
                        .targets(List.of(moved)).build())).join();

        CommitModelActionResult result = store.commitModelAction(action(
                "probe", 1L, ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED,
                ModelActionSubstep.builder().event(event("event-4"))
                        .targets(List.of(moved)).build())).join();

        assertFalse(result.isAccepted());
        assertTrue(result.isRetryAllowed());
        assertEquals(1L, result.getConflicts().getFirst().getCurrentRelationStateIndex());
    }

    @Test
    void parentDeleteDetachesAChildAndAnOrdinaryChildWriteDoesNotReattachIt() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.commitModelAction(action(
                "create-parent", -1L,
                ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder()
                        .event(event("parent"))
                        .targets(List.of(
                                storedTarget("parent-1")))
                        .build())).join();
        ModelActionTarget attached =
                storedTarget("child-1").toBuilder()
                        .updateRelationships(true)
                        .relationships(List.of(
                                ModelRelationship.builder()
                                        .parentId("parent-1")
                                        .parentType("Parent")
                                        .path("children")
                                        .build()))
                        .build();
        store.commitModelAction(action(
                "create-child", 0L,
                ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder()
                        .event(event("child"))
                        .targets(List.of(attached))
                        .build())).join();

        ModelActionTarget deletedParent =
                storedTarget("parent-1").toBuilder()
                        .delete(true)
                        .updateRelationships(true)
                        .relationships(List.of())
                        .build();
        store.commitModelAction(action(
                "delete-parent", 1L,
                ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder()
                        .event(event("delete"))
                        .targets(List.of(deletedParent))
                        .build())).join();

        assertEquals(
                1,
                store.getModelGraph(new GetModelGraph(
                                "parent-1", 1L,
                                1, 10, 0, 0L, false))
                        .getEdges().size());
        assertTrue(
                store.getModelGraph(new GetModelGraph(
                                "parent-1", 2L,
                                1, 10, 0, 0L, false))
                        .getEdges().isEmpty());

        ModelActionTarget ordinaryChildUpdate =
                storedTarget("child-1");
        store.commitModelAction(action(
                "rename-child", 2L,
                ModelConflictPolicy.ACCEPT,
                ModelActionSubstep.builder()
                        .event(event("rename"))
                        .targets(List.of(
                                ordinaryChildUpdate))
                        .build())).join();

        assertTrue(
                store.getModelGraph(new GetModelGraph(
                                "parent-1", 3L,
                                1, 10, 0, 0L, false))
                        .getEdges().isEmpty());
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
                ModelConflictPolicy.ACCEPT, Guarantee.STORED);

        assertThrows(CompletionException.class, () -> store.commitModelAction(action).join());
        assertEquals(0, store.getBatch(null, 10, true).size());
        assertEquals(-1L, store.getModelEvents(
                new GetModelEvents(List.of(), null, 0L)).getStateIndex());
    }

    @Test
    void rejectsDuplicateAndNegativeBatchRequests() {
        InMemoryEventStore store = new InMemoryEventStore();

        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 1),
                        new ModelEventStreamRequest("order-1", -1L, 1)),
                null, 0L)));
        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, -1)),
                null, 0L)));
        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 1)),
                null, -1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.getModelEvents(
                        new GetModelEvents(
                                List.of(), 0L,
                                "action", 0, 0L)));
    }

    private static CommitModelAction action(String actionId, ModelActionSubstep... substeps) {
        return action(actionId, -1L, ModelConflictPolicy.ACCEPT, substeps);
    }

    private static CommitModelAction action(
            String actionId,
            long readStateIndex,
            ModelConflictPolicy conflictPolicy,
            ModelActionSubstep... substeps) {
        List<String> readModelIds = List.of(substeps).stream()
                .flatMap(substep -> substep.getTargets().stream())
                .map(ModelActionTarget::getModelId)
                .distinct()
                .toList();
        return new CommitModelAction(
                actionId, readStateIndex, readModelIds, List.of(substeps),
                conflictPolicy, Guarantee.STORED);
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
