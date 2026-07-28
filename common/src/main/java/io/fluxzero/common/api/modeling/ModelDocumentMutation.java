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

import io.fluxzero.common.api.search.SerializedDocument;
import lombok.Value;

/**
 * Optional direct current-document consequence of one model target transition.
 * <p>
 * A {@code null} document represents deletion from {@link #collection}. The runtime attaches the target transition's
 * assigned {@code stateIndex} and applies the mutation through a monotone write fence.
 */
@Value
public class ModelDocumentMutation {

    /**
     * Internal collection containing current documents for models that opt into graph placement through an explicit
     * parent path without being independently searchable.
     */
    public static final String GRAPH_COMPONENT_COLLECTION =
            "$modelGraphComponents";

    /**
     * Current-document collection. This is either the independently searchable model collection or the internal graph
     * component collection.
     */
    String collection;

    /**
     * Complete serialized current document, or {@code null} to delete it.
     */
    SerializedDocument document;

    /**
     * Returns the serialized document payload size without counting protocol framing.
     */
    public long getBytes() {
        return document == null ? 0L : document.bytes();
    }
}
