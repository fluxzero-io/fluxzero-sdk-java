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
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.util.Arrays;
import java.util.Optional;

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
        ModelMetadata.RootConfiguration model =
                ModelMetadata.validate(modelType)
                        .rootConfiguration()
                        .orElseThrow(() ->
                                             new IllegalArgumentException(
                                                     modelType.getName()
                                                     + " is not an independent model"));
        if (model.kind()
            != ModelMetadata.RootKind.MODEL
            || model.graphProjection() == null
            || !model.graphProjection().enabled()) {
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
}
