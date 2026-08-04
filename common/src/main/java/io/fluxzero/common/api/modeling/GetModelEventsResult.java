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
 * Model heads and stream memberships observed at one pinned {@link #stateIndex}.
 * <p>
 * Event payloads are deduplicated by state index. Memberships reference that identity so loading several target
 * streams affected by the same original event does not multiply payload bytes on the wire.
 */
@Value
public class GetModelEventsResult extends AbstractRequestResult {

    long requestId;
    long stateIndex;
    List<ModelEventPayload> payloads;
    List<ModelEventStream> streams;
    long timestamp = System.currentTimeMillis();

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
        return new Metric(streams.size(), payloads.size(), membershipCount, bytes, stateIndex, timestamp);
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
