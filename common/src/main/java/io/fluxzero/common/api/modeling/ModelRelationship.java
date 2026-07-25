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

import lombok.Builder;
import lombok.Value;

/**
 * Desired outgoing relationship from a changed child model to one parent.
 * <p>
 * The child ID is supplied by the enclosing {@link ModelActionTarget}. A typed ID normally supplies
 * {@link #parentType}; an untyped relationship may declare it explicitly. {@link #path} is optional and only enables
 * automatic graph composition when explicitly configured.
 */
@Value
@Builder(toBuilder = true)
public class ModelRelationship {

    /**
     * Exact parent model ID string.
     */
    String parentId;

    /**
     * Parent model class/type name when statically known, otherwise {@code null}.
     */
    String parentType;

    /**
     * Explicit graph composition path, or {@code null} when composition is not configured.
     */
    String path;
}
