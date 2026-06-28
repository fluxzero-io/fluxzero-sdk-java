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

import io.fluxzero.common.Registration;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.sdk.Fluxzero;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

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

    /**
     * Metadata mode that also forbids migration-debt JVM backend categories.
     */
    public static final String STRICT_GENERATED_ONLY_MODE = "strict-generated-only";

    private static final ConcurrentMap<GeneratedRegistryClassLoaderKey, ComponentRegistry> generatedRegistries =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<GeneratedRegistryClassLoaderKey, ComponentMetadataLookup>
            generatedRegistryLookups = new ConcurrentHashMap<>();
    private static final ConcurrentMap<GeneratedExecutionRegistrationKey, Registration> generatedExecutionRegistrations =
            new ConcurrentHashMap<>();
    private static final int REGISTRY_LOOKUP_CACHE_SIZE = 256;
    private static final Map<IdentityRegistryKey, ComponentMetadataLookup> registryLookupCache =
            Collections.synchronizedMap(new LinkedHashMap<>(REGISTRY_LOOKUP_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<IdentityRegistryKey, ComponentMetadataLookup> eldest) {
                    return size() > REGISTRY_LOOKUP_CACHE_SIZE;
                }
            });
    private static final ThreadLocal<Boolean> generatedOnlyModeOverride = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> strictGeneratedOnlyModeOverride = new ThreadLocal<>();

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
     * Returns generated or explicitly registered metadata for the supplied component types without using the JVM
     * classpath-scanning compatibility fallback.
     */
    public static Optional<ComponentMetadataLookup> registeredLookup(Class<?>... types) {
        List<Class<?>> componentTypes = componentTypes(types);
        if (componentTypes.isEmpty()) {
            return Optional.empty();
        }
        return activeRegistryLookup(componentTypes)
                .or(() -> generatedRegistryLookup(componentTypes));
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
        ComponentRegistry generatedRegistry = generatedRegistry(componentTypes.getFirst().getClassLoader());
        ComponentRegistry generatedSubset = registrySubset(generatedRegistry, componentTypes);
        if (!generatedSubset.isEmpty()) {
            return generatedSubset;
        }
        return jvmLookup(componentTypes).map(ComponentMetadataLookup::registry).orElseGet(ComponentRegistry::empty);
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
     * Installs generated execution handles from generated registry resources for the supplied component classloaders.
     * <p>
     * This is useful for metadata consumers that can run outside an active {@link Fluxzero} instance, such as consumer
     * configuration derivation. Explicitly registered runtime registries are still installed by
     * {@code Fluxzero.registerComponentRegistry}.
     */
    public static void ensureGeneratedExecutions(Class<?>... types) {
        Map<ClassLoader, List<Class<?>>> typesByLoader = new LinkedHashMap<>();
        for (Class<?> type : componentTypes(types)) {
            typesByLoader.computeIfAbsent(classLoader(type), ignored -> new ArrayList<>()).add(type);
        }
        for (Map.Entry<ClassLoader, List<Class<?>>> entry : typesByLoader.entrySet()) {
            ComponentRegistry registry = generatedRegistry(entry.getKey());
            ComponentMetadataLookup lookup = generatedRegistryLookup(entry.getKey());
            ensureGeneratedExecutions(registry, lookup, entry.getKey(), entry.getValue());
        }
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
     * Returns metadata annotations for the supplied executable metadata view.
     */
    public static List<AnnotationDescriptor> executableAnnotations(
            ComponentMetadataLookup lookup, ExecutableView executable) {
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
        return metadataTypeCandidates(type).stream()
                .flatMap(candidate -> MetadataAnnotationResolver.annotationProjection(
                        typeAnnotations(lookup, candidate).toList(), annotationType, candidate).stream())
                .findFirst();
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
        return metadataTypeCandidates(type).stream()
                .flatMap(candidate -> MetadataAnnotationResolver.annotationAs(
                        typeAnnotations(lookup, candidate).toList(), annotationType, projectionType, candidate)
                        .stream())
                .findFirst();
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
     * Projects all matching metadata annotations to JVM annotation views.
     */
    public static <A extends Annotation> List<A> annotations(
            List<AnnotationDescriptor> annotations, Class<A> annotationType, Class<?> declaringClass) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(annotationType, "annotationType");
        Objects.requireNonNull(declaringClass, "declaringClass");
        return MetadataAnnotationResolver.descriptors(annotations, annotationType).stream()
                .map(descriptor -> MetadataAnnotationResolver.annotationProjection(
                        descriptor, annotationType, declaringClass))
                .toList();
    }

    /**
     * Projects metadata annotations to JVM annotation views for annotation types available on the classpath.
     */
    public static List<Annotation> annotationViews(
            List<AnnotationDescriptor> annotations, Class<?> declaringClass) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(declaringClass, "declaringClass");
        return annotations.stream()
                .map(descriptor -> MetadataAnnotationResolver.annotationView(descriptor, declaringClass))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Projects package-level metadata annotations to a JVM annotation view, nearest package first.
     */
    public static <A extends Annotation> Optional<A> packageAnnotation(
            ComponentMetadataLookup lookup, Class<?> anchorType, Class<A> annotationType) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(anchorType, "anchorType");
        Objects.requireNonNull(annotationType, "annotationType");
        return MetadataAnnotationResolver.annotationProjection(
                lookup.packageAnnotations(anchorType.getPackageName()), annotationType, anchorType);
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
        Set<String> targetTypeNames = executableTargetTypeNames(executable);
        Optional<ExecutableDescriptor> result = targetTypeNames.stream()
                .map(targetTypeName -> lookup.executable(targetTypeName, kind, name, parameters))
                .flatMap(Optional::stream)
                .findFirst();
        if (result.isPresent() || executable instanceof Method) {
            return result;
        }
        return targetTypeNames.stream()
                .flatMap(targetTypeName -> lookup.executables(targetTypeName).stream())
                .filter(descriptor -> descriptor.kind() == ExecutableKind.CONSTRUCTOR)
                .filter(descriptor -> descriptor.parameters().stream().map(ParameterDescriptor::typeName).toList()
                        .equals(parameters))
                .findFirst();
    }

    /**
     * Finds executable metadata matching the supplied executable metadata view.
     */
    public static Optional<ExecutableDescriptor> executable(ComponentMetadataLookup lookup, ExecutableView executable) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(executable, "executable");
        ExecutableKind kind = executable.kind() == ExecutableView.Kind.CONSTRUCTOR
                ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
        List<String> parameters = executable.parameters().stream()
                .map(ParameterView::typeName)
                .toList();
        for (String targetTypeName : targetTypeNames(executable)) {
            Optional<ExecutableDescriptor> result = lookup.executable(
                    targetTypeName, kind, executable.name(), parameters);
            if (result.isPresent()) {
                return result;
            }
        }
        if (kind == ExecutableKind.METHOD) {
            return Optional.empty();
        }
        return targetTypeNames(executable).stream()
                .flatMap(targetTypeName -> lookup.executables(targetTypeName).stream())
                .filter(descriptor -> descriptor.kind() == ExecutableKind.CONSTRUCTOR)
                .filter(descriptor -> descriptor.parameters().stream().map(ParameterDescriptor::typeName).toList()
                        .equals(parameters))
                .findFirst();
    }

    /**
     * Finds invocation plan metadata matching the supplied JVM executable.
     */
    public static Optional<InvocationPlanDescriptor> invocationPlan(
            ComponentMetadataLookup lookup, Executable executable) {
        return executable(lookup, executable).map(descriptor -> lookup.invocationPlan(
                executable.getDeclaringClass().getName(),
                descriptor.kind(),
                descriptor.name(),
                descriptor.parameters().stream().map(ParameterDescriptor::typeName).toList()).orElseThrow());
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
        return metadataTypeCandidates(type).stream()
                .flatMap(candidate -> properties(lookup, candidate))
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
        return metadataTypeCandidates(type).stream()
                .flatMap(candidate -> properties(lookup, candidate))
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

    /**
     * Returns whether executable metadata carries the supplied annotation.
     */
    public static boolean hasExecutableAnnotation(
            ComponentMetadataLookup lookup, ExecutableView executable, Class<?> annotationType) {
        return hasAnnotation(executableAnnotations(lookup, executable), annotationType);
    }

    private static Optional<ComponentMetadataLookup> activeRegistryLookup(List<Class<?>> types) {
        return Fluxzero.getOptionally()
                .map(Fluxzero::componentRegistry)
                .flatMap(registry -> registryLookup(registry, types));
    }

    private static Optional<ComponentMetadataLookup> generatedRegistryLookup(List<Class<?>> types) {
        ClassLoader loader = classLoader(types.getFirst());
        ComponentRegistry registry = generatedRegistry(loader);
        ComponentMetadataLookup lookup = generatedRegistryLookup(loader);
        ensureGeneratedExecutions(registry, lookup, loader, types);
        return containsAll(lookup, types) ? Optional.of(lookup) : Optional.empty();
    }

    private static ComponentRegistry generatedRegistry(ClassLoader classLoader) {
        return generatedRegistry(generatedRegistryClassLoaderKey(classLoader));
    }

    private static ComponentMetadataLookup generatedRegistryLookup(ClassLoader classLoader) {
        GeneratedRegistryClassLoaderKey loaderKey = generatedRegistryClassLoaderKey(classLoader);
        return generatedRegistryLookups.computeIfAbsent(
                loaderKey, key -> RegistryComponentMetadataLookup.of(generatedRegistry(key)));
    }

    private static ComponentRegistry generatedRegistry(GeneratedRegistryClassLoaderKey loaderKey) {
        return generatedRegistries.computeIfAbsent(
                loaderKey, key -> ComponentRegistry.merge(ComponentRegistryJson.load(key.loaders())));
    }

    private static void ensureGeneratedExecutions(
            ComponentRegistry registry, ComponentMetadataLookup lookup, ClassLoader loader, Collection<Class<?>> types) {
        if (registry.isEmpty()) {
            return;
        }
        for (Class<?> type : componentTypes(types)) {
            for (Class<?> candidate : metadataTypeCandidates(type)) {
                for (String typeName : typeNames(candidate)) {
                    lookup.component(typeName).ifPresent(component -> {
                        GeneratedExecutionRegistrationKey registrationKey =
                                new GeneratedExecutionRegistrationKey(loader, component.fullClassName());
                        generatedExecutionRegistrations.computeIfAbsent(
                                registrationKey,
                                ignored -> JvmGeneratedExecutionInstaller.install(
                                        registry, loader, List.of(component.fullClassName())));
                    });
                }
            }
        }
    }

    private static Optional<ComponentMetadataLookup> registryLookup(ComponentRegistry registry, List<Class<?>> types) {
        if (registry == null || registry.isEmpty()) {
            return Optional.empty();
        }
        ComponentMetadataLookup lookup = cachedRegistryLookup(registry);
        return containsAll(lookup, types) ? Optional.of(lookup)
                : Optional.empty();
    }

    private static ComponentRegistry registrySubset(ComponentRegistry registry, List<Class<?>> types) {
        if (registry == null || registry.isEmpty()) {
            return ComponentRegistry.empty();
        }
        Map<String, ComponentDescriptor> components = new LinkedHashMap<>();
        ComponentMetadataLookup lookup = cachedRegistryLookup(registry);
        for (Class<?> type : types) {
            for (Class<?> candidate : metadataTypeCandidates(type)) {
                for (String name : typeNames(candidate)) {
                    lookup.component(name)
                            .ifPresent(component -> components.putIfAbsent(component.fullClassName(), component));
                }
            }
        }
        if (components.isEmpty()) {
            return ComponentRegistry.empty();
        }
        return new ComponentRegistry(registry.sourceRoot(), registry.packages(), List.copyOf(components.values()))
                .normalized();
    }

    private static Optional<ComponentMetadataLookup> jvmLookup(List<Class<?>> types) {
        if (generatedOnlyMode()) {
            return Optional.empty();
        }
        return types.stream().allMatch(JvmComponentMetadataLookup::isScannable)
                ? Optional.of(JvmComponentMetadataLookup.scan(types)) : Optional.empty();
    }

    private static ComponentMetadataLookup cachedRegistryLookup(ComponentRegistry registry) {
        synchronized (registryLookupCache) {
            return registryLookupCache.computeIfAbsent(
                    new IdentityRegistryKey(registry),
                    ignored -> RegistryComponentMetadataLookup.of(registry));
        }
    }

    /**
     * Returns whether the central metadata resolver is configured to refuse JVM classpath/reflection fallback.
     */
    public static boolean generatedOnlyMode() {
        if (strictGeneratedOnlyMode()) {
            return true;
        }
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

    /**
     * Returns whether generated-only mode should reject migration-debt JVM backend categories.
     */
    public static boolean strictGeneratedOnlyMode() {
        if (Boolean.TRUE.equals(strictGeneratedOnlyModeOverride.get())) {
            return true;
        }
        String configured = System.getProperty(METADATA_MODE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(METADATA_MODE_ENV);
        }
        return STRICT_GENERATED_ONLY_MODE.equalsIgnoreCase(configured)
               || "strictGeneratedOnly".equalsIgnoreCase(configured);
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

    static void runInStrictGeneratedOnlyMode(ThrowingRunnable runnable) throws Exception {
        Boolean previous = strictGeneratedOnlyModeOverride.get();
        strictGeneratedOnlyModeOverride.set(Boolean.TRUE);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                strictGeneratedOnlyModeOverride.remove();
            } else {
                strictGeneratedOnlyModeOverride.set(previous);
            }
        }
    }

    private static boolean containsAll(ComponentMetadataLookup lookup, List<Class<?>> types) {
        return types.stream().allMatch(type -> metadataTypeCandidates(type).stream()
                .anyMatch(candidate -> typeNames(candidate).stream()
                        .anyMatch(name -> lookup.component(name).isPresent())));
    }

    private static Set<String> targetTypeNames(ExecutableView executable) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(executable.targetTypeName());
        executable.targetClass().ifPresent(type -> {
            result.add(type.getName());
            result.add(typeName(type));
        });
        return result;
    }

    private static Set<String> executableTargetTypeNames(Executable executable) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Stream<Class<?>> types = executable instanceof Method
                ? metadataTypeCandidates(executable.getDeclaringClass()).stream()
                : Stream.of(executable.getDeclaringClass());
        types.flatMap(type -> typeNames(type).stream()).forEach(result::add);
        return result;
    }

    private static List<Class<?>> metadataTypeCandidates(Class<?> type) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        collectMetadataTypeCandidates(type, result);
        return List.copyOf(result);
    }

    private static void collectMetadataTypeCandidates(Class<?> type, LinkedHashSet<Class<?>> result) {
        if (type == null || Object.class.equals(type) || !result.add(type)) {
            return;
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectMetadataTypeCandidates(interfaceType, result);
        }
        collectMetadataTypeCandidates(type.getSuperclass(), result);
    }

    private static Stream<AnnotationDescriptor> typeAnnotations(ComponentMetadataLookup lookup, Class<?> type) {
        return typeNames(type).stream().flatMap(name -> lookup.typeAnnotations(name).stream());
    }

    private static Stream<PropertyDescriptor> properties(ComponentMetadataLookup lookup, Class<?> type) {
        return typeNames(type).stream().flatMap(name -> lookup.properties(name).stream());
    }

    private static List<String> typeNames(Class<?> type) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(type.getName());
        result.add(typeName(type));
        return List.copyOf(result);
    }

    private static List<Class<?>> componentTypes(Class<?>... types) {
        Objects.requireNonNull(types, "types");
        return Arrays.stream(types).filter(Objects::nonNull).distinct().toList();
    }

    private static List<Class<?>> componentTypes(Collection<Class<?>> types) {
        Objects.requireNonNull(types, "types");
        return types.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static ClassLoader classLoader(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null ? ComponentMetadataLookups.class.getClassLoader() : loader;
    }

    private static GeneratedRegistryClassLoaderKey generatedRegistryClassLoaderKey(ClassLoader classLoader) {
        ClassLoader primary = effectiveClassLoader(classLoader);
        ClassLoader context = effectiveClassLoader(Thread.currentThread().getContextClassLoader());
        ClassLoader sdk = effectiveClassLoader(ComponentMetadataLookups.class.getClassLoader());
        return new GeneratedRegistryClassLoaderKey(primary, context, sdk);
    }

    private static ClassLoader effectiveClassLoader(ClassLoader classLoader) {
        return classLoader == null ? ComponentMetadataLookups.class.getClassLoader() : classLoader;
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private static int parameterIndex(Parameter parameter) {
        Parameter[] parameters = parameter.getDeclaringExecutable().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].equals(parameter) || sameParameter(parameters[i], parameter)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameParameter(Parameter candidate, Parameter parameter) {
        return candidate.getDeclaringExecutable().equals(parameter.getDeclaringExecutable())
               && candidate.getParameterizedType().equals(parameter.getParameterizedType())
               && Objects.equals(candidate.getName(), parameter.getName());
    }

    private record GeneratedExecutionRegistrationKey(ClassLoader classLoader, String componentName) {
    }

    private record GeneratedRegistryClassLoaderKey(ClassLoader primary, ClassLoader context, ClassLoader sdk) {
        private List<ClassLoader> loaders() {
            LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
            loaders.add(primary);
            loaders.add(context);
            loaders.add(sdk);
            return List.copyOf(loaders);
        }
    }

    private static final class IdentityRegistryKey {
        private final ComponentRegistry registry;
        private final int hashCode;

        private IdentityRegistryKey(ComponentRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.hashCode = System.identityHashCode(registry);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityRegistryKey key && registry == key.registry;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
