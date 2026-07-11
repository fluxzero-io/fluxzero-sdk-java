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

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.NonNull;
import lombok.Value;

/**
 * Describes an existing search collection and its storage type.
 */
@Value
public class SearchCollection {

    /**
     * Name of the collection.
     */
    @NonNull String name;

    /**
     * Storage type of the collection.
     */
    @NonNull Type type;

    /**
     * Supported search collection types.
     */
    public enum Type {
        /**
         * A regular document collection.
         */
        regular,

        /**
         * A time-partitioned searchable audit trail.
         */
        auditTrail,

        /**
         * A collection type that is unknown to this SDK version.
         */
        @JsonEnumDefaultValue
        unknown
    }
}
