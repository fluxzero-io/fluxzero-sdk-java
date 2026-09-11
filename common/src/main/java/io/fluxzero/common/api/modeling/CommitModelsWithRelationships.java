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

import io.fluxzero.common.Guarantee;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * A Model commit that additionally validates relationship selections. Its distinct wire type is intentional:
 * runtimes without relationship-read validation must reject the request instead of silently committing it.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class CommitModelsWithRelationships extends CommitModels {
    /** Collections inspected at the commit's read boundary, including selections that returned no Models. */
    private final List<ModelRelationshipRead> readRelationships;

    /** Adds immutable relationship dependencies while retaining the original request and commit identity. */
    public CommitModelsWithRelationships(CommitModels commit, List<ModelRelationshipRead> readRelationships) {
        this(commit.getRequestId(), commit.getCommitId(), commit.getReadStateIndex(), commit.getReadModelIds(),
             commit.getSubsteps(), commit.getConflictPolicy(), commit.getGuarantee(), commit.isPossibleDuplicate(),
             commit.isMigration(), readRelationships);
    }

    @ConstructorProperties({"requestId", "commitId", "readStateIndex", "readModelIds", "substeps", "conflictPolicy",
            "guarantee", "possibleDuplicate", "migration", "readRelationships"})
    public CommitModelsWithRelationships(long requestId, String commitId, long readStateIndex,
                                         List<String> readModelIds, List<ModelCommitStep> substeps,
                                         ModelConflictPolicy conflictPolicy, Guarantee guarantee,
                                         boolean possibleDuplicate, boolean migration,
                                         List<ModelRelationshipRead> readRelationships) {
        super(requestId, commitId, readStateIndex, readModelIds, substeps, conflictPolicy, guarantee,
              possibleDuplicate, migration);
        this.readRelationships = List.copyOf(readRelationships);
        if (this.readRelationships.isEmpty()) {
            throw new IllegalArgumentException("A relationship-aware commit requires relationship reads");
        }
    }
}
