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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

/**
 * Indexed Fluxzero application model.
 * <p>
 * The registry contains metadata discovered without compiling or loading application source files. Execution modes,
 * documentation, Spring integration, and future runtime negotiation can consume this model without owning discovery.
 *
 * @param sourceRoot source root that was indexed, or {@code null} for classpath-scanned or merged registries
 * @param packages package-level metadata discovered from source
 * @param components component metadata discovered from source
 */
public record ComponentRegistry(
        Path sourceRoot,
        List<PackageDescriptor> packages,
        List<ComponentDescriptor> components) {

    public ComponentRegistry {
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }

    /**
     * Returns an empty component registry.
     */
    public static ComponentRegistry empty() {
        return new ComponentRegistry(null, List.of(), List.of());
    }

    /**
     * Merges multiple registries into one read model.
     * <p>
     * The merged registry keeps a source root when all non-null source roots are equal. Classpath-only registries do
     * not erase that source root; multiple different source roots still produce {@code null}.
     */
    public static ComponentRegistry merge(Collection<ComponentRegistry> registries) {
        Objects.requireNonNull(registries, "registries");
        List<Path> sourceRoots = registries.stream().map(ComponentRegistry::sourceRoot)
                .filter(Objects::nonNull).distinct().toList();
        Path sourceRoot = sourceRoots.size() == 1 ? sourceRoots.getFirst() : null;
        return new ComponentRegistry(sourceRoot,
                                     registries.stream().flatMap(r -> r.packages().stream()).toList(),
                                     registries.stream().flatMap(r -> r.components().stream()).toList()).normalized();
    }

    /**
     * Returns a registry with duplicate descriptors removed and package/component descriptors in stable order.
     */
    public ComponentRegistry normalized() {
        Map<String, PackageDescriptor> packagesByName = new LinkedHashMap<>();
        packages.stream()
                .sorted(Comparator.comparing(PackageDescriptor::packageName))
                .forEach(p -> packagesByName.putIfAbsent(p.packageName(), p));
        Map<String, ComponentDescriptor> componentsByName = new LinkedHashMap<>();
        components.stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .forEach(c -> componentsByName.putIfAbsent(c.fullClassName(), c));
        return new ComponentRegistry(sourceRoot, List.copyOf(packagesByName.values()),
                                     List.copyOf(componentsByName.values()));
    }

    /**
     * Returns whether the registry contains no components.
     */
    public boolean isEmpty() {
        return components.isEmpty();
    }

    /**
     * Returns all handler routes discovered in the registry.
     */
    public Stream<HandlerRoute> handlerRoutes() {
        return components.stream().flatMap(component -> component.handlerRoutes().stream());
    }

    /**
     * Returns all handler routes for the supplied message type in stable component and route order.
     */
    public List<HandlerRoute> routes(MessageType messageType) {
        Objects.requireNonNull(messageType, "messageType");
        return components.stream().sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .flatMap(component -> component.routes(messageType).stream())
                .toList();
    }

    /**
     * Returns all type registrations discovered in packages and components.
     */
    public Stream<RegisteredTypeDescriptor> registeredTypes() {
        return Stream.concat(
                packages.stream().flatMap(p -> p.registeredTypes().stream()),
                components.stream().flatMap(c -> c.registeredTypes().stream()));
    }

    /**
     * Returns all message types exposed by indexed handler routes.
     */
    public Set<MessageType> messageTypes() {
        return handlerRoutes().map(HandlerRoute::messageType).collect(toSet());
    }

    /**
     * Finds a component descriptor by its fully qualified Java class name.
     */
    public Optional<ComponentDescriptor> findComponent(String fullClassName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        return components.stream().filter(component -> component.fullClassName().equals(fullClassName)).findFirst();
    }

    /**
     * Finds a component descriptor for the supplied Java class.
     */
    public Optional<ComponentDescriptor> findComponent(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return findComponent(type.getName());
    }

    /**
     * Returns components that expose at least one route for the supplied message type.
     */
    public List<ComponentDescriptor> components(MessageType messageType) {
        return components.stream()
                .filter(component -> component.messageTypes().contains(messageType))
                .toList();
    }
}
