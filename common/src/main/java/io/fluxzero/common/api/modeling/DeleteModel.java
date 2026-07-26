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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * Executes or resumes one explicit independent-model hard deletion.
 * <p>
 * {@link ModelDeletionCascade#DESCENDANTS} requires the fingerprint and bounds of a preceding
 * {@link ModelDeletionPlan}. The independently supplied deletion ID is the durable idempotency key.
 */
@Value
@Builder
@EqualsAndHashCode(callSuper = true)
public class DeleteModel extends Command {

    /**
     * Durable caller-selected idempotency key.
     */
    String deletionId;

    /**
     * Exact model ID to erase.
     */
    String modelId;

    /**
     * Required deletion scope.
     */
    ModelDeletionCascade cascade;

    /**
     * Complete-selection fingerprint from the dry run, or {@code null} for {@code NONE}.
     */
    String planFingerprint;

    /**
     * Maximum child-edge depth used by the dry run.
     */
    int maxDepth;

    /**
     * Maximum distinct model count used by the dry run.
     */
    int maxModels;

    /**
     * Requested delivery guarantee.
     */
    @Builder.Default
    Guarantee guarantee = Guarantee.STORED;

    @Override
    public String routingKey() {
        return modelId;
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                cascade, maxDepth, maxModels,
                guarantee);
    }

    @Value
    public static class Metric {
        ModelDeletionCascade cascade;
        int maxDepth;
        int maxModels;
        Guarantee guarantee;
    }
}
