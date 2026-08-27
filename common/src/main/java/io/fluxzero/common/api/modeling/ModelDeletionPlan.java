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

import java.util.List;

/**
 * Bounded dry-run result for one explicit independent-model hard deletion.
 * <p>
 * The sample is diagnostic only. {@link #fingerprint} commits to the complete ordered selection and is required when
 * executing a descendant cascade.
 */
@Value
public class ModelDeletionPlan extends AbstractRequestResult {

    /**
     * Correlated request ID.
     */
    long requestId;

    /**
     * Exact requested root ID.
     */
    String modelId;

    /**
     * Planned deletion scope.
     */
    ModelDeletionCascade cascade;

    /**
     * Maximum child-edge depth used to compute the complete selection.
     */
    int maxDepth;

    /**
     * Maximum distinct model count used to compute the complete selection.
     */
    int maxModels;

    /**
     * Model state boundary at which the plan was observed.
     */
    long stateIndex;

    /**
     * Stable SHA-256 fingerprint of the complete selected ID set and cascade scope.
     */
    String fingerprint;

    /**
     * Number of distinct selected models, including the root.
     */
    int modelCount;

    /**
     * Number of selected descendants with at least one current parent outside the selected set.
     */
    int externallySharedModelCount;

    /**
     * Number of stored event memberships in selected model streams.
     */
    long storedEventMembershipCount;

    /**
     * Number of distinct globally published events referenced by selected model streams.
     */
    long publishedEventCount;

    /**
     * Deterministic bounded prefix of selected IDs for operator review.
     */
    List<String> sampleModelIds;

    /**
     * Observation timestamp.
     */
    long timestamp = System.currentTimeMillis();

    @Override
    public Metric toMetric() {
        return new Metric(
                cascade, maxDepth, maxModels,
                stateIndex, modelCount,
                externallySharedModelCount,
                storedEventMembershipCount,
                publishedEventCount,
                sampleModelIds.size(), timestamp);
    }

    @Value
    public static class Metric {
        ModelDeletionCascade cascade;
        int maxDepth;
        int maxModels;
        long stateIndex;
        int modelCount;
        int externallySharedModelCount;
        long storedEventMembershipCount;
        long publishedEventCount;
        int sampleSize;
        long timestamp;
    }
}
