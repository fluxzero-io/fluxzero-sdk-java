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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
    private static final int REGISTRY_CACHE_SIZE = 512;
    private static final Map<IdentityRegistryListKey, ComponentRegistry> mergeCache =
            Collections.synchronizedMap(new LinkedHashMap<>(REGISTRY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<IdentityRegistryListKey, ComponentRegistry> eldest) {
                    return size() > REGISTRY_CACHE_SIZE;
                }
            });
    private static final Map<IdentityRegistryKey, ComponentRegistry> normalizedCache =
            Collections.synchronizedMap(new LinkedHashMap<>(REGISTRY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<IdentityRegistryKey, ComponentRegistry> eldest) {
                    return size() > REGISTRY_CACHE_SIZE;
                }
            });

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
        List<ComponentRegistry> registryList = registries.stream()
                .map(registry -> Objects.requireNonNull(registry, "registry"))
                .toList();
        if (registryList.isEmpty()) {
            return empty();
        }
        synchronized (mergeCache) {
            return mergeCache.computeIfAbsent(
                    new IdentityRegistryListKey(registryList), ignored -> mergeUncached(registryList));
        }
    }

    private static ComponentRegistry mergeUncached(List<ComponentRegistry> registries) {
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
        synchronized (normalizedCache) {
            return normalizedCache.computeIfAbsent(new IdentityRegistryKey(this), ignored -> normalizedUncached());
        }
    }

    private ComponentRegistry normalizedUncached() {
        Map<String, PackageDescriptor> packagesByName = new LinkedHashMap<>();
        packages.stream()
                .sorted(Comparator.comparing(PackageDescriptor::packageName))
                .forEach(p -> packagesByName.put(p.packageName(), p));
        Map<String, ComponentDescriptor> componentsByName = new LinkedHashMap<>();
        components.stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .forEach(c -> componentsByName.merge(c.fullClassName(), c, ComponentRegistry::mergeComponent));
        return new ComponentRegistry(sourceRoot, List.copyOf(packagesByName.values()),
                                     List.copyOf(componentsByName.values()));
    }

    private static ComponentDescriptor mergeComponent(ComponentDescriptor first, ComponentDescriptor second) {
        return new ComponentDescriptor(
                first.sourceFile() != null ? first.sourceFile() : second.sourceFile(),
                first.packageInfoSource() != null ? first.packageInfoSource() : second.packageInfoSource(),
                first.componentKind(),
                first.packageName(),
                first.className(),
                mergeValues(first.superTypeNames(), second.superTypeNames()),
                mergeAnnotations(first.annotations(), second.annotations()),
                mergeProperties(first.properties(), second.properties()),
                mergeExecutables(first.executables(), second.executables()),
                mergeHandlerRoutes(first.handlerRoutes(), second.handlerRoutes()),
                mergeValues(first.registeredTypes(), second.registeredTypes()),
                first.consumer() != null ? first.consumer() : second.consumer(),
                mergeSet(first.capabilities(), second.capabilities()));
    }

    private static List<AnnotationDescriptor> mergeAnnotations(
            List<AnnotationDescriptor> first, List<AnnotationDescriptor> second) {
        Map<String, AnnotationDescriptor> result = new LinkedHashMap<>();
        first.forEach(annotation -> result.put(annotationKey(annotation), annotation));
        second.forEach(annotation -> result.merge(
                annotationKey(annotation), annotation, ComponentRegistry::mergeAnnotation));
        return List.copyOf(result.values());
    }

    private static String annotationKey(AnnotationDescriptor annotation) {
        return annotation.qualifiedName().replace('$', '.');
    }

    private static AnnotationDescriptor mergeAnnotation(AnnotationDescriptor first, AnnotationDescriptor second) {
        return annotationScore(second) >= annotationScore(first) ? second : first;
    }

    private static int annotationScore(AnnotationDescriptor annotation) {
        return annotation.attributes().values().stream().mapToInt(List::size).sum()
               + annotation.nestedAnnotations().values().stream().mapToInt(List::size).sum()
               + annotation.metaAnnotations().size();
    }

    private static List<PropertyDescriptor> mergeProperties(
            List<PropertyDescriptor> first, List<PropertyDescriptor> second) {
        Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
        first.forEach(property -> result.put(property.name(), property));
        second.forEach(property -> result.merge(property.name(), property, ComponentRegistry::mergeProperty));
        return List.copyOf(result.values());
    }

    private static PropertyDescriptor mergeProperty(PropertyDescriptor first, PropertyDescriptor second) {
        return new PropertyDescriptor(
                first.name(),
                !first.typeName().isBlank() ? first.typeName() : second.typeName(),
                !first.genericTypeName().isBlank() ? first.genericTypeName() : second.genericTypeName(),
                mergeAnnotations(first.annotations(), second.annotations()),
                first.typeUse().equals(TypeUseDescriptor.EMPTY) ? second.typeUse() : first.typeUse());
    }

    private static List<ExecutableDescriptor> mergeExecutables(
            List<ExecutableDescriptor> first, List<ExecutableDescriptor> second) {
        Map<String, ExecutableDescriptor> result = new LinkedHashMap<>();
        first.forEach(executable -> result.put(executableKey(executable), executable));
        second.forEach(executable -> result.merge(
                executableKey(executable), executable, ComponentRegistry::mergeExecutable));
        return List.copyOf(result.values());
    }

    private static ExecutableDescriptor mergeExecutable(ExecutableDescriptor first, ExecutableDescriptor second) {
        return new ExecutableDescriptor(
                first.kind(), first.name(),
                !"void".equals(first.returnTypeName()) ? first.returnTypeName() : second.returnTypeName(),
                first.returnTypeUse().equals(TypeUseDescriptor.EMPTY) ? second.returnTypeUse() : first.returnTypeUse(),
                first.parameters().isEmpty() ? second.parameters() : first.parameters(),
                mergeAnnotations(first.annotations(), second.annotations()),
                first.isStatic() || second.isStatic());
    }

    private static String executableKey(ExecutableDescriptor executable) {
        return executable.kind() + ":" + executable.name() + "("
               + String.join(",", executable.parameters().stream().map(ParameterDescriptor::typeName).toList()) + ")";
    }

    private static Set<HandlerRoute> mergeHandlerRoutes(Set<HandlerRoute> first, Set<HandlerRoute> second) {
        Map<String, HandlerRoute> result = new LinkedHashMap<>();
        first.forEach(route -> result.put(handlerRouteKey(route), route));
        second.forEach(route -> result.putIfAbsent(handlerRouteKey(route), route));
        return Set.copyOf(result.values());
    }

    private static String handlerRouteKey(HandlerRoute route) {
        String annotationName = route.annotation() == null ? "" : route.annotation().qualifiedName();
        String executableName = route.executable() == null ? "" : executableKey(route.executable());
        return route.messageType() + ":" + annotationName + ":" + executableName + ":"
               + route.payloadTypeNames().stream().sorted().toList() + ":"
               + route.allowedClassNames().stream().sorted().toList() + ":"
               + route.webRoutes();
    }

    private static <T> List<T> mergeValues(List<T> first, List<T> second) {
        LinkedHashSet<T> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static <T> Set<T> mergeSet(Set<T> first, Set<T> second) {
        LinkedHashSet<T> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
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

    private static final class IdentityRegistryListKey {
        private final List<ComponentRegistry> registries;
        private final int hashCode;

        private IdentityRegistryListKey(List<ComponentRegistry> registries) {
            this.registries = List.copyOf(registries);
            int result = 1;
            for (ComponentRegistry registry : registries) {
                result = 31 * result + System.identityHashCode(registry);
            }
            this.hashCode = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityRegistryListKey that) || registries.size() != that.registries.size()) {
                return false;
            }
            for (int i = 0; i < registries.size(); i++) {
                if (registries.get(i) != that.registries.get(i)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
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
            return this == other || other instanceof IdentityRegistryKey that && registry == that.registry;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
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
