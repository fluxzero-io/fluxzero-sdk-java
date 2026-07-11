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


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.Backlog;
import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.api.BooleanResult;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.ConnectEvent;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.DisconnectEvent;
import io.fluxzero.common.api.ErrorResult;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.api.VoidResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.ReadFromIndexResult;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.serialization.NullCollectionsAsEmptyModule;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.common.websocket.WebSocketTransportCodec;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.testserver.TestServerVersion;
import io.fluxzero.testserver.metrics.MetricsLog;
import io.fluxzero.testserver.metrics.NoOpMetricsLog;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.ObjectUtils.newPlatformThreadFactory;
import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Optional.ofNullable;

@Slf4j
public abstract class WebsocketEndpoint {
    private static final int DEFAULT_COMMAND_REQUEST_STRIPES = 16;
    private static final int DEFAULT_MAX_IN_FLIGHT_WEBSOCKET_BYTES = 16 * 1024 * 1024;
    private static final int MAX_WEBSOCKET_FRAGMENT_BYTES = getPositiveIntegerProperty(
            "maxWebSocketFragmentBytes", ServerWebsocketSession.DEFAULT_MAX_BINARY_FRAGMENT_BYTES);
    private static final int WEBSOCKET_RESULT_BATCH_SIZE = getPositiveIntegerProperty(
            "webSocketResultBatchSize", 1024);
    private static final int TARGET_WEBSOCKET_RESULT_BATCH_BYTES = getPositiveIntegerProperty(
            "targetWebSocketResultBatchBytes", 4 * 1024 * 1024);
    private static final int ESTIMATED_RESULT_OVERHEAD_BYTES = 256;
    protected static Duration webSocketSendTimeout =
            Duration.ofMillis(getPositiveIntegerProperty("webSocketSendTimeoutMs", 30_000));

