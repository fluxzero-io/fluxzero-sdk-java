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

import io.fluxzero.common.application.PropertySource;
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.util.Objects;

import static io.fluxzero.sdk.configuration.ApplicationProperties.MODEL_NAME_PREFIX_PROPERTY;

/** Central resolver for stable logical Model names. */
public final class ModelNames {

    private ModelNames() {
    }

    /** Resolves a Model name using the active application's configured literal prefix. */
    public static String name(Class<?> modelType) {
        return name(modelType, ApplicationProperties.getProperty(MODEL_NAME_PREFIX_PROPERTY, ""));
    }

    /** Resolves a Model name using a prefix read from the supplied application property source. */
    public static String name(Class<?> modelType, PropertySource propertySource) {
        Objects.requireNonNull(propertySource, "Property source");
        return name(modelType, propertySource.get(MODEL_NAME_PREFIX_PROPERTY));
    }

    /** Resolves a Model name using an already application-scoped literal prefix. */
    public static String name(Class<?> modelType, String prefix) {
        String resolvedPrefix = validatePrefix(prefix);
        return resolvedPrefix + EntityMetadata.validate(modelType).localModelName();
    }

    private static String validatePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        if (prefix.isBlank() || !prefix.equals(prefix.trim())) {
            throw new IllegalArgumentException(
                    "%s must not be blank or have surrounding whitespace"
                            .formatted(MODEL_NAME_PREFIX_PROPERTY));
        }
        return prefix;
    }
}
