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

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Function;

import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertAuthorized;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertValid;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.ignoreSilently;

/**
 * Resolves a method parameter from the payload of a {@link io.fluxzero.sdk.web.WebRequest}.
 * <p>
 * This resolver is only applied to methods annotated with {@link HandleWeb} or any of its meta-annotations (e.g.
 * {@link HandlePost}). It converts the deserialized payload to the method parameter's declared type.
 * For raw request bodies such as {@code multipart/form-data}, this resolver can still provide the full body as
 * {@code byte[]} or {@code String}. Individual multipart fields and files are resolved via {@link FormParam}.
 * <p>
 * Optionally, the resolver can enforce validation and authorization:
 * <ul>
 *   <li>If {@code validatePayload} is {@code true}, the resolved payload is validated using {@code assertValid()}.</li>
 *   <li>If {@code authoriseUser} is {@code true}, the current {@link User} must be authorized to execute the payload’s type via {@code assertAuthorized()}.</li>
 * </ul>
 *
 * Body deserialization failures are reported during invocation rather than treated as a route mismatch.
 * The default web response mapper returns HTTP 400 without exposing internal deserialization details.
 *
 * @see HandleWeb
 */
@AllArgsConstructor
public class WebPayloadParameterResolver implements PreparedParameterResolver<HasMessage> {
    private final boolean validatePayload;
    private final boolean authoriseUser;

    /**
     * Resolves the value for the given method parameter by converting the message payload to the expected parameter
     * type. If configured, it also validates and authorizes the payload.
     *
     * @param p                the method parameter to resolve
     * @param methodAnnotation the annotation on the handler method (typically {@code @HandleWeb})
     * @return a function that resolves the parameter from a {@link HasMessage} instance
     */
    @Override
    public Function<HasMessage, Object> resolve(Parameter p, Annotation methodAnnotation) {
        return m -> {
            Object payload = resolvePayload(m, p, p.getParameterizedType());
            if (payload != null) {
                if (validatePayload) {
                    assertValid(payload);
                }
                if (authoriseUser) {
                    assertAuthorized(payload.getClass(), User.getCurrent());
                }
            }
            return payload;
        };
    }

    @Override
    public Function<HasMessage, Object> resolveIfPossible(Parameter parameter, Annotation methodAnnotation,
                                                         HasMessage message) {
        if (!matches(parameter, methodAnnotation, message)) {
            return null;
        }
        try {
            return test(message, parameter) ? resolve(parameter, methodAnnotation) : null;
        } catch (DeserializationException failure) {
            // The route still applies. Report this exact binding failure during invocation, inside the normal
            // error handling and response mapping, without attempting to deserialize the same body again.
            return ignored -> { throw failure; };
        }
    }

    @Override
    public boolean test(HasMessage m, Parameter p) {
        if (authoriseUser && !hasStreamingPayload(m)) {
            Object payload = resolvePayload(m, p, p.getParameterizedType());
            if (payload != null && ignoreSilently(payload.getClass(), User.getCurrent())) {
                return false;
            }
        }
        return PreparedParameterResolver.super.test(m, p);
    }

    private boolean hasStreamingPayload(HasMessage message) {
        return message.toMessage().getPayload() instanceof InputStream;
    }

    /**
     * Determines whether this resolver should be used for the given method parameter. This resolver is active for any
     * method annotated with {@link HandleWeb} or any annotation that is meta-annotated with it.
     *
     * @param parameter        the method parameter
     * @param methodAnnotation the annotation on the method
     * @param value            the incoming message
     * @return {@code true} if the resolver is applicable to this parameter
     */
    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, HasMessage value) {
        return ReflectionUtils.isOrHas(methodAnnotation, HandleWeb.class);
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        return ReflectionUtils.isMethodAnnotationPresent(method, HandleWeb.class);
    }

    @SneakyThrows
    Object resolvePayload(HasMessage message, Parameter parameter, Type type) {
        try {
            if (message instanceof DeserializingMessage m && shouldBindFormPayload(m, parameter)) {
                var formObject = DefaultWebRequestContext.getWebRequestContext(m).formObject();
                return formObject.isEmpty() ? null : JsonUtils.convertValue(formObject, type);
            }
            return message.getPayloadAs(type);
        } catch (Exception e) {
            // Local and form binding use Jackson directly rather than the message serializer. Keep the same
            // deserialization contract on these paths, without reclassifying unrelated application failures.
            if (e instanceof JsonProcessingException
                || e instanceof IllegalArgumentException && e.getCause() instanceof JsonProcessingException) {
                throw new DeserializationException("Could not deserialize web request body", e);
            }
            throw e;
        }
    }

    boolean shouldBindFormPayload(DeserializingMessage message, Parameter parameter) {
        String contentType = WebRequest.getHeader(message.getMetadata(), "Content-Type").orElse(null);
        Class<?> type = parameter.getType();
        return isFormContentType(contentType)
               && !type.isPrimitive()
               && !type.isArray()
               && !type.isEnum()
               && !String.class.isAssignableFrom(type)
               && !Number.class.isAssignableFrom(type)
               && !Boolean.class.isAssignableFrom(type)
               && !Character.class.isAssignableFrom(type)
               && !Collection.class.isAssignableFrom(type)
               && !InputStream.class.isAssignableFrom(type)
               && !WebFormPart.class.isAssignableFrom(type)
               && !type.getPackageName().startsWith("java.");
    }

    private static boolean isFormContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf(';');
        if (separatorIndex >= 0) {
            normalized = normalized.substring(0, separatorIndex);
        }
        normalized = normalized.trim();
        return "application/x-www-form-urlencoded".equals(normalized) || "multipart/form-data".equals(normalized);
    }
}
