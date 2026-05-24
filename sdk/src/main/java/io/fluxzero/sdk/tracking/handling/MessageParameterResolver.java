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

import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.function.Function;

/**
 * Resolves handler method parameters of type {@link DeserializingMessage}.
 * <p>
 * This allows handler methods to access the deserialization context, including message metadata, payload, and raw
 * serialized content.
 * <p>
 * Useful when advanced information about the message is needed, such as the Fluxzero-assigned message index.
 */
public class MessageParameterResolver implements PreparedParameterResolver<Object> {
    private static final Function<Object, Object> DESERIALIZING_MESSAGE_RESOLVER =
            m -> m instanceof DeserializingMessage ? m : DeserializingMessage.getCurrent();
    private static final Function<Object, Object> SERIALIZED_MESSAGE_RESOLVER = m -> {
        var dm = m instanceof DeserializingMessage dem ? dem : DeserializingMessage.getCurrent();
        return dm.getSerializedObject();
    };
    private static final Function<Object, Object> HAS_MESSAGE_RESOLVER = m -> {
        var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
        return dm.toMessage();
    };

    @Override
    public Function<Object, Object> resolve(Parameter p, Annotation methodAnnotation) {
        return resolve(p.getType());
    }

    @Override
    public Function<Object, Object> prepare(Parameter parameter, Annotation methodAnnotation) {
        return resolve(parameter.getType());
    }

    @Override
    public Function<Object, Object> resolveIfPossible(Parameter parameter, Annotation methodAnnotation, Object value) {
        return resolve(parameter.getType());
    }

    private Function<Object, Object> resolve(Class<?> parameterType) {
        if (DeserializingMessage.class.isAssignableFrom(parameterType)) {
            return DESERIALIZING_MESSAGE_RESOLVER;
        }
        if (SerializedMessage.class.isAssignableFrom(parameterType)) {
            return SERIALIZED_MESSAGE_RESOLVER;
        }
        if (HasMessage.class.isAssignableFrom(parameterType)) {
            return HAS_MESSAGE_RESOLVER;
        }
        return null;
    }

    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, Object value) {
        return DeserializingMessage.class.isAssignableFrom(parameter.getType())
               || HasMessage.class.isAssignableFrom(parameter.getType())
               || SerializedMessage.class.isAssignableFrom(parameter.getType());
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
            if (DeserializingMessage.class.isAssignableFrom(parameter.getType())) {
                return true;
            }
            if (HasMessage.class.isAssignableFrom(parameter.getType())) {
                return true;
            }
            if (SerializedMessage.class.isAssignableFrom(parameter.getType())) {
                return true;
            }
        }
        return false;
    }
}
