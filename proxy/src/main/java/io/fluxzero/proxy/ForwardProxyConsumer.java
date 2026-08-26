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

import io.fluxzero.common.ConsistentHashing;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getLongProperty;
import static io.fluxzero.sdk.web.WebRequest.getHeaders;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.Optional.ofNullable;

@Slf4j
public class ForwardProxyConsumer implements Consumer<List<SerializedMessage>> {
    static final String METRICS_ENABLED_PROPERTY = "FLUXZERO_PROXY_METRICS_ENABLED";
    static final String MAX_CONCURRENT_REQUESTS_PROPERTY = "FLUXZERO_PROXY_FORWARD_MAX_CONCURRENT_REQUESTS";
    static final String MAX_OUTSTANDING_REQUESTS_PROPERTY = "FLUXZERO_PROXY_FORWARD_MAX_OUTSTANDING_REQUESTS";
    static final String BATCH_COMPLETION_GRACE_MILLIS_PROPERTY =
            "FLUXZERO_PROXY_FORWARD_BATCH_COMPLETION_GRACE_MILLIS";
    static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 8;
    static final int DEFAULT_MAX_OUTSTANDING_REQUESTS = 1024;
    static final Duration DEFAULT_BATCH_COMPLETION_GRACE = Duration.ofMillis(250);

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
    private final Set<ScheduledRequest> outstandingRequests;
    private final Object lifecycleMonitor;
    private final int maxConcurrentRequests;
    private final int maxOutstandingRequests;
    private final Duration batchCompletionGrace;
    private final Function<Duration, CompletableFuture<Void>> retryDelay;
    private final SegmentSerialScheduler<ScheduledRequest> scheduler;

