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
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.registry.PackageDescriptor;
import io.fluxzero.sdk.registry.RegistryComponentMetadataLookup;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.common.api.Data.JSON_FORMAT;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Automatic endpoint that serves the generated OpenAPI document for an {@link ApiDocInfo} scope.
 */
public final class OpenApiDocumentEndpoint {
    @Path
    private final String path;
    private final Class<?> handlerType;
    private final Optional<ComponentMetadataLookup> metadataLookup;
    private final Map<Class<?>, Object> handlers = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String documentJson;

    private OpenApiDocumentEndpoint(
            String path, Class<?> handlerType, Object handler, Optional<ComponentMetadataLookup> metadataLookup) {
        this.path = path;
        this.handlerType = handlerType;
        this.metadataLookup = Objects.requireNonNull(metadataLookup, "metadataLookup");
        includeHandler(handlerType, handler);
    }

    public static List<OpenApiDocumentEndpoint> forHandler(Class<?> handlerType, Object handler) {
        List<OpenApiDocumentEndpoint> endpoints = new ArrayList<>();
        Optional<ComponentMetadataLookup> lookup = ComponentMetadataLookups.lookup(handlerType);
        if (ComponentMetadataLookups.generatedOnlyMode() && lookup.isEmpty()) {
            return endpoints;
        }
        Function<AnnotatedElement, java.util.stream.Stream<String>> pathValues = WebUtils.pathValues();
        String path = "";
        if (lookup.isPresent()) {
            for (PackageDescriptor currentPackage : lookup.orElseThrow()
                    .packageMetadataChain(handlerType.getPackageName()).reversed()) {
                path = appendPath(path, WebUtils.pathValues(currentPackage).toList());
                addIfEnabled(endpoints, apiDocInfo(handlerType, currentPackage), path, handlerType, handler, lookup);
            }
        } else {
            for (Package currentPackage : JvmCompatibilityBackend.introspector()
                    .getPackageAndParentPackages(handlerType.getPackage()).reversed()) {
                path = appendPath(path, pathValues.apply(currentPackage).toList());
                addIfEnabled(
                        endpoints, apiDocInfo(lookup, handlerType, currentPackage), path, handlerType, handler,
                        lookup);
            }
        }
        path = appendPath(path, pathValues.apply(handlerType).toList());
        addIfEnabled(endpoints, apiDocInfo(lookup, handlerType), path, handlerType, handler, lookup);
        return endpoints;
    }

    private static ApiDocInfo apiDocInfo(Class<?> handlerType, PackageDescriptor sourcePackage) {
        return ComponentMetadataLookups.annotationAs(
                sourcePackage.annotations(), ApiDocInfo.class, ApiDocInfo.class, handlerType).orElse(null);
    }

    private static ApiDocInfo apiDocInfo(
            Optional<ComponentMetadataLookup> lookup, Class<?> handlerType, Package sourcePackage) {
        Optional<ApiDocInfo> metadata = lookup.flatMap(l -> l.packageMetadata(sourcePackage.getName())
                .flatMap(descriptor -> ComponentMetadataLookups.annotationAs(
                        descriptor.annotations(), ApiDocInfo.class, ApiDocInfo.class, handlerType)));
        if (metadata.isPresent() || lookup.isPresent()) {
            return metadata.orElse(null);
        }
        return sourcePackage.getAnnotation(ApiDocInfo.class);
    }

    private static ApiDocInfo apiDocInfo(Optional<ComponentMetadataLookup> lookup, Class<?> handlerType) {
        Optional<ApiDocInfo> metadata = lookup.flatMap(l -> ComponentMetadataLookups.typeAnnotation(
                l, handlerType, ApiDocInfo.class));
        if (metadata.isPresent() || lookup.isPresent()) {
            return metadata.orElse(null);
        }
        return handlerType.getAnnotation(ApiDocInfo.class);
    }

    private static void addIfEnabled(List<OpenApiDocumentEndpoint> endpoints, ApiDocInfo info, String basePath,
                                     Class<?> handlerType, Object handler,
                                     Optional<ComponentMetadataLookup> metadataLookup) {
        if (info == null || !(info.serveOpenApi() || info.serveApiReference())) {
            return;
        }
        endpoints.add(new OpenApiDocumentEndpoint(
                resolvePath(basePath, info.openApiPath()), handlerType, handler, metadataLookup));
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
        return OpenApiRenderer.renderPrettyJson(
                ApiDocExtractor.extractWebHandlers(handlerScope(), documentMetadataLookup()), null);
    }

    private Optional<ComponentMetadataLookup> documentMetadataLookup() {
        return Fluxzero.getOptionally()
                .map(Fluxzero::componentRegistry)
                .filter(registry -> !registry.isEmpty())
                .map(RegistryComponentMetadataLookup::of)
                .map(ComponentMetadataLookup.class::cast)
                .or(() -> metadataLookup);
    }

    /**
     * Internal hook used while deduplicating document endpoints by path.
     */
    public void include(OpenApiDocumentEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        endpoint.handlerScope().forEach(this::includeHandler);
        documentJson = null;
    }

    private void includeHandler(Class<?> handlerType, Object handler) {
        handlers.putIfAbsent(handlerType, handler instanceof Class<?> ? null : handler);
    }

    private Map<Class<?>, Object> handlerScope() {
        synchronized (handlers) {
            return new LinkedHashMap<>(handlers);
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof OpenApiDocumentEndpoint other && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}
