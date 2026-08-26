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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.web.WebRequest.getHeaders;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.Optional.ofNullable;

@Slf4j
public class ForwardProxyConsumer implements Consumer<List<SerializedMessage>> {
    static final String METRICS_ENABLED_PROPERTY = "FLUXZERO_PROXY_METRICS_ENABLED";
    static final String MAX_CONCURRENT_REQUESTS_PROPERTY = "FLUXZERO_PROXY_FORWARD_MAX_CONCURRENT_REQUESTS";
    static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 4;

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
    private final AtomicBoolean stopping;
    private final Set<CompletableFuture<Void>> pendingResponses;
    private final Set<ActiveRequest> activeRequests;
    private final Object lifecycleMonitor;
    private final Semaphore requestCapacity;
    private final int maxConcurrentRequests;
    private final Function<Duration, CompletableFuture<Void>> retryDelay;

    protected ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                                   boolean publishMetrics) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, sharedHttpClient, new AtomicBoolean(),
             DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                         int maxConcurrentRequests) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             maxConcurrentRequests, ForwardProxyConsumer::delay);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                         int maxConcurrentRequests, Function<Duration, CompletableFuture<Void>> retryDelay) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             new AtomicBoolean(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), new Object(),
             maxConcurrentRequests, retryDelay);
    }

    private ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                                 boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                                 AtomicBoolean stopping, Set<CompletableFuture<Void>> pendingResponses,
                                 Set<ActiveRequest> activeRequests, Object lifecycleMonitor,
                                 int maxConcurrentRequests,
                                 Function<Duration, CompletableFuture<Void>> retryDelay) {
        this.client = client;
        this.consumerName = consumerName;
        this.minIndex = minIndex;
        this.mainConsumer = mainConsumer;
        this.publishMetrics = publishMetrics;
        this.httpClient = httpClient;
        this.forceStopping = forceStopping;
        this.stopping = stopping;
        this.pendingResponses = pendingResponses;
        this.activeRequests = activeRequests;
        this.lifecycleMonitor = lifecycleMonitor;
        this.maxConcurrentRequests = requirePositiveMaxConcurrentRequests(maxConcurrentRequests);
        this.requestCapacity = new Semaphore(maxConcurrentRequests, true);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    public static Registration start(Client client) {
        return startManaged(client);
    }

    static Lifecycle startManaged(Client client) {
        HttpClient httpClient = newHttpClient();
        var consumer = new ForwardProxyConsumer(
                client, defaultSettings.getConsumer(),
                IndexUtils.indexFromTimestamp(Fluxzero.currentTime().minusSeconds(2)), true,
                getBooleanProperty(METRICS_ENABLED_PROPERTY, true), httpClient, new AtomicBoolean(),
                configuredMaxConcurrentRequests());
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

    static int configuredMaxConcurrentRequests() {
        return requirePositiveMaxConcurrentRequests(getIntegerProperty(
                MAX_CONCURRENT_REQUESTS_PROPERTY, DEFAULT_MAX_CONCURRENT_REQUESTS));
    }

    private static int requirePositiveMaxConcurrentRequests(int value) {
        if (value < 1) {
            throw new IllegalArgumentException(MAX_CONCURRENT_REQUESTS_PROPERTY + " must be >= 1");
        }
        return value;
    }

    protected Registration start() {
        log.info(isMainConsumer() ? "Starting consumer {}" : "Starting consumer {} at {}", consumerName, minIndex);
        return DefaultTracker.start(this, MessageType.WEBREQUEST, consumerConfiguration(), client);
    }

    ConsumerConfiguration consumerConfiguration() {
        return ConsumerConfiguration.builder().name(consumerName).minIndex(minIndex)
                .threads(maxConcurrentRequests).maxFetchSize(1).build();
    }

    @Override
    public void accept(List<SerializedMessage> serializedMessages) {
        Instant start = Instant.now();
        List<CompletableFuture<Void>> activeBatch = new ArrayList<>(serializedMessages.size());
        BatchProcessingException stoppingFailure = null;
        try {
            for (SerializedMessage s : serializedMessages) {
                if (stopping.get() || forceStopping.get()) {
                    stoppingFailure = stoppedBeforeStart(s);
                    break;
                }
                try {
                    var settings = getSettings(s);
                    if (consumerName.equals(settings.getConsumer())) {
                        URI uri = URI.create(WebRequest.getUrl(s.getMetadata()));
                        if (uri.isAbsolute()) {
                            activeBatch.add(startRequest(s, uri, settings));
                        }
                    } else if (isMainConsumer()) {
                        startConsumer(settings.getConsumer(), s);
                    }
                } catch (BatchProcessingException e) {
                    stoppingFailure = e;
                    break;
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
            await(activeBatch);
            if (stoppingFailure != null) {
                throw stoppingFailure;
            }
        } finally {
            publishProcessBatchMetrics(start);
        }
    }

    private CompletableFuture<Void> startRequest(SerializedMessage request, URI uri, WebRequestSettings settings) {
        try {
            requestCapacity.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BatchProcessingException(
                    "Forward proxy interrupted before the request could start", e, request.getIndex());
        }
        boolean releaseCapacity = true;
        try {
            synchronized (lifecycleMonitor) {
                if (stopping.get() || forceStopping.get()) {
                    throw stoppedBeforeStart(request);
                }
                ActiveRequest activeRequest = handleAsync(request, uri, settings);
                activeRequests.add(activeRequest);
                activeRequest.completion().whenComplete((ignored, error) -> {
                    activeRequests.remove(activeRequest);
                    requestCapacity.release();
                });
                releaseCapacity = false;
                return activeRequest.completion();
            }
        } finally {
            if (releaseCapacity) {
                requestCapacity.release();
            }
        }
    }

    private void startConsumer(String name, SerializedMessage request) {
        synchronized (lifecycleMonitor) {
            if (stopping.get() || forceStopping.get()) {
                throw stoppedBeforeStart(request);
            }
            runningConsumers.computeIfAbsent(
                    name, c -> new ForwardProxyConsumer(
                            client, c, request.getIndex(), false, publishMetrics, httpClient, forceStopping, stopping,
                            pendingResponses, activeRequests, lifecycleMonitor, maxConcurrentRequests,
                            retryDelay).start());
        }
    }

    private BatchProcessingException stoppedBeforeStart(SerializedMessage request) {
        return new BatchProcessingException(
                "Forward proxy stopped before the remaining request could start", request.getIndex());
    }

    private void await(List<CompletableFuture<Void>> futures) {
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
    }

    protected void handle(SerializedMessage request, URI uri, WebRequestSettings settings) {
        handleAsync(request, uri, settings).completion().join();
    }

    private ActiveRequest handleAsync(SerializedMessage request, URI uri, WebRequestSettings settings) {
        Instant start = Instant.now();
        Map<String, String> correlationData = DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                client, request, MessageType.WEBREQUEST);
        Instant deadline = IndexUtils.timestampFromIndex(request.getIndex())
                .plus(Optional.ofNullable(settings.getTimeout()).orElse(MAX_TIMEOUT));
        CancellableResponseFuture execution = new CancellableResponseFuture();
        if (deadline.isBefore(start)) {
            //the deadline of this request is in the past. Skipping the request to prevent handling 'old' requests.
            execution.complete(WebResponse.builder().status(504).payload("Timeout in forward proxy".getBytes())
                                       .build());
        } else if (settings.getMaxRetries() > 0) {
            executeRequestWithRetries(request, uri, settings, deadline, execution)
                    .whenComplete((response, error) -> complete(execution, response, error));
        } else {
            try {
                executeRequestAsync(asHttpRequest(request, uri, settings), execution)
                        .whenComplete((response, error) -> complete(execution, response, error));
            } catch (Throwable e) {
                execution.complete(asWebResponse(e));
            }
        }
        CompletableFuture<Void> completion = execution.handle((response, error) -> {
            WebResponse result = response;
            if (error != null) {
                Throwable failure = unwrap(error);
                log.error("Failed to handle external request. Returning error.. ", failure);
                result = asWebResponse(failure);
            }
            publishHandleMessageMetrics(request, false, start, correlationData);
            sendResponse(result, request, correlationData);
            return (Void) null;
        }).exceptionally(error -> {
            Throwable failure = unwrap(error);
            log.error("Failed to handle external request {}. Continuing..", request.getMessageId(), failure);
            try {
                sendResponse(asWebResponse(failure), request, correlationData);
            } catch (Throwable responseError) {
                responseError.addSuppressed(failure);
                log.error("Failed to send error response. Continuing..", responseError);
            }
            return null;
        });
        return new ActiveRequest(execution, completion);
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
        CancellableResponseFuture requestFuture = new CancellableResponseFuture();
        executeRequestAsync(httpRequest, requestFuture)
                .whenComplete((response, error) -> complete(requestFuture, response, error));
        return requestFuture.join();
    }

    private CompletableFuture<WebResponse> executeRequestAsync(HttpRequest httpRequest,
                                                               CancellableResponseFuture requestFuture) {
        CompletableFuture<HttpResponse<byte[]>> attempt;
        try {
            attempt = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Throwable e) {
            log.error("Failed to handle external request. Returning error.. ", e);
            return CompletableFuture.completedFuture(asWebResponse(e));
        }
        requestFuture.track(attempt);
        return attempt.handle((response, error) -> {
            if (error == null) {
                return asWebResponse(response);
            }
            Throwable failure = unwrap(error);
            log.error("Failed to handle external request. Returning error.. ", failure);
            return asWebResponse(failure);
        });
    }

    private CompletableFuture<WebResponse> executeRequestWithRetries(
            SerializedMessage request, URI uri, WebRequestSettings settings, Instant deadline,
            CancellableResponseFuture requestFuture) {
        return executeRequestWithRetries(
                request, uri, settings, Math.max(0, settings.getMaxRetries()), deadline, requestFuture);
    }

    private CompletableFuture<WebResponse> executeRequestWithRetries(
            SerializedMessage request, URI uri, WebRequestSettings settings, int retriesRemaining, Instant deadline,
            CancellableResponseFuture requestFuture) {
        if (requestFuture.isCancelled()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return CompletableFuture.completedFuture(
                    asWebResponse(new java.net.http.HttpTimeoutException("Timeout in forward proxy")));
        }

        HttpRequest httpRequest;
        try {
            httpRequest = asHttpRequest(request, uri, settings.toBuilder().timeout(remaining).build());
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(asWebResponse(e));
        }

        CompletableFuture<HttpResponse<byte[]>> attempt;
        try {
            attempt = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(asWebResponse(e));
        }
        requestFuture.track(attempt);
        return attempt.handle((response, error) -> {
            if (error == null) {
                WebResponse mappedResponse = asWebResponse(response);
                if (retriesRemaining > 0 && settings.getRetryableStatusCodes().contains(response.statusCode())) {
                    return retry(request, uri, settings, retriesRemaining, deadline, mappedResponse, requestFuture);
                }
                return CompletableFuture.completedFuture(mappedResponse);
            }
            Throwable failure = unwrap(error);
            WebResponse mappedFailure = asWebResponse(failure);
            if (retriesRemaining > 0 && failure instanceof IOException && Instant.now().isBefore(deadline)) {
                return retry(request, uri, settings, retriesRemaining, deadline, mappedFailure, requestFuture);
            }
            log.error("Failed to handle external request after retries. Returning error.. ", failure);
            return CompletableFuture.completedFuture(mappedFailure);
        }).thenCompose(Function.identity());
    }

    private CompletableFuture<WebResponse> retry(
            SerializedMessage request, URI uri, WebRequestSettings settings, int retriesRemaining, Instant deadline,
            WebResponse exhaustedResult, CancellableResponseFuture requestFuture) {
        Duration delay = normalizedRetryDelay(settings);
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero() || delay.compareTo(remaining) >= 0) {
            return CompletableFuture.completedFuture(exhaustedResult);
        }
        CompletableFuture<Void> delayFuture = retryDelay.apply(delay);
        requestFuture.track(delayFuture);
        return delayFuture.thenCompose(ignored -> Instant.now().isBefore(deadline)
                ? executeRequestWithRetries(
                        request, uri, settings, retriesRemaining - 1, deadline, requestFuture)
                : CompletableFuture.completedFuture(exhaustedResult));
    }

    private Duration normalizedRetryDelay(WebRequestSettings settings) {
        Duration delay = settings.getRetryDelay();
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    private static CompletableFuture<Void> delay(Duration duration) {
        if (duration.isZero()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> {}, CompletableFuture.delayedExecutor(duration.toNanos(), NANOSECONDS));
    }

    private static void complete(CancellableResponseFuture target, WebResponse response, Throwable error) {
        if (error == null) {
            target.complete(response);
        } else {
            target.completeExceptionally(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while (result instanceof CompletionException && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    protected void sendResponse(WebResponse response, SerializedMessage request) {
        sendResponse(response, request, DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                client, request, MessageType.WEBREQUEST));
    }

    private void sendResponse(WebResponse response, SerializedMessage request, Map<String, String> correlationData) {
        Metadata responseMetadata = response.getMetadata().addIfAbsent(correlationData);
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

    void awaitActiveRequests() {
        while (!activeRequests.isEmpty()) {
            CompletableFuture<?>[] snapshot = activeRequests.stream()
                    .map(ActiveRequest::completion).toArray(CompletableFuture[]::new);
            if (snapshot.length > 0) {
                CompletableFuture.allOf(snapshot).join();
            }
        }
    }

    private void stopAccepting() {
        synchronized (lifecycleMonitor) {
            stopping.set(true);
        }
    }

    void forceActiveRequests() {
        List<ActiveRequest> snapshot;
        synchronized (lifecycleMonitor) {
            stopping.set(true);
            forceStopping.set(true);
            snapshot = List.copyOf(activeRequests);
        }
        snapshot.forEach(request -> request.execution().cancel(true));
        httpClient.shutdownNow();
        pendingResponses.forEach(response -> response.cancel(false));
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
        publishHandleMessageMetrics(request, exceptionalResult, start,
                                    DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                                            client, request, MessageType.WEBREQUEST));
    }

    private void publishHandleMessageMetrics(SerializedMessage request, boolean exceptionalResult, Instant start,
                                             Map<String, String> correlationData) {
        if (!publishMetrics) {
            return;
        }
        try {
            var metadata = Metadata.of(correlationData);
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

    private record ActiveRequest(CancellableResponseFuture execution, CompletableFuture<Void> completion) {
    }

    private static final class CancellableResponseFuture extends CompletableFuture<WebResponse> {
        private final AtomicReference<CompletableFuture<?>> activeOperation = new AtomicReference<>();

        private void track(CompletableFuture<?> operation) {
            activeOperation.set(operation);
            if (isCancelled()) {
                operation.cancel(true);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            CompletableFuture<?> operation = activeOperation.get();
            if (operation != null) {
                operation.cancel(mayInterruptIfRunning);
            }
            return true;
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
                consumer.stopAccepting();
                try {
                    while (!consumer.runningConsumers.isEmpty()) {
                        var snapshot = List.copyOf(consumer.runningConsumers.entrySet());
                        snapshot.forEach(entry -> entry.getValue().cancel());
                        snapshot.forEach(entry -> consumer.runningConsumers.remove(entry.getKey(), entry.getValue()));
                    }
                    consumer.awaitActiveRequests();
                    consumer.awaitPendingResponses();
                } finally {
                    consumer.httpClient.close();
                }
            }
        }

        void force() {
            consumer.forceActiveRequests();
        }
    }
}
