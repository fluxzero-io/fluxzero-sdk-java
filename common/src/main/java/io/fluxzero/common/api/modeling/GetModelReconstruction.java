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
 * Loads one Model's canonical head, an optional snapshot and the first page after that snapshot in one response.
 * A concurrently published snapshot may be missed. The chosen snapshot and all returned events must fit the
 * resolved read boundary. Subsequent pages use the returned canonical identity and boundary with GetModelEvents.
 * This distinct wire operation requires the Model reconstruction capability; ordinary event reads are unchanged.
 */
@Value
public class GetModelReconstruction extends Request {
    String modelId;
    ModelReadBoundary boundary;
    /** Maximum event memberships in the first page, excluding the snapshot. */
    int maxSize;
    /** Event payload byte budget, with the same progress rule as GetModelEvents; snapshot bytes are additional. */
    long maxBytes;
    /** Whether alias fallback is forbidden. */
    boolean primaryOnly;

    @ConstructorProperties({"modelId", "boundary", "maxSize", "maxBytes", "primaryOnly"})
    public GetModelReconstruction(String modelId, ModelReadBoundary boundary, int maxSize, long maxBytes,
                                  boolean primaryOnly) {
        this.modelId = modelId;
        this.boundary = Objects.requireNonNull(boundary, "boundary").forRequest();
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
        this.primaryOnly = primaryOnly;
    }
    @Override public GetModelEvents.Metric toMetric() {
        return new GetModelEvents.Metric(1, maxSize == 0 ? 1 : 0, maxSize, boundary, maxBytes);
    }
}
