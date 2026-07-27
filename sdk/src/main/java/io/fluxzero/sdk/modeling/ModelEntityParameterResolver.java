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
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.reflection.ReflectionUtils.isNullable;
import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;

/**
 * Injects directly affected {@link Model} values and {@link Entity Entity&lt;Model&gt;} wrappers into event and
 * notification handlers.
 * <p>
 * The model ID is resolved from the handled payload using the same canonical {@link EntityId}, unique typed
 * {@link Id}{@code <Model>} and parameter-level
 * {@link io.fluxzero.sdk.tracking.handling.Association @Association("property")} rules as model applies. Injection is
 * limited to events carrying a persisted model-action boundary, so the repository reconstructs the model exactly as it
 * existed for that event rather than returning a newer current-cache value.
 */
public class ModelEntityParameterResolver implements PreparedParameterResolver<DeserializingMessage> {
    private static final Object NO_ENTITY = new Object();

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        return ReflectionUtils.getMethodAnnotation(method, HandleMessage.class)
                .map(ModelEntityParameterResolver::supports)
                .orElse(false);
    }

    private static boolean supports(Annotation annotation) {
        return annotation instanceof HandleEvent || annotation instanceof HandleNotification;
    }

    @Override
    public Function<DeserializingMessage, Object> resolve(
            Parameter parameter, Annotation methodAnnotation) {
        ModelMetadata.ModelParameter modelParameter =
                supports(methodAnnotation)
                        ? ModelMetadata.inspectModelParameter(parameter).orElse(null)
                        : null;
        if (modelParameter == null) {
            return null;
        }
        return message -> value(parameter, modelParameter, resolveEntity(message, modelParameter));
    }

    @Override
    public boolean matches(
            Parameter parameter, Annotation methodAnnotation, DeserializingMessage message) {
        ModelMetadata.ModelParameter modelParameter =
                supports(methodAnnotation)
                        ? ModelMetadata.inspectModelParameter(parameter).orElse(null)
                        : null;
        if (modelParameter == null) {
            return false;
        }
        Entity<?> entity = resolveEntity(message, modelParameter);
        return entity != null
               && (modelParameter.entityWrapped() || entity.isPresent() || isNullable(parameter));
    }

    @Override
    public Function<DeserializingMessage, Object> resolveIfPossible(
            Parameter parameter, Annotation methodAnnotation, DeserializingMessage message) {
        ModelMetadata.ModelParameter modelParameter =
                supports(methodAnnotation)
                        ? ModelMetadata.inspectModelParameter(parameter).orElse(null)
                        : null;
        if (modelParameter == null) {
            return null;
        }
        Entity<?> entity = resolveEntity(message, modelParameter);
        if (entity == null
            || !modelParameter.entityWrapped() && !entity.isPresent() && !isNullable(parameter)) {
            return null;
        }
        Object value = value(parameter, modelParameter, entity);
        return ignored -> value;
    }

    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    private Entity<?> resolveEntity(
            DeserializingMessage message,
            ModelMetadata.ModelParameter modelParameter) {
        if (!hasExactModelBoundary(message)) {
            return null;
        }
        String modelId = ModelTargetResolver.resolveDirectModelId(
                        message.getPayload(),
                        modelParameter.modelType(),
                        modelParameter.associationProperty())
                .orElse(null);
        if (modelId == null) {
            return null;
        }
        ModelResolutionKey key =
                new ModelResolutionKey(modelId, modelParameter.modelType());
        return message.computeContextIfAbsent(
                        ModelResolutionCache.class,
                        ignored -> new ModelResolutionCache())
                .get(key, () -> currentRepository(message).load(
                        modelId, modelParameter.modelType()));
    }

    private static boolean hasExactModelBoundary(DeserializingMessage message) {
        return (message.getMessageType() == MessageType.EVENT
                || message.getMessageType() == MessageType.NOTIFICATION)
               && message.getMetadata() != null
               && message.getMetadata().containsKey(ModelEventMetadata.ACTION_ID)
               && message.getMetadata().containsKey(ModelEventMetadata.SUBSTEP);
    }

    private static ModelRepository currentRepository(
            DeserializingMessage message) {
        return Fluxzero.get().modelRepository()
                .forNamespace(getConsumerNamespace(message));
    }

    private static Object value(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Entity<?> entity) {
        if (entity == null || modelParameter.entityWrapped()) {
            return entity;
        }
        Object value = entity.get();
        return value != null || isNullable(parameter) ? value : null;
    }

    private record ModelResolutionKey(String modelId, Class<?> modelType) {
    }

    private static final class ModelResolutionCache {
        private final Map<ModelResolutionKey, Object> entities =
                new ConcurrentHashMap<>();

        private Entity<?> get(
                ModelResolutionKey key, Supplier<Entity<?>> loader) {
            Object result = entities.computeIfAbsent(
                    key, ignored -> {
                        Entity<?> entity = loader.get();
                        return entity == null ? NO_ENTITY : entity;
                    });
            return result == NO_ENTITY ? null : (Entity<?>) result;
        }
    }
}
