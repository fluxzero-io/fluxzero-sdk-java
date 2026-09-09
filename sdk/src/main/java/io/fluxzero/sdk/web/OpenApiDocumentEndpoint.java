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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.common.api.Data.JSON_FORMAT;
import static io.fluxzero.common.reflection.ReflectionUtils.getPackageAndParentPackages;
import static io.fluxzero.common.ObjectUtils.isBlank;

/**
 * Automatic endpoint that serves the generated OpenAPI document for an {@link ApiDocInfo} scope.
 * <p>
 * All {@value OpenApiProcessor#DEFAULT_OUTPUT} resources visible to the handler class loader are combined in stable
 * resource order. Compatible object members are merged, exact duplicates are accepted, and conflicting metadata,
 * routes, operation ids, or components fail during handler registration. A resource produced by an application shade
 * transformer may contain multiple consecutive JSON documents. Runtime extraction is used only when no compiled or
 * manually supplied resource is present.
 */
public final class OpenApiDocumentEndpoint {
    @Path
    private final String path;
    private final Class<?> handlerType;
    private final Object handler;
    private volatile Optional<String> generatedDocument;
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

    /**
     * Loads and validates compiled OpenAPI resources. Handler registration invokes this once for each distinct
     * automatic document endpoint so resource conflicts fail before the endpoint starts serving requests.
     */
    public void validateResources() {
        generatedDocument();
    }

    private String documentJson() {
        String result = documentJson;
        if (result == null) {
            synchronized (this) {
                result = documentJson;
                if (result == null) {
                    result = generatedDocument().orElseGet(this::renderRuntimeDocument);
                    documentJson = result;
                }
            }
        }
        return result;
    }

    private Optional<String> generatedDocument() {
        Optional<String> result = generatedDocument;
        if (result == null) {
            synchronized (this) {
                result = generatedDocument;
                if (result == null) {
                    result = readGeneratedDocument(handlerType);
                    generatedDocument = result;
                }
            }
        }
        return result;
    }

    private static Optional<String> readGeneratedDocument(Class<?> handlerType) {
        return readGeneratedDocument(handlerType.getClassLoader());
    }

    static Optional<String> readGeneratedDocument(ClassLoader classLoader) {
        List<URL> resources;
        try {
            resources = Collections.list(classLoader == null
                                                 ? ClassLoader.getSystemResources(OpenApiProcessor.DEFAULT_OUTPUT)
                                                 : classLoader.getResources(OpenApiProcessor.DEFAULT_OUTPUT));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not enumerate OpenAPI resources at " + OpenApiProcessor.DEFAULT_OUTPUT, e);
        }
        resources.sort((first, second) -> first.toExternalForm().compareTo(second.toExternalForm()));
        if (resources.isEmpty()) {
            return Optional.empty();
        }
        Map<String, JsonNode> documents = new LinkedHashMap<>();
        for (URL resource : resources) {
            readDocuments(resource, documents);
        }
        ObjectNode merged = OpenApiDocumentMerger.merge(documents);
        return Optional.of(JsonUtils.asPrettyJson(merged));
    }

    private static void readDocuments(URL resource, Map<String, JsonNode> documents) {
        String source = resource.toExternalForm();
        int documentIndex = 0;
        try (InputStream input = resource.openStream(); var parser = JsonUtils.reader.createParser(input)) {
            while (parser.nextToken() != null) {
                JsonNode document = JsonUtils.reader.readTree(parser);
                documents.put(source + "#document=" + ++documentIndex, document);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read OpenAPI document from '%s': %s"
                                                       .formatted(source, e.getMessage()), e);
        }
        if (documentIndex == 0) {
            throw new IllegalArgumentException("OpenAPI resource '%s' is empty".formatted(source));
        }
    }

    private String renderRuntimeDocument() {
        return OpenApiRenderer.renderPrettyJson(ApiDocExtractor.extract(handlerType, handler), null);
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
