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

import java.util.Objects;

/**
 * Optional direct current-document consequence of one model target transition.
 * <p>
 * A {@code null} document represents deletion from {@link #collection}. The runtime attaches the target transition's
 * assigned {@code stateIndex} and applies the mutation through a monotone write fence.
 */
@Value
public class ModelDocumentMutation {

    /** Prefix for type-isolated internal graph-component collections. */
    public static final String GRAPH_COMPONENT_COLLECTION_PREFIX =
            "$modelGraphComponents/";

    /**
     * Returns the private search collection for current graph-component documents of one model type.
     * <p>
     * Type isolation lets relationship search use the component's ordinary document indexes without scanning or
     * mixing unrelated model types. The complete model type name is already part of the persisted model contract and
     * keeps the collection deterministic across SDK instances.
     */
    public static String graphComponentCollection(String modelType) {
        Objects.requireNonNull(modelType, "Model type");
        if (modelType.isBlank() || !modelType.equals(modelType.trim())) {
            throw new IllegalArgumentException(
                    "Model type must not be blank or have surrounding whitespace");
        }
        return GRAPH_COMPONENT_COLLECTION_PREFIX + modelType;
    }

    /**
     * Internal collection containing crash-safe direct-document state while published legacy
     * events are being rebuilt. Ordinary Model loads and searches never use this collection.
     */
    public static final String MIGRATION_COLLECTION =
            "$modelMigrationDocuments";

    /**
     * Current-document collection. This is either the independently searchable model collection or an internal,
     * type-isolated graph-component collection.
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
