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
     * Resolves and loads only the closest ancestor assignable to {@code ancestorType} at the graph's read boundary.
     * Intermediate parent values must remain unloaded.
     */
    <A> Optional<Graph<A>> loadAncestorGraph(
            String modelId,
            Class<?> modelType,
            Class<A> ancestorType,
            ModelReadBoundary boundary);

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
            ModelReadBoundary boundary) {
        return loadAncestorGraph(modelId, modelType, ancestorType, boundary)
                .stream().toList();
    }
}
