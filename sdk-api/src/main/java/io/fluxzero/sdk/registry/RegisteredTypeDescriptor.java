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
 * Incubating source-level metadata for a {@code @RegisterType} declaration.
 *
 * @param root registered package or type root
 * @param contains optional name filters
 * @param candidateTypeNames source types in the scan that match this declaration
 * @param annotation source annotation descriptor
 */
public record RegisteredTypeDescriptor(
        String root,
        List<String> contains,
        List<String> candidateTypeNames,
        AnnotationDescriptor annotation) {

    public RegisteredTypeDescriptor {
        Objects.requireNonNull(root, "root");
        contains = RegistryCollections.immutableList(Objects.requireNonNull(contains, "contains"));
        candidateTypeNames = RegistryCollections.immutableList(Objects.requireNonNull(candidateTypeNames, "candidateTypeNames"));
        Objects.requireNonNull(annotation, "annotation");
    }
}
