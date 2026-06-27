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

package io.fluxzero.sdk.registry;

import java.util.List;
import java.util.Objects;

/**
 * Metadata-only binding plan for one executable parameter.
 *
 * @param index zero-based parameter index
 * @param name source or classfile parameter name
 * @param typeName erased parameter type name
 * @param annotationNames direct parameter annotation type names
 */
public record ParameterBindingDescriptor(
        int index,
        String name,
        String typeName,
        List<String> annotationNames) {

    public ParameterBindingDescriptor {
        if (index < 0) {
            throw new IllegalArgumentException("index may not be negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
        annotationNames = List.copyOf(Objects.requireNonNull(annotationNames, "annotationNames"));
    }
}
