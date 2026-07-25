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

import io.fluxzero.sdk.common.Namespaced;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

/**
 * Repository for loading independently stored {@link Model models}.
 * <p>
 * Model identity is always the exact {@link Object#toString()} value of the supplied ID. A model type or annotation
 * name is never concatenated into the persisted key.
 */
public interface ModelRepository extends Namespaced<ModelRepository> {

    /**
     * Returns this repository scoped to the requested namespace.
     * <p>
     * Custom repositories that are not namespace-aware retain their behavior by returning the same instance.
     */
    @Override
    default ModelRepository forNamespace(String namespace) {
        return this;
    }

    /**
     * Loads a model using the type carried by a typed identifier.
     */
    default <T> Entity<T> load(@NonNull Id<T> modelId) {
        return load(modelId.toString(), modelId.getType());
    }

    /**
     * Loads a model by ID, inferring its requested type when the ID is typed.
     * <p>
     * An untyped ID requests {@link Object}; the repository may resolve the stored model type from its model head.
     */
    @SuppressWarnings("unchecked")
    default <T> Entity<T> load(@NotNull Object modelId) {
        return (Entity<T>) load(modelId.toString(),
                                modelId instanceof Id<?> id ? (Class<Object>) id.getType() : Object.class);
    }

    /**
     * Loads a model using the exact string representation of the supplied ID.
     */
    default <T> Entity<T> load(@NonNull Object modelId, @NonNull Class<T> modelType) {
        return load(modelId.toString(), modelType);
    }

    /**
     * Loads a model by its exact string ID and expected type.
     *
     * @param modelId   persisted model key; never decorated with model type metadata
     * @param modelType expected model type, or {@link Object} when it should be resolved from storage
     */
    <T> Entity<T> load(@NonNull String modelId, @NonNull Class<T> modelType);
}
