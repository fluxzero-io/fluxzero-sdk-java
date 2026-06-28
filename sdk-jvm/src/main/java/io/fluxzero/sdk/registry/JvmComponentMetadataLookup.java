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

import io.fluxzero.common.MessageType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JVM metadata lookup facade backed by classpath-scanned component descriptors.
 * <p>
 * This class is the reflection-backed implementation for the runtime-facing lookup facade. Reflection remains inside
 * `ClasspathComponentScanner` and `JvmComponentIntrospector`; callers consume metadata-shaped descriptors.
 */
public final class JvmComponentMetadataLookup implements ComponentMetadataLookup {
    private static final Map<String, Class<?>> PRIMITIVE_TYPES = Map.of(
            "boolean", boolean.class,
            "byte", byte.class,
            "char", char.class,
            "double", double.class,
            "float", float.class,
            "int", int.class,
            "long", long.class,
            "short", short.class,
            "void", void.class
    );

    private final RegistryComponentMetadataLookup delegate;

    private JvmComponentMetadataLookup(ComponentRegistry registry) {
        this.delegate = RegistryComponentMetadataLookup.of(registry);
    }

    /**
     * Creates a JVM lookup facade by scanning the supplied component classes.
     */
    public static JvmComponentMetadataLookup scan(Class<?>... componentTypes) {
        return scan(Arrays.asList(componentTypes));
    }

    /**
     * Returns whether the supplied JVM class can be represented as component metadata.
     */
    public static boolean isScannable(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return !type.isArray()
               && !type.isPrimitive()
               && !type.isSynthetic()
               && !type.getPackageName().startsWith("java.");
    }

    /**
     * Creates a JVM lookup facade when all supplied classes can be represented as component metadata.
     */
    public static Optional<JvmComponentMetadataLookup> scanIfScannable(Class<?>... componentTypes) {
        Objects.requireNonNull(componentTypes, "componentTypes");
        return Arrays.stream(componentTypes).allMatch(JvmComponentMetadataLookup::isScannable)
                ? Optional.of(scan(componentTypes)) : Optional.empty();
    }

    /**
     * Resolves a source/registry type name to a JVM class, including canonical nested class names.
     */
    public static Optional<Class<?>> classForMetadataName(String className) {
        return classForMetadataName(className, (ClassLoader) null);
    }

