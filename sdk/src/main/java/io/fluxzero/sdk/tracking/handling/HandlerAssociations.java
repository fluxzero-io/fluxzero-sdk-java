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

import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ParameterRegistry;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotation;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

/**
 * Shared utility for discovering and evaluating {@link Association} definitions on handler types and handler methods.
 * <p>
 * This class centralizes association behavior that is shared between different handler implementations, such as
 * {@link StatefulHandler} and {@link io.fluxzero.sdk.web.SocketEndpointHandler}. It can:
 * <ul>
 *     <li>inspect field-, method-, and parameter-level {@code @Association} declarations</li>
 *     <li>resolve association values from an incoming {@link DeserializingMessage}</li>
 *     <li>match a concrete handler instance against computed association values</li>
 * </ul>
 */
public class HandlerAssociations {
    private final Class<?> targetClass;
    private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    private final Function<Executable, ? extends Annotation> methodAnnotationProvider;

    private final Function<Class<?>, Map<String, AssociationValue>> associationProperties = ClientUtils.memoize(
            target -> getAnnotatedProperties(target, Association.class).stream()
                    .flatMap(member -> getAnnotation(member, Association.class).stream().flatMap(association -> {
                        String propertyName = getPropertyName(member);
                        return (association.value().length > 0
                                ? Arrays.stream(association.value()) : Stream.of(propertyName))
                                .map(v -> {
                                    var associationValue = AssociationValue.valueOf(association);
                                    if (associationValue.getPath().isBlank()) {
                                        associationValue = associationValue.toBuilder().path(propertyName).build();
                                    }
                                    return Map.entry(v, associationValue);
                                });
                    }))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a)));

    private final Function<Executable, List<MethodAssociationProperty>> methodAssociationProperties = ClientUtils.memoize(
            executable -> Stream.concat(executableAssociationProperties(executable).stream(),
                                        parameterAssociationProperties(executable).stream()).toList());

    /**
     * Creates an association helper for a specific handler type.
     *
     * @param targetClass the handler class whose association metadata should be inspected
     * @param parameterResolvers the parameter resolvers used to resolve parameter-level {@code @Association} sources
     * @param methodAnnotationProvider provider for the effective handler annotation on an executable, used when
     *                                 resolving parameter values
     */
    public HandlerAssociations(@NonNull Class<?> targetClass,
                               List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                               Function<Executable, ? extends Annotation> methodAnnotationProvider) {
        this.targetClass = targetClass;
        this.parameterResolvers = parameterResolvers == null ? List.of() : List.copyOf(parameterResolvers);
        this.methodAnnotationProvider = methodAnnotationProvider == null ? e -> null : methodAnnotationProvider;
    }

    /**
     * Returns the association definitions declared on the handler type itself, keyed by message property name.
     */
    public Map<String, AssociationValue> getAssociationProperties() {
        return associationProperties.apply(targetClass);
    }

    /**
     * Returns the association definitions contributed by a handler executable, including parameter-level
     * {@code @Association} declarations.
     */
    public List<MethodAssociationProperty> getMethodAssociationProperties(Executable executable) {
        return methodAssociationProperties.apply(executable);
    }

    /**
     * Computes the association values contributed by the given message for the supplied matching handler methods.
     * <p>
     * The returned map is keyed by association value and contains the target path within the handler instance that
     * should be matched for that value.
     */
    public Map<Object, String> associations(DeserializingMessage message, Stream<Executable> matchingMethods) {
        return ofNullable(message.getPayload()).stream()
                .flatMap(payload -> {
                    Stream<Map.Entry<Object, String>> methodAssociations = matchingMethods
                            .flatMap(e -> getMethodAssociationProperties(e).stream()
                                    .flatMap(entry -> entry.getValue(message, payload)
                                            .map(v -> Map.entry(v, entry.associationValue.getPath())).stream()));
                    Stream<Map.Entry<Object, String>> propertyAssociations = getAssociationProperties().entrySet()
                            .stream()
                            .filter(entry -> includedPayload(payload, entry.getValue()))
                            .flatMap(entry -> ReflectionUtils.readProperty(entry.getKey(), payload)
                                    .or(() -> entry.getValue().isExcludeMetadata() ? empty()
                                            : ofNullable(message.getMetadata().get(entry.getKey())))
                                    .map(this::normalizeAssociationValue)
                                    .map(v -> Map.entry(v, entry.getValue().getPath()))
                                    .stream());
                    return Stream.concat(methodAssociations, propertyAssociations);
                })
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a.isBlank() ? b : a));
    }

    /**
     * Returns whether the given handler instance matches any of the computed association values.
     */
    public boolean matchesTarget(Object target, Map<Object, String> associations) {
        return !associations.isEmpty() && associations.entrySet().stream()
                .anyMatch(entry -> matchesValue(ReflectionUtils.readProperty(entry.getValue(), target).orElse(null),
                                                entry.getKey()));
    }

    protected boolean matchesValue(Object targetValue, Object associationValue) {
        if (targetValue instanceof Collection<?> collection) {
            return collection.stream().anyMatch(v -> matchesValue(v, associationValue));
        }
        return Objects.equals(normalizeAssociationValue(targetValue), normalizeAssociationValue(associationValue));
    }

    protected Object normalizeAssociationValue(Object value) {
        return value instanceof Id<?> id ? id.getFunctionalId() : value;
    }

    protected boolean includedPayload(Object payload, AssociationValue association) {
        Class<?> payloadType = payload.getClass();
        if (!association.includedClasses.isEmpty()
            && association.includedClasses.stream().noneMatch(c -> c.isAssignableFrom(payloadType))) {
            return false;
        }
        return association.excludedClasses.stream().noneMatch(c -> c.isAssignableFrom(payloadType));
    }

    protected List<MethodAssociationProperty> executableAssociationProperties(Executable executable) {
        return getAnnotation(executable, Association.class).map(association -> {
            AssociationValue associationValue = AssociationValue.valueOf(association);
            if (association.value().length > 0) {
                return Arrays.stream(association.value())
                        .map(v -> MethodAssociationProperty.forProperty(v, associationValue)).toList();
            }
            return getAnnotation(executable, RoutingKey.class).map(RoutingKey::value)
                    .filter(v -> !v.isBlank())
                    .map(v -> List.of(MethodAssociationProperty.forProperty(v, associationValue)))
                    .orElseGet(() -> List.of(MethodAssociationProperty.forComputedRoutingKey(associationValue)));
        }).orElseGet(Collections::emptyList);
    }

    protected List<MethodAssociationProperty> parameterAssociationProperties(Executable executable) {
        Annotation methodAnnotation = methodAnnotationProvider.apply(executable);
        return Arrays.stream(executable.getParameters())
                .flatMap(parameter -> getAnnotation(parameter, Association.class).stream()
                        .flatMap(association -> associationPropertyNames(parameter, association)
                                .map(name -> MethodAssociationProperty.forParameterProperty(
                                        name, AssociationValue.valueOf(association),
                                        message -> resolveParameterValue(executable, parameter, methodAnnotation,
                                                                         message)))))
                .toList();
    }

    protected Stream<String> associationPropertyNames(Parameter parameter, Association association) {
        if (association.value().length > 0) {
            return Arrays.stream(association.value());
        }
        return resolveParameterName(parameter).stream();
    }

    protected Optional<String> resolveParameterName(Parameter parameter) {
        if (parameter.isNamePresent()) {
            return Optional.of(parameter.getName());
        }
        try {
            return Optional.of(ParameterRegistry.of(parameter.getDeclaringExecutable().getDeclaringClass())
                                       .getParameterName(parameter));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    protected Object resolveParameterValue(Executable executable, Parameter parameter, Annotation methodAnnotation,
                                           DeserializingMessage message) {
        return applicableParameterResolvers(executable).stream()
                .filter(r -> r.matches(parameter, methodAnnotation, message))
                .filter(r -> r.test(message, parameter))
                .findFirst()
                .map(r -> r.resolve(parameter, methodAnnotation).apply(message))
                .orElse(null);
    }

    protected List<ParameterResolver<? super DeserializingMessage>> applicableParameterResolvers(Executable executable) {
        return parameterResolvers.stream().filter(r -> mayApply(r, executable)).toList();
    }

    protected boolean mayApply(ParameterResolver<? super DeserializingMessage> resolver, Executable executable) {
        try {
            return resolver.mayApply(executable, targetClass);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Value
    @Builder(toBuilder = true)
    public static class AssociationValue {
        static AssociationValue valueOf(Association association) {
            return ReflectionUtils.convertAnnotation(association, AssociationValue.class);
        }

        List<String> value;
        String path;
        List<Class<?>> includedClasses;
        List<Class<?>> excludedClasses;
        boolean excludeMetadata;
        boolean always;

        public String getPath() {
            return path == null ? "" : path;
        }
    }

    @Value(staticConstructor = "of")
    public static class MethodAssociationProperty {
        String propertyName;
        boolean computedRoutingKey;
        AssociationValue associationValue;
        Function<DeserializingMessage, Object> parameterValueResolver;

        static MethodAssociationProperty forProperty(String propertyName, AssociationValue associationValue) {
            return of(propertyName, false, associationValue, null);
        }

        static MethodAssociationProperty forParameterProperty(
                String propertyName, AssociationValue associationValue,
                Function<DeserializingMessage, Object> parameterValueResolver) {
            return of(propertyName, false, associationValue, parameterValueResolver);
        }

        static MethodAssociationProperty forComputedRoutingKey(AssociationValue associationValue) {
            return of(null, true, associationValue, null);
        }

        Optional<Object> getValue(DeserializingMessage message, Object payload) {
            if (computedRoutingKey) {
                return message.computeRoutingKey().map(v -> v);
            }
            Object source = parameterValueResolver == null ? payload : parameterValueResolver.apply(message);
            return ofNullable(source).flatMap(resolvedValue -> ReflectionUtils.readProperty(propertyName, resolvedValue)
                            .or(() -> associationValue.isExcludeMetadata() ? empty()
                                    : ofNullable(message.getMetadata().get(propertyName))))
                    .map(v -> v instanceof Id<?> id ? id.getFunctionalId() : v);
        }
    }
}
