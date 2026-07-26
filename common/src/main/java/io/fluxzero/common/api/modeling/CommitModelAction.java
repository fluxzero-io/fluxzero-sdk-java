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

import java.beans.Transient;
import java.util.List;

/**
 * Atomically commits the ordered state transitions produced by one model action.
 * <p>
 * The request carries one persisted read boundary and the exact model IDs read while evaluating the action. Each
 * {@link ModelActionSubstep} contains its original event once, regardless of the number of targeted model streams.
 * Target transitions may also carry pre-serialized direct current-document mutations and snapshot candidates. The
 * runtime assigns their state-index fence, verifies snapshot boundaries, and completes them as part of the action
 * workflow.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class CommitModelAction extends Command {

    /**
     * Durable idempotency key shared by every substep in this action.
     */
    String actionId;

    /**
     * Namespace-wide persisted state boundary at which action evaluation began.
     */
    long readStateIndex;

    /**
     * Exact, deduplicated model ID strings read while evaluating the action.
     */
    List<String> readModelIds;

    /**
     * Original events/state transitions in evaluation order.
     */
    List<ModelActionSubstep> substeps;

    /**
     * Behavior when one of the action-scoped model heads advanced after {@link #readStateIndex}.
     * A missing value is interpreted as {@link ModelConflictPolicy#ACCEPT}.
     */
    ModelConflictPolicy conflictPolicy;

    /**
     * Completion guarantee requested by the SDK.
     */
    Guarantee guarantee;

    /**
     * Routes retries of the same action consistently without choosing one target model as its owner.
     */
    @Override
    public String routingKey() {
        return actionId;
    }

    /**
     * Returns the logical event payload bytes carried once by this action.
     */
    @Transient
    public long getBytes() {
        long result = 0L;
        for (ModelActionSubstep substep : substeps) {
            long bytes = substep.getBytes();
            result = bytes > Long.MAX_VALUE - result ? Long.MAX_VALUE : result + bytes;
        }
        return result;
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
        for (ModelActionSubstep substep : substeps) {
            targetCount += substep.getTargets().size();
            storedTargetCount += substep.getStoredTargetCount();
            relationCount += substep.getRelationCount();
            publishedEventCount += substep.isPublishEvent() ? 1 : 0;
            eventBytes = addSaturated(
                    eventBytes, substep.getBytes());
            for (ModelActionTarget target :
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
     * Payload-free model action metrics.
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

        /**
         * Legacy name for {@link #getEventBytes()}.
         */
        @Deprecated
        public long getBytes() {
            return eventBytes;
        }
    }
}
