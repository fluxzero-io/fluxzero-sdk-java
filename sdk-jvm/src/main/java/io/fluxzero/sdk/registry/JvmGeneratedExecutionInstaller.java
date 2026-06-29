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

import io.fluxzero.common.Registration;
import io.fluxzero.common.handling.ExecutableInvocation;
import io.fluxzero.common.handling.ExecutableInvocationBackend;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JVM bridge that lowers registry invocation plans to executable invocation handles.
 * <p>
 * Build/browser targets should eventually emit direct generated invocations. The JVM can install equivalent handles
 * from the same registry metadata so generated-only tests prove the same matching and binding model while retaining a
 * Java-specific invocation backend.
 */
public final class JvmGeneratedExecutionInstaller {
    private static final ExecutableInvocationBackend JVM_BACKEND = ExecutableInvocationBackend.reflection();

    private JvmGeneratedExecutionInstaller() {
    }

    public static Registration install(ComponentRegistry registry) {
        return install(registry, null);
    }

    public static Registration install(ComponentRegistry registry, ClassLoader preferredLoader) {
        return install(registry, preferredLoader, List.of());
    }

    public static Registration install(
            ComponentRegistry registry, ClassLoader preferredLoader, Collection<String> componentNames) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(componentNames, "componentNames");
        Set<String> rootNames = resolveRequestedComponentNames(registry, componentNames);
        Set<String> requestedNames = expandRequestedComponentNames(registry, rootNames);
        boolean limitedInstall = !componentNames.isEmpty();
        ComponentRegistry normalized = limitedInstall
                ? new ComponentRegistry(
                        registry.sourceRoot(),
                        List.of(),
                        registry.components().stream()
                                .filter(component -> requestedNames.contains(component.fullClassName()))
                                .toList()).normalized()
                : registry.normalized();
        if (normalized.isEmpty()) {
            return Registration.noOp();
        }
        List<AutoCloseable> registrations = new ArrayList<>();
        for (ComponentDescriptor component : normalized.components()) {
            Optional<Class<?>> targetClass = JvmComponentMetadataLookup.classForMetadataName(
                    component.fullClassName(), preferredLoader);
            if (targetClass.isEmpty()) {
                continue;
            }
            if (!limitedInstall || rootNames.contains(component.fullClassName())) {
                installInvocations(component, targetClass.orElseThrow(), registrations);
            }
            installPropertyAccesses(component, targetClass.orElseThrow(), registrations);
        }
        return () -> {
            for (int i = registrations.size() - 1; i >= 0; i--) {
                try {
                    registrations.get(i).close();
                } catch (Exception ignored) {
                    // Generated bridge registrations are best-effort lifecycle cleanup.
                }
            }
        };
    }

    private static Set<String> resolveRequestedComponentNames(
            ComponentRegistry registry, Collection<String> componentNames) {
        if (componentNames.isEmpty()) {
            return Set.of();
        }
        Map<String, ComponentDescriptor> componentsByName = new LinkedHashMap<>();
        registry.components().forEach(component -> componentsByName.put(component.fullClassName(), component));
        return componentNames.stream()
                .map(componentName -> resolveComponentName(componentsByName, componentName))
                .flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> expandRequestedComponentNames(
            ComponentRegistry registry, Collection<String> componentNames) {
        Map<String, ComponentDescriptor> componentsByName = new LinkedHashMap<>();
        registry.components().forEach(component -> componentsByName.put(component.fullClassName(), component));
        if (componentNames.isEmpty() || componentsByName.isEmpty()) {
            return Set.copyOf(componentNames);
        }
        Set<String> requestedNames = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        componentNames.stream()
                .map(componentName -> resolveComponentName(componentsByName, componentName))
                .flatMap(Optional::stream)
                .forEach(componentName -> enqueue(componentName, requestedNames, queue));
        while (!queue.isEmpty()) {
            ComponentDescriptor component = componentsByName.get(queue.removeFirst());
            if (component == null) {
                continue;
            }
            referencedTypeNames(component)
                    .map(componentName -> resolveComponentName(componentsByName, componentName))
                    .flatMap(Optional::stream)
                    .forEach(componentName -> enqueue(componentName, requestedNames, queue));
        }
        return requestedNames;
    }

    private static void enqueue(String componentName, Set<String> requestedNames, Deque<String> queue) {
        if (requestedNames.add(componentName)) {
            queue.add(componentName);
        }
    }

    private static Optional<String> resolveComponentName(
            Map<String, ComponentDescriptor> componentsByName, String componentName) {
        if (componentName == null || componentName.isBlank()) {
            return Optional.empty();
        }
        String current = componentName;
        while (true) {
            if (componentsByName.containsKey(current)) {
                return Optional.of(current);
            }
            int separator = current.lastIndexOf('.');
            if (separator < 0) {
                return Optional.empty();
            }
            current = current.substring(0, separator) + "$" + current.substring(separator + 1);
        }
    }

    private static Stream<String> referencedTypeNames(ComponentDescriptor component) {
        List<String> result = new ArrayList<>();
        result.addAll(component.superTypeNames());
        component.properties().forEach(property -> {
            result.add(property.typeName());
            result.add(property.genericTypeName());
            collectTypeUseNames(property.typeUse(), result);
        });
        component.handlerRoutes().forEach(route -> {
            result.addAll(route.payloadTypeNames());
            result.addAll(route.allowedClassNames());
        });
        component.registeredTypes().stream()
                .flatMap(registeredType -> registeredType.candidateTypeNames().stream())
                .forEach(result::add);
        return result.stream().filter(Objects::nonNull).distinct();
    }

    private static void collectTypeUseNames(TypeUseDescriptor typeUse, List<String> result) {
        if (typeUse == null || typeUse == TypeUseDescriptor.EMPTY) {
            return;
        }
        result.add(typeUse.typeName());
        typeUse.typeArguments().forEach(argument -> collectTypeUseNames(argument, result));
        collectTypeUseNames(typeUse.componentType(), result);
    }

    /**
     * Returns a JVM invocation backend that prefers generated invocation registrations.
     * <p>
     * Hybrid mode falls back to the JVM reflection backend when no generated invocation is available. Strict
     * generated-only mode requires an installed invocation plan and fails clearly if one is missing.
     */
    public static ExecutableInvocationBackend executableInvocationBackend() {
        return JvmGeneratedExecutionInstaller::prepareInvocation;
    }

    private static ExecutableInvocation prepareInvocation(Executable executable) {
        ComponentMetadataLookups.ensureGeneratedExecutions(executable.getDeclaringClass());
        Optional<ExecutableInvocation> generatedInvocation = GeneratedExecutableInvocations.find(executable);
        if (generatedInvocation.isPresent()) {
            return generatedInvocation.orElseThrow();
        }
        if (ComponentMetadataLookups.strictGeneratedOnlyMode()) {
            throw new ComponentRegistryException(
                    "Strict generated-only metadata mode requires generated invocation metadata for %s"
                            .formatted(executable));
        }
        return JVM_BACKEND.prepare(executable);
    }

    private static void installInvocations(
            ComponentDescriptor component, Class<?> targetClass, List<AutoCloseable> registrations) {
        for (InvocationPlanDescriptor plan : invocationPlans(component)) {
            executable(targetClass, plan).ifPresent(executable -> {
                ExecutableInvocation invocation = JVM_BACKEND.prepare(executable);
                boolean hasActiveInvocation = GeneratedExecutableInvocations.find(
                        targetClass, plan.executableId()).isPresent();
                registrations.add(hasActiveInvocation
                        ? GeneratedExecutableInvocations.registerFallback(
                                targetClass, plan.executableId(), invocation)
                        : GeneratedExecutableInvocations.register(
                                targetClass, plan.executableId(), invocation));
            });
        }
    }

    private static List<InvocationPlanDescriptor> invocationPlans(ComponentDescriptor component) {
        List<PropertyAccessPlanDescriptor> propertyAccesses = component.properties().stream()
                .map(JvmGeneratedExecutionInstaller::propertyAccessPlan)
                .toList();
        return component.executables().stream()
                .map(executable -> invocationPlan(component.fullClassName(), executable, propertyAccesses))
                .toList();
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

    private static void installPropertyAccesses(
            ComponentDescriptor component, Class<?> targetClass, List<AutoCloseable> registrations) {
        for (PropertyDescriptor property : component.properties()) {
            propertyReader(targetClass, property.name()).ifPresent(reader -> registrations.add(
                    GeneratedPropertyAccesses.registerReader(targetClass, property.name(), reader)));
            propertyWriter(targetClass, property.name()).ifPresent(writer -> registrations.add(
                    GeneratedPropertyAccesses.registerWriter(targetClass, property.name(), writer)));
        }
    }

    private static Optional<GeneratedPropertyAccesses.PropertyReader> propertyReader(
            Class<?> targetClass, String propertyName) {
        return propertyAccessor(targetClass, propertyName)
                .<GeneratedPropertyAccesses.PropertyReader>map(method -> target -> invoke(method, target))
                .or(() -> propertyField(targetClass, propertyName)
                        .map(field -> target -> get(field, target)));
    }

    private static Optional<GeneratedPropertyAccesses.PropertyWriter> propertyWriter(
            Class<?> targetClass, String propertyName) {
        String setterName = "set" + capitalize(propertyName);
        Optional<Method> setter = allMethods(targetClass).stream()
                .filter(method -> method.getName().equals(setterName))
                .filter(method -> method.getParameterCount() == 1)
                .findFirst();
        if (setter.isPresent()) {
            Method method = setter.orElseThrow();
            method.setAccessible(true);
            return Optional.of((target, value) -> invoke(method, target, value));
        }
        return propertyField(targetClass, propertyName)
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> (target, value) -> set(field, target, value));
    }

    private static Optional<Method> propertyAccessor(Class<?> targetClass, String propertyName) {
        List<String> names = List.of(propertyName, "get" + capitalize(propertyName), "is" + capitalize(propertyName));
        return allMethods(targetClass).stream()
                .filter(method -> names.contains(method.getName()))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> method.getReturnType() != void.class)
                .findFirst()
                .map(method -> {
                    method.setAccessible(true);
                    return method;
                });
    }

    private static Optional<Field> propertyField(Class<?> targetClass, String propertyName) {
        for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(propertyName);
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return Arrays.stream(targetClass.getInterfaces())
                .map(type -> propertyField(type, propertyName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static List<Method> allMethods(Class<?> targetClass) {
        List<Method> result = new ArrayList<>();
        try {
            result.addAll(List.of(targetClass.getMethods()));
        } catch (NoClassDefFoundError | TypeNotPresentException ignored) {
            return result;
        }
        for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
            try {
                result.addAll(List.of(current.getDeclaredMethods()));
            } catch (NoClassDefFoundError | TypeNotPresentException ignored) {
                return result;
            }
        }
        try {
            Arrays.stream(targetClass.getInterfaces()).flatMap(type -> allMethods(type).stream()).forEach(result::add);
        } catch (NoClassDefFoundError | TypeNotPresentException ignored) {
            return result;
        }
        return result;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : target, args);
        } catch (ReflectiveOperationException e) {
            throw new ComponentRegistryException(
                    "Failed to invoke generated JVM property accessor %s".formatted(method), e);
        }
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(Modifier.isStatic(field.getModifiers()) ? null : target);
        } catch (IllegalAccessException e) {
            throw new ComponentRegistryException(
                    "Failed to read generated JVM property %s".formatted(field), e);
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(Modifier.isStatic(field.getModifiers()) ? null : target, value);
        } catch (IllegalAccessException e) {
            throw new ComponentRegistryException(
                    "Failed to write generated JVM property %s".formatted(field), e);
        }
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Optional<Executable> executable(Class<?> targetClass, InvocationPlanDescriptor plan) {
        return switch (plan.kind()) {
            case METHOD -> JvmComponentIntrospector.getInstance().getAllMethods(targetClass).stream()
                    .filter(method -> matches(method, plan))
                    .<Executable>map(method -> method)
                    .findFirst();
            case CONSTRUCTOR -> List.of(targetClass.getDeclaredConstructors()).stream()
                    .filter(constructor -> matches(constructor, plan))
                    .<Executable>map(constructor -> constructor)
                    .findFirst();
        };
    }

    private static boolean matches(Method method, InvocationPlanDescriptor plan) {
        return plan.name().equals(method.getName())
               && plan.executableId().equals(GeneratedExecutableInvocations.executableId(method));
    }

    private static boolean matches(Constructor<?> constructor, InvocationPlanDescriptor plan) {
        return plan.executableId().equals(GeneratedExecutableInvocations.executableId(constructor));
    }
}
