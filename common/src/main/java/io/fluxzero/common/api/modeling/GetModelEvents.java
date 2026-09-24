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

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Objects;

/**
 * Batch-loads independent model stream memberships at one namespace-wide state boundary.
 * <p>
 * A current {@link #boundary} pins the committed boundary atomically with the model-head read. State, commit and event
 * selectors perform the corresponding as-of load. A stream request with
 * {@code maxSize == 0} requests only its head.
 * {@link #maxBytes} bounds the total deduplicated complete event messages selected by the runtime. The oldest single
 * event message is always allowed through to guarantee progress, even when it exceeds that bound.
 */
@Value
public class GetModelEvents extends Request {

    /**
     * Requested model streams in deterministic response order.
     */
    List<ModelEventStreamRequest> requests;

    /** Current, state, commit or event selector shared by every Model read. */
    ModelReadBoundary boundary;

    /**
     * Maximum total bytes of unique complete serialized event messages. Zero disables the byte limit.
     * <p>
     * Membership and head metadata are not counted. A response may exceed this value by one event message when the
     * oldest selected event is larger than the limit.
     */
    long maxBytes;

    @ConstructorProperties({"requests", "boundary", "maxBytes"})
    public GetModelEvents(
            List<ModelEventStreamRequest> requests,
            ModelReadBoundary boundary,
            long maxBytes) {
        this.requests = requests;
        this.boundary = Objects.requireNonNull(boundary, "boundary").forRequest();
        this.maxBytes = maxBytes;
    }

    GetModelEvents(
            long requestId,
            List<ModelEventStreamRequest> requests,
            ModelReadBoundary boundary,
            long maxBytes) {
        super(requestId);
        this.requests = requests;
        this.boundary = Objects.requireNonNull(boundary, "boundary").forRequest();
        this.maxBytes = maxBytes;
    }

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
        return new Metric(
                requests.size(), headOnlyCount, maximumEventCount,
                boundary, maxBytes);
    }

    @Value
    public static class Metric {
        int streamCount;
        int headOnlyCount;
        long maximumEventCount;
        ModelReadBoundary boundary;
        long maxBytes;
    }
}
