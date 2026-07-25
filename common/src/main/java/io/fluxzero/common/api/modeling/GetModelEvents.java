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
import lombok.Value;

import java.util.List;

/**
 * Batch-loads independent model stream memberships at one namespace-wide state boundary.
 * <p>
 * A {@code null} {@link #maxStateIndex} pins the current committed boundary atomically with the model-head read.
 * Supplying a boundary performs an as-of load. A stream request with {@code maxSize == 0} requests only its head.
 */
@Value
public class GetModelEvents extends Request {

    /**
     * Requested model streams in deterministic response order.
     */
    List<ModelEventStreamRequest> requests;

    /**
     * Inclusive historical state boundary, or {@code null} to pin the current boundary.
     */
    Long maxStateIndex;

    @Override
    public Metric toMetric() {
        int headOnlyCount = 0;
        long maximumEventCount = 0L;
        for (ModelEventStreamRequest request : requests) {
            if (request.getMaxSize() == 0) {
                headOnlyCount++;
            } else {
                maximumEventCount += request.getMaxSize();
            }
        }
        return new Metric(requests.size(), headOnlyCount, maximumEventCount, maxStateIndex);
    }

    @Value
    public static class Metric {
        int streamCount;
        int headOnlyCount;
        long maximumEventCount;
        Long maxStateIndex;
    }
}
