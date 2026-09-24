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
 * Optional bounds for composing current independent-model documents into the documents returned by a search.
 * <p>
 * Composition follows only relationships with an explicit graph path. Every path represents a collection relative to
 * the parent document. Child documents are ordered by model ID and placed below a numeric list index.
 * By default composition follows the complete finite graph. Callers may set individual positive maxima as explicit
 * operational guardrails; {@link #UNBOUNDED} leaves a dimension unrestricted.
 */
@Value
@Builder
@Jacksonized
public class ModelGraphComposition {

    /**
     * Sentinel indicating that a composition dimension has no configured maximum.
     */
    public static final int UNBOUNDED = -1;

    /**
     * Maximum number of relationship levels included below each returned root.
     * {@link #UNBOUNDED} follows every reachable level.
     */
    @Builder.Default
    int maxDepth = UNBOUNDED;

    /**
     * Maximum number of distinct model IDs traversed for one live search, including candidate roots before graph-view
     * constraints are applied.
     * {@link #UNBOUNDED} imposes no model-count limit.
     */
    @Builder.Default
    int maxModels = UNBOUNDED;

    /**
     * Maximum number of child placements across one result page.
     * <p>
     * A shared DAG child can be placed more than once, so this bound is independent from {@link #maxModels}.
     * {@link #UNBOUNDED} imposes no placement-count limit.
     */
    @Builder.Default
    int maxPlacements = UNBOUNDED;

    /**
     * Maximum number of direct-document collections read for one result page.
     * {@link #UNBOUNDED} imposes no collection-count limit.
     */
    @Builder.Default
    int maxCollections = UNBOUNDED;

    /**
     * Maximum combined serialized allocation budget for one result page.
     * <p>
     * Both the direct source documents (including repeated DAG placements and path expansion) and the final composed
     * documents must fit this limit. The source check is intentionally conservative so composition can fail before
     * allocating a result larger than the configured budget.
     * {@link #UNBOUNDED} imposes no byte limit.
     */
    @Builder.Default
    long maxBytes = UNBOUNDED;

    public ModelGraphComposition(
            int maxDepth,
            int maxModels,
            int maxPlacements,
            int maxCollections,
            long maxBytes) {
        validateMaximum("maxDepth", maxDepth);
        validateMaximum("maxModels", maxModels);
        validateMaximum("maxPlacements", maxPlacements);
        validateMaximum("maxCollections", maxCollections);
        validateMaximum("maxBytes", maxBytes);
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxPlacements = maxPlacements;
        this.maxCollections = maxCollections;
        this.maxBytes = maxBytes;
    }

    private static void validateMaximum(String name, long maximum) {
        if (maximum != UNBOUNDED && maximum < 1L) {
            throw new IllegalArgumentException(
                    "Model graph composition %s must be positive or UNBOUNDED (-1)".formatted(name));
        }
    }
}
