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

import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Function;

/**
 * The TimeParameterResolver class implements the ParameterResolver interface to resolve parameters of type
 * {@link Instant} and {@link Clock} in message handler methods.
 * <p>
 * This resolver is capable of injecting values into handler method parameters based on the message being processed.
 * <p>
 * It supports two types of parameters: 1. {@link Instant}: Resolved to the timestamp of the current message. 2.
 * {@link Clock}: Resolved to a fixed Clock instance with the current message's timestamp in UTC.
 */
public class TimeParameterResolver implements ParameterResolver<Object> {

    @Override
    public Function<Object, Object> resolve(Parameter p, Annotation methodAnnotation) {
        if (Instant.class.isAssignableFrom(p.getType())) {
            return m -> {
                var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                return dm.getTimestamp();
            };
        }
        if (Clock.class.isAssignableFrom(p.getType())) {
            return m -> {
                var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                return Clock.fixed(dm.getTimestamp(), ZoneOffset.UTC);
            };
        }
        return null;
    }

    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, Object value) {
        return Instant.class.isAssignableFrom(parameter.getType())
               || Clock.class.isAssignableFrom(parameter.getType());
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
            if (Instant.class.isAssignableFrom(parameter.getType())) {
                return true;
            }
            if (Clock.class.isAssignableFrom(parameter.getType())) {
                return true;
            }
        }
        return false;
    }
}