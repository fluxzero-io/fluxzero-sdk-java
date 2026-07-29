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

import java.util.HashSet;
import java.util.Set;

/**
 * Resolves inherited participant conflict policies for one atomic model commit.
 */
final class ModelConflictPolicies {

    private ModelConflictPolicies() {
    }

    static ModelConflictPolicy resolve(
            ModelCommitEngine.CommitEvaluation evaluation,
            ModelConflictPolicy applicationPolicy) {
        ModelConflictPolicy application =
                ModelConflictPolicy.resolve(
                        applicationPolicy);
        ModelConflictPolicy result =
                ModelConflictPolicy.ACCEPT;
        Set<String> writtenModelIds =
                new HashSet<>();
        for (ModelCommitEngine.Transition transition :
                evaluation.transitions()) {
            writtenModelIds.add(
                    transition.modelId());
            Apply apply =
                    transition.handler()
                            .getAnnotation(Apply.class);
            ModelConflictPolicy policy =
                    apply == null
                            ? ModelConflictPolicy.DEFAULT
                            : apply.conflictPolicy();
            if (policy == ModelConflictPolicy.DEFAULT) {
                policy = modelPolicy(
                        transition.modelType());
            }
            result = strictest(
                    result,
                    inherit(policy, application));
        }
        for (var entry :
                evaluation.readModelTypes()
                        .entrySet()) {
            if (writtenModelIds.contains(
                    entry.getKey())) {
                continue;
            }
            result = strictest(
                    result,
                    inherit(
                            modelPolicy(
                                    entry.getValue()),
                            application));
        }
        return result;
    }

    private static ModelConflictPolicy modelPolicy(
            Class<?> modelType) {
        Model model =
                modelType.getAnnotation(
                        Model.class);
        return model == null
                ? ModelConflictPolicy.DEFAULT
                : model.conflictPolicy();
    }

    private static ModelConflictPolicy inherit(
            ModelConflictPolicy scoped,
            ModelConflictPolicy application) {
        return scoped == null
               || scoped
                  == ModelConflictPolicy.DEFAULT
                ? application : scoped;
    }

    private static ModelConflictPolicy strictest(
            ModelConflictPolicy left,
            ModelConflictPolicy right) {
        return rank(right) > rank(left)
                ? right : left;
    }

    private static int rank(
            ModelConflictPolicy policy) {
        return switch (ModelConflictPolicy.resolve(
                policy)) {
            case FAIL -> 3;
            case RETRY -> 2;
            case ACCEPT, DEFAULT -> 1;
        };
    }
}
