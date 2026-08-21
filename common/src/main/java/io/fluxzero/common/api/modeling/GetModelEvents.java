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

/**
 * Batch-loads independent model stream memberships at one namespace-wide state boundary.
 * <p>
 * A {@code null} {@link #maxStateIndex} pins the current committed boundary atomically with the model-head read.
 * Supplying a state boundary performs an as-of load. Alternatively, {@link #boundaryCommitId} and
 * {@link #boundarySubstep} resolve the state boundary of one published model event through its already persisted
 * commit result inside the same runtime request. The two boundary forms are mutually exclusive. A stream request with
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

    /**
     * Inclusive historical state boundary, or {@code null} to pin the current boundary.
     */
    Long maxStateIndex;

    /**
     * Durable model commit whose persisted result contains the exact boundary, or {@code null}.
     */
    String boundaryCommitId;

    /**
     * Ordered substep within {@link #boundaryCommitId}, or {@code null}.
     */
    Integer boundarySubstep;

    /**
     * Global event-log index of the published model event whose state boundary is requested.
     */
    Long boundaryEventIndex;

    /**
     * Maximum total bytes of unique complete serialized event messages. Zero disables the byte limit.
     * <p>
     * Membership and head metadata are not counted. A response may exceed this value by one event message when the
     * oldest selected event is larger than the limit.
     */
    long maxBytes;

    public GetModelEvents(
            List<ModelEventStreamRequest> requests,
            Long maxStateIndex,
            long maxBytes) {
        this(
                requests, maxStateIndex,
                null, null, null, maxBytes);
    }

    public GetModelEvents(
            List<ModelEventStreamRequest> requests,
            Long maxStateIndex,
            String boundaryCommitId,
            Integer boundarySubstep,
            long maxBytes) {
        this.requests = requests;
        this.maxStateIndex = maxStateIndex;
        this.boundaryCommitId = boundaryCommitId;
        this.boundarySubstep = boundarySubstep;
        this.boundaryEventIndex = null;
        this.maxBytes = maxBytes;
    }

    @ConstructorProperties({
            "requests", "maxStateIndex", "boundaryCommitId",
            "boundarySubstep", "boundaryEventIndex", "maxBytes"})
    public GetModelEvents(
            List<ModelEventStreamRequest> requests,
            Long maxStateIndex,
            String boundaryCommitId,
            Integer boundarySubstep,
            Long boundaryEventIndex,
            long maxBytes) {
        this.requests = requests;
        this.maxStateIndex = maxStateIndex;
        this.boundaryCommitId = boundaryCommitId;
        this.boundarySubstep = boundarySubstep;
        this.boundaryEventIndex = boundaryEventIndex;
        this.maxBytes = maxBytes;
    }

    GetModelEvents(
            long requestId,
            List<ModelEventStreamRequest> requests,
            Long maxStateIndex,
            String boundaryCommitId,
            Integer boundarySubstep,
            Long boundaryEventIndex,
            long maxBytes) {
        super(requestId);
        this.requests = requests;
        this.maxStateIndex = maxStateIndex;
        this.boundaryCommitId = boundaryCommitId;
        this.boundarySubstep = boundarySubstep;
        this.boundaryEventIndex = boundaryEventIndex;
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
                maxStateIndex, boundaryCommitId,
                boundarySubstep, boundaryEventIndex, maxBytes);
    }

    @Value
    public static class Metric {
        int streamCount;
        int headOnlyCount;
        long maximumEventCount;
        Long maxStateIndex;
        String boundaryCommitId;
        Integer boundarySubstep;
        Long boundaryEventIndex;
        long maxBytes;
    }
}
