/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.configuration;

/**
 * A functional interface representing a resource with substitutable properties. This interface is designed to provide a
 * mechanism for substituting placeholders in the properties of a resource with resolved values. The resource
 * implementing this interface is expected to produce a new instance with all necessary substitutions applied.
 * <p>
 * Implementing classes should define the logic for resolving and substituting placeholder values from the application's
 * configuration or environment.
 *
 * @param <T> the type of the resource implementing this interface
 */
@FunctionalInterface
public interface Substitutable<T> {
    /**
     * Replaces placeholders in the properties of the current resource with resolved values from application properties
     * and returns a new instance of type {@link T}.
     *
     * @return a new instance of the resource with substituted properties.
     */
    T substituteProperties();
}
