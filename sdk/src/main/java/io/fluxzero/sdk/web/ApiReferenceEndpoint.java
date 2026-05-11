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

import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.getPackageAndParentPackages;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Automatic endpoint that serves an HTML API reference page for an {@link ApiDocInfo} scope.
 */
public final class ApiReferenceEndpoint {
    private static final String DEFAULT_REDOC_SCRIPT =
            "https://cdn.redoc.ly/redoc/v2.5.1/bundles/redoc.standalone.js";
    private static final String DEFAULT_SCALAR_SCRIPT =
            "https://cdn.jsdelivr.net/npm/@scalar/api-reference@1";
    private static final String DEFAULT_SWAGGER_UI_SCRIPT =
            "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js";
    private static final String DEFAULT_SWAGGER_UI_STYLESHEET =
            "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css";

    @Path
    private final String path;
    private final String openApiPath;
    private final String title;
    private final ApiReferenceRenderer renderer;
    private final String scriptUrl;
    private final String stylesheetUrl;
    private volatile String documentHtml;

    private ApiReferenceEndpoint(String path, String openApiPath, ApiDocInfo info) {
        this.path = path;
        this.openApiPath = openApiPath;
        title = isBlank(info.title()) ? "API Reference" : info.title();
        renderer = info.apiReferenceRenderer();
        scriptUrl = isBlank(info.apiReferenceScriptUrl())
                ? defaultScriptUrl(renderer) : info.apiReferenceScriptUrl();
        stylesheetUrl = isBlank(info.apiReferenceStylesheetUrl())
                ? defaultStylesheetUrl(renderer) : info.apiReferenceStylesheetUrl();
    }

    public static List<ApiReferenceEndpoint> forHandler(Class<?> handlerType) {
        List<ApiReferenceEndpoint> endpoints = new ArrayList<>();
        Function<AnnotatedElement, java.util.stream.Stream<String>> pathValues = WebUtils.pathValues();
        String path = "";
        for (Package currentPackage : getPackageAndParentPackages(handlerType.getPackage()).reversed()) {
            path = appendPath(path, pathValues.apply(currentPackage).toList());
            addIfEnabled(endpoints, currentPackage.getAnnotation(ApiDocInfo.class), path);
        }
        path = appendPath(path, pathValues.apply(handlerType).toList());
        addIfEnabled(endpoints, handlerType.getAnnotation(ApiDocInfo.class), path);
        return endpoints;
    }

    private static void addIfEnabled(List<ApiReferenceEndpoint> endpoints, ApiDocInfo info, String basePath) {
        if (info == null || !info.serveApiReference()) {
            return;
        }
        endpoints.add(new ApiReferenceEndpoint(resolvePath(basePath, info.apiReferencePath(), "docs"),
                                               resolvePath(basePath, info.openApiPath(), "openapi.json"), info));
    }

    private static String appendPath(String base, List<String> parts) {
        String result = base;
        for (String part : parts) {
            result = WebUtils.isAbsolutePathOrUrl(part) ? part : WebUtils.concatenateUrlParts(result, part);
        }
        return result;
    }

    private static String resolvePath(String basePath, String configuredPath, String defaultPath) {
        String path = isBlank(configuredPath) ? defaultPath : configuredPath;
        return WebUtils.isAbsolutePathOrUrl(path) ? path : WebUtils.concatenateUrlParts(basePath, path);
    }

    private static String defaultScriptUrl(ApiReferenceRenderer renderer) {
        return switch (renderer) {
            case REDOC -> DEFAULT_REDOC_SCRIPT;
            case SCALAR -> DEFAULT_SCALAR_SCRIPT;
            case SWAGGER_UI -> DEFAULT_SWAGGER_UI_SCRIPT;
        };
    }

    private static String defaultStylesheetUrl(ApiReferenceRenderer renderer) {
        return renderer == ApiReferenceRenderer.SWAGGER_UI ? DEFAULT_SWAGGER_UI_STYLESHEET : "";
    }

    @NoUserRequired
    @HandleGet
    WebResponse response() {
        return WebResponse.builder()
                .status(200)
                .contentType("text/html; charset=utf-8")
                .payload(documentHtml())
                .build();
    }

    private String documentHtml() {
        String result = documentHtml;
        if (result == null) {
            synchronized (this) {
                result = documentHtml;
                if (result == null) {
                    result = renderHtml();
                    documentHtml = result;
                }
            }
        }
        return result;
    }

    private String renderHtml() {
        String escapedTitle = escapeHtml(title);
        String escapedScriptUrl = escapeHtml(scriptUrl);
        String escapedStylesheetUrl = escapeHtml(stylesheetUrl);
        String escapedOpenApiPath = escapeHtml(openApiPath);
        return switch (renderer) {
            case REDOC -> """
                    <!doctype html>
                    <html>
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>%s</title>
                      <style>body{margin:0;padding:0}</style>
                    </head>
                    <body>
                      <redoc spec-url="%s"></redoc>
                      <script src="%s"></script>
                    </body>
                    </html>
                    """.formatted(escapedTitle, escapedOpenApiPath, escapedScriptUrl);
            case SCALAR -> """
                    <!doctype html>
                    <html>
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>%s</title>
                    </head>
                    <body>
                      <div id="app"></div>
                      <script src="%s"></script>
                      <script>
                        Scalar.createApiReference('#app', { url: '%s' });
                      </script>
                    </body>
                    </html>
                    """.formatted(escapedTitle, escapedScriptUrl, escapeJs(openApiPath));
            case SWAGGER_UI -> """
                    <!doctype html>
                    <html>
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>%s</title>
                      <link rel="stylesheet" href="%s">
                    </head>
                    <body>
                      <div id="swagger-ui"></div>
                      <script src="%s"></script>
                      <script>
                        window.onload = () => SwaggerUIBundle({ url: '%s', dom_id: '#swagger-ui' });
                      </script>
                    </body>
                    </html>
                    """.formatted(escapedTitle, escapedStylesheetUrl, escapedScriptUrl, escapeJs(openApiPath));
        };
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeJs(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e");
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ApiReferenceEndpoint other && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}
