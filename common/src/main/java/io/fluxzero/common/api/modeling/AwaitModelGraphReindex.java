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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;

/**
 * Waits for current Model projection progress and all pending source rewrites of the selected nodes in one collection.
 * Unlike an ordinary commit barrier, this also covers same-state-index schema work. A distinct wire type prevents
 * older Runtimes from silently treating this request as an ordinary state-index barrier.
 */
public final class AwaitModelGraphReindex extends AwaitModelGraphProjection {
    /** Creates a source-reindex barrier for the given nodes and their affected roots. */
    @JsonCreator
    public AwaitModelGraphReindex(@JsonProperty("collection") String collection,
                                  @JsonProperty("stateIndex") long stateIndex,
                                  @JsonProperty("modelIds") Collection<String> modelIds) {
        super(collection, stateIndex, modelIds);
        if (getModelIds().isEmpty()) {
            throw new IllegalArgumentException("A reindex barrier requires Model IDs");
        }
    }
}
