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

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * Temporal graph edges and one model-event page observed at the same pinned state boundary.
 */
@Value
public class GetModelGraphResult extends AbstractRequestResult {
    long requestId;
    List<ModelGraphEdge> edges;
    GetModelEventsResult events;
    long timestamp = System.currentTimeMillis();

    @ConstructorProperties({"requestId", "edges", "events"})
    public GetModelGraphResult(
            long requestId,
            List<ModelGraphEdge> edges,
            GetModelEventsResult events) {
        this.requestId = requestId;
        this.edges = edges;
        this.events = events;
    }

    @Override
    public Metric toMetric() {
        return new Metric(edges.size(), events.toMetric(), timestamp);
    }

    @Value
    public static class Metric {
        int edgeCount;
        GetModelEventsResult.Metric events;
        long timestamp;
    }
}
