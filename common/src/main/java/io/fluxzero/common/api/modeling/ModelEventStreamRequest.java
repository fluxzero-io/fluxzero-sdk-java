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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Value;

import java.beans.ConstructorProperties;

/**
 * One bounded model-stream read inside {@link GetModelEvents}.
 */
@Value
public class ModelEventStreamRequest {

    /**
     * Exact persisted model identity.
     */
    String modelId;

    /**
     * Last sequence number already held by the caller; returned memberships start after it.
     */
    long lastSequenceNumber;

    /**
     * Maximum memberships to return. Zero requests only coordination head metadata.
     */
    int maxSize;

    /** Include evidence for a matching materialized document in the selected head when available. */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    boolean includeDocumentVersion;

    public ModelEventStreamRequest(String modelId, long lastSequenceNumber, int maxSize) {
        this(modelId, lastSequenceNumber, maxSize, false);
    }

    @ConstructorProperties({"modelId", "lastSequenceNumber", "maxSize", "includeDocumentVersion"})
    public ModelEventStreamRequest(String modelId, long lastSequenceNumber, int maxSize, boolean includeDocumentVersion) {
        this.modelId = modelId;
        this.lastSequenceNumber = lastSequenceNumber;
        this.maxSize = maxSize;
        this.includeDocumentVersion = includeDocumentVersion;
    }
}
