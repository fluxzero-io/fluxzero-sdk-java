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
 * Metadata-only access plan for one component property.
 *
 * @param name logical property name
 * @param typeName erased property type name
 * @param genericTypeName generic property type name
 * @param readable whether generated runtimes may read the property
 * @param writable whether generated runtimes may write the property
 * @param annotationNames direct property annotation type names
 */
public record PropertyAccessPlanDescriptor(
        String name,
        String typeName,
        String genericTypeName,
        boolean readable,
        boolean writable,
        List<String> annotationNames) {

    public PropertyAccessPlanDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(genericTypeName, "genericTypeName");
        annotationNames = RegistryCollections.immutableList(Objects.requireNonNull(annotationNames, "annotationNames"));
    }
}
