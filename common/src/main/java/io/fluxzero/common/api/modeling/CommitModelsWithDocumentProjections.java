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
 * Commits internal Model sources and independent document projections together. Its distinct wire type ensures
 * older runtimes reject these writes instead of silently dropping the projection. Relationship reads remain mandatory
 * dependencies when present.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class CommitModelsWithDocumentProjections extends CommitModels {
    private final List<ModelRelationshipRead> readRelationships;

    public CommitModelsWithDocumentProjections(CommitModels commit) {
        this(commit.getRequestId(), commit.getCommitId(), commit.getReadStateIndex(), commit.getReadModelIds(),
             commit.getSubsteps(), commit.getConflictPolicy(), commit.getGuarantee(), commit.isPossibleDuplicate(),
             commit.isMigration(), commit.getReadRelationships());
    }

    @ConstructorProperties({"requestId", "commitId", "readStateIndex", "readModelIds", "substeps", "conflictPolicy",
            "guarantee", "possibleDuplicate", "migration", "readRelationships"})
    public CommitModelsWithDocumentProjections(long requestId, String commitId, long readStateIndex,
                                               List<String> readModelIds, List<ModelCommitStep> substeps,
                                               ModelConflictPolicy conflictPolicy, Guarantee guarantee,
                                               boolean possibleDuplicate, boolean migration,
                                               List<ModelRelationshipRead> readRelationships) {
        super(requestId, commitId, readStateIndex, readModelIds, substeps, conflictPolicy, guarantee,
              possibleDuplicate, migration);
        this.readRelationships = readRelationships == null ? List.of() : List.copyOf(readRelationships);
    }
}
