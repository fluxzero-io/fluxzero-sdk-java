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
import java.util.Optional;

/**
 * Incubating source-level annotation metadata.
 *
 * @param name simple annotation name
 * @param qualifiedName fully qualified annotation name when it can be resolved from source imports
 * @param attributes annotation attributes, with unnamed values stored under {@code value}
 * @param metaAnnotations annotations present on this annotation type
 */
public record AnnotationDescriptor(
        String name,
        String qualifiedName,
        Map<String, List<String>> attributes,
        List<AnnotationDescriptor> metaAnnotations) {

    public AnnotationDescriptor(String name, String qualifiedName, Map<String, List<String>> attributes) {
        this(name, qualifiedName, attributes, List.of());
    }

    public AnnotationDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        metaAnnotations = List.copyOf(Objects.requireNonNull(metaAnnotations, "metaAnnotations"));
    }

    /**
     * Returns all values for an annotation attribute.
     */
    public List<String> values(String attribute) {
        return attributes.getOrDefault(attribute, List.of());
    }

    /**
     * Returns the first value for an annotation attribute.
     */
    public Optional<String> firstValue(String attribute) {
        return values(attribute).stream().findFirst();
    }

    /**
     * Returns a boolean annotation attribute, or the supplied default when absent.
     */
    public boolean booleanValue(String attribute, boolean defaultValue) {
        return firstValue(attribute).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /**
     * Returns whether this annotation is, or is meta-annotated with, the supplied annotation name.
     */
    public boolean isOrHas(String annotationName, String qualifiedAnnotationName) {
        return find(annotationName, qualifiedAnnotationName).isPresent();
    }

    /**
     * Finds this annotation or the nearest meta-annotation matching the supplied annotation name.
     */
    public Optional<AnnotationDescriptor> find(String annotationName, String qualifiedAnnotationName) {
        if (matches(annotationName, qualifiedAnnotationName)) {
            return Optional.of(this);
        }
        return metaAnnotations.stream()
                .map(annotation -> annotation.find(annotationName, qualifiedAnnotationName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private boolean matches(String annotationName, String qualifiedAnnotationName) {
        return Objects.equals(name, annotationName)
               || qualifiedAnnotationName != null && Objects.equals(qualifiedName, qualifiedAnnotationName);
    }
}
