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

import lombok.Value;

/**
 * Resulting stream/head metadata for one committed target model.
 */
@Value
public class ModelActionTargetResult {

    /**
     * Exact persisted model ID.
     */
    String modelId;

    /**
     * Resulting sequence number of the model stream.
     */
    long sequenceNumber;

    /**
     * Whether every current-state transition through this result remains reconstructible from stored model events.
     */
    boolean historyComplete;
}