    private static final ObjectMapper defaultObjectMapper = JsonMapper.builder()
            .findAndAddModules().disable(WRITE_DATES_AS_TIMESTAMPS)
            .disable(FAIL_ON_UNKNOWN_PROPERTIES).disable(FAIL_ON_EMPTY_BEANS)
            .enable(READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .addModule(new NullCollectionsAsEmptyModule()).enable(ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    protected static Duration pingTimeout = Duration.ofSeconds(15), pingDelay = Duration.ofSeconds(30);

    @Setter
    @Accessors(chain = true, fluent = true)
    MetricsLog metricsLog = new NoOpMetricsLog();

    @Getter(AccessLevel.PROTECTED)
    private final ObjectMapper objectMapper;
    private final Executor requestExecutor;
    private final ExecutorService ownedRequestExecutor;
    private final ExecutorService[] commandExecutors;
    private final CommandIdempotencyStore commandIdempotencyStore;
    private final boolean ownsCommandIdempotencyStore;

    private final Map<String, SessionBacklog> sessionBacklogs = new ConcurrentHashMap<>();
    private final Map<WebSocketTransportFormat, WebSocketTransportCodec> transportCodecs = new ConcurrentHashMap<>();
    private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();
    private final TaskScheduler pingScheduler = new InMemoryTaskScheduler(this + "-pingScheduler");
    private final Map<String, PingRegistration> pingDeadlines = new ConcurrentHashMap<>();
    private final Semaphore inFlightWebSocketBytes = new Semaphore(DEFAULT_MAX_IN_FLIGHT_WEBSOCKET_BYTES);
    private final ThreadLocal<Long> requestReceivedTimestamps = new ThreadLocal<>();
    protected final AtomicBoolean shuttingDown = new AtomicBoolean();
    protected volatile boolean shutDown;

    protected WebsocketEndpoint() {
        this(newWorkerPool(WebsocketEndpoint.class.getSimpleName(), 64), true,
             new CommandIdempotencyStore(), true);
    }

    protected WebsocketEndpoint(CommandIdempotencyStore commandIdempotencyStore) {
        this(newWorkerPool(WebsocketEndpoint.class.getSimpleName(), 64), true,
             commandIdempotencyStore, false);
    }

    protected WebsocketEndpoint(Executor requestExecutor) {
        this(ofNullable(requestExecutor).orElse(Runnable::run), false,
             new CommandIdempotencyStore(), true);
    }

    protected WebsocketEndpoint(Executor requestExecutor, CommandIdempotencyStore commandIdempotencyStore) {
        this(ofNullable(requestExecutor).orElse(Runnable::run), false,
             commandIdempotencyStore, false);
    }

    private WebsocketEndpoint(Executor requestExecutor, boolean ownsRequestExecutor,
                              CommandIdempotencyStore commandIdempotencyStore,
                              boolean ownsCommandIdempotencyStore) {
        this.objectMapper = defaultObjectMapper;
        this.ownedRequestExecutor = ownsRequestExecutor ? (ExecutorService) requestExecutor : null;
        this.requestExecutor = requestExecutor;
        this.commandExecutors = newRequestStripeExecutors(DEFAULT_COMMAND_REQUEST_STRIPES);
        this.commandIdempotencyStore = commandIdempotencyStore;
        this.ownsCommandIdempotencyStore = ownsCommandIdempotencyStore;
    }

    private final Handler<ClientMessage> handler =
            HandlerInspector.createHandler(this, Handle.class, Arrays.asList(new ParameterResolver<>() {
                @Override
                public Function<ClientMessage, Object> resolve(Parameter p, Annotation a) {
                    if (Objects.equals(p.getDeclaringExecutable().getParameters()[0], p)) {
                        return ClientMessage::getPayload;
                    }
                    return null;
                }

                @Override
                public boolean determinesSpecificity() {
                    return true;
                }
            }, (p, a) -> {
                if (p.getType().equals(ServerWebsocketSession.class)) {
                    return ClientMessage::getSession;
                }
                return null;
            }));

    public void onOpen(ServerWebsocketSession session) {
        if (shuttingDown.get()) {
            throw new IllegalStateException("Cannot accept client. Endpoint is shutting down");
        }
        String sessionId = getNegotiatedSessionId(session);
        activeSessionIds.add(sessionId);
        commandIdempotencyStore.registerSession(getClientId(session), sessionId);
        sessionBacklogs.put(sessionId, new SessionBacklog(
                Backlog.forOrderedAsyncConsumer(results -> sendResultBatchAsync(session, results),
                                                WEBSOCKET_RESULT_BATCH_SIZE), session));

        registerMetrics(new ConnectEvent(
                                getClientName(session), getClientId(session), sessionId,
                                toString(), getClientSdkVersion(session), getRuntimeVersion()),
                        session);
        schedulePing(session);
    }

    protected JsonType deserializeRequest(ServerWebsocketSession session, byte[] bytes) throws IOException {
        return transportCodec(session).decode(getCompressionAlgorithm(session).decompress(bytes));
    }

    public void onMessage(byte[] bytes, ServerWebsocketSession session) {
        long requestReceivedTimestamp = currentTimeMillis();
        try {
            dispatchRequest(session, deserializeRequest(session, bytes), requestReceivedTimestamp);
        } catch (Throwable e) {
            log.error("Failed to handle request", e);
        }
    }

    protected void dispatchRequest(ServerWebsocketSession session, JsonType request) {
        dispatchRequest(session, request, currentTimeMillis());
    }

    protected void dispatchRequest(ServerWebsocketSession session, JsonType request, long requestReceivedTimestamp) {
        if (request instanceof RequestBatch<?> batch) {
            batch.getRequests().forEach(r -> dispatchRequest(session, r, requestReceivedTimestamp));
        } else if (request instanceof Command command && shouldHandleIdempotently(command)) {
            submitRequestTask(session, command, () -> processCommandRequest(session, command, requestReceivedTimestamp));
        } else {
            submitRequestTask(session, request, () -> processRequest(session, request, requestReceivedTimestamp));
        }
    }

    protected boolean shouldHandleIdempotently(Command command) {
        return command.getGuarantee().compareTo(STORED) >= 0 && commandIdempotencyStore.isEnabled();
    }

    protected boolean submitRequestTask(ServerWebsocketSession session, JsonType request, Runnable task) {
        String sessionId = getNegotiatedSessionId(session);
        if (!activeSessionIds.contains(sessionId)) {
            log.info("Ignoring request for closed websocket session {}", sessionId);
            return false;
        }
        try {
            executorFor(request).execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private Executor executorFor(JsonType request) {
        return request instanceof Command command && command.routingKey() != null
                ? commandExecutors[ConsistentHashing.computeSegment(command.routingKey(), commandExecutors.length)]
                : requestExecutor;
    }

    private void processRequest(ServerWebsocketSession session, JsonType request, long requestReceivedTimestamp) {
        try {
            if (!canProcessRequest(session, request)) {
                return;
            }
            runWithRequestReceivedTimestamp(requestReceivedTimestamp, () -> handleMessage(session, request));
        } catch (Throwable e) {
            log.error("Failed to handle request", e);
            if (request instanceof Request r) {
                doSendResult(session, withRequestReceivedTimestamp(
                        new ErrorResult(r.getRequestId(), "Fluxzero TestServer: request could not be handled"),
                        requestReceivedTimestamp));
            }
        }
    }

    private boolean canProcessRequest(ServerWebsocketSession session, JsonType request) {
        String sessionId = getNegotiatedSessionId(session);
        if (!activeSessionIds.contains(sessionId)) {
            log.info("Ignoring request for closed websocket session {}", sessionId);
            return false;
        }
        if (shutDown) {
            throw new IllegalStateException(
                    format("Rejecting request %s from client %s with id %s because the service is shutting down",
                           request, getClientName(session), getClientId(session)));
        }
        if (shuttingDown.get()) {
            log.info(
                    "Silently ignoring request {} from client {} with id {} because the service is shutting down",
                    request, getClientName(session), getClientId(session));
            return false;
        }
        return true;
    }

    private void processCommandRequest(ServerWebsocketSession session, Command command, long requestReceivedTimestamp) {
        try {
            if (!canProcessRequest(session, command)) {
                return;
            }
            CommandIdempotencyStore.CommandRegistration registration =
                    commandIdempotencyStore.register(getClientId(session), command.getRequestId());
            if (!registration.newCommand()) {
                replayCommandResult(session, command, registration.result());
                return;
            }
            handleCommandMessage(session, command, registration.result(), requestReceivedTimestamp);
        } catch (Throwable e) {
            log.error("Failed to handle request", e);
            doSendResult(session, withRequestReceivedTimestamp(
                    new ErrorResult(command.getRequestId(), "Fluxzero TestServer: request could not be handled"),
                    requestReceivedTimestamp));
        }
    }

    protected void handleMessage(ServerWebsocketSession session, JsonType message) {
        handleMessage(session, message, currentRequestReceivedTimestamp());
    }

    protected void handleMessage(ServerWebsocketSession session, JsonType message, long requestReceivedTimestamp) {
        if (message instanceof RequestBatch<?> batch) {
            createTasks(batch, session, requestReceivedTimestamp).forEach(Runnable::run);
        } else {
            try {
                Object result = handler.getInvoker(new ClientMessage(message, session)).orElseThrow().invoke();
                trySendResult(session, message, result, requestReceivedTimestamp);
            } catch (Throwable e) {
                log.error("Could not handle request {}", message, e);
                trySendResult(session, message, e, requestReceivedTimestamp);
            }
        }
    }

    private void trySendResult(ServerWebsocketSession session, JsonType message, Object result,
                               long requestReceivedTimestamp) {
        if (message instanceof Request request) {
            toRequestResult(request, message, result, requestReceivedTimestamp)
                    .thenAccept(response -> response.ifPresent(r -> doSendResult(session, r)));
        }
    }

    private void handleCommandMessage(ServerWebsocketSession session, Command command,
                                      CommandIdempotencyStore.CommandResult commandResult,
                                      long requestReceivedTimestamp) {
        try {
            Object result = handler.getInvoker(new ClientMessage(command, session)).orElseThrow().invoke();
            toRequestResult(command, command, result, requestReceivedTimestamp).whenComplete((response, e) -> {
                Optional<RequestResult> completedResponse = e == null ? response : Optional.of(
                        withRequestReceivedTimestamp(new ErrorResult(
                                command.getRequestId(), "Error in Fluxzero TestServer"), requestReceivedTimestamp));
                commandResult.complete(completedResponse);
                completedResponse.ifPresent(r -> doSendResult(session, r));
            });
        } catch (Throwable e) {
            log.error("Could not handle request {}", command, e);
            toRequestResult(command, command, e, requestReceivedTimestamp).whenComplete((response, error) -> {
                Optional<RequestResult> completedResponse = error == null ? response : Optional.of(
                        withRequestReceivedTimestamp(new ErrorResult(
                                command.getRequestId(), "Error in Fluxzero TestServer"), requestReceivedTimestamp));
                commandResult.complete(completedResponse);
                completedResponse.ifPresent(r -> doSendResult(session, r));
            });
        }
    }

    private void replayCommandResult(ServerWebsocketSession session, Command command,
                                     CommandIdempotencyStore.CommandResult commandResult) {
        if (commandResult.isDone()) {
            commandResult.whenComplete((response, e) -> {
                if (e != null) {
                    log.warn("Cached command result completed unexpectedly for request {} from client {}",
                             command.getRequestId(), getClientId(session), e);
                    return;
                }
                response.ifPresent(r -> doSendResult(session, r));
            });
        } else {
            log.debug("Ignoring duplicate in-flight command {} from client {}",
                      command.getRequestId(), getClientId(session));
        }
    }

    private CompletableFuture<Optional<RequestResult>> toRequestResult(Request request, JsonType message,
                                                                       Object result, long requestReceivedTimestamp) {
        if (request instanceof Command command && command.getGuarantee().compareTo(STORED) < 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (result instanceof RequestResult response) {
            return CompletableFuture.completedFuture(Optional.of(
                    withRequestReceivedTimestamp(response, requestReceivedTimestamp)));
        }
        if (result instanceof Throwable e) {
            if (isCancellation(e)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return CompletableFuture.completedFuture(Optional.of(
                    withRequestReceivedTimestamp(
                            new ErrorResult(request.getRequestId(), "Error in Fluxzero TestServer"),
                            requestReceivedTimestamp)));
        }
        if (result == null) {
            return request instanceof Command
                    ? CompletableFuture.completedFuture(Optional.of(withRequestReceivedTimestamp(
                            new VoidResult(request.getRequestId()), requestReceivedTimestamp)))
                    : CompletableFuture.completedFuture(Optional.empty());
        }
        if (result instanceof Boolean v) {
            return CompletableFuture.completedFuture(Optional.of(withRequestReceivedTimestamp(
                    new BooleanResult(request.getRequestId(), v), requestReceivedTimestamp)));
        }
        if (result instanceof String v) {
            return CompletableFuture.completedFuture(Optional.of(withRequestReceivedTimestamp(
                    new StringResult(request.getRequestId(), v), requestReceivedTimestamp)));
        }
        if (result instanceof CompletableFuture<?> future) {
            CompletableFuture<Optional<RequestResult>> response = new CompletableFuture<>();
            future.whenComplete((r, e) -> {
                if (e != null) {
                    if (isCancellation(e)) {
                        response.complete(Optional.empty());
                        return;
                    }
                    log.error("Could not handle request {} ({})", request.getRequestId(), message.getClass(), e);
                    toRequestResult(request, message, e, requestReceivedTimestamp)
                            .whenComplete((errorResponse, error) -> {
                                if (error != null) {
                                    response.completeExceptionally(error);
                                } else {
                                    response.complete(errorResponse);
                                }
                            });
                } else {
                    toRequestResult(request, message, r, requestReceivedTimestamp)
                            .whenComplete((nestedResponse, error) -> {
                                if (error != null) {
                                    response.completeExceptionally(error);
                                } else {
                                    response.complete(nestedResponse);
                                }
                            });
                }
            });
            return response;
        }
        log.warn("Not able to send back result of type {} to client. Contents: {}. Request: {}",
                 result.getClass(), result, request);
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private boolean isCancellation(Throwable e) {
        return unwrapException(e) instanceof CancellationException;
    }

    private void runWithRequestReceivedTimestamp(long requestReceivedTimestamp, Runnable task) {
        Long previous = requestReceivedTimestamps.get();
        requestReceivedTimestamps.set(requestReceivedTimestamp);
        try {
            task.run();
        } finally {
            if (previous == null) {
                requestReceivedTimestamps.remove();
            } else {
                requestReceivedTimestamps.set(previous);
            }
        }
    }

    private long currentRequestReceivedTimestamp() {
        Long requestReceivedTimestamp = requestReceivedTimestamps.get();
        return requestReceivedTimestamp != null ? requestReceivedTimestamp : currentTimeMillis();
    }

    private static <T extends RequestResult> T withRequestReceivedTimestamp(T result, long requestReceivedTimestamp) {
        result.setRequestReceivedTimestamp(requestReceivedTimestamp);
        return result;
    }

    protected void doSendResult(ServerWebsocketSession session, RequestResult result) {
        ofNullable(sessionBacklogs.get(getNegotiatedSessionId(session))).or(() -> findAlternativeBacklog(session))
                .ifPresentOrElse(backlog -> backlog.add(result), () ->
                        log.info("Not sending result {}. Could not find any suitable sessions for client {}.",
                                 result, getClientId(session)));
    }

    protected Stream<Runnable> createTasks(RequestBatch<?> batch, ServerWebsocketSession session) {
        return createTasks(batch, session, currentRequestReceivedTimestamp());
    }

    protected Stream<Runnable> createTasks(RequestBatch<?> batch, ServerWebsocketSession session,
                                           long requestReceivedTimestamp) {
        return batch.getRequests().stream().map(r -> () -> handleMessage(session, r, requestReceivedTimestamp));
    }

    protected void sendResultBatch(ServerWebsocketSession session, List<RequestResult> results) {
        sendResultBatchAsync(session, results).join();
    }

    protected CompletableFuture<Void> sendResultBatchAsync(ServerWebsocketSession session,
                                                           List<RequestResult> results) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (List<RequestResult> batch : splitResultBatch(results)) {
            result = result.thenCompose(ignored -> sendResultBatchChunkAsync(session, batch));
        }
        return result;
    }

    private CompletableFuture<Void> sendResultBatchChunkAsync(ServerWebsocketSession session,
                                                              List<RequestResult> results) {
        try {
            var result = results.size() == 1 ? results.getFirst() : new ResultBatch(results);
            if (session.isOpen()) {
                try {
                    byte[] bytes = getCompressionAlgorithm(session).compress(transportCodec(session).encode(result));
                    return sendEncodedResultBatch(session, results, result, bytes);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return handleSendFailure(session, results, result, e);
                }
            } else {
                findAlternativeBacklog(session).ifPresentOrElse(b -> b.add(results), ()
                        -> log.info("Not sending batch of {}. Could not find any suitable sessions for client {}.",
                                    results.size(), getClientId(session)));
                return CompletableFuture.completedFuture(null);
            }
        } catch (Throwable e) {
            log.error("Failed to send websocket result to client {}, id {} (session {})",
                      getClientName(session), getClientId(session), getNegotiatedSessionId(session), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private List<List<RequestResult>> splitResultBatch(List<RequestResult> results) {
        if (results.size() <= 1) {
            return List.of(results);
        }
        List<List<RequestResult>> batches = new ArrayList<>();
        List<RequestResult> batch = new ArrayList<>();
        int estimatedBatchBytes = 0;
        for (RequestResult result : results) {
            int estimatedResultBytes = estimateResultBytes(result);
            if (!batch.isEmpty()
                    && estimatedBatchBytes + estimatedResultBytes > TARGET_WEBSOCKET_RESULT_BATCH_BYTES) {
                batches.add(List.copyOf(batch));
                batch.clear();
                estimatedBatchBytes = 0;
            }
            batch.add(result);
            estimatedBatchBytes += estimatedResultBytes;
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        return batches;
    }

    private int estimateResultBytes(RequestResult result) {
        int size = ESTIMATED_RESULT_OVERHEAD_BYTES;
        if (result instanceof ReadResult readResult) {
            return size + estimateMessageBatchBytes(readResult.getMessageBatch());
        }
        if (result instanceof ReadFromIndexResult readFromIndexResult) {
            return size + estimateSerializedMessagesBytes(readFromIndexResult.getMessages());
        }
        if (result instanceof StringResult stringResult) {
            return size + estimateStringBytes(stringResult.getResult());
        }
        if (result instanceof ErrorResult errorResult) {
            return size + estimateStringBytes(errorResult.getMessage());
        }
        return size;
    }

    private int estimateMessageBatchBytes(MessageBatch batch) {
        return batch == null ? 0 : estimateSerializedMessagesBytes(batch.getMessages());
    }

    private int estimateSerializedMessagesBytes(List<SerializedMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int result = 0;
        for (SerializedMessage message : messages) {
            result += estimateSerializedMessageBytes(message);
        }
        return result;
    }

    private int estimateSerializedMessageBytes(SerializedMessage message) {
        if (message == null) {
            return 0;
        }
        int result = 128;
        Data<byte[]> data = message.getData();
        if (data != null) {
            byte[] value = data.getValue();
            result += value == null ? 0 : value.length;
            result += estimateStringBytes(data.getType()) + estimateStringBytes(data.getFormat());
        }
        Metadata metadata = message.getMetadata();
        if (metadata != null && metadata.getEntries() != null) {
            for (Map.Entry<String, String> entry : metadata.getEntries().entrySet()) {
                result += estimateStringBytes(entry.getKey()) + estimateStringBytes(entry.getValue());
            }
        }
        result += estimateStringBytes(message.getSource());
        result += estimateStringBytes(message.getTarget());
        result += estimateStringBytes(message.getMessageId());
        return result;
    }

    private int estimateStringBytes(String value) {
        return value == null ? 0 : value.length() * 2;
    }

    private CompletableFuture<Void> sendEncodedResultBatch(ServerWebsocketSession session,
                                                           List<RequestResult> results, JsonType result,
                                                           byte[] bytes) throws InterruptedException {
        int permits = acquireInFlightWebSocketBytes(bytes.length);
        CompletableFuture<Void> sendFuture;
        try {
            sendFuture = session.sendBinaryAsync(ByteBuffer.wrap(bytes), MAX_WEBSOCKET_FRAGMENT_BYTES);
            if (sendFuture == null) {
                session.sendBinary(ByteBuffer.wrap(bytes));
                sendFuture = CompletableFuture.completedFuture(null);
            }
            sendFuture = withSendTimeout(sendFuture);
        } catch (Throwable e) {
            inFlightWebSocketBytes.release(permits);
            return handleTransportSendFailure(session, results, result, e);
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        sendFuture.whenComplete((ignored, error) -> {
            inFlightWebSocketBytes.release(permits);
            if (error == null) {
                completion.complete(null);
                return;
            }
            handleTransportSendFailure(session, results, result, unwrapCompletionException(error))
                    .whenComplete((v, failure) -> {
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    });
        });
        return completion;
    }

    private CompletableFuture<Void> withSendTimeout(CompletableFuture<Void> sendFuture) {
        if (webSocketSendTimeout == null || webSocketSendTimeout.isZero() || webSocketSendTimeout.isNegative()) {
            return sendFuture;
        }
        return sendFuture.orTimeout(webSocketSendTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private int acquireInFlightWebSocketBytes(int byteCount) throws InterruptedException {
        int permits = Math.max(1, Math.min(byteCount, DEFAULT_MAX_IN_FLIGHT_WEBSOCKET_BYTES));
        inFlightWebSocketBytes.acquire(permits);
        return permits;
    }

    private static int getPositiveIntegerProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private CompletableFuture<Void> handleSendFailure(ServerWebsocketSession session, List<RequestResult> results,
                                                      JsonType result, Throwable error) {
        Throwable e = unwrapCompletionException(error);
        if (isClosedChannel(e)) {
            findAlternativeBacklog(session).ifPresentOrElse(b -> b.add(results), ()
                    -> log.info("Not sending batch of {}. Could not find any suitable sessions for client {}.",
                                results.size(), getClientId(session)));
            return CompletableFuture.completedFuture(null);
        }
        log.error("Failed to send websocket result {} to client {}, id {} (session {})",
                  result, getClientName(session), getClientId(session), getNegotiatedSessionId(session), e);
        return CompletableFuture.failedFuture(e);
    }

    private CompletableFuture<Void> handleTransportSendFailure(ServerWebsocketSession session,
                                                               List<RequestResult> results, JsonType result,
                                                               Throwable error) {
        Throwable e = unwrapCompletionException(error);
        if (isTransportSendFailure(e)) {
            log.warn("Websocket send failed for result {} to client {}, id {} (session {}). Aborting session.",
                     result, getClientName(session), getClientId(session), getNegotiatedSessionId(session), e);
            findAlternativeBacklog(session).ifPresentOrElse(b -> b.add(results), ()
                    -> log.info("Not sending batch of {}. Could not find any suitable sessions for client {}.",
                                results.size(), getClientId(session)));
            abortTransport(session, "Websocket send failed: " + sendFailureReason(e));
            return CompletableFuture.completedFuture(null);
        }
        log.error("Failed to send websocket result {} to client {}, id {} (session {})",
                  result, getClientName(session), getClientId(session), getNegotiatedSessionId(session), e);
        return CompletableFuture.failedFuture(e);
    }

    private boolean isClosedChannel(Throwable e) {
        return e instanceof ClosedChannelException
               || ofNullable(e.getMessage()).map(m -> m.contains("Channel is closed")).orElse(false);
    }

    private boolean isTransportSendFailure(Throwable e) {
        return isClosedChannel(e)
               || e instanceof IOException
               || e instanceof TimeoutException
               || ofNullable(e.getMessage()).map(m -> m.contains("No buffer space available")
                       || m.contains("Broken pipe")
                       || m.contains("Connection reset")).orElse(false);
    }

    private String sendFailureReason(Throwable e) {
        return ofNullable(e.getMessage()).filter(m -> !m.isBlank()).orElse(e.getClass().getSimpleName());
    }

    private Throwable unwrapCompletionException(Throwable e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    protected Optional<SessionBacklog> findAlternativeBacklog(ServerWebsocketSession closedSession) {
        String clientId = getClientId(closedSession);
        return sessionBacklogs.values().stream()
                .filter(b -> clientId.equals(getClientId(b.getSession())) && !getNegotiatedSessionId(closedSession)
                        .equals(getNegotiatedSessionId(b.getSession()))).findFirst();
    }

    protected PingRegistration schedulePing(ServerWebsocketSession session) {
        return pingDeadlines.compute(getNegotiatedSessionId(session), (k, v) -> {
            if (v != null) {
                v.cancel();
            }
            return !shuttingDown.get() ? new PingRegistration(
                    pingScheduler.schedule(pingDelay, () -> sendPing(session))) : null;
        });
    }

    @SneakyThrows
    protected void sendPing(ServerWebsocketSession session) {
        if (!shuttingDown.get() && session.isOpen()) {
            String sessionId = getNegotiatedSessionId(session);
            var registration = pingDeadlines.compute(sessionId, (k, v) -> {
                if (v != null) {
                    v.cancel();
                }
                return new PingRegistration(pingScheduler.schedule(pingTimeout, () -> {
                    log.warn("Failed to get a ping response in time for session {}. Resetting connection", sessionId);
                    abort(session, "TestServer ping timeout");
                }));
            });
            try {
                session.sendPing(ByteBuffer.wrap(registration.getId().getBytes()));
            } catch (Exception e) {
                log.warn("Failed to send ping message", e);
            }
        }
    }

    public void onPong(ByteBuffer message, ServerWebsocketSession session) {
        pingDeadlines.compute(getNegotiatedSessionId(session), (k, v) -> {
            if (v == null) {
                return null;
            }
            v.cancel();
            return schedulePing(session);
        });
    }

    protected void abort(ServerWebsocketSession session, String reason) {
        WebsocketCloseReason closeReason = new WebsocketCloseReason(
                WebsocketCloseReason.UNEXPECTED_CONDITION, reason);
        String sessionId = getNegotiatedSessionId(session);
        log.warn("Aborting session {} due to {}", sessionId, reason);
        if (!TimingUtils.runAndWaitSafely(() -> session.close(closeReason), Duration.ofSeconds(5))) {
            log.warn("Failed to close session {} after abort", sessionId);
            onClose(session, closeReason);
        }
    }

    private void abortTransport(ServerWebsocketSession session, String reason) {
        WebsocketCloseReason closeReason = new WebsocketCloseReason(
                WebsocketCloseReason.UNEXPECTED_CONDITION, reason);
        String sessionId = getNegotiatedSessionId(session);
        log.warn("Disconnecting session {} due to {}", sessionId, reason);
        session.abort(closeReason);
        onClose(session, closeReason);
    }

    public void onClose(ServerWebsocketSession session, WebsocketCloseReason closeReason) {
        String sessionId = getNegotiatedSessionId(session);
        boolean wasActive = activeSessionIds.remove(sessionId);
        commandIdempotencyStore.unregisterSession(getClientId(session), sessionId);
        ofNullable(sessionBacklogs.remove(sessionId)).ifPresent(SessionBacklog::shutDown);
        ofNullable(pingDeadlines.remove(sessionId)).ifPresent(PingRegistration::cancel);
        if (!wasActive) {
            return;
        }
        if (!shuttingDown.get()) {
            if (closeReason.code() != WebsocketCloseReason.UNEXPECTED_CONDITION
                && closeReason.code() > WebsocketCloseReason.NO_STATUS_CODE) {
                log.warn("Websocket session {} to endpoint {} for client {} with id {} closed abnormally: {}",
                         sessionId, getClass().getSimpleName(), getClientName(session),
                         getClientId(session), closeReason);
            }
            registerMetrics(new DisconnectEvent(
                    getClientName(session), getClientId(session), sessionId, toString(),
                    closeReason.code(), closeReason.reason()), session);
        }
    }

    public void onError(ServerWebsocketSession session, Throwable e) {
        log.error("Error in session {} for client {} with id {}",
                  getNegotiatedSessionId(session), getClientName(session), getClientId(session), e);
        try {
            session.close(new WebsocketCloseReason(
                    WebsocketCloseReason.UNEXPECTED_CONDITION, "The websocket closed because of an error"));
        } catch (IOException ignored) {
        }
    }

    /**
     * Close all sessions on the websocket after an optional delay. During the delay we don't handle new requests but
     * will be able to send back results.
     */
    protected void shutDown() {
        if (shuttingDown.compareAndSet(false, true)) {
            try {
                if (!sessionBacklogs.isEmpty()) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                shutDown = true;
                pingDeadlines.clear();
                pingScheduler.shutdown();
                if (ownsCommandIdempotencyStore) {
                    commandIdempotencyStore.close();
                }
                Arrays.stream(commandExecutors).forEach(ExecutorService::shutdown);
                ofNullable(ownedRequestExecutor).ifPresent(ExecutorService::shutdown);
                sessionBacklogs.values().stream().map(SessionBacklog::getSession).filter(ServerWebsocketSession::isOpen).forEach(s -> {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @SuppressWarnings({"SameParameterValue", "resource"})
    protected ExecutorService[] newRequestStripeExecutors(int stripes) {
        ExecutorService[] result = new ExecutorService[stripes];
        for (int i = 0; i < stripes; i++) {
            result[i] = Executors.newSingleThreadExecutor(
                    newPlatformThreadFactory(getClass().getSimpleName() + "-command-stripe-" + i));
        }
        return result;
    }

    protected CompressionAlgorithm getCompressionAlgorithm(ServerWebsocketSession session) {
        return ofNullable(
                        session.getUserProperties().get(WebsocketDeploymentUtils.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY))
                .filter(CompressionAlgorithm.class::isInstance)
                .map(CompressionAlgorithm.class::cast)
                .or(() -> WebSocketCapabilities.getPreferredCompressionAlgorithm(getRequestHeaders(session)))
                .or(() -> ofNullable(session.getRequestParameterMap().get("compression"))
                        .map(List::getFirst)
                        .map(CompressionAlgorithm::valueOf))
                .orElse(CompressionAlgorithm.LZ4);
    }

    protected WebSocketTransportFormat getTransportFormat(ServerWebsocketSession session) {
        return ofNullable(
                        session.getUserProperties().get(WebsocketDeploymentUtils.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY))
                .filter(WebSocketTransportFormat.class::isInstance)
                .map(WebSocketTransportFormat.class::cast)
                .orElse(WebSocketTransportFormat.JSON);
    }

    protected WebSocketTransportCodec transportCodec(ServerWebsocketSession session) {
        return transportCodecs.computeIfAbsent(getTransportFormat(session),
                                               format -> WebSocketTransportCodecs.forFormat(format, objectMapper));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, List<String>> getRequestHeaders(ServerWebsocketSession session) {
        return ofNullable(
                        session.getUserProperties().get(WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .orElseGet(() -> ofNullable(session.getRequestHeaders()).orElseGet(Map::of));
    }

    protected String getNamespace(ServerWebsocketSession session) {
        return ofNullable(session.getRequestParameterMap().get("projectId")).map(List::getFirst)
                .orElse("public");
    }

    protected String getClientId(ServerWebsocketSession session) {
        return session.getRequestParameterMap().get("clientId").getFirst();
    }

    protected String getClientName(ServerWebsocketSession session) {
        return session.getRequestParameterMap().get("clientName").getFirst();
    }

    protected String getClientSdkVersion(ServerWebsocketSession session) {
        return WebSocketCapabilities.getClientSdkVersion(getRequestHeaders(session)).orElse(null);
    }

    protected String getRuntimeVersion() {
        return TestServerVersion.version().orElse(null);
    }

    protected String getNegotiatedSessionId(ServerWebsocketSession session) {
        return "%s_%s".formatted(
                WebSocketCapabilities.getClientSessionId(getRequestHeaders(session)).orElse("?"),
                ofNullable(session.getUserProperties().get(RUNTIME_SESSION_ID_USER_PROPERTY))
                        .map(Object::toString).orElseThrow());
    }

    protected void registerMetrics(Object event, ServerWebsocketSession session) {
        metricsLog.registerMetrics(event, sessionMetadata(session));
    }

    protected Metadata sessionMetadata(ServerWebsocketSession session) {
        return Metadata.of("$clientId", getClientId(session), "$clientName", getClientName(session))
                .with("$clientSdkVersion", getClientSdkVersion(session))
                .with("sessionId", getNegotiatedSessionId(session));
    }

    @Value
    protected static class ClientMessage {
        JsonType payload;
        ServerWebsocketSession session;
    }

    @Value
    protected static class SessionBacklog {
        @Delegate
        Backlog<RequestResult> delegate;
        ServerWebsocketSession session;
    }

    @Value
    protected static class PingRegistration implements Registration {
        String id = UUID.randomUUID().toString();
        @Delegate
        Registration delegate;
    }
}
