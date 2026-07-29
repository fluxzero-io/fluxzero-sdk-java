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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

/**
 * Current progress of one asynchronous materialized graph projection.
 */
@Value
public class ModelGraphProjectionStatus extends AbstractRequestResult {

    /**
     * Correlated request ID.
     */
    long requestId;

    /**
     * Observation timestamp.
     */
    long timestamp = System.currentTimeMillis();

    /**
     * Materialized graph collection.
     */
    String collection;

    /**
     * Latest model state visible when this status was created.
     */
    long sourceStateIndex;

    /**
     * Highest contiguous model state whose projection signals were consumed.
     */
    long processedStateIndex;

    /**
     * Number of durable commit signals not yet expanded to affected roots.
     */
    long pendingSignals;

    /**
     * Number of coalesced root documents awaiting materialization.
     */
    long pendingRoots;

    /**
     * Whether a bounded full-root scan is still in progress.
     */
    boolean rebuilding;

    /**
     * Non-negative state-index lag at observation time.
     */
    public long getLag() {
        return Math.max(
                0L, sourceStateIndex - processedStateIndex);
    }
}
