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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies one exact, runtime-positioned model materialization package to a possibly separate search runtime.
 * <p>
 * Search stores must fence direct-document mutations by {@code stateIndex}; retries and out-of-order delivery may
 * therefore never replace a model document with an older or conflicting equal-index value. Snapshots are immutable
 * and trimmed idempotently.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class MaterializeModelAction extends Command {
    String actionId;
    long lastStateIndex;
    List<ModelDocumentMaterialization> documents;
    List<ModelSnapshotMaterialization> snapshots;
    Guarantee guarantee = Guarantee.STORED;

    /**
     * Extracts the exact direct-document and snapshot package from an assigned model action.
     */
    public static MaterializeModelAction from(
            CommitModelAction action,
            List<ModelActionSubstepResult> assignedSubsteps) {
        List<ModelDocumentMaterialization> documents = new ArrayList<>();
        List<ModelSnapshotMaterialization> snapshots = new ArrayList<>();
        for (int substep = 0; substep < action.getSubsteps().size(); substep++) {
            ModelActionSubstep source = action.getSubsteps().get(substep);
            ModelActionSubstepResult assigned = assignedSubsteps.get(substep);
            for (int target = 0; target < source.getTargets().size(); target++) {
                ModelActionTarget mutation = source.getTargets().get(target);
                ModelActionTargetResult position = assigned.getTargets().get(target);
                if (mutation.getDocument() != null) {
                    documents.add(new ModelDocumentMaterialization(
                            mutation.getModelId(), assigned.getStateIndex(), mutation.getDocument()));
                }
                if (mutation.getSnapshot() != null && position.isHistoryComplete()) {
                    snapshots.add(new ModelSnapshotMaterialization(
                            mutation.getModelId(), position.getSequenceNumber(),
                            assigned.getStateIndex(), mutation.getSnapshot()));
                }
            }
        }
        return new MaterializeModelAction(
                action.getActionId(), assignedSubsteps.getLast().getStateIndex(),
                List.copyOf(documents), List.copyOf(snapshots));
    }

    @Override
    public String routingKey() {
        return actionId;
    }

    @Override
    public Object toMetric() {
        long bytes = documents.stream()
                .mapToLong(value -> value.getMutation().getBytes())
                .sum();
        bytes += snapshots.stream()
                .mapToLong(value -> value.getMutation().getBytes())
                .sum();
        return new Metric(
                actionId, lastStateIndex,
                documents.size(), snapshots.size(), bytes);
    }

    public record Metric(
            String actionId,
            long lastStateIndex,
            int documentCount,
            int snapshotCount,
            long bytes) {
    }
}
