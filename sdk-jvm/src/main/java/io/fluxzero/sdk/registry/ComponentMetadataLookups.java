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

import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.sdk.Fluxzero;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves component metadata lookup backends for JVM runtime code.
 * <p>
 * Generated or registered component registries win. JVM classpath scanning is the compatibility fallback.
 */
public final class ComponentMetadataLookups {
    /**
     * Runtime metadata mode property. The default is hybrid mode: generated/registered metadata wins, with JVM
     * classpath scanning as compatibility fallback.
     */
    public static final String METADATA_MODE_PROPERTY = "fluxzero.metadata.mode";

    /**
     * Environment variable alias for {@link #METADATA_MODE_PROPERTY}.
     */
    public static final String METADATA_MODE_ENV = "FLUXZERO_METADATA_MODE";

    /**
     * Metadata mode that forbids classpath/reflection fallback in the central resolver.
     */
    public static final String GENERATED_ONLY_MODE = "generated-only";

    private static final ConcurrentMap<ClassLoader, ComponentRegistry> generatedRegistries = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> generatedOnlyModeOverride = new ThreadLocal<>();

    private ComponentMetadataLookups() {
    }

    /**
     * Returns the best metadata lookup for the supplied component types.
     */
    public static Optional<ComponentMetadataLookup> lookup(Class<?>... types) {
        List<Class<?>> componentTypes = componentTypes(types);
        if (componentTypes.isEmpty()) {
            return Optional.empty();
        }
        return activeRegistryLookup(componentTypes)
                .or(() -> generatedRegistryLookup(componentTypes))
                .or(() -> jvmLookup(componentTypes));
    }

    /**
     * Returns registry metadata for the supplied component types.
     * <p>
     * Generated registry resources win. JVM classpath scanning is used only in hybrid/compatibility mode.
     */
    public static ComponentRegistry registryFor(Collection<Class<?>> types) {
        List<Class<?>> componentTypes = componentTypes(types).stream()
                .filter(JvmComponentMetadataLookup::isScannable)
                .toList();
        if (componentTypes.isEmpty()) {
            return ComponentRegistry.empty();
        }
        return generatedRegistryLookup(componentTypes)
                .or(() -> jvmLookup(componentTypes))
                .map(ComponentMetadataLookup::registry)
                .orElseGet(ComponentRegistry::empty);
    }

    static Optional<ComponentMetadataLookup> lookup(ComponentRegistry registry, Class<?>... types) {
        List<Class<?>> componentTypes = componentTypes(types);
        return registryLookup(registry, componentTypes);
    }

    static Optional<ComponentMetadataLookup> lookupGenerated(ClassLoader classLoader, Class<?>... types) {
        List<Class<?>> componentTypes = componentTypes(types);
        return componentTypes.isEmpty() ? Optional.empty()
                : registryLookup(generatedRegistry(classLoader), componentTypes);
    }

    /**
     * Returns metadata annotations for the supplied executable.
     */
    public static List<AnnotationDescriptor> executableAnnotations(
            ComponentMetadataLookup lookup, Executable executable) {
        return executable(lookup, executable)
                .map(ExecutableDescriptor::annotations)
                .orElseGet(List::of);
    }

