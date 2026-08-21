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
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.sdk.tracking.IndexUtils;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryEventStoreModelCommitTest {

    @Test
    void expectedSequenceCollisionRejectsASecondCreate() {
        InMemoryEventStore store = denseStore();
        ModelCommitTarget creation = storedTarget("model-1")
                .toBuilder()
                .expectedSequenceNumber(-1L)
                .build();
        store.commitModels(commit(
                "first-create",
                ModelCommitStep.builder()
                        .event(event("first"))
                        .targets(List.of(creation))
                        .build())).join();

        CommitModelsResult conflict = store.commitModels(commit(
                "second-create", 0L,
                ModelConflictPolicy.FAIL,
                ModelCommitStep.builder()
                        .event(event("second"))
                        .targets(List.of(creation))
                        .build())).join();

        assertFalse(conflict.isAccepted());
        assertEquals(List.of("model-1"),
                     conflict.getConflicts().stream()
                             .map(io.fluxzero.common.api.modeling.ModelCommitConflict::getModelId)
                             .toList());
        assertEquals(1, modelStream(store, "model-1")
                .getMemberships().size());
    }

    @Test
    void resolvesAndAtomicallyReplacesIndependentModelAliases() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "create-alias-owner",
                ModelCommitStep.builder()
                        .event(event("created"))
                        .targets(List.of(storedTarget("model-1")
                                .toBuilder()
                                .modelType("example.Model")
                                .aliases(List.of("old-code"))
                                .build()))
                        .build())).join();

        var initial = modelStream(store, "old-code");
        assertEquals("old-code", initial.getModelId());
        assertEquals("model-1", initial.getHead().getModelId());
        assertEquals("example.Model", initial.getHead().getModelType());
        assertEquals(1, initial.getMemberships().size());

        store.commitModels(commit(
                "legacy-update", 0L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("legacy"))
                        .targets(List.of(storedTarget("model-1")
                                .toBuilder()
                                .modelType("example.Model")
                                .aliases(null)
                                .build()))
                        .build())).join();
        assertEquals(2, modelStream(store, "old-code")
                .getMemberships().size());

        store.commitModels(commit(
                "replace-alias", 1L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("updated"))
                        .targets(List.of(storedTarget("model-1")
                                .toBuilder()
                                .modelType("example.Model")
                                .aliases(List.of("new-code"))
                                .build()))
                        .build())).join();

        assertEquals(null, modelStream(store, "old-code").getHead());
        var updated = modelStream(store, "new-code");
        assertEquals("model-1", updated.getHead().getModelId());
        assertEquals(3, updated.getMemberships().size());
    }

    @Test
    void aliasCollisionRejectsWholeCommitAndPrimaryIdWinsAliasLookup() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "alias-owner",
                ModelCommitStep.builder()
                        .event(event("owner"))
                        .targets(List.of(storedTarget("model-1")
                                .toBuilder()
                                .aliases(List.of("shared"))
                                .build()))
                        .build())).join();

        assertThrows(CompletionException.class, () ->
                store.commitModels(commit(
                        "alias-collision",
                        ModelCommitStep.builder()
                                .event(event("rejected"))
                                .targets(List.of(storedTarget("model-2")
                                        .toBuilder()
                                        .aliases(List.of("shared"))
                                        .build()))
                                .build())).join());
        assertEquals(null, modelStream(store, "model-2").getHead());

        store.commitModels(commit(
                "primary-owner",
                ModelCommitStep.builder()
                        .event(event("primary"))
                        .targets(List.of(storedTarget("shared")))
                        .build())).join();

        assertEquals("shared", modelStream(store, "shared")
                .getHead().getModelId());
    }

    @Test
    void logicalDeletionClearsIndependentModelAliases() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "create-deleted-alias",
                ModelCommitStep.builder()
                        .event(event("created"))
                        .targets(List.of(storedTarget("model-1")
                                .toBuilder()
                                .aliases(List.of("code"))
                                .build()))
                        .build())).join();
        store.commitModels(commit(
                "delete-alias-owner", 0L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("deleted"))
                        .targets(List.of(storedTarget("model-1")
                                .toBuilder()
                                .delete(true)
                                .aliases(null)
                                .updateRelationships(true)
                                .relationships(List.of())
                                .build()))
                        .build())).join();

        assertEquals(null, modelStream(store, "code").getHead());
        assertTrue(modelStream(store, "model-1").getHead().isDeleted());
    }

    @Test
    void publishedModelEventIsObservedAfterItsModelState() {
        InMemoryEventStore store = denseStore();
        AtomicBoolean modelVisible = new AtomicBoolean();
        store.registerMonitor(ignored -> modelVisible.set(
                store.getModelEvents(new GetModelEvents(
                                List.of(new ModelEventStreamRequest("visible-model", -1L, 10)),
                                ModelReadBoundary.current(), 1_024L))
                        .getStreams().getFirst().getMemberships().size() == 1));

        store.commitModels(commit(
                "visible-model-commit",
                ModelCommitStep.builder()
                        .event(event("visible"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("visible-model")))
                        .build())).join();

        assertTrue(modelVisible.get());
    }

    @Test
    void publishedModelEventMonitorCanWaitForTheCommittedModelUpdate() {
        InMemoryEventStore store = denseStore();
        AtomicBoolean modelVisible = new AtomicBoolean();
        store.registerMonitor(ignored -> modelVisible.set(
                !store.trackModelUpdates(new TrackModelUpdates(-1L, 10, 1_000L)).join().getUpdates().isEmpty()));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> store.commitModels(commit(
                "visible-model-update-commit",
                ModelCommitStep.builder()
                        .event(event("visible-update"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("visible-update-model")))
                        .build())).join());

        assertTrue(modelVisible.get());
    }

    @Test
    void emptyModelUpdateHeartbeatKeepsTheClientCursor() {
        InMemoryEventStore store =
                denseStore();

        var heartbeat =
                store.trackModelUpdates(
                                new TrackModelUpdates(
                                        -1L, 10, 1L))
                        .join();

        assertTrue(
                heartbeat.getUpdates()
                        .isEmpty());
        assertEquals(
                -1L,
                heartbeat.getLastStateIndex());
        assertEquals(
                -1L,
                heartbeat.getCurrentStateIndex());
    }

    @Test
    void emptyLongPollWakesAfterACommit()
            throws Exception {
        InMemoryEventStore store =
                denseStore();
        var waiting =
                store.trackModelUpdates(
                        new TrackModelUpdates(
                                -1L, 10,
                                5_000L));
        assertFalse(waiting.isDone());

        store.commitModels(
                        commit(
                                "wake-in-memory-tracker",
                                ModelCommitStep.builder()
                                        .event(
                                                event("wake"))
                                        .publishEvent(false)
                                        .targets(
                                                List.of(
                                                        storedTarget(
                                                                "wake-model")))
                                        .build()))
                .join();

        assertEquals(
                "wake-in-memory-tracker",
                waiting.get(
                                5L,
                                TimeUnit.SECONDS)
                        .getUpdates()
                        .getFirst()
                        .getCommitId());
    }

    @Test
    void longPollTracksStoreOnlyCommitsAndCompletedHardDeletes() {
        InMemoryEventStore store = denseStore();
        CommitModelsResult committed =
                store.commitModels(
                                commit(
                                        "tracked-commit",
                                        ModelCommitStep.builder()
                                                .event(
                                                        event(
                                                                "stored"))
                                                .publishEvent(
                                                        false)
                                                .targets(
                                                        List.of(
                                                                storedTarget(
                                                                        "tracked-1")))
                                                .build()))
                        .join();
        var commitPage =
                store.trackModelUpdates(
                                new TrackModelUpdates(
                                        -1L, 10, 0L))
                        .join();

        assertEquals(
                ModelUpdateKind.COMMIT,
                commitPage.getUpdates()
                        .getFirst().getKind());
        assertEquals(
                committed.getUpdates()
                        .getFirst().getStateIndex(),
                commitPage.getLastStateIndex());
        assertEquals(
                null,
                commitPage.getUpdates()
                        .getFirst().getEventIndex());
        assertEquals(
                1,
                store.trackModelUpdates(
                                new TrackModelUpdates(
                                        -1L, 10,
                                        0L, 1L))
                        .join()
                        .getUpdates()
                        .size());

        var deleted =
                store.deleteModel(
                                DeleteModel.builder()
                                        .deletionId(
                                                "tracked-deletion")
                                        .modelId(
                                                "tracked-1")
                                        .cascade(
                                                ModelDeletionCascade.NONE)
                                        .maxDepth(0)
                                        .maxModels(1)
                                        .build())
                        .join();
        var deletionPage =
                store.trackModelUpdates(
                                new TrackModelUpdates(
                                        commitPage
                                                .getLastStateIndex(),
                                        10, 0L))
                        .join();

        assertEquals(
                ModelUpdateKind.HARD_DELETE,
                deletionPage.getUpdates()
                        .getFirst().getKind());
        assertTrue(
                deletionPage.getUpdates()
                        .getFirst().getTargets()
                        .isEmpty());
        assertEquals(
                deleted.getStateIndex(),
                deletionPage
                        .getLastStateIndex());
    }

    @Test
    void allocatesTimeDerivedContiguousStateIndices() {
        long timeIndex = IndexUtils.indexFromTimestamp(
                Instant.parse("2026-07-26T12:34:56.789Z"));
        InMemoryEventStore store =
                new InMemoryEventStore(
                        Duration.ofMinutes(2),
                        () -> timeIndex);

        CommitModelsResult result =
                store.commitModels(commit(
                        "time-based",
                        ModelCommitStep.builder()
                                .event(event("first"))
                                .targets(List.of(
                                        storedTarget("a")))
                                .build(),
                        ModelCommitStep.builder()
                                .event(event("second"))
                                .targets(List.of(
                                        storedTarget("b")))
                                .build())).join();

        assertEquals(
                List.of(timeIndex, timeIndex + 1L),
                result.getUpdates().stream()
                        .map(substep ->
                                     substep.getStateIndex())
                        .toList());
        assertEquals(
                Instant.parse("2026-07-26T12:34:56.789Z"),
                IndexUtils.timestampFromIndex(
                        result.getUpdates()
                                .getFirst()
                                .getStateIndex()));
    }

    @Test
    void publishesOneEventAndStoresSharedMembershipsForEveryTarget() {
        InMemoryEventStore store = denseStore();
        SerializedMessage event = event("event-1");
        CommitModels commit = commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event)
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1"), storedTarget("inventory-1")))
                        .build());

        CommitModelsResult result = store.commitModels(commit).join();

        assertEquals(0L, result.getUpdates().getFirst().getStateIndex());
        assertNotNull(result.getUpdates().getFirst().getEventIndex());
        assertEquals(0L, result.getUpdates().getFirst().getTargets().get(0).getSequenceNumber());
        assertEquals(0L, result.getUpdates().getFirst().getTargets().get(1).getSequenceNumber());
        assertEquals(1, store.getBatch(null, 10, true).size());
        assertSame(event, store.getEvents("order-1").findFirst().orElseThrow());
        assertSame(event, store.getEvents("inventory-1").findFirst().orElseThrow());
    }

    @Test
    void duplicateCommitReturnsDurableResultWithoutWritingAgain() {
        InMemoryEventStore store = denseStore();
        CommitModels first = commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("event-1"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build());
        CommitModels retry = commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("event-2"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build());

        CommitModelsResult firstResult = store.commitModels(first).join();
        CommitModelsResult retryResult = store.commitModels(retry).join();

        assertEquals(firstResult.getUpdates(), retryResult.getUpdates());
        assertEquals(retry.getRequestId(), retryResult.getRequestId());
        assertTrue(retryResult.isDuplicate());
        assertEquals(1, store.getBatch(null, 10, true).size());
        assertEquals(1, store.getEvents("order-1").count());
    }

    @Test
    void duplicateCommitRetriesTheOriginalDirectMaterialization() {
        InMemoryEventStore store = denseStore();
        AtomicInteger attempts =
                new AtomicInteger();
        store.setModelCommitMaterializer(
                (commit, assigned, excluded) -> {
                    assertEquals(
                            "commit-1",
                            commit.getCommitId());
                    assertEquals(
                            0L,
                            assigned.getFirst()
                                    .getStateIndex());
                    assertTrue(excluded.isEmpty());
                    if (attempts.getAndIncrement()
                        == 0) {
                        throw new IllegalStateException(
                                "search unavailable");
                    }
                });
        CommitModels commit =
                commit(
                        "commit-1",
                        ModelCommitStep.builder()
                                .event(event("event-1"))
                                .publishEvent(true)
                                .targets(
                                        List.of(
                                                storedTarget(
                                                        "order-1")
                                                        .toBuilder()
                                                        .document(
                                                                new ModelDocumentMutation(
                                                                        "orders",
                                                                        null))
                                                        .build()))
                                .build());

        assertThrows(
                CompletionException.class,
                () -> store.commitModels(
                                commit)
                        .join());
        CommitModels retryCommit =
                commit(
                        "commit-1",
                        ModelCommitStep.builder()
                                .event(event("event-2"))
                                .publishEvent(true)
                                .targets(
                                        commit.getSubsteps()
                                                .getFirst()
                                                .getTargets())
                                .build());
        CommitModelsResult retry =
                store.commitModels(
                                retryCommit)
                        .join();

        assertEquals(2, attempts.get());
        assertTrue(retry.isDuplicate());
        assertEquals(
                1,
                store.getEvents("order-1")
                        .count());
    }

    @Test
    void nonStoredStateUpdateMarksHistoryIncompleteWithoutAdvancingStream() {
        InMemoryEventStore store = denseStore();
        CommitModels commit = commit(
                "commit-1",
                ModelCommitStep.builder()
                        .targets(List.of(ModelCommitTarget.builder()
                                                 .modelId("document-1")
                                                 .modelType("example.Document")
                                                 .updateState(true)
                                                 .relationships(List.of())
                                                 .build()))
                        .build());

        CommitModelsResult result = store.commitModels(commit).join();

        var target = result.getUpdates().getFirst().getTargets().getFirst();
        assertEquals(-1L, target.getSequenceNumber());
        assertFalse(target.isHistoryComplete());
        assertEquals(0, store.getEvents("document-1").count());
        assertEquals(0, store.getBatch(null, 10, true).size());
    }

    @Test
    void batchReadDeduplicatesSharedPayloadAcrossTargetStreams() {
        InMemoryEventStore store = denseStore();
        SerializedMessage event = event("event-1");
        store.commitModels(commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event)
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1"), storedTarget("inventory-1")))
                        .build())).join();

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 10),
                        new ModelEventStreamRequest("inventory-1", -1L, 10)),
                ModelReadBoundary.current(), 0L));

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
        InMemoryEventStore store = denseStore();
        SerializedMessage published = event("published");
        store.commitModels(commit(
                "published-commit",
                ModelCommitStep.builder()
                        .event(published)
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();
        store.commitModels(commit(
                "later-store-only",
                ModelCommitStep.builder()
                        .event(event("later"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();

        var result = store.getModelEvents(
                new GetModelEvents(
                        List.of(new ModelEventStreamRequest(
                                "order-1", -1L, 10)),
                        ModelReadBoundary.commit("published-commit", 0), 0L));

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
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("shared"))
                        .targets(List.of(storedTarget("order-1"), storedTarget("inventory-1")))
                        .build(),
                ModelCommitStep.builder()
                        .event(event("next"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 10),
                        new ModelEventStreamRequest("inventory-1", -1L, 10)),
                ModelReadBoundary.current(), 1L));

        assertEquals(1, result.getPayloads().size());
        assertEquals(0L, result.getPayloads().getFirst().getStateIndex());
        assertEquals(1, result.getStreams().getFirst().getMemberships().size());
        assertEquals(1, result.getStreams().getLast().getMemberships().size());
    }

    @Test
    void batchReadDoesNotSkipAnExcludedEarlierStateFromAnotherStream() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("a0"))
                        .targets(List.of(storedTarget("a")))
                        .build(),
                ModelCommitStep.builder()
                        .event(event("a1"))
                        .targets(List.of(storedTarget("a")))
                        .build(),
                ModelCommitStep.builder()
                        .event(event("b0"))
                        .targets(List.of(storedTarget("b")))
                        .build())).join();

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("a", -1L, 1),
                        new ModelEventStreamRequest("b", -1L, 1)),
                ModelReadBoundary.current(), 0L));

        assertEquals(List.of(0L), result.getPayloads().stream()
                .map(payload -> payload.getStateIndex()).toList());
        assertEquals(1, result.getStreams().getFirst().getMemberships().size());
        assertTrue(result.getStreams().getLast().getMemberships().isEmpty());
    }

    @Test
    void headOnlyAndHistoricalReadsUseTheRequestedStateBoundary() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("event-1"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();
        store.commitModels(new CommitModels(
                "commit-2", 0L, List.of("order-1"),
                List.of(ModelCommitStep.builder()
                                .event(event("event-2"))
                                .targets(List.of(storedTarget("order-1")))
                                .build()),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED, true)).join();

        var historical = store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 0)),
                ModelReadBoundary.state(0L, false), 0L));
        var current = store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 0)),
                ModelReadBoundary.current(), 0L));

        assertEquals(0L, historical.getStateIndex());
        assertEquals(0L, historical.getStreams().getFirst().getHead().getSequenceNumber());
        assertEquals(1L, current.getStateIndex());
        assertEquals(1L, current.getStreams().getFirst().getHead().getSequenceNumber());
        assertTrue(historical.getPayloads().isEmpty());
        assertTrue(current.getPayloads().isEmpty());
    }

    @Test
    void publicationFailureLeavesEveryModelStreamUntouched() {
        InMemoryEventStore store =
                new InMemoryEventStore(
                        Duration.ofMinutes(2),
                        () -> 0L) {
            @Override
            public CompletableFuture<Void> append(List<SerializedMessage> messages) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated"));
            }
        };
        CommitModels commit = commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("event-1"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1")))
                        .build(),
                ModelCommitStep.builder()
                        .event(event("event-2"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("inventory-1")))
                        .build());

        assertThrows(CompletionException.class, () -> store.commitModels(commit).join());

        var result = store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 10),
                        new ModelEventStreamRequest("inventory-1", -1L, 10)),
                ModelReadBoundary.current(), 0L));
        assertEquals(-1L, result.getStateIndex());
        assertTrue(result.getPayloads().isEmpty());
        assertTrue(result.getStreams().stream().allMatch(stream -> stream.getHead() == null));
        assertEquals(0, store.getBatch(null, 10, true).size());
    }

    @Test
    void acceptPolicyRequestsRebaseBeforeCommittingAgainstAStaleReadBoundary() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("event-1"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();

        CommitModels stale = commit(
                "commit-2", -1L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("event-2"))
                        .targets(List.of(storedTarget("order-1")))
                        .build());

        CommitModelsResult rebase = store.commitModels(stale).join();

        assertTrue(rebase.isRebaseRequired());
        assertEquals(0L, rebase.getRebaseStateIndex());
        assertEquals(1, store.getEvents("order-1").count());

        CommitModelsResult result = store.commitModels(commit(
                stale.getCommitId(), rebase.getRebaseStateIndex(),
                ModelConflictPolicy.ACCEPT, stale.getSubsteps().toArray(ModelCommitStep[]::new))).join();

        assertTrue(result.isAccepted());
        assertEquals(1L, result.getUpdates().getFirst().getStateIndex());
        assertEquals(2, store.getEvents("order-1").count());
    }

    @Test
    void failPolicyRejectsBeforePublicationAndDoesNotRetainTheCommitId() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "commit-1",
                ModelCommitStep.builder()
                        .event(event("event-1"))
                        .targets(List.of(storedTarget("order-1")))
                        .build())).join();
        ModelCommitStep staleSubstep = ModelCommitStep.builder()
                .event(event("event-2"))
                .publishEvent(true)
                .targets(List.of(storedTarget("order-1")))
                .build();

        CommitModelsResult rejected = store.commitModels(commit(
                "retryable-commit", -1L, ModelConflictPolicy.FAIL, staleSubstep)).join();

        assertFalse(rejected.isAccepted());
        assertFalse(rejected.isRetryAllowed());
        assertEquals("order-1", rejected.getConflicts().getFirst().getModelId());
        assertEquals(0L, rejected.getConflicts().getFirst().getCurrentStateIndex());
        assertEquals(-1L, rejected.getConflicts().getFirst().getCurrentRelationStateIndex());
        assertEquals(0, store.getBatch(null, 10, true).size());
        assertEquals(0L, store.getModelEvents(
                new GetModelEvents(
                        List.of(), ModelReadBoundary.current(), 0L)).getStateIndex());

        CommitModelsResult rebase = store.commitModels(commit(
                "retryable-commit", -1L, ModelConflictPolicy.ACCEPT, staleSubstep)).join();
        assertTrue(rebase.isRebaseRequired());

        CommitModelsResult accepted = store.commitModels(commit(
                "retryable-commit", rebase.getRebaseStateIndex(),
                ModelConflictPolicy.ACCEPT, staleSubstep)).join();
        assertTrue(accepted.isAccepted());
    }

    @Test
    void relationAwarePolicyAllowsRetryWhenOnlyModelStateChanged() {
        InMemoryEventStore store = denseStore();
        ModelCommitTarget target = storedTarget("order-1").toBuilder()
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        store.commitModels(commit(
                "commit-1", -1L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-1"))
                        .targets(List.of(target)).build())).join();
        store.commitModels(commit(
                "commit-2", 0L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-2"))
                        .targets(List.of(target)).build())).join();

        CommitModelsResult result = store.commitModels(commit(
                "commit-3", 0L, ModelConflictPolicy.RETRY,
                ModelCommitStep.builder().event(event("event-3"))
                        .targets(List.of(target)).build())).join();

        assertFalse(result.isAccepted());
        assertTrue(result.isRetryAllowed());
        assertEquals(1L, result.getConflicts().getFirst().getCurrentStateIndex());
        assertEquals(0L, result.getConflicts().getFirst().getCurrentRelationStateIndex());
    }

    @Test
    void retryPolicyAllowsFreshEvaluationWhenARelevantRelationshipChanged() {
        InMemoryEventStore store = denseStore();
        ModelCommitTarget initial = storedTarget("order-1").toBuilder()
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        ModelCommitTarget moved = initial.toBuilder()
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-2")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        store.commitModels(commit(
                "commit-1", -1L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-1"))
                        .targets(List.of(initial)).build())).join();
        store.commitModels(commit(
                "commit-2", 0L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-2"))
                        .targets(List.of(moved)).build())).join();

        CommitModelsResult result = store.commitModels(commit(
                "commit-3", 0L, ModelConflictPolicy.RETRY,
                ModelCommitStep.builder().event(event("event-3"))
                        .targets(List.of(initial)).build())).join();

        assertFalse(result.isAccepted());
        assertTrue(result.isRetryAllowed());
        assertEquals(1L, result.getConflicts().getFirst().getCurrentRelationStateIndex());
    }

    @Test
    void staleUnchangedRelationshipDoesNotOverwriteTheCurrentEdge() {
        InMemoryEventStore store = denseStore();
        ModelCommitTarget attached = storedTarget("order-1").toBuilder()
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        ModelCommitTarget moved = attached.toBuilder()
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-2")
                                               .parentType("Customer")
                                               .path("orders")
                                               .build()))
                .build();
        store.commitModels(commit(
                "attach", -1L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-1"))
                        .targets(List.of(attached)).build())).join();
        store.commitModels(commit(
                "move", 0L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-2"))
                        .targets(List.of(moved)).build())).join();
        CommitModelsResult rebase = store.commitModels(commit(
                "stale-rename", 0L, ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-3"))
                        .targets(List.of(attached)).build())).join();
        assertTrue(rebase.isRebaseRequired());
        store.commitModels(commit(
                "stale-rename", rebase.getRebaseStateIndex(), ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder().event(event("event-3"))
                        .targets(List.of(moved)).build())).join();

        CommitModelsResult result = store.commitModels(commit(
                "probe", 1L, ModelConflictPolicy.RETRY,
                ModelCommitStep.builder().event(event("event-4"))
                        .targets(List.of(moved)).build())).join();

        assertFalse(result.isAccepted());
        assertTrue(result.isRetryAllowed());
        assertEquals(1L, result.getConflicts().getFirst().getCurrentRelationStateIndex());
    }

    @Test
    void parentDeleteDetachesAChildAndAnOrdinaryChildWriteDoesNotReattachIt() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "create-parent", -1L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("parent"))
                        .targets(List.of(
                                storedTarget("parent-1")))
                        .build())).join();
        ModelCommitTarget attached =
                storedTarget("child-1").toBuilder()
                        .updateRelationships(true)
                        .relationships(List.of(
                                ModelRelationship.builder()
                                        .parentId("parent-1")
                                        .parentType("Parent")
                                        .path("children")
                                        .build()))
                        .build();
        store.commitModels(commit(
                "create-child", 0L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("child"))
                        .targets(List.of(attached))
                        .build())).join();

        ModelCommitTarget deletedParent =
                storedTarget("parent-1").toBuilder()
                        .delete(true)
                        .updateRelationships(true)
                        .relationships(List.of())
                        .build();
        store.commitModels(commit(
                "delete-parent", 1L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("delete"))
                        .targets(List.of(deletedParent))
                        .build())).join();

        assertEquals(
                1,
                store.getModelGraph(new GetModelGraph(
                                "parent-1", ModelReadBoundary.state(1L, false),
                                1, 10, 0, 0L, false))
                        .getEdges().size());
        assertEquals(
                1,
                store.getModelGraph(new GetModelGraph(
                                "parent-1", ModelReadBoundary.state(1L, false),
                                -1, -1, 0, 0L, false))
                        .getEdges().size());
        assertTrue(
                store.getModelGraph(new GetModelGraph(
                                "parent-1", ModelReadBoundary.state(2L, false),
                                1, 10, 0, 0L, false))
                        .getEdges().isEmpty());
        assertEquals(
                1,
                store.getModelGraph(new GetModelGraph(
                                "parent-1", ModelReadBoundary.state(2L, false).asBefore(),
                                1, 10, 0, 0L, false))
                        .getEdges().size());
        assertEquals(
                "parent-1",
                store.getModelAncestors(new GetModelAncestors(
                                List.of("child-1"), ModelReadBoundary.state(1L, false),
                                1, 10, 0, 0L))
                        .getEdges().getFirst().getParentId());

        ModelCommitTarget ordinaryChildUpdate =
                storedTarget("child-1");
        store.commitModels(commit(
                "rename-child", 2L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("rename"))
                        .targets(List.of(
                                ordinaryChildUpdate))
                        .build())).join();

        assertTrue(
                store.getModelGraph(new GetModelGraph(
                                "parent-1", ModelReadBoundary.state(3L, false),
                                1, 10, 0, 0L, false))
                        .getEdges().isEmpty());
    }

    @Test
    void deletionPlanIncludesDetachedAndExternallySharedDescendantsWithoutMutating() {
        InMemoryEventStore store =
                denseStore();
        store.commitModels(commit(
                "create-parent", -1L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("parent"))
                        .publishEvent(true)
                        .targets(List.of(
                                storedTarget("parent-1")))
                        .build())).join();
        ModelCommitTarget child =
                storedTarget("child-1")
                        .toBuilder()
                        .updateRelationships(true)
                        .relationships(List.of(
                                ModelRelationship.builder()
                                        .parentId("parent-1")
                                        .path("children")
                                        .build(),
                                ModelRelationship.builder()
                                        .parentId("other-parent")
                                        .path("children")
                                        .build()))
                        .build();
        store.commitModels(commit(
                "create-child", 0L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("child"))
                        .publishEvent(true)
                        .targets(List.of(child))
                        .build())).join();
        store.commitModels(commit(
                "delete-parent", 1L,
                ModelConflictPolicy.ACCEPT,
                ModelCommitStep.builder()
                        .event(event("deleted"))
                        .targets(List.of(
                                storedTarget("parent-1")
                                        .toBuilder()
                                        .delete(true)
                                        .updateRelationships(true)
                                        .relationships(List.of())
                                        .build()))
                        .build())).join();

        var plan = store.planModelDeletion(
                new PlanModelDeletion(
                        "parent-1",
                        ModelDeletionCascade.DESCENDANTS,
                        10, 100, 10));

        assertEquals(
                2, plan.getModelCount());
        assertEquals(
                1,
                plan.getExternallySharedModelCount());
        assertEquals(
                3L,
                plan.getStoredEventMembershipCount());
        assertEquals(
                2L,
                plan.getPublishedEventCount());
        assertEquals(
                List.of("child-1", "parent-1"),
                plan.getSampleModelIds());
        assertEquals(
                1,
                store.getEvents("child-1").count());
        assertEquals(
                2,
                store.getEvents("parent-1").count());

        var deleted = store.deleteModel(
                DeleteModel.builder()
                        .deletionId(
                                "erase-parent")
                        .modelId("parent-1")
                        .cascade(
                                ModelDeletionCascade.NONE)
                        .maxDepth(0)
                        .maxModels(1)
                        .build()).join();

        assertEquals(
                1,
                deleted.getDeletedModelCount());
        assertEquals(
                2L,
                deleted
                        .getDeletedEventMembershipCount());
        assertEquals(
                1L,
                deleted
                        .getRetainedPublishedEventCount());
        assertTrue(
                store.deleteModel(
                                DeleteModel.builder()
                                        .deletionId(
                                                "erase-parent")
                                        .modelId(
                                                "parent-1")
                                        .cascade(
                                                ModelDeletionCascade.NONE)
                                        .maxDepth(0)
                                        .maxModels(1)
                                        .build())
                        .join()
                        .isDuplicate());

        var followupPlan =
                store.planModelDeletion(
                        new PlanModelDeletion(
                                "parent-1",
                                ModelDeletionCascade.DESCENDANTS,
                                10, 100, 10));
        assertEquals(
                2,
                followupPlan.getModelCount());
        assertEquals(
                List.of(
                        "child-1", "parent-1"),
                followupPlan.getSampleModelIds());
        assertEquals(
                2,
                store.getBatch(
                                null, 10, true)
                        .size());

        assertThrows(
                CompletionException.class,
                () -> store.commitModels(
                                commit(
                                        "recreate-parent",
                                        ModelCommitStep
                                                .builder()
                                                .event(
                                                        event(
                                                                "again"))
                                                .targets(
                                                        List.of(
                                                                storedTarget(
                                                                        "parent-1")))
                                                .build()))
                        .join());
    }

    @Test
    void descendantDeletionRequiresTheCurrentPlanFingerprint() {
        InMemoryEventStore store =
                denseStore();
        ModelCommitTarget child =
                storedTarget("child")
                        .toBuilder()
                        .updateRelationships(true)
                        .relationships(List.of(
                                ModelRelationship.builder()
                                        .parentId("parent")
                                        .build()))
                        .build();
        store.commitModels(commit(
                "create-child",
                ModelCommitStep.builder()
                        .event(event("child"))
                        .targets(List.of(child))
                        .build())).join();
        var plan =
                store.planModelDeletion(
                        new PlanModelDeletion(
                                "parent",
                                ModelDeletionCascade.DESCENDANTS,
                                10, 100, 10));

        assertThrows(
                CompletionException.class,
                () -> store.deleteModel(
                                DeleteModel.builder()
                                        .deletionId(
                                                "stale")
                                        .modelId("parent")
                                        .cascade(
                                                ModelDeletionCascade.DESCENDANTS)
                                        .planFingerprint(
                                                "not-the-plan")
                                        .maxDepth(10)
                                        .maxModels(100)
                                        .build())
                        .join());

        var result = store.deleteModel(
                DeleteModel.builder()
                        .deletionId("confirmed")
                        .modelId("parent")
                        .cascade(
                                ModelDeletionCascade.DESCENDANTS)
                        .planFingerprint(
                                plan.getFingerprint())
                        .maxDepth(
                                plan.getMaxDepth())
                        .maxModels(
                                plan.getMaxModels())
                        .build()).join();

        assertEquals(
                2,
                result.getDeletedModelCount());
        var streams = store.getModelEvents(
                new GetModelEvents(
                        List.of(
                                new ModelEventStreamRequest(
                                        "parent", -1L, 10),
                                new ModelEventStreamRequest(
                                        "child", -1L, 10)),
                        ModelReadBoundary.current(), 0L));
        assertTrue(
                streams.getStreams()
                        .stream()
                        .allMatch(stream ->
                                          stream.getHead()
                                          == null));
    }

    @Test
    void deletionPlanFailsInsteadOfReturningATruncatedClosure() {
        InMemoryEventStore store =
                denseStore();
        ModelCommitTarget child =
                storedTarget("child")
                        .toBuilder()
                        .updateRelationships(true)
                        .relationships(List.of(
                                ModelRelationship.builder()
                                        .parentId("parent")
                                        .build()))
                        .build();
        store.commitModels(commit(
                "create-child",
                ModelCommitStep.builder()
                        .event(event("child"))
                        .targets(List.of(child))
                        .build())).join();

        assertThrows(
                IllegalArgumentException.class,
                () -> store.planModelDeletion(
                        new PlanModelDeletion(
                                "parent",
                                ModelDeletionCascade.DESCENDANTS,
                                0, 100, 10)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.planModelDeletion(
                        new PlanModelDeletion(
                                "parent",
                                ModelDeletionCascade.DESCENDANTS,
                                10, 1, 10)));
    }

    @Test
    void validatesWholeCommitBeforePublishingAnything() {
        InMemoryEventStore store = denseStore();
        CommitModels commit = new CommitModels(
                "invalid", -1L, List.of("order-1"),
                List.of(
                        ModelCommitStep.builder()
                                .event(event("event-1"))
                                .publishEvent(true)
                                .targets(List.of(storedTarget("order-1")))
                                .build(),
                        ModelCommitStep.builder()
                                .event(event("event-2"))
                                .publishEvent(true)
                                .targets(List.of(storedTarget("missing-read-model")))
                                .build()),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED, true);

        assertThrows(CompletionException.class, () -> store.commitModels(commit).join());
        assertEquals(0, store.getBatch(null, 10, true).size());
        assertEquals(-1L, store.getModelEvents(
                new GetModelEvents(
                        List.of(), ModelReadBoundary.current(), 0L)).getStateIndex());
    }

    @Test
    void rejectsModelTypeChangesBeforePublishingAnything() {
        InMemoryEventStore store = denseStore();
        store.commitModels(commit(
                "create-typed",
                ModelCommitStep.builder()
                        .event(event("created"))
                        .publishEvent(true)
                        .targets(List.of(storedTarget("order-1").toBuilder()
                                                 .modelType("old").build()))
                        .build())).join();

        assertThrows(
                CompletionException.class,
                () -> store.commitModels(commit(
                        "change-type", 0L, ModelConflictPolicy.ACCEPT,
                        ModelCommitStep.builder()
                                .event(event("rejected"))
                                .publishEvent(true)
                                .targets(List.of(storedTarget("order-1").toBuilder()
                                                         .modelType("new").build()))
                                .build())).join());

        assertEquals(1, store.getBatch(null, 10, true).size());
        assertEquals("old", modelStream(store, "order-1").getHead().getModelType());
        assertEquals(0L, store.getModelEvents(
                new GetModelEvents(
                        List.of(), ModelReadBoundary.current(), 0L)).getStateIndex());
    }

    @Test
    void rejectsDynamicRelationshipCyclesBeforePublishingOrMutating() {
        InMemoryEventStore store = denseStore();
        ModelCommitTarget child = storedTarget("b")
                .toBuilder()
                .updateRelationships(true)
                .relationships(List.of(
                        ModelRelationship.builder()
                                .parentId("a")
                                .build()))
                .build();
        store.commitModels(commit(
                "create-b",
                ModelCommitStep.builder()
                        .event(event("event-b"))
                        .publishEvent(true)
                        .targets(List.of(child))
                        .build())).join();
        ModelCommitTarget cyclic = storedTarget("a")
                .toBuilder()
                .updateRelationships(true)
                .relationships(List.of(
                        ModelRelationship.builder()
                                .parentId("b")
                                .build()))
                .build();

        assertThrows(
                CompletionException.class,
                () -> store.commitModels(
                                commit(
                                        "cycle", 0L,
                                        ModelConflictPolicy.ACCEPT,
                                        ModelCommitStep.builder()
                                                .event(event("event-a"))
                                                .publishEvent(true)
                                                .targets(List.of(cyclic))
                                                .build()))
                        .join());

        assertEquals(1, store.getBatch(null, 10, true).size());
        assertEquals(0, store.getEvents("a").count());
        assertEquals(0L, store.getModelEvents(
                new GetModelEvents(
                        List.of(), ModelReadBoundary.current(), 0L)).getStateIndex());
    }

    @Test
    void rejectsDuplicateAndNegativeBatchRequests() {
        InMemoryEventStore store = denseStore();

        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 1),
                        new ModelEventStreamRequest("order-1", -1L, 1)),
                ModelReadBoundary.current(), 0L)));
        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, -1)),
                ModelReadBoundary.current(), 0L)));
        assertThrows(IllegalArgumentException.class, () -> store.getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("order-1", -1L, 1)),
                ModelReadBoundary.current(), -1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.getModelEvents(
                        new GetModelEvents(
                                List.of(),
                                new ModelReadBoundary(
                                        null, "commit", null, null,
                                        false, false),
                                0L)));
    }

    private static CommitModels commit(String commitId, ModelCommitStep... substeps) {
        return commit(commitId, -1L, ModelConflictPolicy.ACCEPT, substeps);
    }

    private static InMemoryEventStore denseStore() {
        return new InMemoryEventStore(
                Duration.ofMinutes(2), () -> 0L);
    }

    private static io.fluxzero.common.api.modeling.ModelEventStream
            modelStream(InMemoryEventStore store, String modelId) {
        return store.getModelEvents(new GetModelEvents(
                        List.of(new ModelEventStreamRequest(
                                modelId, -1L, 10)),
                        ModelReadBoundary.current(), 1_024L))
                .getStreams().getFirst();
    }

    private static CommitModels commit(
            String commitId,
            long readStateIndex,
            ModelConflictPolicy conflictPolicy,
            ModelCommitStep... substeps) {
        List<String> readModelIds = List.of(substeps).stream()
                .flatMap(substep -> substep.getTargets().stream())
                .map(ModelCommitTarget::getModelId)
                .distinct()
                .toList();
        return new CommitModels(
                commitId, readStateIndex, readModelIds, List.of(substeps),
                conflictPolicy, Guarantee.STORED, true);
    }

    private static ModelCommitTarget storedTarget(String modelId) {
        return ModelCommitTarget.builder()
                .modelId(modelId)
                .modelType("example.Model")
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
