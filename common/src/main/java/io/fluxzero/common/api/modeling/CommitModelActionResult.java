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
 * Durable result of an accepted {@link CommitModelAction}.
 * <p>
 * A duplicate {@code actionId} returns the same logical result with the new request ID used for response correlation.
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
     * Timestamp at which this result object was created.
     */
    long timestamp = System.currentTimeMillis();

    /**
     * Returns a target-ID-free metric representation.
     */
    @Override
    public Metric toMetric() {
        int targetCount = 0;
        for (ModelActionSubstepResult substep : substeps) {
            targetCount += substep.getTargets().size();
        }
        return new Metric(substeps.size(), targetCount, timestamp);
    }

    /**
     * Payload-free commit-result metrics.
     */
    @Value
    public static class Metric {
        int substepCount;
        int targetCount;
        long timestamp;
    }
}
