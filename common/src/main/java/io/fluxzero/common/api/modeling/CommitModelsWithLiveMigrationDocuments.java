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
 * Replays one existing published event while materializing documents in their configured collections.
 * Migration identity, ordering and publication restrictions are unchanged. Unsupported peers must reject this
 * distinct wire type instead of silently staging its documents.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class CommitModelsWithLiveMigrationDocuments extends CommitModels {
    private final List<ModelRelationshipRead> readRelationships;
    private final List<String> readAliasIds;

    /** Retains the migration's transport identity and read dependencies. */
    public CommitModelsWithLiveMigrationDocuments(CommitModels commit) {
        this(commit.getRequestId(), commit.getCommitId(), commit.getReadStateIndex(), commit.getReadModelIds(),
             commit.getSubsteps(), commit.getConflictPolicy(), commit.getGuarantee(), commit.isPossibleDuplicate(),
             commit.isMigration(), commit.getReadRelationships(), commit.getReadAliasIds());
        if (!commit.getDeadlineUpdates().isEmpty() || !commit.getAliasObservations().isEmpty()) {
            throw new IllegalArgumentException("Historical migrations cannot carry deadline effects or identity observations");
        }
    }

    @ConstructorProperties({"requestId", "commitId", "readStateIndex", "readModelIds", "substeps", "conflictPolicy",
            "guarantee", "possibleDuplicate", "migration", "readRelationships", "readAliasIds"})
    public CommitModelsWithLiveMigrationDocuments(long requestId, String commitId, long readStateIndex,
            List<String> readModelIds, List<ModelCommitStep> substeps, ModelConflictPolicy conflictPolicy,
            Guarantee guarantee, boolean possibleDuplicate, boolean migration,
            List<ModelRelationshipRead> readRelationships, List<String> readAliasIds) {
        super(requestId, commitId, readStateIndex, readModelIds, substeps, conflictPolicy, guarantee,
              possibleDuplicate, migration);
        if (!migration) {
            throw new IllegalArgumentException("Live migration documents require a migration commit");
        }
        this.readRelationships = readRelationships == null ? List.of() : List.copyOf(readRelationships);
        this.readAliasIds = readAliasIds == null ? List.of() : List.copyOf(readAliasIds);
    }

    @Override
    public boolean stagesMigrationDocuments() {
        return false;
    }
}
