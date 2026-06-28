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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final ConcurrentMap<String, List<PackageDescriptor>> packageMetadataChainCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<ComponentExecutableKey, Optional<ExecutableDescriptor>> executableCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<InvocationPlanDescriptor>> invocationPlansByComponentName =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<ComponentExecutableKey, Optional<InvocationPlanDescriptor>> invocationPlanCache =
            new ConcurrentHashMap<>();

    /**
     * Creates a lookup facade for the supplied registry.
     */
    public RegistryComponentMetadataLookup(ComponentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        RegistryIndexes indexes = indexes(registry);
        this.componentsByName = indexes.componentsByName();
        this.componentsByCapability = indexes.componentsByCapability();
        this.componentsByMessageType = indexes.componentsByMessageType();
        this.routesByMessageType = indexes.routesByMessageType();
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
        return packageMetadataChainCache.computeIfAbsent(packageName, this::computePackageMetadataChain);
    }

    private List<PackageDescriptor> computePackageMetadataChain(String packageName) {
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

    @Override
    public List<PropertyDescriptor> properties(String fullClassName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        return component(fullClassName).map(ComponentDescriptor::properties).orElseGet(List::of);
    }

    @Override
    public Optional<PropertyDescriptor> property(String fullClassName, String propertyName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        Objects.requireNonNull(propertyName, "propertyName");
        return properties(fullClassName).stream()
                .filter(property -> property.name().equals(propertyName))
                .findFirst();
    }

    @Override
    public List<ExecutableDescriptor> executables(String fullClassName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        return component(fullClassName).map(ComponentDescriptor::executables).orElseGet(List::of);
    }

    @Override
    public Optional<ExecutableDescriptor> executable(
            String fullClassName, ExecutableKind kind, String name, List<String> parameterTypeNames) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        ComponentExecutableKey key = componentExecutableKey(fullClassName, kind, name, parameterTypeNames);
        return executableCache.computeIfAbsent(key, ignored -> findExecutable(fullClassName, key.executableId()));
    }

    @Override
    public List<InvocationPlanDescriptor> invocationPlans(String fullClassName) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        return invocationPlansByComponentName.computeIfAbsent(fullClassName, this::buildInvocationPlans);
    }

    @Override
    public Optional<InvocationPlanDescriptor> invocationPlan(
            String fullClassName, ExecutableKind kind, String name, List<String> parameterTypeNames) {
        Objects.requireNonNull(fullClassName, "fullClassName");
        ComponentExecutableKey key = componentExecutableKey(fullClassName, kind, name, parameterTypeNames);
        return invocationPlanCache.computeIfAbsent(key, ignored -> invocationPlans(fullClassName).stream()
                .filter(plan -> plan.executableId().equals(key.executableId()))
                .findFirst());
    }

    private static RegistryIndexes indexes(ComponentRegistry registry) {
        Map<String, ComponentDescriptor> result = new LinkedHashMap<>();
        Map<ComponentCapability, List<ComponentDescriptor>> byCapability = new EnumMap<>(ComponentCapability.class);
        Map<MessageType, List<ComponentDescriptor>> byMessageType = new EnumMap<>(MessageType.class);
        Map<MessageType, List<HandlerRoute>> routesByMessageType = new EnumMap<>(MessageType.class);

        registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .forEach(component -> {
                    result.putIfAbsent(component.fullClassName(), component);
                    component.capabilities().forEach(capability ->
                            byCapability.computeIfAbsent(capability, ignored -> new ArrayList<>()).add(component));
                    EnumSet<MessageType> messageTypes = EnumSet.noneOf(MessageType.class);
                    for (HandlerRoute route : component.routes()) {
                        MessageType messageType = route.messageType();
                        messageTypes.add(messageType);
                        routesByMessageType.computeIfAbsent(messageType, ignored -> new ArrayList<>()).add(route);
                    }
                    messageTypes.forEach(messageType ->
                            byMessageType.computeIfAbsent(messageType, ignored -> new ArrayList<>()).add(component));
                });
        return new RegistryIndexes(
                Map.copyOf(result), copyLists(byCapability), copyLists(byMessageType), copyLists(routesByMessageType));
    }

    private static Map<String, PackageDescriptor> packagesByName(ComponentRegistry registry) {
        Map<String, PackageDescriptor> result = new LinkedHashMap<>();
        registry.packages().stream()
                .sorted(Comparator.comparing(PackageDescriptor::packageName))
                .forEach(descriptor -> result.putIfAbsent(descriptor.packageName(), descriptor));
        return Map.copyOf(result);
    }

    private Optional<ExecutableDescriptor> findExecutable(String fullClassName, String executableId) {
        return executables(fullClassName).stream()
                .filter(executable -> executableId(executable).equals(executableId))
                .findFirst();
    }

    private List<InvocationPlanDescriptor> buildInvocationPlans(String fullClassName) {
        Optional<ComponentDescriptor> component = component(fullClassName);
        if (component.isEmpty()) {
            return List.of();
        }
        ComponentDescriptor descriptor = component.orElseThrow();
        List<PropertyAccessPlanDescriptor> propertyAccesses = descriptor.properties().stream()
                .map(RegistryComponentMetadataLookup::propertyAccessPlan)
                .toList();
        return descriptor.executables().stream()
                .map(executable -> invocationPlan(fullClassName, executable, propertyAccesses))
                .toList();
    }

    private static String executableId(ExecutableDescriptor executable) {
        return InvocationPlanDescriptor.executableId(
                executable.kind(), executable.name(),
                executable.parameters().stream().map(ParameterDescriptor::typeName).toList());
    }

    private static ComponentExecutableKey componentExecutableKey(
            String fullClassName, ExecutableKind kind, String name, List<String> parameterTypeNames) {
        return new ComponentExecutableKey(
                fullClassName, InvocationPlanDescriptor.executableId(kind, name, parameterTypeNames));
    }

    private static InvocationPlanDescriptor invocationPlan(
            String targetComponentName, ExecutableDescriptor executable,
            List<PropertyAccessPlanDescriptor> propertyAccesses) {
        List<String> parameterTypes = executable.parameters().stream()
                .map(ParameterDescriptor::typeName)
                .toList();
        return new InvocationPlanDescriptor(
                targetComponentName,
                InvocationPlanDescriptor.executableId(executable.kind(), executable.name(), parameterTypes),
                executable.kind(),
                executable.name(),
                executable.returnTypeName(),
                parameterBindings(executable),
                propertyAccesses,
                List.of());
    }

    private static List<ParameterBindingDescriptor> parameterBindings(ExecutableDescriptor executable) {
        List<ParameterDescriptor> parameters = executable.parameters();
        List<ParameterBindingDescriptor> result = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            ParameterDescriptor parameter = parameters.get(i);
            result.add(new ParameterBindingDescriptor(
                    i, parameter.name(), parameter.typeName(), annotationNames(parameter.annotations())));
        }
        return List.copyOf(result);
    }

    private static PropertyAccessPlanDescriptor propertyAccessPlan(PropertyDescriptor property) {
        return new PropertyAccessPlanDescriptor(
                property.name(),
                property.typeName(),
                property.genericTypeName(),
                true,
                true,
                annotationNames(property.annotations()));
    }

    private static List<String> annotationNames(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(AnnotationDescriptor::qualifiedName)
                .filter(Objects::nonNull)
                .toList();
    }

    private static <K, V> Map<K, List<V>> copyLists(Map<K, List<V>> source) {
        return source.entrySet().stream()
                .collect(LinkedHashMap::new,
                         (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue())),
                         Map::putAll);
    }

    private record ComponentExecutableKey(String componentName, String executableId) {
    }

    private record RegistryIndexes(
            Map<String, ComponentDescriptor> componentsByName,
            Map<ComponentCapability, List<ComponentDescriptor>> componentsByCapability,
            Map<MessageType, List<ComponentDescriptor>> componentsByMessageType,
            Map<MessageType, List<HandlerRoute>> routesByMessageType) {
    }
}
