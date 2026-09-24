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

/**
 * Completed result of an idempotent independent-model hard deletion.
 */
@Value
public class ModelDeletionResult extends AbstractRequestResult {

    /**
     * Correlated request ID.
     */
    long requestId;

    /**
     * Durable deletion idempotency key.
     */
    String deletionId;

    /**
     * Executed cascade scope.
     */
    ModelDeletionCascade cascade;

    /**
     * State index assigned to the lifecycle operation.
     */
    long stateIndex;

    /**
     * Number of distinct selected models confirmed absent when the operation completed.
     * This includes a previously erased root when its protected descendants are erased later.
     */
    int deletedModelCount;

    /**
     * Number of model-stream memberships physically erased.
     */
    long deletedEventMembershipCount;

    /**
     * Number of distinct referenced published events retained in the global event log.
     */
    long retainedPublishedEventCount;

    /**
     * Whether an earlier completed result was returned for the same deletion ID.
     */
    boolean duplicate;

    /**
     * Result creation timestamp.
     */
    long timestamp = System.currentTimeMillis();

    /**
     * Returns this durable result correlated to a retrying request.
     */
    public ModelDeletionResult forRequest(
            long requestId, boolean duplicate) {
        return new ModelDeletionResult(
                requestId, deletionId, cascade,
                stateIndex, deletedModelCount,
                deletedEventMembershipCount,
                retainedPublishedEventCount,
                duplicate);
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                cascade, stateIndex,
                deletedModelCount,
                deletedEventMembershipCount,
                retainedPublishedEventCount,
                duplicate, timestamp);
    }

    @Value
    public static class Metric {
        ModelDeletionCascade cascade;
        long stateIndex;
        int deletedModelCount;
        long deletedEventMembershipCount;
        long retainedPublishedEventCount;
        boolean duplicate;
        long timestamp;
    }
}
