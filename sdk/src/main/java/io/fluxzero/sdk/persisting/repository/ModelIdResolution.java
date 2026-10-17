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

import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.common.api.modeling.ModelReadBoundary;

/** Identity policy for repository Graph resolution; normalization and alias lookup are independent choices. */
public enum ModelIdResolution {
    /** Apply the model's ID conversion and affixes, retaining alias resolution. */
    FUNCTIONAL,
    /** Use the supplied string representation unchanged, retaining alias resolution. */
    LITERAL,
    /** Retain an already resolved canonical identity without redirecting it through aliases. */
    EXACT;

    /** Selects the primary repository key before alias lookup. */
    public String primaryId(Object id, Class<?> type) {
        return this == FUNCTIONAL && type != Object.class ? EntityMetadata.of(type).repositoryId(id) : id.toString();
    }

    /** Whether alias resolution must be suppressed. */
    public boolean exact() { return this == EXACT; }

    /** Converts the legacy exact-identity flag without changing its meaning. */
    public static ModelIdResolution ofExact(boolean exact) { return exact ? EXACT : FUNCTIONAL; }

    /** Dispatches existing policies through the original extension methods. */
    public ModelGraphResolver.Identity resolveIdentity(ModelGraphResolver resolver, Object id, Class<?> type,
                                                        ModelReadBoundary boundary) {
        return this == LITERAL ? resolver.resolveGraphIdentity(id, this, type, boundary)
                : resolver.resolveGraphIdentity(id, exact(), type, boundary);
    }

    /** Dispatches pinned identity reads while retaining existing extension methods. */
    public ModelGraphResolver.Identity resolveIdentity(ModelGraphResolver resolver, Object id, Class<?> type,
                                                        ModelReadBoundary boundary, boolean historical) {
        return this == LITERAL ? resolver.resolveGraphIdentity(id, this, type, boundary, historical)
                : resolver.resolveGraphIdentity(id, exact(), type, boundary, historical);
    }

    /** Dispatches source value reads while retaining existing extension methods. */
    public ModelGraphResolver.Value loadValue(ModelGraphResolver resolver, Object id, Class<?> type,
                                              ModelReadBoundary boundary) {
        return this == LITERAL ? resolver.loadGraphValue(id, this, type, boundary)
                : resolver.loadGraphValue(id, exact(), type, boundary);
    }

    /** Dispatches pinned value reads while retaining existing extension methods. */
    public ModelGraphResolver.Value loadValue(ModelGraphResolver resolver, Object id, Class<?> type,
                                              ModelReadBoundary boundary, boolean historical) {
        return this == LITERAL ? resolver.loadGraphValue(id, this, type, boundary, historical)
                : resolver.loadGraphValue(id, exact(), type, boundary, historical);
    }
}
