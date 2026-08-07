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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.modeling.Graph;

import java.util.Optional;
import java.util.List;

/**
 * Optional repository capability for resolving a typed ancestor from relationship identities before loading model
 * values. {@link Graph} uses this only for a detached, lazy graph node; repositories without this capability retain
 * the ordinary parent-by-parent fallback.
 */
public interface ModelAncestorResolver {

    /**
     * Exact relationship read boundary for ancestor resolution. An empty selector means current state. Pending
     * message-batch values are included only when {@code includeMessageBatch} is true.
     */
    record Boundary(
            Long stateIndex,
            String commitId,
            Integer substep,
            Long eventIndex,
            boolean includeMessageBatch) {

        public Boundary {
            int specified = (stateIndex == null ? 0 : 1)
                            + (commitId == null ? 0 : 1)
                            + (eventIndex == null ? 0 : 1);
            if (specified > 1) {
                throw new IllegalArgumentException(
                        "Specify one model state, commit, or event boundary");
            }
            if ((commitId == null) != (substep == null)) {
                throw new IllegalArgumentException(
                        "A model commit boundary requires both commitId and substep");
            }
            if (stateIndex != null && stateIndex < -1L) {
                throw new IllegalArgumentException(
                        "Model stateIndex must be at least -1");
            }
            if (commitId != null
                && (commitId.isBlank() || substep < 0)) {
                throw new IllegalArgumentException(
                        "A model commit boundary requires a non-blank commitId and non-negative substep");
            }
            if (eventIndex != null && eventIndex < 0L) {
                throw new IllegalArgumentException(
                        "Model eventIndex must be non-negative");
            }
            if (includeMessageBatch
                && (commitId != null || eventIndex != null)) {
                throw new IllegalArgumentException(
                        "Exact event boundaries cannot include pending message-batch state");
            }
        }

        public static Boundary current() {
            return new Boundary(null, null, null, null, true);
        }

        public static Boundary state(long stateIndex, boolean includeMessageBatch) {
            return new Boundary(stateIndex, null, null, null, includeMessageBatch);
        }

        public static Boundary commit(String commitId, int substep) {
            return new Boundary(null, commitId, substep, null, false);
        }

        public static Boundary event(long eventIndex) {
            return new Boundary(null, null, null, eventIndex, false);
        }
    }

    /**
     * Resolves and loads only the closest ancestor assignable to {@code ancestorType} at the graph's read boundary.
     * Intermediate parent values must remain unloaded.
     */
    <A> Optional<Graph<A>> loadAncestorGraph(
            String modelId,
            Class<?> modelType,
            Class<A> ancestorType,
            Boundary boundary);

    /**
     * Resolves every reachable ancestor assignable to {@code ancestorType} at one boundary.
     * <p>
     * The singular method remains the ergonomic default for normal graph traversal. Change subscriptions use this
     * form because one changed model may be shared by multiple roots.
     */
    default <A> List<Graph<A>> loadAncestorGraphs(
            String modelId,
            Class<?> modelType,
            Class<A> ancestorType,
            Boundary boundary) {
        return loadAncestorGraph(modelId, modelType, ancestorType, boundary)
                .stream().toList();
    }
}
