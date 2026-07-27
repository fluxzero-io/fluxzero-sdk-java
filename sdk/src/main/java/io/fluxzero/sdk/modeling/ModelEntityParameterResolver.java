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
import java.util.ArrayList;
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
 * selected handler is invoked. Events and notifications require persisted model-action metadata and are reconstructed
 * at that exact action boundary; other message types use the repository's current boundary.
 */
public class ModelEntityParameterResolver
        implements PreparedParameterResolver<DeserializingMessage> {

    @Override
    public boolean mayApply(
            Executable method, Class<?> targetClass) {
        return ReflectionUtils
                .getMethodAnnotation(
                        method, HandleMessage.class)
                .isPresent()
               && plan(method).hasModels();
    }

    @Override
    public Function<DeserializingMessage, Object> resolve(
            Parameter parameter,
            Annotation methodAnnotation) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        return modelParameter == null
                ? null
                : message -> value(
                        parameter, modelParameter,
                        context(message, plan).resolve(
                                modelParameter.modelType(),
                                modelParameter
                                        .associationProperty()));
    }

    @Override
    public boolean matches(
            Parameter parameter,
            Annotation methodAnnotation,
            DeserializingMessage message) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        return plan.parameters().containsKey(
                parameter)
               && resolvedPlan(message, plan)
                       .isPresent();
    }

    @Override
    public Function<DeserializingMessage, Object>
            resolveIfPossible(
                    Parameter parameter,
                    Annotation methodAnnotation,
                    DeserializingMessage message) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        if (modelParameter == null
            || resolvedPlan(message, plan).isEmpty()) {
            return null;
        }
        return invocation -> value(
                parameter, modelParameter,
                context(invocation, plan).resolve(
                        modelParameter.modelType(),
                        modelParameter
                                .associationProperty()));
    }

    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    private static Object value(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Entity<?> entity) {
        if (modelParameter.entityWrapped()) {
            return entity;
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

    private static ModelActionContext context(
            DeserializingMessage message,
            HandlerPlan plan) {
        ResolutionCache cache = cache(message);
        return cache.contexts.computeIfAbsent(
                plan.executable(),
                ignored -> currentRepository(message)
                        .loadContext(
                                resolvedPlan(
                                        message, plan)
                                        .orElseThrow()
                                        .resolution()));
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
        return message.getMetadata() != null
               && message.getMetadata().containsKey(
                ModelEventMetadata.ACTION_ID)
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
            LinkedHashMap<String, MutableTarget> targets =
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
                String sourceProperty =
                        association == null
                                ? ModelMetadata.validate(
                                                parameter
                                                        .modelType())
                                        .entityId()
                                        .orElseThrow()
                                        .name()
                                : association;
                targets.compute(
                        modelId,
                        (ignored, existing) -> {
                            if (existing == null) {
                                return new MutableTarget(
                                        modelId,
                                        parameter.modelType(),
                                        sourceProperty);
                            }
                            existing.merge(
                                    parameter.modelType(),
                                    sourceProperty);
                            return existing;
                        });
            }
            if (!ancestors.isEmpty()) {
                for (ModelTargetResolver.ResolvedModel anchor :
                        ModelTargetResolver
                                .resolveReferencedModels(
                                        message.getPayload())) {
                    targets.compute(
                            anchor.modelId(),
                            (ignored, existing) -> {
                                if (existing == null) {
                                    MutableTarget created =
                                            new MutableTarget(
                                            anchor.modelId(),
                                            anchor.modelType(),
                                            anchor.sourceProperties()
                                                    .getFirst());
                                    anchor.sourceProperties()
                                            .stream()
                                            .skip(1)
                                            .forEach(source ->
                                                             created.merge(
                                                                     anchor.modelType(),
                                                                     source));
                                    return created;
                                }
                                anchor.sourceProperties()
                                        .forEach(source ->
                                                         existing.merge(
                                                                 anchor.modelType(),
                                                                 source));
                                return existing;
                            });
                }
            }
            if (targets.isEmpty()) {
                return Optional.empty();
            }
            List<ModelTargetResolver.ResolvedModel>
                    resolved = targets.values()
                    .stream()
                    .map(MutableTarget::freeze)
                    .toList();
            return Optional.of(
                    new ResolvedHandlerPlan(
                            new ModelTargetResolver
                                    .Resolution(
                                            resolved,
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

    private static final class MutableTarget {
        private final String modelId;
        private Class<?> modelType;
        private final List<String> sourceProperties =
                new ArrayList<>(1);

        private MutableTarget(
                String modelId, Class<?> modelType,
                String sourceProperty) {
            this.modelId = modelId;
            this.modelType = modelType;
            sourceProperties.add(sourceProperty);
        }

        private void merge(
                Class<?> requestedType,
                String sourceProperty) {
            if (!modelType.equals(requestedType)) {
                if (modelType.isAssignableFrom(
                        requestedType)) {
                    modelType = requestedType;
                } else if (!requestedType
                        .isAssignableFrom(modelType)) {
                    throw new IllegalStateException(
                            "Model ID '%s' is requested as incompatible handler parameter types %s and %s"
                                    .formatted(
                                            modelId,
                                            modelType.getName(),
                                            requestedType
                                                    .getName()));
                }
            }
            if (!sourceProperties.contains(
                    sourceProperty)) {
                sourceProperties.add(sourceProperty);
            }
        }

        private ModelTargetResolver.ResolvedModel
                freeze() {
            return new ModelTargetResolver.ResolvedModel(
                    modelId, modelType,
                    ModelTargetResolver.Access.READ_ONLY,
                    List.copyOf(sourceProperties));
        }
    }

    private record ResolvedHandlerPlan(
            ModelTargetResolver.Resolution resolution) {
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
        private final Map<Executable,
                ModelActionContext> contexts =
                new ConcurrentHashMap<>();
    }
}
