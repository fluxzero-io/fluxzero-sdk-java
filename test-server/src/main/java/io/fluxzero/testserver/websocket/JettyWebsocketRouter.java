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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Embedded Jetty router for the test server's WebSocket endpoints and health check.
 */
public class JettyWebsocketRouter {
    private static final long UNLIMITED_WEBSOCKET_SIZE = 0L;
    private final List<Route> routes = new ArrayList<>();
    private final Set<WebsocketEndpoint> endpoints = ConcurrentHashMap.newKeySet();

    /**
     * Registers a WebSocket route.
     *
     * @param path             route path, with or without leading/trailing slash
     * @param endpointSupplier creates the Fluxzero endpoint for each accepted session
     * @return this router
     */
    public JettyWebsocketRouter addRoute(String path,
                                         Function<ServerWebsocketSession, WebsocketEndpoint> endpointSupplier) {
        routes.add(new Route(normalizePath(path), session -> {
            WebsocketEndpoint endpoint = endpointSupplier.apply(session);
            endpoints.add(endpoint);
            return endpoint;
        }));
        return this;
    }

    /**
     * Starts Jetty on the given port.
     *
     * <p>The server does not register Jetty's JVM shutdown hook. When the returned server is stopped, the router shuts
     * down any endpoints it created.</p>
     *
     * @return the started Jetty server, owned by the caller
     */
    public Server start(int port) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("0.0.0.0");
        connector.setPort(port);
        server.addConnector(connector);
        server.setStopAtShutdown(false);
        server.setStopTimeout(1000);
        server.addEventListener(new LifeCycle.Listener() {
            @Override
            public void lifeCycleStopping(LifeCycle lifecycle) {
                shutDownEndpoints();
            }
        });
        server.setHandler(createHandler(server));
        server.start();
        return server;
    }

    Handler createHandler(Server server) {
        ContextHandler context = new ContextHandler("/");
        WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(server, context, container -> {
            configureContainer(container);
            for (Route route : routes) {
                container.addMapping(route.path(), route.creator());
                container.addMapping(route.path() + "/", route.creator());
            }
        });
        webSocketHandler.setHandler(new HealthHandler());
        context.setHandler(webSocketHandler);
        return context;
    }

    private static void configureContainer(ServerWebSocketContainer container) {
        // Jetty defaults to 64 KiB; the test server historically accepted larger SDK request batches.
        container.setMaxBinaryMessageSize(UNLIMITED_WEBSOCKET_SIZE);
        container.setMaxTextMessageSize(UNLIMITED_WEBSOCKET_SIZE);
        container.setMaxFrameSize(UNLIMITED_WEBSOCKET_SIZE);
    }

    private static String normalizePath(String path) {
        String normalized = path == null || path.isBlank() ? "/" : path;
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void shutDownEndpoints() {
        endpoints.forEach(WebsocketEndpoint::shutDown);
        endpoints.clear();
    }

    private record Route(String path, Function<ServerWebsocketSession, WebsocketEndpoint> endpointSupplier) {
        org.eclipse.jetty.websocket.server.WebSocketCreator creator() {
            return (request, response, callback) ->
                    new JettyWebsocketAdapter(endpointSupplier,
                                              WebsocketDeploymentUtils.createHandshake(request, response));
        }
    }

    private static class HealthHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) {
            if (!"/health".equals(Request.getPathInContext(request))) {
                return false;
            }
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            response.write(true, ByteBuffer.wrap("Healthy".getBytes(StandardCharsets.UTF_8)), callback);
            return true;
        }
    }
}
