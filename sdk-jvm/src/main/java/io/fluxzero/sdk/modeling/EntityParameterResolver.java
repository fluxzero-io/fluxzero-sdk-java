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

import io.fluxzero.common.handling.ExecutableAnnotationResolver;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;


/**
 * Resolves handler method parameters that reference an {@link Entity} or the entity's value.
 *
 * <p>This resolver supports parameters of either {@code Entity<T>} or the entity's actual type {@code T}.
 * It will traverse the hierarchy of parent-child relationships between entities (if any) to find the closest match.
 *
 * <p>Resolution logic supports both {@link HasEntity} and {@link HasMessage} sources:
 * <ul>
 *   <li>If the input implements {@link HasEntity}, the existing entity is used.</li>
 *   <li>If the input implements {@link HasMessage}, the resolver attempts to extract the aggregate type and ID.
 *   Aggregate metadata is resolved directly using {@code Fluxzero#loadAggregate}; routing keys still use
 *   {@code Fluxzero#loadEntity} because they may point at nested entities.</li>
 * </ul>
 *
 * <p>The entity is only resolved if:
 * <ul>
 *   <li>The parameter type is assignable from the resolved entity type (or the {@code Entity<T>} type).</li>
 *   <li>Or, the entity has a parent matching the required parameter type.</li>
 * </ul>
 *
 * <p>This resolver determines handler method specificity and can thus be used in disambiguation when multiple
 * handler methods are present in the same target class.
 */
public class EntityParameterResolver implements PreparedParameterResolver<Object> {

    private final boolean checkCompatibility;
    private final ExecutableAnnotationResolver annotationResolver;
    private static final Object NO_ENTITY = new Object();

    public EntityParameterResolver() {
        this(true);
    }

    public EntityParameterResolver(boolean checkCompatibility) {
        this(checkCompatibility, MetadataExecutableAnnotationResolver.create());
    }

    EntityParameterResolver(boolean checkCompatibility, ExecutableAnnotationResolver annotationResolver) {
        this.checkCompatibility = checkCompatibility;
        this.annotationResolver = Objects.requireNonNull(annotationResolver, "annotationResolver");
    }

    /**
     * Marker for handler-selection contexts where an {@link Entity} parameter should only be matched from message
     * metadata. Actual entity loading is deferred until an invoker is created for a concrete handler instance.
     */
    public interface DeferredMessageEntityResolution {
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        return annotationResolver.getAnnotation(method, HandleMessage.class)
                .map(EntityParameterResolver::supportsMessageEntityInjection)
                .orElse(true);
    }

    private static boolean supportsMessageEntityInjection(Annotation methodAnnotation) {
        if (methodAnnotation == null || methodAnnotation.annotationType().getAnnotation(HandleMessage.class) == null) {
            return true;
        }
        return methodAnnotation instanceof HandleEvent || methodAnnotation instanceof HandleNotification;
    }

