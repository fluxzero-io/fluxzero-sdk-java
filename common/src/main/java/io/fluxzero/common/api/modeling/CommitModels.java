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
import io.fluxzero.common.api.RetryAwareRequest;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.NonFinal;

import java.beans.ConstructorProperties;
import java.beans.Transient;
import java.util.List;

/**
 * Atomically commits the ordered state transitions produced by one model commit.
 * <p>
 * The request carries one persisted read boundary and the exact model IDs read while evaluating the commit. Each
 * {@link ModelCommitStep} contains its original event once, regardless of the number of targeted model streams.
 * Target transitions may also carry pre-serialized direct current-document mutations and snapshot candidates. The
 * runtime assigns their state-index fence, verifies snapshot boundaries, and completes them as part of the commit
 * workflow.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class CommitModels extends Command implements RetryAwareRequest {

    /**
     * Durable idempotency key shared by every substep in this commit.
     */
    String commitId;

    /**
     * Namespace-wide persisted state boundary at which commit evaluation began.
     */
    long readStateIndex;

    /**
     * Exact, deduplicated model ID strings read while evaluating the commit.
     */
    List<String> readModelIds;

    /**
     * Original events/state transitions in evaluation order.
     */
    List<ModelCommitStep> substeps;

    /**
     * Behavior when one of the commit-scoped model heads advanced after {@link #readStateIndex}.
     * A missing value is interpreted as {@link ModelConflictPolicy#ACCEPT}.
     */
    ModelConflictPolicy conflictPolicy;

    /**
     * Completion guarantee requested by the SDK.
     */
    Guarantee guarantee;

    /**
     * Whether this commit id may already have been stored. {@code false} is a proof supplied by the SDK for the first
     * transport attempt of a freshly handled source message; {@code true} requests a durable duplicate lookup.
     */
    @NonFinal
    volatile boolean possibleDuplicate;

    @ConstructorProperties({
            "commitId", "readStateIndex", "readModelIds", "substeps",
            "conflictPolicy", "guarantee", "possibleDuplicate"})
    public CommitModels(
            String commitId,
            long readStateIndex,
            List<String> readModelIds,
            List<ModelCommitStep> substeps,
            ModelConflictPolicy conflictPolicy,
            Guarantee guarantee,
            boolean possibleDuplicate) {
        this.commitId = commitId;
        this.readStateIndex = readStateIndex;
        this.readModelIds = readModelIds;
        this.substeps = substeps;
        this.conflictPolicy = conflictPolicy;
        this.guarantee = guarantee;
        this.possibleDuplicate = possibleDuplicate;
    }

    CommitModels(
            long requestId,
            String commitId,
            long readStateIndex,
            List<String> readModelIds,
            List<ModelCommitStep> substeps,
            ModelConflictPolicy conflictPolicy,
            Guarantee guarantee,
            boolean possibleDuplicate) {
        super(requestId);
        this.commitId = commitId;
        this.readStateIndex = readStateIndex;
        this.readModelIds = readModelIds;
        this.substeps = substeps;
        this.conflictPolicy = conflictPolicy;
        this.guarantee = guarantee;
        this.possibleDuplicate = possibleDuplicate;
    }

    @Override
    public void markPossibleDuplicate() {
        possibleDuplicate = true;
    }

    /**
     * Routes retries of the same commit consistently without choosing one target model as its owner.
     */
    @Override
    public String routingKey() {
        return commitId;
    }

    /**
     * Returns the complete serialized event-message bytes carried once by this commit.
     */
    @Transient
    public long getBytes() {
        long result = 0L;
        for (ModelCommitStep substep : substeps) {
            long bytes = substep.getBytes();
            result = bytes > Long.MAX_VALUE - result ? Long.MAX_VALUE : result + bytes;
        }
        return result;
    }

    /** Returns the sole target when this commit has one substep with one target, otherwise {@code null}. */
    @Transient
    public ModelCommitTarget singleTarget() {
        if (substeps == null || substeps.size() != 1) {
            return null;
        }
        List<ModelCommitTarget> targets = substeps.getFirst() == null
                ? null : substeps.getFirst().getTargets();
        return targets != null && targets.size() == 1 ? targets.getFirst() : null;
    }

    /**
     * Returns a payload-free representation for operational metrics.
     */
    @Override
    public Metric toMetric() {
        int targetCount = 0;
        int storedTargetCount = 0;
        int relationCount = 0;
        int directDocumentCount = 0;
        int snapshotCount = 0;
        int publishedEventCount = 0;
        long eventBytes = 0L;
        long directDocumentBytes = 0L;
        long snapshotBytes = 0L;
        for (ModelCommitStep substep : substeps) {
            targetCount += substep.getTargets().size();
            storedTargetCount += substep.getStoredTargetCount();
            relationCount += substep.getRelationCount();
            publishedEventCount += substep.isPublishEvent() ? 1 : 0;
            eventBytes = addSaturated(
                    eventBytes, substep.getBytes());
            for (ModelCommitTarget target :
                    substep.getTargets()) {
                if (target.getDocument() != null) {
                    directDocumentCount++;
                    directDocumentBytes = addSaturated(
                            directDocumentBytes,
                            target.getDocument()
                                    .getBytes());
                }
                if (target.getSnapshot() != null) {
                    snapshotCount++;
                    snapshotBytes = addSaturated(
                            snapshotBytes,
                            target.getSnapshot()
                                    .getBytes());
                }
            }
        }
        return new Metric(
                readModelIds.size(), substeps.size(), targetCount, storedTargetCount,
                relationCount, directDocumentCount,
                snapshotCount, publishedEventCount,
                eventBytes, directDocumentBytes,
                snapshotBytes,
                ModelConflictPolicy.resolve(conflictPolicy));
    }

    private static long addSaturated(
            long left, long right) {
        return right > Long.MAX_VALUE - left
                ? Long.MAX_VALUE : left + right;
    }

    /**
     * Payload-free model commit metrics.
     */
    @Value
    public static class Metric {
        int readModelCount;
        int substepCount;
        int targetCount;
        int storedTargetCount;
        int relationCount;
        int directDocumentCount;
        int snapshotCount;
        int publishedEventCount;
        long eventBytes;
        long directDocumentBytes;
        long snapshotBytes;
        ModelConflictPolicy conflictPolicy;

    }
}
