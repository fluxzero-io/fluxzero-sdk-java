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
import java.util.Objects;
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
        return modelParameter == null
                ? null
                : input -> value(parameter, modelParameter,
                                 resolveEntity(input, plan, modelParameter),
                                 input, plan);
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
            for (ModelMetadata.ModelParameter parameter :
                    parameters.values()) {
                String association =
                        parameter.associationProperty();
                String modelId = directId(
                        message, parameter);
                if (modelId == null) {
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
                ModelTargetResolver.merge(
                        targets,
                        new ModelTargetResolver.ResolvedModel(
                                modelId, parameter.modelType(),
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
            if (targets.isEmpty()) {
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

        private static String directId(
                DeserializingMessage message,
                ModelMetadata.ModelParameter parameter) {
            String association =
                    parameter.associationProperty();
            if (association != null
                && !parameter
                        .associationExcludeMetadata()
                && message.getMetadata() != null) {
                Object metadataValue =
                        message.getMetadata().get(
                                association);
                if (metadataValue != null) {
                    return Objects.requireNonNull(
                            metadataValue.toString(),
                            () ->
                                    "Metadata property '"
                                    + association
                                    + "' returned a null model ID");
                }
            }
            return ModelTargetResolver
                    .resolveDirectModelId(
                            message.getPayload(),
                            parameter.modelType(),
                            association)
                    .orElse(null);
        }
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
    }
}
