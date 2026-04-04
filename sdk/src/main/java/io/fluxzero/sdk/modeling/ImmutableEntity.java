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

package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.NonFinal;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotationAs;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedPropertyValue;
import static io.fluxzero.common.reflection.ReflectionUtils.getValue;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.mapProperty;
import static io.fluxzero.sdk.modeling.AnnotatedEntityHolder.getEntityHolder;
import static java.util.Collections.emptyList;


/**
 * Immutable implementation of the {@link Entity} interface, representing a snapshot of a domain entity.
 * <p>
 * Unlike mutable entities, an {@code ImmutableEntity} is never changed in-place. Instead, updates such as event
 * applications or transformations return a new, updated instance, preserving immutability and making it suitable for
 * event-sourced state transitions, testing, and functional-style programming models.
 * <p>
 * This entity is typically wrapped by higher-level mutable structures such as {@code ModifiableAggregateRoot}, which
 * manage lifecycle and mutation tracking.
 *
 * <h2>Key Features</h2>
 * <ul>
 *     <li>Supports event application via methods annotated with {@code @Apply}</li>
 *     <li>Supports recursive validation using {@code @AssertLegal}</li>
 *     <li>Automatically resolves child entities and aliases using {@code @Member} and {@code @Alias} annotations</li>
 *     <li>Preserves immutability by returning a new instance after each update</li>
 * </ul>
 *
 * <h2>Common Usage</h2>
 * <pre>{@code
 * ImmutableEntity<Order> orderEntity = ImmutableEntity.<Order>builder()
 *     .id("order123")
 *     .type(Order.class)
 *     .value(order)
 *     .entityHelper(entityHelper)
 *     .serializer(serializer)
 *     .build();
 *
 * // Apply an event and receive a new version of the entity
 * orderEntity = orderEntity.apply(eventMessage);
 * }</pre>
 *
 * <p>
 * The {@link #update(UnaryOperator)} method applies a transformation to the entity value and automatically
 * updates parent references when nested.
 * <p>
 * Event application is handled via the {@link EntityHelper} which dynamically locates appropriate handlers.
 * If no direct handler is found, the event is propagated recursively to nested child entities.
 *
 * @param <T> the type of the underlying domain object represented by this entity
 * @see Entity
 * @see Apply
 * @see Alias
 * @see Member
 */
@Value
@NonFinal
@SuperBuilder(toBuilder = true)
@Accessors(fluent = true)
@Slf4j
public class ImmutableEntity<T> implements Entity<T> {
    private static final ThreadLocal<Map<RouteCacheKey, String>> loadingRouteCache = ThreadLocal.withInitial(HashMap::new);
    private static final Map<RoutingKeyOverlapCacheKey, Boolean> routingKeyOverlapsCurrentIdCache =
            new ConcurrentHashMap<>();
    @JsonProperty
    Object id;
    @JsonProperty
    Class<T> type;
    @ToString.Exclude
    @JsonProperty
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
    @Getter(AccessLevel.PROTECTED)
    T value;
    @JsonProperty
    String idProperty;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    @NonFinal
    transient Entity<?> parent;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    transient AnnotatedEntityHolder holder;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    transient EntityHelper entityHelper;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    transient Serializer serializer;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Getter(lazy = true)
    Collection<? extends Entity<?>> entities = computeEntities();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Getter(lazy = true)
    Collection<?> aliases = computeAliases();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Getter(lazy = true)
    @JsonIgnore
    DescendantTargetMetadata descendantTargetMetadata = computeDescendantTargetMetadata();

    @SuppressWarnings("unchecked")
    public Class<T> type() {
        T value = get();
        return value == null ? type : (Class<T>) value.getClass();
    }

    @Override
    public Entity<T> withType(Class<T> type) {
        if (!type().isAssignableFrom(type)) {
            throw new IllegalArgumentException("Given type is not assignable to entity type");
        }
        return toBuilder().type(type).build();
    }

