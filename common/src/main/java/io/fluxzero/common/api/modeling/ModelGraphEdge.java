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

/**
 * One temporal child-to-parent edge selected for a model graph.
 */
@Value
public class ModelGraphEdge {
    String childId;
    String parentId;

    /** Stable application-scoped logical parent Model name, or {@code null} when unknown. */
    String parentType;
    String path;
    long validFrom;
    Long validUntil;

    /** Whether this edge makes the parent responsible for deleting the child. */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    boolean deleteOnParentDeletion;

    /** Returns this edge with a projection-relative path. */
    public ModelGraphEdge withPath(String path) {
        return new ModelGraphEdge(
                childId, parentId, parentType, path, validFrom, validUntil,
                deleteOnParentDeletion);
    }
}
