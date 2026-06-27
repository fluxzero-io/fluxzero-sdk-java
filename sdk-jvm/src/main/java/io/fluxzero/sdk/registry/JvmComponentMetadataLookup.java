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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
     * Returns executables for the supplied JVM class.
     */
    public List<ExecutableDescriptor> executables(Class<?> type) {
        return executables(typeName(type));
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
}
