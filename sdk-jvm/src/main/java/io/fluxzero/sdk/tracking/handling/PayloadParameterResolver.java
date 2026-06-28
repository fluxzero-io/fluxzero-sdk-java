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
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves handler method parameters by injecting the message payload.
 *
 * <p>This resolver matches a parameter when its declared type is assignable from the actual payload class
 * of the incoming message.
 *
 * <p>This resolver is typically used in conjunction with filters such as {@link PayloadFilter} to determine
 * handler compatibility based on payload types.
 *
 * <p>Special care is taken to allow null payloads for parameters that are declared nullable,
 * which can occur during upcasting or transformation pipelines.
 *
 * @see HasMessage#getPayload()
 * @see HasMessage#getPayloadClass()
 * @see ParameterResolver
 */
public class PayloadParameterResolver implements PreparedParameterResolver<HasMessage> {
    private static final Object UNRESOLVED_PAYLOAD = new Object();
    private static final List<String> NULLABLE_ANNOTATION_TYPES = List.of(
            "jakarta.annotation.Nullable",
            "javax.annotation.Nullable",
            "org.jetbrains.annotations.Nullable",
            "org.jspecify.annotations.Nullable",
            "io.fluxzero.sdk.common.Nullable");

    @Override
    public boolean matches(Parameter p, Annotation methodAnnotation, HasMessage value) {
        if (value instanceof ChunkedDeserializingMessage && InputStream.class.isAssignableFrom(p.getType())) {
            return true;
        }
        return p.getType().isAssignableFrom(value.getPayloadClass());
    }

    @Override
    public boolean matches(ParameterView p, Annotation methodAnnotation, HasMessage value) {
        return p.type()
                .map(type -> {
                    if (value instanceof ChunkedDeserializingMessage && InputStream.class.isAssignableFrom(type)) {
                        return true;
                    }
                    return type.isAssignableFrom(value.getPayloadClass());
                })
                .orElseGet(() -> typeName(value.getPayloadClass()).equals(p.typeName()));
    }

    @Override
    public Function<HasMessage, Object> resolve(Parameter p, Annotation methodAnnotation) {
        if (InputStream.class.isAssignableFrom(p.getType())) {
            return m -> m.getPayloadAs(p.getParameterizedType());
        }
        return HasMessage::getPayload;
    }

    @Override
    public Function<HasMessage, Object> resolve(ParameterView p, Annotation methodAnnotation) {
        return p.parameter().map(parameter -> resolve(parameter, methodAnnotation))
                .orElseGet(() -> p.type()
                        .filter(InputStream.class::isAssignableFrom)
                        .<Function<HasMessage, Object>>map(type -> m -> m.getPayloadAs(type))
                        .orElse(HasMessage::getPayload));
    }

    @Override
    public Function<HasMessage, Object> resolveIfPossible(
            Parameter parameter, Annotation methodAnnotation, HasMessage value) {
        if (!matches(parameter, methodAnnotation, value)) {
            return null;
        }
        if (InputStream.class.isAssignableFrom(parameter.getType())) {
            return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
        }
        if (value instanceof ChunkedDeserializingMessage) {
            return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
        }
        Object payload = getPayloadIfAvailable(value);
        if (payload != UNRESOLVED_PAYLOAD) {
            return payload != null || isNullable(parameter) ? ignored -> payload : null;
        }
        return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
    }

    @Override
    public Function<HasMessage, Object> resolveIfPossible(
            ParameterView parameter, Annotation methodAnnotation, HasMessage value) {
        if (!matches(parameter, methodAnnotation, value)) {
            return null;
        }
        if (parameter.type().filter(InputStream.class::isAssignableFrom).isPresent()) {
            return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
        }
        if (value instanceof ChunkedDeserializingMessage) {
            return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
        }
        Object payload = getPayloadIfAvailable(value);
        if (payload != UNRESOLVED_PAYLOAD) {
            return payload != null || isNullable(parameter) ? ignored -> payload : null;
        }
        return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
    }

    @Override
    public boolean test(HasMessage message, Parameter parameter) {
        if (message instanceof ChunkedDeserializingMessage) {
            return message.getPayloadClass() != Void.class || isNullable(parameter);
        }
        Object payload = getPayloadIfAvailable(message);
        if (payload != UNRESOLVED_PAYLOAD) {
            return payload != null || isNullable(parameter);
        }
        return message.getPayloadClass() != Void.class || isNullable(parameter);
    }

    @Override
    public boolean test(HasMessage message, ParameterView parameter) {
        if (message instanceof ChunkedDeserializingMessage) {
            return message.getPayloadClass() != Void.class || isNullable(parameter);
        }
        Object payload = getPayloadIfAvailable(message);
        if (payload != UNRESOLVED_PAYLOAD) {
            return payload != null || isNullable(parameter);
        }
        return message.getPayloadClass() != Void.class || isNullable(parameter);
    }

    private Object getPayloadIfAvailable(HasMessage message) {
        return message instanceof DeserializingMessage deserializingMessage && !deserializingMessage.isDeserialized()
                ? UNRESOLVED_PAYLOAD : message.getPayload();
    }

    private boolean isNullable(ParameterView parameter) {
        return parameter.parameter()
                .map(PayloadParameterResolver::isNullable)
                .orElseGet(() -> NULLABLE_ANNOTATION_TYPES.stream().anyMatch(typeName -> hasAnnotation(parameter, typeName)));
    }

    private static boolean isNullable(Parameter parameter) {
        return Arrays.stream(parameter.getAnnotations())
                .map(Annotation::annotationType)
                .anyMatch(PayloadParameterResolver::isNullableAnnotation);
    }

    private static boolean hasAnnotation(ParameterView parameter, String annotationTypeName) {
        return annotationType(annotationTypeName)
                .flatMap(parameter::annotation)
                .isPresent();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<? extends Annotation>> annotationType(String annotationTypeName) {
        try {
            Class<?> type = Class.forName(annotationTypeName);
            return Annotation.class.isAssignableFrom(type)
                    ? Optional.of((Class<? extends Annotation>) type) : Optional.empty();
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isNullableAnnotation(Class<? extends Annotation> annotationType) {
        return annotationType.getSimpleName().equals("Nullable") || annotationType.getName().equals("Nullable");
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    /**
     * Indicates that this resolver contributes to disambiguating handler methods when multiple handlers are present in
     * the same target class.
     *
     * <p>This is useful when more than one method matches a message, and the framework must
     * decide which method is more specific. If this returns {@code true}, the resolver's presence and compatibility
     * with the parameter may influence which handler is selected.
     *
     * @return true, signaling that this resolver helps determine method specificity
     */
    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    @Override
    public int specificityPriority() {
        return -100;
    }
}
