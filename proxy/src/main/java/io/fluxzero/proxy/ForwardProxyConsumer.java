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

package io.fluxzero.proxy;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.correlation.DefaultCorrelationDataProvider;
import io.fluxzero.sdk.tracking.BatchProcessingException;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.client.DefaultTracker;
import io.fluxzero.sdk.tracking.metrics.HandleMessageEvent;
import io.fluxzero.sdk.tracking.metrics.ProcessBatchEvent;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.web.WebRequest.getHeaders;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Optional.ofNullable;

@Slf4j
public class ForwardProxyConsumer implements Consumer<List<SerializedMessage>> {
    static final String METRICS_ENABLED_PROPERTY = "FLUXZERO_PROXY_METRICS_ENABLED";

    private static final HttpClient sharedHttpClient = newHttpClient();
    protected static final WebRequestSettings defaultSettings = WebRequestSettings.builder().build();
    protected static final Serializer serializer = new ProxySerializer();
    protected static final Serializer metricsSerializer = new JacksonSerializer();

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    protected final Map<String, Registration> runningConsumers = new ConcurrentHashMap<>();

    private final Client client;
    private final String consumerName;
    private final Long minIndex;
    @Getter(value = AccessLevel.PROTECTED)
    private final boolean mainConsumer;
    private final boolean publishMetrics;
    private final HttpClient httpClient;
    private final AtomicBoolean forceStopping;
    private final Set<CompletableFuture<Void>> pendingResponses;

