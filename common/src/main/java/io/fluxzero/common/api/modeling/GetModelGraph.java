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

    /**
     * Inclusive historical state boundary, or {@code null} to pin the current boundary.
     */
    Long maxStateIndex;

    /**
     * Durable model commit whose persisted result contains the exact boundary, or {@code null}.
     */
    String boundaryCommitId;

    /**
     * Ordered substep within {@link #boundaryCommitId}, or {@code null}.
     */
    Integer boundarySubstep;

    /**
     * Global event-log index of the published model event whose state boundary is requested.
     */
    Long boundaryEventIndex;

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

    public GetModelGraph(
            String rootId,
            Long maxStateIndex,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            boolean composableOnly) {
        this(
                rootId, maxStateIndex, null, null, null,
                maxDepth, maxModels,
                maxEventsPerModel, maxBytes, composableOnly);
    }

    public GetModelGraph(
            String rootId,
            Long maxStateIndex,
            String boundaryCommitId,
            Integer boundarySubstep,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            boolean composableOnly) {
        this.rootId = rootId;
        this.maxStateIndex = maxStateIndex;
        this.boundaryCommitId = boundaryCommitId;
        this.boundarySubstep = boundarySubstep;
        this.boundaryEventIndex = null;
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxEventsPerModel = maxEventsPerModel;
        this.maxBytes = maxBytes;
        this.composableOnly = composableOnly;
    }

    @ConstructorProperties({
            "rootId", "maxStateIndex", "boundaryCommitId",
            "boundarySubstep", "boundaryEventIndex", "maxDepth", "maxModels",
            "maxEventsPerModel", "maxBytes", "composableOnly"})
    public GetModelGraph(
            String rootId,
            Long maxStateIndex,
            String boundaryCommitId,
            Integer boundarySubstep,
            Long boundaryEventIndex,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            boolean composableOnly) {
        this.rootId = rootId;
        this.maxStateIndex = maxStateIndex;
        this.boundaryCommitId = boundaryCommitId;
        this.boundarySubstep = boundarySubstep;
        this.boundaryEventIndex = boundaryEventIndex;
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxEventsPerModel = maxEventsPerModel;
        this.maxBytes = maxBytes;
        this.composableOnly = composableOnly;
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                maxStateIndex, boundaryCommitId,
                boundarySubstep, boundaryEventIndex, maxDepth, maxModels,
                maxEventsPerModel, maxBytes, composableOnly);
    }

    @Value
    public static class Metric {
        Long maxStateIndex;
        String boundaryCommitId;
        Integer boundarySubstep;
        Long boundaryEventIndex;
        int maxDepth;
        int maxModels;
        int maxEventsPerModel;
        long maxBytes;
        boolean composableOnly;
    }
}
