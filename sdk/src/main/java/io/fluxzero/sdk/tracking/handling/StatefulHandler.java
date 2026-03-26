/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ParameterRegistry;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Tracker;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedPropertyValue;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotation;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

/**
 * A {@link Handler} implementation for classes annotated with {@link Stateful}, responsible for resolving and invoking
 * stateful handler instances based on {@link Association} metadata.
 * <p>
 * This handler enables long-lived, stateful components to participate in message processing. It ensures that:
 * <ul>
 *     <li>Messages are routed to the correct instance(s) based on matching association keys</li>
 *     <li>Instances are automatically created, updated, or deleted depending on the result of handler methods</li>
 *     <li>Static methods may initialize new handler instances (e.g., factory methods on creation events)</li>
 *     <li>Association routing is supported via property-based or method-level annotations</li>
 * </ul>
 *
 * <h2>Routing Logic</h2>
 * The handler uses the following mechanisms to determine message dispatch:
 * <ul>
 *     <li>{@link Association} annotations on fields and methods define the routing keys used to match incoming messages to stateful instances.</li>
 *     <li>{@link EntityId} defines the identity of the handler, used when persisting or retrieving state.</li>
 *     <li>If no matching instances are found, static methods marked with {@code @Handle...} and {@code @Association(always = true)} may be invoked to initialize new instances.</li>
 *     <li>Fallback routing via {@link RoutingKey} annotations or message metadata is also supported.</li>
 * </ul>
 *
 * <h2>Persistence and Lifecycle</h2>
 * <ul>
 *     <li>The resolved handler instances are loaded and stored via a {@link HandlerRepository} (typically backed by the {@code DocumentStore}).</li>
 *     <li>If a handler method returns a new instance, it replaces the current state.</li>
 *     <li>If a handler method returns {@code null}, the instance is removed from storage.</li>
 * </ul>
 *
 * <h2>Batch-Aware Behavior</h2>
 * <ul>
 *     <li>Routing decisions may respect the current {@link Tracker} context, segment ownership, and routing constraints.</li>
 *     <li>Within a batch, state changes may be staged locally before committing (when {@code commitInBatch = true}).</li>
 * </ul>
 *
 * <h2>Internal Mechanics</h2>
 * <ul>
 *     <li>Associations are lazily resolved and memoized for performance.</li>
 *     <li>Handler invocation is delegated via {@link HandlerMatcher} and {@link HandlerInvoker} abstractions.</li>
 *     <li>Support is provided for multiple matches and combined invocation across entries.</li>
 * </ul>
 *
 * @see Stateful
 * @see Association
 * @see HandlerRepository
 * @see HandlerInvoker
 * @see DeserializingMessage
 * @see io.fluxzero.sdk.tracking.Tracker
 */
@Getter
@AllArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Slf4j
public class StatefulHandler implements Handler<DeserializingMessage> {
    Class<?> targetClass;
    HandlerMatcher<Object, DeserializingMessage> handlerMatcher;
    HandlerRepository repository;
    List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    Function<Executable, ? extends java.lang.annotation.Annotation> methodAnnotationProvider;

    public StatefulHandler(Class<?> targetClass,
                           HandlerMatcher<Object, DeserializingMessage> handlerMatcher,
                           HandlerRepository repository) {
        this(targetClass, handlerMatcher, repository, List.of(), e -> null);
    }

    @Getter(lazy = true)
    Map<String, AssociationValue> associationProperties =
            getAnnotatedProperties(getTargetClass(), Association.class).stream()
                    .flatMap(member -> getAnnotation(member, Association.class).stream().flatMap(
                            association -> {
                                String propertyName = getPropertyName(member);
                                return (association.value().length > 0
                                        ? Arrays.stream(association.value()) : Stream.of(propertyName))
                                        .map(v -> {
                                            var associationValue = AssociationValue.valueOf(association);
                                            if (associationValue.getPath().isBlank()) {
                                                associationValue = associationValue.toBuilder()
                                                        .path(propertyName).build();
                                            }
                                            return Map.entry(v, associationValue);
                                        });
                            }))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    Function<Executable, List<MethodAssociationProperty>> methodAssociationProperties = ClientUtils.memoize(
            m -> Stream.concat(executableAssociationProperties(m).stream(), parameterAssociationProperties(m).stream())
                    .toList());

    Function<Executable, Boolean> alwaysAssociateMethods = ClientUtils.memoize(
            m -> getAnnotation(m, Association.class).filter(Association::always).isPresent());

