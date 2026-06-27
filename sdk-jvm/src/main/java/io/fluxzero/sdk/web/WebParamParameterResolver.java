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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.common.reflection.ParameterRegistry;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.sdk.web.DefaultWebRequestContext.getWebRequestContext;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Resolves method parameters in web handler methods based on meta-annotations derived from {@link WebParam}.
 *
 * <p>This resolver targets parameters in methods that handle {@link MessageType#WEBREQUEST} messages.
 * It looks for parameters annotated with a concrete annotation that is itself meta-annotated with {@code @WebParam},
 * such as {@code @PathParam}, {@code @QueryParam}, or other custom parameter annotations used in web APIs.
 *
 * <p>The resolver extracts the desired parameter value from the associated {@link WebRequestContext}
 * using the rules and source defined by the meta-annotation (e.g. from the path, query string, or headers).
 *
 * <p>To determine the parameter name to resolve, the following resolution strategy is used:
 * <ul>
 *   <li>If {@code @WebParam.value()} is set, it is used directly.</li>
 *   <li>Otherwise, if Java parameter names are available (compiled with {@code -parameters}) or if the method was compiled from Kotlin source, the parameter name is used.</li>
 *   <li>If neither is available, the name is resolved from a generated {@link ParameterRegistry} class.</li>
 * </ul>
 *
 * <p>If the parameter cannot be found in the request, {@code null} is returned.
 *
 * @see WebRequest
 * @see io.fluxzero.common.api.Metadata
 * @see WebParam
 * @see WebParameterSource
 */
@AllArgsConstructor
public class WebParamParameterResolver implements ParameterResolver<HasMessage> {

    /**
     * Resolves the parameter value from a {@link WebRequestContext} using the metadata provided by a
     * parameter annotation that is meta-annotated with {@link WebParam}.
     *
     * <p>The parameter name is determined based on the annotation value, Java reflection, or a generated
     * {@link ParameterRegistry}, and the value is extracted from the request using the declared
     * {@link WebParameterSource} (e.g. QUERY, PATH, HEADER).
     *
     * @param p the method parameter to resolve
     * @param methodAnnotation the handler method annotation (not used here)
     * @return a function that resolves the argument from the incoming {@link HasMessage}
     */
    @Override
    public Function<HasMessage, Object> resolve(Parameter p, Annotation methodAnnotation) {
        return m -> {
            WebRequestContext context = getWebRequestContext((DeserializingMessage) m);
            Optional<ParamField> field = metadataField(p)
                    .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                            ? Optional.empty()
                            : JvmComponentIntrospector.getInstance().getAnnotationAs(
                                    p, WebParam.class, ParamField.class));
            return field.map(f -> {
                        String value = f.getValue();
                        String name;
                        if (isBlank(value)) {
                            if (p.isNamePresent()) {
                                name = p.getName();
                            } else {
                                var registry = ParameterRegistry.of(p.getDeclaringExecutable().getDeclaringClass());
                                name = registry.getParameterName(p);
                            }
                        } else {
                            name = value;
                        }
                        return context.getParameter(name, f.getType());
                    }).map(v -> v.as(p.getType())).orElse(null);
        };
    }

    @Override
    public Function<HasMessage, Object> resolve(ParameterView p, Annotation methodAnnotation) {
        Optional<Parameter> reflectionParameter = p.parameter();
        if (reflectionParameter.isPresent()) {
            return resolve(reflectionParameter.orElseThrow(), methodAnnotation);
        }
        return m -> {
            WebRequestContext context = getWebRequestContext((DeserializingMessage) m);
            return metadataField(p)
                    .map(f -> {
                        String value = f.getValue();
                        String name = isBlank(value) ? p.name() : value;
                        return context.getParameter(name, f.getType());
                    })
                    .flatMap(parameterValue -> p.type().map(parameterValue::as))
                    .orElse(null);
        };
    }

    /**
     * Determines if this resolver is applicable to a given method parameter.
     *
     * <p>This returns {@code true} if:
     * <ul>
     *   <li>The incoming message is a {@link DeserializingMessage}</li>
     *   <li>The message type is {@link MessageType#WEBREQUEST}</li>
     *   <li>The parameter is annotated with an annotation that is meta-annotated with {@link WebParam}</li>
     * </ul>
     *
     * @param parameter the method parameter
     * @param methodAnnotation the enclosing method annotation (not used here)
     * @param value the message passed to the handler
     * @return {@code true} if the parameter is eligible for resolution
     */
    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, HasMessage value) {
        return value instanceof DeserializingMessage m && m.getMessageType() == MessageType.WEBREQUEST
               && hasWebParam(parameter);
    }

    @Override
    public boolean matches(ParameterView parameter, Annotation methodAnnotation, HasMessage value) {
        Optional<Parameter> reflectionParameter = parameter.parameter();
        if (reflectionParameter.isPresent()) {
            return matches(reflectionParameter.orElseThrow(), methodAnnotation, value);
        }
        return value instanceof DeserializingMessage m && m.getMessageType() == MessageType.WEBREQUEST
               && hasWebParam(parameter);
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
            if (hasWebParam(parameter)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mayApply(ExecutableView method, Class<?> targetClass) {
        Optional<Executable> reflectionMethod = method.executable();
        if (reflectionMethod.isPresent()) {
            return mayApply(reflectionMethod.orElseThrow(), targetClass);
        }
        for (ParameterView parameter : method.parameters()) {
            if (hasWebParam(parameter)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<ParamField> metadataField(Parameter parameter) {
        return ComponentMetadataLookups.lookup(parameter.getDeclaringExecutable().getDeclaringClass())
                .flatMap(lookup -> ComponentMetadataLookups.parameter(lookup, parameter))
                .flatMap(descriptor -> ComponentMetadataLookups.annotationAs(
                        descriptor.annotations(), WebParam.class, ParamField.class,
                        parameter.getDeclaringExecutable().getDeclaringClass()));
    }

    private static Optional<ParamField> metadataField(ParameterView parameter) {
        return parameter.parameter().flatMap(WebParamParameterResolver::metadataField)
                .or(() -> parameter.annotation(WebParam.class)
                        .map(annotation -> new ParamField(annotation.value(), annotation.type())));
    }

    private static boolean hasWebParam(Parameter parameter) {
        Optional<Boolean> metadataResult = ComponentMetadataLookups.lookup(
                        parameter.getDeclaringExecutable().getDeclaringClass())
                .map(lookup -> ComponentMetadataLookups.hasParameterAnnotation(lookup, parameter, WebParam.class));
        if (metadataResult.orElse(false)) {
            return true;
        }
        return !ComponentMetadataLookups.generatedOnlyMode()
               && JvmComponentIntrospector.getInstance().isAnnotationPresent(parameter, WebParam.class);
    }

    private static boolean hasWebParam(ParameterView parameter) {
        return parameter.parameter().map(WebParamParameterResolver::hasWebParam)
                .orElseGet(() -> parameter.annotation(WebParam.class).isPresent());
    }

    @Value
    static class ParamField {
        String value;
        WebParameterSource type;
    }
}