    /**
     * Resolves a source/registry type name to a JVM class using the supplied loader first.
     */
    public static Optional<Class<?>> classForMetadataName(String className, ClassLoader preferredLoader) {
        Objects.requireNonNull(className, "className");
        if (PRIMITIVE_TYPES.containsKey(className)) {
            return Optional.of(PRIMITIVE_TYPES.get(className));
        }
        if (className.endsWith("[]")) {
            return classForMetadataName(className.substring(0, className.length() - 2), preferredLoader)
                    .map(componentType -> Array.newInstance(componentType, 0).getClass());
        }
        Optional<Class<?>> result = loadClass(className, preferredLoader);
        if (result.isPresent()) {
            return result;
        }
        String[] segments = className.split("\\.");
        for (int typeStart = segments.length - 1; typeStart >= 0; typeStart--) {
            String packageName = String.join(".", Arrays.copyOfRange(segments, 0, typeStart));
            String binaryName = String.join("$", Arrays.copyOfRange(segments, typeStart, segments.length));
            String candidate = packageName.isEmpty() ? binaryName : packageName + "." + binaryName;
            if (candidate.equals(className)) {
                continue;
            }
            result = loadClass(candidate, preferredLoader);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private static Optional<Class<?>> loadClass(String className, ClassLoader preferredLoader) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader ownLoader = JvmComponentMetadataLookup.class.getClassLoader();
        for (ClassLoader loader : new ClassLoader[]{preferredLoader, contextLoader, ownLoader}) {
            if (loader == null) {
                continue;
            }
            try {
                return Optional.of(Class.forName(className, false, loader));
            } catch (ClassNotFoundException ignored) {
                // Try the next available loader.
            }
        }
        try {
            return Optional.of(Class.forName(className));
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a source/registry type name to a JVM class, returning the supplied default when it cannot be resolved.
     */
    public static Class<?> classForMetadataName(String className, Class<?> defaultValue) {
        Objects.requireNonNull(className, "className");
        return classForMetadataName(className).orElse(defaultValue);
    }

    /**
     * Creates a JVM lookup facade by scanning the supplied component classes.
     */
    public static JvmComponentMetadataLookup scan(Collection<Class<?>> componentTypes) {
        Objects.requireNonNull(componentTypes, "componentTypes");
        return new JvmComponentMetadataLookup(new ClasspathComponentScanner().scan(componentTypes));
    }

    /**
     * Creates a JVM lookup facade around an already materialized registry.
     */
    public static JvmComponentMetadataLookup of(ComponentRegistry registry) {
        return new JvmComponentMetadataLookup(registry);
    }

    @Override
    public ComponentRegistry registry() {
        return delegate.registry();
    }

    /**
     * Finds component metadata for the supplied JVM class.
     */
    public Optional<ComponentDescriptor> component(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return component(typeName(type));
    }

    /**
     * Returns component/type annotations for the supplied JVM class.
     */
    public List<AnnotationDescriptor> typeAnnotations(Class<?> type) {
        return typeAnnotations(typeName(type));
    }

    /**
     * Returns whether the supplied JVM class has the supplied annotation in component metadata.
     */
    public boolean hasTypeAnnotation(Class<?> type, Class<? extends Annotation> annotationType) {
        return hasAnnotation(typeAnnotations(type), annotationType);
    }

    /**
     * Returns package annotations for the supplied JVM package and known ancestor packages.
     */
    public List<AnnotationDescriptor> packageAnnotations(Package p) {
        Objects.requireNonNull(p, "p");
        return packageAnnotations(p.getName());
    }

    /**
     * Returns properties for the supplied JVM class.
     */
    public List<PropertyDescriptor> properties(Class<?> type) {
        return properties(typeName(type));
    }

    /**
     * Finds a property by JVM class and property name.
     */
    public Optional<PropertyDescriptor> property(Class<?> type, String propertyName) {
        return property(typeName(type), propertyName);
    }

    /**
     * Returns properties that carry the supplied annotation in component metadata.
     */
    public List<PropertyDescriptor> annotatedProperties(
            Class<?> type, Class<? extends Annotation> annotationType) {
        return properties(type).stream()
                .filter(property -> hasAnnotation(property.annotations(), annotationType))
                .toList();
    }

    /**
     * Finds the first property that carries the supplied annotation in component metadata.
     */
    public Optional<PropertyDescriptor> annotatedProperty(
            Class<?> type, Class<? extends Annotation> annotationType) {
        return annotatedProperties(type, annotationType).stream().findFirst();
    }

    /**
     * Finds the first property name carrying the supplied annotation in component metadata.
     */
    public Optional<String> annotatedPropertyName(
            Class<?> type, Class<? extends Annotation> annotationType) {
        return annotatedProperty(type, annotationType).map(PropertyDescriptor::name);
    }

    /**
     * Returns executables for the supplied JVM class.
     */
    public List<ExecutableDescriptor> executables(Class<?> type) {
        return executables(typeName(type));
    }

    /**
     * Finds executable metadata matching the supplied JVM executable.
     */
    public Optional<ExecutableDescriptor> executable(Executable executable) {
        Objects.requireNonNull(executable, "executable");
        ExecutableKind kind = executable instanceof Constructor<?> ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
        String name = executable.getName();
        List<String> parameters = Arrays.stream(executable.getParameterTypes())
                .map(JvmComponentMetadataLookup::metadataTypeName)
                .toList();
        Optional<ExecutableDescriptor> result = executable(
                typeName(executable.getDeclaringClass()), kind, name, parameters);
        if (result.isPresent() || executable instanceof Method) {
            return result;
        }
        return executables(executable.getDeclaringClass()).stream()
                .filter(descriptor -> descriptor.kind() == ExecutableKind.CONSTRUCTOR)
                .filter(descriptor -> descriptor.parameters().stream().map(ParameterDescriptor::typeName).toList()
                        .equals(parameters))
                .findFirst();
    }

    /**
     * Returns executable annotations for the supplied JVM executable.
     */
    public List<AnnotationDescriptor> executableAnnotations(Executable executable) {
        return executable(executable).map(ExecutableDescriptor::annotations).orElseGet(List::of);
    }

    /**
     * Returns whether the supplied JVM executable has the supplied annotation in component metadata.
     */
    public boolean hasExecutableAnnotation(
            Executable executable, Class<? extends Annotation> annotationType) {
        return hasAnnotation(executableAnnotations(executable), annotationType);
    }

    /**
     * Returns handler routes for the supplied JVM class.
     */
    public List<HandlerRoute> handlerRoutes(Class<?> type) {
        return handlerRoutes(typeName(type));
    }

    /**
     * Returns handler routes for the supplied JVM class and message type.
     */
    public List<HandlerRoute> routes(Class<?> type, MessageType messageType) {
        return routes(typeName(type), messageType);
    }

    /**
     * Returns the effective consumer metadata for a JVM class, if known.
     */
    public Optional<ConsumerDescriptor> consumer(Class<?> type) {
        return consumer(typeName(type));
    }

    /**
     * Returns capabilities declared by the supplied JVM class.
     */
    public Set<ComponentCapability> capabilities(Class<?> type) {
        return capabilities(typeName(type));
    }

    private static String typeName(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return type.getName();
    }

    private static String metadataTypeName(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private static boolean hasAnnotation(
            List<AnnotationDescriptor> annotations, Class<? extends Annotation> annotationType) {
        return annotations.stream().anyMatch(annotation -> annotation.isOrHas(
                annotationType.getSimpleName(), annotationType.getName()));
    }
}