    protected ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                                   boolean publishMetrics) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, sharedHttpClient, new AtomicBoolean(),
             DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_MAX_OUTSTANDING_REQUESTS, DEFAULT_BATCH_COMPLETION_GRACE);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_MAX_OUTSTANDING_REQUESTS, DEFAULT_BATCH_COMPLETION_GRACE);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                         int maxConcurrentRequests) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             maxConcurrentRequests, DEFAULT_MAX_OUTSTANDING_REQUESTS, DEFAULT_BATCH_COMPLETION_GRACE);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                         int maxConcurrentRequests, Function<Duration, CompletableFuture<Void>> retryDelay) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             new AtomicBoolean(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
             new Object(), maxConcurrentRequests, DEFAULT_MAX_OUTSTANDING_REQUESTS,
             DEFAULT_BATCH_COMPLETION_GRACE, retryDelay, null);
    }

    ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                         boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                         int maxConcurrentRequests, int maxOutstandingRequests, Duration batchCompletionGrace) {
        this(client, consumerName, minIndex, mainConsumer, publishMetrics, httpClient, forceStopping,
             new AtomicBoolean(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
             new Object(), maxConcurrentRequests, maxOutstandingRequests,
             batchCompletionGrace, ForwardProxyConsumer::delay, null);
    }

    private ForwardProxyConsumer(Client client, String consumerName, Long minIndex, boolean mainConsumer,
                                 boolean publishMetrics, HttpClient httpClient, AtomicBoolean forceStopping,
                                 AtomicBoolean stopping, Set<CompletableFuture<Void>> pendingResponses,
                                 Set<ScheduledRequest> outstandingRequests, Object lifecycleMonitor,
                                 int maxConcurrentRequests, int maxOutstandingRequests, Duration batchCompletionGrace,
                                 Function<Duration, CompletableFuture<Void>> retryDelay,
                                 SegmentSerialScheduler<ScheduledRequest> scheduler) {
        this.client = client;
        this.consumerName = consumerName;
        this.minIndex = minIndex;
        this.mainConsumer = mainConsumer;
        this.publishMetrics = publishMetrics;
        this.httpClient = httpClient;
        this.forceStopping = forceStopping;
        this.stopping = stopping;
        this.pendingResponses = pendingResponses;
        this.outstandingRequests = outstandingRequests;
        this.lifecycleMonitor = lifecycleMonitor;
        this.maxConcurrentRequests = requirePositiveMaxConcurrentRequests(maxConcurrentRequests);
        this.maxOutstandingRequests = requireValidMaxOutstandingRequests(
                maxOutstandingRequests, this.maxConcurrentRequests);
        this.batchCompletionGrace = requireNonNegativeBatchCompletionGrace(batchCompletionGrace);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        this.scheduler = scheduler == null
                ? new SegmentSerialScheduler<>(this.maxConcurrentRequests, this.maxOutstandingRequests) : scheduler;
    }

    public static Registration start(Client client) {
        return startManaged(client);
    }

    static Lifecycle startManaged(Client client) {
        HttpClient httpClient = newHttpClient();
        int maxConcurrentRequests = configuredMaxConcurrentRequests();
        var consumer = new ForwardProxyConsumer(
                client, defaultSettings.getConsumer(),
                IndexUtils.indexFromTimestamp(Fluxzero.currentTime().minusSeconds(2)), true,
                getBooleanProperty(METRICS_ENABLED_PROPERTY, true), httpClient, new AtomicBoolean(),
                maxConcurrentRequests, configuredMaxOutstandingRequests(maxConcurrentRequests),
                configuredBatchCompletionGrace());
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

    static int configuredMaxOutstandingRequests() {
        return configuredMaxOutstandingRequests(configuredMaxConcurrentRequests());
    }

    private static int configuredMaxOutstandingRequests(int maxConcurrentRequests) {
        return requireValidMaxOutstandingRequests(
                getIntegerProperty(MAX_OUTSTANDING_REQUESTS_PROPERTY, DEFAULT_MAX_OUTSTANDING_REQUESTS),
                maxConcurrentRequests);
    }

    static Duration configuredBatchCompletionGrace() {
        return requireNonNegativeBatchCompletionGrace(Duration.ofMillis(getLongProperty(
                BATCH_COMPLETION_GRACE_MILLIS_PROPERTY, DEFAULT_BATCH_COMPLETION_GRACE.toMillis())));
    }

    private static int requirePositiveMaxConcurrentRequests(int value) {
        if (value < 1) {
            throw new IllegalArgumentException(MAX_CONCURRENT_REQUESTS_PROPERTY + " must be >= 1");
        }
        return value;
    }

    private static int requireValidMaxOutstandingRequests(int value, int maxConcurrentRequests) {
        if (value < maxConcurrentRequests) {
            throw new IllegalArgumentException(
                    MAX_OUTSTANDING_REQUESTS_PROPERTY + " must be >= " + MAX_CONCURRENT_REQUESTS_PROPERTY);
        }
        return value;
    }

    private static Duration requireNonNegativeBatchCompletionGrace(Duration value) {
        Objects.requireNonNull(value);
        if (value.isNegative()) {
            throw new IllegalArgumentException(BATCH_COMPLETION_GRACE_MILLIS_PROPERTY + " must be >= 0");
        }
        return value;
    }

    protected Registration start() {
        log.info(isMainConsumer() ? "Starting consumer {}" : "Starting consumer {} at {}", consumerName, minIndex);
        return DefaultTracker.start(this, MessageType.WEBREQUEST, consumerConfiguration(), client);
    }

    ConsumerConfiguration consumerConfiguration() {
        return ConsumerConfiguration.builder().name(consumerName).minIndex(minIndex).threads(1).build();
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
                            activeBatch.add(scheduleRequest(s, uri, settings));
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
                        activeBatch.add(scheduleFailedRequest(s, e));
                    } catch (BatchProcessingException stoppingException) {
                        stoppingFailure = stoppingException;
                        break;
                    } catch (Throwable e2) {
                        e2.addSuppressed(e);
                        log.error("Failed to send error response. Continuing..", e2);
                    }
                }
            }
            awaitBestEffort(activeBatch);
            if (stoppingFailure != null) {
                throw stoppingFailure;
            }
        } finally {
            publishProcessBatchMetrics(start);
        }
    }

    private CompletableFuture<Void> scheduleRequest(SerializedMessage request, URI uri, WebRequestSettings settings) {
        return scheduleRequest(new ScheduledRequest(request, uri, settings));
    }

    private CompletableFuture<Void> scheduleFailedRequest(SerializedMessage request, Throwable failure) {
        return scheduleRequest(new ScheduledRequest(request, failure));
    }

    private CompletableFuture<Void> scheduleRequest(ScheduledRequest scheduledRequest) {
        SerializedMessage request = scheduledRequest.request();
        synchronized (lifecycleMonitor) {
            if (stopping.get() || forceStopping.get()) {
                throw stoppedBeforeStart(request);
            }
        }
        try {
            if (!scheduler.schedule(scheduledRequest)) {
                BatchProcessingException failure = stoppedBeforeStart(request);
                scheduledRequest.fail(failure);
                throw failure;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduledRequest.fail(e);
            throw new BatchProcessingException(
                    "Forward proxy interrupted before the request could start", e, request.getIndex());
        }
        return scheduledRequest.completion();
    }

    private void startConsumer(String name, SerializedMessage request) {
        synchronized (lifecycleMonitor) {
            if (stopping.get() || forceStopping.get()) {
                throw stoppedBeforeStart(request);
            }
            runningConsumers.computeIfAbsent(
                    name, c -> new ForwardProxyConsumer(
                            client, c, request.getIndex(), false, publishMetrics, httpClient, forceStopping, stopping,
                            pendingResponses, outstandingRequests, lifecycleMonitor,
                            maxConcurrentRequests, maxOutstandingRequests, batchCompletionGrace, retryDelay,
                            scheduler).start());
        }
    }

    private BatchProcessingException stoppedBeforeStart(SerializedMessage request) {
        return new BatchProcessingException(
                "Forward proxy stopped before the remaining request could start", request.getIndex());
    }

    private void awaitBestEffort(List<CompletableFuture<Void>> futures) {
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((ignored, error) -> null)
                    .completeOnTimeout(null, batchCompletionGrace.toNanos(), NANOSECONDS).join();
        }
    }

    protected void handle(SerializedMessage request, URI uri, WebRequestSettings settings) {
        handleAsync(request, uri, settings, null).completion().join();
    }

    private ActiveRequest handleAsync(SerializedMessage request, URI uri, WebRequestSettings settings,
                                      ScheduledRequest scheduledRequest) {
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
                if (scheduledRequest == null || !scheduledRequest.isRequeuing()) {
                    log.error("Failed to handle external request. Returning error.. ", failure);
                }
                result = asWebResponse(failure);
            }
            return result;
        }).thenCompose(result -> {
            if (scheduledRequest != null && !scheduledRequest.beginResponsePublication()) {
                return CompletableFuture.completedFuture(null);
            }
            publishHandleMessageMetrics(request, false, start, correlationData);
            return sendResponseAsync(result, request, correlationData);
        }).handle((ignored, error) -> {
            if (error != null && !forceStopping.get()) {
                log.error("Failed to publish response for external request {}. Continuing..",
                          request.getMessageId(), unwrap(error));
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
            if (!(failure instanceof CancellationException && requestFuture.isCancelled())) {
                log.error("Failed to handle external request. Returning error.. ", failure);
            }
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
            if (!(failure instanceof CancellationException && requestFuture.isCancelled())) {
                log.error("Failed to handle external request after retries. Returning error.. ", failure);
            }
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
        sendResponseAsync(response, request, DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                client, request, MessageType.WEBREQUEST));
    }

    private CompletableFuture<Void> sendResponseAsync(WebResponse response, SerializedMessage request,
                                                      Map<String, String> correlationData) {
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
        return publication;
    }

    void awaitPendingResponses() {
        CompletableFuture<?>[] snapshot = pendingResponses.toArray(CompletableFuture[]::new);
        if (snapshot.length > 0) {
            CompletableFuture.allOf(snapshot).join();
        }
    }

    void awaitActiveRequests() {
        while (!outstandingRequests.isEmpty()) {
            CompletableFuture<?>[] snapshot = outstandingRequests.stream()
                    .map(ScheduledRequest::completion).toArray(CompletableFuture[]::new);
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
        List<ScheduledRequest> requeued;
        synchronized (lifecycleMonitor) {
            stopping.set(true);
            forceStopping.set(true);
            scheduler.stopDispatching();
            requeued = outstandingRequests.stream().filter(ScheduledRequest::beginRequeue)
                    .sorted(Comparator.comparingLong(r -> ofNullable(r.request().getIndex()).orElse(Long.MAX_VALUE)))
                    .toList();
        }
        requeued.forEach(ScheduledRequest::cancelExecution);
        if (!requeued.isEmpty()) {
            SerializedMessage[] requests = requeued.stream().map(ScheduledRequest::copyForRequeue)
                    .toArray(SerializedMessage[]::new);
            CompletableFuture<Void> handoff;
            try {
                handoff = client.getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.STORED, requests);
            } catch (Throwable e) {
                requeued.forEach(request -> request.fail(e));
                log.error("Failed to return {} unfinished forward requests to the WebRequest log", requeued.size(), e);
                httpClient.shutdownNow();
                return;
            }
            pendingResponses.add(handoff);
            handoff.whenComplete((ignored, error) -> {
                pendingResponses.remove(handoff);
                if (error == null) {
                    requeued.forEach(ScheduledRequest::completeRequeue);
                } else {
                    requeued.forEach(request -> request.fail(error));
                    log.error("Failed to return {} unfinished forward requests to the WebRequest log",
                              requeued.size(), error);
                }
            });
        }
        httpClient.shutdownNow();
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

    private final class ScheduledRequest implements SegmentSerialScheduler.Task {
        private final SerializedMessage request;
        private final URI uri;
        private final WebRequestSettings settings;
        private final Throwable initialFailure;
        private final int segment;
        private final AtomicReference<RequestState> state = new AtomicReference<>(RequestState.QUEUED);
        private final AtomicReference<CancellableResponseFuture> execution = new AtomicReference<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private ScheduledRequest(SerializedMessage request, URI uri, WebRequestSettings settings) {
            this.request = request;
            this.uri = uri;
            this.settings = settings;
            this.initialFailure = null;
            this.segment = requestSegment(request);
        }

        private ScheduledRequest(SerializedMessage request, Throwable initialFailure) {
            this.request = request;
            this.uri = null;
            this.settings = null;
            this.initialFailure = initialFailure;
            this.segment = requestSegment(request);
        }

        private int requestSegment(SerializedMessage request) {
            return ofNullable(request.getSegment()).orElseGet(() -> ConsistentHashing.computeSegment(
                    Objects.toString(request.getMessageId(), Objects.toString(request.getIndex(), "unsegmented"))));
        }

        @Override
        public int segment() {
            return segment;
        }

        @Override
        public CompletableFuture<Void> completion() {
            return completion;
        }

        @Override
        public void admitted() {
            outstandingRequests.add(this);
            completion.whenComplete((ignored, error) -> outstandingRequests.remove(this));
        }

        @Override
        public void start() {
            if (initialFailure != null) {
                publishInitialFailure();
                return;
            }
            if (!state.compareAndSet(RequestState.QUEUED, RequestState.HTTP_ACTIVE)) {
                return;
            }
            ActiveRequest activeRequest = handleAsync(request, uri, settings, this);
            execution.set(activeRequest.execution());
            if (state.get() == RequestState.REQUEUING) {
                activeRequest.execution().cancel(true);
            }
            activeRequest.completion().whenComplete((ignored, error) -> finishNormally(error));
        }

        private void publishInitialFailure() {
            if (!state.compareAndSet(RequestState.QUEUED, RequestState.RESPONSE_PUBLISHING)) {
                return;
            }
            CompletableFuture<Void> publication;
            try {
                publication = sendResponseAsync(
                        asWebResponse(initialFailure), request,
                        DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                                client, request, MessageType.WEBREQUEST));
            } catch (Throwable e) {
                publication = CompletableFuture.failedFuture(e);
            }
            publication.handle((ignored, error) -> {
                        if (error != null && !forceStopping.get()) {
                            log.error("Failed to publish error response for external request {}. Continuing..",
                                      request.getMessageId(), unwrap(error));
                        }
                        return null;
                    })
                    .whenComplete((ignored, error) -> finishNormally(error));
        }

        @Override
        public void fail(Throwable error) {
            RequestState previous = state.getAndSet(RequestState.DONE);
            if (previous != RequestState.DONE && previous != RequestState.REQUEUED) {
                if (!forceStopping.get()) {
                    log.error("Failed to schedule forward request {}", request.getMessageId(), unwrap(error));
                }
                completion.complete(null);
            }
        }

        private boolean beginResponsePublication() {
            return state.compareAndSet(RequestState.HTTP_ACTIVE, RequestState.RESPONSE_PUBLISHING);
        }

        private void finishNormally(Throwable error) {
            if (state.compareAndSet(RequestState.RESPONSE_PUBLISHING, RequestState.DONE)) {
                if (error != null && !forceStopping.get()) {
                    log.error("Failed to complete forward request {}", request.getMessageId(), unwrap(error));
                }
                completion.complete(null);
            }
        }

        private boolean beginRequeue() {
            while (true) {
                RequestState current = state.get();
                if (current != RequestState.QUEUED && current != RequestState.HTTP_ACTIVE) {
                    return false;
                }
                if (state.compareAndSet(current, RequestState.REQUEUING)) {
                    return true;
                }
            }
        }

        private boolean isRequeuing() {
            return state.get() == RequestState.REQUEUING;
        }

        private void cancelExecution() {
            ofNullable(execution.get()).ifPresent(active -> active.cancel(true));
        }

        private void completeRequeue() {
            if (state.compareAndSet(RequestState.REQUEUING, RequestState.REQUEUED)) {
                completion.complete(null);
            }
        }

        private SerializedMessage copyForRequeue() {
            return new SerializedMessage(
                    request.getData(), request.getMetadata(), request.getSegment(), null, request.getSource(),
                    request.getTarget(), request.getRequestId(), request.getTimestamp(), request.getMessageId(),
                    request.getOriginalRevision());
        }

        private SerializedMessage request() {
            return request;
        }
    }

    private enum RequestState {
        QUEUED, HTTP_ACTIVE, RESPONSE_PUBLISHING, REQUEUING, REQUEUED, DONE
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
