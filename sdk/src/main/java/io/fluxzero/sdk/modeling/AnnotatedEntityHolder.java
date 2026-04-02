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

import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.serialization.Serializer;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.call;
import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.common.reflection.ReflectionUtils.copyFields;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperty;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotationAs;
import static io.fluxzero.common.reflection.ReflectionUtils.getCollectionElementType;
import static io.fluxzero.common.reflection.ReflectionUtils.getName;
import static io.fluxzero.common.reflection.ReflectionUtils.getValue;
import static io.fluxzero.common.reflection.ReflectionUtils.ifClass;
import static io.fluxzero.common.reflection.ReflectionUtils.readProperty;
import static java.beans.Introspector.decapitalize;

/**
 * Helper class for {@link Entity} instances that manage nested entity relationships annotated with
 * {@link Member @Member}.
 * <p>
 * The {@code AnnotatedEntityHolder} provides a way to access and update a composite object (holder) that contains one
 * or more nested entities. It supports collections, maps, and single-value member fields. It extracts member values
 * using reflection and allows updating them through generated "wither" or "setter" logic, making it possible to
 * reconstruct an updated parent entity when one of its children changes.
 * <p>
 * The holder is typically tied to a specific {@link AccessibleObject} (field or method), which is analyzed and cached
 * to optimize repeated operations.
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Extract and cache metadata about a member location (field/method), including its type, ID property, and wither.</li>
 *   <li>Create {@link ImmutableEntity} instances from nested values at this member location.</li>
 *   <li>Apply updates to these entities and propagate the changes back to the parent object via a wither or clone-and-set.</li>
 *   <li>Support updating collection and map-based member fields while preserving immutability semantics.</li>
 * </ul>
 *
 * <p>
 * The holder is initialized and cached via {@link #getEntityHolder(Class, AccessibleObject, EntityHelper, Serializer)},
 * and update logic falls back to serialization-based cloning if a wither method is not explicitly defined.
 *
 * @see ImmutableEntity
 * @see Member
 */
@Slf4j
public class AnnotatedEntityHolder {
    private static final Map<AccessibleObject, AnnotatedEntityHolder> cache = new ConcurrentHashMap<>();
    /**
     * Replay-local cache for wrapper reuse while event sourcing an aggregate.
     * <p>
     * This optimization assumes the aggregate forms a tree: the same child object instance may only occur once in the
     * aggregate graph during replay. Re-encountering the same raw child instance under a different parent would make
     * the cached wrapper ambiguous, because replay updates the wrapper's parent pointer as it walks back up the tree.
     */
    private static final ThreadLocal<Map<AnnotatedEntityHolder, IdentityHashMap<Object, ImmutableEntity<?>>>>
            loadingEntityCache = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Map<AnnotatedEntityHolder, IdentityHashMap<Object, Collection<String>>>>
            loadingRouteValuesCache = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final Pattern getterPattern = Pattern.compile("(get|is)([A-Z].*)");

    private final AccessibleObject location;
    private final BiFunction<Object, Object, Object> wither;
    private final Class<?> holderType;
    private final boolean collectionHolder;
    private final boolean mapHolder;
    private final Function<Object, Id> idProvider;
    private final Class<?> entityType;

    private final EntityHelper entityHelper;
    private final Serializer serializer;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Getter(lazy = true)
    private final ImmutableEntity<?> emptyEntity = ImmutableEntity.builder()
            .type((Class) entityType)
            .entityHelper(entityHelper)
            .serializer(serializer)
            .holder(this)
            .idProperty(idProvider.apply(entityType).property())
            .build();

    /**
     * Retrieves or creates a cached {@code AnnotatedEntityHolder} at the given member location.
     *
     * @param ownerType    the class that owns the annotated member
     * @param location     the accessible field or method annotated with @Member
     * @param entityHelper the helper used to apply/validate/apply logic to nested entities
     * @param serializer   the serializer used for cloning values during updates
     * @return a cached or new instance of {@code AnnotatedEntityHolder}
     */
    public static AnnotatedEntityHolder getEntityHolder(Class<?> ownerType, AccessibleObject location,
                                                        EntityHelper entityHelper, Serializer serializer) {
        return cache.computeIfAbsent(location,
                                     l -> new AnnotatedEntityHolder(ownerType, l, entityHelper, serializer));
    }

