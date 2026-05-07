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
 *
 */

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.MemoizingFunction;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.testserver.TestServerVersion;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.fluxzero.sdk.common.ClientUtils.memoize;
import static java.util.Optional.ofNullable;

/**
 * Utility methods for mapping Fluxzero test-server WebSocket endpoints onto the embedded Jetty transport.
 */
public class WebsocketDeploymentUtils {
    public static final String HANDSHAKE_HEADERS_USER_PROPERTY =
            WebsocketDeploymentUtils.class.getName() + ".handshakeHeaders";
    public static final String RUNTIME_SESSION_ID_USER_PROPERTY =
            WebsocketDeploymentUtils.class.getName() + ".runtimeSessionId";
    public static final String SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY =
            WebsocketDeploymentUtils.class.getName() + ".selectedCompressionAlgorithm";

    /**
     * Registers a namespace-aware endpoint route.
     */
    public static JettyWebsocketRouter deploy(Function<String, WebsocketEndpoint> endpointSupplier, String path,
                                              JettyWebsocketRouter router) {
        return deployFromSession(memoize(endpointSupplier).compose(WebsocketDeploymentUtils::getNamespace),
                                 path, router);
    }

    /**
     * Registers a route whose endpoint can be resolved from the complete WebSocket session.
     */
    public static JettyWebsocketRouter deployFromSession(
            MemoizingFunction<ServerWebsocketSession, WebsocketEndpoint> endpointSupplier, String path,
            JettyWebsocketRouter router) {
        return router.addRoute(path, endpointSupplier);
    }

    /**
     * Resolves the test-server namespace from modern {@code namespace} or legacy {@code projectId} query parameters.
     */
    public static String getNamespace(ServerWebsocketSession session) {
        return ofNullable(session.getRequestParameterMap().get("namespace")).map(List::getFirst)
                .or(() -> ofNullable(session.getRequestParameterMap().get("projectId")).map(List::getFirst))
                .orElse("public");
    }

    static JettyWebsocketHandshake createHandshake(ServerUpgradeRequest request, ServerUpgradeResponse response) {
        Map<String, List<String>> requestHeaders = copyHeaders(request.getHeaders());
        Map<String, Object> userProperties = new java.util.concurrent.ConcurrentHashMap<>();
        userProperties.put(HANDSHAKE_HEADERS_USER_PROPERTY, requestHeaders);

        String runtimeSessionId = WebSocketCapabilities.newShortSessionId();
        userProperties.put(RUNTIME_SESSION_ID_USER_PROPERTY, runtimeSessionId);
        response.getHeaders().put(WebSocketCapabilities.RUNTIME_SESSION_ID_HEADER, runtimeSessionId);

        TestServerVersion.version().ifPresent(version ->
                response.getHeaders().put(WebSocketCapabilities.RUNTIME_VERSION_HEADER, version));

        WebSocketCapabilities.getPreferredCompressionAlgorithm(requestHeaders).ifPresent(compressionAlgorithm -> {
            userProperties.put(SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, compressionAlgorithm);
            response.getHeaders().put(WebSocketCapabilities.SELECTED_COMPRESSION_ALGORITHM_HEADER,
                                      compressionAlgorithm.name());
        });

        URI requestUri = request.getHttpURI().toURI();
        return new JettyWebsocketHandshake(requestUri, parseQuery(request.getHttpURI().getQuery()),
                                           requestHeaders, userProperties);
    }

    private static Map<String, List<String>> copyHeaders(HttpFields headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach(field -> copy.computeIfAbsent(field.getName(), ignored -> new ArrayList<>())
                .addAll(field.getValueList()));
        copy.replaceAll((ignored, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String name = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = separator < 0 ? "" : decode(pair.substring(separator + 1));
            parameters.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        parameters.replaceAll((ignored, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(parameters);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
