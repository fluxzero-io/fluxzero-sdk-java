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
 * Resolves one bounded independent-model relationship graph and optionally includes the first page of every selected
 * model stream.
 * <p>
 * The graph, heads, memberships, and payloads are all observed at one namespace-wide state boundary. Direction and
 * roots are query options of this one capability. When {@link #composableOnly} is set, descendant relationships
 * without an explicit graph path are not traversed.
 */
@Value
public class GetModelGraph extends Request {

    /**
     * Exact root model IDs.
     */
    List<String> modelIds;

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

    /** Relationship direction followed from the supplied roots. */
    Direction direction;

    /**
     * Whether traversal is limited to relationships with an explicit graph-composition path.
     */
    boolean composableOnly;

    @ConstructorProperties({
            "modelIds", "boundary", "maxDepth", "maxModels",
            "maxEventsPerModel", "maxBytes", "direction", "composableOnly"})
    public GetModelGraph(
            List<String> modelIds,
            ModelReadBoundary boundary,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            Direction direction,
            boolean composableOnly) {
        this.modelIds = modelIds == null ? null : List.copyOf(modelIds);
        ModelReadBoundary selectedBoundary = Objects.requireNonNull(boundary, "boundary");
        ModelReadBoundary requestBoundary = selectedBoundary.forRequest();
        this.boundary = selectedBoundary.before()
                ? requestBoundary.asBefore() : requestBoundary;
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxEventsPerModel = maxEventsPerModel;
        this.maxBytes = maxBytes;
        this.direction = Objects.requireNonNull(direction, "direction");
        this.composableOnly = composableOnly;
    }

    /** Creates the ordinary single-root descendant query used by public Graph loads. */
    public GetModelGraph(
            String rootId,
            ModelReadBoundary boundary,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            boolean composableOnly) {
        this(List.of(rootId), boundary, maxDepth, maxModels,
             maxEventsPerModel, maxBytes, Direction.DESCENDANTS, composableOnly);
    }

    /** Creates a multi-root ancestor query through the same graph capability. */
    public static GetModelGraph ancestors(
            List<String> modelIds,
            ModelReadBoundary boundary,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes) {
        return new GetModelGraph(
                modelIds, Objects.requireNonNull(boundary, "boundary").forRequest(), maxDepth, maxModels,
                maxEventsPerModel, maxBytes, Direction.ANCESTORS, false);
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                modelIds == null ? 0 : modelIds.size(), boundary, maxDepth, maxModels,
                maxEventsPerModel, maxBytes, direction, composableOnly);
    }

    public enum Direction { DESCENDANTS, ANCESTORS }

    @Value
    public static class Metric {
        int rootCount;
        ModelReadBoundary boundary;
        int maxDepth;
        int maxModels;
        int maxEventsPerModel;
        long maxBytes;
        Direction direction;
        boolean composableOnly;
    }
}
