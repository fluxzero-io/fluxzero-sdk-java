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
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.AnnotatedEntityHolder;
import io.fluxzero.sdk.modeling.DefaultEntityHelper;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.HasEntity;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.modeling.ImmutableEntity;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Tracker;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Slf4j
public class StatefulHandler implements Handler<DeserializingMessage> {
    Class<?> targetClass;
    HandlerMatcher<Object, DeserializingMessage> handlerMatcher;
    HandlerRepository repository;
    List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    Function<Executable, ? extends java.lang.annotation.Annotation> methodAnnotationProvider;
    HandlerAssociations handlerAssociations;
    BiFunction<Class<?>, List<ParameterResolver<? super DeserializingMessage>>, HandlerMatcher<Object, DeserializingMessage>>
            memberHandlerMatcherFactory;
    Serializer serializer;
    EntityHelper entityHelper;
    List<StatefulMember> statefulMembers;

    public StatefulHandler(Class<?> targetClass,
                           HandlerMatcher<Object, DeserializingMessage> handlerMatcher,
                           HandlerRepository repository,
                           List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                           Function<Executable, ? extends java.lang.annotation.Annotation> methodAnnotationProvider,
                           BiFunction<Class<?>, List<ParameterResolver<? super DeserializingMessage>>, HandlerMatcher<Object, DeserializingMessage>>
                                   memberHandlerMatcherFactory,
                           Serializer serializer) {
        this.targetClass = targetClass;
        this.handlerMatcher = handlerMatcher;
        this.repository = repository;
        this.parameterResolvers = parameterResolvers == null ? List.of() : List.copyOf(parameterResolvers);
        this.methodAnnotationProvider = methodAnnotationProvider == null ? e -> null : methodAnnotationProvider;
        this.handlerAssociations = new HandlerAssociations(targetClass, this.parameterResolvers,
                                                           this.methodAnnotationProvider);
        this.memberHandlerMatcherFactory = memberHandlerMatcherFactory;
        this.serializer = serializer == null ? defaultSerializer() : serializer;
        this.entityHelper = new DefaultEntityHelper(this.parameterResolvers, false);
        this.statefulMembers = memberHandlerMatcherFactory == null
                               ? List.of() : discoverStatefulMembers(targetClass, "", new HashSet<>());
    }

