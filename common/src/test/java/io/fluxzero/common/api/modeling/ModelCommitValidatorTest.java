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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.Guarantee;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCommitValidatorTest {

    @Test
    void migrationDerivedStepsRequireMatchingPrecedingSourceAndPrivateDurablePayload() {
        var original = existingEventCommit("event-1", "event-1", 42L);
        var internal = new SerializedMessage(new Data<>(new byte[]{2}, "internal", 0),
                Metadata.of(ModelEventMetadata.MIGRATION_SOURCE_EVENT_INDEX, "42"), "cascade", 1L);
        var step = ModelCommitStep.builder().event(internal)
                .targets(List.of(target("child").toBuilder().storeEvent(true).build())).build();
        var valid = new CommitModels(original.getCommitId(), -1L, List.of("order-1", "child"),
                List.of(original.getSubsteps().getFirst(), step), ModelConflictPolicy.ACCEPT, Guarantee.STORED, true, true);
        assertDoesNotThrow(() -> ModelCommitValidator.validate(valid));
        var decoded = (CommitModels) io.fluxzero.common.serialization.JsonUtils.fromJson(
                io.fluxzero.common.serialization.JsonUtils.asJson(valid), io.fluxzero.common.api.JsonType.class);
        assertEquals(valid, decoded);
        assertDoesNotThrow(() -> ModelCommitValidator.validate(decoded));
        for (String value : List.of("41", "-1", "invalid")) {
            internal.setMetadata(Metadata.of(ModelEventMetadata.MIGRATION_SOURCE_EVENT_INDEX, value));
            assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(valid));
        }
        internal.setMetadata(Metadata.of(ModelEventMetadata.MIGRATION_SOURCE_EVENT_INDEX, "42"));
        var reversed = new CommitModels(original.getCommitId(), -1L, List.of("order-1", "child"),
                List.of(step, original.getSubsteps().getFirst()), ModelConflictPolicy.ACCEPT, Guarantee.STORED, true, true);
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(reversed));
        for (var invalidStep : List.of(step.toBuilder().publishEvent(true).build(),
                step.toBuilder().targets(List.of(target("child").toBuilder().storeEvent(false).build())).build())) {
            var invalid = new CommitModels(original.getCommitId(), -1L, List.of("order-1", "child"),
                    List.of(original.getSubsteps().getFirst(), invalidStep), ModelConflictPolicy.ACCEPT, Guarantee.STORED, true, true);
            assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(invalid));
        }
    }

    @Test
    void ordinarySimpleCommitRejectsMigrationMarker() {
        var ordinary = publishedCommit(-1L);
        ordinary.getSubsteps().getFirst().getEvent().setMetadata(
                Metadata.of(ModelEventMetadata.MIGRATION_SOURCE_EVENT_INDEX, "42"));
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(ordinary));
    }

    @Test
    void eventBeforeSelectionSurvivesRequestSerialization() {
        var before = ModelReadBoundary.event(42L).asBefore();
        var request = new GetModelEvents(List.of(new ModelEventStreamRequest("model", -1L, 100)), before, 0);
        assertTrue(request.getBoundary().before());
        var decoded = (GetModelEvents) io.fluxzero.common.serialization.JsonUtils.fromJson(
                io.fluxzero.common.serialization.JsonUtils.asJson(request), io.fluxzero.common.api.JsonType.class);
        assertEquals(before, decoded.getBoundary());
        assertTrue(new GetModelReconstruction("model", before, 100, 0, false).getBoundary().before());
    }

    @Test
    void migrationRelationshipOptInSurvivesRoundTripAndMissingFieldRetainsLegacyBehavior() {
        var commit = new CommitModelsWithLiveMigrationDocuments(existingEventCommit("event-1", "event-1", 42L), true);
        String json = io.fluxzero.common.serialization.JsonUtils.asJson(commit);
        var decoded = (CommitModels) io.fluxzero.common.serialization.JsonUtils.fromJson(json, io.fluxzero.common.api.JsonType.class);
        assertTrue(decoded.preservesMigrationRelationships());
        assertTrue(decoded.withDeadlineUpdates(List.of()).preservesMigrationRelationships());
        String legacy = json.replace("\"preserveMigrationRelationships\":true,", "")
                .replace(",\"preserveMigrationRelationships\":true", "");
        assertFalse(legacy.contains("preserveMigrationRelationships"));
        var oldRequest = (CommitModels) io.fluxzero.common.serialization.JsonUtils.fromJson(legacy, io.fluxzero.common.api.JsonType.class);
        assertFalse(oldRequest.preservesMigrationRelationships());
    }

    @Test
    void liveMigrationRoundTripsWithoutChangingLegacyStagingOrReadDependencies() {
        CommitModels legacy = existingEventCommit("event-1", "event-1", 42L);
        var live = new CommitModelsWithLiveMigrationDocuments(
                new CommitModelsWithAliasReads(legacy, List.of("order-1")));
        assertTrue(legacy.stagesMigrationDocuments());
        assertTrue(live.isMigration());
        assertFalse(live.stagesMigrationDocuments());
        assertDoesNotThrow(() -> ModelCommitValidator.validate(live));
        var decoded = (CommitModels) io.fluxzero.common.serialization.JsonUtils.fromJson(
                io.fluxzero.common.serialization.JsonUtils.asJson(live), io.fluxzero.common.api.JsonType.class);
        assertEquals(live, decoded);
        assertFalse(decoded.stagesMigrationDocuments());
        assertEquals(List.of("order-1"), decoded.getReadAliasIds());
        assertFalse(live.withDeadlineUpdates(List.of()).stagesMigrationDocuments());
        var oldDecoded = (CommitModels) io.fluxzero.common.serialization.JsonUtils.fromJson(
                io.fluxzero.common.serialization.JsonUtils.asJson(legacy), io.fluxzero.common.api.JsonType.class);
        assertTrue(oldDecoded.stagesMigrationDocuments());
        assertThrows(IllegalArgumentException.class, () -> new CommitModelsWithLiveMigrationDocuments(publishedCommit(-1L)));
        assertThrows(IllegalArgumentException.class, () -> new CommitModelsWithLiveMigrationDocuments(
                new CommitModelsWithIdentityReads(legacy, List.of(new ModelAliasObservation("alias", "order-1")))));
    }

    @Test
    void liveMigrationPreservesSourceIdentityAndNoPublicationValidation() {
        var live = new CommitModelsWithLiveMigrationDocuments(existingEventCommit("event-1", "event-1", 42L));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(live));
        var invalid = new CommitModelsWithLiveMigrationDocuments(existingEventCommit("different", "event-1", 42L));
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(invalid));
        live.getSubsteps().getFirst().getEvent().setIndex(null);
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(live));
    }

    @Test
    void acceptsSimplePublishedCreateAndUpdate() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(publishedCommit(-1L)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(publishedCommit(42L)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(publishedCommit(null)));
    }

    @Test
    void aliasReadsRequireCanonicalLookupHeadsAndUniqueKeys() {
        CommitModels base = publishedCommit(-1L);
        String id = base.getReadModelIds().getFirst();
        assertDoesNotThrow(() -> ModelCommitValidator.validate(new CommitModelsWithAliasReads(base, List.of(id))));
        assertThrows(IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(new CommitModelsWithAliasReads(base, List.of("missing"))));
        assertThrows(IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(new CommitModelsWithAliasReads(base, List.of(id, id))));
        assertThrows(IllegalArgumentException.class, () -> new CommitModelsWithAliasReads(base, List.of()));
    }

    @Test
    void identityObservationsRequireCanonicalHeadsAndUniqueNonEmptyAliases() {
        CommitModels base = publishedCommit(-1L);
        String id = base.getReadModelIds().getFirst();
        assertDoesNotThrow(() -> ModelCommitValidator.validate(new CommitModelsWithIdentityReads(base,
                List.of(new ModelAliasObservation("email", id), new ModelAliasObservation("missing", null)))));
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(
                new CommitModelsWithIdentityReads(base, List.of(new ModelAliasObservation("email", "unread")))));
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(
                new CommitModelsWithIdentityReads(base, List.of(new ModelAliasObservation(" ", null)))));
        assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(
                new CommitModelsWithIdentityReads(base, List.of(new ModelAliasObservation("email", id),
                        new ModelAliasObservation("email", id)))));
    }

    @Test
    void rejectsMalformedSimplePublishedUpdate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(publishedCommit(-2L)));
        CommitModels indexedEvent = publishedCommit(42L);
        indexedEvent.getSubsteps().getFirst().getEvent().setIndex(1L);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(indexedEvent));
    }

    @Test
    void acceptsOneExistingGlobalEventWithoutRepublishingIt() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                existingEventCommit("event-1", "event-1", 42L)));
    }

    @Test
    void validatesExistingGlobalEventIdentityAndShape() {
        CommitModels republished = existingEventCommit("event-1", "event-1", 42L);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(new CommitModels(
                        republished.getCommitId(), republished.getReadStateIndex(),
                        republished.getReadModelIds(),
                        List.of(republished.getSubsteps().getFirst().toBuilder()
                                        .publishEvent(true)
                                        .build()),
                        republished.getConflictPolicy(), republished.getGuarantee(), true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        existingEventCommit("other", "event-1", 42L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        existingEventCommit("event-1", "event-1", -1L)));

        CommitModels multiple = existingEventCommit("event-1", "event-1", 42L);
        SerializedMessage second = new SerializedMessage(
                new Data<>(new byte[]{2}, "event", 0), Metadata.empty(), "event-1", 1L);
        second.setIndex(43L);
        CommitModels multipleSteps = new CommitModels(
                multiple.getCommitId(), multiple.getReadStateIndex(),
                multiple.getReadModelIds(),
                List.of(
                        multiple.getSubsteps().getFirst(),
                        ModelCommitStep.builder()
                                .event(second)
                                .targets(List.of(target("order-1")))
                                .build()),
                multiple.getConflictPolicy(), multiple.getGuarantee(), true);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(multipleSteps));
    }

    @Test
    void acceptsOneCompleteStateOnlyTransition() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(commit(
                List.of("order-1"),
                ModelCommitTarget.builder()
                        .modelId("order-1")
                        .updateState(true)
                        .relationships(List.of())
                        .build())));
    }

    @Test
    void acceptsEventOnlyTransitionButRejectsEmptyNoOp() {
        ModelCommitTarget eventOnly = ModelCommitTarget.builder()
                .modelId("order-1")
                .updateState(false)
                .relationships(List.of())
                .build();
        CommitModels published = new CommitModels(
                "commit-1", -1L, List.of("order-1"),
                List.of(ModelCommitStep.builder()
                                .event(new SerializedMessage(
                                        new Data<>(new byte[]{1}, "event", 0), Metadata.empty(), "event-1", 1L))
                                .publishEvent(true)
                                .targets(List.of(eventOnly))
                                .build()),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED, true);

        assertDoesNotThrow(() -> ModelCommitValidator.validate(published));
        assertThrows(IllegalArgumentException.class,
                     () -> ModelCommitValidator.validate(commit(List.of("order-1"), eventOnly)));
    }

    @Test
    void rejectsDuplicateReadsAndMalformedTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(commit(
                        List.of("order-1", "order-1"),
                        target("order-1"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(commit(
                        List.of("order-1"),
                        target("order-2"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(commit(
                        List.of("order-1"),
                        target("order-1").toBuilder()
                                .relationships(List.of(
                                        ModelRelationship.builder()
                                                .parentId(" ")
                                                .build()))
                                .build())));
    }

    @Test
    void rejectsSnapshotsThatCannotBeReconstructed() {
        ModelCommitTarget invalid = target("order-1").toBuilder()
                .snapshot(new ModelSnapshotMutation(null, 0L, 10, 2, null))
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(commit(List.of("order-1"), invalid)));
    }

    @Test
    void acceptsNegativeSnapshotLimitsButRejectsZeroOnTheWire() {
        for (int maximum : new int[]{-1, -7, Integer.MIN_VALUE, 1, 2, 0}) {
            CommitModels base = publishedCommit(-1L);
            var step = base.getSubsteps().getFirst();
            var target = step.getTargets().getFirst().toBuilder()
                    .snapshot(new ModelSnapshotMutation(new Data<>(new byte[]{1}, "order", 0), 1L, 1, maximum, null))
                    .build();
            var request = new CommitModels(base.getCommitId(), base.getReadStateIndex(), base.getReadModelIds(),
                    List.of(step.toBuilder().targets(List.of(target)).build()), base.getConflictPolicy(),
                    base.getGuarantee(), true);
            if (maximum == 0) {
                assertThrows(IllegalArgumentException.class, () -> ModelCommitValidator.validate(request));
            } else {
                assertDoesNotThrow(() -> ModelCommitValidator.validate(request));
            }
        }
    }

    @Test
    void validatesCompleteAliasReplacement() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(commit(
                List.of("order-1"),
                target("order-1").toBuilder()
                        .aliases(List.of("code-1", "code-2"))
                        .build())));
        assertThrows(IllegalArgumentException.class, () ->
                ModelCommitValidator.validate(commit(
                        List.of("order-1"),
                        target("order-1").toBuilder()
                                .aliases(List.of("code", "code"))
                                .build())));
        assertThrows(IllegalArgumentException.class, () ->
                ModelCommitValidator.validate(commit(
                        List.of("order-1"),
                        target("order-1").toBuilder()
                                .aliases(List.of(" "))
                                .build())));
        assertThrows(IllegalArgumentException.class, () ->
                ModelCommitValidator.validate(commit(
                        List.of("order-1"),
                        target("order-1").toBuilder()
                                .delete(true)
                                .updateRelationships(true)
                                .aliases(List.of("code"))
                                .build())));
    }

    @Test
    void acceptsCascadeDeletionButRejectsCascadeWithoutDeletion() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(commit(
                List.of("order-1"),
                target("order-1").toBuilder()
                        .delete(true)
                        .cascadeDelete(true)
                        .updateRelationships(true)
                        .build())));
        assertThrows(IllegalArgumentException.class, () ->
                ModelCommitValidator.validate(commit(
                        List.of("order-1"),
                        target("order-1").toBuilder()
                                .cascadeDelete(true)
                                .build())));
    }

    @Test
    void validatesTemporalGraphBoundsAndBoundaries() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                new GetModelGraph(
                        "root-1", ModelReadBoundary.current(),
                        16, 10_000, 100, 0L, true)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                new GetModelGraph(
                        "root-1", ModelReadBoundary.current(),
                        -1, -1, 100, 0L, true)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                new GetModelGraph(
                        "root-1", ModelReadBoundary.current(), Integer.MAX_VALUE, Integer.MAX_VALUE,
                        100, 0L, true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        new GetModelGraph(
                                "root-1", ModelReadBoundary.current(), -2, 10_000,
                                100, 0L, true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        new GetModelGraph(
                                "root-1", ModelReadBoundary.current(), 16, -2,
                                100, 0L, true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        GetModelGraph.ancestors(
                                List.of("child-1", "child-2"), ModelReadBoundary.current(),
                                1, 1, 100, 0L)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                GetModelGraph.ancestors(
                        List.of("child-1"), ModelReadBoundary.current(),
                        -1, -1, 0, 0L)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                GetModelGraph.ancestors(
                        List.of("child-1"), ModelReadBoundary.state(2L, false).asBefore(),
                        -1, -1, 0, 0L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        GetModelGraph.ancestors(
                                List.of("child-1"), ModelReadBoundary.current(),
                                -2, -1, 0, 0L)));
    }

    @Test
    void validatesModelChangeBoundary() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                new GetModelChange("commit-1", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        new GetModelChange(" ", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        new GetModelChange("commit-1", -1)));
    }

    @Test
    void validatesDeletionBounds() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(
                new PlanModelDeletion("root-1", ModelDeletionCascade.NONE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(
                        new PlanModelDeletion(
                                "root-1", ModelDeletionCascade.DESCENDANTS,
                                1_025, 100_000, 100)));
    }

    private static CommitModels commit(
            List<String> readModelIds,
            ModelCommitTarget target) {
        return new CommitModels(
                "commit-1", -1L, readModelIds,
                List.of(ModelCommitStep.builder()
                                .targets(List.of(target))
                                .build()),
                ModelConflictPolicy.ACCEPT,
                Guarantee.STORED, true);
    }

    private static ModelCommitTarget target(String modelId) {
        return ModelCommitTarget.builder()
                .modelId(modelId)
                .updateState(true)
                .relationships(List.of())
                .build();
    }

    private static CommitModels publishedCommit(Long expectedSequenceNumber) {
        SerializedMessage event = new SerializedMessage(
                new Data<>(new byte[]{1}, "event", 0), Metadata.empty(), "event-1", 1L);
        ModelCommitTarget target = ModelCommitTarget.builder()
                .modelId("order-1")
                .modelType("order")
                .expectedSequenceNumber(expectedSequenceNumber)
                .storeEvent(true)
                .updateState(true)
                .relationships(List.of())
                .build();
        return new CommitModels(
                "commit-1", 42L, List.of("order-1"),
                List.of(ModelCommitStep.builder()
                                .event(event)
                                .publishEvent(true)
                                .targets(List.of(target))
                                .build()),
                ModelConflictPolicy.ACCEPT,
                Guarantee.STORED, true);
    }

    private static CommitModels existingEventCommit(
            String commitId, String messageId, long eventIndex) {
        SerializedMessage event = new SerializedMessage(
                new Data<>(new byte[]{1}, "event", 0), Metadata.empty(), messageId, 1L);
        event.setIndex(eventIndex);
        return new CommitModels(
                commitId, -1L, List.of("order-1"),
                List.of(ModelCommitStep.builder()
                                .event(event)
                                .targets(List.of(target("order-1").toBuilder()
                                                         .modelType("order")
                                                         .storeEvent(true)
                                                         .build()))
                                .build()),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED, true, true);
    }
}