    @Override
    public T get() {
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Entity<T> update(UnaryOperator<T> function) {
        ImmutableEntity<T> after = toBuilder().value(function.apply(get())).build();
        return parent == null ? after : (Entity<T>) parent.update(
                (UnaryOperator) p -> holder.updateOwner(p, this, after)).getEntity(id()).orElse(null);
    }

    @Override
    public Entity<T> apply(Message message) {
        return apply(new DeserializingMessage(message, EVENT, null, serializer));
    }

    @Override
    public Entity<T> commit() {
        return this;
    }

    ImmutableEntity<T> replayParent(Entity<?> parent) {
        this.parent = parent;
        return this;
    }

    @Override
    public <E extends Exception> Entity<T> assertLegal(Object update) throws E {
        entityHelper.assertLegal(update, root());
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Entity<T> apply(DeserializingMessage message) {
        Optional<HandlerInvoker> directInvoker = entityHelper.applyInvoker(message, this);
        if (directInvoker.isPresent() && explicitlyTargetsCurrent(message.getPayload())) {
            T updatedValue = (T) directInvoker.get().invoke();
            Entity<T> selfMemberAddition = updatedValue == get() ? null : applyAsSelfMemberAddition(updatedValue);
            if (selfMemberAddition != null) {
                return selfMemberAddition;
            }
            return updatedValue == get() ? this : toBuilder().value(updatedValue).build();
        }
        ImmutableEntity<T> result = this;
        for (Entity<?> entity : result.resolvePossibleTargets(message.getPayload())) {
            ImmutableEntity<?> immutableEntity = (ImmutableEntity<?>) entity;
            Entity<?> updated = immutableEntity.apply(message);
            if (updated != immutableEntity) {
                result = result.toBuilder().value((T) immutableEntity
                        .holder().updateOwner(result.get(), entity, updated)).build();
            }
        }
        boolean explicitlyTargetsOther = directInvoker.isPresent() && result == this
                && explicitTarget(message.getPayload()) == ExplicitTarget.OTHER;
        Optional<HandlerInvoker> invoker = directInvoker.isPresent() && result == this && !explicitlyTargetsOther ? directInvoker
                : entityHelper.applyInvoker(message, result);
        if (invoker.isPresent()) {
            T updatedValue = (T) invoker.get().invoke();
            Entity<T> selfMemberAddition = updatedValue == result.get() ? null : result.applyAsSelfMemberAddition(updatedValue);
            if (selfMemberAddition != null) {
                return selfMemberAddition;
            }
            return updatedValue == result.get() ? result : result.toBuilder().value(updatedValue).build();
        }
        if (result == this && !Entity.isLoading()
            && getBooleanProperty("fluxzero.assert.apply-compatibility", true)) {
            assertApplyCompatibility(message, this);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Entity<T> applyAsSelfMemberAddition(T updatedValue) {
        if (updatedValue == null || get() == null || type() == null
            || !Entity.selfReferentialMemberCache.get(type())) {
            return null;
        }
        Object updatedId = getAnnotatedPropertyValue(updatedValue, EntityId.class).orElse(null);
        if (updatedId == null || Objects.equals(updatedId, id())) {
            return null;
        }
        for (AccessibleObject location : getAnnotatedProperties(type(), Member.class)) {
            AnnotatedEntityHolder entityHolder = getEntityHolder(type(), location, entityHelper, serializer);
            ImmutableEntity<?> emptyChild = entityHolder.getEmptyEntity();
            if (!Objects.equals(type(), emptyChild.type())) {
                continue;
            }
            ImmutableEntity<?> before = emptyChild.toBuilder().parent(this).build();
            ImmutableEntity<?> after = ImmutableEntity.builder().id(updatedId).type((Class<Object>) updatedValue.getClass())
                    .value(updatedValue).idProperty(before.idProperty()).parent(this).holder(entityHolder)
                    .entityHelper(entityHelper).serializer(serializer).build();
            return toBuilder().value((T) entityHolder.updateOwner(get(), before, after)).build();
        }
        return null;
    }

    private boolean explicitlyTargetsCurrent(Object payload) {
        ExplicitTarget explicitTarget = explicitTarget(payload);
        if (explicitTarget != ExplicitTarget.UNKNOWN) {
            return explicitTarget == ExplicitTarget.CURRENT;
        }
        if (getAnnotatedProperties(type(), Member.class).isEmpty()) {
            return true;
        }
        Object routingKey = getRoutingKey(payload);
        if (routingKey != null) {
            String routingKeyValue = routingKey.toString();
            return matchesCurrentRoute(routingKeyValue) && !hasAmbiguousDescendantTarget(payload);
        }
        if (id() == null || idProperty() == null) {
            return false;
        }
        if (mentionsDistinctDescendantProperty(payload)) {
            return false;
        }
        return ReflectionUtils.readProperty(idProperty(), payload).map(id()::equals).orElse(false);
    }

    private ExplicitTarget explicitTarget(Object payload) {
        Object routingKey = getRoutingKey(payload);
        if (routingKey != null) {
            String routingKeyValue = routingKey.toString();
            if (matchesCurrentRoute(routingKeyValue)) {
                return hasAmbiguousDescendantTarget(payload) ? ExplicitTarget.UNKNOWN : ExplicitTarget.CURRENT;
            }
            return ExplicitTarget.OTHER;
        }
        if (id() != null && idProperty() != null && ReflectionUtils.hasProperty(idProperty(), payload)) {
            return ReflectionUtils.readProperty(idProperty(), payload)
                    .map(candidate -> Objects.equals(id(), candidate)
                            ? (mentionsDistinctDescendantProperty(payload) ? ExplicitTarget.UNKNOWN : ExplicitTarget.CURRENT)
                            : ExplicitTarget.OTHER)
                    .orElse(ExplicitTarget.UNKNOWN);
        }
        DescendantTargetMetadata targetMetadata = descendantTargetMetadata();
        if (targetMetadata.certain()) {
            for (String property : targetMetadata.idProperties()) {
                if (ReflectionUtils.hasProperty(property, payload)) {
                    return ExplicitTarget.OTHER;
                }
            }
            return ExplicitTarget.CURRENT;
        }
        return ExplicitTarget.UNKNOWN;
    }

    private boolean mentionsDistinctDescendantProperty(Object payload) {
        DescendantTargetMetadata targetMetadata = descendantTargetMetadata();
        if (payload == null || !targetMetadata.certain()) {
            return false;
        }
        for (String property : targetMetadata.idProperties()) {
            if (!Objects.equals(property, idProperty()) && ReflectionUtils.hasProperty(property, payload)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAmbiguousDescendantTarget(Object payload) {
        return routingKeyOverlapsCurrentId(payload) && mentionsDistinctDescendantProperty(payload);
    }

    private boolean routingKeyOverlapsCurrentId(Object payload) {
        Class<?> entityType = type();
        if (payload == null || entityType == null || idProperty() == null) {
            return false;
        }
        return routingKeyOverlapsCurrentIdCache.computeIfAbsent(new RoutingKeyOverlapCacheKey(entityType,
                                                                                              payload.getClass()),
                                                                ignored -> ReflectionUtils.getAnnotatedPropertyName(
                                                                                payload,
                                                                                io.fluxzero.sdk.publishing.routing.RoutingKey.class)
                                                                        .map(idProperty()::equals)
                                                                        .orElseGet(() -> ReflectionUtils.hasProperty(
                                                                                idProperty(),
                                                                                payload)));
    }

    private Iterable<Entity<?>> resolvePossibleTargets(Object payload) {
        Entity<?> directTarget = resolveDirectTarget(payload);
        if (directTarget != null) {
            return List.of(directTarget);
        }
        if (!Entity.isLoading() || !isRoot() || explicitlyTargetsCurrent(payload)) {
            return possibleTargets(payload);
        }
        RouteCacheKey cacheKey = routeCacheKey(payload);
        if (cacheKey == null) {
            return possibleTargets(payload);
        }
        String cached = loadingRouteCache.get().get(cacheKey);
        if (cached != null) {
            return cachedTargets(cached, payload);
        }
        Iterable<Entity<?>> resolved = possibleTargets(payload);
        cacheResolvedTargets(cacheKey, resolved);
        return resolved;
    }

    private Entity<?> resolveDirectTarget(Object payload) {
        Object routingKey = getRoutingKey(payload);
        if (routingKey != null) {
            return resolveDirectTarget(routingKey.toString());
        }
        for (String routeValue : routeCandidates(payload)) {
            Entity<?> target = resolveDirectTarget(routeValue);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private Entity<?> resolveDirectTarget(String routeValue) {
        Class<?> type = type();
        for (AccessibleObject location : getAnnotatedProperties(type, Member.class)) {
            Entity<?> entity = getEntityHolder(type, location, entityHelper, serializer).getEntityByRoute(this, routeValue);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private Object getRoutingKey(Object payload) {
        return getAnnotatedPropertyValue(payload, io.fluxzero.sdk.publishing.routing.RoutingKey.class).orElse(null);
    }

    private List<String> routeCandidates(Object payload) {
        LinkedHashSet<String> results = new LinkedHashSet<>();
        Optional.ofNullable(getRoutingKey(payload)).map(Object::toString).ifPresent(results::add);
        DescendantTargetMetadata targetMetadata = descendantTargetMetadata();
        for (String property : targetMetadata.idProperties()) {
            ReflectionUtils.readProperty(property, payload).map(Object::toString).ifPresent(results::add);
        }
        return List.copyOf(results);
    }

    private Iterable<Entity<?>> cachedTargets(String cached, Object payload) {
        Entity<?> directTarget = resolveDirectTarget(cached);
        if (directTarget != null) {
            return List.of(directTarget);
        }
        Iterable<Entity<?>> resolved = possibleTargets(payload);
        RouteCacheKey cacheKey = routeCacheKey(payload);
        if (cacheKey != null) {
            cacheResolvedTargets(cacheKey, resolved);
        }
        return resolved;
    }

    private void cacheResolvedTargets(RouteCacheKey cacheKey, Iterable<Entity<?>> resolvedTargets) {
        List<Entity<?>> resolved = new ArrayList<>();
        resolvedTargets.forEach(resolved::add);
        if (resolved.isEmpty()) {
            loadingRouteCache.get().remove(cacheKey);
            return;
        }
        Entity<?> first = resolved.getFirst();
        if (explicitlyTargets(first, cacheKey.routeValue(), cacheKey.payloadType())) {
            loadingRouteCache.get().remove(cacheKey);
            return;
        }
        String directChildKey = first.id() == null ? null : first.id().toString();
        if (directChildKey == null) {
            for (Object alias : first.aliases()) {
                if (alias != null) {
                    directChildKey = alias.toString();
                    break;
                }
            }
        }
        if (directChildKey == null) {
            loadingRouteCache.get().remove(cacheKey);
        } else {
            loadingRouteCache.get().put(cacheKey, directChildKey);
        }
    }

    private RouteCacheKey routeCacheKey(Object payload) {
        Object routingKey = getRoutingKey(payload);
        String routeValue = Optional.ofNullable(routingKey).map(Object::toString)
                .or(() -> idProperty() == null ? Optional.empty()
                        : ReflectionUtils.readProperty(idProperty(), payload).map(Object::toString))
                .orElse(null);
        if (routeValue == null) {
            return null;
        }
        return new RouteCacheKey(type(), id() == null ? null : id().toString(), payload.getClass(), routeValue);
    }

    private DescendantTargetMetadata computeDescendantTargetMetadata() {
        return computeDescendantTargetMetadata(type(), new HashSet<>());
    }

    private DescendantTargetMetadata computeDescendantTargetMetadata(Class<?> ownerType, Set<Class<?>> visitedTypes) {
        if (ownerType == null || !visitedTypes.add(ownerType)) {
            return DescendantTargetMetadata.CERTAIN_EMPTY;
        }
        Set<String> properties = new LinkedHashSet<>();
        boolean certain = true;
        for (AccessibleObject location : getAnnotatedProperties(ownerType, Member.class)) {
            AnnotatedEntityHolder holder = getEntityHolder(ownerType, location, entityHelper, serializer);
            ImmutableEntity<?> emptyChild = holder.getEmptyEntity();
            Class<?> childType = emptyChild.type();
            if (childType == null || Object.class.equals(childType)) {
                certain = false;
                continue;
            }
            if (emptyChild.idProperty() != null) {
                properties.add(emptyChild.idProperty());
            }
            if (!childType.equals(ownerType)) {
                DescendantTargetMetadata childMetadata = computeDescendantTargetMetadata(childType, new HashSet<>(visitedTypes));
                properties.addAll(childMetadata.idProperties());
                certain &= childMetadata.certain();
            }
        }
        return new DescendantTargetMetadata(properties, certain);
    }

    private boolean explicitlyTargets(Entity<?> entity, String routeValue, Class<?> payloadType) {
        if (matchesRoute(entity, routeValue)) {
            return true;
        }
        String childIdProperty = entity.idProperty();
        return childIdProperty != null && ReflectionUtils.hasProperty(childIdProperty, payloadType);
    }

    private boolean matchesRoute(Entity<?> entity, String routeValue) {
        if (entity.id() != null && routeValue.equals(entity.id().toString())) {
            return true;
        }
        for (Object alias : entity.aliases()) {
            if (alias != null && routeValue.equals(alias.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAliases(String routeValue) {
        for (Object alias : aliases()) {
            if (alias != null && routeValue.equals(alias.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCurrentRoute(String routeValue) {
        return (id() != null && routeValue.equals(id().toString())) || matchesAliases(routeValue);
    }

    record RouteCacheKey(Class<?> entityType, String entityId, Class<?> payloadType, String routeValue) {
    }

    private record RoutingKeyOverlapCacheKey(Class<?> entityType, Class<?> payloadType) {
    }

    private enum ExplicitTarget {
        CURRENT,
        OTHER,
        UNKNOWN
    }

    private record DescendantTargetMetadata(Set<String> idProperties, boolean certain) {
        private static final DescendantTargetMetadata CERTAIN_EMPTY = new DescendantTargetMetadata(Set.of(), true);
    }

    public static Map<?, String> snapshotLoadingRouteCache() {
        return new HashMap<>(loadingRouteCache.get());
    }

    @SuppressWarnings("unchecked")
    public static void restoreLoadingRouteCache(Map<?, String> snapshot) {
        loadingRouteCache.get().clear();
        loadingRouteCache.get().putAll((Map<RouteCacheKey, String>) snapshot);
    }

    public static void clearLoadingRouteCache() {
        loadingRouteCache.get().clear();
    }

    protected <E extends Exception> void assertApplyCompatibility(DeserializingMessage message, Entity<?> entity) throws E {
        assertApplyCompatibility(message, entity, new HashSet<>());
    }

    protected <E extends Exception> void assertApplyCompatibility(DeserializingMessage message, Entity<?> entity,
                                                                  Set<Class<?>> visitedTypes) throws E {
        entityHelper.applyInvoker(message, entity, false, false)
                .ifPresent(i -> {
                    Apply apply = ReflectionUtils.getAnnotation(i.getMethod(), Apply.class).orElseThrow();
                    if (!apply.disableCompatibilityCheck()) {
                        Object targetId = expectedTargetId(message, entity);
                        if (entity.isPresent()) {
                            log.warn("@Apply method {}#{} expected {} (id = '{}') to be empty",
                                     message.getPayloadClass().getSimpleName(), i.getMethod().getName(),
                                     entity.type().getSimpleName(), targetId);
                            throw mapProperty("fluxzero.assert.apply-compatibility.exception.already-exists",
                                              IllegalCommandException::new, () -> Entity.ALREADY_EXISTS_EXCEPTION);
                        } else {
                            log.warn("@Apply method {}#{} expected {} (id = '{}') to exist",
                                     message.getPayloadClass().getSimpleName(), i.getMethod().getName(),
                                     entity.type().getSimpleName(), targetId);
                            throw mapProperty("fluxzero.assert.apply-compatibility.exception.not-found",
                                              IllegalCommandException::new, () -> Entity.NOT_FOUND_EXCEPTION);
                        }
                    }
                });
        if (entity.type() == null) {
            return;
        }
        if (!entity.isEmpty()) {
            if (explicitlyTargetsOther(message.getPayload(), entity.type())) {
                assertApplyCompatibilityOnSelfReferentialChildren(message, entity, visitedTypes);
            } else if (entity == this) {
                assertApplyCompatibilityOnSelfReferentialDescendants(message, visitedTypes);
            }
            return;
        }
        if (!visitedTypes.add(entity.type())) {
            return;
        }
        for (AccessibleObject location : getAnnotatedProperties(entity.type(), Member.class)) {
            ImmutableEntity<?> child = getEntityHolder(entity.type(), location, entityHelper, serializer)
                    .getEmptyEntity().toBuilder().parent(entity).build();
            assertApplyCompatibility(message, child, visitedTypes);
        }
    }

    private <E extends Exception> void assertApplyCompatibilityOnSelfReferentialChildren(
            DeserializingMessage message, Entity<?> entity, Set<Class<?>> visitedTypes) throws E {
        for (AccessibleObject location : getAnnotatedProperties(entity.type(), Member.class)) {
            AnnotatedEntityHolder childHolder = getEntityHolder(entity.type(), location, entityHelper, serializer);
            ImmutableEntity<?> emptyChild = childHolder.getEmptyEntity().toBuilder().parent(entity).build();
            assertApplyCompatibility(message, emptyChild, new HashSet<>(visitedTypes));
        }
    }

    private <E extends Exception> void assertApplyCompatibilityOnSelfReferentialDescendants(
            DeserializingMessage message, Set<Class<?>> visitedTypes) throws E {
        for (Entity<?> descendant : allEntities().toList()) {
            if (descendant == this || descendant.isEmpty() || descendant.type() == null) {
                continue;
            }
            if (explicitlyTargetsOther(message.getPayload(), descendant.type())) {
                assertApplyCompatibility(message, descendant, new HashSet<>(visitedTypes));
            }
        }
    }

    private boolean explicitlyTargetsOther(Object payload, Class<?> entityType) {
        if (explicitTarget(payload) != ExplicitTarget.OTHER || entityType == null) {
            return false;
        }
        for (AccessibleObject location : getAnnotatedProperties(entityType, Member.class)) {
            AnnotatedEntityHolder childHolder = getEntityHolder(entityType, location, entityHelper, serializer);
            if (Objects.equals(entityType, childHolder.getEmptyEntity().type())) {
                return true;
            }
        }
        return false;
    }

    private Object expectedTargetId(DeserializingMessage message, Entity<?> entity) {
        if (entity.id() != null) {
            return entity.id();
        }
        Object payload = message.getPayload();
        if (payload == null) {
            return null;
        }
        if (entity.idProperty() != null) {
            Object payloadId = ReflectionUtils.readProperty(entity.idProperty(), payload).orElse(null);
            if (payloadId != null) {
                return payloadId;
            }
        }
        Object routingKey = getRoutingKey(payload);
        if (routingKey != null) {
            return routingKey;
        }
        return routeCandidates(payload).stream().findFirst().orElse(null);
    }

    protected Collection<? extends ImmutableEntity<?>> computeEntities() {
        Class<?> type = type();
        List<ImmutableEntity<?>> result = new ArrayList<>();
        for (AccessibleObject location : getAnnotatedProperties(type, Member.class)) {
            result.addAll(getEntityHolder(type, location, entityHelper, serializer).getEntities(this));
        }
        return result;
    }

    protected Collection<?> computeAliases() {
        Object target = get();
        if (target == null) {
            return emptyList();
        }
        List<Object> results = new ArrayList<>();
        for (AccessibleObject location : getAnnotatedProperties(target.getClass(), Alias.class)) {
            Object v = getValue(location, target, false);
            if (v != null) {
                getAnnotationAs(location, Alias.class, Alias.class).ifPresent(alias -> {
                    UnaryOperator<Object> aliasFunction = id -> "".equals(alias.prefix()) && "".equals(alias.postfix())
                            ? id : alias.prefix() + id + alias.postfix();
                    if (v instanceof Collection<?> collection) {
                        results.addAll(collection.stream().filter(Objects::nonNull).map(aliasFunction).toList());
                    } else {
                        results.add(aliasFunction.apply(v));
                    }
                });
            }
        }
        return results;
    }
}
