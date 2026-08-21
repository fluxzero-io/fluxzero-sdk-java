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

import io.fluxzero.common.api.Request;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the bounded ancestor graph of one or more independent models and optionally includes the first bounded page
 * of every selected model stream.
 * <p>
 * Roots, ancestor edges, heads, memberships, and payloads are observed at one namespace-wide state boundary.
 */
@Value
public class GetModelAncestors extends Request {

    /**
     * Exact IDs from which parent edges are followed.
     */
    List<String> modelIds;

    /** Current, state, commit or event selector shared by every Model read. */
    ModelReadBoundary boundary;

    /**
     * Maximum number of parent-edge levels above the roots.
     */
    int maxDepth;

    /**
     * Maximum number of distinct model nodes, including the supplied roots.
     */
    int maxModels;

    /**
     * Maximum first-page membership count per selected model. Zero returns heads and edges only.
     */
    int maxEventsPerModel;

    /**
     * Maximum total bytes of deduplicated event payloads. Zero disables the byte limit.
     */
    long maxBytes;

    @ConstructorProperties({
            "modelIds", "boundary", "maxDepth", "maxModels",
            "maxEventsPerModel", "maxBytes"})
    public GetModelAncestors(
            List<String> modelIds,
            ModelReadBoundary boundary,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes) {
        this.modelIds = modelIds == null ? null : List.copyOf(modelIds);
        this.boundary = Objects.requireNonNull(boundary, "boundary").forRequest();
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxEventsPerModel = maxEventsPerModel;
        this.maxBytes = maxBytes;
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                modelIds == null ? 0 : modelIds.size(),
                boundary, maxDepth, maxModels,
                maxEventsPerModel, maxBytes);
    }

    @Value
    public static class Metric {
        int rootCount;
        ModelReadBoundary boundary;
        int maxDepth;
        int maxModels;
        int maxEventsPerModel;
        long maxBytes;
    }
}