    /**
     * Projects type-level metadata annotations to a JVM annotation view.
     */
    public static <A extends Annotation> Optional<A> typeAnnotation(
            ComponentMetadataLookup lookup, Class<?> type, Class<A> annotationType) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        return MetadataAnnotationResolver.annotation(lookup.typeAnnotations(type.getName()), annotationType, type)
                .filter(annotationType::isInstance)
                .map(annotationType::cast);
    }

    /**
     * Projects type-level metadata annotation attributes to the supplied JVM projection type.
     */
    public static <A extends Annotation, R> Optional<R> typeAnnotationAs(
            ComponentMetadataLookup lookup, Class<?> type, Class<A> annotationType, Class<R> projectionType) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        Objects.requireNonNull(projectionType, "projectionType");
        return MetadataAnnotationResolver.annotationAs(
                lookup.typeAnnotations(type.getName()), annotationType, projectionType, type);
    }

    /**
     * Resolves a type-level annotation through the active metadata lookup.
     */
    public static <A extends Annotation> Optional<A> typeAnnotation(Class<?> type, Class<A> annotationType) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        return lookup(type).flatMap(lookup -> typeAnnotation(lookup, type, annotationType));
    }

    /**
     * Resolves and projects a type-level annotation through the active metadata lookup.
     */
    public static <A extends Annotation, R> Optional<R> typeAnnotationAs(
            Class<?> type, Class<A> annotationType, Class<R> projectionType) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        Objects.requireNonNull(projectionType, "projectionType");
        return lookup(type).flatMap(lookup -> typeAnnotationAs(lookup, type, annotationType, projectionType));
    }

    /**
     * Projects metadata annotation attributes to the supplied JVM projection type.
     */
    public static <A extends Annotation, R> Optional<R> annotationAs(
            List<AnnotationDescriptor> annotations, Class<A> annotationType, Class<R> projectionType,
            Class<?> declaringClass) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(annotationType, "annotationType");
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(declaringClass, "declaringClass");
        return MetadataAnnotationResolver.annotationAs(annotations, annotationType, projectionType, declaringClass);
    }

    /**
     * Projects package-level metadata annotations to a JVM annotation view, nearest package first.
     */
    public static <A extends Annotation> Optional<A> packageAnnotation(
            ComponentMetadataLookup lookup, Class<?> anchorType, Class<A> annotationType) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(anchorType, "anchorType");
        Objects.requireNonNull(annotationType, "annotationType");
        return MetadataAnnotationResolver.annotation(
                        lookup.packageAnnotations(anchorType.getPackageName()), annotationType, anchorType)
                .filter(annotationType::isInstance)
                .map(annotationType::cast);
    }

    /**
     * Resolves a package-level annotation through the active metadata lookup.
     */
    public static <A extends Annotation> Optional<A> packageAnnotation(Class<?> anchorType, Class<A> annotationType) {
        Objects.requireNonNull(anchorType, "anchorType");
        Objects.requireNonNull(annotationType, "annotationType");
        return lookup(anchorType).flatMap(lookup -> packageAnnotation(lookup, anchorType, annotationType));
    }

    /**
     * Resolves a type-level annotation first, then the nearest package-level annotation.
     */
    public static <A extends Annotation> Optional<A> typeOrPackageAnnotation(
            Class<?> type, Class<A> annotationType) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        return lookup(type).flatMap(lookup -> typeAnnotation(lookup, type, annotationType)
                .or(() -> packageAnnotation(lookup, type, annotationType)));
    }

    /**
     * Finds executable metadata matching the supplied JVM executable.
     */
    public static Optional<ExecutableDescriptor> executable(ComponentMetadataLookup lookup, Executable executable) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(executable, "executable");
        ExecutableKind kind = executable instanceof Constructor<?> ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
        String name = executable.getName();
        List<String> parameters = Arrays.stream(executable.getParameterTypes())
                .map(ComponentMetadataLookups::typeName)
                .toList();
        Optional<ExecutableDescriptor> result = lookup.executable(
                executable.getDeclaringClass().getName(), kind, name, parameters);
        if (result.isPresent() || executable instanceof Method) {
            return result;
        }
        return lookup.executables(executable.getDeclaringClass().getName()).stream()
                .filter(descriptor -> descriptor.kind() == ExecutableKind.CONSTRUCTOR)
                .filter(descriptor -> descriptor.parameters().stream().map(ParameterDescriptor::typeName).toList()
                        .equals(parameters))
                .findFirst();
    }

    /**
     * Finds parameter metadata matching the supplied JVM parameter.
     */
    public static Optional<ParameterDescriptor> parameter(ComponentMetadataLookup lookup, Parameter parameter) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(parameter, "parameter");
        Executable executable = parameter.getDeclaringExecutable();
        int index = parameterIndex(parameter);
        if (index < 0) {
            return Optional.empty();
        }
        return executable(lookup, executable)
                .filter(descriptor -> descriptor.parameters().size() > index)
                .map(descriptor -> descriptor.parameters().get(index));
    }

    /**
     * Returns whether parameter metadata carries the supplied annotation.
     */
    public static boolean hasParameterAnnotation(
            ComponentMetadataLookup lookup, Parameter parameter, Class<?> annotationType) {
        return parameter(lookup, parameter)
                .map(descriptor -> hasAnnotation(descriptor.annotations(), annotationType))
                .orElse(false);
    }

    /**
     * Returns whether the supplied metadata annotations contain, or are meta-annotated with, the supplied annotation.
     */
    public static boolean hasAnnotation(
            Iterable<AnnotationDescriptor> annotations, Class<?> annotationType) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(annotationType, "annotationType");
        for (AnnotationDescriptor annotation : annotations) {
            if (annotation.isOrHas(annotationType.getSimpleName(), annotationType.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the first property in metadata that carries the supplied annotation.
     */
    public static Optional<PropertyDescriptor> annotatedProperty(
            ComponentMetadataLookup lookup, Class<?> type, Class<?> annotationType) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        return lookup.properties(type.getName()).stream()
                .filter(property -> hasAnnotation(property.annotations(), annotationType))
                .findFirst();
    }

    /**
     * Finds the first property name in metadata that carries the supplied annotation.
     */
    public static Optional<String> annotatedPropertyName(
            ComponentMetadataLookup lookup, Class<?> type, Class<?> annotationType) {
        return annotatedProperty(lookup, type, annotationType).map(PropertyDescriptor::name);
    }

    /**
     * Returns properties in metadata that carry the supplied annotation.
     */
    public static List<PropertyDescriptor> annotatedProperties(
            ComponentMetadataLookup lookup, Class<?> type, Class<?> annotationType) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationType, "annotationType");
        return lookup.properties(type.getName()).stream()
                .filter(property -> hasAnnotation(property.annotations(), annotationType))
                .toList();
    }

    /**
     * Returns whether executable metadata carries the supplied annotation.
     */
    public static boolean hasExecutableAnnotation(
            ComponentMetadataLookup lookup, Executable executable, Class<?> annotationType) {
        return hasAnnotation(executableAnnotations(lookup, executable), annotationType);
    }

    private static Optional<ComponentMetadataLookup> activeRegistryLookup(List<Class<?>> types) {
        return Fluxzero.getOptionally()
                .map(Fluxzero::componentRegistry)
                .flatMap(registry -> registryLookup(registry, types));
    }

    private static Optional<ComponentMetadataLookup> generatedRegistryLookup(List<Class<?>> types) {
        return registryLookup(generatedRegistry(types.getFirst().getClassLoader()), types);
    }

    private static ComponentRegistry generatedRegistry(ClassLoader classLoader) {
        ClassLoader loader = classLoader == null ? ComponentMetadataLookups.class.getClassLoader() : classLoader;
        return generatedRegistries.computeIfAbsent(loader, key -> ComponentRegistry.merge(ComponentRegistryJson.load(key)));
    }

    private static Optional<ComponentMetadataLookup> registryLookup(ComponentRegistry registry, List<Class<?>> types) {
        if (registry == null || registry.isEmpty()) {
            return Optional.empty();
        }
        ComponentRegistry normalized = registry.normalized();
        return containsAll(normalized, types) ? Optional.of(RegistryComponentMetadataLookup.of(normalized))
                : Optional.empty();
    }

    private static Optional<ComponentMetadataLookup> jvmLookup(List<Class<?>> types) {
        if (generatedOnlyMode()) {
            return Optional.empty();
        }
        return types.stream().allMatch(JvmComponentMetadataLookup::isScannable)
                ? Optional.of(JvmComponentMetadataLookup.scan(types)) : Optional.empty();
    }

    /**
     * Returns whether the central metadata resolver is configured to refuse JVM classpath/reflection fallback.
     */
    public static boolean generatedOnlyMode() {
        if (Boolean.TRUE.equals(generatedOnlyModeOverride.get())) {
            return true;
        }
        String configured = System.getProperty(METADATA_MODE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(METADATA_MODE_ENV);
        }
        return GENERATED_ONLY_MODE.equalsIgnoreCase(configured)
               || "generatedOnly".equalsIgnoreCase(configured);
    }

    static void runInGeneratedOnlyMode(ThrowingRunnable runnable) throws Exception {
        Boolean previous = generatedOnlyModeOverride.get();
        generatedOnlyModeOverride.set(Boolean.TRUE);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                generatedOnlyModeOverride.remove();
            } else {
                generatedOnlyModeOverride.set(previous);
            }
        }
    }

    private static boolean containsAll(ComponentRegistry registry, List<Class<?>> types) {
        return types.stream().allMatch(type -> registry.findComponent(type.getName()).isPresent());
    }

    private static List<Class<?>> componentTypes(Class<?>... types) {
        Objects.requireNonNull(types, "types");
        return Arrays.stream(types).filter(Objects::nonNull).distinct().toList();
    }

    private static List<Class<?>> componentTypes(Collection<Class<?>> types) {
        Objects.requireNonNull(types, "types");
        return types.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private static int parameterIndex(Parameter parameter) {
        Parameter[] parameters = parameter.getDeclaringExecutable().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].equals(parameter)) {
                return i;
            }
        }
        return -1;
    }
}
