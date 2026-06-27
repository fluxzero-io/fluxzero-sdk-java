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

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Function;

/**
 * Resolves {@link Instant} and {@link Clock} parameters for message‑handling methods.
 *
 * <p>When a handler method declares an {@link Instant} parameter, the resolver injects the timestamp
 * of the current message if available. Otherwise, it supplies the current time via {@link Fluxzero#currentTime()}.
 * <p>
 * For a {@link Clock} parameter, it provides a clock fixed at that timestamp if the handler method is annotated with
 * {@link Apply}; otherwise it supplies the clock of {@link Fluxzero} if available or else the system UTC clock.
 *
 * @see ParameterResolver
 */
public class TimestampParameterResolver implements ParameterResolver<Object> {
    private final JvmComponentIntrospector introspector = JvmComponentIntrospector.getInstance();

    @Override
    public Function<Object, Object> resolve(Parameter p, Annotation methodAnnotation) {
        if (Instant.class.isAssignableFrom(p.getType())) {
            return m -> {
                var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                return dm == null ? Fluxzero.currentTime() : dm.getTimestamp();
            };
        }
        if (Clock.class.isAssignableFrom(p.getType())) {
            if (introspector.isOrHas(methodAnnotation, Apply.class)) {
                return m -> {
                    var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                    return Clock.fixed(dm.getTimestamp(), ZoneOffset.UTC);
                };
            }
            return m -> Fluxzero.currentClock();
        }
        return null;
    }

    @Override
    public Function<Object, Object> resolve(ParameterView p, Annotation methodAnnotation) {
        return p.type().map(type -> resolve(type, methodAnnotation))
                .orElseGet(() -> resolve(p.typeName(), methodAnnotation));
    }

    @Override
    public Function<Object, Object> prepare(Parameter parameter, Annotation methodAnnotation) {
        return Instant.class.isAssignableFrom(parameter.getType()) || Clock.class.isAssignableFrom(parameter.getType())
                ? resolve(parameter, methodAnnotation) : null;
    }

    @Override
    public Function<Object, Object> prepare(ParameterView parameter, Annotation methodAnnotation) {
        return supports(parameter) ? resolve(parameter, methodAnnotation) : null;
    }

    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, Object value) {
        return Instant.class.isAssignableFrom(parameter.getType())
               || Clock.class.isAssignableFrom(parameter.getType());
    }

    @Override
    public boolean matches(ParameterView parameter, Annotation methodAnnotation, Object value) {
        return supports(parameter);
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
            if (supports(parameter.getType())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mayApply(ExecutableView method, Class<?> targetClass) {
        for (ParameterView parameter : method.parameters()) {
            if (supports(parameter)) {
                return true;
            }
        }
        return false;
    }

    private Function<Object, Object> resolve(Class<?> parameterType, Annotation methodAnnotation) {
        if (Instant.class.isAssignableFrom(parameterType)) {
            return m -> {
                var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                return dm == null ? Fluxzero.currentTime() : dm.getTimestamp();
            };
        }
        if (Clock.class.isAssignableFrom(parameterType)) {
            if (introspector.isOrHas(methodAnnotation, Apply.class)) {
                return m -> {
                    var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                    return Clock.fixed(dm.getTimestamp(), ZoneOffset.UTC);
                };
            }
            return m -> Fluxzero.currentClock();
        }
        return null;
    }

    private Function<Object, Object> resolve(String parameterTypeName, Annotation methodAnnotation) {
        if (typeName(Instant.class).equals(parameterTypeName)) {
            return resolve(Instant.class, methodAnnotation);
        }
        if (typeName(Clock.class).equals(parameterTypeName)) {
            return resolve(Clock.class, methodAnnotation);
        }
        return null;
    }

    private boolean supports(ParameterView parameter) {
        return parameter.type().map(this::supports)
                .orElseGet(() -> typeName(Instant.class).equals(parameter.typeName())
                             || typeName(Clock.class).equals(parameter.typeName()));
    }

    private boolean supports(Class<?> parameterType) {
        return Instant.class.isAssignableFrom(parameterType) || Clock.class.isAssignableFrom(parameterType);
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }
}
