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

package io.fluxzero.sdk.modeling;

import java.util.List;

/**
 * Internal reconstructing event stored for models deleted through an owning {@link Parent} relationship.
 * <p>
 * The payload is intentionally stable because event-sourced descendant streams retain it. It is never globally
 * published and applications do not need an {@link io.fluxzero.sdk.persisting.eventsourcing.Apply @Apply} handler for
 * it; the model repository interprets it as a logical deletion during replay.
 *
 * @param parentIds deleted parent model IDs which caused this cascade step
 */
public record CascadedModelDeletion(List<String> parentIds) {
    public CascadedModelDeletion {
        parentIds = List.copyOf(parentIds);
        if (parentIds.isEmpty()) {
            throw new IllegalArgumentException("Cascaded model deletion requires at least one parent ID");
        }
    }
}
