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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.Request;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.Objects;

/**
 * Requests freshness and backlog state for one materialized graph collection.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class GetModelGraphProjectionStatus extends Request {

    /**
     * Materialized graph collection identifying the projection.
     */
    String collection;

    @JsonCreator
    public GetModelGraphProjectionStatus(
            @JsonProperty("collection")
            String collection) {
        String value = Objects.requireNonNull(
                collection, "Graph projection collection");
        if (value.isBlank()
            || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "Graph projection collection must not be blank or have surrounding whitespace");
        }
        this.collection = value;
    }
}
