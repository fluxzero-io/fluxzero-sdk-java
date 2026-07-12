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

import io.fluxzero.common.handling.KeyedParameterResolver;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInputResolver;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
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
public class PayloadParameterResolver
        implements PreparedParameterResolver<HasMessage>, KeyedParameterResolver<HasMessage>,
                   HandlerInputResolver<HasMessage> {
    private static final Object UNRESOLVED_PAYLOAD = new Object();
    private static final ClassValue<PayloadShape> nullPayloadKeys = new ClassValue<>() {
        @Override
        protected PayloadShape computeValue(Class<?> type) {
            return new PayloadShape(type, false);
        }
    };
    private static final ClassValue<PayloadShape> chunkedPayloadKeys = new ClassValue<>() {
        @Override
        protected PayloadShape computeValue(Class<?> type) {
            return new PayloadShape(type, true);
        }
    };

    @Override
    public boolean matches(Parameter p, Annotation methodAnnotation, HasMessage value) {
        if (value instanceof ChunkedDeserializingMessage && InputStream.class.isAssignableFrom(p.getType())) {
            return true;
        }
        return p.getType().isAssignableFrom(value.getPayloadClass());
    }

    @Override
    public Function<HasMessage, Object> resolve(Parameter p, Annotation methodAnnotation) {
        if (InputStream.class.isAssignableFrom(p.getType())) {
            return m -> m.getPayloadAs(p.getParameterizedType());
        }
        return HasMessage::getPayload;
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
            return payload != null || ReflectionUtils.isNullable(parameter) ? ignored -> payload : null;
        }
        return test(value, parameter) ? resolve(parameter, methodAnnotation) : null;
    }

    @Override
    public Object getCacheKey(HasMessage value) {
        if (getClass() != PayloadParameterResolver.class) {
            return null;
        }
        Class<?> payloadClass = value.getPayloadClass();
        if (value instanceof ChunkedDeserializingMessage) {
            return chunkedPayloadKeys.get(payloadClass);
        }
        if (!(value instanceof DeserializingMessage message)) {
            return null;
        }
        if (!message.isDeserialized()) {
            return payloadClass;
        }
        return value.getPayload() == null ? nullPayloadKeys.get(payloadClass) : payloadClass;
    }

    @Override
    public KeyedParameterResolver.Resolution<HasMessage> resolveForKey(
            Parameter parameter, Annotation methodAnnotation, HasMessage value, Object cacheKey) {
        Class<?> payloadClass = cacheKey instanceof PayloadShape shape ? shape.payloadClass() : (Class<?>) cacheKey;
        boolean chunked = cacheKey instanceof PayloadShape shape && shape.chunked();
        boolean matches = chunked && InputStream.class.isAssignableFrom(parameter.getType())
                          || parameter.getType().isAssignableFrom(payloadClass);
        if (!matches) {
            return KeyedParameterResolver.Resolution.unmatched();
        }
        boolean nullPayload = cacheKey instanceof PayloadShape shape && !shape.chunked();
        if ((nullPayload || payloadClass == Void.class) && !ReflectionUtils.isNullable(parameter)) {
            return KeyedParameterResolver.Resolution.rejected();
        }
        return KeyedParameterResolver.Resolution.resolved(resolve(parameter, methodAnnotation));
    }

    @Override
    public Object getInputCacheKey(Parameter parameter, Annotation methodAnnotation,
                                   HandlerInput<HasMessage> representative) {
        if (getClass() != PayloadParameterResolver.class) {
            return null;
        }
        HasMessage message = representative.getMessageIfAvailable();
        if (message instanceof ChunkedDeserializingMessage) {
            return null;
        }
        Object payload = message == null ? representative.getPayload() : message.getPayload();
        return payload == null ? null : payload.getClass();
    }

    @Override
    public boolean isPayloadClassKey(Parameter parameter, Annotation methodAnnotation,
                                     HandlerInput<HasMessage> representative) {
        return getClass() == PayloadParameterResolver.class
               && !(representative.getMessageIfAvailable() instanceof ChunkedDeserializingMessage)
               && representative.getPayload() != null;
    }

    @Override
    public boolean isNoMatchPayloadClassKey(Parameter parameter, Annotation methodAnnotation,
                                            HandlerInput<HasMessage> representative) {
        return isPayloadClassKey(parameter, methodAnnotation, representative);
    }

    @Override
    public HandlerInputResolver.Resolution<HasMessage> prepareInput(
            Parameter parameter, Annotation methodAnnotation, HandlerInput<HasMessage> representative) {
        HasMessage message = representative.getMessageIfAvailable();
        if (getClass() != PayloadParameterResolver.class
            || message instanceof ChunkedDeserializingMessage) {
            return null;
        }
        Object payload = message == null ? representative.getPayload() : message.getPayload();
        Class<?> payloadClass = payload == null ? Void.class : payload.getClass();
        if (!parameter.getType().isAssignableFrom(payloadClass)) {
            return HandlerInputResolver.Resolution.unmatched();
        }
        if ((payload == null || payloadClass == Void.class) && !ReflectionUtils.isNullable(parameter)) {
            return HandlerInputResolver.Resolution.rejected();
        }
        return HandlerInputResolver.Resolution.resolved(HandlerInput::getPayload);
    }

    @Override
    public boolean test(HasMessage message, Parameter parameter) {
        if (message instanceof ChunkedDeserializingMessage) {
            return message.getPayloadClass() != Void.class || ReflectionUtils.isNullable(parameter);
        }
        Object payload = getPayloadIfAvailable(message);
        if (payload != UNRESOLVED_PAYLOAD) {
            return payload != null || ReflectionUtils.isNullable(parameter);
        }
        return message.getPayloadClass() != Void.class || ReflectionUtils.isNullable(parameter);
    }

    private Object getPayloadIfAvailable(HasMessage message) {
        return message instanceof DeserializingMessage deserializingMessage && !deserializingMessage.isDeserialized()
                ? UNRESOLVED_PAYLOAD : message.getPayload();
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

    private record PayloadShape(Class<?> payloadClass, boolean chunked) {
    }
}
