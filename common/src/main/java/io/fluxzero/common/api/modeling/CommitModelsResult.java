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
 * {@link CommitModels}.
 * <p>
 * Accepted results are durable and include completion of direct document and snapshot materialization. A duplicate
 * accepted {@code commitId} returns the same logical result with the new request ID used for response correlation.
 * Rebase and conflict results are not retained under the idempotency key.
 */
@Value
public class CommitModelsResult extends AbstractRequestResult {

    /**
     * Request ID used to correlate this response.
     */
    long requestId;

    /**
     * Durable commit idempotency key.
     */
    String commitId;

    /**
     * Assigned positions in request substep order.
     */
    List<ModelCommitStepResult> substeps;

    /**
     * Current positions for model identities involved in a rejected commit. Empty for an accepted commit.
     */
    List<ModelCommitConflict> conflicts;

    /**
     * Whether the runtime permits an SDK retry. For strict conflict results
     * this means the scoped relationships were unchanged; a default-policy
     * apply-only rebase is always retryable at
     * {@link #rebaseStateIndex}.
     */
    boolean retryAllowed;

    /**
     * Whether an accepted response represents an already committed commit ID rather than a new commit.
     */
    boolean duplicate;

    /**
     * New pinned boundary requested for an internal apply-only rebase, or {@code null} when no rebase is required.
     */
    Long rebaseStateIndex;

    /**
     * Timestamp at which this result object was created.
     */
    long timestamp = System.currentTimeMillis();

    /**
     * Creates a model commit result, normalizing omitted compatibility fields to empty collections.
     */
    @ConstructorProperties({
            "requestId", "commitId", "substeps", "conflicts", "retryAllowed",
            "duplicate", "rebaseStateIndex"})
    public CommitModelsResult(
            long requestId,
            String commitId,
            List<ModelCommitStepResult> substeps,
            List<ModelCommitConflict> conflicts,
            boolean retryAllowed,
            boolean duplicate,
            Long rebaseStateIndex) {
        this.requestId = requestId;
        this.commitId = commitId;
        this.substeps = substeps == null ? List.of() : List.copyOf(substeps);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.retryAllowed = retryAllowed;
        this.duplicate = duplicate;
        this.rebaseStateIndex = rebaseStateIndex;
    }

    /**
     * Creates an accepted commit result.
     */
    public static CommitModelsResult accepted(
            long requestId, String commitId, List<ModelCommitStepResult> substeps) {
        return new CommitModelsResult(
                requestId, commitId, substeps, List.of(), false,
                false, null);
    }

    /**
     * Creates a rejected conflict result. Rejected results are not retained under the commit idempotency key.
     */
    public static CommitModelsResult conflict(
            long requestId,
            String commitId,
            List<ModelCommitConflict> conflicts,
            boolean retryAllowed) {
        return new CommitModelsResult(
                requestId, commitId, List.of(), conflicts, retryAllowed,
                false, null);
    }

    /**
     * Returns whether the runtime committed the commit.
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
    public CommitModelsResult forRequest(long requestId) {
        return new CommitModelsResult(
                requestId, commitId, substeps, conflicts, retryAllowed,
                duplicate, rebaseStateIndex);
    }

    /**
     * Copies this accepted result for an idempotent duplicate request.
     */
    public CommitModelsResult asDuplicateForRequest(long requestId) {
        return new CommitModelsResult(
                requestId, commitId, substeps, conflicts, retryAllowed,
                true, rebaseStateIndex);
    }

    /**
     * Requests an internal apply-only rebase at the supplied current boundary.
     */
    public static CommitModelsResult rebase(
            long requestId,
            String commitId,
            List<ModelCommitConflict> changedModels,
            long rebaseStateIndex) {
        return new CommitModelsResult(
                requestId, commitId, List.of(), changedModels, true,
                false, rebaseStateIndex);
    }

    /**
     * Returns a target-ID-free metric representation.
     */
    @Override
    public Metric toMetric() {
        int targetCount = 0;
        for (ModelCommitStepResult substep : substeps) {
            targetCount += substep.getTargets().size();
        }
        return new Metric(
                substeps.size(), targetCount, conflicts.size(), retryAllowed,
                duplicate, isRebaseRequired(), timestamp);
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
        long timestamp;
    }
}
