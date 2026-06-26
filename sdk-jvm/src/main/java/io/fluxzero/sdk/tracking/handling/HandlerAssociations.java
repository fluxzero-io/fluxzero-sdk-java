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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ParameterRegistry;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Id;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

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
    private static final ClassValue<AssociationMetadata> metadataCache = new ClassValue<>() {
        @Override
        protected AssociationMetadata computeValue(Class<?> type) {
            return new AssociationMetadata(type);
        }
    };

    private final Class<?> targetClass;
    private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    private final Function<Executable, ? extends Annotation> methodAnnotationProvider;
    private final AssociationMetadata metadata;
    private final Function<Executable, List<MethodAssociationProperty>> boundMethodAssociationProperties =
            ClientUtils.memoize(this::computeMethodAssociationProperties);

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
        this.metadata = metadataCache.get(targetClass);
    }

    /**
     * Returns the association definitions declared on the handler type itself, keyed by message property name.
     */
    public Map<String, AssociationValue> getAssociationProperties() {
        return metadata.associationProperties();
    }

    /**
     * Returns the association definitions contributed by a handler executable, including parameter-level
     * {@code @Association} declarations.
     */
    public List<MethodAssociationProperty> getMethodAssociationProperties(Executable executable) {
        return boundMethodAssociationProperties.apply(executable);
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

    protected List<MethodAssociationProperty> computeMethodAssociationProperties(Executable executable) {
        Annotation methodAnnotation = methodAnnotationProvider.apply(executable);
        Stream<MethodAssociationProperty> parameterAssociations = metadata.parameterAssociationProperties(executable)
                .stream()
                .map(template -> MethodAssociationProperty.forParameterProperty(
                        template.getPropertyName(), template.getAssociationValue(),
                        message -> resolveParameterValue(executable, template.getParameter(), methodAnnotation,
                                                         message)));
        return Stream.concat(metadata.executableAssociationProperties(executable).stream(),
                             parameterAssociations).toList();
    }

    public boolean alwaysAssociate(Executable executable) {
        return metadata.alwaysAssociate(executable);
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

    @Value
    static class ParameterAssociationTemplate {
        Parameter parameter;
        String propertyName;
        AssociationValue associationValue;
    }

    private static final class AssociationMetadata {
        private final Class<?> targetClass;
        private final Map<String, AssociationValue> associationProperties;
        private final ConcurrentHashMap<Executable, List<MethodAssociationProperty>>
                executableAssociationProperties = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Executable, List<ParameterAssociationTemplate>>
                parameterAssociationProperties = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Executable, Boolean> alwaysAssociate = new ConcurrentHashMap<>();

        private AssociationMetadata(Class<?> targetClass) {
            this.targetClass = targetClass;
            this.associationProperties = Map.copyOf(computeAssociationProperties(targetClass));
        }

        private Map<String, AssociationValue> associationProperties() {
            return associationProperties;
        }

        private List<MethodAssociationProperty> executableAssociationProperties(Executable executable) {
            return getOrCompute(executableAssociationProperties, executable, this::computeExecutableAssociationProperties);
        }

        private List<ParameterAssociationTemplate> parameterAssociationProperties(Executable executable) {
            return getOrCompute(parameterAssociationProperties, executable, this::computeParameterAssociationProperties);
        }

        private boolean alwaysAssociate(Executable executable) {
            return getOrCompute(alwaysAssociate, executable,
                                key -> ReflectionUtils.getAnnotation(key, Association.class)
                                        .filter(Association::always)
                                        .isPresent());
        }

        private Map<String, AssociationValue> computeAssociationProperties(Class<?> targetClass) {
            return ReflectionUtils.getAnnotatedProperties(targetClass, Association.class).stream()
                    .flatMap(member -> ReflectionUtils.getAnnotationAs(member, Association.class, AssociationValue.class)
                            .stream()
                            .flatMap(associationValue -> {
                                String propertyName = ReflectionUtils.getPropertyName(member);
                                AssociationValue mappedValue = associationValue.getPath().isBlank()
                                        ? associationValue.toBuilder().path(propertyName).build()
                                        : associationValue;
                                List<String> aliases = associationValue.getValue();
                                return (aliases == null || aliases.isEmpty() ? Stream.of(propertyName) : aliases.stream())
                                        .map(name -> Map.entry(name, mappedValue));
                            }))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        }

        private List<MethodAssociationProperty> computeExecutableAssociationProperties(Executable executable) {
            return ReflectionUtils.getAnnotationAs(executable, Association.class, AssociationValue.class)
                    .map(associationValue -> {
                        List<String> aliases = associationValue.getValue();
                        if (aliases != null && !aliases.isEmpty()) {
                            return aliases.stream()
                                    .map(name -> MethodAssociationProperty.forProperty(name, associationValue))
                                    .toList();
                        }
                        return ReflectionUtils.getAnnotation(executable, io.fluxzero.sdk.publishing.routing.RoutingKey.class)
                                .map(io.fluxzero.sdk.publishing.routing.RoutingKey::value)
                                .filter(value -> !value.isBlank())
                                .map(value -> List.of(MethodAssociationProperty.forProperty(value, associationValue)))
                                .orElseGet(() -> List.of(
                                        MethodAssociationProperty.forComputedRoutingKey(associationValue)));
                    })
                    .orElseGet(Collections::emptyList);
        }

        private List<ParameterAssociationTemplate> computeParameterAssociationProperties(Executable executable) {
            return Arrays.stream(executable.getParameters())
                    .flatMap(parameter -> ReflectionUtils.getAnnotationAs(parameter, Association.class, AssociationValue.class)
                            .stream()
                            .flatMap(associationValue -> propertyNames(parameter, associationValue).map(
                                    propertyName -> new ParameterAssociationTemplate(parameter, propertyName,
                                                                                     associationValue))))
                    .toList();
        }

        private Stream<String> propertyNames(Parameter parameter, AssociationValue associationValue) {
            List<String> aliases = associationValue.getValue();
            if (aliases != null && !aliases.isEmpty()) {
                return aliases.stream();
            }
            if (parameter.isNamePresent()) {
                return Stream.of(parameter.getName());
            }
            try {
                return Stream.of(ParameterRegistry.of(
                                         parameter.getDeclaringExecutable().getDeclaringClass())
                                         .getParameterName(parameter));
            } catch (RuntimeException ignored) {
                return Stream.empty();
            }
        }

        private static <K, V> V getOrCompute(ConcurrentHashMap<K, V> cache, K key, Function<K, V> loader) {
            V cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            V computed = loader.apply(key);
            V existing = cache.putIfAbsent(key, computed);
            return existing != null ? existing : computed;
        }
    }
}
