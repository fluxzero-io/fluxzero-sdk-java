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

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * A relation starts either from related model documents selected by {@link #query} or from
 * {@link #relatedModelIds exact persisted model identities}. The runtime then follows temporal model relationships
 * from those IDs towards the target documents of a {@link SearchModelDocuments} request. Traversal is always bounded
 * and never changes the ordinary document constraint semantics.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class ModelRelationConstraint {

    /**
     * Relationship direction as observed from each returned target document.
     */
    RelationDirection direction;

    /**
     * Query that selects the related ancestor or descendant documents.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    SearchQuery query;

    /**
     * Exact persisted identities used as relation starting points instead of a related-document query.
     * <p>
     * This form does not require the related models to maintain current-state documents.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Singular("relatedModelId")
    List<String> relatedModelIds;

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
     * Optional allowed {@code @Parent(pathInParent = ...)} values. When non-empty, every traversed edge must match one.
     */
    @Singular("path")
    List<String> paths;

    /**
     * Maximum number of documents that a related query may match, or exact related IDs that may be supplied, before
     * graph search refuses the request.
     */
    @Builder.Default
    int maxRelatedModels = 10_000;

    /**
     * Maximum number of distinct models and depth states visited while traversing this constraint.
     */
    @Builder.Default
    int maxTraversedModels = 100_000;

    public ModelRelationConstraint(
            RelationDirection direction,
            SearchQuery query,
            List<String> relatedModelIds,
            int minDepth,
            int maxDepth,
            List<String> paths,
            int maxRelatedModels,
            int maxTraversedModels) {
        this.direction = Objects.requireNonNull(
                direction, "Model relation direction");
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        if (relatedModelIds != null) {
            for (String modelId : relatedModelIds) {
                if (modelId == null || modelId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Related model IDs must not be blank");
                }
                normalizedIds.add(modelId);
            }
        }
        if ((query == null) == normalizedIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Supply either a related model query or exact related model IDs");
        }
        this.query = query;
        this.relatedModelIds = List.copyOf(normalizedIds);
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
        if (this.relatedModelIds.size() > maxRelatedModels) {
            throw new IllegalArgumentException(
                    "Exact related model IDs exceed maxRelatedModels " + maxRelatedModels);
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
    public enum RelationDirection {
        ANCESTOR,
        DESCENDANT
    }
}