    public static Map<?, ?> snapshotLoadingEntityCache() {
        Map<AnnotatedEntityHolder, IdentityHashMap<Object, ImmutableEntity<?>>> snapshot = new IdentityHashMap<>();
        loadingEntityCache.get().forEach((holder, entities) -> snapshot.put(holder, new IdentityHashMap<>(entities)));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public static void restoreLoadingEntityCache(Map<?, ?> snapshot) {
        Map<AnnotatedEntityHolder, IdentityHashMap<Object, ImmutableEntity<?>>> target = loadingEntityCache.get();
        target.clear();
        ((Map<AnnotatedEntityHolder, IdentityHashMap<Object, ImmutableEntity<?>>>) snapshot)
                .forEach((holder, entities) -> target.put(holder, new IdentityHashMap<>(entities)));
    }

    public static void clearLoadingEntityCache() {
        loadingEntityCache.get().clear();
    }

    public static Map<?, ?> snapshotLoadingRouteValuesCache() {
        Map<AnnotatedEntityHolder, IdentityHashMap<Object, Collection<String>>> snapshot = new IdentityHashMap<>();
        loadingRouteValuesCache.get().forEach((holder, routes) -> snapshot.put(holder, new IdentityHashMap<>(routes)));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public static void restoreLoadingRouteValuesCache(Map<?, ?> snapshot) {
        Map<AnnotatedEntityHolder, IdentityHashMap<Object, Collection<String>>> target = loadingRouteValuesCache.get();
        target.clear();
        ((Map<AnnotatedEntityHolder, IdentityHashMap<Object, Collection<String>>>) snapshot)
                .forEach((holder, routes) -> target.put(holder, new IdentityHashMap<>(routes)));
    }

    public static void clearLoadingRouteValuesCache() {
        loadingRouteValuesCache.get().clear();
    }

    private static final Function<Class<?>, Optional<MemberInvoker>> entityIdInvokerCache = memoize(
            entityType -> getAnnotatedProperty(
                    entityType, EntityId.class).map(a -> DefaultMemberInvoker.asInvoker((java.lang.reflect.Member) a)));

    private AnnotatedEntityHolder(Class<?> ownerType, AccessibleObject location,
                                  EntityHelper entityHelper, Serializer serializer) {
        this.entityHelper = entityHelper;
        this.serializer = serializer;
        this.location = location;
        this.holderType = ReflectionUtils.getPropertyType(location);
        this.collectionHolder = Collection.class.isAssignableFrom(holderType);
        this.mapHolder = Map.class.isAssignableFrom(holderType);
        this.entityType = getCollectionElementType(location).orElse(holderType);
        Member member = ReflectionUtils.getAnnotation(location, Member.class).orElseThrow();
        String pathToId = member.idProperty();
        this.idProvider = pathToId.isBlank() ?
                v -> (v == null ? Optional.<MemberInvoker>empty() : entityIdInvokerCache.apply(v.getClass())).map(
                                p -> new Id(p.invoke(v), p.getMember().getName()))
                        .orElseGet(() -> {
                            if (ifClass(v) instanceof Class<?> c) {
                                return new Id(null, getAnnotatedProperty(c, EntityId.class)
                                        .map(ReflectionUtils::getName).orElse(null));
                            }
                            return new Id(null, null);
                        }) :
                v -> new Id(readProperty(pathToId, v).orElse(null), pathToId);
        this.wither = computeWither(ownerType, location, serializer, member);
    }

    private static BiFunction<Object, Object, Object> computeWither(
            Class<?> ownerType, AccessibleObject location, Serializer serializer, Member member) {
        String propertyName = decapitalize(Optional.of(getName(location)).map(name -> Optional.of(
                        getterPattern.matcher(name)).map(matcher -> matcher.matches() ? matcher.group(2) : name)
                .orElse(name)).orElseThrow());
        Class<?>[] witherParams = new Class<?>[]{ReflectionUtils.getPropertyType(location)};
        Stream<Method> witherCandidates = ReflectionUtils.getAllMethods(ownerType).stream().filter(
                m -> m.getReturnType().isAssignableFrom(ownerType) || m.getReturnType().equals(void.class));
        witherCandidates = member.wither().isBlank() ?
                witherCandidates.filter(m -> Arrays.equals(witherParams, m.getParameterTypes())
                                             && m.getName().toLowerCase().contains(propertyName.toLowerCase())) :
                witherCandidates.filter(m -> Objects.equals(member.wither(), m.getName()));
        Optional<BiFunction<Object, Object, Object>> wither =
                witherCandidates.findFirst().map(m -> (o, h) -> call(() -> m.invoke(o, h)));
        return wither.orElseGet(() -> {
            AtomicBoolean warningIssued = new AtomicBoolean();
            MemberInvoker field = ReflectionUtils.getField(ownerType, propertyName)
                    .map(DefaultMemberInvoker::asInvoker).orElse(null);
            Function<Object, Object> ownerCloner = computeOwnerCloner(ownerType, serializer);
            return (o, h) -> {
                if (warningIssued.get()) {
                    return o;
                }
                if (field == null) {
                    if (warningIssued.compareAndSet(false, true)) {
                        log.warn("No update function found for @Member {}. {}",
                                 location, updateFunctionAdvice(ownerType, propertyName));
                    }
                } else {
                    try {
                        o = ownerCloner.apply(o);
                        field.invoke(o, h);
                    } catch (Exception e) {
                        if (warningIssued.compareAndSet(false, true)) {
                            log.warn("Not able to update @Member {}. {}", location,
                                     updateFunctionAdvice(ownerType, propertyName), e);
                        }
                    }
                }
                return o;
            };
        });
    }

    private static Function<Object, Object> computeOwnerCloner(Class<?> ownerType, Serializer serializer) {
        try {
            var constructor = ReflectionUtils.ensureAccessible(ownerType.getDeclaredConstructor());
            return owner -> copyFields(owner, call(constructor::newInstance));
        } catch (Exception ignored) {
            return serializer::clone;
        }
    }


    private static String updateFunctionAdvice(Class<?> ownerType, String propertyName) {
        if (ownerType.isRecord()) {
            return "Records require an explicit wither such as with%s(...) or @Member(wither = \"...\") to update the parent entity automatically."
                    .formatted(Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1));
        }
        return "Please add a wither or setter method.";
    }

    /**
     * Returns the set of {@link ImmutableEntity} instances that are defined at the member location within the specified
     * parent entity. This includes actual members and an "empty" fallback entity used for creating new entities or
     * assertions to carry out before an entity is created.
     *
     * @param parent the parent entity instance
     * @return a stream of nested {@link ImmutableEntity} instances
     */
    public List<? extends ImmutableEntity<?>> getEntities(Entity<?> parent) {
        if (parent.get() == null) {
            return List.of();
        }
        Object holderValue = getValue(location, parent.get(), false);
        ImmutableEntity<?> emptyEntity = getEmptyEntity().toBuilder().parent(parent).build();
        if (holderValue == null) {
            return List.of(emptyEntity);
        }
        if (collectionHolder) {
            Collection<?> collection = (Collection<?>) holderValue;
            List<ImmutableEntity<?>> result = new ArrayList<>(collection.size() + 1);
            for (Object value : collection) {
                ImmutableEntity<?> entity = createEntity(value, idProvider, parent);
                if (entity != null) {
                    result.add(entity);
                }
            }
            result.add(emptyEntity);
            return result;
        }
        if (mapHolder) {
            Map<?, ?> map = (Map<?, ?>) holderValue;
            List<ImmutableEntity<?>> result = new ArrayList<>(map.size() + 1);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                ImmutableEntity<?> entity = createEntity(
                        entry.getValue(), v -> new Id(entry.getKey(), idProvider.apply(v).property()), parent);
                if (entity != null) {
                    result.add(entity);
                }
            }
            result.add(emptyEntity);
            return result;
        }
        ImmutableEntity<?> entity = createEntity(holderValue, idProvider, parent);
        return entity == null ? List.of(emptyEntity) : List.of(entity, emptyEntity);
    }

