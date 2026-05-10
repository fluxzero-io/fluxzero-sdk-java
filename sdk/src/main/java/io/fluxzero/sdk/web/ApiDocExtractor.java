/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
 */

package io.fluxzero.sdk.web;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.reflection.ParameterRegistry;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.authentication.User;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAllMethods;
import static io.fluxzero.common.reflection.ReflectionUtils.getPackageAndParentPackages;
import static io.fluxzero.sdk.web.WebUtils.getWebPatterns;
import static java.util.Arrays.stream;
import static java.util.stream.Stream.concat;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Extracts generic API documentation metadata from Fluxzero web handlers.
 * <p>
 * The extractor reuses the same {@link WebPattern} resolution as runtime web dispatch, including package/class/method
 * {@link Path} prefixes and optional route fragments. The resulting {@link ApiDocCatalog} is intentionally generic and
 * can be rendered to OpenAPI or another documentation format later.
 * </p>
 */
public final class ApiDocExtractor {
    private ApiDocExtractor() {
    }

    /**
     * Extracts API documentation metadata from a handler class.
     */
    public static ApiDocCatalog extract(Class<?> handlerType) {
        return extract(handlerType, null);
    }

    /**
     * Extracts API documentation metadata from a handler instance. Dynamic {@link Path} properties are resolved from
     * the given instance.
     */
    public static ApiDocCatalog extract(Object handler) {
        return extract(ReflectionUtils.asClass(handler), handler instanceof Class<?> ? null : handler);
    }

    /**
     * Extracts API documentation metadata from a handler class and optional handler instance.
     */
    public static ApiDocCatalog extract(Class<?> handlerType, Object handler) {
        List<ApiDocEndpoint> endpoints = new ArrayList<>();
        for (Executable executable : handlerExecutables(handlerType).toList()) {
            if (!ReflectionUtils.isMethodAnnotationPresent(executable, HandleWeb.class)
                || isExcluded(handlerType, executable)
                || !isDocumented(handlerType, executable)) {
                continue;
            }
            for (WebPattern pattern : getWebPatterns(handlerType, handler, executable)) {
                for (WebRouteMatcher.RouteVariant variant : WebRouteMatcher.RouteVariants.expand(pattern.getPath())) {
                    endpoints.add(endpoint(handlerType, executable, pattern, variant.path()));
                }
            }
        }
        return new ApiDocCatalog(endpoints);
    }

    private static Stream<Executable> handlerExecutables(Class<?> handlerType) {
        return concat(getAllMethods(handlerType).stream(), stream(handlerType.getDeclaredConstructors()));
    }

    private static ApiDocEndpoint endpoint(Class<?> handlerType, Executable executable, WebPattern pattern,
                                           String path) {
        List<ApiDocParameter> parameters = parameters(executable, path);
        return new ApiDocEndpoint(
                handlerType,
                executable,
                pattern.getOrigin(),
                path,
                pattern.getMethod(),
                pattern.isAutoHead(),
                pattern.isAutoOptions(),
                documentation(handlerType, executable),
                parameters,
                requestBodies(executable),
                responseType(executable),
                responses(handlerType, executable));
    }

    private static List<ApiDocParameter> parameters(Executable executable, String path) {
        List<ApiDocParameter> parameters = new ArrayList<>();
        Set<String> pathParameterNames = extractPathParameterNames(path);
        for (String pathParameter : pathParameterNames) {
            parameters.add(new ApiDocParameter(pathParameter, WebParameterSource.PATH, String.class, null));
        }
        for (Parameter parameter : executable.getParameters()) {
            webParam(parameter).ifPresent(param -> {
                String name = parameterName(parameter, param.annotationValue());
                if (param.source() != WebParameterSource.PATH || pathParameterNames.contains(name)) {
                    addOrReplace(parameters,
                                 new ApiDocParameter(name, param.source(), parameter.getParameterizedType(),
                                                     parameter));
                }
            });
        }
        return parameters;
    }

    private static List<ApiDocRequestBody> requestBodies(Executable executable) {
        List<ApiDocRequestBody> requestBodies = new ArrayList<>();
        for (Parameter parameter : executable.getParameters()) {
            if (webParam(parameter).isEmpty() && !isFrameworkParameter(parameter.getType())) {
                requestBodies.add(new ApiDocRequestBody(parameter.getParameterizedType(), parameter));
            }
        }
        return requestBodies;
    }

    private static void addOrReplace(List<ApiDocParameter> parameters, ApiDocParameter parameter) {
        for (int i = 0; i < parameters.size(); i++) {
            ApiDocParameter existing = parameters.get(i);
            if (Objects.equals(existing.name(), parameter.name()) && existing.source() == parameter.source()) {
                parameters.set(i, parameter);
                return;
            }
        }
        parameters.add(parameter);
    }

    private static Type responseType(Executable executable) {
        return executable instanceof Method method ? method.getGenericReturnType() : Void.TYPE;
    }

    private static ApiDocDetails documentation(Class<?> handlerType, Executable executable) {
        DocumentationBuilder builder = new DocumentationBuilder();
        packages(handlerType).forEach(p -> builder.apply(p.getAnnotation(ApiDoc.class)));
        builder.apply(handlerType.getAnnotation(ApiDoc.class));
        builder.apply(executable.getAnnotation(ApiDoc.class));
        return builder.build();
    }

