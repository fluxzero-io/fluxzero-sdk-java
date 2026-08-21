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

package io.fluxzero.common.modeling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommitConflictsTest {

    @Test
    void detectsAndDeduplicatesReadAndSequenceConflictsInRequestOrder() {
        CommitModels commit = commit(
                ModelConflictPolicy.FAIL,
                List.of("stale-read", "stale-read"),
                target("stale-read", 1L),
                target("missing", 0L),
                target("current", 3L));

        List<ModelCommitConflict> conflicts = ModelCommitConflicts.detect(
                commit,
                Map.of(
                        "stale-read", new Head(2L, 12L),
                        "current", new Head(3L, 10L)),
                Head::sequenceNumber,
                Head::stateIndex,
                Map.of("stale-read", 11L, "missing", 4L));

        assertEquals(
                List.of(
                        new ModelCommitConflict("stale-read", 12L, 11L),
                        new ModelCommitConflict("missing", -1L, 4L)),
                conflicts);
    }

    @Test
    void returnsNoResultForACurrentCommit() {
        CommitModels commit = commit(
                ModelConflictPolicy.RETRY,
                List.of("current"),
                target("current", 3L));
        List<ModelCommitConflict> conflicts = ModelCommitConflicts.detect(
                commit,
                Map.of("current", new Head(3L, 10L)),
                Head::sequenceNumber,
                Head::stateIndex,
                Map.of());

        assertTrue(conflicts.isEmpty());
        assertNull(ModelCommitConflicts.result(commit, conflicts, 20L));
    }

    @Test
    void mapsAcceptFailAndRetryPoliciesToTheirSingleOutcomeOwner() {
        List<ModelCommitConflict> conflicts =
                List.of(new ModelCommitConflict("stale", 12L, -1L));

        CommitModelsResult accepted = ModelCommitConflicts.result(
                commit(ModelConflictPolicy.ACCEPT, List.of()), conflicts, 20L);
        assertTrue(accepted.isRebaseRequired());
        assertEquals(20L, accepted.getRebaseStateIndex());
        assertTrue(accepted.isRetryAllowed());

        CommitModelsResult failed = ModelCommitConflicts.result(
                commit(ModelConflictPolicy.FAIL, List.of()), conflicts, 20L);
        assertFalse(failed.isRebaseRequired());
        assertFalse(failed.isRetryAllowed());

        CommitModelsResult retry = ModelCommitConflicts.result(
                commit(ModelConflictPolicy.RETRY, List.of()), conflicts, 20L);
        assertFalse(retry.isRebaseRequired());
        assertTrue(retry.isRetryAllowed());
    }

    private static CommitModels commit(
            ModelConflictPolicy policy,
            List<String> readModelIds,
            ModelCommitTarget... targets) {
        return new CommitModels(
                "commit", 10L, readModelIds,
                targets.length == 0
                        ? List.of()
                        : List.of(
                                ModelCommitStep.builder()
                                        .publishEvent(false)
                                        .targets(List.of(targets))
                                        .build()),
                policy, Guarantee.STORED);
    }

    private static ModelCommitTarget target(
            String modelId,
            Long expectedSequenceNumber) {
        return ModelCommitTarget.builder()
                .modelId(modelId)
                .modelType("type")
                .expectedSequenceNumber(expectedSequenceNumber)
                .relationships(List.of())
                .build();
    }

    private record Head(long sequenceNumber, long stateIndex) {
    }
}
