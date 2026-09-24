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

import java.util.Objects;

/**
 * A relationship selection observed at the commit's read boundary, including a selection that returned no edges.
 * Values of the selected Models are separate head dependencies. A {@code null} path selects every relationship path;
 * an empty path selects only pathless relationships. Type filtering is conservatively protected at path granularity.
 *
 * @param modelId canonical identity at the source of the selection
 * @param direction whether incoming children or outgoing parents were selected
 * @param path exact persisted relationship path, or {@code null} for all paths
 */
public record ModelRelationshipRead(String modelId, Direction direction, String path) {
    public ModelRelationshipRead {
        ModelCommitValidator.validateModelId(modelId);
        Objects.requireNonNull(direction, "Relationship read direction");
    }

    /** Direction relative to the observed Model. */
    public enum Direction {
        CHILDREN, PARENTS
    }

    /** Whether an edge belongs to this selection, independently of its temporal validity. */
    public boolean matches(String childId, ModelRelationship relationship) {
        return modelId.equals(direction == Direction.CHILDREN ? relationship.getParentId() : childId)
               && (path == null || path.equals(Objects.requireNonNullElse(relationship.getPath(), "")));
    }
}
