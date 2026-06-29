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
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.reflection.ParameterRegistry;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.ParameterDescriptor;
import io.fluxzero.sdk.registry.WebRouteDescriptor;
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
        return extract(handler instanceof Class<?> type ? type : handler.getClass(),
                       handler instanceof Class<?> ? null : handler);
    }

    /**
     * Extracts API documentation metadata from a handler class and optional handler instance.
     */
    public static ApiDocCatalog extract(Class<?> handlerType, Object handler) {
        return extract(handlerType, handler, MetadataContext.of(handlerType));
    }

    static ApiDocCatalog extract(
            Class<?> handlerType, Object handler, Optional<ComponentMetadataLookup> metadataLookup) {
        return extract(handlerType, handler, new MetadataContext(handlerType, metadataLookup));
    }

    static ApiDocCatalog extractWebHandlers(
            Map<Class<?>, Object> handlers, Optional<ComponentMetadataLookup> metadataLookup) {
        List<ApiDocEndpoint> endpoints = new ArrayList<>();
        handlers.forEach((handlerType, handler) -> {
            ApiDocCatalog catalog = metadataLookup.isPresent()
                    ? extractFromMetadata(handlerType, new MetadataContext(handlerType, metadataLookup))
                    : extract(handlerType, handler);
            endpoints.addAll(catalog.endpoints());
        });
        return new ApiDocCatalog(endpoints);
    }

    private static ApiDocCatalog extract(Class<?> handlerType, Object handler, MetadataContext metadata) {
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.available() ? extractFromMetadata(handlerType, metadata) : new ApiDocCatalog(List.of());
        }
        List<ApiDocEndpoint> endpoints = new ArrayList<>();
        for (Executable executable : handlerExecutables(handlerType).toList()) {
            if (!isHandleWeb(metadata, executable)
                || isExcluded(handlerType, executable, metadata)
                || !isDocumented(handlerType, executable, metadata)) {
                continue;
            }
            for (WebPattern pattern : getWebPatterns(handlerType, handler, executable)) {
                for (WebRouteMatcher.RouteVariant variant : WebRouteMatcher.RouteVariants.expand(pattern.getPath())) {
                    endpoints.add(endpoint(handlerType, executable, pattern, variant.path(), metadata));
                }
            }
        }
        return new ApiDocCatalog(endpoints);
    }

    private static ApiDocCatalog extractFromMetadata(Class<?> handlerType, MetadataContext metadata) {
        if (metadata.lookup().isEmpty()) {
            return new ApiDocCatalog(List.of());
        }
        List<ApiDocEndpoint> endpoints = new ArrayList<>();
        ComponentMetadataLookup lookup = metadata.lookup().orElseThrow();
        for (HandlerRoute route : lookup.routes(handlerType.getName(), MessageType.WEBREQUEST)) {
            Optional<ExecutableDescriptor> executable = route.executableMetadata();
            if (executable.isEmpty()
                || route.webRoutes().isEmpty()
                || isExcluded(handlerType, executable.orElseThrow().annotations(), metadata)
                || !isDocumented(handlerType, executable.orElseThrow().annotations(), metadata)) {
                continue;
            }
            for (WebRouteDescriptor webRoute : route.webRoutes()) {
                for (String routePath : webRoute.paths()) {
                    for (WebRouteMatcher.RouteVariant variant : WebRouteMatcher.RouteVariants.expand(routePath)) {
                        for (String method : webRoute.methods()) {
                            endpoints.add(endpoint(
                                    handlerType, executable.orElseThrow(), routePath, variant.path(), method,
                                    webRoute.autoHead(), webRoute.autoOptions(), metadata));
                        }
                    }
                }
            }
        }
        return new ApiDocCatalog(endpoints);
    }

    private static Stream<Executable> handlerExecutables(Class<?> handlerType) {
        return concat(JvmCompatibilityBackend.introspector().getAllMethods(handlerType).stream(), stream(handlerType.getDeclaredConstructors()));
    }

    private static boolean isHandleWeb(MetadataContext metadata, Executable executable) {
        Optional<Boolean> metadataResult = metadata.lookup()
                .map(lookup -> ComponentMetadataLookups.hasExecutableAnnotation(lookup, executable, HandleWeb.class));
        if (metadataResult.isPresent() || (ComponentMetadataLookups.generatedOnlyMode() && metadata.available())) {
            return metadataResult.orElse(false);
        }
        return JvmCompatibilityBackend.introspector().isMethodAnnotationPresent(executable, HandleWeb.class);
    }

    private static ApiDocEndpoint endpoint(Class<?> handlerType, Executable executable, WebPattern pattern,
                                           String path, MetadataContext metadata) {
        List<ApiDocParameter> parameters = parameters(executable, path, metadata);
        return new ApiDocEndpoint(
                handlerType,
                executable,
                pattern.getOrigin(),
                path,
                pattern.getMethod(),
                pattern.isAutoHead(),
                pattern.isAutoOptions(),
                documentation(handlerType, executable, metadata),
                parameters,
                requestBodies(executable, metadata),
                responseType(executable),
                responses(handlerType, executable, metadata));
    }

    private static ApiDocEndpoint endpoint(
            Class<?> handlerType, ExecutableDescriptor executable, String origin, String path, String method,
            boolean autoHead, boolean autoOptions, MetadataContext metadata) {
        List<ApiDocParameter> parameters = parameters(executable, path, handlerType);
        return new ApiDocEndpoint(
                handlerType,
                null,
                origin,
                path,
                method,
                autoHead,
                autoOptions,
                documentation(handlerType, executable.annotations(), metadata),
                parameters,
                requestBodies(executable, handlerType),
                type(executable.returnTypeName()),
                responses(handlerType, executable.annotations(), metadata),
                executable);
    }

    private static List<ApiDocParameter> parameters(Executable executable, String path, MetadataContext metadata) {
        List<ApiDocParameter> parameters = new ArrayList<>();
        Set<String> pathParameterNames = extractPathParameterNames(path);
        for (String pathParameter : pathParameterNames) {
            parameters.add(new ApiDocParameter(pathParameter, WebParameterSource.PATH, String.class, null));
        }
        for (Parameter parameter : executable.getParameters()) {
            webParam(parameter, metadata).ifPresent(param -> {
                String name = parameterName(parameter, param.annotationValue(), metadata);
                if (param.source() != WebParameterSource.PATH || pathParameterNames.contains(name)) {
                    addOrReplace(parameters,
                                 new ApiDocParameter(name, param.source(), parameter.getParameterizedType(),
                                                     parameter));
                }
            });
        }
        return parameters;
    }

    private static List<ApiDocParameter> parameters(
            ExecutableDescriptor executable, String path, Class<?> declaringClass) {
        List<ApiDocParameter> parameters = new ArrayList<>();
        Set<String> pathParameterNames = extractPathParameterNames(path);
        for (String pathParameter : pathParameterNames) {
            parameters.add(new ApiDocParameter(pathParameter, WebParameterSource.PATH, String.class, null));
        }
        for (ParameterDescriptor parameter : executable.parameters()) {
            webParam(parameter, declaringClass).ifPresent(param -> {
                String name = isBlank(param.annotationValue()) ? parameter.name() : param.annotationValue();
                if (!isBlank(name) && (param.source() != WebParameterSource.PATH || pathParameterNames.contains(name))) {
                    addOrReplace(parameters, new ApiDocParameter(
                            name, param.source(), type(parameter.typeName()), null, parameter));
                }
            });
        }
        return parameters;
    }

    private static List<ApiDocRequestBody> requestBodies(Executable executable, MetadataContext metadata) {
        List<ApiDocRequestBody> requestBodies = new ArrayList<>();
        for (Parameter parameter : executable.getParameters()) {
            if (webParam(parameter, metadata).isEmpty() && !isFrameworkParameter(parameter.getType())) {
                requestBodies.add(new ApiDocRequestBody(parameter.getParameterizedType(), parameter));
            }
        }
        return requestBodies;
    }

    private static List<ApiDocRequestBody> requestBodies(ExecutableDescriptor executable, Class<?> declaringClass) {
        List<ApiDocRequestBody> requestBodies = new ArrayList<>();
        for (ParameterDescriptor parameter : executable.parameters()) {
            if (webParam(parameter, declaringClass).isEmpty() && !isFrameworkParameter(parameter.typeName())) {
                requestBodies.add(new ApiDocRequestBody(type(parameter.typeName()), null, parameter));
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

    private static ApiDocDetails documentation(Class<?> handlerType, Executable executable, MetadataContext metadata) {
        DocumentationBuilder builder = new DocumentationBuilder();
        if (metadata.available()) {
            metadata.packageAnnotations().forEach(annotations -> apply(builder, annotations, handlerType));
            apply(builder, metadata.typeAnnotations(), handlerType);
            apply(builder, metadata.executableAnnotations(executable), handlerType);
            return builder.build();
        }
        packages(handlerType).forEach(p -> builder.apply(p.getAnnotation(ApiDoc.class)));
        builder.apply(handlerType.getAnnotation(ApiDoc.class));
        builder.apply(executable.getAnnotation(ApiDoc.class));
        return builder.build();
    }

    private static ApiDocDetails documentation(
            Class<?> handlerType, List<AnnotationDescriptor> executableAnnotations, MetadataContext metadata) {
        DocumentationBuilder builder = new DocumentationBuilder();
        metadata.packageAnnotations().forEach(annotations -> apply(builder, annotations, handlerType));
        apply(builder, metadata.typeAnnotations(), handlerType);
        apply(builder, executableAnnotations, handlerType);
        return builder.build();
    }

    private static void apply(DocumentationBuilder builder, List<AnnotationDescriptor> annotations,
                              Class<?> declaringClass) {
        ComponentMetadataLookups.annotationAs(annotations, ApiDoc.class, ApiDoc.class, declaringClass)
                .ifPresent(builder::apply);
    }

    private static List<ApiDocResponseDescriptor> responses(
            Class<?> handlerType, Executable executable, MetadataContext metadata) {
        Map<Integer, ApiDocResponseDescriptor> responses = new LinkedHashMap<>();
        if (metadata.available()) {
            metadata.packageAnnotations().forEach(annotations -> addResponses(responses, annotations, handlerType));
            addResponses(responses, metadata.typeAnnotations(), handlerType);
            addResponses(responses, metadata.executableAnnotations(executable), handlerType);
            return new ArrayList<>(responses.values());
        }
        packages(handlerType).forEach(p -> addResponses(responses, p.getAnnotationsByType(ApiDocResponse.class)));
        addResponses(responses, handlerType.getAnnotationsByType(ApiDocResponse.class));
        addResponses(responses, executable.getAnnotationsByType(ApiDocResponse.class));
        return new ArrayList<>(responses.values());
    }

    private static List<ApiDocResponseDescriptor> responses(
            Class<?> handlerType, List<AnnotationDescriptor> executableAnnotations, MetadataContext metadata) {
        Map<Integer, ApiDocResponseDescriptor> responses = new LinkedHashMap<>();
        metadata.packageAnnotations().forEach(annotations -> addResponses(responses, annotations, handlerType));
        addResponses(responses, metadata.typeAnnotations(), handlerType);
        addResponses(responses, executableAnnotations, handlerType);
        return new ArrayList<>(responses.values());
    }

    private static void addResponses(
            Map<Integer, ApiDocResponseDescriptor> target, List<AnnotationDescriptor> annotations,
            Class<?> declaringClass) {
        addResponses(target, ComponentMetadataLookups.annotations(annotations, ApiDocResponse.class, declaringClass)
                .toArray(ApiDocResponse[]::new));
        for (ApiDocResponses responses : ComponentMetadataLookups.annotations(
                annotations, ApiDocResponses.class, declaringClass)) {
            addResponses(target, responses.value());
        }
    }

    private static void addResponses(Map<Integer, ApiDocResponseDescriptor> target, ApiDocResponse[] responses) {
        for (ApiDocResponse response : responses) {
            target.put(response.status(),
                       new ApiDocResponseDescriptor(response.status(), response.description(), response.ref(),
                                                    response.type(), response.contentType()));
        }
    }

    private static boolean isExcluded(Class<?> handlerType, Executable executable, MetadataContext metadata) {
        if (metadata.available()) {
            return metadata.packageAnnotations().stream()
                           .anyMatch(annotations -> ComponentMetadataLookups.hasAnnotation(
                                   annotations, ApiDocExclude.class))
                   || ComponentMetadataLookups.hasAnnotation(metadata.typeAnnotations(), ApiDocExclude.class)
                   || ComponentMetadataLookups.hasAnnotation(
                           metadata.executableAnnotations(executable), ApiDocExclude.class);
        }
        return packages(handlerType).anyMatch(p -> p.isAnnotationPresent(ApiDocExclude.class))
               || handlerType.isAnnotationPresent(ApiDocExclude.class)
               || executable.isAnnotationPresent(ApiDocExclude.class);
    }

    private static boolean isExcluded(
            Class<?> handlerType, List<AnnotationDescriptor> executableAnnotations, MetadataContext metadata) {
        return metadata.packageAnnotations().stream()
                       .anyMatch(annotations -> ComponentMetadataLookups.hasAnnotation(
                               annotations, ApiDocExclude.class))
               || ComponentMetadataLookups.hasAnnotation(metadata.typeAnnotations(), ApiDocExclude.class)
               || ComponentMetadataLookups.hasAnnotation(executableAnnotations, ApiDocExclude.class);
    }

    private static boolean isDocumented(Class<?> handlerType, Executable executable, MetadataContext metadata) {
        if (metadata.available()) {
            return metadata.packageAnnotations().stream()
                           .anyMatch(annotations -> ComponentMetadataLookups.hasAnnotation(annotations, ApiDoc.class))
                   || ComponentMetadataLookups.hasAnnotation(metadata.typeAnnotations(), ApiDoc.class)
                   || ComponentMetadataLookups.hasAnnotation(metadata.executableAnnotations(executable), ApiDoc.class);
        }
        return packages(handlerType).anyMatch(p -> p.isAnnotationPresent(ApiDoc.class))
               || handlerType.isAnnotationPresent(ApiDoc.class)
               || executable.isAnnotationPresent(ApiDoc.class);
    }

    private static boolean isDocumented(
            Class<?> handlerType, List<AnnotationDescriptor> executableAnnotations, MetadataContext metadata) {
        return metadata.packageAnnotations().stream()
                       .anyMatch(annotations -> ComponentMetadataLookups.hasAnnotation(annotations, ApiDoc.class))
               || ComponentMetadataLookups.hasAnnotation(metadata.typeAnnotations(), ApiDoc.class)
               || ComponentMetadataLookups.hasAnnotation(executableAnnotations, ApiDoc.class);
    }

    private static Stream<Package> packages(Class<?> handlerType) {
        return JvmCompatibilityBackend.introspector().getPackageAndParentPackages(handlerType.getPackage()).reversed().stream();
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

    private static String parameterName(Parameter parameter, String annotationValue, MetadataContext metadata) {
        if (!isBlank(annotationValue)) {
            return annotationValue;
        }
        Optional<String> metadataName = metadata.parameter(parameter)
                .map(ParameterDescriptor::name)
                .filter(name -> !isBlank(name));
        if (metadataName.isPresent()) {
            return metadataName.get();
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

    private static Optional<WebParamInfo> webParam(Parameter parameter, MetadataContext metadata) {
        Optional<WebParamInfo> metadataParam = metadata.parameter(parameter)
                .flatMap(descriptor -> ComponentMetadataLookups.annotationAs(
                        descriptor.annotations(), WebParam.class, WebParamProjection.class,
                        parameter.getDeclaringExecutable().getDeclaringClass()))
                .map(webParam -> new WebParamInfo(webParam.type(), webParam.value()));
        if (metadataParam.isPresent() || metadata.available()) {
            return metadataParam;
        }
        return Arrays.stream(parameter.getAnnotations())
                .map(ApiDocExtractor::webParam)
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .findFirst();
    }

    private static Optional<WebParamInfo> webParam(ParameterDescriptor parameter, Class<?> declaringClass) {
        return ComponentMetadataLookups.annotationAs(
                        parameter.annotations(), WebParam.class, WebParamProjection.class, declaringClass)
                .map(webParam -> new WebParamInfo(webParam.type(), webParam.value()));
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

    private static boolean isFrameworkParameter(String typeName) {
        return classForTypeName(typeName).map(ApiDocExtractor::isFrameworkParameter).orElse(false);
    }

    private static Type type(String typeName) {
        return classForTypeName(typeName).<Type>map(type -> type).orElse(Object.class);
    }

    private static Optional<Class<?>> classForTypeName(String typeName) {
        return switch (typeName) {
            case "boolean" -> Optional.of(boolean.class);
            case "byte" -> Optional.of(byte.class);
            case "short" -> Optional.of(short.class);
            case "int" -> Optional.of(int.class);
            case "long" -> Optional.of(long.class);
            case "float" -> Optional.of(float.class);
            case "double" -> Optional.of(double.class);
            case "char" -> Optional.of(char.class);
            case "void" -> Optional.of(void.class);
            default -> JvmComponentMetadataLookup.classForMetadataName(typeName);
        };
    }

    private record WebParamInfo(WebParameterSource source, String annotationValue) {
    }

    private record WebParamProjection(String value, WebParameterSource type) {
    }

    private record MetadataContext(Class<?> handlerType, Optional<ComponentMetadataLookup> lookup) {
        static MetadataContext of(Class<?> handlerType) {
            return new MetadataContext(handlerType, ComponentMetadataLookups.lookup(handlerType));
        }

        boolean available() {
            return lookup.isPresent();
        }

        List<List<AnnotationDescriptor>> packageAnnotations() {
            return lookup.map(l -> l.packageMetadataChain(handlerType.getPackageName()).reversed().stream()
                            .map(descriptor -> descriptor.annotations())
                            .toList())
                    .orElseGet(List::of);
        }

        List<AnnotationDescriptor> typeAnnotations() {
            return lookup.map(l -> l.typeAnnotations(handlerType.getName())).orElseGet(List::of);
        }

        List<AnnotationDescriptor> executableAnnotations(Executable executable) {
            return lookup.map(l -> ComponentMetadataLookups.executableAnnotations(l, executable))
                    .orElseGet(List::of);
        }

        Optional<ParameterDescriptor> parameter(Parameter parameter) {
            return lookup.flatMap(l -> ComponentMetadataLookups.parameter(l, parameter));
        }
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
