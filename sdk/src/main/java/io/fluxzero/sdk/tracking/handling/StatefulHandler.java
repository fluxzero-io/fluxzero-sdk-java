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

import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Tracker;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedPropertyValue;
import static java.util.Optional.ofNullable;

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
    HandlerAssociations handlerAssociations;

    public StatefulHandler(Class<?> targetClass,
                           HandlerMatcher<Object, DeserializingMessage> handlerMatcher,
                           HandlerRepository repository) {
        this(targetClass, handlerMatcher, repository, List.of(), e -> null);
    }

    public StatefulHandler(Class<?> targetClass,
                           HandlerMatcher<Object, DeserializingMessage> handlerMatcher,
                           HandlerRepository repository,
                           List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                           Function<Executable, ? extends java.lang.annotation.Annotation> methodAnnotationProvider) {
        this.targetClass = targetClass;
        this.handlerMatcher = handlerMatcher;
        this.repository = repository;
        this.parameterResolvers = parameterResolvers;
        this.methodAnnotationProvider = methodAnnotationProvider;
        this.handlerAssociations = new HandlerAssociations(targetClass, parameterResolvers, methodAnnotationProvider);
    }

    @Override
    public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
        if (!handlerMatcher.canHandle(message)) {
            return Optional.empty();
        }
        var matchingMethods = handlerMatcher.matchingMethods(message).toList();
        var alwaysMatch = matchingMethods.stream().anyMatch(handlerAssociations::alwaysAssociate);
        var alwaysInvoke = alwaysMatch && matchingMethods.stream().allMatch(ReflectionUtils::isStatic);
        var matches = alwaysInvoke ? List.<Entry<?>>of() : alwaysMatch ? repository.getAll()
                : repository.findByAssociation(handlerAssociations.associations(message, matchingMethods.stream()));
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

    @Override
    public String toString() {
        return "StatefulHandler[%s]".formatted(targetClass);
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
