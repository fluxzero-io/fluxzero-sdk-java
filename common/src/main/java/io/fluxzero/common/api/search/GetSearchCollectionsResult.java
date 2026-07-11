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

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

import java.util.List;

import static io.fluxzero.common.api.search.SearchCollection.Type.auditTrail;
import static io.fluxzero.common.api.search.SearchCollection.Type.regular;

/**
 * Response to a {@link GetSearchCollections} request.
 */
@Value
public class GetSearchCollectionsResult extends AbstractRequestResult {

    /**
     * The identifier of the originating request.
     */
    long requestId;

    /**
     * The available search collections with their storage types.
     */
    List<SearchCollection> searchCollections;

    /**
     * Timestamp when the collection names were retrieved.
     */
    long timestamp = System.currentTimeMillis();

    @Override
    public Metric toMetric() {
        int collectionCount = (int) searchCollections.stream().filter(c -> c.getType() == regular).count();
        int auditTrailCount = (int) searchCollections.stream().filter(c -> c.getType() == auditTrail).count();
        return new Metric(collectionCount, auditTrailCount,
                          searchCollections.size() - collectionCount - auditTrailCount, timestamp);
    }

    /**
     * Compact result summary used for metrics.
     */
    @Value
    public static class Metric {
        /**
         * Number of regular collections.
         */
        int collectionCount;

        /**
         * Number of audit trails.
         */
        int auditTrailCount;

        /**
         * Number of collections whose type is unknown to this SDK version.
         */
        int unknownCount;

        /**
         * Timestamp when the collection names were retrieved.
         */
        long timestamp;
    }
}
