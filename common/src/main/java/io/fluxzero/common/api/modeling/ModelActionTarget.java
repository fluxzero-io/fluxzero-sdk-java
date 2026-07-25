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

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Persistence and lifecycle effects of one original event on one independently stored model.
 */
@Value
@Builder(toBuilder = true)
public class ModelActionTarget {

    /**
     * Exact persisted identity, equal to the model ID's {@code toString()} value.
     */
    String modelId;

    /**
     * Whether the substep event receives a membership in this model's stream.
     */
    boolean storeEvent;

    /**
     * Whether the returned model state becomes the current logical state.
     * <p>
     * This may be true while {@link #storeEvent} is false for an explicitly non-stored state transition. The runtime
     * then marks the model's event history incomplete.
     */
    boolean updateState;

    /**
     * Whether the updated current state is a logical deletion.
     */
    boolean delete;

    /**
     * Complete desired outgoing parent relationships after this target transition.
     * <p>
     * The runtime reconciles these against its actual current relationships; they are not blindly applied as
     * client-calculated previous/current deltas.
     */
    List<ModelRelationship> relationships;
}
