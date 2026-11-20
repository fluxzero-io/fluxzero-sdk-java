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

import java.util.Set;

/** Write boundary of one isolated published-event migration; native read dependencies remain available. */
record MigrationScope(Set<Class<?>> modelTypes) {
    MigrationScope {
        modelTypes = Set.copyOf(modelTypes);
    }

    boolean includes(Class<?> type) {
        return modelTypes.contains(type);
    }

    boolean accepts(EntityMetadata.HandlerMethod handler) {
        return handler.dynamicApplyResult() || handler.targetModelTypes().isEmpty()
                || handler.targetModelTypes().stream().anyMatch(declared -> modelTypes.stream()
                        .anyMatch(selected -> EntityMetadata.compatibleTypes(declared, selected)));
    }

    void validate(CommitAttempt attempt) {
        for (Change change : attempt.transitions()) {
            if (!includes(change.modelType())) {
                throw new IllegalStateException("Migration attempted to write a Model outside its catalog: "
                                                        + change.modelType().getName());
            }
        }
    }
}
