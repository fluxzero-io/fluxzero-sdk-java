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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.IndexUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.newPlatformThreadFactory;
import static io.fluxzero.sdk.common.ClientUtils.waitForResults;
import static io.fluxzero.sdk.tracking.client.DefaultTracker.start;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Default implementation of the {@link RequestHandler} interface.
 * <p>
 * This handler supports both single and batch request dispatching, tracking responses using an internal
 * {@link java.util.concurrent.ConcurrentHashMap} keyed by {@code requestId}. When a request is sent, the handler
 * subscribes to a corresponding result log (e.g., result or web response) via a
 * {@link io.fluxzero.sdk.tracking.client.TrackingClient}, which listens for responses targeted at this client only.
 *
 * <p>Each request is assigned a unique {@code requestId} and tagged with the client's {@code source} identifier.
 * When a response with a matching {@code requestId} is received, the corresponding {@link CompletableFuture} is
 * completed.
 *
 * <p>If no response is received within the configured timeout (default: 200 seconds), the future is completed
 * exceptionally.
 * <p>
 * This request handle supports chunked responses. Request senders that can deal with chunked responses should use
 * {@link #sendRequest(SerializedMessage, Consumer, Duration, Consumer)}}. If a chunked response is received, but the
 * request sender expected a single response, the intermediate responses are aggregated before completing the request.
 *
 * <p>Features:
 * <ul>
 *   <li>Supports both single and batch request dispatching.</li>
 *   <li>Tracks responses via the configured {@link MessageType} and filters using {@code filterMessageTarget = true}.</li>
 *   <li>Ensures startup of the underlying result tracker on first request dispatch.</li>
 *   <li>Cleans up subscriptions and pending futures on {@link #close()}.</li>
 * </ul>
 *
 * @see RequestHandler
 * @see MessageType#RESULT
 * @see MessageType#WEBRESPONSE
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultRequestHandler extends AbstractNamespaced<RequestHandler> implements RequestHandler {

    private static final int RESPONSE_MAX_FETCH_SIZE =
            Math.max(1, Integer.getInteger("fluxzero.requestHandlerMaxFetchSize", 65_536));

    private final Client client;
    private final MessageType resultType;
    private final Duration timeout;
    private final String responseConsumerName;
    private final ExecutorService responseExecutor;

    private final Map<Integer, ResponseCallback> callbacks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor timeoutExecutor = timeoutExecutor();
    private CompletableFuture<Void> responseProcessing = CompletableFuture.completedFuture(null);
    private volatile Registration registration;

    @Override
    protected RequestHandler createForNamespace(String namespace) {
        var clientForNamespace = client.forNamespace(namespace);
        return clientForNamespace == client
                ? this : new DefaultRequestHandler(clientForNamespace, resultType, timeout, responseConsumerName);
    }

    /**
     * Constructs a DefaultRequestHandler with the specified client, message type, timeout, and response consumer name.
     * This constructor creates an internal worker pool for handling requests and responses.
     *
     * @param client               the client responsible for sending and receiving messages
     * @param resultType           the type of message expected as a result
     * @param timeout              the maximum duration to wait for a response
     * @param responseConsumerName the name of the consumer responsible for handling the response
     */
    public DefaultRequestHandler(Client client, MessageType resultType, Duration timeout, String responseConsumerName) {
        this(client, resultType, timeout, responseConsumerName,
             newWorkerPool("request-handler-%s-%s".formatted(client.name(), resultType.name().toLowerCase()), 8));
    }

    /**
     * Constructs a DefaultRequestHandler with the specified client and message type, and a default timeout of 200
     * seconds. This constructor creates an internal worker pool for handling requests and responses.
     * <p>
     * Uses a default name for the result consumer based on the application name.
     *
     * @param client     the client responsible for sending and receiving messages
     * @param resultType the type of message expected as a result
     */
    public DefaultRequestHandler(Client client, MessageType resultType) {
        this(client, resultType, Duration.ofSeconds(200), format("%s_%s", client.name(), "$request-handler"));
    }

    /**
     * Sends a request and processes the response, combining intermediate responses (if any) with the final response
     * data. This method ensures intermediate results are aggregated and included in the final output.
     */
    @Override
    public CompletableFuture<SerializedMessage> sendRequest(SerializedMessage request,
                                                            Consumer<SerializedMessage> requestSender,
                                                            Duration timeout) {
        ensureStarted();
        CompletableFuture<SerializedMessage> future = prepareRequest(request, timeout, null);
        requestSender.accept(request);
        return future;
    }

    @Override
    public CompletableFuture<SerializedMessage> sendRequest(SerializedMessage request,
                                                            Consumer<SerializedMessage> requestSender,
                                                            Duration timeout,
                                                            Consumer<SerializedMessage> intermediateCallback) {
        ensureStarted();
        CompletableFuture<SerializedMessage> future = prepareRequest(request, timeout, intermediateCallback);
        requestSender.accept(request);
        return future;
    }

    @Override
    public List<CompletableFuture<SerializedMessage>> sendRequests(List<SerializedMessage> requests,
                                                                   Consumer<List<SerializedMessage>> requestSender) {
        return sendRequests(requests, requestSender, timeout);
    }

    @Override
    public List<CompletableFuture<SerializedMessage>> sendRequests(List<SerializedMessage> requests,
                                                                   Consumer<List<SerializedMessage>> requestSender,
                                                                   Duration timeout) {
        long started = Boolean.getBoolean("fluxzero.requestDispatchDiagnostics") ? System.nanoTime() : 0L;
        ensureStarted();
        if (requests.isEmpty()) {
            return List.of();
        }
        Duration effectiveTimeout = timeout == null ? this.timeout : timeout;
        List<CompletableFuture<SerializedMessage>> futures = new ArrayList<>(requests.size());
        int[] requestIds = new int[requests.size()];
        for (int i = 0; i < requests.size(); i++) {
            PreparedRequest prepared = prepareRequest(requests.get(i), effectiveTimeout, null, false);
            requestIds[i] = prepared.requestId();
            futures.add(prepared.result());
        }
        long preparedAt = started == 0L ? 0L : System.nanoTime();
        if (!effectiveTimeout.isNegative()) {
            scheduleBatchTimeout(requests, requestIds, futures, effectiveTimeout);
        }
        long scheduledAt = started == 0L ? 0L : System.nanoTime();
        requestSender.accept(requests);
        if (started != 0L) {
            System.out.printf("RequestHandler registered %,d requests: prepare %.3f ms, timeout %.3f ms, send %.3f ms%n",
                              requests.size(),
                              (preparedAt - started) / 1_000_000.0,
                              (scheduledAt - preparedAt) / 1_000_000.0,
                              (System.nanoTime() - scheduledAt) / 1_000_000.0);
        }
        return futures;
    }

    protected CompletableFuture<SerializedMessage> prepareRequest(SerializedMessage request, Duration timeout,
                                                                  Consumer<SerializedMessage> intermediateCallback) {
        if (timeout == null) {
            timeout = this.timeout;
        }
        return prepareRequest(request, timeout, intermediateCallback, true).result();
    }

    private PreparedRequest prepareRequest(SerializedMessage request, Duration timeout,
                                           Consumer<SerializedMessage> intermediateCallback,
                                           boolean scheduleTimeout) {
        int requestId = nextId.getAndIncrement();
        CompletableFuture<SerializedMessage> result = new CompletableFuture<>();
        ResponseCallback callback = new ResponseCallback(intermediateCallback, result);
        callbacks.put(requestId, callback);
        Metadata metadata = ofNullable(request.getMetadata()).orElseGet(Metadata::empty);
        if (timeout.isNegative()) {
            request.setMetadata(metadata.without(REQUEST_TIMEOUT_METADATA_KEY));
        } else {
            request.setMetadata(metadata.with(REQUEST_TIMEOUT_METADATA_KEY, Long.toString(timeout.toMillis())));
        }
        request.setRequestId(requestId);
        request.setSource(client.id());
        ScheduledFuture<?> timeoutTask = scheduleTimeout && !timeout.isNegative()
                ? timeoutExecutor.schedule(
                        () -> callback.completeExceptionally(timeoutException(request, requestId, timeout)),
                        timeout.toMillis(), MILLISECONDS)
                : null;
        result.whenComplete((m, e) -> {
            callbacks.remove(requestId, callback);
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
        });
        return new PreparedRequest(requestId, result);
    }

    private void scheduleBatchTimeout(List<SerializedMessage> requests, int[] requestIds,
                                      List<CompletableFuture<SerializedMessage>> results, Duration timeout) {
        ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(() -> {
            for (int i = 0; i < requestIds.length; i++) {
                ResponseCallback callback = callbacks.get(requestIds[i]);
                if (callback != null) {
                    callback.completeExceptionally(timeoutException(requests.get(i), requestIds[i], timeout));
                }
            }
        }, timeout.toMillis(), MILLISECONDS);
        AtomicInteger remaining = new AtomicInteger(results.size());
        results.forEach(result -> result.whenComplete((message, error) -> {
            if (remaining.decrementAndGet() == 0) {
                timeoutTask.cancel(false);
            }
        }));
    }

    private Throwable timeoutException(SerializedMessage request, int requestId, Duration timeout) {
        return FluxzeroErrors.requestTimeoutException(
                "message", request.getData().getType(), request.getMessageId(), requestId, resultType.name(), timeout);
    }

    protected void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            registration = start(this::handleResults, resultType, ConsumerConfiguration.builder()
                    .name(responseConsumerName)
                    .maxFetchSize(RESPONSE_MAX_FETCH_SIZE)
                    .ignoreSegment(true)
                    .clientControlledIndex(true)
                    .filterMessageTarget(true)
                    .minIndex(IndexUtils.indexFromTimestamp(
                            Fluxzero.currentTime().minusSeconds(2)))
                    .namespace(client.namespace())
                    .build(), client);
        }
    }

    protected synchronized void handleResults(List<SerializedMessage> messages) {
        responseProcessing = responseProcessing.exceptionally(e -> null)
                .thenRunAsync(() -> processResults(messages), responseExecutor);
    }

    private void processResults(List<SerializedMessage> messages) {
        long started = Boolean.getBoolean("fluxzero.resultProcessingDiagnostics") ? System.nanoTime() : 0L;
        messages.stream().filter(m -> m.getRequestId() != null).forEach(response -> {
            var callback = callbacks.get(response.getRequestId());
            if (callback == null) {
                log.warn("Received response with index {} for unknown request {}", response.getIndex(),
                         response.getRequestId());
                return;
            }
            callback.process(response, responseExecutor);
        });
        if (started != 0L) {
            System.out.printf("Processed %,d request results in %.3f ms%n", messages.size(),
                              (System.nanoTime() - started) / 1_000_000.0);
        }
    }

    /**
     * Completes a pending request exceptionally and removes its response callback.
     *
     * @param requestId the request id assigned by {@link #prepareRequest(SerializedMessage, Duration, Consumer)}
     * @param error     the error that should complete the pending request
     * @return {@code true} when a pending request was found and completed
     */
    protected boolean completeRequestExceptionally(int requestId, Throwable error) {
        ResponseCallback callback = callbacks.remove(requestId);
        return callback != null && callback.completeExceptionally(error);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            super.close();
            waitForResults(Duration.ofSeconds(2),
                           callbacks.values().stream().map(ResponseCallback::finalCallback).toList());
            completePendingRequests(new IllegalStateException("Request handler has closed"));
            if (registration != null) {
                registration.cancel();
            }
            timeoutExecutor.shutdownNow();
            responseExecutor.shutdown();
        }
    }

    private void completePendingRequests(Throwable error) {
        callbacks.forEach((requestId, callback) -> {
            if (callbacks.remove(requestId, callback)) {
                callback.completeExceptionally(error);
            }
        });
    }

    private ScheduledThreadPoolExecutor timeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, newPlatformThreadFactory("request-timeout"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    protected static class ResponseCallback {
        private final Consumer<SerializedMessage> intermediateCallback;
        private final CompletableFuture<SerializedMessage> finalCallback;
        private List<SerializedMessage> intermediates;
        private CompletableFuture<Void> processingChain;

        ResponseCallback(Consumer<SerializedMessage> intermediateCallback,
                         CompletableFuture<SerializedMessage> finalCallback) {
            this.intermediateCallback = intermediateCallback;
            this.finalCallback = finalCallback;
        }

        synchronized void process(SerializedMessage response, Executor executor) {
            if (processingChain == null && response.lastChunk()) {
                process(response);
                return;
            }
            if (processingChain == null) {
                processingChain = CompletableFuture.completedFuture(null);
            }
            processingChain = processingChain.exceptionally(e -> null)
                    .thenRunAsync(() -> process(response), executor);
        }

        CompletableFuture<SerializedMessage> finalCallback() {
            return finalCallback;
        }

        boolean completeExceptionally(Throwable error) {
            return finalCallback.completeExceptionally(error);
        }

        private void process(SerializedMessage response) {
            try {
                if (response.lastChunk()) {
                    finalCallback.complete(aggregate(response));
                } else if (intermediateCallback == null) {
                    if (intermediates == null) {
                        intermediates = new ArrayList<>();
                    }
                    intermediates.add(response);
                } else {
                    intermediateCallback.accept(response);
                }
            } catch (Throwable e) {
                finalCallback.completeExceptionally(e);
                throw e;
            }
        }

        private SerializedMessage aggregate(SerializedMessage last) {
            if (intermediates == null) {
                return last;
            }
            var data = last.getData();
            byte[][] chunks = new byte[intermediates.size() + 1][];
            for (int i = 0; i < intermediates.size(); i++) {
                chunks[i] = intermediates.get(i).data().getValue();
            }
            chunks[chunks.length - 1] = data.getValue();
            return last.withData(new Data<>(ObjectUtils.join(chunks), data.getType(), data.getRevision(),
                                            data.getFormat()));
        }
    }

    private record PreparedRequest(int requestId, CompletableFuture<SerializedMessage> result) {
    }

}
