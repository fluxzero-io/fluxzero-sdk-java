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

import com.fasterxml.jackson.annotation.JsonInclude;
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
     * Stable serialized model type descriptor.
     * <p>
     * This is coordination metadata for untyped and graph loads; it is not part of model identity.
     */
    String modelType;

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
     * Optional direct current-document mutation produced by this transition.
     * <p>
     * The runtime applies it with the same assigned state index as this target. A missing mutation means that the model
     * is not directly searchable; a mutation with a {@code null} document means delete.
     */
    ModelDocumentMutation document;

    /**
     * Optional snapshot value produced when this transition is predicted to reach the model's configured snapshot
     * period. The runtime verifies the candidate against its assigned target sequence before storing it.
     */
    ModelSnapshotMutation snapshot;

    /**
     * Whether {@link #relationships} intentionally replaces the model's outgoing parent relationships.
     * <p>
     * A regular transition whose {@code @ParentId} values did not change leaves this false. This prevents such a
     * transition from reopening relationships which the runtime closed because a parent was deleted. Logical model
     * deletion always sets this to true.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    boolean updateRelationships;

    /**
     * Complete desired outgoing parent relationships when {@link #updateRelationships} is true.
     * <p>
     * The runtime reconciles these against its actual current relationships; they are not blindly applied as
     * client-calculated previous/current deltas. The list is empty when relationships are not updated.
     */
    List<ModelRelationship> relationships;
}