    @Override
    public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
        var matchingMethods = handlerMatcher.matchingMethods(message).toList();
        var memberCandidates = statefulMembers.stream()
                .map(member -> member.candidate(message))
                .filter(StatefulMemberCandidate::isRelevant)
                .toList();
        if (matchingMethods.isEmpty() && memberCandidates.isEmpty()) {
            return Optional.empty();
        }
        var alwaysMatch = matchingMethods.stream().anyMatch(handlerAssociations::alwaysAssociate);
        var alwaysInvoke = alwaysMatch && matchingMethods.stream().allMatch(ReflectionUtils::isStatic);
        var memberAlwaysMatch = memberCandidates.stream().anyMatch(StatefulMemberCandidate::alwaysAssociate);
        Map<Object, String> associations = handlerAssociations.associations(message, matchingMethods.stream());
        Map<Object, String> repositoryAssociations = new LinkedHashMap<>(associations);
        memberCandidates.forEach(candidate -> mergeAssociations(repositoryAssociations,
                                                                candidate.repositoryAssociations()));
        var matches = alwaysInvoke && !memberAlwaysMatch ? List.<Entry<?>>of()
                : alwaysMatch || memberAlwaysMatch ? repository.getAll()
                : repository.findByAssociation(repositoryAssociations);
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
            if (entry.getValue() == null) {
                continue;
            }
            StatefulEntryState state = new StatefulEntryState(entry);
            HandlerInvoker parentInvoker = null;
            if (alwaysMatch || matchesParent(entry.getValue(), associations)) {
                parentInvoker = handlerMatcher.getInvoker(entry.getValue(), message)
                        .filter(i -> alreadyFiltered(i) || canTrackerHandle(message, entry.getId()))
                        .orElse(null);
            }
            List<HandlerInvoker> initialInvokers = new ArrayList<>();
            ofNullable(parentInvoker).ifPresent(initialInvokers::add);
            for (StatefulMemberCandidate candidate : memberCandidates) {
                initialInvokers.addAll(candidate.member().getInvokers(state, message, candidate));
            }
            if (!initialInvokers.isEmpty()) {
                invokers.add(new StatefulEntryInvoker(initialInvokers.getFirst(), parentInvoker, state, message,
                                                      memberCandidates));
            }
        }
        return HandlerInvoker.join(invokers);
    }

    private boolean matchesParent(Object parent, Map<Object, String> associations) {
        return !associations.isEmpty()
               && (associations.values().stream().anyMatch(String::isBlank)
                   || handlerAssociations.matchesTarget(parent, associations));
    }

    private static Serializer defaultSerializer() {
        return Fluxzero.getOptionally().map(Fluxzero::serializer).orElseGet(JacksonSerializer::new);
    }

    private static void mergeAssociations(Map<Object, String> target, Map<Object, String> source) {
        source.forEach((key, value) -> target.merge(
                key, value, (first, second) -> first.isBlank() || second.isBlank() ? "" : first));
    }

    private List<StatefulMember> discoverStatefulMembers(Class<?> ownerType, String ownerPath,
                                                         Set<Class<?>> visitedTypes) {
        if (ownerType == null || Object.class.equals(ownerType) || !visitedTypes.add(ownerType)) {
            return List.of();
        }
        List<StatefulMember> result = new ArrayList<>();
        for (AccessibleObject location : ReflectionUtils.getAnnotatedProperties(ownerType, Member.class)) {
            Class<?> memberType = ReflectionUtils.getCollectionElementType(location)
                    .orElse(ReflectionUtils.getPropertyType(location));
            if (Object.class.equals(memberType)) {
                continue;
            }
            String propertyName = ReflectionUtils.getPropertyName(location);
            String memberPath = ownerPath.isBlank() ? propertyName : ownerPath + "/" + propertyName;
            List<ParameterResolver<? super DeserializingMessage>> memberResolvers =
                    memberParameterResolvers(ownerType, memberType);
            HandlerMatcher<Object, DeserializingMessage> memberMatcher =
                    memberHandlerMatcherFactory.apply(memberType, memberResolvers);
            HandlerAssociations memberAssociations =
                    new HandlerAssociations(memberType, memberResolvers, methodAnnotationProvider);
            AnnotatedEntityHolder holder =
                    AnnotatedEntityHolder.getEntityHolder(ownerType, location, entityHelper, serializer);
            boolean mapHolder = Map.class.isAssignableFrom(ReflectionUtils.getPropertyType(location));
            result.add(new StatefulMember(ownerType, memberPath, memberType, mapHolder, holder, memberMatcher,
                                          memberAssociations));
            result.addAll(discoverStatefulMembers(memberType, memberPath, new HashSet<>(visitedTypes)));
        }
        return List.copyOf(result);
    }

    private List<ParameterResolver<? super DeserializingMessage>> memberParameterResolvers(Class<?> ownerType,
                                                                                           Class<?> memberType) {
        List<ParameterResolver<? super DeserializingMessage>> result = new ArrayList<>(parameterResolvers);
        result.add(new StatefulMemberParameterResolver(List.of(targetClass, ownerType, memberType)));
        return List.copyOf(result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ImmutableEntity<?> rootEntity(Entry<?> entry) {
        Object value = entry.getValue();
        String idProperty = ReflectionUtils.getAnnotatedProperty(value.getClass(), EntityId.class)
                .map(ReflectionUtils::getPropertyName).orElse(null);
        return ImmutableEntity.builder()
                .id(computeId(value, entry, null))
                .type((Class) value.getClass())
                .value(value)
                .idProperty(idProperty)
                .entityHelper(entityHelper)
                .serializer(serializer)
                .build();
    }

    private Object computeId(Object handler, Entry<?> currentEntry, DeserializingMessage message) {
        return getAnnotatedPropertyValue(handler, EntityId.class)
                .or(() -> ofNullable(currentEntry).map(Entry::getId))
                .orElseGet(() -> message == null ? null : message.getMessageId());
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
            return StatefulHandler.this.computeId(handler, currentEntry, message);
        }
    }

    protected class StatefulEntryState {
        private final String originalId;
        private final Object originalValue;
        private final List<StatefulMemberMutation> memberMutations = new ArrayList<>();
        private String id;
        private Object value;
        private ImmutableEntity<?> root;
        private boolean deleted;

        StatefulEntryState(Entry<?> entry) {
            this.originalId = entry.getId();
            this.originalValue = entry.getValue();
            this.id = originalId;
            this.value = originalValue;
        }

        Entry<?> entry() {
            return new Entry<>() {
                @Override
                public String getId() {
                    return id;
                }

                @Override
                public Object getValue() {
                    return value;
                }
            };
        }

        ImmutableEntity<?> root() {
            if (root == null) {
                root = rootEntity(entry());
            }
            return root;
        }

        boolean isDeleted() {
            return deleted;
        }

        void store(Object newId, Object newValue) {
            store(newId, newValue, null);
        }

        void store(Object newId, Object newValue, Entity<?> newRoot) {
            String nextId = newId == null ? null : newId.toString();
            id = nextId;
            value = newValue;
            root = (ImmutableEntity<?>) newRoot;
            deleted = false;
        }

        @SneakyThrows
        void storeAdditional(Object newId, Object newValue) {
            String nextId = newId == null ? null : newId.toString();
            if (Objects.equals(id, nextId)) {
                store(newId, newValue);
            } else {
                repository.put(newId, newValue).get();
            }
        }

        void delete() {
            id = null;
            value = null;
            root = null;
            memberMutations.clear();
            deleted = true;
        }

        void stageMemberMutation(StatefulMember member, Entity<?> target, Entity<?> before, Entity<?> after) {
            memberMutations.add(new StatefulMemberMutation(member, target, before, after));
        }

        void flushMemberMutations(DeserializingMessage message) {
            while (!memberMutations.isEmpty()) {
                StatefulMemberMutation first = memberMutations.removeFirst();
                List<StatefulMemberMutation> group = new ArrayList<>();
                group.add(first);
                for (int i = 0; i < memberMutations.size();) {
                    StatefulMemberMutation mutation = memberMutations.get(i);
                    if (sameMemberOwner(first, mutation)) {
                        group.add(mutation);
                        memberMutations.remove(i);
                    } else {
                        i++;
                    }
                }
                applyMemberMutations(first.member(), first.target().parent(), group, message);
            }
        }

        private boolean sameMemberOwner(StatefulMemberMutation first, StatefulMemberMutation second) {
            return first.member() == second.member()
                   && first.member().sameParent(first.target().parent(), second.target().parent());
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void applyMemberMutations(StatefulMember member, Entity<?> previousOwner,
                                          List<StatefulMemberMutation> mutations,
                                          DeserializingMessage message) {
            Entity<?> owner = member.currentOwner(root(), previousOwner).orElse(previousOwner);
            if (owner == null || owner.get() == null) {
                return;
            }
            Object updatedOwner = member.holder.updateOwner(owner.get(), entityUpdates(mutations));
            Entity<?> updatedEntity = ((Entity) owner).update(ignored -> updatedOwner);
            Entity<?> updatedRoot = updatedEntity.root();
            Object updatedParent = updatedRoot.get();
            Object newId = computeId(updatedParent, entry(), message);
            store(newId, updatedParent, updatedRoot);
        }

        private List<AnnotatedEntityHolder.EntityUpdate> entityUpdates(List<StatefulMemberMutation> mutations) {
            List<AnnotatedEntityHolder.EntityUpdate> result = new ArrayList<>();
            Map<StatefulMemberTargetKey, Integer> indexes = new LinkedHashMap<>();
            for (StatefulMemberMutation mutation : mutations) {
                AnnotatedEntityHolder.EntityUpdate update =
                        new AnnotatedEntityHolder.EntityUpdate(mutation.before(), mutation.after());
                if (mutation.before().isPresent()) {
                    StatefulMemberTargetKey key = new StatefulMemberTargetKey(
                            mutation.before().type(), mutation.before().id(), mutation.before().get());
                    Integer index = indexes.get(key);
                    if (index != null) {
                        AnnotatedEntityHolder.EntityUpdate previous = result.get(index);
                        result.set(index, new AnnotatedEntityHolder.EntityUpdate(previous.before(), mutation.after()));
                        continue;
                    }
                    indexes.put(key, result.size());
                }
                result.add(update);
            }
            return result;
        }

        @SneakyThrows
        void flush() {
            flushMemberMutations(null);
            if (deleted) {
                if (originalId != null) {
                    repository.delete(originalId).get();
                }
                return;
            }
            if (!Objects.equals(originalValue, value) || !Objects.equals(originalId, id)) {
                repository.put(id, value).get();
            }
            if (id != null && originalId != null && !originalId.equals(id)) {
                repository.delete(originalId).get();
            }
        }
    }

    protected class StatefulEntryInvoker extends HandlerInvoker.DelegatingHandlerInvoker {
        private final HandlerInvoker parentInvoker;
        private final StatefulEntryState state;
        private final DeserializingMessage message;
        private final List<StatefulMemberCandidate> memberCandidates;

        StatefulEntryInvoker(HandlerInvoker metadataInvoker, HandlerInvoker parentInvoker, StatefulEntryState state,
                             DeserializingMessage message, List<StatefulMemberCandidate> memberCandidates) {
            super(metadataInvoker);
            this.parentInvoker = parentInvoker;
            this.state = state;
            this.message = message;
            this.memberCandidates = memberCandidates;
        }

        @Override
        public Object invoke(BiFunction<Object, Object, Object> combiner) {
            Object result = null;
            boolean invoked = false;
            if (parentInvoker != null) {
                result = parentInvoker.invoke(combiner);
                invoked = true;
                handleParentResult(result);
            }
            for (StatefulMemberCandidate candidate : memberCandidates) {
                if (state.isDeleted()) {
                    break;
                }
                for (HandlerInvoker memberInvoker : candidate.member().getInvokers(state, message, candidate)) {
                    Object memberResult = memberInvoker.invoke(combiner);
                    result = invoked ? combiner.apply(result, memberResult) : memberResult;
                    invoked = true;
                }
                state.flushMemberMutations(message);
            }
            state.flush();
            return result;
        }

        @SneakyThrows
        private void handleParentResult(Object result) {
            if (result instanceof Collection<?> collection) {
                if (collection.isEmpty()) {
                    state.delete();
                } else {
                    String currentId = state.entry().getId();
                    var newIds = collection.stream().map(this::tryStoreAdditionalParentResult)
                            .filter(Objects::nonNull)
                            .map(Object::toString).toList();
                    if (new HashSet<>(newIds).size() != newIds.size()) {
                        log.warn("Duplicate IDs returned from stateful handler {}#{}: {}."
                                 + " Please ensure that each handler has a unique property marked with @EntityId!",
                                 getTargetClass(), getMethod(), newIds);
                    }
                    if (!newIds.isEmpty() && currentId != null && !newIds.contains(currentId)) {
                        state.delete();
                    }
                }
            } else if (result == null) {
                if (expectResult() && getMethod() instanceof Method method
                    && (getTargetClass().isAssignableFrom(method.getReturnType())
                        || method.getReturnType().isAssignableFrom(getTargetClass()))) {
                    state.delete();
                }
            } else {
                tryStoreParentResult(result);
            }
        }

        private Object tryStoreAdditionalParentResult(Object result) {
            if (!getTargetClass().isInstance(result)) {
                return null;
            }
            Object id = computeId(result, state.entry(), message);
            state.storeAdditional(id, result);
            return id;
        }

        private Object tryStoreParentResult(Object result) {
            if (!getTargetClass().isInstance(result)) {
                return null;
            }
            Object id = computeId(result, state.entry(), message);
            state.store(id, result);
            return id;
        }
    }

    protected record StatefulMemberCandidate(StatefulMember member,
                                             List<Executable> matchingMethods,
                                             Map<Object, String> associations,
                                             Map<Object, String> repositoryAssociations,
                                             boolean alwaysAssociate) {
        boolean isRelevant() {
            return alwaysAssociate || !matchingMethods.isEmpty() || !associations.isEmpty();
        }
    }

    protected record StatefulMemberMutation(StatefulMember member, Entity<?> target,
                                            Entity<?> before, Entity<?> after) {
    }

    protected record StatefulMemberTargetKey(Class<?> type, Object id, Object value) {
    }

    protected class StatefulMember {
        private final Class<?> ownerType;
        private final String path;
        private final Class<?> type;
        private final boolean mapHolder;
        private final AnnotatedEntityHolder holder;
        private final HandlerMatcher<Object, DeserializingMessage> matcher;
        private final HandlerAssociations associations;

        StatefulMember(Class<?> ownerType, String path, Class<?> type, boolean mapHolder, AnnotatedEntityHolder holder,
                       HandlerMatcher<Object, DeserializingMessage> matcher, HandlerAssociations associations) {
            this.ownerType = ownerType;
            this.path = path;
            this.type = type;
            this.mapHolder = mapHolder;
            this.holder = holder;
            this.matcher = matcher;
            this.associations = associations;
        }

        StatefulMemberCandidate candidate(DeserializingMessage message) {
            List<Executable> matchingMethods = matcher.matchingMethods(message).toList();
            Map<Object, String> targetAssociations = associations.associations(message, matchingMethods.stream());
            Map<Object, String> repositoryAssociations = new LinkedHashMap<>();
            targetAssociations.forEach((value, targetPath) ->
                                               repositoryAssociations.put(value, repositoryPath(targetPath)));
            boolean alwaysAssociate = matchingMethods.stream().anyMatch(associations::alwaysAssociate);
            return new StatefulMemberCandidate(this, matchingMethods, targetAssociations, repositoryAssociations,
                                               alwaysAssociate);
        }

        private String repositoryPath(String targetPath) {
            if (targetPath == null || targetPath.isBlank()) {
                return targetPath;
            }
            return mapHolder ? "" : path + "/" + targetPath;
        }

        List<HandlerInvoker> getInvokers(StatefulEntryState state, DeserializingMessage message,
                                         StatefulMemberCandidate candidate) {
            List<HandlerInvoker> result = new ArrayList<>();
            List<Entity<?>> targets = matchingTargets(state.root(), candidate);
            for (Entity<?> target : targets) {
                StatefulMemberMessage memberMessage = new StatefulMemberMessage(message, target);
                matcher.getInvoker(target.get(), memberMessage)
                        .filter(i -> alreadyFiltered(i) || canTrackerHandle(message, state.entry().getId()))
                        .map(i -> new StatefulMemberInvoker(i, state, memberMessage, target, this))
                        .ifPresent(result::add);
            }
            if (targets.isEmpty()) {
                for (Entity<?> target : emptyTargets(state.root())) {
                    StatefulMemberMessage memberMessage = new StatefulMemberMessage(message, target);
                    matcher.getInvoker(null, memberMessage)
                            .filter(i -> alreadyFiltered(i) || canTrackerHandle(message, state.entry().getId()))
                            .map(i -> new StatefulMemberInvoker(i, state, memberMessage, target, this))
                            .ifPresent(result::add);
                }
            }
            return result;
        }

        private List<Entity<?>> matchingTargets(Entity<?> root, StatefulMemberCandidate candidate) {
            List<Entity<?>> result = new ArrayList<>();
            for (Entity<?> owner : owners(root)) {
                for (Entity<?> entity : holder.getEntities(owner)) {
                    if (entity.isPresent()
                        && (candidate.alwaysAssociate()
                            || associations.matchesTarget(entity.get(), candidate.associations()))) {
                        result.add(entity);
                    }
                }
            }
            return result;
        }

        private List<Entity<?>> emptyTargets(Entity<?> root) {
            List<Entity<?>> result = new ArrayList<>();
            for (Entity<?> owner : owners(root)) {
                for (Entity<?> entity : holder.getEntities(owner)) {
                    if (entity.isEmpty()) {
                        result.add(entity);
                    }
                }
            }
            return result;
        }

        private List<Entity<?>> owners(Entity<?> root) {
            return root.allEntities()
                    .filter(Entity::isPresent)
                    .filter(entity -> ownerType.isAssignableFrom(entity.type()))
                    .toList();
        }

        Optional<Entity<?>> currentOwner(Entity<?> root, Entity<?> previousOwner) {
            if (previousOwner == null) {
                return Optional.empty();
            }
            for (Entity<?> owner : owners(root)) {
                if (sameTarget(owner, previousOwner)) {
                    return Optional.of(owner);
                }
            }
            return Optional.empty();
        }

        private boolean sameTarget(Entity<?> first, Entity<?> second) {
            if (first.isEmpty() || second.isEmpty()) {
                return first.isEmpty() && second.isEmpty() && sameParent(first.parent(), second.parent());
            }
            if (first.id() != null && second.id() != null) {
                return Objects.equals(first.id(), second.id());
            }
            return Objects.equals(first.get(), second.get());
        }

        private boolean sameParent(Entity<?> first, Entity<?> second) {
            if (first == null || second == null) {
                return first == second;
            }
            return sameTarget(first, second);
        }

    }

    protected class StatefulMemberInvoker extends HandlerInvoker.DelegatingHandlerInvoker {
        private final StatefulEntryState state;
        private final StatefulMemberMessage message;
        private final Entity<?> target;
        private final StatefulMember member;

        StatefulMemberInvoker(HandlerInvoker delegate, StatefulEntryState state, StatefulMemberMessage message,
                              Entity<?> target, StatefulMember member) {
            super(delegate);
            this.state = state;
            this.message = message;
            this.target = target;
            this.member = member;
        }

        @Override
        public Object invoke(BiFunction<Object, Object, Object> combiner) {
            Object result = delegate.invoke(combiner);
            handleResult(result);
            return result;
        }

        @SneakyThrows
        private void handleResult(Object result) {
            if (result instanceof Collection<?> collection) {
                List<?> values = collection.stream().filter(member.type::isInstance).toList();
                if (values.isEmpty()) {
                    deleteTarget();
                } else {
                    Entity<?> addTarget = target;
                    if (target.isPresent()) {
                        deleteTarget();
                        addTarget = emptyTarget(target);
                    }
                    Entity<?> finalAddTarget = addTarget;
                    values.forEach(value -> storeTarget(finalAddTarget, value));
                }
            } else if (result == null) {
                if (shouldDeleteTarget()) {
                    deleteTarget();
                }
            } else if (member.type.isInstance(result)) {
                storeTarget(result);
            }
        }

        private boolean shouldDeleteTarget() {
            return target.isPresent() && expectResult() && getMethod() instanceof Method method
                   && (target.type().isAssignableFrom(method.getReturnType())
                       || method.getReturnType().isAssignableFrom(target.type()));
        }

        private void deleteTarget() {
            if (target.isPresent()) {
                storeTarget(null);
            }
        }

        @SneakyThrows
        private void storeTarget(Object value) {
            storeTarget(target, value);
        }

        @SneakyThrows
        private void storeTarget(Entity<?> target, Object value) {
            ImmutableEntity before = (ImmutableEntity) target;
            ImmutableEntity after = (ImmutableEntity) before.toBuilder()
                    .value(value)
                    .id(computeMemberId(target, value))
                    .build();
            state.stageMemberMutation(member, target, before, after);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Entity<?> emptyTarget(Entity<?> target) {
            return (Entity<?>) ((ImmutableEntity) target).toBuilder().id(null).value(null).build();
        }

        private Object computeMemberId(Entity<?> target, Object value) {
            return getAnnotatedPropertyValue(value, EntityId.class).orElse(target.id());
        }
    }

    protected static class StatefulMemberMessage extends DeserializingMessage implements HasEntity {
        private final Entity<?> entity;

        StatefulMemberMessage(DeserializingMessage message, Entity<?> entity) {
            super(message);
            this.entity = entity;
        }

        @Override
        public Entity<?> getEntity() {
            return entity;
        }
    }

    protected static class StatefulMemberParameterResolver implements ParameterResolver<DeserializingMessage> {
        private final List<Class<?>> contextTypes;

        StatefulMemberParameterResolver(List<Class<?>> contextTypes) {
            this.contextTypes = contextTypes.stream().distinct().toList();
        }

        @Override
        public Function<DeserializingMessage, Object> resolve(Parameter parameter,
                                                              java.lang.annotation.Annotation methodAnnotation) {
            return message -> {
                if (message instanceof StatefulMemberMessage memberMessage) {
                    Entity<?> entity = findEntity(parameter, memberMessage.getEntity());
                    if (entity != null) {
                        return Entity.class.isAssignableFrom(parameter.getType()) ? entity : entity.get();
                    }
                }
                return null;
            };
        }

        @Override
        public boolean matches(Parameter parameter, java.lang.annotation.Annotation methodAnnotation,
                               DeserializingMessage value) {
            if (value instanceof StatefulMemberMessage memberMessage) {
                return findEntity(parameter, memberMessage.getEntity()) != null;
            }
            Class<?> parameterType = entityParameterType(parameter);
            return contextTypes.stream().anyMatch(type -> parameterType.isAssignableFrom(type)
                                                          || type.isAssignableFrom(parameterType));
        }

        @Override
        public boolean mayApply(Executable method, Class<?> targetClass) {
            for (Parameter parameter : method.getParameters()) {
                if (matches(parameter, null, null)) {
                    return true;
                }
            }
            return false;
        }

        private Entity<?> findEntity(Parameter parameter, Entity<?> entity) {
            for (Entity<?> candidate = entity; candidate != null; candidate = candidate.parent()) {
                if (matchesEntity(parameter, candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private boolean matchesEntity(Parameter parameter, Entity<?> entity) {
            Class<?> entityType = entity.type();
            Class<?> parameterType = entityParameterType(parameter);
            if (entityType == null) {
                return false;
            }
            if (entity.get() != null && !Entity.class.isAssignableFrom(parameter.getType())) {
                return parameterType.isAssignableFrom(entity.get().getClass());
            }
            return parameterType.isAssignableFrom(entityType) || entityType.isAssignableFrom(parameterType);
        }

        private Class<?> entityParameterType(Parameter parameter) {
            if (Entity.class.equals(parameter.getType())) {
                Type parameterizedType = parameter.getParameterizedType();
                if (parameterizedType instanceof ParameterizedType type) {
                    Type actualType = type.getActualTypeArguments()[0];
                    if (actualType instanceof Class<?>) {
                        return (Class<?>) actualType;
                    }
                    if (actualType instanceof WildcardType wildcardType) {
                        Type[] lowerBounds = wildcardType.getLowerBounds();
                        Type[] upperBounds = wildcardType.getUpperBounds();
                        Type bound = lowerBounds.length > 0 ? lowerBounds[0]
                                : upperBounds.length > 0 ? upperBounds[0] : Object.class;
                        if (bound instanceof Class<?>) {
                            return (Class<?>) bound;
                        }
                        if (bound instanceof ParameterizedType parameterizedBound
                            && parameterizedBound.getRawType() instanceof Class<?> rawType) {
                            return rawType;
                        }
                    }
                }
                return Object.class;
            }
            return parameter.getType();
        }
    }
}
