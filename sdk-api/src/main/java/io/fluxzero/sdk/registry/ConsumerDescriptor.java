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
import java.util.Map;
import java.util.Objects;

/**
 * Incubating source-level metadata for a package or type-level {@code @Consumer} declaration.
 *
 * @param name consumer name
 * @param attributes raw source annotation attributes
 * @param annotation source annotation descriptor
 */
public record ConsumerDescriptor(
        String name,
        Map<String, List<String>> attributes,
        AnnotationDescriptor annotation) {

    public ConsumerDescriptor {
        Objects.requireNonNull(name, "name");
        attributes = RegistryCollections.immutableMap(Objects.requireNonNull(attributes, "attributes"));
        Objects.requireNonNull(annotation, "annotation");
    }
}
