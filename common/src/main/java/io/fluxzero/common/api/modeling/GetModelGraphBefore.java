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
import io.fluxzero.common.api.Request;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.Objects;

/**
 * Resolves the model graph that was current immediately before the boundary selected by a regular
 * {@link GetModelGraph} request.
 * <p>
 * This is a separate request type so adding before-state reconstruction does not alter the serialized form of normal
 * graph requests sent to older runtimes.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class GetModelGraphBefore extends Request {

    /** Graph selection and exclusive upper boundary. */
    GetModelGraph request;

    @JsonCreator
    public GetModelGraphBefore(
            @JsonProperty("request")
            GetModelGraph request) {
        this.request = Objects.requireNonNull(
                request, "request");
    }

    @Override
    public Object toMetric() {
        return request.toMetric();
    }
}
