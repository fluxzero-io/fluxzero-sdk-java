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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.AggregateRepository;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import lombok.AllArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.reflection.ReflectionUtils.isNullable;
import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;

/**
 * Resolves handler method parameters that reference an {@link Entity} or the entity's value.
 *
 * <p>This resolver supports parameters of either {@code Entity<T>} or the entity's actual type {@code T}.
 * It will traverse the hierarchy of parent-child relationships between entities (if any) to find the closest match.
 *
 * <p>Resolution logic supports both {@link HasEntity} and {@link HasMessage} sources:
 * <ul>
 *   <li>If the input implements {@link HasEntity}, the existing entity is used.</li>
 *   <li>If the input implements {@link HasMessage}, the resolver attempts to extract the aggregate type and ID. Event
 *   and notification inputs load from their consumer namespace; other handler types use the application repository.</li>
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
@AllArgsConstructor
public class EntityParameterResolver implements PreparedParameterResolver<Object> {

    private final boolean checkCompatibility;
    private static final Object NO_ENTITY = new Object();

    public EntityParameterResolver() {
        this(true);
    }

    /**
     * Marker for handler-selection contexts where an {@link Entity} parameter should only be matched from message
     * metadata. Actual entity loading is deferred until an invoker is created for a concrete handler instance.
     */
    public interface DeferredMessageEntityResolution {
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        EntityMetadata.ExecutableParameters plan = EntityMetadata.modelParameters(method);
        if (plan.hasModels()) {
            return ReflectionUtils.getMethodAnnotation(method, HandleMessage.class).isPresent()
                   || EntityMetadata.of(method.getDeclaringClass()).handlerMethods().stream()
                           .anyMatch(handler -> handler.executable().equals(method));
        }
        return ReflectionUtils.getMethodAnnotation(method, HandleMessage.class)
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
        EntityMetadata.ExecutableParameters plan = EntityMetadata.modelParameters(parameter.getDeclaringExecutable());
        EntityMetadata.ModelParameter model = plan.parameters().get(parameter);
        if (model != null) {
            return input -> modelArgument(parameter, model, input, plan);
        }
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
        EntityMetadata.ExecutableParameters plan = EntityMetadata.modelParameters(parameter.getDeclaringExecutable());
        EntityMetadata.ModelParameter model = plan.parameters().get(parameter);
        if (model != null) {
            return canResolveModel(parameter, model, input, plan);
        }
        if (input instanceof DeferredMessageEntityResolution && input instanceof HasMessage message) {
            return canMatchFromMessageMetadata(parameter, message);
        }
        return matches(parameter, getMatchingEntity(input, parameter));
    }

    @Override
    public Function<Object, Object> resolveIfPossible(Parameter parameter, Annotation methodAnnotation, Object input) {
        EntityMetadata.ExecutableParameters plan = EntityMetadata.modelParameters(parameter.getDeclaringExecutable());
        EntityMetadata.ModelParameter model = plan.parameters().get(parameter);
        if (model != null) {
            return canResolveModel(parameter, model, input, plan)
                    ? invocation -> modelArgument(parameter, model, invocation, plan) : null;
        }
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

    private static boolean canResolveModel(
            Parameter parameter, EntityMetadata.ModelParameter model, Object input,
            EntityMetadata.ExecutableParameters plan) {
        if (GraphChangeHandlerDecorator.suppliesGraph(parameter)) {
            return true;
        }
        MutationPlan.DirectReferences references = modelReferences(input, model, plan);
        if (model.collectionWrapped()) {
            if (!references.present()) {
                return false;
            }
            Optional<CommitAttempt> context = modelContext(input);
            return references.modelIds().isEmpty()
                   || context.map(value -> references.modelIds().stream()
                                   .allMatch(id -> value.entity(id) != null))
                           .orElseGet(() -> input instanceof DeserializingMessage message
                                           && resolvedModelBinding(message, plan).isPresent());
        }
        if (references.present() && references.modelId() == null) {
            return isNullable(parameter);
        }
        Optional<CommitAttempt> context = modelContext(input);
        if (context.isEmpty()) {
            return input instanceof DeserializingMessage message
                   && resolvedModelBinding(message, plan).isPresent();
        }
        Entity<?> entity = context.get().resolve(model.modelType(), model.associationProperty());
        return entity != null && (model.entityWrapped() || model.graphWrapped()
                                  || entity.isPresent() || isNullable(parameter));
    }

    private static Object modelArgument(
            Parameter parameter, EntityMetadata.ModelParameter model, Object input,
            EntityMetadata.ExecutableParameters plan) {
        if (GraphChangeHandlerDecorator.suppliesGraph(parameter)) {
            return GraphChangeHandlerDecorator.suppliedGraph(parameter);
        }
        MutationPlan.DirectReferences references = modelReferences(input, model, plan);
        if (model.collectionWrapped()) {
            return modelCollection(parameter, model, input, plan, references);
        }
        if (references.present() && references.modelId() == null && isNullable(parameter)) {
            return null;
        }
        CommitAttempt context = modelContext(input).orElseGet(() ->
                input instanceof DeserializingMessage message ? modelContext(message, plan) : null);
        Entity<?> entity = context == null ? null
                : context.resolve(model.modelType(), model.associationProperty());
        if (model.entityWrapped()) {
            return entity;
        }
        if (model.graphWrapped()) {
            if (entity == null) {
                return null;
            }
            ModelRepository repository = modelRepository(input);
            return context == null
                    ? Graphs.lazy(entity, entity instanceof ModelRoot<?> root ? root.stateIndex() : -1L, repository)
                    : Graphs.lazy(entity, context, repository);
        }
        if (entity == null || !entity.isPresent()) {
            if (isNullable(parameter)) {
                return null;
            }
            throw new IllegalStateException(
                    "Model parameter %s in %s resolved to a missing or deleted %s model".formatted(
                            parameter, parameter.getDeclaringExecutable().toGenericString(),
                            model.modelType().getName()));
        }
        return entity.get();
    }

    private static List<Graph<?>> modelCollection(
            Parameter parameter, EntityMetadata.ModelParameter model, Object input,
            EntityMetadata.ExecutableParameters plan, MutationPlan.DirectReferences references) {
        if (!references.present()) {
            throw new IllegalStateException(
                    "Graph collection parameter %s in %s has no payload property '%s'".formatted(
                            parameter, parameter.getDeclaringExecutable().toGenericString(),
                            model.associationProperty()));
        }
        if (references.modelIds().isEmpty()) {
            return List.of();
        }
        CommitAttempt context = modelContext(input).orElseGet(() ->
                input instanceof DeserializingMessage message ? modelContext(message, plan) : null);
        if (context == null) {
            throw new IllegalStateException(
                    "No coherent model context is available for graph collection parameter " + parameter);
        }
        ModelRepository repository = modelRepository(input);
        List<Graph<?>> result = new ArrayList<>(references.modelIds().size());
        for (String modelId : references.modelIds()) {
            Entity<?> entity = context.entity(modelId);
            if (entity == null) {
                throw new IllegalStateException(
                        "Model context does not contain '%s' required by graph collection parameter %s"
                                .formatted(modelId, parameter));
            }
            result.add(Graphs.lazy(entity, context, repository));
        }
        return List.copyOf(result);
    }

    private static MutationPlan.DirectReferences modelReferences(
            Object input, EntityMetadata.ModelParameter parameter, EntityMetadata.ExecutableParameters plan) {
        Optional<CommitAttempt> context = modelContext(input);
        if (context.isPresent()) {
            MutationPlan.DirectReferences references = context.get().references(parameter);
            if (references != null) {
                return references;
            }
        }
        DeserializingMessage message = currentMessage(input);
        return message == null ? MutationPlan.DirectReferences.missing()
                : modelBinding(message, plan).resolution.references().getOrDefault(
                        parameter, MutationPlan.DirectReferences.missing());
    }

    private static Optional<CommitAttempt> modelContext(Object input) {
        if (input instanceof DeserializingMessage message) {
            Optional<CommitAttempt> direct = message.getContext(CommitAttempt.class);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return DeserializingMessage.getOptionally()
                .flatMap(message -> message.getContext(CommitAttempt.class));
    }

    private static CommitAttempt modelContext(
            DeserializingMessage message, EntityMetadata.ExecutableParameters plan) {
        return resolvedModelBinding(message, plan).orElseThrow().context(message);
    }

    private static Optional<ModelBinding> resolvedModelBinding(
            DeserializingMessage message, EntityMetadata.ExecutableParameters plan) {
        if (!supportsModelBoundary(message)) {
            return Optional.empty();
        }
        ModelBinding binding = modelBinding(message, plan);
        return binding.resolution.canLoadContext() ? Optional.of(binding) : Optional.empty();
    }

    private static ModelBinding modelBinding(
            DeserializingMessage message, EntityMetadata.ExecutableParameters plan) {
        return modelResolutionCache(message).bindings.computeIfAbsent(
                plan.executable(), ignored -> new ModelBinding(MutationPlan.bind(message, plan)));
    }

    private static boolean supportsModelBoundary(DeserializingMessage message) {
        return message.getMessageType() != MessageType.EVENT
               && message.getMessageType() != MessageType.NOTIFICATION
               || message.getIndex() != null
               || ModelEventMetadata.readBoundary(message.getMetadata()) != null;
    }

    private static ModelResolutionCache modelResolutionCache(DeserializingMessage message) {
        return message.computeContextIfAbsent(ModelResolutionCache.class, ignored -> new ModelResolutionCache());
    }

    private static ModelRepository currentModelRepository(DeserializingMessage message) {
        return Fluxzero.get().modelRepository().forNamespace(getConsumerNamespace(message));
    }

    private static DeserializingMessage currentMessage(Object input) {
        return input instanceof DeserializingMessage message
                ? message : DeserializingMessage.getOptionally().orElse(null);
    }

    private static ModelRepository modelRepository(Object input) {
        DeserializingMessage message = currentMessage(input);
        return message == null ? Fluxzero.get().modelRepository() : currentModelRepository(message);
    }

    private static final class ModelBinding {
        private final MutationPlan.Resolution resolution;
        private volatile CommitAttempt context;

        private ModelBinding(MutationPlan.Resolution resolution) {
            this.resolution = resolution;
        }

        private CommitAttempt context(DeserializingMessage message) {
            CommitAttempt result = context;
            if (result == null) {
                synchronized (this) {
                    result = context;
                    if (result == null) {
                        context = result = currentModelRepository(message)
                                .loadContext(resolution);
                    }
                }
            }
            return result;
        }
    }

    private static final class ModelResolutionCache {
        private final Map<Executable, ModelBinding> bindings = new ConcurrentHashMap<>();
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
        return playbackToHandledMessage(currentRepository().load(aggregateId, (Class) aggregateType));
    }

    private Entity<?> loadAggregate(Object input, String aggregateId, Class<?> aggregateType) {
        return cachedEntity(input, new EntityCacheKey("aggregate", aggregateId, aggregateType),
                            () -> Fluxzero.getOptionally()
                                    .map(fc -> loadAggregate(aggregateId, aggregateType)).orElse(null));
    }

    private Entity<?> loadEntity(Object input, String entityId) {
        return cachedEntity(input, new EntityCacheKey("entity", entityId, null),
                            () -> Fluxzero.getOptionally()
                                    .map(fc -> loadEntity(entityId)).orElse(null));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity<?> loadEntity(String entityId) {
        AggregateRepository repository = currentRepository();
        Entity<?> aggregate = playbackToHandledMessage(repository.loadFor(entityId, Object.class));
        return aggregate.getEntity(entityId).orElseGet(
                () -> playbackToHandledMessage(repository.load(entityId, (Class) Object.class)));
    }

    private AggregateRepository currentRepository() {
        AggregateRepository repository = Fluxzero.get().aggregateRepository();
        DeserializingMessage current = DeserializingMessage.getCurrent();
        return current != null && (current.getMessageType() == MessageType.EVENT
                                   || current.getMessageType() == MessageType.NOTIFICATION)
                ? repository.forNamespace(getConsumerNamespace(current)) : repository;
    }

    private static <T> Entity<T> playbackToHandledMessage(Entity<T> entity) {
        DeserializingMessage message = DeserializingMessage.getCurrent();
        if (message != null && (message.getMessageType() == MessageType.EVENT
                                || message.getMessageType() == MessageType.NOTIFICATION)
            && !Entity.isApplying()
            && entity.id().toString().equals(Entity.getAggregateId(message))
            && entity.rootConfiguration().eventSourced()
            && entity.sequenceNumber() >= 0L) {
            return entity.playBackToEvent(message.getIndex(), message.getMessageId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Could not load entity %s of type %s for event %s. Entity (%s) started at event %s"
                                    .formatted(entity.id(), entity.type().getSimpleName(), message.getIndex(),
                                               entity, entity.lastEventIndex())));
        }
        return entity;
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
                ? (!checkCompatibility || isNullable(parameter) || Entity.class.isAssignableFrom(parameter.getType()))
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
