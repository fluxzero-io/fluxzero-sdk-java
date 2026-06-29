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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ExecutableInvocation;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.InvocationPlanDescriptor;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.RegistryExecutableViews;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RegistryHandlerMatcherFactory {
    private static final ConcurrentMap<RegisteredExecutableViewsKey, List<RegisteredExecutableView>>
            executableViewsByLookup = new ConcurrentHashMap<>();

    private RegistryHandlerMatcherFactory() {
    }

    static Optional<HandlerMatcher<Object, DeserializingMessage>> create(
            Class<?> targetClass,
            MessageType messageType,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config) {
        return registeredExecutableViews(targetClass, messageType, config)
                .filter(views -> !views.isEmpty())
                .flatMap(views -> {
                    Map<ExecutableView, ExecutableInvocation> invocations = new LinkedHashMap<>();
                    for (RegisteredExecutableView view : views) {
                        generatedInvocation(view).ifPresent(invocation -> invocations.put(view.view(), invocation));
                    }
                    if (invocations.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(HandlerInspector.inspectViews(
                            targetClass, invocations.keySet().stream().toList(), invocations::get,
                            parameterResolvers, config));
                });
    }

    static boolean hasRegisteredHandlersWithoutGeneratedInvocations(
            Class<?> targetClass,
            MessageType messageType,
            HandlerConfiguration<DeserializingMessage> config) {
        return registeredExecutableViews(targetClass, messageType, config)
                .map(views -> !views.isEmpty() && views.stream().anyMatch(
                        view -> generatedInvocation(view).isEmpty()
                                && !hasGeneratedEquivalent(view, views)))
                .orElse(false);
    }

    static boolean hasRegisteredHandlers(
            Class<?> targetClass,
            MessageType messageType,
            HandlerConfiguration<DeserializingMessage> config) {
        return registeredExecutableViews(targetClass, messageType, config)
                .filter(views -> views.stream().anyMatch(view -> generatedInvocation(view).isPresent()))
                .isPresent();
    }

    static boolean hasRegisteredHandlerMetadata(Class<?> targetClass, MessageType messageType) {
        return !registeredRoutes(targetClass, messageType).isEmpty();
    }

    private static List<RegisteredRoute> registeredRoutes(Class<?> targetClass, MessageType messageType) {
        return ComponentMetadataLookups.registeredLookup(targetClass)
                .map(lookup -> metadataRouteTypes(targetClass, lookup).stream()
                        .flatMap(metadataType -> targetClassNames(metadataType).stream()
                                .flatMap(name -> lookup.routes(name, messageType).stream())
                                .filter(route -> !route.disabled())
                                .map(route -> new RegisteredRoute(metadataType, route)))
                        .toList())
                .orElseGet(List::of);
    }

    private static boolean routeHasMissingGeneratedInvocation(Class<?> targetClass, HandlerRoute route) {
        return route.executableMetadata()
                .map(executable -> GeneratedExecutableInvocations.find(
                        targetClass, executableId(executable)).isEmpty())
                .orElse(true);
    }

    private static Optional<ExecutableInvocation> generatedInvocation(RegisteredExecutableView view) {
        ComponentMetadataLookups.ensureGeneratedExecutions(view.metadataType());
        return GeneratedExecutableInvocations.find(view.metadataType(), view.view().executableId());
    }

    private static boolean hasGeneratedEquivalent(
            RegisteredExecutableView missingView, List<RegisteredExecutableView> views) {
        return views.stream()
                .filter(candidate -> candidate != missingView)
                .filter(candidate -> generatedInvocation(candidate).isPresent())
                .anyMatch(candidate -> equivalentExecutable(missingView, candidate));
    }

    private static boolean equivalentExecutable(RegisteredExecutableView first, RegisteredExecutableView second) {
        ExecutableDescriptor left = first.descriptor();
        ExecutableDescriptor right = second.descriptor();
        if (left.kind() != right.kind()
            || !left.name().equals(right.name())
            || left.parameters().size() != right.parameters().size()) {
            return false;
        }
        for (int i = 0; i < left.parameters().size(); i++) {
            if (!equivalentParameterType(
                    first.metadataType(), left.parameters().get(i).typeName(),
                    second.metadataType(), right.parameters().get(i).typeName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean equivalentParameterType(
            Class<?> leftMetadataType, String leftTypeName, Class<?> rightMetadataType, String rightTypeName) {
        if (leftTypeName.equals(rightTypeName)) {
            return true;
        }
        Optional<Class<?>> leftType = JvmComponentMetadataLookup.classForMetadataName(
                leftTypeName, leftMetadataType.getClassLoader());
        Optional<Class<?>> rightType = JvmComponentMetadataLookup.classForMetadataName(
                rightTypeName, rightMetadataType.getClassLoader());
        if (leftType.isPresent() && leftType.equals(rightType)) {
            return true;
        }
        return simpleTypeName(leftTypeName).equals(simpleTypeName(rightTypeName))
               && (leftType.isPresent() || rightType.isPresent());
    }

    private static String simpleTypeName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }

    private static String executableId(ExecutableDescriptor executable) {
        return InvocationPlanDescriptor.executableId(
                executable.kind(),
                executable.name(),
                executable.parameters().stream()
                        .map(parameter -> parameter.typeName())
                        .toList());
    }

    private static Optional<List<RegisteredExecutableView>> registeredExecutableViews(
            Class<?> targetClass,
            MessageType messageType,
            HandlerConfiguration<DeserializingMessage> config) {
        return ComponentMetadataLookups.registeredLookup(targetClass)
                .map(lookup -> registeredExecutableViews(targetClass, messageType, lookup).stream()
                        .filter(view -> config.methodMatches(targetClass, view.view()))
                        .toList());
    }

    private static List<RegisteredExecutableView> registeredExecutableViews(
            Class<?> targetClass, MessageType messageType, ComponentMetadataLookup lookup) {
        return executableViewsByLookup.computeIfAbsent(
                new RegisteredExecutableViewsKey(targetClass, messageType, lookup),
                ignored -> computeRegisteredExecutableViews(targetClass, messageType, lookup));
    }

    private static List<RegisteredExecutableView> computeRegisteredExecutableViews(
            Class<?> targetClass, MessageType messageType, ComponentMetadataLookup lookup) {
        Map<String, RegisteredExecutableView> result = new LinkedHashMap<>();
        for (RegisteredRoute registeredRoute : registeredRoutes(targetClass, messageType)) {
            ExecutableDescriptor descriptor = registeredRoute.route().executableMetadata().orElse(null);
            if (descriptor == null) {
                continue;
            }
            Class<?> metadataType = registeredRoute.metadataType();
            result.putIfAbsent(
                    metadataType.getName() + ":" + executableId(descriptor),
                    new RegisteredExecutableView(
                            metadataType,
                            RegistryExecutableViews.executableView(metadataType, descriptor),
                            descriptor));
        }
        return List.copyOf(result.values());
    }

    private static List<Class<?>> metadataRouteTypes(Class<?> targetClass, ComponentMetadataLookup lookup) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        collectMetadataRouteTypes(targetClass, lookup, result);
        return List.copyOf(result);
    }

    private static void collectMetadataRouteTypes(
            Class<?> type, ComponentMetadataLookup lookup, LinkedHashSet<Class<?>> result) {
        if (type == null || Object.class.equals(type)) {
            return;
        }
        if (targetClassNames(type).stream().anyMatch(name -> lookup.component(name).isPresent())) {
            result.add(type);
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectMetadataRouteTypes(interfaceType, lookup, result);
        }
        collectMetadataRouteTypes(type.getSuperclass(), lookup, result);
    }

    private static List<String> targetClassNames(Class<?> targetClass) {
        String canonicalName = targetClass.getCanonicalName();
        return canonicalName == null || canonicalName.equals(targetClass.getName())
               ? List.of(targetClass.getName())
               : List.of(targetClass.getName(), canonicalName);
    }

    private record RegisteredRoute(Class<?> metadataType, HandlerRoute route) {
    }

    private record RegisteredExecutableView(
            Class<?> metadataType, ExecutableView view, ExecutableDescriptor descriptor) {
    }

    private static final class RegisteredExecutableViewsKey {
        private final Class<?> targetClass;
        private final MessageType messageType;
        private final ComponentMetadataLookup lookup;
        private final int hashCode;

        private RegisteredExecutableViewsKey(
                Class<?> targetClass, MessageType messageType, ComponentMetadataLookup lookup) {
            this.targetClass = targetClass;
            this.messageType = messageType;
            this.lookup = lookup;
            this.hashCode = 31 * (31 * System.identityHashCode(targetClass) + messageType.hashCode())
                            + System.identityHashCode(lookup);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof RegisteredExecutableViewsKey that
                                  && targetClass == that.targetClass
                                  && messageType == that.messageType
                                  && lookup == that.lookup;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
