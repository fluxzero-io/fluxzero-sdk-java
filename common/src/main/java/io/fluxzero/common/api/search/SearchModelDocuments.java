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

import io.fluxzero.common.api.Request;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Objects;

/**
 * Searches current independent model documents subject to bounded relationship constraints.
 * <p>
 * This is a distinct request type so runtimes without graph-search support reject it instead of silently ignoring the
 * relationship constraints.
 */
@Value
public class SearchModelDocuments extends Request {

    /**
     * Ordinary target-document search, including sorting, pagination, and path filtering.
     */
    SearchDocuments search;

    /**
     * Relationship constraints combined using logical AND.
     */
    List<ModelRelationConstraint> relations;

    @ConstructorProperties({"search", "relations"})
    public SearchModelDocuments(
            SearchDocuments search,
            List<ModelRelationConstraint> relations) {
        this.search = Objects.requireNonNull(
                search, "Target document search");
        if (relations != null
            && relations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Model relation constraints must not contain null");
        }
        this.relations = relations == null
                ? List.of() : List.copyOf(relations);
        if (this.relations.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one model relation constraint is required");
        }
        if (this.relations.size() > 8) {
            throw new IllegalArgumentException(
                    "At most 8 model relation constraints are supported");
        }
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                relations.size(),
                relations.stream()
                        .mapToInt(ModelRelationConstraint::getMaxDepth)
                        .max().orElse(0),
                search.getMaxSize());
    }

    @Value
    public static class Metric {
        int relationCount;
        int maxDepth;
        Integer maxSize;
    }
}
