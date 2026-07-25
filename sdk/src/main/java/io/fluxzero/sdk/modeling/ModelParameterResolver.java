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

import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.isNullable;

/**
 * Injects model values and {@link Entity} wrappers from the current {@link ModelActionContext}.
 */
final class ModelParameterResolver implements PreparedParameterResolver<Object> {

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        return ModelMetadata.of(method.getDeclaringClass()).handlerMethods().stream()
                .filter(handler -> handler.executable().equals(method))
                .anyMatch(handler -> !handler.modelParameters().isEmpty());
    }

    @Override
    public Function<Object, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
        ModelMetadata.ModelParameter modelParameter = modelParameter(parameter).orElse(null);
        return modelParameter == null ? null : input -> {
            Entity<?> entity = resolve(input, modelParameter);
            return entity == null || modelParameter.entityWrapped() ? entity : entity.get();
        };
    }

    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, Object input) {
        ModelMetadata.ModelParameter modelParameter = modelParameter(parameter).orElse(null);
        if (modelParameter == null) {
            return false;
        }
        Entity<?> entity = resolve(input, modelParameter);
        return entity != null
               && (modelParameter.entityWrapped() || entity.get() != null || isNullable(parameter));
    }

    @Override
    public Function<Object, Object> resolveIfPossible(
            Parameter parameter, Annotation methodAnnotation, Object input) {
        ModelMetadata.ModelParameter modelParameter = modelParameter(parameter).orElse(null);
        if (modelParameter == null) {
            return null;
        }
        Entity<?> entity = resolve(input, modelParameter);
        if (entity == null
            || !modelParameter.entityWrapped() && entity.get() == null && !isNullable(parameter)) {
            return null;
        }
        Object value = modelParameter.entityWrapped() ? entity : entity.get();
        return ignored -> value;
    }

    private static Optional<ModelMetadata.ModelParameter> modelParameter(Parameter parameter) {
        return ModelMetadata.of(parameter.getDeclaringExecutable().getDeclaringClass()).modelParameter(parameter);
    }

    private static Entity<?> resolve(
            Object input, ModelMetadata.ModelParameter modelParameter) {
        ModelActionContext context = context(input).orElse(null);
        return context == null ? null : context.resolve(
                modelParameter.modelType(), modelParameter.associationProperty());
    }

    private static Optional<ModelActionContext> context(Object input) {
        if (input instanceof DeserializingMessage message) {
            Optional<ModelActionContext> direct = message.getContext(ModelActionContext.class);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return DeserializingMessage.getOptionally()
                .flatMap(message -> message.getContext(ModelActionContext.class));
    }
}
