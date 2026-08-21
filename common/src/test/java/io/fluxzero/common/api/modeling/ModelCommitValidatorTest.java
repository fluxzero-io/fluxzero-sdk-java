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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCommitValidatorTest {

    @Test
    void acceptsSimplePublishedCreateAndUpdate() {
        assertDoesNotThrow(() -> ModelCommitValidator.validate(publishedCommit(-1L)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(publishedCommit(42L)));
        assertDoesNotThrow(() -> ModelCommitValidator.validate(publishedCommit(null)));
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
                .snapshot(new ModelSnapshotMutation(null, 0L, 10, 2))
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitValidator.validate(commit(List.of("order-1"), invalid)));
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
}
