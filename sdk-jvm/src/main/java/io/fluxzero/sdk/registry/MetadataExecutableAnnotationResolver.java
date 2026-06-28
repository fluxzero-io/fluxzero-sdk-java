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

import io.fluxzero.common.handling.ExecutableAnnotationResolver;
import io.fluxzero.common.handling.ExecutableView;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM annotation resolver that prefers generated component metadata over reflection.
 */
public final class MetadataExecutableAnnotationResolver implements ExecutableAnnotationResolver {
    private final ConcurrentMap<Class<?>, Optional<ComponentMetadataLookup>> lookups = new ConcurrentHashMap<>();

    private MetadataExecutableAnnotationResolver() {
    }

    /**
     * Creates a metadata-first executable annotation resolver.
     */
    public static MetadataExecutableAnnotationResolver create() {
        return new MetadataExecutableAnnotationResolver();
    }

    @Override
    public Optional<? extends Annotation> getAnnotation(
            Executable executable, Class<? extends Annotation> annotationType) {
        return getAnnotations(executable, annotationType).stream().findFirst();
    }

    /**
     * Projects executable annotation metadata to another shape, preferring generated registry metadata.
     */
    public <T> Optional<T> getAnnotationAs(
            Executable executable, Class<? extends Annotation> annotationType, Class<T> projectionType) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(annotationType, "annotationType");
        Objects.requireNonNull(projectionType, "projectionType");
        Optional<T> metadata = lookup(executable.getDeclaringClass())
                .flatMap(lookup -> ComponentMetadataLookups.executable(lookup, executable))
                .flatMap(executableMetadata -> MetadataAnnotationResolver.annotationAs(
                        executableMetadata.annotations(), annotationType, projectionType,
                        executable.getDeclaringClass()));
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata;
        }
        return getAnnotations(executable, annotationType).stream()
                .flatMap(annotation -> JvmComponentIntrospector.getInstance()
                        .getAnnotationAs(annotation, annotationType, projectionType).stream())
                .findFirst();
    }

    @Override
    public List<? extends Annotation> getAnnotations(
            Executable executable, Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(annotationType, "annotationType");
        List<AnnotationDescriptor> descriptors = lookup(executable.getDeclaringClass())
                .flatMap(metadata -> ComponentMetadataLookups.executable(metadata, executable))
                .map(metadata -> MetadataAnnotationResolver.descriptors(metadata.annotations(), annotationType))
                .orElseGet(List::of);
        if (!descriptors.isEmpty()) {
            return descriptors.stream()
                    .map(descriptor -> MetadataAnnotationResolver.annotationView(
                            annotationType, descriptor, executable.getDeclaringClass()))
                    .toList();
        }
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            return List.of();
        }
        return JvmComponentIntrospector.getInstance().getMethodAnnotations(executable, annotationType);
    }

    @Override
    public Optional<? extends Annotation> getAnnotation(
            ExecutableView executable, Class<? extends Annotation> annotationType) {
        return getAnnotations(executable, annotationType).stream().findFirst();
    }

    /**
     * Projects executable-view annotation metadata to another shape, preferring generated registry metadata.
     */
    public <T> Optional<T> getAnnotationAs(
            ExecutableView executable, Class<? extends Annotation> annotationType, Class<T> projectionType) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(annotationType, "annotationType");
        Objects.requireNonNull(projectionType, "projectionType");
        Optional<Class<?>> targetClass = executable.targetClass();
        Optional<T> metadata = targetClass
                .flatMap(this::lookup)
                .flatMap(lookup -> ComponentMetadataLookups.executable(lookup, executable))
                .flatMap(executableMetadata -> MetadataAnnotationResolver.annotationAs(
                        executableMetadata.annotations(), annotationType, projectionType,
                        targetClass.orElse(Object.class)));
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata;
        }
        return getAnnotations(executable, annotationType).stream()
                .flatMap(annotation -> JvmComponentIntrospector.getInstance()
                        .getAnnotationAs(annotation, annotationType, projectionType).stream())
                .findFirst();
    }

    @Override
    public List<? extends Annotation> getAnnotations(
            ExecutableView executable, Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(annotationType, "annotationType");
        Optional<Class<?>> targetClass = executable.targetClass();
        List<AnnotationDescriptor> descriptors = targetClass
                .flatMap(this::lookup)
                .flatMap(metadata -> ComponentMetadataLookups.executable(metadata, executable))
                .map(metadata -> MetadataAnnotationResolver.descriptors(metadata.annotations(), annotationType))
                .orElseGet(List::of);
        if (!descriptors.isEmpty()) {
            Class<?> declaringClass = targetClass.orElse(Object.class);
            return descriptors.stream()
                    .map(descriptor -> MetadataAnnotationResolver.annotationView(
                            annotationType, descriptor, declaringClass))
                    .toList();
        }
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            return List.of();
        }
        Optional<Executable> reflectionExecutable = executable.executable();
        if (reflectionExecutable.isPresent()) {
            return JvmComponentIntrospector.getInstance().getMethodAnnotations(
                    reflectionExecutable.get(), annotationType);
        }
        return executable.annotation(annotationType).stream().map(Annotation.class::cast).toList();
    }

    private Optional<ComponentMetadataLookup> lookup(Class<?> type) {
        return lookups.computeIfAbsent(type, ComponentMetadataLookups::lookup);
    }
}
