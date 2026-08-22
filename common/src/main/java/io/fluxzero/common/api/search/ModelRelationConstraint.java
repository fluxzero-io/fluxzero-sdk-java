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

package io.fluxzero.common.api.search;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Current-state relationship constraint for independent model documents.
 * <p>
 * The {@link #query} selects related model documents. The runtime then follows temporal model relationships from those
 * matches towards the target documents of a {@link SearchModelDocuments} request. Traversal is always bounded and
 * never changes the ordinary document constraint semantics.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class ModelRelationConstraint {

    /**
     * Relationship direction as observed from each returned target document.
     */
    Direction direction;

    /**
     * Query that selects the related ancestor or descendant documents.
     */
    SearchQuery query;

    /**
     * Minimum number of relationship edges between a returned target and a related match.
     */
    @Builder.Default
    int minDepth = 1;

    /**
     * Maximum number of relationship edges between a returned target and a related match.
     */
    @Builder.Default
    int maxDepth = 1;

    /**
     * Optional allowed {@code @Parent(path = ...)} values. When non-empty, every traversed edge must match one.
     */
    @Singular("path")
    List<String> paths;

    /**
     * Maximum number of documents that the related query may match before graph search refuses the request.
     */
    @Builder.Default
    int maxRelatedModels = 10_000;

    /**
     * Maximum number of distinct models and depth states visited while traversing this constraint.
     */
    @Builder.Default
    int maxTraversedModels = 100_000;

    public ModelRelationConstraint(
            Direction direction,
            SearchQuery query,
            int minDepth,
            int maxDepth,
            List<String> paths,
            int maxRelatedModels,
            int maxTraversedModels) {
        this.direction = Objects.requireNonNull(
                direction, "Model relation direction");
        this.query = Objects.requireNonNull(
                query, "Related model query");
        if (minDepth < 1 || maxDepth < minDepth
            || maxDepth > 64) {
            throw new IllegalArgumentException(
                    "Model relation depth must satisfy 1 <= minDepth <= maxDepth <= 64");
        }
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        LinkedHashSet<String> normalizedPaths = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException(
                            "Model relation paths must not be blank");
                }
                normalizedPaths.add(path);
            }
        }
        this.paths = List.copyOf(normalizedPaths);
        if (maxRelatedModels < 1
            || maxRelatedModels > 100_000) {
            throw new IllegalArgumentException(
                    "maxRelatedModels must be between 1 and 100000");
        }
        if (maxTraversedModels < maxRelatedModels
            || maxTraversedModels > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxTraversedModels must be between maxRelatedModels and 1000000");
        }
        this.maxRelatedModels = maxRelatedModels;
        this.maxTraversedModels = maxTraversedModels;
    }

    /**
     * Relationship direction as observed from returned target documents.
     */
    public enum Direction {
        ANCESTOR,
        DESCENDANT
    }
}
