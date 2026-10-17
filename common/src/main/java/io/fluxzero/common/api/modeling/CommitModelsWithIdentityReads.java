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

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * Atomically validates explicit alias selections with Model mutations and existing commit facets.
 * Null expected owners mean observed absence. Unsupported peers must reject this distinct wire type.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public final class CommitModelsWithIdentityReads extends CommitModels {
    private final List<ModelRelationshipRead> readRelationships;
    private final List<String> readAliasIds;
    private final List<ModelDeadlineUpdate> deadlineUpdates;
    private final List<ModelAliasObservation> aliasObservations;

    public CommitModelsWithIdentityReads(
            CommitModels commit, List<ModelAliasObservation> observations) {
        this(
                commit.getRequestId(),
                commit.getCommitId(),
                commit.getReadStateIndex(),
                commit.getReadModelIds(),
                commit.getSubsteps(),
                commit.getConflictPolicy(),
                commit.getGuarantee(),
                commit.isPossibleDuplicate(),
                commit.isMigration(),
                commit.getReadRelationships(),
                commit.getReadAliasIds(),
                commit.getDeadlineUpdates(), observations);
    }

    @ConstructorProperties({
        "requestId",
        "commitId",
        "readStateIndex",
        "readModelIds",
        "substeps",
        "conflictPolicy",
        "guarantee",
        "possibleDuplicate",
        "migration",
        "readRelationships",
        "readAliasIds",
        "deadlineUpdates", "aliasObservations"
    })
    public CommitModelsWithIdentityReads(
            long requestId,
            String commitId,
            long readStateIndex,
            List<String> readModelIds,
            List<ModelCommitStep> substeps,
            ModelConflictPolicy conflictPolicy,
            Guarantee guarantee,
            boolean possibleDuplicate,
            boolean migration,
            List<ModelRelationshipRead> readRelationships,
            List<String> readAliasIds,
            List<ModelDeadlineUpdate> deadlineUpdates, List<ModelAliasObservation> aliasObservations) {
        super(
                requestId,
                commitId,
                readStateIndex,
                readModelIds,
                substeps,
                conflictPolicy,
                guarantee,
                possibleDuplicate,
                migration);
        this.readRelationships =
                readRelationships == null ? List.of() : List.copyOf(readRelationships);
        this.readAliasIds = readAliasIds == null ? List.of() : List.copyOf(readAliasIds);
        this.deadlineUpdates = deadlineUpdates == null ? List.of() : List.copyOf(deadlineUpdates);
        this.aliasObservations = aliasObservations == null ? List.of() : List.copyOf(aliasObservations);
    }

}
