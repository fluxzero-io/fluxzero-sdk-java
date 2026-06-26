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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.newPlatformThreadFactory;
import static io.fluxzero.sdk.common.ClientUtils.memoize;
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
public class DefaultRequestHandler implements RequestHandler {

    private final Client client;
    private final MessageType resultType;
    private final Duration timeout;
    private final String responseConsumerName;
    private final ExecutorService responseExecutor;

    private final Map<Integer, ResponseCallback> callbacks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor timeoutExecutor = timeoutExecutor();
    private volatile Registration registration;

    private final Function<String, RequestHandler> handlerSupplier = memoize(this::supplyRequestHandler);

    private RequestHandler supplyRequestHandler(String namespace) {
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
        List<SerializedMessage> intermediates = new CopyOnWriteArrayList<>();
        CompletableFuture<SerializedMessage> future = sendRequest(request, requestSender, timeout, intermediates::add);
        return future.thenApply(m -> {
            if (intermediates.isEmpty()) {
                return m;
            }
            var data = m.getData();
            byte[] allBytes = ObjectUtils.join(Stream.concat(
                    intermediates.stream().map(i -> i.data().getValue()),
                    Stream.of(data.getValue())).toArray(byte[][]::new));
            return m.withData(new Data<>(allBytes, data.getType(), data.getRevision(), data.getFormat()));
        });
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
    public RequestHandler forNamespace(String namespace) {
        return handlerSupplier.apply(namespace);
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
        ensureStarted();
        List<CompletableFuture<SerializedMessage>> futures = new ArrayList<>();
        requestSender.accept(requests.stream().peek(request -> futures.add(prepareRequest(request, timeout, null)))
                                     .collect(Collectors.toList()));
        return futures;
    }

    protected CompletableFuture<SerializedMessage> prepareRequest(SerializedMessage request, Duration timeout,
                                                                  Consumer<SerializedMessage> intermediateCallback) {
        int requestId = nextId.getAndIncrement();
        CompletableFuture<SerializedMessage> rawResult = new CompletableFuture<>();
        CompletableFuture<SerializedMessage> result = rawResult;
        if (timeout == null) {
            timeout = this.timeout;
        }
        ScheduledFuture<?> timeoutTask = null;
        if (intermediateCallback == null) {
            List<SerializedMessage> intermediates = new CopyOnWriteArrayList<>();
            intermediateCallback = intermediates::add;
            result = result.thenApply(m -> {
                if (intermediates.isEmpty()) {
                    return m;
                }
                var data = m.getData();
                byte[] allBytes = ObjectUtils.join(Stream.concat(
                        intermediates.stream().map(i -> i.data().getValue()),
                        Stream.of(data.getValue())).toArray(byte[][]::new));
                return m.withData(new Data<>(allBytes, data.getType(), data.getRevision(), data.getFormat()));
            });
        }
        Metadata metadata = ofNullable(request.getMetadata()).orElseGet(Metadata::empty);
        if (timeout.isNegative()) {
            request.setMetadata(metadata.without(REQUEST_TIMEOUT_METADATA_KEY));
        } else {
            request.setMetadata(metadata.with(REQUEST_TIMEOUT_METADATA_KEY, timeout.toMillis()));
            Duration effectiveTimeout = timeout;
            String requestDataType = request.getData().getType();
            String messageId = request.getMessageId();
            String resultTypeName = resultType.name();
            timeoutTask = timeoutExecutor.schedule(
                    () -> rawResult.completeExceptionally(FluxzeroErrors.requestTimeoutException(
                            "message", requestDataType, messageId, requestId, resultTypeName, effectiveTimeout)),
                    effectiveTimeout.toMillis(), MILLISECONDS);
        }
        ScheduledFuture<?> finalTimeoutTask = timeoutTask;
        result.whenComplete((m, e) -> {
            callbacks.remove(requestId);
            if (finalTimeoutTask != null) {
                finalTimeoutTask.cancel(false);
            }
        });
        callbacks.put(requestId, new ResponseCallback(intermediateCallback, rawResult));
        request.setRequestId(requestId);
        request.setSource(client.id());
        return result;
    }

    protected void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            registration = start(this::handleResults, resultType, ConsumerConfiguration.builder()
                    .name(responseConsumerName)
                    .ignoreSegment(true)
                    .clientControlledIndex(true)
                    .filterMessageTarget(true)
                    .minIndex(IndexUtils.indexFromTimestamp(
                            Fluxzero.currentTime().minusSeconds(2)))
                    .namespace(client.namespace())
                    .build(), client);
        }
    }

    protected void handleResults(List<SerializedMessage> messages) {
        messages.stream().filter(m -> m.getRequestId() != null).forEach(response -> {
            var callback = callbacks.get(response.getRequestId());
            if (callback == null) {
                log.warn("Received response with index {} for unknown request {}", response.getIndex(),
                         response.getRequestId());
                return;
            }
            callback.enqueue(response, responseExecutor);
        });
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
        waitForResults(Duration.ofSeconds(2),
                       callbacks.values().stream().map(ResponseCallback::finalCallback).toList());
        completePendingRequests(new IllegalStateException("Request handler has closed"));
        if (registration != null) {
            registration.cancel();
        }
        timeoutExecutor.shutdownNow();
        responseExecutor.shutdown();
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
        private CompletableFuture<Void> processingChain = CompletableFuture.completedFuture(null);

        ResponseCallback(Consumer<SerializedMessage> intermediateCallback,
                         CompletableFuture<SerializedMessage> finalCallback) {
            this.intermediateCallback = intermediateCallback;
            this.finalCallback = finalCallback;
        }

        synchronized void enqueue(SerializedMessage response, Executor executor) {
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
                    finalCallback.complete(response);
                } else {
                    intermediateCallback.accept(response);
                }
            } catch (Throwable e) {
                finalCallback.completeExceptionally(e);
                throw e;
            }
        }
    }

}
