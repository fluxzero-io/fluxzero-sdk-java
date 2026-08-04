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

import io.fluxzero.common.api.Request;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * Creates a bounded, non-mutating hard-deletion plan.
 * <p>
 * Descendant execution must present the returned fingerprint. A changed model closure invalidates the plan.
 */
@Value
@AllArgsConstructor
public class PlanModelDeletion extends Request {
    public static final int DEFAULT_MAX_DEPTH = 1_024;
    public static final int DEFAULT_MAX_MODELS = 100_000;
    public static final int DEFAULT_SAMPLE_SIZE = 100;

    /**
     * Exact root model ID.
     */
    String modelId;

    /**
     * Required deletion scope.
     */
    ModelDeletionCascade cascade;

    /**
     * Maximum number of child-edge levels to inspect.
     */
    int maxDepth;

    /**
     * Maximum number of distinct selected models.
     */
    int maxModels;

    /**
     * Maximum number of deterministic sample IDs returned in the plan.
     */
    int maxSampleSize;

    public PlanModelDeletion(
            String modelId,
            ModelDeletionCascade cascade) {
        this(modelId, cascade, DEFAULT_MAX_DEPTH,
             DEFAULT_MAX_MODELS, DEFAULT_SAMPLE_SIZE);
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                cascade, maxDepth, maxModels,
                maxSampleSize);
    }

    @Value
    public static class Metric {
        ModelDeletionCascade cascade;
        int maxDepth;
        int maxModels;
        int maxSampleSize;
    }
}
