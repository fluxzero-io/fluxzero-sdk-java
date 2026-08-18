/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Converts cached {@link Model} metadata to its durable graph-projection protocol definition.
 */
public final class ModelGraphProjections {

    private ModelGraphProjections() {
    }

    /**
     * Returns the configured materialized graph projection, if enabled.
     */
    public static Optional<ModelGraphProjectionConfiguration> configuration(
            Class<?> modelType) {
        EntityMetadata.RootConfiguration model =
                EntityMetadata.validate(modelType)
                        .rootConfiguration()
                        .orElseThrow(() ->
                                             new IllegalArgumentException(
                                                     modelType.getName()
                                                     + " is not an independent model"));
        if (model.kind()
            != EntityMetadata.RootKind.MODEL
            || model.graphProjection() == null
            || !model.materializeGraph()) {
            return Optional.empty();
        }
        GraphProjection projection =
                model.graphProjection();
        String rootCollection =
                model.collection().isEmpty()
                        ? modelType.getSimpleName()
                        : ApplicationProperties
                                .substituteProperties(
                                        model.collection());
        String collection = projection.collection().isEmpty()
                ? rootCollection + "-graphs"
                : ApplicationProperties.substituteProperties(projection.collection());
        if (rootCollection.equals(collection)) {
            throw new IllegalStateException(
                    "Graph projection collection on %s must differ from its direct-model collection '%s'"
                            .formatted(modelType.getName(), rootCollection));
        }
        return Optional.of(
                new ModelGraphProjectionConfiguration(
                        modelType.getName(),
                        rootCollection,
                        collection,
                        ModelGraphComposition.builder().build(),
                        Arrays.stream(
                                        projection.pathOverrides())
                                .map(override ->
                                             new ModelGraphPathOverride(
                                                     override.path(),
                                                     override.projectionPath()))
                .toList()));
    }

    /** Returns every materialized projection root reachable from this model through parent relationships. */
    static List<Root> roots(Class<?> modelType) {
        EntityMetadata.validate(modelType);
        return ReflectionUtils.getTypeMetadata(modelType)
                .specializedMetadata(Roots.class, Roots::new)
                .values();
    }

    private record Roots(List<Root> values) {
        private Roots(Class<?> modelType) {
            this(inspect(modelType, new LinkedHashSet<>()));
        }

        private static List<Root> inspect(
                Class<?> modelType, Set<Class<?>> visited) {
            if (!visited.add(modelType)) {
                return List.of();
            }
            List<Root> result = new ArrayList<>();
            EntityMetadata metadata = EntityMetadata.of(modelType);
            metadata.model().flatMap(model -> configuration(modelType)
                            .map(configuration -> new Root(
                                    modelType, configuration, model.graphProjection())))
                    .ifPresent(result::add);
            metadata.parentReferences().stream()
                    .map(EntityMetadata.ParentReference::parentModelType)
                    .filter(java.util.Objects::nonNull)
                    .forEach(parent -> result.addAll(inspect(parent, visited)));
            return List.copyOf(result);
        }
    }

    record Root(
            Class<?> modelType,
            ModelGraphProjectionConfiguration configuration,
            GraphProjection projection) {
        String collection() {
            return configuration.getCollection();
        }
    }
}
