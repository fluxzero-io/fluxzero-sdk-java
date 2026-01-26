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
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

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

    @Override
    public Function<Object, Object> resolve(Parameter p, Annotation methodAnnotation) {
        if (Instant.class.isAssignableFrom(p.getType())) {
            return m -> {
                var dm = m instanceof HasMessage dem ? dem : DeserializingMessage.getCurrent();
                return dm == null ? Fluxzero.currentTime() : dm.getTimestamp();
            };
        }
        if (Clock.class.isAssignableFrom(p.getType())) {
            if (ReflectionUtils.isOrHas(methodAnnotation, Apply.class)) {
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