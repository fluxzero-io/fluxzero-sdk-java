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
 * Commits SDK-computed ordinary scheduler effects together with Model state. The distinct
 * wire type requires explicit service support. All existing relationship, alias and document
 * projection facets are retained.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public final class CommitModelsWithDeadlines extends CommitModels {
    private final List<ModelRelationshipRead> readRelationships;
    private final List<String> readAliasIds;
    private final List<ModelDeadlineUpdate> deadlineUpdates;

    public CommitModelsWithDeadlines(
            CommitModels commit, List<ModelDeadlineUpdate> updates) {
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
                updates);
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
        "deadlineUpdates"
    })
    public CommitModelsWithDeadlines(
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
            List<ModelDeadlineUpdate> deadlineUpdates) {
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
        this.deadlineUpdates = List.copyOf(deadlineUpdates);
    }

    @Override
    public long getBytes() {
        long size = super.getBytes();
        for (var update : deadlineUpdates) {
            if (update.schedule() != null) {
                long bytes = update.schedule().getMessage().getBytes();
                size = bytes > Long.MAX_VALUE - size ? Long.MAX_VALUE : size + bytes;
            }
        }
        return size;
    }
}
