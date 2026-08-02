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
     * Consecutive persisted MessagePack event payloads. Each decoded message corresponds by ordinal to
     * {@link #compactPayloadStateIndices}.
     */
    byte[] compactPayloads;
    /**
     * Model state index for each message in {@link #compactPayloads}.
     */
    long[] compactPayloadStateIndices;
    /**
     * Persisted global-event blocks used instead of {@link #compactPayloads} when transporting the original storage
     * representation is cheaper.
     */
    List<ModelEventPayloadBlock> compactPayloadBlocks;
    /**
     * Selected global event indices in {@link #compactPayloadBlocks}, aligned with
     * {@link #compactPayloadStateIndices}.
     */
    long[] compactPayloadEventIndices;
    /**
     * Persisted independent-model stream batches. The SDK expands only entries selected by this response's payloads
     * and the original stream requests.
     */
    List<ModelEventDataBlock> compactMembershipBlocks;
    long timestamp = System.currentTimeMillis();

    public GetModelEventsResult(
            long requestId,
            long stateIndex,
            List<ModelEventPayload> payloads,
            List<ModelEventStream> streams) {
        this(requestId, stateIndex, payloads, streams, null, null, null, null, null);
    }

    public GetModelEventsResult(
            long requestId,
            long stateIndex,
            List<ModelEventPayload> payloads,
            List<ModelEventStream> streams,
            byte[] compactPayloads,
            long[] compactPayloadStateIndices) {
        this(requestId, stateIndex, payloads, streams, compactPayloads,
             compactPayloadStateIndices, null, null, null);
    }

    @ConstructorProperties({
            "requestId", "stateIndex", "payloads", "streams",
            "compactPayloads", "compactPayloadStateIndices",
            "compactPayloadBlocks", "compactPayloadEventIndices",
            "compactMembershipBlocks"})
    public GetModelEventsResult(
            long requestId,
            long stateIndex,
            List<ModelEventPayload> payloads,
            List<ModelEventStream> streams,
            byte[] compactPayloads,
            long[] compactPayloadStateIndices,
            List<ModelEventPayloadBlock> compactPayloadBlocks,
            long[] compactPayloadEventIndices,
            List<ModelEventDataBlock> compactMembershipBlocks) {
        this.requestId = requestId;
        this.stateIndex = stateIndex;
        this.payloads = payloads;
        this.streams = streams;
        this.compactPayloads = compactPayloads;
        this.compactPayloadStateIndices = compactPayloadStateIndices;
        this.compactPayloadBlocks = compactPayloadBlocks;
        this.compactPayloadEventIndices = compactPayloadEventIndices;
        this.compactMembershipBlocks = compactMembershipBlocks;
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
        int compactPayloadCount =
                compactPayloadStateIndices == null ? 0 : compactPayloadStateIndices.length;
        if (compactPayloads != null) {
            bytes = compactPayloads.length > Long.MAX_VALUE - bytes
                    ? Long.MAX_VALUE : bytes + compactPayloads.length;
        }
        if (compactPayloadBlocks != null) {
            for (ModelEventPayloadBlock block : compactPayloadBlocks) {
                bytes = block.getData().length > Long.MAX_VALUE - bytes
                        ? Long.MAX_VALUE : bytes + block.getData().length;
            }
        }
        if (compactMembershipBlocks != null) {
            for (ModelEventDataBlock block : compactMembershipBlocks) {
                bytes = block.length() > Long.MAX_VALUE - bytes
                        ? Long.MAX_VALUE : bytes + block.length();
            }
        }
        return new Metric(
                streams.size(), payloads.size() + compactPayloadCount,
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
