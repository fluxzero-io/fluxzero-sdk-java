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

package io.fluxzero.testserver;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.tracking.HasMessageStore;
import io.fluxzero.common.tracking.MessageStore;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.scheduling.client.LocalSchedulingClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.testserver.metrics.DefaultMetricsLog;
import io.fluxzero.testserver.metrics.MetricsLog;
import io.fluxzero.testserver.metrics.NoOpMetricsLog;
import io.fluxzero.testserver.scheduling.TestServerScheduleStore;
import io.fluxzero.testserver.websocket.ConsumerEndpoint;
import io.fluxzero.testserver.websocket.EventSourcingEndpoint;
import io.fluxzero.testserver.websocket.KeyValueEndPoint;
import io.fluxzero.testserver.websocket.ProducerEndpoint;
import io.fluxzero.testserver.websocket.SchedulingEndpoint;
import io.fluxzero.testserver.websocket.SearchEndpoint;
import io.undertow.Undertow;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.PathHandler;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Session;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

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
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.deploy;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.deployFromSession;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.getProjectId;
import static io.undertow.Handlers.path;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

@Slf4j
public class TestServer {

    private static final Function<String, Client> clients = memoize(
            projectId -> new TestServerProject(LocalClient.newInstance()));
    private static final Function<String, MetricsLog> metricsLogSupplier =
            memoize(projectId -> new DefaultMetricsLog(getMessageStore(projectId, METRICS)));

    public static void main(final String[] args) {
        start(getIntegerProperty("FLUXZERO_PORT", getIntegerProperty("FLUX_PORT", getIntegerProperty("port", 8888))));
    }

    public static void start(int port) {
        PathHandler pathHandler = path();
        for (MessageType messageType : Arrays.asList(METRICS, EVENT, COMMAND, QUERY, RESULT, ERROR, WEBREQUEST, WEBRESPONSE)) {
            pathHandler = deploy(projectId -> new ProducerEndpoint(getMessageStore(projectId, messageType))
                                         .metricsLog(messageType == METRICS ? new NoOpMetricsLog() : metricsLogSupplier.apply(projectId)),
                                 format("/%s/", gatewayPath(messageType)), pathHandler);
            pathHandler = deploy(projectId -> new ConsumerEndpoint(getMessageStore(projectId, messageType), messageType)
                                         .metricsLog(messageType == METRICS ? new NoOpMetricsLog() : metricsLogSupplier.apply(projectId)),
                                 format("/%s/", trackingPath(messageType)), pathHandler);
        }
        pathHandler = deploy(projectId -> new ConsumerEndpoint(getMessageStore(projectId, NOTIFICATION), NOTIFICATION)
                                     .metricsLog(metricsLogSupplier.apply(projectId)),
                             format("/%s/", trackingPath(NOTIFICATION)), pathHandler);

        for (MessageType messageType : MessageType.values()) {
            switch (messageType) {
                case CUSTOM: {
                    pathHandler = deployFromSession(
                            ObjectUtils.<String, String, Endpoint>memoize((projectId, topic) -> new ProducerEndpoint(
                                            getMessageStore(projectId, messageType, topic))
                                            .metricsLog(metricsLogSupplier.apply(projectId)))
                                    .compose(s -> new SimpleEntry<>(getProjectId(s), getTopic(s))),
                            format("/%s/", gatewayPath(messageType)), pathHandler);
                }
                case DOCUMENT: {
                    pathHandler = deployFromSession(
                            ObjectUtils.<String, String, Endpoint>memoize((projectId, topic) -> new ConsumerEndpoint(
                                            getMessageStore(projectId, messageType, topic), messageType)
                                            .metricsLog(metricsLogSupplier.apply(projectId)))
                                    .compose(s -> new SimpleEntry<>(getProjectId(s), getTopic(s))),
                            format("/%s/", trackingPath(messageType)), pathHandler);
                    break;
                }
            }
        }

        pathHandler = deploy(projectId -> new EventSourcingEndpoint(clients.apply(projectId).getEventStoreClient())
                .metricsLog(metricsLogSupplier.apply(projectId)), format("/%s/", eventSourcingPath()), pathHandler);
        pathHandler = deploy(projectId -> new KeyValueEndPoint(clients.apply(projectId).getKeyValueClient())
                .metricsLog(metricsLogSupplier.apply(projectId)), format("/%s/", keyValuePath()), pathHandler);
        pathHandler = deploy(projectId -> new SearchEndpoint(clients.apply(projectId).getSearchClient())
                .metricsLog(metricsLogSupplier.apply(projectId)), format("/%s/", searchPath()), pathHandler);
        pathHandler = deploy(projectId -> new SchedulingEndpoint(clients.apply(projectId).getSchedulingClient())
                .metricsLog(metricsLogSupplier.apply(projectId)), format("/%s/", schedulingPath()), pathHandler);
        pathHandler = deploy(projectId -> new ConsumerEndpoint((MessageStore) clients.apply(projectId).getSchedulingClient(), SCHEDULE)
                                     .metricsLog(metricsLogSupplier.apply(projectId)),
                             format("/%s/", trackingPath(SCHEDULE)), pathHandler);
        pathHandler = pathHandler.addPrefixPath("/health", exchange -> {
            exchange.getResponseHeaders().put(CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Healthy");
        });

        GracefulShutdownHandler shutdownHandler = new GracefulShutdownHandler(pathHandler);

        Undertow server = Undertow.builder().addHttpListener(port, "0.0.0.0").setHandler(shutdownHandler).build();
        server.start();

        getRuntime().addShutdownHook(Thread.ofVirtual().name("fluxzero-test-server-shutdown").unstarted(() -> {
            log.info("Initiating controlled shutdown");
            shutdownHandler.shutdown();
            try {
                shutdownHandler.awaitShutdown(1000);
            } catch (InterruptedException e) {
                log.warn("Thread to kill server was interrupted");
                Thread.currentThread().interrupt();
            }
        }));

        log.info("Fluxzero test server running on port {}", port);
    }

    private static MessageStore getMessageStore(String projectId, MessageType messageType) {
        return getMessageStore(projectId, messageType, null);
    }

    private static MessageStore getMessageStore(String projectId, MessageType messageType, String topic) {
        if (messageType == NOTIFICATION) {
            messageType = EVENT;
        }
        var client = (HasMessageStore) clients.apply(projectId).getTrackingClient(messageType, topic);
        return client.getMessageStore();
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

    static String getTopic(Session s) {
        return ofNullable(s.getRequestParameterMap().get("topic")).map(List::getFirst)
                .orElseThrow(() -> new IllegalStateException("Topic parameter missing"));
    }

    static StoreIdentifier getStoreIdentifier(MessageType messageType, Session s) {
        return new StoreIdentifier(
                getProjectId(s), messageType,
                ofNullable(s.getRequestParameterMap().get("topic")).map(List::getFirst)
                        .orElseThrow(() -> new IllegalStateException("Topic parameter missing")));
    }

    @Value
    public static class StoreIdentifier {
        String projectId;
        @With
        MessageType messageType;
        String topic;
    }
}