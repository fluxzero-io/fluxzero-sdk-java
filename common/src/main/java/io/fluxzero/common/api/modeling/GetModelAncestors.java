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

    /**
     * Inclusive historical state boundary, or {@code null} to pin the current boundary.
     */
    Long maxStateIndex;

    /**
     * Durable model action whose persisted result contains the exact boundary, or {@code null}.
     */
    String boundaryActionId;

    /**
     * Ordered substep within {@link #boundaryActionId}, or {@code null}.
     */
    Integer boundarySubstep;

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

    public GetModelAncestors(
            List<String> modelIds,
            Long maxStateIndex,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes) {
        this(
                modelIds, maxStateIndex, null, null,
                maxDepth, maxModels, maxEventsPerModel, maxBytes);
    }

    @ConstructorProperties({
            "modelIds", "maxStateIndex", "boundaryActionId",
            "boundarySubstep", "maxDepth", "maxModels",
            "maxEventsPerModel", "maxBytes"})
    public GetModelAncestors(
            List<String> modelIds,
            Long maxStateIndex,
            String boundaryActionId,
            Integer boundarySubstep,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes) {
        this.modelIds = modelIds == null ? null : List.copyOf(modelIds);
        this.maxStateIndex = maxStateIndex;
        this.boundaryActionId = boundaryActionId;
        this.boundarySubstep = boundarySubstep;
        this.maxDepth = maxDepth;
        this.maxModels = maxModels;
        this.maxEventsPerModel = maxEventsPerModel;
        this.maxBytes = maxBytes;
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                modelIds == null ? 0 : modelIds.size(),
                maxStateIndex, boundaryActionId,
                boundarySubstep, maxDepth, maxModels,
                maxEventsPerModel, maxBytes);
    }

    @Value
    public static class Metric {
        int rootCount;
        Long maxStateIndex;
        String boundaryActionId;
        Integer boundarySubstep;
        int maxDepth;
        int maxModels;
        int maxEventsPerModel;
        long maxBytes;
    }
}
