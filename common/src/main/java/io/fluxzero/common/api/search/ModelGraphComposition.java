/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api.search;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Bounds for composing current independent-model documents into the documents returned by a search.
 * <p>
 * Composition follows only relationships with an explicit graph path. Every path represents a collection relative to
 * the parent document. Child documents are ordered by model ID and placed below a numeric list index.
 */
@Value
@Builder
@Jacksonized
public class ModelGraphComposition {

    /**
     * Maximum number of relationship levels included below each returned root.
     */
    @Builder.Default
    int maxDepth = 16;

    /**
     * Maximum number of distinct model IDs traversed for one result page, including the roots.
     */
    @Builder.Default
    int maxModels = 10_000;

    /**
     * Maximum number of child placements across one result page.
     * <p>
     * A shared DAG child can be placed more than once, so this bound is independent from {@link #maxModels}.
     */
    @Builder.Default
    int maxPlacements = 25_000;

    /**
     * Maximum number of direct-document collections read for one result page.
     */
    @Builder.Default
    int maxCollections = 128;

    /**
     * Maximum combined serialized allocation budget for one result page.
     * <p>
     * Both the direct source documents (including repeated DAG placements and path expansion) and the final composed
     * documents must fit this limit. The source check is intentionally conservative so composition can fail before
     * allocating a result larger than the configured budget.
     */
    @Builder.Default
    long maxBytes = 64L * 1024L * 1024L;

    public ModelGraphComposition(
            int maxDepth,
            int maxModels,
            int maxPlacements,
            int maxCollections,
            long maxBytes) {
        if (maxDepth < 1 || maxDepth > 64) {
            throw new IllegalArgumentException(
                    "Model graph composition maxDepth must be between 1 and 64");
        }
        if (maxModels < 1 || maxModels > 100_000) {
            throw new IllegalArgumentException(
                    "Model graph composition maxModels must be between 1 and 100000");
        }
        if (maxPlacements < 1 || maxPlacements > 1_000_000) {
            throw new IllegalArgumentException(
                    "Model graph composition maxPlacements must be between 1 and 1000000");
        }
        if (maxCollections < 1 || maxCollections > 10_000) {
            throw new IllegalArgumentException(
                    "Model graph composition maxCollections must be between 1 and 10000");
        }
        if (maxBytes < 1L || maxBytes > 1024L * 1024L * 1024L) {
            throw new IllegalArgumentException(
                    "Model graph composition maxBytes must be between 1 and 1073741824");
        }
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxPlacements = maxPlacements;
        this.maxCollections = maxCollections;
        this.maxBytes = maxBytes;
    }
}