    /**
     * Provides a {@link Supplier} that returns the matching entity or its value for the given parameter. Will
     * recursively traverse parent entities if needed.
     *
     * @param parameter        the parameter for which a value must be injected
     * @param methodAnnotation the annotation on the handler method
     * @return a function that supplies the resolved value
     */
    @Override
    public Function<Object, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
        return m -> resolve(parameter, getMatchingEntity(m, parameter)).get();
    }

    /**
     * Determines whether the parameter can be resolved from the given input. The match succeeds if a suitable entity or
     * value can be found in the message or entity context.
     *
     * @param parameter        the method parameter
     * @param methodAnnotation the annotation on the handler method
     * @param input            the handler input (e.g., {@link DeserializingMessage} or {@link HasEntity})
     * @return true if the parameter can be resolved from the input, false otherwise
     */
    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, Object input) {
        if (input instanceof DeferredMessageEntityResolution && input instanceof HasMessage message) {
            return canMatchFromMessageMetadata(parameter, message);
        }
        return matches(parameter, getMatchingEntity(input, parameter));
    }

    @Override
    public Function<Object, Object> resolveIfPossible(Parameter parameter, Annotation methodAnnotation, Object input) {
        if (input instanceof DeferredMessageEntityResolution && input instanceof HasMessage message) {
            if (canMatchFromMessageMetadata(parameter, message)) {
                return ignored -> null;
            }
            return null;
        }
        Entity<?> entity = getMatchingEntity(input, parameter);
        if (!matches(parameter, entity)) {
            return null;
        }
        Supplier<?> supplier = resolve(parameter, entity);
        return ignored -> supplier.get();
    }

    /**
     * Attempts to retrieve an {@link Entity} instance matching the given method parameter.
     * <p>
     * The search is performed on:
     * <ul>
     *   <li>{@link HasEntity} input types (directly returning the contained entity)</li>
     *   <li>{@link HasMessage} input types (by extracting aggregate metadata and loading the entity)</li>
     * </ul>
     *
     * @param input     the message or entity context
     * @param parameter the method parameter being resolved
     * @return the matching {@link Entity} or {@code null} if not resolvable
     */
    protected Entity<?> getMatchingEntity(Object input, Parameter parameter) {
        if (input instanceof HasEntity) {
            return ((HasEntity) input).getEntity();
        } else if (input instanceof HasMessage message) {
            var type = Entity.getAggregateType(message);
            String aggregateId = Entity.getAggregateId(message);
            if (aggregateId != null) {
                if (!isCompatibleAggregateParameter(parameter, type)) {
                    return null;
                }
                Entity<?> entity = loadAggregate(input, aggregateId, type);
                return entity != null && isAssignable(parameter, entity)
                       && (entity.isPresent() || entity.sequenceNumber() > -1L) ? entity : null;
            }
            if (type != null && (Entity.class.isAssignableFrom(parameter.getType())
                                 || parameter.getType().isAssignableFrom(type))) {
                return message.computeRoutingKey()
                        .map(possibleEntityId -> loadEntity(input, possibleEntityId))
                        .filter(e -> isAssignable(parameter, e))
                        .filter(e -> e.isPresent() || e.sequenceNumber() > -1L)
                        .orElse(null);
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Entity<?> loadAggregate(String aggregateId, Class<?> aggregateType) {
        return Fluxzero.loadAggregate(aggregateId, (Class) aggregateType);
    }

    private Entity<?> loadAggregate(Object input, String aggregateId, Class<?> aggregateType) {
        return cachedEntity(input, new EntityCacheKey("aggregate", aggregateId, aggregateType),
                            () -> Fluxzero.getOptionally()
                                    .map(fc -> loadAggregate(aggregateId, aggregateType)).orElse(null));
    }

    private Entity<?> loadEntity(Object input, String entityId) {
        return cachedEntity(input, new EntityCacheKey("entity", entityId, null),
                            () -> Fluxzero.getOptionally()
                                    .map(fc -> Fluxzero.loadEntity(entityId)).orElse(null));
    }

    private Entity<?> cachedEntity(Object input, EntityCacheKey key, Supplier<Entity<?>> loader) {
        if (input instanceof DeserializingMessage message && DeserializingMessage.getCurrent() == message) {
            return message.computeContextIfAbsent(EntityResolutionCache.class, ignored -> new EntityResolutionCache())
                    .get(key, loader);
        }
        return loader.get();
    }

    private boolean canMatchFromMessageMetadata(Parameter parameter, HasMessage message) {
        Class<?> aggregateType = Entity.getAggregateType(message);
        String aggregateId = Entity.getAggregateId(message);
        if (aggregateId != null) {
            return isCompatibleAggregateParameter(parameter, aggregateType);
        }
        return aggregateType != null && (Entity.class.isAssignableFrom(parameter.getType())
                                         || parameter.getType().isAssignableFrom(aggregateType))
               && message.computeRoutingKey().isPresent();
    }

    private boolean isCompatibleAggregateParameter(Parameter parameter, Class<?> aggregateType) {
        if (aggregateType == null) {
            return false;
        }
        Class<?> parameterType = getEntityParameterType(parameter);
        return parameterType.isAssignableFrom(aggregateType);
    }

    /**
     * Returns {@code true} if the entity or any of its parents match the expected parameter type, respecting nullable
     * flags on parameters.
     */
    protected boolean matches(Parameter parameter, Entity<?> entity) {
        if (entity == null) {
            return false;
        }
        if (isAssignable(parameter, entity)) {
            return true;
        }
        return matches(parameter, entity.parent());
    }

    /**
     * Returns a {@link Supplier} that returns the entity if the entity or any of its parents match the expected
     * parameter type.
     */
    protected Supplier<?> resolve(Parameter parameter, Entity<?> entity) {
        if (entity == null) {
            return () -> null;
        }
        if (isAssignable(parameter, entity)) {
            return Entity.class.isAssignableFrom(parameter.getType()) ? () -> entity : entity::get;
        }
        return resolve(parameter, entity.parent());
    }

    private boolean isAssignable(Parameter parameter, Entity<?> entity) {
        Class<?> eType = entity.type();
        Class<?> pType = getEntityParameterType(parameter);
        return entity.get() == null
                ? (!checkCompatibility || JvmComponentIntrospector.getInstance().isNullable(parameter) || Entity.class.isAssignableFrom(parameter.getType()))
                  && (pType.isAssignableFrom(eType) || eType.isAssignableFrom(pType))
                : pType.isAssignableFrom(eType);
    }

    private Class<?> getEntityParameterType(Parameter parameter) {
        if (Entity.class.equals(parameter.getType())) {
            Type parameterizedType = parameter.getParameterizedType();
            if (parameterizedType instanceof ParameterizedType) {
                Type[] actualTypeArguments = ((ParameterizedType) parameterizedType).getActualTypeArguments();
                if (actualTypeArguments.length == 1) {
                    Type actualType = actualTypeArguments[0];
                    if (actualType instanceof Class<?>) {
                        return (Class<?>) actualType;
                    } else if (actualType instanceof WildcardType) {
                        Type[] lowerBounds = ((WildcardType) actualType).getLowerBounds();
                        if (lowerBounds.length == 0) {
                            return Object.class;
                        } else {
                            Type lowerBound = lowerBounds[0];
                            if (lowerBound instanceof Class<?>) {
                                return (Class<?>) lowerBound;
                            } else if (lowerBound instanceof ParameterizedType) {
                                lowerBound = ((ParameterizedType) lowerBound).getRawType();
                                if (lowerBound instanceof Class<?>) {
                                    return (Class<?>) lowerBound;
                                }
                            }
                        }
                    }
                }
            }
            return Object.class;
        }
        return parameter.getType();
    }

    /**
     * Indicates that this resolver contributes to disambiguating handler methods when multiple handlers are present in
     * the same target class.
     *
     * <p>This is useful when more than one method matches a message, and the framework must
     * decide which method is more specific. If this returns {@code true}, the resolver's presence and compatibility
     * with the parameter may influence which handler is selected.
     *
     * @return true, signaling that this resolver helps determine method specificity
     */
    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    private record EntityCacheKey(String source, String id, Class<?> type) {
    }

    private static class EntityResolutionCache {
        private final Map<EntityCacheKey, Object> entities = new ConcurrentHashMap<>();

        Entity<?> get(EntityCacheKey key, Supplier<Entity<?>> loader) {
            Object result = entities.computeIfAbsent(key, ignored -> {
                Entity<?> entity = loader.get();
                return entity == null ? NO_ENTITY : entity;
            });
            return result == NO_ENTITY ? null : (Entity<?>) result;
        }
    }
}
