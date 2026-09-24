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

package io.fluxzero.sdk.tracking.handling;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.Graph;
import lombok.AllArgsConstructor;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Adapts typed graph handler results to an explicitly JSON-shaped {@link Request} response contract.
 *
 * <p>The interceptor is prepared as a no-op for ordinary handler return types. A handler may therefore work with a
 * typed {@link Graph}, while callers continue to receive the recursively composed JSON document declared by the
 * request. Conversion happens after content filtering and before local return or result publication, keeping local
 * and transported request behavior identical.</p>
 */
@AllArgsConstructor
public final class RequestGraphResponseInterceptor implements HandlerInterceptor {
    private final Serializer serializer;

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(
            Function<DeserializingMessage, Object> function,
            HandlerInvoker invoker) {
        return message -> convert(message.getPayload(), function.apply(message));
    }

    @Override
    public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
        if (!handlesGraph(handler)) {
            return PreparedHandlerInterceptor.noOp;
        }
        return (message, descriptor, combiner, next) -> convert(
                message.getPayload(), next.apply(message, descriptor, combiner));
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(HandlerDescriptor handler) {
        if (!handlesGraph(handler)) {
            return PreparedHandlerInputInterceptor.noOp;
        }
        return (input, descriptor, next) -> convert(
                input.getPayload(), next.apply(input, descriptor));
    }

    @Override
    public boolean supportsPreparation() {
        return true;
    }

    private Object convert(Object request, Object response) {
        if (!(request instanceof Request<?> typedRequest) || response == null) {
            return response;
        }
        if (response instanceof CompletableFuture<?> future) {
            return future.thenApply(result -> convert(request, result));
        }
        if (response instanceof Optional<?> optional) {
            return optional.map(result -> convert(request, result));
        }
        Type responseType = typedRequest.responseType();
        return requiresJsonConversion(response, responseType)
                ? serializer.convert(response, responseType) : response;
    }

    private static boolean handlesGraph(HandlerDescriptor handler) {
        return handler.getMethod() instanceof Method method
               && containsGraph(unwrap(method.getGenericReturnType()));
    }

    private static boolean requiresJsonConversion(Object value, Type expectedType) {
        if (value instanceof Graph<?>) {
            Class<?> expectedClass = rawClass(expectedType);
            return expectedClass != null && JsonNode.class.isAssignableFrom(expectedClass);
        }
        if (!(expectedType instanceof ParameterizedType parameterizedType)) {
            return false;
        }
        Type[] arguments = parameterizedType.getActualTypeArguments();
        if (value instanceof Collection<?> collection && arguments.length == 1) {
            return collection.stream().anyMatch(item -> item != null && requiresJsonConversion(item, arguments[0]));
        }
        return false;
    }

    private static Type unwrap(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> rawType = rawClass(parameterizedType);
            if (rawType != null && (Future.class.isAssignableFrom(rawType)
                                    || Optional.class.isAssignableFrom(rawType))) {
                Type[] arguments = parameterizedType.getActualTypeArguments();
                return arguments.length == 1 ? unwrap(arguments[0]) : type;
            }
        }
        return type;
    }

    private static boolean containsGraph(Type type) {
        Class<?> rawType = rawClass(type);
        if (rawType != null && Graph.class.isAssignableFrom(rawType)) {
            return true;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            if (rawType == null || !(Future.class.isAssignableFrom(rawType)
                                     || Optional.class.isAssignableFrom(rawType)
                                     || Collection.class.isAssignableFrom(rawType))) {
                return false;
            }
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (containsGraph(argument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Class<?> rawClass(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p when p.getRawType() instanceof Class<?> c -> c;
            default -> null;
        };
    }
}
