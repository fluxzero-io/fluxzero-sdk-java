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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.Request;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Waits until one materialized graph projection has completed every root update originating at or before a committed
 * model state.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class AwaitModelGraphProjection extends Request {

    /**
     * Materialized graph collection identifying the projection.
     */
    String collection;

    /**
     * Committed model-action boundary that must be visible in all affected roots.
     */
    long stateIndex;

    /**
     * First state boundary occupied by the model action. The runtime uses the graph immediately before this boundary
     * to include the old side of moves that occurred before the action's final substep.
     */
    long firstStateIndex;

    /**
     * Models changed by the action. The runtime resolves their old and new projection roots before applying the fence.
     * Empty retains the collection-wide barrier used by direct administrative callers.
     */
    List<String> modelIds;

    /**
     * Creates a collection-wide projection barrier.
     */
    public AwaitModelGraphProjection(
            String collection,
            long stateIndex) {
        this(collection, stateIndex,
             stateIndex, List.of());
    }

    /**
     * Creates a projection barrier for the supplied affected models when the action consists of one state transition.
     */
    public AwaitModelGraphProjection(
            String collection,
            long stateIndex,
            Collection<String> modelIds) {
        this(collection, stateIndex,
             stateIndex, modelIds);
    }

    @JsonCreator
    public AwaitModelGraphProjection(
            @JsonProperty("collection")
            String collection,
            @JsonProperty("stateIndex")
            long stateIndex,
            @JsonProperty("firstStateIndex")
            Long firstStateIndex,
            @JsonProperty("modelIds")
            Collection<String> modelIds) {
        String value = Objects.requireNonNull(
                collection, "Graph projection collection");
        if (value.isBlank()
            || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "Graph projection collection must not be blank or have surrounding whitespace");
        }
        if (stateIndex < 0L) {
            throw new IllegalArgumentException(
                    "Graph projection state index must not be negative");
        }
        long first =
                firstStateIndex == null
                        ? stateIndex
                        : firstStateIndex;
        if (first < 0L
            || first > stateIndex) {
            throw new IllegalArgumentException(
                    "Graph projection first state index must be between zero and the final state index");
        }
        List<String> targets =
                modelIds == null
                        ? List.of()
                        : modelIds.stream()
                                .map(modelId ->
                                             Objects.requireNonNull(
                                                     modelId,
                                                     "Graph projection model ID"))
                                .distinct()
                                .toList();
        if (targets.size() > 10_000
            || targets.stream().anyMatch(
                    modelId ->
                            modelId.isBlank()
                            || !modelId.equals(
                                    modelId.trim()))) {
            throw new IllegalArgumentException(
                    "Graph projection model IDs must contain at most 10000 non-blank trimmed values");
        }
        this.collection = value;
        this.stateIndex = stateIndex;
        this.firstStateIndex = first;
        this.modelIds = targets;
    }
}
