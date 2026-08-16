/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleMessage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.isNullable;
import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;

/**
 * Injects independent models into selected message handlers.
 * <p>
 * Every handler kind can address direct models through canonical or typed IDs. A parameter-level
 * {@link io.fluxzero.sdk.tracking.handling.Association @Association} can select another payload or metadata property;
 * when no such direct value exists it qualifies a reachable parent edge. Parents, grandparents, and further
 * ancestors are loaded with the directly addressed descendants in one handler-level context.
 * <p>
 * Handler matching is structural and never performs repository I/O. The complete model plan is loaded only when the
 * selected handler is invoked. Events and notifications require persisted model-commit metadata and are reconstructed
 * at that exact commit boundary; other message types use the repository's current boundary.
 */
public class ModelEntityParameterResolver
        implements PreparedParameterResolver<Object> {

    @Override
    public boolean mayApply(
            Executable method, Class<?> targetClass) {
        HandlerPlan plan = plan(method);
        return plan.hasModels()
               && (ReflectionUtils.getMethodAnnotation(
                        method, HandleMessage.class).isPresent()
                   || ModelMetadata.of(method.getDeclaringClass())
                        .handlerMethods().stream()
                        .anyMatch(handler ->
                                handler.executable().equals(method)));
    }

    @Override
    public Function<Object, Object> resolve(
            Parameter parameter,
            Annotation methodAnnotation) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        return modelParameter == null ? null : input -> {
            if (modelParameter.collectionWrapped()) {
                return collectionValue(parameter, modelParameter, input, plan);
            }
            return nullDirectReference(input, modelParameter) && isNullable(parameter)
                    ? null
                    : value(parameter, modelParameter,
                            resolveEntity(input, plan, modelParameter), input, plan);
        };
    }

    @Override
    public boolean matches(
            Parameter parameter,
            Annotation methodAnnotation,
            Object input) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        if (modelParameter == null) {
            return false;
        }
        if (modelParameter.collectionWrapped()) {
            ModelTargetResolver.DirectModelReferences references =
                    directReferences(input, modelParameter);
            if (!references.present()) {
                return false;
            }
            if (references.modelIds().isEmpty()) {
                return true;
            }
            Optional<ModelCommitContext> context = commitContext(input);
            if (context.isPresent()) {
                return references.modelIds().stream().allMatch(id -> context.get().entry(id) != null);
            }
            return input instanceof DeserializingMessage message
                   && resolvedPlan(message, plan).isPresent();
        }
        if (nullDirectReference(input, modelParameter)) {
            return isNullable(parameter);
        }
        Optional<ModelCommitContext> context =
                commitContext(input);
        if (context.isPresent()) {
            Entity<?> entity = context.get().resolve(
                    modelParameter.modelType(),
                    modelParameter.associationProperty());
            return entity != null
                   && (modelParameter.entityWrapped()
                       || modelParameter.graphWrapped()
                       || entity.isPresent()
                       || isNullable(parameter));
        }
        return input instanceof DeserializingMessage message
               && resolvedPlan(message, plan).isPresent();
    }

    @Override
    public Function<Object, Object>
            resolveIfPossible(
                    Parameter parameter,
                    Annotation methodAnnotation,
                    Object input) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        if (modelParameter == null) {
            return null;
        }
        if (modelParameter.collectionWrapped()) {
            ModelTargetResolver.DirectModelReferences references =
                    directReferences(input, modelParameter);
            if (!references.present()) {
                return null;
            }
            if (!references.modelIds().isEmpty()
                && commitContext(input).isEmpty()
                && (!(input instanceof DeserializingMessage message)
                    || resolvedPlan(message, plan).isEmpty())) {
                return null;
            }
            return invocation -> collectionValue(parameter, modelParameter, invocation, plan);
        }
        if (nullDirectReference(input, modelParameter)) {
            return isNullable(parameter) ? ignored -> null : null;
        }
        Optional<ModelCommitContext> context =
                commitContext(input);
        if (context.isPresent()) {
            Entity<?> entity = context.get().resolve(
                    modelParameter.modelType(),
                    modelParameter.associationProperty());
            if (entity == null
                || !modelParameter.entityWrapped()
                   && !modelParameter.graphWrapped()
                   && !entity.isPresent()
                   && !isNullable(parameter)) {
                return null;
            }
        } else if (!(input instanceof DeserializingMessage message)
                   || resolvedPlan(message, plan).isEmpty()) {
            return null;
        }
        return invocation -> value(
                parameter, modelParameter,
                resolveEntity(invocation, plan, modelParameter),
                invocation, plan);
    }

    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    private static Object value(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Entity<?> entity,
            Object input,
            HandlerPlan plan) {
        if (modelParameter.entityWrapped()) {
            return entity;
        }
        if (modelParameter.graphWrapped()) {
            if (entity == null) {
                return null;
            }
            ModelCommitContext context = commitContext(input)
                    .orElseGet(() -> input instanceof DeserializingMessage message
                            ? context(message, plan) : null);
            DeserializingMessage message = input instanceof DeserializingMessage deserializingMessage
                    ? deserializingMessage : DeserializingMessage.getOptionally().orElse(null);
            ModelRepository repository = message == null
                    ? Fluxzero.get().modelRepository() : currentRepository(message);
            if (context != null) {
                return Graphs.lazy(entity, context, repository);
            }
            long stateIndex = entity instanceof ModelRoot<?> root ? root.stateIndex() : -1L;
            return Graphs.lazy(entity, stateIndex, repository);
        }
        if (entity == null || !entity.isPresent()) {
            if (isNullable(parameter)) {
                return null;
            }
            throw new IllegalStateException(
                    "Model parameter %s in %s resolved to a missing or deleted %s model"
                            .formatted(
                                    parameter,
                                    parameter
                                            .getDeclaringExecutable()
                                            .toGenericString(),
                                    modelParameter.modelType()
                                            .getName()));
        }
        return entity.get();
    }

    private static List<Graph<?>> collectionValue(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Object input,
            HandlerPlan plan) {
        ModelTargetResolver.DirectModelReferences references = directReferences(input, modelParameter);
        if (!references.present()) {
            throw new IllegalStateException(
                    "Graph collection parameter %s in %s has no payload property '%s'"
                            .formatted(parameter, parameter.getDeclaringExecutable().toGenericString(),
                                       modelParameter.associationProperty()));
        }
        if (references.modelIds().isEmpty()) {
            return List.of();
        }
        ModelCommitContext context = commitContext(input)
                .orElseGet(() -> input instanceof DeserializingMessage message
                        ? context(message, plan) : null);
        if (context == null) {
            throw new IllegalStateException(
                    "No coherent model context is available for graph collection parameter " + parameter);
        }
        DeserializingMessage message = input instanceof DeserializingMessage deserializingMessage
                ? deserializingMessage : DeserializingMessage.getOptionally().orElse(null);
        ModelRepository repository = message == null
                ? Fluxzero.get().modelRepository() : currentRepository(message);
        List<Graph<?>> result = new java.util.ArrayList<>(references.modelIds().size());
        for (String modelId : references.modelIds()) {
            ModelCommitContext.Entry entry = context.entry(modelId);
            if (entry == null) {
                throw new IllegalStateException(
                        "Model context does not contain '%s' required by graph collection parameter %s"
                                .formatted(modelId, parameter));
            }
            result.add(Graphs.lazy(entry.entity(), context, repository));
        }
        return List.copyOf(result);
    }

    private static Entity<?> resolveEntity(
            Object input,
            HandlerPlan plan,
            ModelMetadata.ModelParameter parameter) {
        Optional<ModelCommitContext> commitContext =
                commitContext(input);
        if (commitContext.isPresent()) {
            return commitContext.get().resolve(
                    parameter.modelType(),
                    parameter.associationProperty());
        }
        return input instanceof DeserializingMessage message
                ? context(message, plan).resolve(
                        parameter.modelType(),
                        parameter.associationProperty())
                : null;
    }

    private static boolean nullDirectReference(
            Object input,
            ModelMetadata.ModelParameter parameter) {
        if (parameter.collectionWrapped()) {
            return false;
        }
        DeserializingMessage message = input instanceof DeserializingMessage direct
                ? direct : DeserializingMessage.getOptionally().orElse(null);
        if (message == null) {
            return false;
        }
        ModelTargetResolver.DirectModelReference reference =
                directReference(message, parameter);
        return reference.present() && reference.modelId() == null;
    }

    private static ModelTargetResolver.DirectModelReferences directReferences(
            Object input,
            ModelMetadata.ModelParameter parameter) {
        DeserializingMessage message = input instanceof DeserializingMessage direct
                ? direct : DeserializingMessage.getOptionally().orElse(null);
        if (message == null) {
            return new ModelTargetResolver.DirectModelReferences(false, List.of());
        }
        return cache(message).collectionReferences.computeIfAbsent(
                parameter, ignored -> computeDirectReferences(message, parameter));
    }

    private static ModelTargetResolver.DirectModelReferences computeDirectReferences(
            DeserializingMessage message,
            ModelMetadata.ModelParameter parameter) {
        String association = parameter.associationProperty();
        if (association != null
            && !parameter.associationExcludeMetadata()
            && message.getMetadata() != null
            && message.getMetadata().containsKey(association)) {
            Object metadataValue = message.getMetadata().get(association);
            if (metadataValue == null) {
                return new ModelTargetResolver.DirectModelReferences(true, List.of());
            }
            if (!(metadataValue instanceof java.util.Collection<?> collection)) {
                throw new IllegalArgumentException(
                        "Metadata property '%s' must contain a model ID collection, but found %s"
                                .formatted(association, metadataValue.getClass().getName()));
            }
            List<String> ids = new java.util.ArrayList<>(collection.size());
            for (Object id : collection) {
                if (id == null) {
                    throw new IllegalArgumentException(
                            "Metadata property '%s' contains a null model ID".formatted(association));
                }
                ids.add(id.toString());
            }
            return new ModelTargetResolver.DirectModelReferences(true, ids);
        }
        return ModelTargetResolver.resolveDirectModelReferences(
                message.getPayload(), parameter.modelType(), association);
    }

    private static Optional<ModelCommitContext>
            commitContext(Object input) {
        if (input instanceof DeserializingMessage message) {
            Optional<ModelCommitContext> direct =
                    message.getContext(ModelCommitContext.class);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return DeserializingMessage.getOptionally()
                .flatMap(message -> message.getContext(
                        ModelCommitContext.class));
    }

    private static ModelCommitContext context(
            DeserializingMessage message,
            HandlerPlan plan) {
        return resolvedPlan(message, plan).orElseThrow()
                .context(message);
    }

    private static Optional<ResolvedHandlerPlan>
            resolvedPlan(
                    DeserializingMessage message,
                    HandlerPlan plan) {
        if (!supportsBoundary(message)) {
            return Optional.empty();
        }
        return cache(message).plans
                .computeIfAbsent(
                        plan.executable(),
                        ignored ->
                                plan.resolve(message));
    }

    private static boolean supportsBoundary(
            DeserializingMessage message) {
        if (message.getMessageType()
            != MessageType.EVENT
            && message.getMessageType()
               != MessageType.NOTIFICATION) {
            return true;
        }
        return message.getIndex() != null
               || message.getMetadata() != null
               && message.getMetadata().containsKey(
                ModelEventMetadata.COMMIT_ID)
               && message.getMetadata().containsKey(
                ModelEventMetadata.SUBSTEP);
    }

    private static ResolutionCache cache(
            DeserializingMessage message) {
        return message.computeContextIfAbsent(
                ResolutionCache.class,
                ignored -> new ResolutionCache());
    }

    private static ModelRepository currentRepository(
            DeserializingMessage message) {
        return Fluxzero.get().modelRepository()
                .forNamespace(
                        getConsumerNamespace(message));
    }

    private static HandlerPlan plan(
            Executable executable) {
        return ReflectionUtils.getTypeMetadata(
                        executable.getDeclaringClass())
                .specializedMetadata(
                        HandlerPlans.class,
                        HandlerPlans::new)
                .get(executable);
    }

    private record HandlerPlan(
            Executable executable,
            Map<Parameter, ModelMetadata.ModelParameter>
                    parameters) {

        private static HandlerPlan inspect(
                Executable executable) {
            LinkedHashMap<Parameter,
                    ModelMetadata.ModelParameter> parameters =
                    new LinkedHashMap<>();
            for (Parameter parameter :
                    executable.getParameters()) {
                ModelMetadata
                        .inspectModelParameter(parameter)
                        .ifPresent(modelParameter ->
                                           parameters.put(
                                                   parameter,
                                                   modelParameter));
            }
            return new HandlerPlan(
                    executable,
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    parameters)));
        }

        private boolean hasModels() {
            return !parameters.isEmpty();
        }

        private Optional<ResolvedHandlerPlan> resolve(
                DeserializingMessage message) {
            LinkedHashMap<String, ModelTargetResolver.ResolvedModel> targets =
                    new LinkedHashMap<>();
            LinkedHashSet<ModelTargetResolver
                    .AncestorDependency> ancestors =
                    new LinkedHashSet<>();
            boolean resolvedEmptyCollection = false;
            for (ModelMetadata.ModelParameter parameter : parameters.values()) {
                if (parameter.collectionWrapped()) {
                    ModelTargetResolver.DirectModelReferences references =
                            directReferences(message, parameter);
                    if (!references.present()) {
                        continue;
                    }
                    resolvedEmptyCollection |= references.modelIds().isEmpty();
                    for (String modelId : references.modelIds()) {
                        ModelTargetResolver.merge(
                                targets,
                                new ModelTargetResolver.ResolvedModel(
                                        modelId, parameter.modelType(),
                                        ModelTargetResolver.Access.READ_ONLY,
                                        List.of(parameter.associationProperty())));
                    }
                    continue;
                }
                String association =
                        parameter.associationProperty();
                ModelTargetResolver.DirectModelReference direct =
                        directReference(message, parameter);
                if (!direct.present()) {
                    ancestors.add(
                            new ModelTargetResolver
                                    .AncestorDependency(
                                            parameter
                                                    .modelType(),
                                            association,
                                            executable
                                                    .toGenericString()));
                    continue;
                }
                if (direct.modelId() == null) {
                    continue;
                }
                ModelTargetResolver.merge(
                        targets,
                        new ModelTargetResolver.ResolvedModel(
                                direct.modelId(), parameter.modelType(),
                                ModelTargetResolver.Access.READ_ONLY,
                                List.of(association == null
                                                ? ModelMetadata.validate(parameter.modelType())
                                                        .entityId().orElseThrow().name()
                                                : association)));
            }
            if (!ancestors.isEmpty()) {
                ModelTargetResolver.resolveReferencedModels(message.getPayload())
                        .forEach(anchor -> ModelTargetResolver.merge(targets, anchor));
            }
            if (targets.isEmpty() && !resolvedEmptyCollection) {
                return Optional.empty();
            }
            return Optional.of(
                    new ResolvedHandlerPlan(
                            new ModelTargetResolver
                                    .Resolution(
                                            List.copyOf(targets.values()),
                                            List.of(),
                                            List.copyOf(
                                                    ancestors))));
        }

    }

    private static ModelTargetResolver.DirectModelReference directReference(
            DeserializingMessage message,
            ModelMetadata.ModelParameter parameter) {
        String association = parameter.associationProperty();
        if (association != null
            && !parameter.associationExcludeMetadata()
            && message.getMetadata() != null
            && message.getMetadata().containsKey(association)) {
            Object metadataValue = message.getMetadata().get(association);
            return new ModelTargetResolver.DirectModelReference(
                    true, metadataValue == null ? null : metadataValue.toString());
        }
        return ModelTargetResolver.resolveDirectModelReference(
                message.getPayload(), parameter.modelType(), association);
    }

    private static final class ResolvedHandlerPlan {
        private final ModelTargetResolver.Resolution resolution;
        private volatile ModelCommitContext context;

        private ResolvedHandlerPlan(
                ModelTargetResolver.Resolution resolution) {
            this.resolution = resolution;
        }

        private ModelCommitContext context(
                DeserializingMessage message) {
            ModelCommitContext result = context;
            if (result == null) {
                synchronized (this) {
                    result = context;
                    if (result == null) {
                        context = result =
                                currentRepository(message)
                                        .loadContext(resolution);
                    }
                }
            }
            return result;
        }
    }

    private static final class HandlerPlans {
        private final Map<Executable, HandlerPlan> plans =
                new ConcurrentHashMap<>();

        @SuppressWarnings("unused")
        private HandlerPlans(Class<?> ignored) {
        }

        private HandlerPlan get(
                Executable executable) {
            return plans.computeIfAbsent(
                    executable,
                    HandlerPlan::inspect);
        }
    }

    private static final class ResolutionCache {
        private final Map<Executable,
                Optional<ResolvedHandlerPlan>> plans =
                new ConcurrentHashMap<>();
        private final Map<ModelMetadata.ModelParameter,
                ModelTargetResolver.DirectModelReferences> collectionReferences =
                new ConcurrentHashMap<>();
    }
}
