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
 * Incubating source-level metadata for a component property, field, or record component.
 *
 * @param name property name
 * @param typeName resolved erased property type name
 * @param genericTypeName resolved generic property type name when available
 * @param annotations source annotations on the property
 * @param typeUse source annotations on the property type use
 */
public record PropertyDescriptor(
        String name,
        String typeName,
        String genericTypeName,
        List<AnnotationDescriptor> annotations,
        TypeUseDescriptor typeUse) {

    public PropertyDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(genericTypeName, "genericTypeName");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        typeUse = typeUse == null ? TypeUseDescriptor.EMPTY : typeUse;
    }

    public PropertyDescriptor(
            String name,
            String typeName,
            String genericTypeName,
            List<AnnotationDescriptor> annotations) {
        this(name, typeName, genericTypeName, annotations, TypeUseDescriptor.EMPTY);
    }
}
