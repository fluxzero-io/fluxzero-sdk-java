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

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Objects;

/**
 * Model heads and stream memberships observed at one pinned {@link #stateIndex}.
 * <p>
 * Event payloads are deduplicated by state index. Memberships reference that identity so loading several target
 * streams affected by the same original event does not multiply event-message bytes on the wire.
 */
@Value
public class GetModelEventsResult extends AbstractRequestResult {

    long requestId;
    long stateIndex;
    List<ModelEventPayload> payloads;
    List<ModelEventStream> streams;
    /**
     * Model state index for every selected message in {@link #payloadBlocks}.
     */
    long[] payloadStateIndices;
    /**
     * Persisted global-event blocks transported without Runtime-side message decoding or re-encoding.
     */
    List<ModelEventPayloadBlock> payloadBlocks;
    /**
     * Selected global event indices in {@link #payloadBlocks}, aligned with {@link #payloadStateIndices}.
     */
    long[] payloadEventIndices;
    /**
     * Persisted independent-model stream batches. The SDK expands only entries selected by this response's payloads
     * and the original stream requests.
     */
    List<ModelEventDataBlock> membershipBlocks;
    long timestamp = System.currentTimeMillis();

    public GetModelEventsResult(
            long requestId,
            long stateIndex,
            List<ModelEventPayload> payloads,
            List<ModelEventStream> streams) {
        this(
                requestId, stateIndex, payloads, streams,
                new long[0], List.of(), new long[0], List.of());
    }

    @ConstructorProperties({
            "requestId", "stateIndex", "payloads", "streams",
            "payloadStateIndices", "payloadBlocks", "payloadEventIndices", "membershipBlocks"})
    public GetModelEventsResult(
            long requestId,
            long stateIndex,
            List<ModelEventPayload> payloads,
            List<ModelEventStream> streams,
            long[] payloadStateIndices,
            List<ModelEventPayloadBlock> payloadBlocks,
            long[] payloadEventIndices,
            List<ModelEventDataBlock> membershipBlocks) {
        Objects.requireNonNull(payloadStateIndices, "payloadStateIndices");
        Objects.requireNonNull(payloadEventIndices, "payloadEventIndices");
        if (payloadStateIndices.length != payloadEventIndices.length) {
            throw new IllegalArgumentException(
                    "Payload state and event index mappings must have equal length");
        }
        Objects.requireNonNull(payloadBlocks, "payloadBlocks");
        if (payloadBlocks.isEmpty() != (payloadStateIndices.length == 0)) {
            throw new IllegalArgumentException(
                    "Payload blocks and their selected index mappings must be present together");
        }
        this.requestId = requestId;
        this.stateIndex = stateIndex;
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.payloadStateIndices = payloadStateIndices;
        this.payloadBlocks = payloadBlocks;
        this.payloadEventIndices = payloadEventIndices;
        this.membershipBlocks = Objects.requireNonNull(membershipBlocks, "membershipBlocks");
    }

    @Override
    public Metric toMetric() {
        int membershipCount = 0;
        long bytes = 0L;
        for (ModelEventStream stream : streams) {
            membershipCount += stream.getMemberships().size();
        }
        for (ModelEventPayload payload : payloads) {
            long eventBytes = payload.getEvent().getBytes();
            bytes = eventBytes > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + eventBytes;
        }
        for (ModelEventPayloadBlock block : payloadBlocks) {
            bytes = block.getData().length > Long.MAX_VALUE - bytes
                    ? Long.MAX_VALUE : bytes + block.getData().length;
        }
        for (ModelEventDataBlock block : membershipBlocks) {
            bytes = block.length() > Long.MAX_VALUE - bytes
                    ? Long.MAX_VALUE : bytes + block.length();
        }
        return new Metric(
                streams.size(), payloads.size() + payloadStateIndices.length,
                membershipCount, bytes, stateIndex, timestamp);
    }

    @Value
    public static class Metric {
        int streamCount;
        int payloadCount;
        int membershipCount;
        long bytes;
        long stateIndex;
        long timestamp;
    }
}
