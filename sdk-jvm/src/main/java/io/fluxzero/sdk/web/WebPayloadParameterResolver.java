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

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AllArgsConstructor;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
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
 * @see HandleWeb
 */
@AllArgsConstructor
public class WebPayloadParameterResolver implements ParameterResolver<HasMessage> {
    private static final MetadataExecutableAnnotationResolver ANNOTATION_RESOLVER =
            MetadataExecutableAnnotationResolver.create();
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
    public Function<HasMessage, Object> resolve(ParameterView p, Annotation methodAnnotation) {
        Optional<Parameter> reflectionParameter = p.parameter();
        if (reflectionParameter.isPresent()) {
            return resolve(reflectionParameter.orElseThrow(), methodAnnotation);
        }
        return p.genericType().<Function<HasMessage, Object>>map(type -> m -> {
            Object payload = resolvePayload(m, p, type);
            if (payload != null) {
                if (validatePayload) {
                    assertValid(payload);
                }
                if (authoriseUser) {
                    assertAuthorized(payload.getClass(), User.getCurrent());
                }
            }
            return payload;
        }).orElse(null);
    }

    @Override
    public boolean test(HasMessage m, Parameter p) {
        if (authoriseUser && !hasStreamingPayload(m)) {
            Object payload = resolvePayload(m, p, p.getParameterizedType());
            if (payload != null && ignoreSilently(payload.getClass(), User.getCurrent())) {
                return false;
            }
        }
        return ParameterResolver.super.test(m, p);
    }

    @Override
    public boolean test(HasMessage m, ParameterView p) {
        Optional<Parameter> reflectionParameter = p.parameter();
        if (reflectionParameter.isPresent()) {
            return test(m, reflectionParameter.orElseThrow());
        }
        if (authoriseUser && !hasStreamingPayload(m)) {
            Object payload = p.genericType().map(type -> resolvePayload(m, p, type)).orElse(null);
            if (payload != null && ignoreSilently(payload.getClass(), User.getCurrent())) {
                return false;
            }
        }
        return ParameterResolver.super.test(m, p);
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
        return isWebHandlerAnnotation(methodAnnotation);
    }

    @Override
    public boolean matches(ParameterView parameter, Annotation methodAnnotation, HasMessage value) {
        return isWebHandlerAnnotation(methodAnnotation);
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        var metadata = ANNOTATION_RESOLVER.getAnnotation(method, HandleWeb.class);
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.isPresent();
        }
        return JvmComponentIntrospector.getInstance().isExecutableAnnotationPresent(method, HandleWeb.class);
    }

    @Override
    public boolean mayApply(ExecutableView method, Class<?> targetClass) {
        var metadata = ANNOTATION_RESOLVER.getAnnotation(method, HandleWeb.class);
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.isPresent();
        }
        return method.executable()
                .map(executable -> JvmComponentIntrospector.getInstance()
                        .isExecutableAnnotationPresent(executable, HandleWeb.class))
                .orElseGet(() -> method.annotation(HandleWeb.class).isPresent());
    }

    Object resolvePayload(HasMessage message, Parameter parameter, Type type) {
        if (message instanceof DeserializingMessage m && shouldBindFormPayload(m, parameter)) {
            var formObject = DefaultWebRequestContext.getWebRequestContext(m).formObject();
            return formObject.isEmpty() ? null : JsonUtils.convertValue(formObject, type);
        }
        return message.getPayloadAs(type);
    }

    Object resolvePayload(HasMessage message, ParameterView parameter, Type type) {
        Optional<Parameter> reflectionParameter = parameter.parameter();
        if (reflectionParameter.isPresent()) {
            return resolvePayload(message, reflectionParameter.orElseThrow(), type);
        }
        if (message instanceof DeserializingMessage m && shouldBindFormPayload(m, parameter)) {
            var formObject = DefaultWebRequestContext.getWebRequestContext(m).formObject();
            return formObject.isEmpty() ? null : JsonUtils.convertValue(formObject, type);
        }
        return message.getPayloadAs(type);
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

    boolean shouldBindFormPayload(DeserializingMessage message, ParameterView parameter) {
        return parameter.type().map(type -> shouldBindFormPayload(message, type)).orElse(false);
    }

    private boolean shouldBindFormPayload(DeserializingMessage message, Class<?> type) {
        String contentType = WebRequest.getHeader(message.getMetadata(), "Content-Type").orElse(null);
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

    private static boolean isWebHandlerAnnotation(Annotation annotation) {
        return annotation != null
               && (annotation.annotationType().equals(HandleWeb.class)
                   || annotation.annotationType().isAnnotationPresent(HandleWeb.class));
    }
}