    @Override
    public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
        if (!handlerMatcher.canHandle(message)) {
            return Optional.empty();
        }
        var alwaysMatch = handlerMatcher.matchingMethods(message).anyMatch(alwaysAssociateMethods::apply);
        var alwaysInvoke = alwaysMatch && handlerMatcher.matchingMethods(message).allMatch(ReflectionUtils::isStatic);
        var matches = alwaysInvoke ? List.<Entry<?>>of() : alwaysMatch ? repository.getAll()
                : repository.findByAssociation(associations(message));
        if (matches.isEmpty()) {
            return handlerMatcher.getInvoker(null, message)
                    .filter(i -> {
                        if (alreadyFiltered(i)) {
                            return true;
                        }
                        String routingKey = ReflectionUtils.getAnnotatedProperty(targetClass, EntityId.class)
                                .map(ReflectionUtils::getPropertyName)
                                .flatMap(propertyName -> message.getRoutingKey(propertyName, false))
                                .orElseGet(message::getMessageId);
                        return canTrackerHandle(message, routingKey);
                    })
                    .map(i -> new StatefulHandlerInvoker(i, null, message));
        }
        List<HandlerInvoker> invokers = new ArrayList<>();
        for (Entry<?> entry : matches) {
            handlerMatcher.getInvoker(entry.getValue(), message)
                    .filter(i -> alreadyFiltered(i) || canTrackerHandle(message, entry.getId()))
                    .map(i -> new StatefulHandlerInvoker(i, entry, message))
                    .ifPresent(invokers::add);
        }
        return HandlerInvoker.join(invokers);
    }

    protected boolean alreadyFiltered(HandlerInvoker i) {
        return ReflectionUtils.getMethodAnnotation(i.getMethod(), RoutingKey.class).isPresent();
    }

    protected Boolean canTrackerHandle(DeserializingMessage message, String routingKey) {
        return Tracker.current().filter(tracker -> tracker.getConfiguration().ignoreSegment())
                .map(tracker -> tracker.canHandle(message, routingKey)).orElse(true);
    }

    protected Map<Object, String> associations(DeserializingMessage message) {
        return ofNullable(message.getPayload()).stream()
                .flatMap(payload -> {
                    Stream<Map.Entry<Object, String>> methodAssociations = handlerMatcher.matchingMethods(message)
                            .flatMap(e -> methodAssociationProperties.apply(e).stream()
                                    .flatMap(entry -> entry.getValue(message, payload)
                                            .map(v -> Map.entry(v, entry.associationValue.getPath()))
                                            .stream()));
                    Stream<Map.Entry<Object, String>> propertyAssociations = getAssociationProperties().entrySet()
                            .stream()
                            .filter(entry -> includedPayload(payload, entry.getValue()))
                            .flatMap(entry -> ReflectionUtils.readProperty(entry.getKey(), payload)
                                    .or(() -> entry.getValue().isExcludeMetadata() ? empty() :
                                            ofNullable(message.getMetadata().get(entry.getKey())))
                                    .map(v -> v instanceof Id<?> id ? id.getFunctionalId() : v)
                                    .map(v -> Map.entry(v, entry.getValue().getPath()))
                                    .stream());
                    return Stream.concat(methodAssociations, propertyAssociations);
                })
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a.isBlank() ? b : a));
    }

    protected boolean includedPayload(Object payload, AssociationValue association) {
        Class<?> payloadType = payload.getClass();
        if (!association.includedClasses.isEmpty()
            && association.includedClasses.stream().noneMatch(c -> c.isAssignableFrom(payloadType))) {
            return false;
        }
        return association.excludedClasses.stream().noneMatch(c -> c.isAssignableFrom(payloadType));
    }

    @Override
    public String toString() {
        return "StatefulHandler[%s]".formatted(targetClass);
    }

    @Value
    @Builder(toBuilder = true)
    protected static class AssociationValue {
        public static AssociationValue valueOf(Association association) {
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
    protected static class MethodAssociationProperty {
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
                return message.computeRoutingKey().map(v -> (Object) v)
                        .map(v -> v instanceof Id<?> id ? id.getFunctionalId() : v);
            }
            Object source = parameterValueResolver == null ? payload : parameterValueResolver.apply(message);
            return ofNullable(source).flatMap(resolvedValue -> ReflectionUtils.readProperty(propertyName, resolvedValue)
                            .or(() -> associationValue.isExcludeMetadata() ? empty()
                                    : ofNullable(message.getMetadata().get(propertyName))))
                    .map(v -> v instanceof Id<?> id ? id.getFunctionalId() : v);
        }
    }

    protected List<MethodAssociationProperty> executableAssociationProperties(Executable executable) {
        return getAnnotation(executable, Association.class).map(
                association -> {
                    AssociationValue associationValue = AssociationValue.valueOf(association);
                    if (association.value().length > 0) {
                        return Arrays.stream(association.value())
                                .map(v -> MethodAssociationProperty.forProperty(v, associationValue)).toList();
                    }
                    return getAnnotation(executable, RoutingKey.class).map(RoutingKey::value)
                            .filter(v -> !v.isBlank())
                            .map(v -> List.of(MethodAssociationProperty.forProperty(v, associationValue)))
                            .orElseGet(() -> List.of(MethodAssociationProperty.forComputedRoutingKey(
                                    associationValue)));
                }).orElseGet(Collections::emptyList);
    }

    protected List<MethodAssociationProperty> parameterAssociationProperties(Executable executable) {
        var methodAnnotation = methodAnnotationProvider.apply(executable);
        return Arrays.stream(executable.getParameters())
                .flatMap(parameter -> getAnnotation(parameter, Association.class).stream()
                        .flatMap(association -> associationPropertyNames(parameter, association)
                                .map(name -> MethodAssociationProperty.forParameterProperty(
                                        name, AssociationValue.valueOf(association),
                                        message -> resolveParameterValue(
                                                executable, parameter, methodAnnotation, message)))))
                .toList();
    }

    protected Object resolveParameterValue(Executable executable,
                                           Parameter parameter,
                                           java.lang.annotation.Annotation methodAnnotation,
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

    protected class StatefulHandlerInvoker extends HandlerInvoker.DelegatingHandlerInvoker {
        private final Entry<?> currentEntry;
        private final DeserializingMessage message;

        public StatefulHandlerInvoker(HandlerInvoker delegate, Entry<?> currentEntry, DeserializingMessage message) {
            super(delegate);
            this.currentEntry = currentEntry;
            this.message = message;
        }

        @Override
        public Object invoke(BiFunction<Object, Object, Object> combiner) {
            Object result = delegate.invoke(combiner);
            handleResult(result);
            return result;
        }

        /**
         * Applies stateful persistence semantics to a handler invocation result.
         * <p>
         * Supported result forms:
         * <ul>
         *     <li>{@code Collection<?>}: stores every same-type instance in the collection. If the collection is empty,
         *     the current entry is deleted. If the current entry ID is not part of returned IDs, the current entry is
         *     deleted.</li>
         *     <li>{@code null}: deletes the current entry only when the invoked method is expected to return handler
         *     state (same/assignable handler type).</li>
         *     <li>Single object: stores it only if it is assignable to the handler type. If the stored ID differs from
         *     the current ID, the current entry is deleted (replacement behavior).</li>
         * </ul>
         */
        @SneakyThrows
        protected void handleResult(Object result) {
            if (result instanceof Collection<?> collection) {
                if (collection.isEmpty()) {
                    if (currentEntry != null) {
                        //when an empty collection is returned, the entry is deleted
                        repository.delete(currentEntry.getId()).get();
                    }
                } else {
                    var newIds = collection.stream().map(this::tryStoreResult).filter(Objects::nonNull)
                            .map(Object::toString).toList();
                    if (new HashSet<>(newIds).size() != newIds.size()) {
                        log.warn("Duplicate IDs returned from stateful handler {}#{}: {}."
                                 + " Please ensure that each handler has a unique property marked with @EntityId!",
                                 getTargetClass(), getMethod(), newIds);
                    }
                    if (!newIds.isEmpty() && currentEntry != null && !newIds.contains(currentEntry.getId())) {
                        // entry was deleted because the collection did not contain the current entry
                        repository.delete(currentEntry.getId()).get();
                    }
                }
            } else if (result == null) {
                if (expectResult() && getMethod() instanceof Method m
                    && (getTargetClass().isAssignableFrom(m.getReturnType())
                    || m.getReturnType().isAssignableFrom(getTargetClass()))) {
                    if (currentEntry != null) {
                        repository.delete(currentEntry.getId()).get();
                    }
                }
            } else {
                Object newId = tryStoreResult(result);
                if (newId != null && currentEntry != null && !currentEntry.getId().equals(newId.toString())) {
                    // entry was replaced
                    repository.delete(currentEntry.getId()).get();
                }
            }
        }

        /**
         * Stores a result object if it is assignable to the stateful handler type.
         *
         * @param result object returned from a handler method
         * @return the computed/stored handler ID, or {@code null} if the result is not a stateful handler instance
         */
        protected Object tryStoreResult(Object result) {
            if (!getTargetClass().isInstance(result)) {
                return null;
            }
            Object id = computeId(result);
            if (currentEntry == null || !Objects.equals(currentEntry.getValue(), result)) {
                repository.put(id, result).join();
            }
            return id;
        }

        protected Object computeId(Object handler) {
            return getAnnotatedPropertyValue(handler, EntityId.class)
                    .or(() -> ofNullable(currentEntry).map(Entry::getId))
                    .orElseGet(message::getMessageId);
        }
    }
}
