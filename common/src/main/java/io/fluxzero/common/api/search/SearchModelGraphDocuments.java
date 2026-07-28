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

import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Objects;

/**
 * Searches independent model documents and composes their current child graph into every returned root document.
 * <p>
 * This is deliberately a distinct wire action. A runtime that predates graph composition must reject the operation
 * instead of returning silently uncomposed documents.
 */
@Value
public class SearchModelGraphDocuments extends Request {

    /**
     * Search applied to the complete graph view after live composition, including constraints, sorting, pagination,
     * and path filtering. Only its root collections and explicit document IDs participate in bounded root discovery.
     */
    SearchDocuments search;

    /**
     * Optional relationship constraints combined using logical AND before graph composition.
     */
    List<ModelRelationConstraint> relations;

    /**
     * Bounds for current graph traversal and document composition.
     */
    ModelGraphComposition composition;

    /**
     * Canonical path replacements used by the configured materialized projection.
     */
    List<ModelGraphPathOverride> pathOverrides;

    public SearchModelGraphDocuments(
            SearchDocuments search,
            List<ModelRelationConstraint> relations,
            ModelGraphComposition composition) {
        this(search, relations, composition, List.of());
    }

    @ConstructorProperties({"search", "relations", "composition", "pathOverrides"})
    public SearchModelGraphDocuments(
            SearchDocuments search,
            List<ModelRelationConstraint> relations,
            ModelGraphComposition composition,
            List<ModelGraphPathOverride> pathOverrides) {
        this.search = Objects.requireNonNull(
                search, "Graph document search");
        if (relations != null
            && relations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Model relation constraints must not contain null");
        }
        this.relations = relations == null
                ? List.of() : List.copyOf(relations);
        if (this.relations.size() > 8) {
            throw new IllegalArgumentException(
                    "At most 8 model relation constraints are supported");
        }
        this.composition = Objects.requireNonNull(
                composition, "Model graph composition");
        if (pathOverrides != null
            && pathOverrides.stream()
                    .anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Model graph path overrides must not contain null");
        }
        this.pathOverrides = pathOverrides == null
                ? List.of() : List.copyOf(
                        pathOverrides);
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                relations.size(),
                relations.stream()
                        .mapToInt(ModelRelationConstraint::getMaxDepth)
                        .max().orElse(0),
                composition.getMaxDepth(),
                composition.getMaxModels(),
                composition.getMaxPlacements(),
                composition.getMaxCollections(),
                composition.getMaxBytes(),
                pathOverrides.size(),
                search.getMaxSize());
    }

    @Value
    public static class Metric {
        int relationCount;
        int relationMaxDepth;
        int compositionMaxDepth;
        int maxModels;
        int maxPlacements;
        int maxCollections;
        long maxBytes;
        int pathOverrideCount;
        Integer maxSize;
    }
}
