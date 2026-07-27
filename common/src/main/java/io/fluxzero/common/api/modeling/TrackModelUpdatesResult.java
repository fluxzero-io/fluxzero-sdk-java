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

import java.util.List;

/**
 * One bounded page of model-action updates returned by {@link TrackModelUpdates}.
 */
@Value
public class TrackModelUpdatesResult extends AbstractRequestResult {

    long requestId;

    /**
     * Cursor after the last update in this response, or the request cursor for an empty heartbeat.
     */
    long lastStateIndex;

    /**
     * Current durable namespace high-watermark observed while producing the response.
     */
    long currentStateIndex;

    /**
     * Highest namespace state from which a newly loaded direct document can start tracking without skipping an action
     * whose document/snapshot materialization is still pending.
     */
    long materializedStateIndex;

    /**
     * Committed action-substep updates in increasing state-index order.
     */
    List<ModelUpdate> updates;

    long timestamp = System.currentTimeMillis();

    @Override
    public Metric toMetric() {
        int targetCount = 0;
        int publishedCount = 0;
        for (ModelUpdate update : updates) {
            targetCount += update.getTargets().size();
            if (update.getEventIndex() != null) {
                publishedCount++;
            }
        }
        return new Metric(
                updates.size(), targetCount, publishedCount,
                lastStateIndex, currentStateIndex,
                materializedStateIndex, timestamp);
    }

    @Value
    public static class Metric {
        int updateCount;
        int targetCount;
        int publishedCount;
        long lastStateIndex;
        long currentStateIndex;
        long materializedStateIndex;
        long timestamp;
    }
}
