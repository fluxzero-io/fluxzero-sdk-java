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
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.beans.ConstructorProperties;

/**
 * Long-polls committed independent-model updates after a client-controlled state cursor.
 * <p>
 * The runtime may retain the request for {@link #maxWaitMillis} while no newer commit substep is visible. Empty
 * responses are heartbeats and leave the supplied cursor unchanged.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class TrackModelUpdates extends Request {

    /**
     * Last completely processed model state index, or {@code -1} for the beginning.
     */
    long lastStateIndex;

    /**
     * Maximum number of commit-substep updates returned in one response.
     */
    int maxSize;

    /**
     * Maximum time for which the runtime may retain an empty long-poll request.
     */
    long maxWaitMillis;

    /**
     * Maximum estimated metadata bytes returned by one response. The oldest single update is always returned to
     * guarantee cursor progress. Zero disables the byte limit.
     */
    long maxBytes;

    /**
     * Creates a bounded model-update long poll.
     */
    public TrackModelUpdates(
            long lastStateIndex,
            int maxSize,
            long maxWaitMillis) {
        this(
                lastStateIndex, maxSize,
                maxWaitMillis,
                8L * 1_024L * 1_024L);
    }

    /**
     * Creates a bounded model-update long poll with an explicit response metadata-byte limit.
     */
    @ConstructorProperties({
            "lastStateIndex", "maxSize",
            "maxWaitMillis", "maxBytes"})
    public TrackModelUpdates(
            long lastStateIndex,
            int maxSize,
            long maxWaitMillis,
            long maxBytes) {
        if (lastStateIndex < -1L) {
            throw new IllegalArgumentException(
                    "Last model state index must be at least -1");
        }
        if (maxSize <= 0 || maxSize > 65_536) {
            throw new IllegalArgumentException(
                    "Model update batch size must be between 1 and 65536");
        }
        if (maxWaitMillis < 0L || maxWaitMillis > 60_000L) {
            throw new IllegalArgumentException(
                    "Model update wait time must be between 0 and 60000 milliseconds");
        }
        if (maxBytes < 0L
            || maxBytes
               > 64L * 1_024L * 1_024L) {
            throw new IllegalArgumentException(
                    "Model update maxBytes must be between 0 and 67108864");
        }
        this.lastStateIndex = lastStateIndex;
        this.maxSize = maxSize;
        this.maxWaitMillis = maxWaitMillis;
        this.maxBytes = maxBytes;
    }
}