    public ImmutableEntity<?> getEntityByRoute(Entity<?> parent, String routeValue) {
        if (parent.get() == null) {
            return null;
        }
        Object holderValue = getValue(location, parent.get(), false);
        if (holderValue == null) {
            return null;
        }
        if (collectionHolder) {
            for (Object value : (Collection<?>) holderValue) {
                ImmutableEntity<?> entity = getEntityByRoute(value, idProvider, parent, routeValue);
                if (entity != null) {
                    return entity;
                }
            }
            return null;
        }
        if (mapHolder) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) holderValue).entrySet()) {
                ImmutableEntity<?> entity = getEntityByRoute(
                        entry.getValue(), v -> new Id(entry.getKey(), idProvider.apply(v).property()), parent, routeValue);
                if (entity != null) {
                    return entity;
                }
            }
            return null;
        }
        return getEntityByRoute(holderValue, idProvider, parent, routeValue);
    }

    private ImmutableEntity<?> getEntityByRoute(Object member, Function<Object, Id> idProvider,
                                                Entity<?> parent, String routeValue) {
        if (member == null) {
            return null;
        }
        for (String candidate : routeValues(member, idProvider)) {
            if (routeValue.equals(candidate)) {
                return createEntity(member, idProvider, parent);
            }
        }
        return null;
    }

    private Collection<String> routeValues(Object member, Function<Object, Id> idProvider) {
        if (member == null) {
            return List.of();
        }
        if (Entity.isLoading() && !mapHolder) {
            IdentityHashMap<Object, Collection<String>> cache = loadingRouteValuesCache.get()
                    .computeIfAbsent(this, ignored -> new IdentityHashMap<>());
            Collection<String> cached = cache.get(member);
            if (cached != null) {
                return cached;
            }
            Collection<String> computed = computeRouteValues(member, idProvider);
            cache.put(member, computed);
            return computed;
        }
        return computeRouteValues(member, idProvider);
    }

    private Collection<String> computeRouteValues(Object member, Function<Object, Id> idProvider) {
        List<String> results = new ArrayList<>(4);
        Id id = idProvider.apply(member);
        if (id.value() != null) {
            addRouteValue(results, id.value().toString());
        }
        for (AccessibleObject aliasLocation : getAnnotatedProperties(member.getClass(), Alias.class)) {
            Object aliasValue = getValue(aliasLocation, member, false);
            if (aliasValue == null) {
                continue;
            }
            getAnnotationAs(aliasLocation, Alias.class, Alias.class).ifPresent(alias -> {
                if (aliasValue instanceof Collection<?> collection) {
                    for (Object item : collection) {
                        if (item != null) {
                            addRouteValue(results, alias.prefix() + item + alias.postfix());
                        }
                    }
                } else {
                    addRouteValue(results, alias.prefix() + aliasValue + alias.postfix());
                }
            });
        }
        return List.copyOf(results);
    }

    private static void addRouteValue(List<String> results, String candidate) {
        if (!results.contains(candidate)) {
            results.add(candidate);
        }
    }

    @SuppressWarnings({"unchecked"})
    private ImmutableEntity<?> createEntity(Object member, Function<Object, Id> idProvider,
                                            Entity<?> parent) {
        if (member == null) {
            return null;
        }
        if (Entity.isLoading()) {
            IdentityHashMap<Object, ImmutableEntity<?>> cache = loadingEntityCache.get()
                    .computeIfAbsent(this, ignored -> new IdentityHashMap<>());
            ImmutableEntity<?> cached = cache.get(member);
            if (cached != null) {
                return cached.replayParent(parent);
            }
            ImmutableEntity<?> created = createNewEntity(member, idProvider, parent);
            cache.put(member, created);
            return created;
        }
        return createNewEntity(member, idProvider, parent);
    }

    @SuppressWarnings({"unchecked"})
    private ImmutableEntity<?> createNewEntity(Object member, Function<Object, Id> idProvider,
                                               Entity<?> parent) {
        Id id = idProvider.apply(member);
        return ImmutableEntity.builder().id(id.value()).type((Class<Object>) member.getClass())
                .value(member).idProperty(id.property()).parent(parent).holder(this)
                .entityHelper(entityHelper).serializer(serializer).build();
    }

    /**
     * Updates the parent object with the new state of a child entity. Uses a wither method if available; otherwise
     * attempts a reflective update through cloning and direct field access. If the entity has been removed (value has
     * become {@code null}), the entity is removed from the owner object if possible.
     * <p>
     * Note that this method typically returns a new clone of the owner object containing the updated child entity
     * value.
     *
     * @param owner  the parent object containing the member field
     * @param before the old entity (before update)
     * @param after  the new entity (after update)
     * @return a new updated parent object, or the original if mutation fails
     */
    @SneakyThrows
    public Object updateOwner(Object owner, Entity<?> before, Entity<?> after) {
        Object holder = ReflectionUtils.getValue(location, owner);
        if (collectionHolder) {
            Collection<Object> collection = serializer.clone(holder);
            if (collection == null) {
                collection = new ArrayList<>();
            }
            if (collection instanceof List<?>) {
                List<Object> list = (List<Object>) collection;
                int index = list.indexOf(before.get());
                if (index < 0) {
                    list.add(after.get());
                } else {
                    if (after.get() == null) {
                        list.remove(index);
                    } else {
                        list.set(index, after.get());
                    }
                }
                holder = list;
            } else {
                collection.remove(before.get());
                collection.add(after.get());
                holder = collection;
            }
        } else if (mapHolder) {
            Map<Object, Object> map = serializer.clone(holder);
            if (map == null) {
                map = new LinkedHashMap<>();
            }
            Object id = Optional.ofNullable(after.id()).orElseGet(() -> idProvider.apply(after.get()).value());
            if (after.get() == null) {
                map.remove(id);
            } else {
                map.put(id, after.get());
            }
            holder = map;
        } else {
            holder = after.get();
        }
        Object result = wither.apply(owner, holder);
        return result == null ? owner : result;
    }

    @Value
    @Accessors(fluent = true)
    private static class Id {
        Object value;
        String property;
    }

}
