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

import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelRelationshipRead;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.ModelBatchScope;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Optional repository capability for metadata-only Graph navigation. Queries return complete direct collections for
 * the supplied identities at a shared boundary. A repository may additionally return complete ancestor collections
 * when its protocol reads an ancestor closure; descendants are loaded one frontier at a time. Returned value
 * suppliers must preserve that boundary and never skip replay errors.
 * The {@code historical} mode denotes an explicitly historical read, not merely a pinned current boundary: pinning
 * must not replace a Model's current document authority with event replay.
 * Repositories without this capability retain their existing fully loaded Graph navigation.
 */
public interface ModelGraphResolver {
    /** Captures the current message-batch overlay once for a new navigation view. */
    ModelBatchScope.Snapshot graphStagedValues(ModelReadBoundary boundary);

    /** Loads deliberately current state with its namespace boundary, ignoring any active handler boundary. */
    Value loadCurrentGraphValue(Object modelId, Class<?> modelType);

    /** Strictly materializes a full graph at the selected boundary without consulting later batch state. */
    Graph<?> loadGraphProjection(String rootId, Class<?> rootType, ModelReadBoundary boundary, boolean historical);

    /**
     * Materializes relationships while retaining a root value whose identity or absence was already resolved.
     * A resolver opting into metadata-only identity resolution must implement this operation for absent roots;
     * silently resolving the supplied identity again could bind it to a different model.
     */
    default Graph<?> loadGraphProjection(String rootId, Class<?> rootType, ModelReadBoundary boundary,
                                        boolean historical, Entity<?> resolvedRoot) {
        throw new UnsupportedOperationException("Graph resolver must retain the resolved root identity: " + rootId);
    }

    /** Loads a source value and retains the coherent boundary that ordinary single-value APIs do not expose. */
    Value loadGraphValue(Object modelId, boolean exact, Class<?> modelType,
                         ModelReadBoundary boundary);

    /**
     * Resolves an identity without requiring its value. The returned supplier must retain the resolved identity,
     * absence and boundary even if the alias changes later. Returning {@code null} (the default) retains a custom
     * repository's existing value-based identity lookup, without opting into metadata-only identity resolution.
     */
    default Identity resolveGraphIdentity(Object modelId, Class<?> modelType, ModelReadBoundary boundary) {
        return null;
    }

    /** One resolved identity and lazy value at a shared boundary; absent identities must not be resolved again. */
    record Identity(String modelId, boolean present, ModelReadBoundary boundary, boolean historical,
                    Supplier<Entity<?>> entity) {
    }

    /** Reconstructs explicitly requested values together; unrelated metadata nodes must remain unloaded. */
    Map<String, Entity<?>> loadGraphValues(Map<String, Class<?>> modelTypes, ModelReadBoundary boundary,
                                          Map<String, Entity<?>> staged, boolean historical);

    /** A source value together with the exact relationship boundary established while reading it. */
    record Value(Entity<?> entity, ModelReadBoundary boundary, boolean historical) {
    }

    /**
     * Reads direct relationships and heads without loading values. The supplied staged snapshot takes precedence
     * over durable relationships and must also be used by returned value suppliers. No implicit later batch reads.
     */
    Relations loadGraphRelations(List<String> modelIds, ModelRelationshipRead.Direction direction,
                                ModelReadBoundary boundary, Map<String, Entity<?>> staged, boolean historical);

    /** One head with optional local class knowledge and a lazy, exact-boundary authoritative value. */
    record ModelNode(String id, String modelName, Class<?> knownType, Supplier<Entity<?>> entity) {
    }

    /** Coherent metadata for the requested direct relationship collections, including empty ones. */
    record Relations(ModelReadBoundary boundary, Map<String, ModelNode> models, List<ModelGraphEdge> edges,
                     Set<String> completeCollections, boolean historical) {
        public Relations {
            models = Map.copyOf(models);
            edges = List.copyOf(edges);
            completeCollections = Set.copyOf(completeCollections);
        }
    }
}
