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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.common.api.Data.JSON_FORMAT;
import static io.fluxzero.common.reflection.ReflectionUtils.getPackageAndParentPackages;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Automatic endpoint that serves the generated OpenAPI document for an {@link ApiDocInfo} scope.
 */
public final class OpenApiDocumentEndpoint {
    @Path
    private final String path;
    private final Class<?> handlerType;
    private final Object handler;
    private volatile String documentJson;

    private OpenApiDocumentEndpoint(String path, Class<?> handlerType, Object handler) {
        this.path = path;
        this.handlerType = handlerType;
        this.handler = handler instanceof Class<?> ? null : handler;
    }

    public static List<OpenApiDocumentEndpoint> forHandler(Class<?> handlerType, Object handler) {
        List<OpenApiDocumentEndpoint> endpoints = new ArrayList<>();
        Function<AnnotatedElement, java.util.stream.Stream<String>> pathValues = WebUtils.pathValues();
        String path = "";
        for (Package currentPackage : getPackageAndParentPackages(handlerType.getPackage()).reversed()) {
            path = appendPath(path, pathValues.apply(currentPackage).toList());
            addIfEnabled(endpoints, currentPackage.getAnnotation(ApiDocInfo.class), path, handlerType, handler);
        }
        path = appendPath(path, pathValues.apply(handlerType).toList());
        addIfEnabled(endpoints, handlerType.getAnnotation(ApiDocInfo.class), path, handlerType, handler);
        return endpoints;
    }

    private static void addIfEnabled(List<OpenApiDocumentEndpoint> endpoints, ApiDocInfo info, String basePath,
                                     Class<?> handlerType, Object handler) {
        if (info == null || !(info.serveOpenApi() || info.serveApiReference())) {
            return;
        }
        endpoints.add(new OpenApiDocumentEndpoint(resolvePath(basePath, info.openApiPath()), handlerType, handler));
    }

    private static String appendPath(String base, List<String> parts) {
        String result = base;
        for (String part : parts) {
            result = WebUtils.isAbsolutePathOrUrl(part) ? part : WebUtils.concatenateUrlParts(result, part);
        }
        return result;
    }

    private static String resolvePath(String basePath, String configuredPath) {
        String path = isBlank(configuredPath) ? "openapi.json" : configuredPath;
        return WebUtils.isAbsolutePathOrUrl(path) ? path : WebUtils.concatenateUrlParts(basePath, path);
    }

    @NoUserRequired
    @HandleGet
    WebResponse response() {
        return WebResponse.builder()
                .status(200)
                .contentType(JSON_FORMAT)
                .payload(JsonUtils.fromJson(documentJson(), JsonNode.class))
                .build();
    }

    private String documentJson() {
        String result = documentJson;
        if (result == null) {
            synchronized (this) {
                result = documentJson;
                if (result == null) {
                    result = readGeneratedDocument(handlerType).orElseGet(this::renderRuntimeDocument);
                    documentJson = result;
                }
            }
        }
        return result;
    }

    @SneakyThrows
    private static Optional<String> readGeneratedDocument(Class<?> handlerType) {
        ClassLoader classLoader = handlerType.getClassLoader();
        InputStream input = classLoader == null
                ? ClassLoader.getSystemResourceAsStream(OpenApiProcessor.DEFAULT_OUTPUT)
                : classLoader.getResourceAsStream(OpenApiProcessor.DEFAULT_OUTPUT);
        if (input == null) {
            return Optional.empty();
        }
        try (input) {
            return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String renderRuntimeDocument() {
        return OpenApiRenderer.renderPrettyJson(ApiDocExtractor.extract(handlerType, handler), null);
    }
}
