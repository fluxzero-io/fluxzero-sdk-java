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
import java.util.List;
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
        List<ModelCommitEngine.Transition> transitions =
                evaluation.transitions();
        if (transitions.size() == 1
            && evaluation.readModelTypes().size() == 1
            && evaluation.readModelTypes().containsKey(
                    transitions.getFirst().modelId())) {
            return transitionPolicy(
                    transitions.getFirst(), application);
        }
        ModelConflictPolicy result =
                ModelConflictPolicy.ACCEPT;
        Set<String> writtenModelIds =
                new HashSet<>();
        for (ModelCommitEngine.Transition transition :
                transitions) {
            writtenModelIds.add(
                    transition.modelId());
            result = strictest(
                    result,
                    transitionPolicy(
                            transition, application));
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

    private static ModelConflictPolicy transitionPolicy(
            ModelCommitEngine.Transition transition,
            ModelConflictPolicy application) {
        Apply apply = transition.handler() == null
                ? null
                : transition.handler().getAnnotation(Apply.class);
        ModelConflictPolicy policy = apply == null
                ? ModelConflictPolicy.DEFAULT
                : apply.conflictPolicy();
        if (policy == ModelConflictPolicy.DEFAULT) {
            policy = modelPolicy(
                    transition.modelType());
        }
        ModelConflictPolicy resolved =
                inherit(policy, application);
        if (transition.before() == null
            && transition.beforeSequenceNumber() < 0L
            && resolved == ModelConflictPolicy.ACCEPT) {
            /*
             * A newly created identity has no meaningful state on which an ACCEPT rebase can apply. Retrying is
             * safe: the complete action is evaluated again against the concurrently created value, so create-only
             * assertions still fail while upserts may deliberately update it. Silent ACCEPT must never turn the
             * original create into an overwrite without rerunning application logic.
             */
            return ModelConflictPolicy.FAIL;
        }
        return resolved;
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
