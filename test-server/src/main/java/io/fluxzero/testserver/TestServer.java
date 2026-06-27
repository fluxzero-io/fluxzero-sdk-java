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

package io.fluxzero.testserver;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.MemoizingFunction;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RuntimeLifecycleEvent;
import io.fluxzero.common.tracking.HasMessageStore;
import io.fluxzero.common.tracking.MessageLogMaintenance;
import io.fluxzero.common.tracking.MessageStore;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.scheduling.client.LocalSchedulingClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.client.LocalTrackingClient;
import io.fluxzero.testserver.metrics.DefaultMetricsLog;
import io.fluxzero.testserver.metrics.MetricsLog;
import io.fluxzero.testserver.metrics.NoOpMetricsLog;
import io.fluxzero.testserver.scheduling.TestServerScheduleStore;
import io.fluxzero.testserver.websocket.CommandIdempotencyStore;
import io.fluxzero.testserver.websocket.ConsumerEndpoint;
import io.fluxzero.testserver.websocket.EventSourcingEndpoint;
import io.fluxzero.testserver.websocket.JettyWebsocketRouter;
import io.fluxzero.testserver.websocket.KeyValueEndPoint;
import io.fluxzero.testserver.websocket.ProducerEndpoint;
import io.fluxzero.testserver.websocket.SchedulingEndpoint;
import io.fluxzero.testserver.websocket.SearchEndpoint;
import io.fluxzero.testserver.websocket.ServerWebsocketSession;
import io.fluxzero.testserver.websocket.WebsocketEndpoint;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;

import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.ERROR;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.MessageType.QUERY;
import static io.fluxzero.common.MessageType.RESULT;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.MessageType.WEBRESPONSE;
import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.common.ServicePathBuilder.eventSourcingPath;
import static io.fluxzero.common.ServicePathBuilder.gatewayPath;
import static io.fluxzero.common.ServicePathBuilder.keyValuePath;
import static io.fluxzero.common.ServicePathBuilder.schedulingPath;
import static io.fluxzero.common.ServicePathBuilder.searchPath;
import static io.fluxzero.common.ServicePathBuilder.trackingPath;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STARTED;
import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STOPPING;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.deploy;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.deployFromSession;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.getNamespace;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Optional.ofNullable;

@Slf4j
public class TestServer {

    private static final String DEFAULT_NAMESPACE = "public";
    private static final String RUNTIME_NAME = "FluxzeroTestServer";

    private static volatile ServerState latestState = new ServerState();
    private static final Map<Server, ServerState> serverStates = new ConcurrentHashMap<>();

    /**
     * Standalone process entry point.
     *
     * <p>Do not call this method to embed the test server in another application. Use {@link #startServer(int)} instead
     * so the caller owns the server lifecycle and shutdown order.</p>
     */
    public static void main(final String[] args) {
        startServer(getConfiguredPort(), true);
    }

    /**
     * Starts an embedded test server on the given port without registering a JVM shutdown hook.
     *
     * <p>Callers that need to stop the embedded server explicitly should use {@link #startServer(int)}.</p>
     *
     * @param port the port to bind, or {@code 0} to select a random available port
     */
    public static void start(int port) {
        startServer(port);
    }

    /**
     * Starts an embedded test server using the configured port.
     *
     * <p>The port is resolved from {@code FLUXZERO_PORT}, {@code FLUX_PORT}, {@code port}, or {@code 8888}, in that
     * order. The returned Jetty server is owned by the caller and should be stopped by the caller.</p>
     *
     * @return the started Jetty server
     */
    public static Server startServer() {
        return startServer(getConfiguredPort());
    }

    /**
     * Starts an embedded test server on the given port.
     *
     * <p>The returned Jetty server is owned by the caller and should be stopped by the caller. This method does not
     * register a JVM shutdown hook, so it can be used safely in applications that coordinate their own shutdown order.</p>
     *
     * @param port the port to bind, or {@code 0} to select a random available port
     * @return the started Jetty server
     */
    public static Server startServer(int port) {
        return startServer(port, false);
    }

