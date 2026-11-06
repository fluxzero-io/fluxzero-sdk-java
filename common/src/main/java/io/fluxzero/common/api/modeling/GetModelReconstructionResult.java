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

import lombok.Getter;
import java.util.Objects;
import lombok.AccessLevel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fluxzero.common.api.AbstractRequestResult;
import io.fluxzero.common.api.search.SerializedDocument;
import lombok.Value;

import java.beans.ConstructorProperties;

/** A serialized reconstruction seed and one canonical event stream at its pinned namespace boundary. */
@Value
public class GetModelReconstructionResult extends AbstractRequestResult {
    long requestId;
    /** Null when no eligible snapshot is available. Deserialization remains the SDK's responsibility. */
    SerializedDocument snapshot;
    /** Null only for a local unsupported-peer response, before anything is transmitted. */
    GetModelEventsResult events;
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    boolean locallyUnsupported;

    @ConstructorProperties({"requestId", "snapshot", "events"})
    public GetModelReconstructionResult(long requestId, SerializedDocument snapshot, GetModelEventsResult events) {
        this(requestId, snapshot, Objects.requireNonNull(events, "events"), false);
    }

    private GetModelReconstructionResult(long requestId, SerializedDocument snapshot, GetModelEventsResult events,
                                         boolean locallyUnsupported) {
        this.requestId = requestId;
        this.snapshot = snapshot;
        this.events = events;
        this.locallyUnsupported = locallyUnsupported;
    }

    /** Local-only fallback marker: never send this result over the wire. */
    public static GetModelReconstructionResult unsupportedPeer(long requestId) {
        return new GetModelReconstructionResult(requestId, null, null, true);
    }

    @JsonIgnore
    public boolean isSupported() { return !locallyUnsupported; }

    @Override public long getTimestamp() { return events == null ? 0L : events.getTimestamp(); }

    @Override public Object toMetric() { return events == null ? null : events.toMetric(); }
}