    protected ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                                   boolean publishMetrics) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, sharedHttpClient, new AtomicBoolean());
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             ConcurrentHashMap.newKeySet());
    }

    private ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                                 boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                                 Set<CompletableFuture<Void>> pendingResponses) {
        this.client = client;
        this.consumerName = consumerName;
        this.minIndex = minIndex;
        this.mainConsumer = mainConsumer;
        this.publishMetrics = publishMetrics;
        this.httpClient = httpClient;
        this.forceStopping = forceStopping;
        this.pendingResponses = pendingResponses;
    }

    public static Registration start(Client client) {
        return startManaged(client);
    }

    static Lifecycle startManaged(Client client) {
        HttpClient httpClient = newHttpClient();
        var consumer = new ForwardProxyConsumer(
                client, defaultSettings.getConsumer(),
                IndexUtils.indexFromTimestamp(Fluxzero.currentTime().minusSeconds(2)), true,
                getBooleanProperty(METRICS_ENABLED_PROPERTY, true), httpClient, new AtomicBoolean());
        try {
            consumer.runningConsumers.computeIfAbsent(defaultSettings.getConsumer(), c -> consumer.start());
            return new Lifecycle(consumer);
        } catch (RuntimeException | Error e) {
            httpClient.shutdownNow();
            throw e;
        }
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build();
    }

    protected Registration start() {
        log.info(isMainConsumer() ? "Starting consumer {}" : "Starting consumer {} at {}", consumerName, minIndex);
        return DefaultTracker.start(this, MessageType.WEBREQUEST,
                                    ConsumerConfiguration.builder().name(consumerName).minIndex(minIndex).threads(4)
                                            .build(), client);
    }

    @Override
    public void accept(List<SerializedMessage> serializedMessages) {
        Instant start = Instant.now();
        try {
            for (SerializedMessage s : serializedMessages) {
                if (forceStopping.get()) {
                    throw new BatchProcessingException(
                            "Forward proxy stopped before the remaining request could start", s.getIndex());
                }
                try {
                    var settings = getSettings(s);
                    if (consumerName.equals(settings.getConsumer())) {
                        URI uri = URI.create(WebRequest.getUrl(s.getMetadata()));
                        if (uri.isAbsolute()) {
                            handle(s, uri, settings);
                        }
                    } else if (isMainConsumer()) {
                        runningConsumers.computeIfAbsent(
                                settings.getConsumer(), c -> new ForwardProxyConsumer(
                                        client, c, s.getIndex(), false, publishMetrics,
                                        httpClient, forceStopping, pendingResponses).start());
                    }
                } catch (Throwable e) {
                    log.error("Failed to handle external request {}. Continuing..", s.getMessageId(), e);
                    try {
                        sendResponse(asWebResponse(e), s);
                    } catch (Throwable e2) {
                        e2.addSuppressed(e);
                        log.error("Failed to send error response. Continuing..", e2);
                    }
                }
            }
        } finally {
            publishProcessBatchMetrics(start);
        }
    }

    protected void handle(SerializedMessage request, URI uri, WebRequestSettings settings) {
        Instant start = Instant.now();
        Instant deadline = IndexUtils.timestampFromIndex(request.getIndex())
                .plus(Optional.ofNullable(settings.getTimeout()).orElse(MAX_TIMEOUT));
        if (deadline.isBefore(start)) {
            //the deadline of this request is in the past. Skipping the request to prevent handling 'old' requests.
            sendResponse(WebResponse.builder().status(504).payload("Timeout in forward proxy".getBytes())
                            .build(), request);
        }
        WebResponse webResponse;
        try {
            HttpRequest httpRequest = asHttpRequest(request, uri, settings);
            webResponse = executeRequest(httpRequest);
        } catch (Throwable e) {
            publishHandleMessageMetrics(request, true, start);
            throw e;
        }
        publishHandleMessageMetrics(request, false, start);
        sendResponse(webResponse, request);
    }

    protected HttpRequest asHttpRequest(SerializedMessage request, URI uri, WebRequestSettings settings) {
        var builder = HttpRequest.newBuilder()
                .version(HttpClient.Version.valueOf(settings.getHttpVersion().name()))
                .timeout(settings.getTimeout());
        getHeaders(request.getMetadata()).forEach((name, values) -> values.forEach(v -> builder.header(name, v)));
        builder.uri(uri).method(WebRequest.getMethod(request.getMetadata()), getBodyPublisher(request));
        return builder.build();
    }

    protected WebRequestSettings getSettings(SerializedMessage request) {
        return Optional.ofNullable(request.getMetadata().get("settings", WebRequestSettings.class))
                .orElse(defaultSettings);
    }

    protected WebResponse executeRequest(HttpRequest httpRequest) {
        try {
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            return asWebResponse(response);
        } catch (Throwable e) {
            log.error("Failed to handle external request. Returning error.. ", e);
            return asWebResponse(e);
        }
    }

    protected void sendResponse(WebResponse response, SerializedMessage request) {
        Metadata responseMetadata = response.getMetadata().addIfAbsent(
                DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(client, request, MessageType.WEBREQUEST));
        SerializedMessage serializedResponse = new SerializedMessage(
                serializer.serialize(response.getPayload()).withFormat("application/octet-stream"),
                responseMetadata, response.getMessageId(), response.getTimestamp().toEpochMilli());

        serializedResponse.setRequestId(request.getRequestId());
        serializedResponse.setTarget(request.getSource());
        CompletableFuture<Void> publication = client.getGatewayClient(MessageType.WEBRESPONSE)
                .append(Guarantee.STORED, serializedResponse);
        pendingResponses.add(publication);
        publication.whenComplete((ignored, error) -> {
            pendingResponses.remove(publication);
            if (error != null && !forceStopping.get()) {
                log.warn("Failed to store forwarded response for request {}", request.getRequestId(), error);
            }
        });
    }

    void awaitPendingResponses() {
        CompletableFuture<?>[] snapshot = pendingResponses.toArray(CompletableFuture[]::new);
        if (snapshot.length > 0) {
            CompletableFuture.allOf(snapshot).join();
        }
    }

    protected WebResponse asWebResponse(HttpResponse<byte[]> response) {
        WebResponse.Builder builder = WebResponse.builder();
        response.headers().map().forEach((name, values) -> values.forEach(v -> builder.header(name, v)));
        return builder.status(response.statusCode()).payload(response.body()).build();
    }

    protected WebResponse asWebResponse(Throwable e) {
        return WebResponse.builder().status(502).payload(
                ofNullable(e.getMessage()).orElse("Exception while handling request in proxy")
                        .getBytes()).build();
    }

    protected HttpRequest.BodyPublisher getBodyPublisher(SerializedMessage request) {
        String type = request.getData().getType();
        if (type == null || Void.class.getName().equals(type) || request.getData().getValue().length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(request.data().getValue()));
    }

    protected void publishHandleMessageMetrics(SerializedMessage request, boolean exceptionalResult, Instant start) {
        if (!publishMetrics) {
            return;
        }
        try {
            var metadata = Metadata.of(DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                    client, request, MessageType.WEBREQUEST));
            var metricsMessage = new Message(new HandleMessageEvent(
                    consumerName, ForwardProxyConsumer.class.getSimpleName(),
                    request.getIndex(), MessageType.WEBREQUEST, null, formatType(request), exceptionalResult,
                    start.until(Instant.now(), NANOS), true), metadata);
            var metricsGateway = client.getGatewayClient(MessageType.METRICS);
            metricsGateway.append(Guarantee.NONE, metricsMessage.serialize(metricsSerializer));
        } catch (Throwable e) {
            log.error("Failed to publish HandleMessage metrics", e);
        }
    }

    protected String formatType(SerializedMessage request) {
        try {
            return "%s %s".formatted(WebRequest.getMethod(request.getMetadata()),
                                     WebRequest.getUrl(request.getMetadata()));
        } catch (Exception ignored) {
            return request.getType();
        }
    }

    protected void publishProcessBatchMetrics(Instant start) {
        if (!publishMetrics) {
            return;
        }
        try {
            var metadata = Metadata.of(DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                    client, null, null));
            var tracker = Tracker.current().orElseThrow();
            var metricsMessage = new Message(new ProcessBatchEvent(
                    consumerName, tracker.getTrackerId(), MessageType.WEBREQUEST, null,
                    tracker.getMessageBatch().getSegment(),
                    tracker.getMessageBatch().getLastIndex(), tracker.getMessageBatch().getSize(),
                    start.until(Instant.now(), NANOS)), metadata);
            var metricsGateway = client.getGatewayClient(MessageType.METRICS);
            metricsGateway.append(Guarantee.NONE, metricsMessage.serialize(metricsSerializer));
        } catch (Throwable e) {
            log.error("Failed to publish HandleMessage metrics", e);
        }
    }

    static final class Lifecycle implements Registration {
        private final ForwardProxyConsumer consumer;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Lifecycle(ForwardProxyConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                try {
                    while (!consumer.runningConsumers.isEmpty()) {
                        var snapshot = List.copyOf(consumer.runningConsumers.entrySet());
                        snapshot.forEach(entry -> entry.getValue().cancel());
                        snapshot.forEach(entry -> consumer.runningConsumers.remove(entry.getKey(), entry.getValue()));
                    }
                    consumer.awaitPendingResponses();
                } finally {
                    consumer.httpClient.close();
                }
            }
        }

        void force() {
            consumer.forceStopping.set(true);
            consumer.httpClient.shutdownNow();
            consumer.pendingResponses.forEach(response -> response.cancel(false));
        }
    }
}
