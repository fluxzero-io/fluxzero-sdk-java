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

import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AllArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertAuthorized;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertValid;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.ignoreSilently;

/**
 * Resolves a method parameter from the payload of a {@link io.fluxzero.sdk.web.WebRequest}.
 * <p>
 * This resolver is only applied to methods annotated with {@link HandleWeb} or any of its meta-annotations (e.g.
 * {@link HandlePost}). It converts the deserialized payload to the method parameter's declared type.
 * <p>
 * Optionally, the resolver can enforce validation and authorization:
 * <ul>
 *   <li>If {@code validatePayload} is {@code true}, the resolved payload is validated using {@code assertValid()}.</li>
 *   <li>If {@code authoriseUser} is {@code true}, the current {@link User} must be authorized to execute the payload’s type via {@code assertAuthorized()}.</li>
 * </ul>
 *
 * @see HandleWeb
 */
@AllArgsConstructor
public class WebPayloadParameterResolver implements ParameterResolver<HasMessage> {
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
            optimizeForStreamingHandler(m, p);
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
    public boolean test(HasMessage m, Parameter p) {
        if (m instanceof ChunkedDeserializingMessage) {
            return !authoriseUser || !ignoreSilently(p.getType(), User.getCurrent());
        }
        if (authoriseUser) {
            Object payload = resolvePayload(m, p, p.getParameterizedType());
            if (payload != null && ignoreSilently(payload.getClass(), User.getCurrent())) {
                return false;
            }
        }
        return ParameterResolver.super.test(m, p);
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

    protected void optimizeForStreamingHandler(HasMessage message, Parameter parameter) {
        if (message instanceof ChunkedDeserializingMessage chunked
            && chunked.getMessageType() == io.fluxzero.common.MessageType.WEBREQUEST
            && isStreamingOnlyWebHandler(parameter.getDeclaringExecutable())) {
            chunked.enableStreamingOnlyMode();
        }
    }

    Object resolvePayload(HasMessage message, Parameter parameter, Type type) {
        if (message instanceof DeserializingMessage m && shouldBindFormPayload(m, parameter)) {
            return DefaultWebRequestContext.getWebRequestContext(m).formObject()
                    .isEmpty() ? null : JsonUtils.convertValue(DefaultWebRequestContext.getWebRequestContext(m).formObject(), type);
        }
        return message.getPayloadAs(type);
    }

    boolean shouldBindFormPayload(DeserializingMessage message, Parameter parameter) {
        String contentType = WebRequest.getHeader(message.getMetadata(), "Content-Type").orElse(null);
        Class<?> type = parameter.getType();
        return WebUtils.isFormContentType(contentType)
               && !type.isPrimitive()
               && !type.isArray()
               && !type.isEnum()
               && !String.class.isAssignableFrom(type)
               && !Number.class.isAssignableFrom(type)
               && !Boolean.class.isAssignableFrom(type)
               && !Character.class.isAssignableFrom(type)
               && !Collection.class.isAssignableFrom(type)
               && !java.io.InputStream.class.isAssignableFrom(type)
               && !MultipartFormPart.class.isAssignableFrom(type)
               && !type.getPackageName().startsWith("java.");
    }

    protected boolean isStreamingOnlyWebHandler(Executable method) {
        boolean hasInputStream = false;
        for (Parameter parameter : method.getParameters()) {
            if (java.io.InputStream.class.isAssignableFrom(parameter.getType())) {
                hasInputStream = true;
                continue;
            }
            if (WebRequest.class.isAssignableFrom(parameter.getType())
                || WebResponse.class.isAssignableFrom(parameter.getType())
                || DeserializingMessage.class.isAssignableFrom(parameter.getType())
                || SerializedMessage.class.isAssignableFrom(parameter.getType())
                || User.class.isAssignableFrom(parameter.getType())
                || Instant.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            Annotation webParam = Arrays.stream(parameter.getAnnotations())
                    .filter(a -> ReflectionUtils.isOrHas(a.annotationType(), WebParam.class))
                    .findFirst().orElse(null);
            if (webParam != null
                && !ReflectionUtils.isOrHas(webParam.annotationType(), BodyParam.class)
                && !ReflectionUtils.isOrHas(webParam.annotationType(), FormParam.class)) {
                continue;
            }
            return false;
        }
        return hasInputStream;
    }
}