    private static List<ApiDocResponseDescriptor> responses(Class<?> handlerType, Executable executable) {
        Map<Integer, ApiDocResponseDescriptor> responses = new LinkedHashMap<>();
        packages(handlerType).forEach(p -> addResponses(responses, p.getAnnotationsByType(ApiDocResponse.class)));
        addResponses(responses, handlerType.getAnnotationsByType(ApiDocResponse.class));
        addResponses(responses, executable.getAnnotationsByType(ApiDocResponse.class));
        return new ArrayList<>(responses.values());
    }

    private static void addResponses(Map<Integer, ApiDocResponseDescriptor> target, ApiDocResponse[] responses) {
        for (ApiDocResponse response : responses) {
            target.put(response.status(),
                       new ApiDocResponseDescriptor(response.status(), response.description(), response.ref(),
                                                    response.type(), response.contentType()));
        }
    }

    private static boolean isExcluded(Class<?> handlerType, Executable executable) {
        return packages(handlerType).anyMatch(p -> p.isAnnotationPresent(ApiDocExclude.class))
               || handlerType.isAnnotationPresent(ApiDocExclude.class)
               || executable.isAnnotationPresent(ApiDocExclude.class);
    }

    private static boolean isDocumented(Class<?> handlerType, Executable executable) {
        return packages(handlerType).anyMatch(p -> p.isAnnotationPresent(ApiDoc.class))
               || handlerType.isAnnotationPresent(ApiDoc.class)
               || executable.isAnnotationPresent(ApiDoc.class);
    }

    private static Stream<Package> packages(Class<?> handlerType) {
        return getPackageAndParentPackages(handlerType.getPackage()).reversed().stream();
    }

    private static Set<String> extractPathParameterNames(String path) {
        Set<String> parameters = new LinkedHashSet<>();
        if (isBlank(path)) {
            return parameters;
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) != '{') {
                continue;
            }
            int end = parameterEnd(path, i);
            String value = path.substring(i + 1, end);
            int regexStart = value.indexOf(':');
            String name = regexStart < 0 ? value : value.substring(0, regexStart);
            if (!name.isBlank()) {
                parameters.add(name);
            }
            i = end;
        }
        return parameters;
    }

    private static int parameterEnd(String path, int start) {
        int depth = 0;
        for (int i = start; i < path.length(); i++) {
            char current = path.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Route path parameter closing delimiter '}' is missing in: " + path);
    }

    private static String parameterName(Parameter parameter, String annotationValue) {
        if (!isBlank(annotationValue)) {
            return annotationValue;
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        try {
            return ParameterRegistry.of(parameter.getDeclaringExecutable().getDeclaringClass())
                    .getParameterName(parameter);
        } catch (RuntimeException ignored) {
            return parameter.getName();
        }
    }

    private static Optional<WebParamInfo> webParam(Parameter parameter) {
        return Arrays.stream(parameter.getAnnotations())
                .map(ApiDocExtractor::webParam)
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .findFirst();
    }

    private static Optional<WebParamInfo> webParam(Annotation annotation) {
        WebParam webParam = annotation.annotationType().getAnnotation(WebParam.class);
        if (webParam == null) {
            return Optional.empty();
        }
        return Optional.of(new WebParamInfo(webParam.type(), annotationValue(annotation, webParam.value())));
    }

    private static String annotationValue(Annotation annotation, String defaultValue) {
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            return value instanceof String s ? s : defaultValue;
        } catch (ReflectiveOperationException ignored) {
            return defaultValue;
        }
    }

    private static boolean isFrameworkParameter(Class<?> type) {
        return WebRequest.class.isAssignableFrom(type)
               || WebRequestContext.class.isAssignableFrom(type)
               || SocketSession.class.isAssignableFrom(type)
               || Metadata.class.isAssignableFrom(type)
               || HasMessage.class.isAssignableFrom(type)
               || DeserializingMessage.class.isAssignableFrom(type)
               || SerializedMessage.class.isAssignableFrom(type)
               || User.class.isAssignableFrom(type)
               || Instant.class.isAssignableFrom(type)
               || Clock.class.isAssignableFrom(type);
    }

    private record WebParamInfo(WebParameterSource source, String annotationValue) {
    }

    private static class DocumentationBuilder {
        private String summary = "";
        private String description = "";
        private String operationId = "";
        private final List<String> tags = new ArrayList<>();
        private boolean deprecated;
        private final List<String> security = new ArrayList<>();

        void apply(ApiDoc apiDoc) {
            if (apiDoc == null) {
                return;
            }
            if (!isBlank(apiDoc.summary())) {
                summary = apiDoc.summary();
            }
            if (!isBlank(apiDoc.description())) {
                description = apiDoc.description();
            }
            if (!isBlank(apiDoc.operationId())) {
                operationId = apiDoc.operationId();
            }
            for (String tag : apiDoc.tags()) {
                if (!isBlank(tag) && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
            deprecated = deprecated || apiDoc.deprecated();
            for (String requirement : apiDoc.security()) {
                if (!isBlank(requirement) && !security.contains(requirement)) {
                    security.add(requirement);
                }
            }
        }

        ApiDocDetails build() {
            return new ApiDocDetails(summary, description, operationId, tags, deprecated, security);
        }
    }
}
