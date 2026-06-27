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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ComponentRegistryParityAssertions {
    private ComponentRegistryParityAssertions() {
    }

    static void assertSemanticParity(ComponentRegistry expected, ComponentRegistry actual) {
        RegistrySnapshot expectedSnapshot = snapshot(expected);
        RegistrySnapshot actualSnapshot = snapshot(actual);
        assertEquals(expectedSnapshot.packages(), actualSnapshot.packages(),
                     "Fluxzero component registry package semantic parity");
        assertEquals(expectedSnapshot.components().keySet(), actualSnapshot.components().keySet(),
                     "Fluxzero component registry component keys");
        expectedSnapshot.components().forEach((name, component) ->
                assertEquals(component, actualSnapshot.components().get(name),
                             "Fluxzero component registry semantic parity for " + name));
    }

    private static RegistrySnapshot snapshot(ComponentRegistry registry) {
        return new RegistrySnapshot(
                packages(registry),
                components(registry));
    }

    private static Map<String, PackageSnapshot> packages(ComponentRegistry registry) {
        Map<String, PackageSnapshot> result = new TreeMap<>();
        registry.normalized().packages().forEach(descriptor -> result.put(
                descriptor.packageName(),
                new PackageSnapshot(
                        descriptor.packageName(),
                        annotationNames(descriptor.annotations()),
                        registeredTypes(descriptor.registeredTypes()),
                        descriptor.consumerMetadata().map(ConsumerDescriptor::name).orElse(""),
                        capabilities(descriptor.capabilities()))));
        return result;
    }

    private static Map<String, ComponentSnapshot> components(ComponentRegistry registry) {
        Map<String, ComponentSnapshot> result = new TreeMap<>();
        registry.normalized().components().forEach(descriptor -> result.put(
                descriptor.fullClassName(),
                new ComponentSnapshot(
                        descriptor.fullClassName(),
                        descriptor.componentKind(),
                        descriptor.packageName(),
                        typeNames(descriptor.superTypeNames()),
                        annotationNames(descriptor.annotations()),
                        properties(descriptor),
                        routes(descriptor),
                        registeredTypes(descriptor.registeredTypes()),
                        descriptor.consumerMetadata().map(ConsumerDescriptor::name).orElse(""),
                        capabilities(descriptor.capabilities()))));
        return result;
    }

    private static Map<String, PropertySnapshot> properties(ComponentDescriptor descriptor) {
        Map<String, PropertySnapshot> result = new TreeMap<>();
        descriptor.properties().forEach(property -> result.put(
                property.name(),
                new PropertySnapshot(
                        property.name(), typeName(property.typeName()), typeName(property.genericTypeName()),
                        annotationNames(property.annotations()))));
        return result;
    }

    private static List<RouteSnapshot> routes(ComponentDescriptor descriptor) {
        return descriptor.routes().stream()
                .map(route -> new RouteSnapshot(
                        route.messageType(),
                        route.annotationMetadata().map(AnnotationDescriptor::qualifiedName).orElse(""),
                        executable(route),
                        route.disabled(),
                        route.passive(),
                        route.skipExpiredRequests(),
                        route.local(),
                        route.tracked(),
                        typeNames(route.payloadTypeNames()),
                        typeNames(route.allowedClassNames()),
                        route.webRoutes()))
                .sorted(Comparator.comparing(RouteSnapshot::messageType)
                                .thenComparing(route -> route.executable().name())
                                .thenComparing(route -> route.payloadTypeNames().toString()))
                .toList();
    }

    private static ExecutableSnapshot executable(HandlerRoute route) {
        return route.executableMetadata()
                .map(executable -> new ExecutableSnapshot(
                        executable.kind(),
                        executable.name(),
                        typeName(executable.returnTypeName()),
                        parameters(executable.parameters()),
                        annotationNames(executable.annotations())))
                .orElseGet(() -> new ExecutableSnapshot(null, "", "", List.of(), List.of()));
    }

    private static List<ParameterSnapshot> parameters(List<ParameterDescriptor> parameters) {
        return parameters.stream()
                .map(parameter -> new ParameterSnapshot(
                        typeName(parameter.typeName()), annotationNames(parameter.annotations())))
                .toList();
    }

    private static List<RegisteredTypeSnapshot> registeredTypes(List<RegisteredTypeDescriptor> descriptors) {
        return descriptors.stream()
                .map(descriptor -> new RegisteredTypeSnapshot(
                        typeName(descriptor.root()), descriptor.contains(), typeNames(descriptor.candidateTypeNames())))
                .sorted(Comparator.comparing(RegisteredTypeSnapshot::root)
                                .thenComparing(descriptor -> descriptor.contains().toString()))
                .toList();
    }

    private static List<String> annotationNames(List<AnnotationDescriptor> annotations) {
        return annotations.stream().map(AnnotationDescriptor::qualifiedName)
                .filter(name -> !name.startsWith("java.lang."))
                .sorted().toList();
    }

    private static Set<ComponentCapability> capabilities(Set<ComponentCapability> capabilities) {
        java.util.TreeSet<ComponentCapability> result = new java.util.TreeSet<>(capabilities);
        result.remove(ComponentCapability.SOURCE_COMPONENT);
        result.remove(ComponentCapability.CLASSPATH_COMPONENT);
        return Set.copyOf(result);
    }

    private static Set<String> typeNames(Set<String> typeNames) {
        return typeNames.stream().map(ComponentRegistryParityAssertions::typeName)
                .filter(name -> !"java.lang.Record".equals(name))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private static List<String> typeNames(List<String> typeNames) {
        return typeNames.stream().map(ComponentRegistryParityAssertions::typeName)
                .filter(name -> !"java.lang.Record".equals(name))
                .sorted().toList();
    }

    private static String typeName(String typeName) {
        return typeName == null ? "" : typeName.replace('$', '.');
    }

    private record RegistrySnapshot(
            Map<String, PackageSnapshot> packages,
            Map<String, ComponentSnapshot> components) {
    }

    private record PackageSnapshot(
            String packageName,
            List<String> annotationNames,
            List<RegisteredTypeSnapshot> registeredTypes,
            String consumerName,
            Set<ComponentCapability> capabilities) {
    }

    private record ComponentSnapshot(
            String fullClassName,
            ComponentKind componentKind,
            String packageName,
            List<String> superTypeNames,
            List<String> annotationNames,
            Map<String, PropertySnapshot> properties,
            List<RouteSnapshot> routes,
            List<RegisteredTypeSnapshot> registeredTypes,
            String consumerName,
            Set<ComponentCapability> capabilities) {
    }

    private record PropertySnapshot(
            String name,
            String typeName,
            String genericTypeName,
            List<String> annotationNames) {
    }

    private record RouteSnapshot(
            MessageType messageType,
            String annotationName,
            ExecutableSnapshot executable,
            boolean disabled,
            boolean passive,
            boolean skipExpiredRequests,
            boolean local,
            boolean tracked,
            Set<String> payloadTypeNames,
            Set<String> allowedClassNames,
            List<WebRouteDescriptor> webRoutes) {
    }

    private record ExecutableSnapshot(
            ExecutableKind kind,
            String name,
            String returnTypeName,
            List<ParameterSnapshot> parameters,
            List<String> annotationNames) {
    }

    private record ParameterSnapshot(
            String typeName,
            List<String> annotationNames) {
    }

    private record RegisteredTypeSnapshot(
            String root,
            List<String> contains,
            List<String> candidateTypeNames) {
    }
}
