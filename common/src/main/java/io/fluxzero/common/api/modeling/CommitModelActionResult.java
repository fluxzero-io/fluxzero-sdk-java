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

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.beans.Transient;
import java.util.List;

/**
 * Result of an accepted, rebase-requested, or conflict-rejected
 * {@link CommitModelAction}.
 * <p>
 * Accepted results are durable. A duplicate accepted {@code actionId} returns the same logical result with the new
 * request ID used for response correlation. Rebase and conflict results are
 * not retained under the idempotency key.
 */
@Value
public class CommitModelActionResult extends AbstractRequestResult {

    /**
     * Request ID used to correlate this response.
     */
    long requestId;

    /**
     * Durable action idempotency key.
     */
    String actionId;

    /**
     * Assigned positions in request substep order.
     */
    List<ModelActionSubstepResult> substeps;

    /**
     * Current positions for model identities involved in a rejected action. Empty for an accepted action.
     */
    List<ModelActionConflict> conflicts;

    /**
     * Whether the runtime permits an SDK retry. For strict conflict results
     * this means the scoped relationships were unchanged; a default-policy
     * apply-only rebase is always retryable at
     * {@link #rebaseStateIndex}.
     */
    boolean retryAllowed;

    /**
     * Whether an accepted response represents an already committed action ID rather than a new commit.
     */
    boolean duplicate;

    /**
     * New pinned boundary requested for an internal apply-only rebase, or {@code null} when no rebase is required.
     */
    Long rebaseStateIndex;

    /**
     * Whether direct model-document consequences were completed by the authoritative store.
     * <p>
     * This remains {@code false} for SDK-local/in-memory compatibility stores that require the SDK fallback.
     */
    boolean documentsApplied;

    /**
     * Whether due model snapshots were completed by the authoritative store.
     */
    boolean snapshotsApplied;

    /**
     * Timestamp at which this result object was created.
     */
    long timestamp = System.currentTimeMillis();

    /**
     * Creates a model action result, normalizing omitted compatibility fields to empty collections.
     */
    @ConstructorProperties({
            "requestId", "actionId", "substeps", "conflicts", "retryAllowed",
            "duplicate", "rebaseStateIndex", "documentsApplied",
            "snapshotsApplied"})
    public CommitModelActionResult(
            long requestId,
            String actionId,
            List<ModelActionSubstepResult> substeps,
            List<ModelActionConflict> conflicts,
            boolean retryAllowed,
            boolean duplicate,
            Long rebaseStateIndex,
            boolean documentsApplied,
            boolean snapshotsApplied) {
        this.requestId = requestId;
        this.actionId = actionId;
        this.substeps = substeps == null ? List.of() : List.copyOf(substeps);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.retryAllowed = retryAllowed;
        this.duplicate = duplicate;
        this.rebaseStateIndex = rebaseStateIndex;
        this.documentsApplied = documentsApplied;
        this.snapshotsApplied = snapshotsApplied;
    }

    /**
     * Creates an accepted action result.
     */
    public static CommitModelActionResult accepted(
            long requestId, String actionId, List<ModelActionSubstepResult> substeps) {
        return new CommitModelActionResult(
                requestId, actionId, substeps, List.of(), false,
                false, null, false, false);
    }

    /**
     * Creates a rejected conflict result. Rejected results are not retained under the action idempotency key.
     */
    public static CommitModelActionResult conflict(
            long requestId,
            String actionId,
            List<ModelActionConflict> conflicts,
            boolean retryAllowed) {
        return new CommitModelActionResult(
                requestId, actionId, List.of(), conflicts, retryAllowed,
                false, null, false, false);
    }

    /**
     * Returns whether the runtime committed the action.
     */
    @Transient
    public boolean isAccepted() {
        return conflicts.isEmpty() && !isRebaseRequired();
    }

    /**
     * Returns whether the runtime requests an internal apply-only rebase without rejecting the original event.
     */
    @Transient
    public boolean isRebaseRequired() {
        return rebaseStateIndex != null;
    }

    /**
     * Copies this logical result with another transport request ID.
     */
    public CommitModelActionResult forRequest(long requestId) {
        return new CommitModelActionResult(
                requestId, actionId, substeps, conflicts, retryAllowed,
                duplicate, rebaseStateIndex, documentsApplied,
                snapshotsApplied);
    }

    /**
     * Copies this accepted result for an idempotent duplicate request.
     */
    public CommitModelActionResult asDuplicateForRequest(long requestId) {
        return new CommitModelActionResult(
                requestId, actionId, substeps, conflicts, retryAllowed,
                true, rebaseStateIndex, documentsApplied,
                snapshotsApplied);
    }

    /**
     * Marks runtime-owned direct document completion on an accepted result.
     */
    public CommitModelActionResult withDocumentsApplied() {
        return new CommitModelActionResult(
                requestId, actionId, substeps, conflicts, retryAllowed,
                duplicate, rebaseStateIndex, true,
                snapshotsApplied);
    }

    /**
     * Marks runtime-owned snapshot completion on an accepted result.
     */
    public CommitModelActionResult withSnapshotsApplied() {
        return new CommitModelActionResult(
                requestId, actionId, substeps, conflicts, retryAllowed,
                duplicate, rebaseStateIndex, documentsApplied,
                true);
    }

    /**
     * Requests an internal apply-only rebase at the supplied current boundary.
     */
    public static CommitModelActionResult rebase(
            long requestId,
            String actionId,
            List<ModelActionConflict> changedModels,
            long rebaseStateIndex) {
        return new CommitModelActionResult(
                requestId, actionId, List.of(), changedModels, true,
                false, rebaseStateIndex, false, false);
    }

    /**
     * Returns a target-ID-free metric representation.
     */
    @Override
    public Metric toMetric() {
        int targetCount = 0;
        for (ModelActionSubstepResult substep : substeps) {
            targetCount += substep.getTargets().size();
        }
        return new Metric(
                substeps.size(), targetCount, conflicts.size(), retryAllowed,
                duplicate, isRebaseRequired(), documentsApplied,
                snapshotsApplied, timestamp);
    }

    /**
     * Payload-free commit-result metrics.
     */
    @Value
    public static class Metric {
        int substepCount;
        int targetCount;
        int conflictCount;
        boolean retryAllowed;
        boolean duplicate;
        boolean rebaseRequired;
        boolean documentsApplied;
        boolean snapshotsApplied;
        long timestamp;
    }
}
