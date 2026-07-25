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

import java.util.List;

/**
 * Atomically commits the ordered state transitions produced by one model action.
 * <p>
 * The request carries one persisted read boundary and the exact model IDs read while evaluating the action. Each
 * {@link ModelActionSubstep} contains its original event once, regardless of the number of targeted model streams.
 * Direct search documents are intentionally not part of this authoritative commit request.
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
     * Returns a payload-free representation for operational metrics.
     */
    @Override
    public Metric toMetric() {
        int targetCount = 0;
        int storedTargetCount = 0;
        int relationCount = 0;
        int publishedEventCount = 0;
        long bytes = 0L;
        for (ModelActionSubstep substep : substeps) {
            targetCount += substep.getTargets().size();
            storedTargetCount += substep.getStoredTargetCount();
            relationCount += substep.getRelationCount();
            publishedEventCount += substep.isPublishEvent() ? 1 : 0;
            bytes += substep.getBytes();
        }
        return new Metric(
                readModelIds.size(), substeps.size(), targetCount, storedTargetCount,
                relationCount, publishedEventCount, bytes);
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
        int publishedEventCount;
        long bytes;
    }
}
