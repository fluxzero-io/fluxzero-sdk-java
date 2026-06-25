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
import lombok.NonNull;

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
 * @param executables methods and constructors discovered on the component
 * @param handlerRoutes handler routes discovered on the component
 * @param registeredTypes source type registration metadata declared by the component
 * @param consumer source consumer metadata applied to the component
 * @param capabilities indexed capabilities exposed by the component
 */
public record ComponentDescriptor(
        Path sourceFile,
        Path packageInfoSource,
        @NonNull ComponentKind componentKind,
        @NonNull String packageName,
        @NonNull String className,
        @NonNull List<String> superTypeNames,
        @NonNull List<AnnotationDescriptor> annotations,
        @NonNull List<ExecutableDescriptor> executables,
        @NonNull Set<HandlerRoute> handlerRoutes,
        @NonNull List<RegisteredTypeDescriptor> registeredTypes,
        ConsumerDescriptor consumer,
        @NonNull Set<ComponentCapability> capabilities) {

    public ComponentDescriptor {
        Objects.requireNonNull(componentKind, "componentKind");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(className, "className");
        superTypeNames = List.copyOf(Objects.requireNonNull(superTypeNames, "superTypeNames"));
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        executables = List.copyOf(Objects.requireNonNull(executables, "executables"));
        handlerRoutes = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(handlerRoutes, "handlerRoutes")));
        registeredTypes = List.copyOf(Objects.requireNonNull(registeredTypes, "registeredTypes"));
        capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(capabilities, "capabilities")));
    }

    public ComponentDescriptor(Path sourceFile, Path packageInfoSource, String packageName, String className,
                               Set<HandlerRoute> handlerRoutes, Set<ComponentCapability> capabilities) {
        this(sourceFile, packageInfoSource, ComponentKind.CLASS, packageName, className,
             List.of(), List.of(), List.of(), handlerRoutes, List.of(), null, capabilities);
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
}
