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

import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 * Component metadata discovered for a Fluxzero application.
 *
 * @param sourceFile Java source file declaring the component, or {@code null} for classpath-scanned components
 * @param packageInfoSource optional package metadata source that influences this component
 * @param componentKind Java source component kind
 * @param packageName Java package name
 * @param className simple Java type name
 * @param superTypeNames directly declared superclass and interface type names
 * @param annotations source annotations on the component
 * @param properties fields, record components, or source properties discovered on the component
 * @param executables methods and constructors discovered on the component
 * @param handlerRoutes handler routes discovered on the component
 * @param registeredTypes source type registration metadata declared by the component
 * @param consumer source consumer metadata applied to the component
 * @param capabilities indexed capabilities exposed by the component
 */
public final class ComponentDescriptor {
    private final String sourceFileName;
    private final String packageInfoSourceName;
    private final ComponentKind componentKind;
    private final String packageName;
    private final String className;
    private final List<String> superTypeNames;
    private final List<AnnotationDescriptor> annotations;
    private final List<PropertyDescriptor> properties;
    private final List<ExecutableDescriptor> executables;
    private final Set<HandlerRoute> handlerRoutes;
    private final List<RegisteredTypeDescriptor> registeredTypes;
    private final ConsumerDescriptor consumer;
    private final Set<ComponentCapability> capabilities;

    public ComponentDescriptor(
            Path sourceFile,
            Path packageInfoSource,
            ComponentKind componentKind,
            String packageName,
            String className,
            List<String> superTypeNames,
            List<AnnotationDescriptor> annotations,
            List<PropertyDescriptor> properties,
            List<ExecutableDescriptor> executables,
            Set<HandlerRoute> handlerRoutes,
            List<RegisteredTypeDescriptor> registeredTypes,
            ConsumerDescriptor consumer,
            Set<ComponentCapability> capabilities) {
        this(pathName(sourceFile), pathName(packageInfoSource), componentKind, packageName, className, superTypeNames,
             annotations, properties, executables, handlerRoutes, registeredTypes, consumer, capabilities);
    }

    public ComponentDescriptor(Path sourceFile, Path packageInfoSource, String packageName, String className,
                               Set<HandlerRoute> handlerRoutes, Set<ComponentCapability> capabilities) {
        this(sourceFile, packageInfoSource, ComponentKind.CLASS, packageName, className,
             List.of(), List.of(), List.of(), List.of(), handlerRoutes, List.of(), null, capabilities);
    }

