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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry-backed implementation of the component metadata lookup facade.
 * <p>
 * This backend is dependency-free and can be used by generated/browser execution as well as JVM tests that want to
 * prove metadata-backed semantics without reflection lookups.
 */
public final class RegistryComponentMetadataLookup implements ComponentMetadataLookup {
    private final ComponentRegistry registry;
    private final Map<String, ComponentDescriptor> componentsByName;
    private final Map<ComponentCapability, List<ComponentDescriptor>> componentsByCapability;
    private final Map<MessageType, List<ComponentDescriptor>> componentsByMessageType;
    private final Map<MessageType, List<HandlerRoute>> routesByMessageType;
    private final Map<String, PackageDescriptor> packagesByName;

    /**
     * Creates a lookup facade for the supplied registry.
     */
    public RegistryComponentMetadataLookup(ComponentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.componentsByName = componentsByName(registry);
        this.componentsByCapability = componentsByCapability(registry);
        this.componentsByMessageType = componentsByMessageType(registry);
        this.routesByMessageType = routesByMessageType(registry);
        this.packagesByName = packagesByName(registry);
    }

    /**
     * Creates a lookup facade for the supplied registry.
     */
    public static RegistryComponentMetadataLookup of(ComponentRegistry registry) {
        return new RegistryComponentMetadataLookup(registry);
    }

    @Override
    public ComponentRegistry registry() {
        return registry;
    }

    @Override
    public Optional<ComponentDescriptor> component(String fullClassName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        return Optional.ofNullable(componentsByName.get(fullClassName));
    }

    @Override
    public List<ComponentDescriptor> components(ComponentCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return componentsByCapability.getOrDefault(capability, List.of());
    }

    @Override
    public List<ComponentDescriptor> components(MessageType messageType) {
        Objects.requireNonNull(messageType, "messageType");
        return componentsByMessageType.getOrDefault(messageType, List.of());
    }

    @Override
    public Optional<PackageDescriptor> packageMetadata(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        return Optional.ofNullable(packagesByName.get(packageName));
    }

    @Override
    public List<PackageDescriptor> packageMetadataChain(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        return registry.packages().stream()
                .filter(descriptor -> packageName.equals(descriptor.packageName())
                                      || packageName.startsWith(descriptor.packageName() + "."))
                .sorted(Comparator.comparingInt((PackageDescriptor descriptor) -> descriptor.packageName().length())
                        .reversed())
                .toList();
    }

    @Override
    public List<HandlerRoute> routes(MessageType messageType) {
        Objects.requireNonNull(messageType, "messageType");
        return routesByMessageType.getOrDefault(messageType, List.of());
    }

    private static Map<String, ComponentDescriptor> componentsByName(ComponentRegistry registry) {
        Map<String, ComponentDescriptor> result = new LinkedHashMap<>();
        registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .forEach(component -> result.putIfAbsent(component.fullClassName(), component));
        return Map.copyOf(result);
    }

    private static Map<ComponentCapability, List<ComponentDescriptor>> componentsByCapability(
            ComponentRegistry registry) {
        Map<ComponentCapability, List<ComponentDescriptor>> result = new EnumMap<>(ComponentCapability.class);
        registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .forEach(component -> component.capabilities().forEach(capability ->
                        result.computeIfAbsent(capability, ignored -> new ArrayList<>()).add(component)));
        return copyLists(result);
    }

    private static Map<MessageType, List<ComponentDescriptor>> componentsByMessageType(ComponentRegistry registry) {
        Map<MessageType, List<ComponentDescriptor>> result = new EnumMap<>(MessageType.class);
        registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .forEach(component -> component.messageTypes().forEach(messageType ->
                        result.computeIfAbsent(messageType, ignored -> new ArrayList<>()).add(component)));
        return copyLists(result);
    }

    private static Map<MessageType, List<HandlerRoute>> routesByMessageType(ComponentRegistry registry) {
        Map<MessageType, List<HandlerRoute>> result = new EnumMap<>(MessageType.class);
        registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .flatMap(component -> component.routes().stream())
                .forEach(route -> result.computeIfAbsent(route.messageType(), ignored -> new ArrayList<>()).add(route));
        return copyLists(result);
    }

    private static Map<String, PackageDescriptor> packagesByName(ComponentRegistry registry) {
        Map<String, PackageDescriptor> result = new LinkedHashMap<>();
        registry.packages().stream()
                .sorted(Comparator.comparing(PackageDescriptor::packageName))
                .forEach(descriptor -> result.putIfAbsent(descriptor.packageName(), descriptor));
        return Map.copyOf(result);
    }

    private static <K, V> Map<K, List<V>> copyLists(Map<K, List<V>> source) {
        return source.entrySet().stream()
                .collect(LinkedHashMap::new,
                         (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue())),
                         Map::putAll);
    }
}
