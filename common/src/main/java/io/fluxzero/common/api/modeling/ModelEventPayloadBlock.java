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

import lombok.Value;

import java.beans.ConstructorProperties;

/**
 * One persisted global-event block transported without runtime-side decompression or re-encoding.
 *
 * <p>{@link #firstIndex} is the index of the first message and the fallback for messages without an embedded index.
 * Persisted storage blocks contain consecutive messages and may include unselected neighbors; a packed selection may
 * contain only selected messages with their global indices embedded in the messages themselves.</p>
 */
@Value
public class ModelEventPayloadBlock {

    long firstIndex;
    int messageCount;
    boolean compressed;
    byte[] data;

    @ConstructorProperties({"firstIndex", "messageCount", "compressed", "data"})
    public ModelEventPayloadBlock(
            long firstIndex, int messageCount, boolean compressed, byte[] data) {
        if (firstIndex < 0L) {
            throw new IllegalArgumentException("First event index must be non-negative");
        }
        if (messageCount <= 0) {
            throw new IllegalArgumentException("Message count must be positive");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Event block data must not be empty");
        }
        this.firstIndex = firstIndex;
        this.messageCount = messageCount;
        this.compressed = compressed;
        this.data = data;
    }
}
