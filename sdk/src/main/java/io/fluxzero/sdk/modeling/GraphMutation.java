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

import io.fluxzero.common.api.modeling.CommitModelsResult;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** One direct Graph mutation waiting to be resolved against a commit-scoped Model value. */
record GraphMutation(
        String modelId,
        Class<?> modelType,
        Long expectedStateIndex,
        Long readStateIndex,
        boolean initiallyAbsent,
        Object preview,
        UnaryOperator<Entity<?>> replay) {

    GraphMutation {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelType, "modelType");
        Objects.requireNonNull(replay, "replay");
    }

    GraphMutation then(GraphMutation addition) {
        if (!modelType.equals(addition.modelType)) {
            throw new IllegalStateException(
                    "Graph mutations target repository id '%s' as both %s and %s"
                            .formatted(addition.modelId, modelType.getName(),
                                       addition.modelType.getName()));
        }
        return new GraphMutation(
                modelId, modelType, expectedStateIndex, readStateIndex, initiallyAbsent, addition.preview,
                current -> addition.replay.apply(replay.apply(current)));
    }

    /** Reevaluate the original intent at the retry boundary without losing its absence precondition. */
    GraphMutation forRetry(CommitModelsResult conflict) {
        return new GraphMutation(modelId, modelType, null, null, initiallyAbsent, null, current -> {
            if (initiallyAbsent && current.isPresent()) {
                throw new ModelCommitConflictException(conflict);
            }
            return replay.apply(current);
        });
    }
}
