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
import lombok.Value;

import java.util.Objects;

import static io.fluxzero.common.SearchUtils.isInteger;
import static io.fluxzero.common.search.JacksonInverter.isMetadataPath;

/**
 * Projection-local replacement for a canonical relationship path.
 * <p>
 * Overrides affect only the materialized search document. They never alter relationship truth.
 */
@Value
public class ModelGraphPathOverride {

    /**
     * Canonical path stored on the relationship.
     */
    String path;

    /**
     * Path used in this materialized projection.
     */
    String projectionPath;

    @JsonCreator
    public ModelGraphPathOverride(
            @JsonProperty("path") String path,
            @JsonProperty("projectionPath")
            String projectionPath) {
        this.path = requirePath(path, "Canonical graph path");
        this.projectionPath = requirePath(
                projectionPath, "Projection graph path");
    }

    private static String requirePath(String value, String description) {
        String result = Objects.requireNonNull(value, description).trim();
        if (result.isEmpty()
            || !result.equals(value)
            || result.startsWith("/")
            || result.endsWith("/")
            || result.contains("//")) {
            throw new IllegalArgumentException(
                    description
                    + " must be a non-empty relative path without surrounding whitespace");
        }
        for (String segment : result.split("/")) {
            if (".".equals(segment)
                || "..".equals(segment)
                || isInteger(segment)) {
                throw new IllegalArgumentException(
                        description
                        + " contains a reserved path segment: "
                        + result);
            }
        }
        if (isMetadataPath(result)) {
            throw new IllegalArgumentException(
                    description
                    + " uses the reserved document metadata path: "
                    + result);
        }
        return result;
    }
}
