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

package io.fluxzero.sdk.tracking.handling;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.function.Function;

/**
 * A {@link ParameterResolver} that converts the message payload to the parameter's declared type
 * if that type is assignable to {@link JsonNode}.
 * <p>
 * This resolver supports handler parameters that accept JSON payloads directly as {@code JsonNode}
 * instances. When matched, it uses {@link HasMessage#getPayloadAs(java.lang.reflect.Type)} to
 * deserialize the message payload into the declared parameter type.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * @HandleCommand(allowedClasses = CreateProject.class)
 * void handle(JsonNode command) {
 *     // The message payload is automatically converted to a JsonNode instance
 * }
 * }</pre>
 *
 * <p>
 * This resolver only applies to handler methods or constructors that declare at least one parameter
 * of a type assignable to {@link JsonNode}. Additionally, the format of the message payload must be
 * JSON, as indicated by {@link Data#JSON_FORMAT}.
 * </p>
 *
 * @see ParameterResolver
 * @see HasMessage#getPayloadAs(java.lang.reflect.Type)
 * @see Data#JSON_FORMAT
 */
public class JsonPayloadParameterResolver implements ParameterResolver<DeserializingMessage> {

    /**
     * Returns a function that converts the message payload to the expected parameter type
     * using {@link HasMessage#getPayloadAs(java.lang.reflect.Type)}.
     *
     * @param parameter        the handler method parameter to resolve
     * @param methodAnnotation the handler method annotation, if present
     * @return a function that produces the converted JSON payload value
     */
    @Override
    public Function<DeserializingMessage, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
        return m -> m.getPayloadAs(parameter.getParameterizedType());
    }

    /**
     * Determines whether this resolver matches the given parameter.
     * <p>
     * A parameter is considered a match if it is assignable to {@link JsonNode}
     * and the payload format of the message equals {@link Data#JSON_FORMAT}.
     * </p>
     */
    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, DeserializingMessage value) {
        return JsonNode.class.isAssignableFrom(parameter.getType())
               && Data.JSON_FORMAT.equals(value.getSerializedObject().getData().getFormat());
    }

    /**
     * Indicates whether this resolver may apply to the given method.
     * <p>
     * This implementation returns {@code true} if any of the method's parameters
     * are assignable to {@link JsonNode}.
     * </p>
     */
    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
            if (JsonNode.class.isAssignableFrom(parameter.getType())) {
                return true;
            }
        }
        return false;
    }
}
