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
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.function.Function;

/** Dedicated resolver for the cold graph-change handler route. */
final class GraphChangeParameterResolver
        implements PreparedParameterResolver<DeserializingMessage> {

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        return isGraphChangeMethod(method);
    }

    @Override
    public Function<DeserializingMessage, Object> resolve(
            Parameter parameter,
            Annotation methodAnnotation) {
        return isGraphChangeParameter(parameter)
                ? ignored -> GraphChangeInvocation.graph(parameter)
                : null;
    }

    @Override
    public boolean matches(
            Parameter parameter,
            Annotation methodAnnotation,
            DeserializingMessage value) {
        return isGraphChangeParameter(parameter)
               && GraphChangeInvocation.supplies(parameter);
    }

    @Override
    public Function<DeserializingMessage, Object> resolveIfPossible(
            Parameter parameter,
            Annotation methodAnnotation,
            DeserializingMessage value) {
        return matches(parameter, methodAnnotation, value)
                ? ignored -> GraphChangeInvocation.graph(parameter)
                : null;
    }

    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    static boolean isGraphChangeMethod(Executable method) {
        if (method.getParameterCount() != 1
            || !isGraphChangeParameter(method.getParameters()[0])) {
            return false;
        }
        return ReflectionUtils.getMethodAnnotation(
                        method, HandleEvent.class).isPresent()
               || ReflectionUtils.getMethodAnnotation(
                        method, HandleNotification.class).isPresent();
    }

    private static boolean isGraphChangeParameter(
            Parameter parameter) {
        ModelMetadata.ModelParameter model = ModelMetadata
                .inspectModelParameter(parameter).orElse(null);
        return model != null
               && model.graphWrapped()
               && model.associationProperty() == null;
    }
}