    private static Server startServer(int port, boolean registerShutdownHook) {
        ServerState state = new ServerState();
        latestState = state;
        JettyWebsocketRouter router = new JettyWebsocketRouter();
        CommandIdempotencyStore commandIdempotencyStore = new CommandIdempotencyStore();
        RuntimeLifecycleMetrics runtimeLifecycleMetrics = new RuntimeLifecycleMetrics(state);
        for (MessageType messageType : Arrays.asList(METRICS, EVENT, COMMAND, QUERY, RESULT, ERROR, WEBREQUEST, WEBRESPONSE)) {
            router = deploy(namespace -> new ProducerEndpoint(state.getMessageLogMaintenance(namespace, messageType), messageType,
                                                              null, commandIdempotencyStore)
                                    .metricsLog(messageType == METRICS ? new NoOpMetricsLog() :
                                                runtimeLifecycleMetrics.metricsLog(namespace)),
                            format("/%s/", gatewayPath(messageType)), router);
            router = deploy(namespace -> new ConsumerEndpoint(state.getMessageLogMaintenance(namespace, messageType), messageType,
                                                              commandIdempotencyStore)
                                    .metricsLog(messageType == METRICS ? new NoOpMetricsLog() :
                                                runtimeLifecycleMetrics.metricsLog(namespace)),
                            format("/%s/", trackingPath(messageType)), router);
        }
        router = deploy(namespace -> new ConsumerEndpoint(state.getMessageLogMaintenance(namespace, NOTIFICATION), NOTIFICATION,
                                                          commandIdempotencyStore)
                                .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)),
                        format("/%s/", trackingPath(NOTIFICATION)), router);

        for (MessageType messageType : MessageType.values()) {
            switch (messageType) {
                case CUSTOM: {
                    router = deployFromSession(
                            ObjectUtils.<String, String, WebsocketEndpoint>memoize((namespace, topic) -> new ProducerEndpoint(
                                            state.getMessageLogMaintenance(namespace, messageType, topic), messageType, topic,
                                            commandIdempotencyStore)
                                            .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)))
                                    .compose(s -> new SimpleEntry<>(getNamespace(s), getTopic(s))),
                            format("/%s/", gatewayPath(messageType)), router);
                }
                case DOCUMENT: {
                    router = deployFromSession(
                            ObjectUtils.<String, String, WebsocketEndpoint>memoize((namespace, topic) -> new ConsumerEndpoint(
                                            state.getMessageLogMaintenance(namespace, messageType, topic), messageType, topic,
                                            commandIdempotencyStore)
                                            .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)))
                                    .compose(s -> new SimpleEntry<>(getNamespace(s), getTopic(s))),
                            format("/%s/", trackingPath(messageType)), router);
                    break;
                }
            }
        }

        router = deploy(namespace -> new EventSourcingEndpoint(state.client(namespace).getEventStoreClient(),
                                                               commandIdempotencyStore)
                .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)), format("/%s/", eventSourcingPath()), router);
        router = deploy(namespace -> new KeyValueEndPoint(state.client(namespace).getKeyValueClient(),
                                                          commandIdempotencyStore)
                .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)), format("/%s/", keyValuePath()), router);
        router = deploy(namespace -> new SearchEndpoint(state.client(namespace).getSearchClient(),
                                                        commandIdempotencyStore)
                .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)), format("/%s/", searchPath()), router);
        router = deploy(namespace -> new SchedulingEndpoint(state.client(namespace).getSchedulingClient(),
                                                            commandIdempotencyStore)
                .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)), format("/%s/", schedulingPath()), router);
        router = deploy(namespace -> new ConsumerEndpoint((MessageStore) state.client(namespace).getSchedulingClient(), SCHEDULE,
                                                          commandIdempotencyStore)
                                .metricsLog(runtimeLifecycleMetrics.metricsLog(namespace)),
                        format("/%s/", trackingPath(SCHEDULE)), router);

        Server server;
        try {
            server = router.start(port);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Fluxzero test server on port " + port, e);
        }

        int localPort = getLocalPort(server, port);
        AtomicBoolean commandIdempotencyStoreClosed = new AtomicBoolean();
        serverStates.put(server, state);
        registerRuntimeLifecycle(server, localPort, commandIdempotencyStore, commandIdempotencyStoreClosed,
                                 runtimeLifecycleMetrics);

        if (registerShutdownHook) {
            getRuntime().addShutdownHook(Thread.ofPlatform().name("fluxzero-test-server-shutdown").unstarted(
                    () -> stopServer(server, commandIdempotencyStore, commandIdempotencyStoreClosed)));
        }

        logStarted(localPort);
        return server;
    }

    private static void logStarted(int localPort) {
        TestServerVersion.version().ifPresentOrElse(
                version -> log.info("Fluxzero test server (version {}) running on port {}", version, localPort),
                () -> log.info("Fluxzero test server running on port {}", localPort));
    }

    private static void stopServer(Server server, CommandIdempotencyStore commandIdempotencyStore,
                                   AtomicBoolean commandIdempotencyStoreClosed) {
        log.info("Initiating controlled shutdown");
        try {
            server.stop();
        } catch (InterruptedException e) {
            log.warn("Thread to kill server was interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to stop test server", e);
        } finally {
            closeCommandIdempotencyStore(commandIdempotencyStore, commandIdempotencyStoreClosed);
        }
    }

    private static int getConfiguredPort() {
        return getIntegerProperty("FLUXZERO_PORT", getIntegerProperty("FLUX_PORT", getIntegerProperty("port", 8888)));
    }

    private static void registerRuntimeLifecycle(Server server, int port, CommandIdempotencyStore commandIdempotencyStore,
                                                 AtomicBoolean commandIdempotencyStoreClosed,
                                                 RuntimeLifecycleMetrics runtimeLifecycleMetrics) {
        AtomicBoolean shutdownMetricPublished = new AtomicBoolean();
        server.addEventListener(new LifeCycle.Listener() {
            @Override
            public void lifeCycleStopping(LifeCycle lifecycle) {
                if (shutdownMetricPublished.compareAndSet(false, true)) {
                    runtimeLifecycleMetrics.stopping(port);
                }
            }

            @Override
            public void lifeCycleStopped(LifeCycle lifecycle) {
                serverStates.remove(server);
                closeCommandIdempotencyStore(commandIdempotencyStore, commandIdempotencyStoreClosed);
            }
        });
        runtimeLifecycleMetrics.started(port);
    }

    private static RuntimeLifecycleEvent runtimeLifecycleEvent(RuntimeLifecycleEvent.Phase phase, int port) {
        return new RuntimeLifecycleEvent(phase, RUNTIME_NAME, TestServerVersion.version().orElse(null), port,
                                         currentTimeMillis());
    }

    private static void registerLifecycleMetric(MetricsLog metricsLog, RuntimeLifecycleEvent event) {
        metricsLog.registerMetrics(event, Metadata.empty()).join();
    }

    private static int getLocalPort(Server server, int fallbackPort) {
        return Arrays.stream(server.getConnectors())
                .filter(ServerConnector.class::isInstance)
                .map(ServerConnector.class::cast)
                .mapToInt(ServerConnector::getLocalPort)
                .filter(port -> port > 0)
                .findFirst()
                .orElse(fallbackPort);
    }

    private static void closeCommandIdempotencyStore(CommandIdempotencyStore commandIdempotencyStore,
                                                     AtomicBoolean commandIdempotencyStoreClosed) {
        if (commandIdempotencyStoreClosed.compareAndSet(false, true)) {
            commandIdempotencyStore.close();
        }
    }

    static MessageStore getMetricsMessageStore(String namespace) {
        return latestState.getMessageStore(namespace, METRICS);
    }

    static MessageStore getMetricsMessageStore(Server server, String namespace) {
        ServerState state = ofNullable(serverStates.get(server))
                .orElseThrow(() -> new IllegalStateException("Unknown Fluxzero test server"));
        return state.getMessageStore(namespace, METRICS);
    }

    private static class ServerState {
        private final MemoizingFunction<String, Client> clients = memoize(
                namespace -> new TestServerProject(LocalClient.newInstance()));
        private final MemoizingFunction<String, MetricsLog> metricsLogSupplier = memoize(
                namespace -> new DefaultMetricsLog(getMessageStore(namespace, METRICS)));

        private Client client(String namespace) {
            return clients.apply(namespace);
        }

        private MetricsLog metricsLog(String namespace) {
            return metricsLogSupplier.apply(namespace);
        }

        private MessageStore getMessageStore(String namespace, MessageType messageType) {
            return getMessageStore(namespace, messageType, null);
        }

        private MessageStore getMessageStore(String namespace, MessageType messageType, String topic) {
            return getMessageLogMaintenance(namespace, messageType, topic).getMessageStore();
        }

        private MessageLogMaintenance getMessageLogMaintenance(String namespace, MessageType messageType) {
            return getMessageLogMaintenance(namespace, messageType, null);
        }

        private MessageLogMaintenance getMessageLogMaintenance(String namespace, MessageType messageType,
                                                               String topic) {
            var client = client(namespace).getTrackingClient(messageType, topic);
            if (client instanceof LocalTrackingClient localTrackingClient) {
                return localTrackingClient.getMessageLogMaintenance();
            }
            if (client instanceof HasMessageStore hasMessageStore) {
                throw new IllegalStateException("Tracking client with message store has no message log maintenance: "
                                                + hasMessageStore.getClass());
            }
            throw new IllegalStateException("Tracking client has no message log maintenance: " + client.getClass());
        }
    }

    private static class RuntimeLifecycleMetrics {
        private final ServerState state;
        private final Set<String> namespaces = ConcurrentHashMap.newKeySet();
        private final Set<String> startupNamespaces = ConcurrentHashMap.newKeySet();
        private volatile RuntimeLifecycleEvent startupEvent;

        RuntimeLifecycleMetrics(ServerState state) {
            this.state = state;
        }

        MetricsLog metricsLog(String namespace) {
            namespace = namespace == null ? DEFAULT_NAMESPACE : namespace;
            namespaces.add(namespace);
            MetricsLog metricsLog = state.metricsLog(namespace);
            registerStartupMetric(namespace, metricsLog);
            return metricsLog;
        }

        void started(int port) {
            startupEvent = runtimeLifecycleEvent(STARTED, port);
            namespaces.add(DEFAULT_NAMESPACE);
            namespaces.forEach(namespace -> registerStartupMetric(namespace, state.metricsLog(namespace)));
        }

        void stopping(int port) {
            RuntimeLifecycleEvent event = runtimeLifecycleEvent(STOPPING, port);
            namespaces.forEach(namespace -> registerLifecycleMetric(state.metricsLog(namespace), event));
        }

        private void registerStartupMetric(String namespace, MetricsLog metricsLog) {
            ofNullable(startupEvent)
                    .filter(event -> startupNamespaces.add(namespace))
                    .ifPresent(event -> registerLifecycleMetric(metricsLog, event));
        }
    }

    @AllArgsConstructor
    static class TestServerProject implements Client {
        @Delegate
        private final LocalClient delegate;

        @Override
        public SchedulingClient getSchedulingClient() {
            return new TestServerScheduleStore(
                    ((LocalSchedulingClient) delegate.getSchedulingClient()).getMessageStore());
        }
    }

    static String getTopic(ServerWebsocketSession s) {
        return ofNullable(s.getRequestParameterMap().get("topic")).map(List::getFirst)
                .orElseThrow(() -> new IllegalStateException("Topic parameter missing"));
    }

    static StoreIdentifier getStoreIdentifier(MessageType messageType, ServerWebsocketSession s) {
        return new StoreIdentifier(
                getNamespace(s), messageType,
                ofNullable(s.getRequestParameterMap().get("topic")).map(List::getFirst)
                        .orElseThrow(() -> new IllegalStateException("Topic parameter missing")));
    }

    @Value
    public static class StoreIdentifier {
        String namespace;
        @With
        MessageType messageType;
        String topic;
    }
}
