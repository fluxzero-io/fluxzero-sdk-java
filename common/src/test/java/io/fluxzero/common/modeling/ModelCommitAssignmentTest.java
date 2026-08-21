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

package io.fluxzero.common.modeling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommitAssignmentTest {

    @Test
    void describesFinalCommitScopeInSourceOrder() {
        CommitModels source = commit(
                step(target("first", false, true, true)),
                step(target("second", false, true, true)),
                step(target("first", true, false, false)));

        ModelCommitAssignment.Description result = ModelCommitAssignment.describe(source);

        assertEquals(List.of("first", "second"), result.targetIds());
        assertEquals(List.of("first", "second"), result.unstoredTargetIds());
        assertEquals(Map.of("second", 1), result.finalDeletionSubsteps());
        assertEquals(List.of("second"), result.cascadeRootIds());
        assertTrue(result.affectsRelationships());
    }

    @Test
    void assignsRepeatedTargetsFromThePreviousAssignedHead() {
        var previous = new TestHead(
                "model", "type", 7, 4L, 80L, null, false, "documents");
        SerializedMessage firstEvent = event("first");
        CommitModels source = commit(
                step(firstEvent, target("model", true, false, false)),
                step(target("model", false, false, false)));

        List<TestHead> heads = new ArrayList<>();
        ModelCommitAssignment.Commit<TestHead> result =
                assign(ModelCommitAssignment.session(id -> previous, HEAD_FACTORY, 100L),
                       source, (step, target, substep, head) -> heads.add(head));
        firstEvent.setIndex(42L);

        assertEquals(5L, heads.getFirst().sequenceNumber());
        assertEquals(5L, heads.getLast().sequenceNumber());
        assertEquals(101L, heads.getLast().firstIncompleteStateIndex());
        assertEquals("documents", heads.getLast().documentCollection());
        assertFalse(heads.getLast().historyComplete());
        assertEquals(42L, result.result().getSubsteps().getFirst().getEventIndex());
        assertFalse(result.result().getSubsteps().getLast().getTargets().getFirst()
                            .isHistoryComplete());
    }

    @Test
    void deletionClearsDocumentCollection() {
        var previous = new TestHead(
                "model", "type", 7, 4L, 80L, null, false, "documents");

        List<TestHead> heads = new ArrayList<>();
        assign(ModelCommitAssignment.session(id -> previous, HEAD_FACTORY, 100L),
               commit(step(target("model", false, true, false))),
               (step, target, substep, head) -> heads.add(head));
        TestHead result = heads.getFirst();

        assertTrue(result.deleted());
        assertNull(result.documentCollection());
    }

    @Test
    void describesValidatesAndAppliesFinalAliasReplacements() {
        ModelCommitTarget first = target("first", true, false, false).toBuilder()
                .aliases(List.of("first", "shared")).build();
        ModelCommitTarget second = target("second", true, false, false).toBuilder()
                .aliases(List.of("replacement")).build();
        ModelCommitAssignment.Aliases aliases = ModelCommitAssignment.describe(
                commit(step(first), step(second))).aliases();
        Map<String, String> current = new java.util.HashMap<>(
                Map.of("legacy", "first", "unrelated", "other"));

        aliases.validate(current);
        aliases.applyTo(current);

        assertEquals(
                Map.of("shared", "first", "replacement", "second", "unrelated", "other"),
                current);
        assertThrows(
                ModelCommitAssignment.AliasCollisionException.class,
                () -> ModelCommitAssignment.describe(commit(
                        step(first), step(second.toBuilder().aliases(List.of("shared")).build()))));
    }

    @Test
    void rejectsTypeChangesAndStateIndexOverflowBeforeProducingAResult() {
        var previous = new TestHead(
                "model", "old", 7, 4L, 80L, null, false, null);
        CommitModels typeChange = commit(step(
                ModelCommitTarget.builder()
                        .modelId("model").modelType("new").storeEvent(true).updateState(true)
                        .relationships(List.of()).build()));

        assertThrows(
                IllegalArgumentException.class,
                () -> assign(ModelCommitAssignment.session(id -> previous, HEAD_FACTORY, 100L),
                             typeChange, IGNORE));
        assertThrows(
                IllegalStateException.class,
                () -> assign(ModelCommitAssignment.session(
                                     id -> (TestHead) null, HEAD_FACTORY, Long.MAX_VALUE),
                             commit(step(target("first", true, false, false)),
                                    step(target("second", true, false, false))), IGNORE));
        ModelCommitAssignment.Session<TestHead> exhausted = ModelCommitAssignment.session(
                id -> null, HEAD_FACTORY, Long.MAX_VALUE);
        assign(exhausted, commit(step(target("first", true, false, false))), IGNORE);
        assertThrows(
                IllegalStateException.class,
                () -> assign(exhausted,
                             commit(step(target("second", true, false, false))), IGNORE));
    }

    private static CommitModels commit(ModelCommitStep... steps) {
        return new CommitModels(
                "commit", -1L,
                List.of("first", "second", "model"), List.of(steps),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED, false);
    }

    private static ModelCommitStep step(ModelCommitTarget target) {
        return step(event(target.getModelId()), target);
    }

    private static ModelCommitStep step(SerializedMessage event, ModelCommitTarget target) {
        return ModelCommitStep.builder()
                .event(event).publishEvent(true).targets(List.of(target)).build();
    }

    private static ModelCommitTarget target(
            String id, boolean storeEvent, boolean delete, boolean cascadeDelete) {
        return ModelCommitTarget.builder()
                .modelId(id).modelType("type").storeEvent(storeEvent).updateState(true)
                .delete(delete).cascadeDelete(cascadeDelete)
                .updateRelationships(delete).relationships(List.of()).build();
    }

    private static SerializedMessage event(String id) {
        return new SerializedMessage(
                new Data<>(id.getBytes(), "event", 0, "application/octet-stream"),
                Metadata.empty(), null, null, null, null, null,
                System.currentTimeMillis(), id, null);
    }

    private static <H extends ModelCommitAssignment.Head>
    ModelCommitAssignment.Commit<H> assign(
            ModelCommitAssignment.Session<H> session, CommitModels source,
            ModelCommitAssignment.HeadConsumer<H> consumer) {
        return session.assign(source, ModelCommitAssignment.describe(source), consumer);
    }

    private record TestHead(
            String modelId, String modelType, int segment, long sequenceNumber, long stateIndex,
            Long firstIncompleteStateIndex, boolean deleted, String documentCollection)
            implements ModelCommitAssignment.Head {
        @Override
        public boolean historyComplete() {
            return firstIncompleteStateIndex == null;
        }
    }

    private static final ModelCommitAssignment.HeadFactory<TestHead> HEAD_FACTORY =
            (modelId, previous, modelType, sequenceNumber, stateIndex, incomplete,
             deleted, collection) -> new TestHead(
                    modelId, modelType, previous == null ? 7 : previous.segment(),
                    sequenceNumber, stateIndex, incomplete, deleted, collection);

    private static final ModelCommitAssignment.HeadConsumer<TestHead> IGNORE =
            (step, target, substep, head) -> {
            };
}
