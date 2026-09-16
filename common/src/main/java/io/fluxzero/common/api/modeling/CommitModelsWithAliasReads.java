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
 * Atomically validates consumed alias mappings alongside Model heads, relationship reads and document projections.
 * The distinct wire type makes unsupported receivers reject the request rather than lose alias dependencies.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class CommitModelsWithAliasReads extends CommitModels {
    private final List<ModelRelationshipRead> readRelationships;
    /**
     * Lookup keys whose mappings were consumed. {@code readModelIds} must also contain each lookup key
     * and, for a successful lookup, its observed canonical owner. The latter detects removal or reassignment
     * away from the observed owner; the former preserves canonical-ID precedence.
     */
    private final List<String> readAliasIds;

    /** Retains all commit capabilities and its original transport identity. */
    public CommitModelsWithAliasReads(CommitModels commit, List<String> readAliasIds) {
        this(commit.getRequestId(), commit.getCommitId(), commit.getReadStateIndex(), commit.getReadModelIds(),
             commit.getSubsteps(), commit.getConflictPolicy(), commit.getGuarantee(), commit.isPossibleDuplicate(),
             commit.isMigration(), commit.getReadRelationships(), readAliasIds);
    }

    @ConstructorProperties({"requestId", "commitId", "readStateIndex", "readModelIds", "substeps", "conflictPolicy",
            "guarantee", "possibleDuplicate", "migration", "readRelationships", "readAliasIds"})
    public CommitModelsWithAliasReads(long requestId, String commitId, long readStateIndex,
                                     List<String> readModelIds, List<ModelCommitStep> substeps,
                                     ModelConflictPolicy conflictPolicy, Guarantee guarantee,
                                     boolean possibleDuplicate, boolean migration,
                                     List<ModelRelationshipRead> readRelationships, List<String> readAliasIds) {
        super(requestId, commitId, readStateIndex, readModelIds, substeps, conflictPolicy, guarantee,
              possibleDuplicate, migration);
        this.readRelationships = readRelationships == null ? List.of() : List.copyOf(readRelationships);
        this.readAliasIds = List.copyOf(readAliasIds);
        if (this.readAliasIds.isEmpty()) {
            throw new IllegalArgumentException("An alias-aware commit requires alias reads");
        }
    }
}
