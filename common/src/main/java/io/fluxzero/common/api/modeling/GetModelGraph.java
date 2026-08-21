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
import java.util.Objects;

/**
 * Resolves one independent-model graph and optionally includes the first bounded page of every selected model stream.
 * <p>
 * The graph, heads, memberships, and payloads are all observed at one namespace-wide state boundary. Only child edges
 * are followed. When {@link #composableOnly} is set, relationships without an explicit graph path are not traversed.
 */
@Value
public class GetModelGraph extends Request {

    /**
     * Exact root model ID.
     */
    String rootId;

    /** Current, state, commit or event selector shared by every Model read. */
    ModelReadBoundary boundary;

    /**
     * Maximum number of child-edge levels below the root, or {@code -1} to follow every reachable level.
     */
    int maxDepth;

    /**
     * Maximum number of distinct model nodes, including the root, or {@code -1} for no caller-imposed limit.
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

    /**
     * Whether traversal is limited to relationships with an explicit graph-composition path.
     */
    boolean composableOnly;

    @ConstructorProperties({
            "rootId", "boundary", "maxDepth", "maxModels",
            "maxEventsPerModel", "maxBytes", "composableOnly"})
    public GetModelGraph(
            String rootId,
            ModelReadBoundary boundary,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            boolean composableOnly) {
        this.rootId = rootId;
        this.boundary = Objects.requireNonNull(boundary, "boundary").forRequest();
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxEventsPerModel = maxEventsPerModel;
        this.maxBytes = maxBytes;
        this.composableOnly = composableOnly;
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                boundary, maxDepth, maxModels,
                maxEventsPerModel, maxBytes, composableOnly);
    }

    @Value
    public static class Metric {
        ModelReadBoundary boundary;
        int maxDepth;
        int maxModels;
        int maxEventsPerModel;
        long maxBytes;
        boolean composableOnly;
    }
}
