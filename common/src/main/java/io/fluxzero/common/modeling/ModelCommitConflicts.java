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

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Owns generic model-commit conflict detection and conflict-policy outcomes. Stores supply their current heads,
 * optional relationship positions and the identities whose operation depends on a current relationship closure;
 * storage-specific loading remains the store's responsibility.
 */
public final class ModelCommitConflicts {

    /**
     * Detects stale read-state and expected-sequence conflicts in stable request order.
     *
     * <p>No collection is allocated when the commit has no conflicts, and each model occurs at most once.</p>
     */
    public static <H> List<ModelCommitConflict> detect(
            CommitModels commit,
            Map<String, ? extends H> heads,
            ToLongFunction<? super H> sequenceNumber,
            ToLongFunction<? super H> stateIndex,
            Map<String, Long> relationStateIndices) {
        return detect(
                commit, heads, sequenceNumber, stateIndex,
                relationStateIndices, List.of());
    }

    /**
     * Detects ordinary model conflicts plus relationship-only changes for identities whose evaluated operation
     * depended on their relationship closure, such as cascade-deletion roots.
     */
    public static <H> List<ModelCommitConflict> detect(
            CommitModels commit,
            Map<String, ? extends H> heads,
            ToLongFunction<? super H> sequenceNumber,
            ToLongFunction<? super H> stateIndex,
            Map<String, Long> relationStateIndices,
            Iterable<String> relationshipSensitiveModelIds) {
        List<ModelCommitConflict> conflicts = null;
        for (int modelIndex = 0; modelIndex < commit.getReadModelIds().size(); modelIndex++) {
            String modelId = commit.getReadModelIds().get(modelIndex);
            H head = heads.get(modelId);
            long currentStateIndex = head == null ? -1L : stateIndex.applyAsLong(head);
            if (currentStateIndex > commit.getReadStateIndex()
                && !contains(conflicts, modelId)) {
                if (conflicts == null) {
                    conflicts = new ArrayList<>();
                }
                conflicts.add(new ModelCommitConflict(
                        modelId, currentStateIndex,
                        relationStateIndices.getOrDefault(modelId, -1L)));
            }
        }
        for (int stepIndex = 0; stepIndex < commit.getSubsteps().size(); stepIndex++) {
            ModelCommitStep substep = commit.getSubsteps().get(stepIndex);
            for (int targetIndex = 0; targetIndex < substep.getTargets().size(); targetIndex++) {
                ModelCommitTarget target = substep.getTargets().get(targetIndex);
                Long expectedSequence = target.getExpectedSequenceNumber();
                if (expectedSequence == null || contains(conflicts, target.getModelId())) {
                    continue;
                }
                H head = heads.get(target.getModelId());
                long currentSequence = head == null ? -1L : sequenceNumber.applyAsLong(head);
                if (expectedSequence != currentSequence) {
                    if (conflicts == null) {
                        conflicts = new ArrayList<>();
                    }
                    conflicts.add(new ModelCommitConflict(
                            target.getModelId(),
                            head == null ? -1L : stateIndex.applyAsLong(head),
                            relationStateIndices.getOrDefault(target.getModelId(), -1L)));
                }
            }
        }
        for (String modelId : relationshipSensitiveModelIds) {
            long currentRelationStateIndex =
                    relationStateIndices.getOrDefault(modelId, -1L);
            if (currentRelationStateIndex <= commit.getReadStateIndex()
                || contains(conflicts, modelId)) {
                continue;
            }
            if (conflicts == null) {
                conflicts = new ArrayList<>();
            }
            H head = heads.get(modelId);
            conflicts.add(new ModelCommitConflict(
                    modelId,
                    head == null ? -1L : stateIndex.applyAsLong(head),
                    currentRelationStateIndex));
        }
        return conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /**
     * Maps detected conflicts to the outcome required by the commit's resolved conflict policy.
     *
     * @return {@code null} when there are no conflicts
     */
    public static CommitModelsResult result(
            CommitModels commit,
            List<ModelCommitConflict> conflicts,
            long rebaseBoundary) {
        if (conflicts.isEmpty()) {
            return null;
        }
        ModelConflictPolicy policy = ModelConflictPolicy.resolve(commit.getConflictPolicy());
        if (policy == ModelConflictPolicy.ACCEPT) {
            return CommitModelsResult.rebase(
                    commit.getRequestId(), commit.getCommitId(), conflicts, rebaseBoundary);
        }
        return CommitModelsResult.conflict(
                commit.getRequestId(), commit.getCommitId(), conflicts,
                policy == ModelConflictPolicy.RETRY);
    }

    private static boolean contains(
            List<ModelCommitConflict> conflicts,
            String modelId) {
        if (conflicts != null) {
            for (ModelCommitConflict conflict : conflicts) {
                if (conflict.getModelId().equals(modelId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ModelCommitConflicts() {
    }
}
