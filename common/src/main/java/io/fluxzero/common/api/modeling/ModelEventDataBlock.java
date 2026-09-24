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

import java.util.Objects;

/**
 * Byte-range view containing one persisted model-event block.
 *
 * <p>A range can reference the websocket receive buffer directly. This avoids copying every compressed block before
 * the reconstruction decoder immediately expands it.</p>
 */
public record ModelEventDataBlock(
        byte[] data,
        int offset,
        int length) {

    public ModelEventDataBlock {
        Objects.requireNonNull(data, "data");
        if (offset < 0
            || length <= 0
            || offset > data.length - length) {
            throw new IllegalArgumentException(
                    "Invalid model-event data block range");
        }
    }

    public ModelEventDataBlock(byte[] data) {
        this(data, 0, Objects.requireNonNull(data, "data").length);
    }
}
