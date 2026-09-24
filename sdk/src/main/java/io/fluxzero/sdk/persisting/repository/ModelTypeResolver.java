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

import java.util.Optional;

/** Resolves application-scoped logical Model names in both directions. */
public interface ModelTypeResolver {

    /** Returns and registers the stable logical name for one concrete Model type. */
    String modelName(Class<?> modelType);

    /** Resolves a stored logical name to a registered concrete Model type. */
    Class<?> modelType(String modelName, String modelId);

    /**
     * Looks up a logical name without interpreting missing local registration as corrupt stored data. Implementations
     * that do not support unknown types retain their strict resolution; application or registry failures still fail.
     */
    default Optional<Class<?>> knownModelType(String modelName, String modelId) {
        return Optional.of(modelType(modelName, modelId));
    }
}
