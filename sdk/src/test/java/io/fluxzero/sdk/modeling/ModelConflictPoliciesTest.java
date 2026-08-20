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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelConflictPoliciesTest {

    @Test
    void applyOverridePrecedesModelAndApplicationPolicy() throws Exception {
        assertEquals(
                ModelConflictPolicy.ACCEPT,
                evaluation(
                                transition(
                                        "accept", FailModel.class,
                                        method("accept")))
                        .conflictPolicy(ModelConflictPolicy.RETRY));
    }

    @Test
    void strictestParticipantPolicyControlsTheAtomicCommit() throws Exception {
        assertEquals(
                ModelConflictPolicy.FAIL,
                evaluation(
                                transition(
                                        "retry", RetryModel.class,
                                        method("inherit")),
                                transition(
                                        "fail", DefaultModel.class,
                                        method("fail")))
                        .conflictPolicy(ModelConflictPolicy.ACCEPT));
    }

    @Test
    void readOnlyDependencyUsesItsModelPolicy() throws Exception {
        ModelExecutionPlan.CommitEvaluation evaluation =
                evaluation(
                        Map.of(
                                "written", DefaultModel.class,
                                "ancestor", FailModel.class),
                        transition(
                                "written", DefaultModel.class,
                                method("retry")));

        assertEquals(
                ModelConflictPolicy.FAIL,
                evaluation.conflictPolicy(ModelConflictPolicy.ACCEPT));
    }

    @Test
    void defaultsInheritTheApplicationPolicy() throws Exception {
        assertEquals(
                ModelConflictPolicy.RETRY,
                evaluation(
                                transition(
                                        "default",
                                        DefaultModel.class,
                                        method("inherit")))
                        .conflictPolicy(ModelConflictPolicy.RETRY));
    }

    @Test
    void newIdentityAcceptFailsInsteadOfRebasingIntoAnOverwrite()
            throws Exception {
        Change creation =
                Change.applied(
                        "new", DefaultModel.class,
                        -1L, null, null,
                        new DefaultModel("new"),
                        method("accept"), null, false);

        assertEquals(
                ModelConflictPolicy.FAIL,
                evaluation(creation).conflictPolicy(ModelConflictPolicy.ACCEPT));
    }

    @Test
    void newIdentityPreservesExplicitRetry()
            throws Exception {
        Change creation =
                Change.applied(
                        "new", DefaultModel.class,
                        -1L, null, null,
                        new DefaultModel("new"),
                        method("retry"), null, false);

        assertEquals(
                ModelConflictPolicy.RETRY,
                evaluation(creation).conflictPolicy(ModelConflictPolicy.ACCEPT));
    }

    private static ModelExecutionPlan.CommitEvaluation evaluation(
            Change... transitions) {
        return evaluation(
                java.util.Arrays.stream(transitions)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Change::modelId,
                                        Change::modelType)),
                transitions);
    }

    private static ModelExecutionPlan.CommitEvaluation evaluation(
            Map<String, Class<?>> readTypes,
            Change... transitions) {
        return new ModelExecutionPlan.CommitEvaluation(
                1L, List.copyOf(readTypes.keySet()),
                readTypes,
                List.of(
                        new ModelExecutionPlan.AppliedSubstep(
                                null,
                                List.of(transitions))),
                Map.of());
    }

    private static Change transition(
            String id,
            Class<?> type,
            Executable handler) {
        return Change.applied(
                id, type, 0L, null,
                null, new Object(), handler,
                null, false);
    }

    private static Executable method(
            String name) throws Exception {
        return Applies.class.getDeclaredMethod(
                name);
    }

    private static class Applies {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        Object accept() {
            return null;
        }

        @Apply(conflictPolicy = ModelConflictPolicy.FAIL)
        Object fail() {
            return null;
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Object retry() {
            return null;
        }

        @Apply
        Object inherit() {
            return null;
        }
    }

    @Model(conflictPolicy = ModelConflictPolicy.FAIL)
    private record FailModel(
            @EntityId String id) {
    }

    @Model(conflictPolicy = ModelConflictPolicy.RETRY)
    private record RetryModel(
            @EntityId String id) {
    }

    @Model
    private record DefaultModel(
            @EntityId String id) {
    }
}
