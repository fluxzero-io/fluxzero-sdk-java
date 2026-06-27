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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Incubating runtime-facing lookup facade for Fluxzero component metadata.
 * <p>
 * Runtime code should ask this facade metadata-shaped questions instead of depending on whether metadata came from JVM
 * reflection, source scanning, generated registry artifacts, or browser code generation.
 */
public interface ComponentMetadataLookup {

    /**
     * Returns the underlying application model for callers that need to inspect the complete registry.
     */
    ComponentRegistry registry();

    /**
     * Finds component metadata by fully-qualified Java class name.
     */
    default Optional<ComponentDescriptor> component(String fullClassName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        return registry().findComponent(fullClassName);
    }

    /**
     * Returns all components that expose the supplied capability.
     */
    default List<ComponentDescriptor> components(ComponentCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return registry().components().stream()
                .filter(component -> component.capabilities().contains(capability))
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .toList();
    }

    /**
     * Returns all components that expose a route for the supplied message type.
     */
    default List<ComponentDescriptor> components(MessageType messageType) {
        return registry().components(messageType);
    }

    /**
     * Finds package metadata by package name.
     */
    default Optional<PackageDescriptor> packageMetadata(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        return registry().packages().stream()
                .filter(descriptor -> descriptor.packageName().equals(packageName))
                .findFirst();
    }

    /**
     * Returns package metadata for the supplied package and all registered ancestor packages, nearest package first.
     */
    default List<PackageDescriptor> packageMetadataChain(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        return registry().packages().stream()
                .filter(descriptor -> packageName.equals(descriptor.packageName())
                                      || packageName.startsWith(descriptor.packageName() + "."))
                .sorted(Comparator.comparingInt((PackageDescriptor descriptor) -> descriptor.packageName().length())
                        .reversed())
                .toList();
    }

    /**
     * Returns package annotations for the supplied package and known ancestor packages.
     */
    default List<AnnotationDescriptor> packageAnnotations(String packageName) {
        return packageMetadataChain(packageName).stream()
                .flatMap(descriptor -> descriptor.annotations().stream())
                .toList();
    }

    /**
     * Returns component/type annotations for the supplied class name.
     */
    default List<AnnotationDescriptor> typeAnnotations(String fullClassName) {
        return component(fullClassName).map(ComponentDescriptor::annotations).orElseGet(List::of);
    }

    /**
     * Returns properties for the supplied class name.
     */
    default List<PropertyDescriptor> properties(String fullClassName) {
        return component(fullClassName).map(ComponentDescriptor::properties).orElseGet(List::of);
    }

    /**
     * Finds a property by component class name and property name.
     */
    default Optional<PropertyDescriptor> property(String fullClassName, String propertyName) {
        Objects.requireNonNull(propertyName, "propertyName");
        return properties(fullClassName).stream()
                .filter(property -> property.name().equals(propertyName))
                .findFirst();
    }

    /**
     * Returns executables for the supplied class name.
     */
    default List<ExecutableDescriptor> executables(String fullClassName) {
        return component(fullClassName).map(ComponentDescriptor::executables).orElseGet(List::of);
    }

    /**
     * Finds an executable by kind, name, and erased parameter type names.
     */
    default Optional<ExecutableDescriptor> executable(
            String fullClassName, ExecutableKind kind, String name, List<String> parameterTypeNames) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        List<String> parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypeNames, "parameterTypeNames"));
        return executables(fullClassName).stream()
                .filter(executable -> executable.kind() == kind)
                .filter(executable -> executable.name().equals(name))
                .filter(executable -> executable.parameters().stream().map(ParameterDescriptor::typeName).toList()
                        .equals(parameterTypes))
                .findFirst();
    }

    /**
     * Returns handler routes for the supplied class name.
     */
    default List<HandlerRoute> handlerRoutes(String fullClassName) {
        return component(fullClassName).map(ComponentDescriptor::routes).orElseGet(List::of);
    }

    /**
     * Returns all handler routes for the supplied message type.
     */
    default List<HandlerRoute> routes(MessageType messageType) {
        return registry().routes(messageType);
    }

    /**
     * Returns handler routes for a component and message type.
     */
    default List<HandlerRoute> routes(String fullClassName, MessageType messageType) {
        Objects.requireNonNull(messageType, "messageType");
        return component(fullClassName)
                .map(component -> component.routes(messageType))
                .orElseGet(List::of);
    }

    /**
     * Returns the effective consumer metadata for a component, if known.
     */
    default Optional<ConsumerDescriptor> consumer(String fullClassName) {
        return component(fullClassName).flatMap(ComponentDescriptor::consumerMetadata);
    }

    /**
     * Returns registered type metadata declared by the component.
     */
    default List<RegisteredTypeDescriptor> registeredTypes(String fullClassName) {
        return component(fullClassName).map(ComponentDescriptor::registeredTypes).orElseGet(List::of);
    }

    /**
     * Returns capabilities declared by the component.
     */
    default Set<ComponentCapability> capabilities(String fullClassName) {
        return component(fullClassName).map(ComponentDescriptor::capabilities).orElseGet(Set::of);
    }
}
