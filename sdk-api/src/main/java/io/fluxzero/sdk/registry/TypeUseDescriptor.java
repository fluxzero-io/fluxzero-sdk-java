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
 * Incubating source-level metadata for annotations attached to a Java type use.
 *
 * @param typeName resolved type name for this type use
 * @param annotations annotations declared on this type use
 * @param typeArguments nested generic type argument metadata
 * @param componentType nested array component metadata
 */
public record TypeUseDescriptor(
        String typeName,
        List<AnnotationDescriptor> annotations,
        List<TypeUseDescriptor> typeArguments,
        TypeUseDescriptor componentType) {

    public static final TypeUseDescriptor EMPTY = new TypeUseDescriptor(
            "java.lang.Object", List.of(), List.of(), null);

    public TypeUseDescriptor {
        Objects.requireNonNull(typeName, "typeName");
        annotations = RegistryCollections.immutableList(Objects.requireNonNull(annotations, "annotations"));
        typeArguments = RegistryCollections.immutableList(Objects.requireNonNull(typeArguments, "typeArguments"));
    }

    public TypeUseDescriptor(String typeName, List<AnnotationDescriptor> annotations) {
        this(typeName, annotations, List.of(), null);
    }
}