    private ComponentDescriptor(
            String sourceFileName,
            String packageInfoSourceName,
            ComponentKind componentKind,
            String packageName,
            String className,
            List<String> superTypeNames,
            List<AnnotationDescriptor> annotations,
            List<PropertyDescriptor> properties,
            List<ExecutableDescriptor> executables,
            Set<HandlerRoute> handlerRoutes,
            List<RegisteredTypeDescriptor> registeredTypes,
            ConsumerDescriptor consumer,
            Set<ComponentCapability> capabilities) {
        this.sourceFileName = sourceFileName;
        this.packageInfoSourceName = packageInfoSourceName;
        this.componentKind = Objects.requireNonNull(componentKind, "componentKind");
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.className = Objects.requireNonNull(className, "className");
        this.superTypeNames = RegistryCollections.immutableList(
                Objects.requireNonNull(superTypeNames, "superTypeNames"));
        this.annotations = RegistryCollections.immutableList(Objects.requireNonNull(annotations, "annotations"));
        this.properties = RegistryCollections.immutableList(Objects.requireNonNull(properties, "properties"));
        this.executables = RegistryCollections.immutableList(Objects.requireNonNull(executables, "executables"));
        this.handlerRoutes = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(handlerRoutes, "handlerRoutes")));
        this.registeredTypes = RegistryCollections.immutableList(
                Objects.requireNonNull(registeredTypes, "registeredTypes"));
        this.consumer = consumer;
        this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(capabilities, "capabilities")));
    }

    /**
     * Creates a descriptor from browser-safe source labels.
     */
    public static ComponentDescriptor fromSourceNames(
            String sourceFileName,
            String packageInfoSourceName,
            ComponentKind componentKind,
            String packageName,
            String className,
            List<String> superTypeNames,
            List<AnnotationDescriptor> annotations,
            List<PropertyDescriptor> properties,
            List<ExecutableDescriptor> executables,
            Set<HandlerRoute> handlerRoutes,
            List<RegisteredTypeDescriptor> registeredTypes,
            ConsumerDescriptor consumer,
            Set<ComponentCapability> capabilities) {
        return new ComponentDescriptor(sourceFileName, packageInfoSourceName, componentKind, packageName, className,
                                       superTypeNames, annotations, properties, executables, handlerRoutes,
                                       registeredTypes, consumer, capabilities);
    }

    public Path sourceFile() {
        return path(sourceFileName);
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public Path packageInfoSource() {
        return path(packageInfoSourceName);
    }

    public String packageInfoSourceName() {
        return packageInfoSourceName;
    }

    public ComponentKind componentKind() {
        return componentKind;
    }

    public String packageName() {
        return packageName;
    }

    public String className() {
        return className;
    }

    public List<String> superTypeNames() {
        return superTypeNames;
    }

    public List<AnnotationDescriptor> annotations() {
        return annotations;
    }

    public List<PropertyDescriptor> properties() {
        return properties;
    }

    public List<ExecutableDescriptor> executables() {
        return executables;
    }

    public Set<HandlerRoute> handlerRoutes() {
        return handlerRoutes;
    }

    public List<RegisteredTypeDescriptor> registeredTypes() {
        return registeredTypes;
    }

    public ConsumerDescriptor consumer() {
        return consumer;
    }

    public Set<ComponentCapability> capabilities() {
        return capabilities;
    }

    /**
     * Returns the fully qualified Java class name.
     */
    public String fullClassName() {
        return packageName.isBlank() ? className : packageName + "." + className;
    }

    /**
     * Returns all message types exposed by this component.
     */
    public Set<MessageType> messageTypes() {
        return handlerRoutes.stream().map(HandlerRoute::messageType).collect(toSet());
    }

    /**
     * Returns handler routes in stable route order.
     */
    public List<HandlerRoute> routes() {
        return handlerRoutes.stream().sorted(routeComparator()).toList();
    }

    /**
     * Returns handler routes for the supplied message type in stable route order.
     */
    public List<HandlerRoute> routes(MessageType messageType) {
        Objects.requireNonNull(messageType, "messageType");
        return routes().stream().filter(route -> route.messageType() == messageType).toList();
    }

    /**
     * Returns the first route for the supplied message type.
     */
    public Optional<HandlerRoute> route(MessageType messageType) {
        return routes(messageType).stream().findFirst();
    }

    /**
     * Returns whether any indexed route can be considered for the supplied message and payload type.
     */
    public boolean canHandle(MessageType messageType, Class<?> payloadType) {
        return handlerRoutes.stream().anyMatch(route -> route.canHandle(messageType, payloadType));
    }

    /**
     * Returns source consumer metadata applied to this component.
     */
    public Optional<ConsumerDescriptor> consumerMetadata() {
        return Optional.ofNullable(consumer);
    }

    private static Comparator<HandlerRoute> routeComparator() {
        return Comparator.comparing((HandlerRoute route) -> route.messageType().ordinal())
                .thenComparing(route -> route.executableMetadata().map(ExecutableDescriptor::name).orElse(""))
                .thenComparing(route -> route.payloadTypeNames().stream().findFirst().orElse(""));
    }

    private static String pathName(Path path) {
        return path == null ? null : path.toString();
    }

    private static Path path(String pathName) {
        return pathName == null ? null : Path.of(pathName);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComponentDescriptor that)) {
            return false;
        }
        return Objects.equals(sourceFileName, that.sourceFileName)
               && Objects.equals(packageInfoSourceName, that.packageInfoSourceName)
               && componentKind == that.componentKind
               && packageName.equals(that.packageName)
               && className.equals(that.className)
               && superTypeNames.equals(that.superTypeNames)
               && annotations.equals(that.annotations)
               && properties.equals(that.properties)
               && executables.equals(that.executables)
               && handlerRoutes.equals(that.handlerRoutes)
               && registeredTypes.equals(that.registeredTypes)
               && Objects.equals(consumer, that.consumer)
               && capabilities.equals(that.capabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFileName, packageInfoSourceName, componentKind, packageName, className,
                            superTypeNames, annotations, properties, executables, handlerRoutes, registeredTypes,
                            consumer, capabilities);
    }

    @Override
    public String toString() {
        return "ComponentDescriptor[sourceFile=" + sourceFileName
               + ", packageInfoSource=" + packageInfoSourceName
               + ", componentKind=" + componentKind
               + ", packageName=" + packageName
               + ", className=" + className
               + ", superTypeNames=" + superTypeNames
               + ", annotations=" + annotations
               + ", properties=" + properties
               + ", executables=" + executables
               + ", handlerRoutes=" + handlerRoutes
               + ", registeredTypes=" + registeredTypes
               + ", consumer=" + consumer
               + ", capabilities=" + capabilities + "]";
    }
}
