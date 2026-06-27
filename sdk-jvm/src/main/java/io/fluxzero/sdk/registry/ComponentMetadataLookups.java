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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
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

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }
}
