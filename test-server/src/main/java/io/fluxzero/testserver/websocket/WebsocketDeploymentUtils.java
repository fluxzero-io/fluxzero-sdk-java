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

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.MemoizingFunction;
import io.undertow.Undertow;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.SneakyThrows;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import java.util.List;
import java.util.function.Function;

import static io.fluxzero.sdk.common.ClientUtils.memoize;
import static io.undertow.servlet.Servlets.deployment;
import static java.util.Optional.ofNullable;

/**
 * Utility class for deploying WebSocket server endpoints using Undertow or similar frameworks. Provides methods for
 * setting up WebSocket endpoints, maintaining endpoint configurations, and managing namespace extraction from WebSocket
 * sessions.
 */
public class WebsocketDeploymentUtils {

    private static final ByteBufferPool bufferPool =
            new DefaultByteBufferPool(false, 1024, 100, 12);

    /**
     * Deploys a WebSocket server endpoint for the specified path using an endpoint supplier and a path handler. The
     * method relies on a memoized session-to-namespace mapping function and integrates the deployment into the provided
     * path handler.
     *
     * @param endpointSupplier a function mapping WebSocket session identifiers to their corresponding endpoints
     * @param path             the URL path under which the WebSocket endpoint will be made accessible
     * @param pathHandler      the handler managing path mappings for deployment
     * @return a {@link PathHandler} that includes the new WebSocket endpoint's path configuration
     */
    public static PathHandler deploy(Function<String, Endpoint> endpointSupplier, String path,
                                     PathHandler pathHandler) {
        return deployFromSession(memoize(endpointSupplier).compose(WebsocketDeploymentUtils::getNamespace),
                                 path, pathHandler);
    }

    /**
     * Deploys a WebSocket server endpoint using a specified session-to-endpoint mapping function, path, and handler.
     * The method sets up a deployment configuration for the provided path and registers the WebSocket endpoint to
     * handle requests at the specified path.
     *
     * @param endpointSupplier a memoizing function mapping WebSocket sessions to their respective endpoints
     * @param path             the URL path under which the WebSocket endpoint is made available
     * @param pathHandler      the handler that manages path mappings for deployment
     * @return a {@link PathHandler} with the WebSocket path mapping added to it
     */
    @SneakyThrows
    public static PathHandler deployFromSession(MemoizingFunction<Session, Endpoint> endpointSupplier, String path,
                                                PathHandler pathHandler) {
        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(MultiClientEndpoint.class, "/")
                .configurator(
                        new ServerEndpointConfig.Configurator() {
                            final MultiClientEndpoint endpoint = new MultiClientEndpoint(endpointSupplier);

                            @Override
                            public <T> T getEndpointInstance(Class<T> endpointClass) {
                                return endpointClass.cast(endpoint);
                            }
                        }
                )
                .build();
        DeploymentManager deploymentManager = Servlets.defaultContainer()
                .addDeployment(deployment()
                                       .setContextPath("/")
                                       .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME,
                                                                   createWebsocketDeploymentInfo()
                                                                           .addEndpoint(config))
                                       .setDeploymentName(path)
                                       .setClassLoader(Undertow.class.getClassLoader()));
        deploymentManager.deploy();
        return pathHandler.addPrefixPath(path, deploymentManager.start());
    }

    /**
     * Creates and configures a {@link WebSocketDeploymentInfo} instance, setting up the buffer pool and worker for
     * handling WebSocket connections.
     *
     * @return a configured {@link WebSocketDeploymentInfo} instance
     */
    public static WebSocketDeploymentInfo createWebsocketDeploymentInfo() {
        return new WebSocketDeploymentInfo().setBuffers(bufferPool).setWorker(createWorker());
    }

    /**
     * Extracts the namespace from the given WebSocket session, using the {@code namespace} parameter.
     * <p>
     * If the parameter is not present, the legacy {@code projectId} parameter is used instead. If no {@code namespace}
     * or {@code projectsId} parameter is present in the session, the method defaults to {@code "public"}.
     *
     * @param session the WebSocket session
     * @return the resolved namespace (never {@code null})
     */
    public static String getNamespace(Session session) {
        return ofNullable(session.getRequestParameterMap().get("namespace")).map(List::getFirst)
                .or(() -> ofNullable(session.getRequestParameterMap().get("projectId")).map(List::getFirst))
                .orElse("public");
    }

    @SneakyThrows
    private static XnioWorker createWorker() {
        return Xnio.getInstance().createWorker(OptionMap.create(Options.THREAD_DAEMON, true));
    }
}
