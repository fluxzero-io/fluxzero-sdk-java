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
public class MessageParameterResolver implements ParameterResolver<Object> {

    @Override
    public Function<Object, Object> resolve(Parameter p, Annotation methodAnnotation) {
        if (DeserializingMessage.class.isAssignableFrom(p.getType())) {
            return m -> m instanceof DeserializingMessage ? m : DeserializingMessage.getCurrent();
        }
        if (SerializedMessage.class.isAssignableFrom(p.getType())) {
            return m -> {
                var dm = m instanceof DeserializingMessage dem ? dem : DeserializingMessage.getCurrent();
                return dm.getSerializedObject();
            };
        }
        if (HasMessage.class.isAssignableFrom(p.getType())) {
            return m -> {
                var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                return dm.toMessage();
            };
        }
        return null;
    }

    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, Object value) {
        return HasMessage.class.isAssignableFrom(parameter.getType())
               || SerializedMessage.class.isAssignableFrom(parameter.getType());
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
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