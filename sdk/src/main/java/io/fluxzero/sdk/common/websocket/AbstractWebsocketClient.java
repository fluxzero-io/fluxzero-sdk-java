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

package io.fluxzero.sdk.common.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.Backlog;
import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.Registration;
import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.RetryStatus;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.ErrorResult;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.application.DefaultPropertySource;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.common.websocket.WebSocketTransportCodec;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.SdkVersion;
import io.fluxzero.sdk.common.exception.ServiceException;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient.ClientConfig;
import io.fluxzero.sdk.publishing.AdhocDispatchInterceptor;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.GatewayException;
import io.fluxzero.sdk.publishing.client.WebsocketGatewayClient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static io.fluxzero.common.Guarantee.NONE;
import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.TimingUtils.retryOnFailure;
import static io.fluxzero.sdk.Fluxzero.currentCorrelationData;
import static io.fluxzero.sdk.Fluxzero.publishMetrics;
import static io.fluxzero.sdk.common.ClientUtils.ignoreMarker;
import static io.fluxzero.sdk.common.Message.asMessage;
import static io.fluxzero.sdk.publishing.AdhocDispatchInterceptor.getAdhocInterceptor;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;
import static java.util.Optional.ofNullable;

/**
 * Abstract base class for all WebSocket-based clients in the Fluxzero Java client.
 * <p>
 * This class provides robust connection management, message dispatching, result handling, batching, metrics publishing,
 * and ping-based health checking. It underpins core components such as {@code WebsocketGatewayClient}, providing the
 * shared infrastructure needed for durable, resilient WebSocket communication with the Fluxzero Runtime.
 *
 * <h2>Core Responsibilities</h2>
 * <ul>
 *   <li>Establishing and maintaining WebSocket connections with automatic reconnection support</li>
 *   <li>Managing message sending and batching via {@link Request} and {@link RequestBatch}</li>
 *   <li>Receiving and processing incoming {@link RequestResult} and {@link ResultBatch} messages</li>
 *   <li>Supporting command guarantees (e.g., SENT, STORED) with retries and backpressure handling</li>
 *   <li>Sending periodic ping frames to detect connection drops</li>
 *   <li>Integrating with the Fluxzero metrics infrastructure for custom performance telemetry</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Session Pooling:</b> Maintains multiple concurrent sessions to handle high-throughput scenarios</li>
 *   <li><b>Request Backlogs:</b> Each session has a backlog to buffer and batch outgoing requests</li>
 *   <li><b>Ping Scheduling:</b> Scheduled tasks detect broken sessions using WebSocket pings</li>
 *   <li><b>Auto Retry:</b> Failed requests are retried if the session is closed unexpectedly</li>
 *   <li><b>Async Result Handling:</b> Responses are handled on a separate thread pool to avoid blocking I/O</li>
 *   <li><b>Metrics Publishing:</b> Optional emission of message-related metrics based on configuration</li>
 * </ul>
 *
 * @see WebsocketGatewayClient
 * @see Command
 * @see WebSocketRequest
 * @see SessionPool
 * @see Request
 * @see RequestResult
 * @see ResultBatch
 */
public abstract class AbstractWebsocketClient implements WebsocketEndpoint, AutoCloseable {
    private static final CompletionStage<Void> COMPLETED_RUNTIME_MESSAGE = CompletableFuture.completedFuture(null);
    static final String TRANSPORT_METRICS_ENABLED_PROPERTY =
            "fluxzero.websocket.transportMetrics.enabled";
    private static final Duration CLOSE_HANDSHAKE_TIMEOUT = Duration.ofSeconds(1);
    protected static final Duration CONNECTION_TIMEOUT_FAILSAFE_GRACE = Duration.ofSeconds(5);
    protected static final int CONNECTION_RETRY_LOG_INTERVAL = 10;
    protected static final String CLIENT_HANDSHAKE_CONFIGURATOR_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".clientHandshakeConfigurator";
    protected static final String CLIENT_SESSION_ID_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".clientSessionId";
    protected static final String RUNTIME_SESSION_ID_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".runtimeSessionId";
    protected static final String NEGOTIATED_SESSION_ID_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".negotiatedSessionId";
    protected static final String RUNTIME_VERSION_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".runtimeVersion";
    protected static final String SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".selectedCompressionAlgorithm";
    protected static final String SELECTED_TRANSPORT_FORMAT_USER_PROPERTY =
            AbstractWebsocketClient.class.getName() + ".selectedTransportFormat";
    static final String REPLAYED_RESPONSE_METADATA_KEY = WebsocketResultDiagnostics.REPLAYED_RESPONSE_METADATA_KEY;
    static final String RESULT_DIAGNOSTICS_PROPERTY = WebsocketResultDiagnostics.MODE_PROPERTY;
    static final String RESULT_TIMING_METRICS_ENABLED_PROPERTY =
            WebsocketResultDiagnostics.LEGACY_TIMING_ENABLED_PROPERTY;

    public static WebsocketConnector defaultWebsocketConnector = new JdkWebsocketConnector();
    public static ObjectMapper defaultObjectMapper = JsonMapper.builder().disable(FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .findAndAddModules().disable(WRITE_DATES_AS_TIMESTAMPS).build();

    @Getter(lazy = true)
    @Accessors(fluent = true)
    private final Logger log = LoggerFactory.getLogger("%s.%s".formatted(getClass().getPackageName(), this));

    private final SessionPool sessionPool;
    private final WebSocketClient client;
    private final ClientConfig clientConfig;
    private final ObjectMapper objectMapper;
    private final Map<Long, WebSocketRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, Backlog<Request>> sessionBacklogs = new ConcurrentHashMap<>();
    private final Map<WebSocketTransportFormat, WebSocketTransportCodec> transportCodecs = new ConcurrentHashMap<>();
    private final TaskScheduler pingScheduler;
    private final Map<String, PingRegistration> pingDeadlines = new ConcurrentHashMap<>();
    private final Set<String> runtimeIngressBackpressured = ConcurrentHashMap.newKeySet();
    private final Set<String> runtimeIngressBackpressureReported = ConcurrentHashMap.newKeySet();
    private final Map<String, RuntimeIngressLiveness> runtimeIngressLiveness = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService resultExecutor;
    private final RuntimeResultDispatcher runtimeResultDispatcher;
    private final ExecutorService reconnectExecutor;
    private final Semaphore inFlightWebSocketBytes;
    private final boolean allowMetrics;
    private final boolean transportMetricsEnabled;
    private final boolean monitorRuntimeIngressProgress;
    private final WebsocketResultDiagnostics resultDiagnostics;
    private final SdkRuntimeWebsocketEndpoint sdkRuntimeEndpoint = new SdkRuntimeWebsocketEndpoint(this);
    private final ThreadLocal<CompletionStage<Void>> runtimeMessageCompletion = new ThreadLocal<>();

    @Getter(value = AccessLevel.PROTECTED, lazy = true)
    private final Serializer fallbackSerializer = new JacksonSerializer();

    /**
     * Creates a WebSocket client using the given endpoint URI, client implementation, and a flag to allow metrics. Uses
     * a default WebSocket container, default object mapper, and a single WebSocket session.
     *
     * @param endpointUri  the URI of the WebSocket endpoint to connect to
     * @param client       the client implementation that provides configuration and gateway access
     * @param allowMetrics whether metrics should be published for each request
     */
    public AbstractWebsocketClient(URI endpointUri, WebSocketClient client, boolean allowMetrics) {
        this(endpointUri, client, allowMetrics, 1);
    }

    /**
     * Creates a WebSocket client with multiple parallel sessions using default settings. This constructor allows you to
     * specify the number of WebSocket sessions to use, which is useful for increasing throughput and isolating message
     * streams.
     *
     * @param endpointUri      the URI of the WebSocket endpoint to connect to
     * @param client           the client implementation that provides configuration and gateway access
     * @param allowMetrics     whether metrics should be published for each request
     * @param numberOfSessions the number of WebSocket sessions to maintain concurrently
     */
    public AbstractWebsocketClient(URI endpointUri, WebSocketClient client, boolean allowMetrics,
                                   int numberOfSessions) {
        this(defaultWebsocketConnector, endpointUri, client, allowMetrics, Duration.ofSeconds(1),
             defaultObjectMapper, numberOfSessions);
    }

    /**
     * Constructs a WebSocket client with fine-grained control over connection setup. This constructor allows you to
     * specify a custom container, reconnect delay, object mapper, and session count. It is primarily used for advanced
     * configuration or test scenarios.
     *
     * @param connector        the WebSocket connector to use for establishing connections
     * @param endpointUri      the WebSocket server endpoint
     * @param client           the client providing config and access to the Fluxzero Runtime
     * @param allowMetrics     flag to enable or disable automatic metrics publishing
     * @param reconnectDelay   the delay between reconnect attempts if the connection is lost
     * @param objectMapper     the Jackson object mapper for (de)serializing requests and responses
     * @param numberOfSessions the number of WebSocket sessions to establish in parallel
     */
    public AbstractWebsocketClient(WebsocketConnector connector, URI endpointUri, WebSocketClient client,
                                   boolean allowMetrics, Duration reconnectDelay, ObjectMapper objectMapper,
                                   int numberOfSessions) {
        this(connector, endpointUri, client, allowMetrics, reconnectDelay, objectMapper, numberOfSessions,
             DefaultPropertySource.getInstance(), AbstractWebsocketClient::createPingScheduler);
    }

    AbstractWebsocketClient(WebsocketConnector connector, URI endpointUri, WebSocketClient client,
                            boolean allowMetrics, Duration reconnectDelay, ObjectMapper objectMapper,
                            int numberOfSessions, PropertySource propertySource,
                            PingSchedulerFactory pingSchedulerFactory) {
        this.client = client;
        this.clientConfig = client.getClientConfig();
        this.objectMapper = objectMapper;
        this.allowMetrics = allowMetrics;
        this.resultDiagnostics = WebsocketResultDiagnostics.from(propertySource);
        this.transportMetricsEnabled = allowMetrics && !clientConfig.isDisableMetrics()
                                       && transportMetricsEnabled(propertySource);
        this.monitorRuntimeIngressProgress = transportMetricsEnabled
                                             || !clientConfig.getRuntimeIngressStallCloseTimeout().isZero();
        this.inFlightWebSocketBytes = new Semaphore(Math.max(1, clientConfig.getMaxInFlightWebSocketBytes()));
        this.pingScheduler = pingSchedulerFactory.create(this, numberOfSessions);
        this.resultExecutor = newWorkerPool(
                this + "-onMessage", clientConfig.getMaxConcurrentRuntimeResultCompletions());
        this.runtimeResultDispatcher = new RuntimeResultDispatcher(
                resultExecutor, clientConfig.getMaxConcurrentRuntimeResultCompletions());
        this.reconnectExecutor = newWorkerPool(this + "-reconnect", Math.max(1, numberOfSessions));
        this.sessionPool = new SessionPool(numberOfSessions, previousSession -> retryOnFailure(
                () -> connectToServer(connector, endpointUri, previousSession),
                createConnectionRetryConfiguration(endpointUri, reconnectDelay)));
    }

    private static TaskScheduler createPingScheduler(AbstractWebsocketClient client, int numberOfSessions) {
        return new InMemoryTaskScheduler(client + "-pingScheduler",
                                         ObjectUtils.newWorkerPool(client + "-ping",
                                                                   Math.max(1, numberOfSessions)));
    }

    protected RetryConfiguration createConnectionRetryConfiguration(URI endpointUri, Duration reconnectDelay) {
        return RetryConfiguration.builder()
                .delay(reconnectDelay)
                .errorTest(e -> {
                    if (e instanceof Error) {
                        log().error("Error while connecting to endpoint {}", endpointUri, e);
                    }
                    return !closed.get();
                })
                .successLogger(status -> logSuccessfulReconnect(endpointUri, status))
                .exceptionLogger(status -> logConnectionRetryStatus(endpointUri, status))
                .build();
    }

    protected void logSuccessfulReconnect(URI endpointUri, RetryStatus status) {
        log().info("Successfully reconnected to endpoint {} after {} {}",
                   endpointUri, status.getNumberOfTimesRetried(),
                   status.getNumberOfTimesRetried() == 1 ? "retry" : "retries");
    }

    protected void logConnectionRetryStatus(URI endpointUri, RetryStatus status) {
        int retryCount = status.getNumberOfTimesRetried();
        if (retryCount == 0) {
            log().warn("Failed to connect to endpoint {}; reason: {}. Retrying every {} ms...",
                       endpointUri, status.getException().getMessage(),
                       status.getRetryConfiguration().getDelay().toMillis());
        } else if (retryCount > 0 && retryCount % CONNECTION_RETRY_LOG_INTERVAL == 0) {
            log().warn("Still trying to connect to endpoint {} after {} retries. Last error: {}.",
                       endpointUri, retryCount, status.getException().getMessage());
        }
    }

    protected WebsocketSession connectToServer(WebsocketConnector connector, URI endpointUri) throws Exception {
        return connectToServer(connector, endpointUri, null);
    }

    protected WebsocketSession connectToServer(WebsocketConnector connector, URI endpointUri,
                                               WebsocketSession previousSession) throws Exception {
        String replacedSessionId = null;
        if (previousSession != null) {
            try {
                replacedSessionId = getNegotiatedSessionId(previousSession);
            } catch (RuntimeException e) {
                log().debug("Could not determine the replaced websocket session id", e);
            }
        }
        ConnectionSetup connectionSetup = createConnectionSetup(
                clientConfig, replacedSessionId, transportMetricsEnabled);
        return TimingUtils.callAndWait(
                () -> connector.connect(sdkRuntimeEndpoint, connectionSetup.options(), endpointUri),
                clientConfig.getConnectionTimeout().plus(getConnectionTimeoutFailsafeGrace()));
    }

    protected Duration getConnectionTimeoutFailsafeGrace() {
        return CONNECTION_TIMEOUT_FAILSAFE_GRACE;
    }

    @Override
    public void onOpen(WebsocketSession session) {
        Optional.ofNullable(session.getUserProperties().get(CLIENT_HANDSHAKE_CONFIGURATOR_USER_PROPERTY))
                .filter(ClientHandshakeConfigurator.class::isInstance)
                .map(ClientHandshakeConfigurator.class::cast)
                .ifPresent(configurator -> {
                    configurator.afterResponse(session.getHandshakeResponseHeaders());
                    session.getUserProperties().put(CLIENT_SESSION_ID_USER_PROPERTY,
                                                    configurator.getClientSessionId());
                    session.getUserProperties().put(RUNTIME_SESSION_ID_USER_PROPERTY,
                                                    ofNullable(configurator.getRuntimeSessionId()).orElse("?"));
                    session.getUserProperties().put(NEGOTIATED_SESSION_ID_USER_PROPERTY,
                                                    negotiatedSessionId(
                                                            configurator.getClientSessionId(),
                                                            ofNullable(configurator.getRuntimeSessionId()).orElse("?")));
                    ofNullable(configurator.getRuntimeVersion()).ifPresent(
                            runtimeVersion -> session.getUserProperties().put(RUNTIME_VERSION_USER_PROPERTY,
                                                                              runtimeVersion));
                    session.getUserProperties().put(
                            SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                            ofNullable(configurator.getSelectedCompressionAlgorithm())
                                    .orElseGet(() -> ServiceUrlBuilder.legacyCompressionHint(clientConfig)));
                    session.getUserProperties().put(
                            SELECTED_TRANSPORT_FORMAT_USER_PROPERTY,
                            ofNullable(configurator.getSelectedTransportFormat())
                                    .orElse(WebSocketTransportFormat.JSON));
                });
        log().info("Session {} is connected to endpoint {} (runtime version: {}, transport: {}, compression: {})",
                   getNegotiatedSessionId(session), session.getRequestURI(),
                   getRuntimeVersion(session).orElse("unknown"), getTransportFormat(session),
                   getCompressionAlgorithm(session));
        schedulePing(session);
    }

    protected <R extends RequestResult> CompletableFuture<R> send(Request request) {
        return new WebSocketRequest(request, currentCorrelationData(),
                                    getAdhocInterceptor(METRICS).orElse(null),
                                    Fluxzero.getOptionally().orElse(null)).send();
    }

    @SuppressWarnings("unchecked")
    @SneakyThrows
    protected <R extends RequestResult> R sendAndWait(Request request) {
        return (R) send(request).get();
    }

    protected CompletableFuture<Void> sendCommand(Command command) {
        return switch (command.getGuarantee()) {
            case NONE -> {
                sendAndForget(command);
                yield CompletableFuture.completedFuture(null);
            }
            case SENT -> sendAndForget(command);
            default -> send(command).thenApply(r -> null);
        };
    }

    @SneakyThrows
    private CompletableFuture<Void> sendAndForget(Command object) {
        return send(object, Fluxzero.currentCorrelationData(), sessionPool.get(object.routingKey()));
    }

    @SneakyThrows
    private CompletableFuture<Void> send(Request request, Map<String, String> correlationData,
                                         WebsocketSession session) {
        String sessionId = getNegotiatedSessionId(session);
        try {
            return sessionBacklogs.computeIfAbsent(
                    sessionId, id -> Backlog.forOrderedAsyncConsumer(
                            batch -> sendBatchAsync(batch, session))).add(request);
        } finally {
            tryPublishMetrics(request, metricsMetadata().with(correlationData)
                    .with("sessionId", sessionId).with("requestId", request.getRequestId()));
        }
    }

    @SneakyThrows
    private void sendBatch(List<Request> requests, WebsocketSession session) {
        sendBatchAsync(requests, session).join();
    }

    private CompletableFuture<Void> sendBatchAsync(List<Request> requests, WebsocketSession session) {
        JsonType object = requests.size() == 1 ? requests.getFirst() : new RequestBatch<>(requests);
        try {
            byte[] bytes = getCompressionAlgorithm(session).compress(transportCodec(session).encode(object));
            if (session.isOpen()) {
                return sendEncodedBatch(session, object, bytes);
            } else if (!closed.get()) {
                abort(session, "Channel closed ahead of sending");
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                currentThread().interrupt();
            }
            return handleSendFailure(object, session, e);
        }
    }

    private CompletableFuture<Void> sendEncodedBatch(WebsocketSession session, JsonType object, byte[] bytes)
            throws InterruptedException {
        int permits = acquireInFlightWebSocketBytes(bytes.length);
        CompletableFuture<Void> sendFuture;
        try {
            sendFuture = session.sendBinaryAsync(ByteBuffer.wrap(bytes), clientConfig.getMaxWebSocketFragmentBytes());
            if (sendFuture == null) {
                session.sendBinary(ByteBuffer.wrap(bytes));
                sendFuture = CompletableFuture.completedFuture(null);
            }
            sendFuture = withSendTimeout(sendFuture);
        } catch (Throwable e) {
            inFlightWebSocketBytes.release(permits);
            return handleTransportSendFailure(object, session, e);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        sendFuture.whenComplete((ignored, error) -> {
            inFlightWebSocketBytes.release(permits);
            if (error == null) {
                result.complete(null);
                return;
            }
            handleTransportSendFailure(object, session, unwrapCompletionException(error))
                    .whenComplete((v, failure) -> {
                        if (failure == null) {
                            result.complete(null);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
        });
        return result;
    }

    private CompletableFuture<Void> withSendTimeout(CompletableFuture<Void> sendFuture) {
        Duration timeout = clientConfig.getWebSocketSendTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return sendFuture;
        }
        return sendFuture.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private int acquireInFlightWebSocketBytes(int byteCount) throws InterruptedException {
        int permits = Math.max(1, Math.min(byteCount, clientConfig.getMaxInFlightWebSocketBytes()));
        inFlightWebSocketBytes.acquire(permits);
        return permits;
    }

    private CompletableFuture<Void> handleSendFailure(JsonType object, WebsocketSession session, Throwable error) {
        Throwable e = unwrapCompletionException(error);
        boolean closedChannel = isClosedChannel(e);
        if (closed.get() && closedChannel) {
            return CompletableFuture.completedFuture(null);
        }
        log().error(ignoreMarker, "Failed to send request {} (session {})",
                    object, getNegotiatedSessionId(session), e);
        if (closedChannel) {
            abort(session, "Channel closed while sending");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(e);
    }

    private CompletableFuture<Void> handleTransportSendFailure(JsonType object, WebsocketSession session,
                                                               Throwable error) {
        Throwable e = unwrapCompletionException(error);
        boolean transportSendFailure = isTransportSendFailure(e);
        if (closed.get() && transportSendFailure) {
            return CompletableFuture.completedFuture(null);
        }
        log().error(ignoreMarker, "Failed to send request {} (session {})",
                    object, getNegotiatedSessionId(session), e);
        if (transportSendFailure) {
            abort(session, "Websocket send failed: " + sendFailureReason(e));
            return CompletableFuture.completedFuture(null);
        }
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

    @Override
    public void onMessage(byte[] bytes, WebsocketSession session) {
        handleMessage(bytes, session, null);
    }

    @Override
    public void onMessage(byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming) {
        handleMessage(bytes, session, receiveTiming);
    }

    @Override
    public boolean captureReceiveTiming() {
        return resultDiagnostics.captureReceiveTiming();
    }

    protected void handleMessage(byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming) {
        WebsocketResultDiagnostics.FrameTiming frameTiming =
                resultDiagnostics.frameTiming(
                        receiveTiming, resultDiagnostics.captureReceiveTiming()
                                ? sdkRuntimeEndpoint.currentDispatchTiming() : null);
        long decodeStartedNanos = resultDiagnostics.monotonicTimestamp();
        JsonType value;
        try {
            value = transportCodec(session).decode(getCompressionAlgorithm(session).decompress(bytes));
        } catch (Exception e) {
            log().error("Could not parse input. Expected a {} websocket message.",
                        getTransportFormat(session), e);
            if (runtimeMessageCompletion.get() != null) {
                runtimeMessageCompletion.set(CompletableFuture.failedFuture(e));
            }
            return;
        }
        long decodedTimestamp = resultDiagnostics.timestamp();
        long decodeDuration = decodeStartedNanos == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, resultDiagnostics.monotonicTimestamp() - decodeStartedNanos));
        String sessionId = getNegotiatedSessionId(session);
        CompletionStage<Void> completion;
        if (value instanceof ResultBatch batch) {
            String batchId = Fluxzero.generateId();
            long callbackQueuedTimestamp = resultDiagnostics.timestamp();
            List<RequestResult> results = batch.getResults();
            if (results.size() == 1) {
                RequestResult result = results.getFirst();
                completion = submitRuntimeResult(sessionId, () -> handleResult(
                        result, batchId, sessionId,
                        resultDiagnostics.resultTiming(
                                frameTiming, decodedTimestamp, decodeDuration, callbackQueuedTimestamp,
                                resultDiagnostics.timestamp())));
            } else {
                completion = runtimeResultDispatcher.submit(sessionId, results, result -> handleResult(
                        result, batchId, sessionId,
                        resultDiagnostics.resultTiming(
                                frameTiming, decodedTimestamp, decodeDuration, callbackQueuedTimestamp,
                                resultDiagnostics.timestamp())));
            }
        } else {
            completion = submitRuntimeResult(
                    sessionId, resultCallback((RequestResult) value, null, sessionId,
                                               frameTiming, decodedTimestamp, decodeDuration));
        }
        if (runtimeMessageCompletion.get() != null) {
            runtimeMessageCompletion.set(completion);
        }
    }

    private CompletableFuture<Void> submitRuntimeResult(String sessionId, Runnable resultCallback) {
        return runtimeMessageCompletion.get() == null
                ? runtimeResultDispatcher.submit(sessionId, resultCallback)
                : runtimeResultDispatcher.submitFromRuntimeWorker(sessionId, resultCallback);
    }

    private Runnable resultCallback(RequestResult result, String batchId, String sessionId,
                                    WebsocketResultDiagnostics.FrameTiming frameTiming,
                                    long decodedTimestamp, long decodeDuration) {
        long callbackQueuedTimestamp = resultDiagnostics.timestamp();
        return () -> handleResult(
                result, batchId, sessionId,
                resultDiagnostics.resultTiming(
                        frameTiming, decodedTimestamp, decodeDuration, callbackQueuedTimestamp,
                        resultDiagnostics.timestamp()));
    }

    CompletionStage<Void> dispatchRuntimeMessage(Runnable messageCallback) {
        CompletionStage<Void> previousCompletion = runtimeMessageCompletion.get();
        runtimeMessageCompletion.set(COMPLETED_RUNTIME_MESSAGE);
        try {
            messageCallback.run();
            return runtimeMessageCompletion.get();
        } finally {
            // Retain the empty ThreadLocal slot on reusable SDK workers to avoid allocating a new map entry per
            // message. Restoring also keeps nested dispatches from losing their outer completion context.
            runtimeMessageCompletion.set(previousCompletion);
        }
    }

    void onRuntimeIngressBackpressure(WebsocketSession session, boolean backpressured,
                                      RuntimeIngressController.State state) {
        String sessionId = getNegotiatedSessionId(session);
        if (backpressured) {
            if (runtimeIngressBackpressured.add(sessionId)) {
                pingDeadlines.computeIfPresent(sessionId, (key, current) -> {
                    if (!current.isAwaitingPong()) {
                        return current;
                    }
                    current.cancel();
                    return null;
                });
                if (shouldEmitTransportMetrics() && state != null
                    && runtimeIngressBackpressureReported.add(sessionId)) {
                    emitTransportMetricAsync(
                            WebsocketTransportMetric.Event.RUNTIME_INGRESS_BACKPRESSURED, session,
                            transportMetricState(session, JdkWebSocketSession.runtimeDataState(state)));
                }
            }
        } else if (runtimeIngressBackpressured.remove(sessionId)) {
            pingDeadlines.computeIfAbsent(sessionId, ignored -> newScheduledPing(session));
        }
    }

    void onRuntimeIngressProgress(
            WebsocketSession session, RuntimeIngressController.Progress progress, int retainedMessages,
            long sequence) {
        if (!monitorRuntimeIngressProgress) {
            return;
        }
        String sessionId = getNegotiatedSessionId(session);
        RuntimeIngressLiveness current = runtimeIngressLiveness.get(sessionId);
        if (current == null) {
            if (retainedMessages == 0) {
                return;
            }
            RuntimeIngressLiveness candidate = new RuntimeIngressLiveness();
            RuntimeIngressLiveness existing = runtimeIngressLiveness.putIfAbsent(sessionId, candidate);
            current = existing == null ? candidate : existing;
        }
        CompletableFuture<Void> stalledMetricPublication = null;
        boolean recovered;
        synchronized (current) {
            if (sequence <= current.progressSequence) {
                return;
            }
            current.progressSequence = sequence;
            boolean newBurst = current.retainedMessages == 0 && retainedMessages > 0;
            current.retainedMessages = retainedMessages;
            if (newBurst || progress == RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED) {
                current.progressDeadline = nextRuntimeIngressProgressDeadline();
            }
            recovered = current.stalled;
            if (recovered) {
                stalledMetricPublication = current.stallMetricPublication;
            }
            if (retainedMessages == 0) {
                current.cancel();
                current.stalled = false;
            } else if (current.stallDeadline == null || recovered) {
                current.cancel();
                current.registrationId = Fluxzero.generateId();
                current.stalled = false;
                if (current.progressDeadline == 0L) {
                    current.progressDeadline = nextRuntimeIngressProgressDeadline();
                }
                current.stallDeadline = scheduleRuntimeIngressStallDeadline(
                        session, sessionId, current.registrationId, current.progressDeadline);
                current.stallMetricPublication = CompletableFuture.completedFuture(null);
            }
        }
        if (recovered) {
            JdkWebSocketSession.RuntimeDataState recoveredState = transportMetricState(session, null);
            stalledMetricPublication.whenComplete((ignored, failure) -> emitTransportMetricAsync(
                    WebsocketTransportMetric.Event.RUNTIME_INGRESS_RECOVERED, session, recoveredState));
        }
    }

    private Registration scheduleRuntimeIngressStallDeadline(
            WebsocketSession session, String sessionId, String registrationId, long deadline) {
        return pingScheduler.schedule(
                deadline,
                () -> handleRuntimeIngressStall(session, sessionId, registrationId));
    }

    private long nextRuntimeIngressProgressDeadline() {
        return Math.addExact(pingScheduler.clock().millis(), clientConfig.getPingTimeout().toMillis());
    }

    private void handleRuntimeIngressStall(WebsocketSession session, String sessionId, String registrationId) {
        RuntimeIngressLiveness current = runtimeIngressLiveness.get(sessionId);
        if (current == null) {
            return;
        }
        JdkWebSocketSession.RuntimeDataState metricState;
        CompletableFuture<Void> metricPublicationGate;
        synchronized (current) {
            if (!registrationId.equals(current.registrationId)) {
                return;
            }
            if (current.retainedMessages == 0) {
                current.cancel();
                return;
            }
            if (current.progressDeadline > pingScheduler.clock().millis()) {
                current.stallDeadline = scheduleRuntimeIngressStallDeadline(
                        session, sessionId, registrationId, current.progressDeadline);
                return;
            }
            current.stallDeadline = null;
            current.closeDeadline = clientConfig.getRuntimeIngressStallCloseTimeout().isZero()
                    ? null : pingScheduler.schedule(
                    clientConfig.getRuntimeIngressStallCloseTimeout(),
                    () -> handleRuntimeIngressStallClose(session, sessionId, registrationId));
            current.stalled = true;
            current.stallMetricPublication = new CompletableFuture<>();
            metricPublicationGate = current.stallMetricPublication;
            metricState = transportMetricState(session, null);
        }
        CompletableFuture<Void> publication = emitTransportMetricAsync(
                WebsocketTransportMetric.Event.RUNTIME_INGRESS_STALLED, session, metricState);
        publication.whenComplete((ignored, failure) -> metricPublicationGate.complete(null));
    }

    private void handleRuntimeIngressStallClose(
            WebsocketSession session, String sessionId, String registrationId) {
        RuntimeIngressLiveness current = runtimeIngressLiveness.get(sessionId);
        if (current == null) {
            return;
        }
        boolean close;
        synchronized (current) {
            if (current.stalled && registrationId.equals(current.registrationId)
                && current.retainedMessages > 0) {
                current.cancel();
                close = true;
            } else {
                close = false;
            }
        }
        if (close && runtimeIngressLiveness.remove(sessionId, current)) {
            abort(session, "Runtime ingress made no functional progress");
        }
    }

    protected void handleResult(RequestResult result, String batchId, String sessionId) {
        handleResult(result, batchId, sessionId, WebsocketResultDiagnostics.ResultTiming.none());
    }

    protected void handleResult(RequestResult result, String batchId, String sessionId,
                                WebsocketResultDiagnostics.ResultTiming clientResultTiming) {
        try {
            WebSocketRequest webSocketRequest = requests.remove(result.getRequestId());
            if (webSocketRequest == null) {
                log().warn("Could not find outstanding read request for id {}", result.getRequestId());
            } else {
                try {
                    Metadata metadata = metricsMetadata()
                            .with("requestId", webSocketRequest.request.getRequestId(),
                                  "msDuration", currentTimeMillis() - webSocketRequest.sendTimestamp)
                            .with("requestSentTimestamp", webSocketRequest.sendTimestamp)
                            .with("resultType", result.getClass().getSimpleName(),
                                  "requestType", webSocketRequest.request.getClass().getSimpleName())
                            .with(resultDiagnostics.metadata(result, clientResultTiming))
                            .with(webSocketRequest.correlationData)
                            .with("batchId", batchId)
                            .with("sessionId", sessionId)
                            .with("request", webSocketRequest.request.toMetric());
                    Fluxzero.getOptionally().or(() -> ofNullable(webSocketRequest.fluxzero))
                            .ifPresent(fc -> fc.execute(f -> ofNullable(webSocketRequest.adhocMetricsInterceptor)
                                    .ifPresentOrElse(
                                            i -> AdhocDispatchInterceptor.runWithAdhocInterceptor(
                                                    () -> tryPublishMetrics(result, metadata), i,
                                                    METRICS),
                                            () -> tryPublishMetrics(result, metadata))));
                } finally {
                    if (result instanceof ErrorResult e) {
                        webSocketRequest.result.completeExceptionally(new ServiceException(e.getMessage()));
                    } else {
                        webSocketRequest.result.complete(result);
                    }
                }
            }
        } catch (Throwable e) {
            log().error("Failed to handle result {}", result, e);
        }
    }

    static Metadata responseTimingMetadata(RequestResult result) {
        return WebsocketResultDiagnostics.baseMetadata(result);
    }

    static Metadata clientResultTimingMetadata(WebsocketResultDiagnostics.ResultTiming timing) {
        return WebsocketResultDiagnostics.clientTimingMetadata(timing);
    }

    static Long serverMsDuration(RequestResult result) {
        return WebsocketResultDiagnostics.serverMsDuration(result);
    }

    static boolean isReplayedResponse(RequestResult result) {
        return WebsocketResultDiagnostics.isReplayedResponse(result);
    }

    protected PingRegistration schedulePing(WebsocketSession session) {
        return pingDeadlines.compute(getNegotiatedSessionId(session), (k, v) -> {
            if (v != null) {
                v.cancel();
            }
            return newScheduledPing(session);
        });
    }

    private PingRegistration newScheduledPing(WebsocketSession session) {
        return !closed.get() && session.isOpen()
               && !runtimeIngressBackpressured.contains(getNegotiatedSessionId(session)) ? new PingRegistration(
                pingScheduler.schedule(clientConfig.getPingDelay(), () -> sendPing(session))) : null;
    }

    @SneakyThrows
    protected void sendPing(WebsocketSession session) {
        String sessionId = getNegotiatedSessionId(session);
        if (!closed.get() && session.isOpen()) {
            var registration = pingDeadlines.compute(sessionId, (k, v) -> {
                if (v != null) {
                    v.cancel();
                }
                if (closed.get() || !session.isOpen()
                    || runtimeIngressBackpressured.contains(sessionId)) {
                    return null;
                }
                String pingId = Fluxzero.generateId();
                return new PingRegistration(pingId, pingScheduler.schedule(
                        clientConfig.getPingTimeout(), () -> handlePingTimeout(session, sessionId, pingId)), true);
            });
            if (registration != null) {
                try {
                    session.sendPing(ByteBuffer.wrap(registration.getId().getBytes()));
                } catch (Exception e) {
                    log().warn("Failed to send ping message", e);
                }
            }
        }
    }

    private void handlePingTimeout(WebsocketSession session, String sessionId, String pingId) {
        PingRegistration current = pingDeadlines.get(sessionId);
        if (current != null && current.getId().equals(pingId) && pingDeadlines.remove(sessionId, current)) {
            JdkWebSocketSession.RuntimeDataState metricState = shouldEmitTransportMetrics()
                    ? transportMetricState(session, null) : null;
            log().warn("Failed to get a ping response in time for session {}. Resetting connection", sessionId);
            abort(session, "Ping failed");
            emitTransportMetricAsync(WebsocketTransportMetric.Event.PING_TIMEOUT, session, metricState);
        }
    }

    public void onPong(ByteBuffer message, WebsocketSession session) {
        acknowledgePong(session);
        executeResultCallback("pong", () -> handlePong(session));
    }

    private void acknowledgePong(WebsocketSession session) {
        pingDeadlines.compute(getNegotiatedSessionId(session), (key, current) -> {
            if (current == null) {
                return null;
            }
            current.cancel();
            return newScheduledPing(session);
        });
    }

    /**
     * Invoked asynchronously after the pong deadline has been acknowledged on the protocol callback path.
     * Subclasses may override this method for observation but should not perform liveness bookkeeping themselves.
     */
    protected void handlePong(WebsocketSession session) {
    }

    @Value
    protected static class PingRegistration implements Registration {
        String id;
        @Delegate
        Registration delegate;
        boolean awaitingPong;

        public PingRegistration(Registration delegate) {
            this(Fluxzero.generateId(), delegate, false);
        }

        PingRegistration(String id, Registration delegate, boolean awaitingPong) {
            this.id = id;
            this.delegate = delegate;
            this.awaitingPong = awaitingPong;
        }
    }

    private static final class RuntimeIngressLiveness {
        private String registrationId;
        private Registration stallDeadline;
        private Registration closeDeadline;
        private boolean stalled;
        private CompletableFuture<Void> stallMetricPublication = CompletableFuture.completedFuture(null);
        private long progressDeadline;
        private long progressSequence;
        private int retainedMessages;

        synchronized void cancel() {
            registrationId = null;
            if (stallDeadline != null) {
                stallDeadline.cancel();
                stallDeadline = null;
            }
            if (closeDeadline != null) {
                closeDeadline.cancel();
                closeDeadline = null;
            }
        }
    }

    @SneakyThrows
    protected void abort(WebsocketSession session, String reason) {
        WebsocketCloseReason closeReason = new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, reason);
        log().warn("Closing session {} due to {}", getNegotiatedSessionId(session), reason);
        CompletableFuture<Void> closeFuture;
        try {
            closeFuture = session.closeAsync(closeReason);
        } catch (Throwable e) {
            log().warn("Failed to start an orderly close for session {}. Aborting transport.",
                       getNegotiatedSessionId(session), e);
            session.abort(closeReason);
            return;
        }
        if (closeFuture == null) {
            session.abort(closeReason);
            return;
        }
        closeFuture.orTimeout(getCloseHandshakeTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log().warn("Orderly close did not complete for session {}. Aborting transport.",
                                   getNegotiatedSessionId(session), error);
                        session.abort(closeReason);
                    }
                });
    }

    protected Duration getCloseHandshakeTimeout() {
        return CLOSE_HANDSHAKE_TIMEOUT;
    }

    @Override
    public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        executeResultCallback("close", () -> handleClose(session, closeReason));
    }

    protected void handleClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        if (!closed.get() && session.isOpen()) {
            session.abort(closeReason);
        }
        if (closeReason.code() > WebsocketCloseReason.GOING_AWAY) {
            log().warn("Connection to endpoint {} closed with reason {} (session: {})", session.getRequestURI(),
                       closeReason, getNegotiatedSessionId(session));
        }
        String sessionId = getNegotiatedSessionId(session);
        Backlog<Request> backlog = sessionBacklogs.remove(sessionId);
        ofNullable(backlog).ifPresent(Backlog::shutDown);
        ofNullable(pingDeadlines.remove(sessionId)).ifPresent(PingRegistration::cancel);
        runtimeIngressBackpressured.remove(sessionId);
        runtimeIngressBackpressureReported.remove(sessionId);
        ofNullable(runtimeIngressLiveness.remove(sessionId)).ifPresent(RuntimeIngressLiveness::cancel);
        runtimeResultDispatcher.releaseSession(sessionId);
        if (backlog != null && !closed.get()) {
            retryOutstandingRequestsAsync(sessionId);
        }
    }

    protected void retryOutstandingRequestsAsync(String sessionId) {
        reconnectExecutor.execute(() -> retryOutstandingRequests(sessionId));
    }

    protected void retryOutstandingRequests(String sessionId) {
        if (!closed.get() && !requests.isEmpty()) {
            try {
                long retryDelayMillis = retryOutstandingRequestsDelay().toMillis();
                if (retryDelayMillis > 0) {
                    sleep(retryDelayMillis);
                }
            } catch (InterruptedException e) {
                currentThread().interrupt();
                throw new IllegalStateException("Thread interrupted while retrying outstanding requests (session: %s)"
                                                        .formatted(sessionId), e);
            }
            synchronized (sessionId.intern()) {
                requests.values().stream().filter(r -> sessionId.equals(r.sessionId)).toList().forEach(
                        r -> {
                            log().info("Retrying request {} using a new session (old session {})",
                                       r.request.getRequestId(), r.sessionId);
                            r.send();
                        });
            }
        }
    }

    /**
     * Delay before outstanding requests from a closed session are resent on a replacement session.
     */
    protected Duration retryOutstandingRequestsDelay() {
        return Duration.ofSeconds(1);
    }

    @Override
    public void onError(WebsocketSession session, Throwable e) {
        executeResultCallback("error", () -> handleError(session, e));
    }

    protected void handleError(WebsocketSession session, Throwable e) {
        if (e instanceof JdkWebSocketSession.RuntimeDataDispatchException dispatchException) {
            JdkWebSocketSession.RuntimeDataState state = dispatchException.state();
            emitTransportMetric(
                    dispatchException.reason() == JdkWebSocketSession.RuntimeDataDispatchException.Reason.OVERFLOW
                            ? WebsocketTransportMetric.Event.RUNTIME_INGRESS_OVERFLOW
                            : WebsocketTransportMetric.Event.RUNTIME_EXECUTOR_REJECTED,
                    session, state);
        }
        log().error("Client side error for web socket session {}, connected to endpoint {}",
                    getNegotiatedSessionId(session), session.getRequestURI(), e);
    }

    private void executeResultCallback(String callbackType, Runnable task) {
        try {
            resultExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            if (closed.get()) {
                log().info("Ignoring websocket {} callback because the client is already closed", callbackType);
                return;
            }
            throw e;
        }
    }

    @Override
    public void close() {
        close(false);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    protected void close(boolean clearOutstandingRequests) {
        if (closed.compareAndSet(false, true)) {
            synchronized (closed) {
                if (clearOutstandingRequests) {
                    requests.clear();
                }
                pingScheduler.shutdown();
                sessionPool.close();
                sessionBacklogs.values().forEach(Backlog::shutDown);
                sessionBacklogs.clear();
                runtimeResultDispatcher.close();
                shutdownExecutor(resultExecutor, "websocket result executor");
                shutdownExecutor(reconnectExecutor, "websocket reconnect executor");
                pingDeadlines.clear();
                runtimeIngressBackpressured.clear();
                runtimeIngressBackpressureReported.clear();
                runtimeIngressLiveness.values().forEach(RuntimeIngressLiveness::cancel);
                runtimeIngressLiveness.clear();
                if (!requests.isEmpty()) {
                    log().warn("{}: Closed websocket session to endpoint with {} outstanding requests",
                               getClass().getSimpleName(), requests.size());
                }
            }
        }
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                log().info("Timed out while waiting for {} to terminate", name);
            }
        } catch (InterruptedException e) {
            currentThread().interrupt();
            log().info("Interrupted while waiting for {} to terminate", name);
        }
    }

    protected void tryPublishMetrics(JsonType message, Metadata metadata) {
        if (!allowMetrics || clientConfig.isDisableMetrics() || closed.get()) {
            return;
        }
        try {
            Object metric = message.toMetric();
            if (metric != null) {
                Fluxzero.getOptionally().ifPresentOrElse(
                        f -> publishMetrics(metric, metadata),
                        () -> client.getGatewayClient(METRICS).append(
                                STORED, asMessage(metric).addMetadata(metadata).serialize(getFallbackSerializer())));
            }
        } catch (SessionPool.ClientClosedException ignored) {
        } catch (GatewayException e) {
            if (!closed.get() && !(e.getCause() instanceof SessionPool.ClientClosedException)) {
                log().warn("Failed to publish websocket metric", e);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                log().warn("Failed to publish websocket metric", e);
            }
        }
    }

    private void emitTransportMetric(WebsocketTransportMetric.Event event, WebsocketSession session,
                                     JdkWebSocketSession.RuntimeDataState knownState) {
        if (!shouldEmitTransportMetrics()) {
            return;
        }
        emitTransportMetricSnapshot(event, session, transportMetricState(session, knownState));
    }

    private void emitTransportMetricSnapshot(WebsocketTransportMetric.Event event, WebsocketSession session,
                                             JdkWebSocketSession.RuntimeDataState state) {
        if (!shouldEmitTransportMetrics()) {
            return;
        }
        try {
            RuntimeResultDispatcher.State completionState = runtimeResultDispatcher.state();
            WebsocketTransportMetric metric = new WebsocketTransportMetric(
                    event, getClass().getSimpleName(), getRuntimeVersion(session).orElse(null),
                    Runtime.version().feature(), transportWorkerMode(session),
                    state.retainedMessages(), state.retainedBytes(), state.inFlightMessages(),
                    state.inFlightBytes(), state.activeMessages(), state.activeBytes(),
                    state.pendingMessages(), state.pendingBytes(), state.maxConcurrency(),
                    state.maxRetainedMessages(), state.maxRetainedBytes(),
                    state.deferredFrameBytes(),
                    runtimeIngressBackpressured.contains(getNegotiatedSessionId(session)),
                    completionState.workGroups(), completionState.activeResults(), completionState.pendingResults(),
                    completionState.maxConcurrency(),
                    clientConfig.getRuntimeIngressStallCloseTimeout().toMillis(),
                    state.lastInboundAgeMillis());
            publishTransportMetric(metric, metricsMetadata().with(
                    "transportFormat", getTransportFormat(session),
                    "compression", getCompressionAlgorithm(session)));
        } catch (Exception e) {
            if (!closed.get()) {
                log().warn("Failed to emit websocket transport metric", e);
            }
        }
    }

    private CompletableFuture<Void> emitTransportMetricAsync(
            WebsocketTransportMetric.Event event, WebsocketSession session,
            JdkWebSocketSession.RuntimeDataState state) {
        CompletableFuture<Void> publication = new CompletableFuture<>();
        if (state == null || !shouldEmitTransportMetrics()) {
            publication.complete(null);
            return publication;
        }
        try {
            resultExecutor.execute(() -> {
                try {
                    emitTransportMetricSnapshot(event, session, state);
                    publication.complete(null);
                } catch (Throwable e) {
                    publication.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            publication.completeExceptionally(e);
            if (!closed.get()) {
                log().warn("Failed to schedule websocket transport metric publication", e);
            }
        }
        return publication;
    }

    private JdkWebSocketSession.RuntimeDataState transportMetricState(
            WebsocketSession session, JdkWebSocketSession.RuntimeDataState knownState) {
        JdkWebSocketSession.RuntimeDataState observedState = session instanceof JdkWebSocketSession jdkSession
                ? jdkSession.runtimeDataState() : JdkWebSocketSession.RuntimeDataState.empty();
        return knownState == null ? observedState
                : knownState.withLastInboundAgeMillis(observedState.lastInboundAgeMillis());
    }

    private String transportWorkerMode(WebsocketSession session) {
        return session instanceof JdkWebSocketSession jdkSession
                ? jdkSession.runtimeDataWorkerMode() : "custom-connector";
    }

    private boolean shouldEmitTransportMetrics() {
        return transportMetricsEnabled && allowMetrics && !clientConfig.isDisableMetrics() && !closed.get();
    }

    void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
        try {
            Fluxzero.getOptionally().ifPresentOrElse(
                    f -> publishMetrics(metric, metadata),
                    () -> client.getGatewayClient(METRICS).append(
                            NONE, asMessage(metric).addMetadata(metadata).serialize(getFallbackSerializer())));
        } catch (SessionPool.ClientClosedException ignored) {
        } catch (GatewayException e) {
            if (!closed.get() && !(e.getCause() instanceof SessionPool.ClientClosedException)) {
                log().warn("Failed to publish websocket transport metric", e);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                log().warn("Failed to publish websocket transport metric", e);
            }
        }
    }

    protected Metadata metricsMetadata() {
        return Metadata.empty();
    }

    @RequiredArgsConstructor
    protected class WebSocketRequest {
        private final Request request;
        private final CompletableFuture<RequestResult> result = new CompletableFuture<>();
        private final Map<String, String> correlationData;
        private final DispatchInterceptor adhocMetricsInterceptor;
        private final Fluxzero fluxzero;
        private volatile String sessionId;
        private volatile long sendTimestamp;

        @SuppressWarnings("unchecked")
        protected <T extends RequestResult> CompletableFuture<T> send() {
            WebsocketSession session;
            try {
                session = request instanceof Command c ? sessionPool.get(c.routingKey()) : sessionPool.get();
            } catch (SessionPool.ClientClosedException e) {
                result.completeExceptionally(e);
                return (CompletableFuture<T>) result;
            } catch (Exception e) {
                if (!closed.get()) {
                    log().error("Failed to get websocket session to send request {}", request, e);
                }
                result.completeExceptionally(e);
                return (CompletableFuture<T>) result;
            }
            this.sessionId = getNegotiatedSessionId(session);
            requests.put(request.getRequestId(), this);

            try {
                sendTimestamp = System.currentTimeMillis();
                AbstractWebsocketClient.this.send(request, correlationData, session);
            } catch (Exception e) {
                requests.remove(request.getRequestId());
                result.completeExceptionally(e);
            }
            return (CompletableFuture<T>) result;
        }
    }

    protected static ConnectionSetup createConnectionSetup(ClientConfig clientConfig) {
        return createConnectionSetup(clientConfig, null);
    }

    protected static ConnectionSetup createConnectionSetup(ClientConfig clientConfig, String replacedSessionId) {
        PropertySource propertySource = DefaultPropertySource.getInstance();
        return createConnectionSetup(clientConfig, replacedSessionId,
                                     !clientConfig.isDisableMetrics() && transportMetricsEnabled(propertySource));
    }

    static ConnectionSetup createConnectionSetup(ClientConfig clientConfig, String replacedSessionId,
                                                  boolean transportMetricsEnabled) {
        ClientHandshakeConfigurator configurator = new ClientHandshakeConfigurator(
                WebSocketCapabilities.newShortSessionId(), clientConfig, replacedSessionId);
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        configurator.beforeRequest(headers);
        Map<String, Object> userProperties = new java.util.LinkedHashMap<>();
        userProperties.put(CLIENT_HANDSHAKE_CONFIGURATOR_USER_PROPERTY, configurator);
        userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true);
        userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY,
                           clientConfig.getMaxConcurrentRuntimeWebSocketMessages());
        userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY,
                           clientConfig.getMaxRetainedRuntimeWebSocketMessages());
        userProperties.put(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY,
                           clientConfig.getMaxRetainedRuntimeWebSocketBytes());
        if (transportMetricsEnabled || !clientConfig.getRuntimeIngressStallCloseTimeout().isZero()) {
            userProperties.put(JdkWebSocketSession.SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY, true);
        }
        if (transportMetricsEnabled) {
            userProperties.put(JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true);
        }
        return new ConnectionSetup(new WebsocketConnectionOptions(
                headers, userProperties, clientConfig.getConnectionTimeout(), List.of()), configurator);
    }

    static boolean transportMetricsEnabled(PropertySource propertySource) {
        return propertySource.getBoolean(TRANSPORT_METRICS_ENABLED_PROPERTY);
    }

    protected String getNegotiatedSessionId(WebsocketSession session) {
        Object cached = session.getUserProperties().get(NEGOTIATED_SESSION_ID_USER_PROPERTY);
        if (cached instanceof String sessionId) {
            return sessionId;
        }
        String sessionId = negotiatedSessionId(
                ofNullable(session.getUserProperties().get(CLIENT_SESSION_ID_USER_PROPERTY))
                        .map(Object::toString).orElseThrow(),
                ofNullable(session.getUserProperties().get(RUNTIME_SESSION_ID_USER_PROPERTY))
                        .map(Object::toString).orElseThrow());
        session.getUserProperties().put(NEGOTIATED_SESSION_ID_USER_PROPERTY, sessionId);
        return sessionId;
    }

    private static String negotiatedSessionId(String clientSessionId, String runtimeSessionId) {
        return clientSessionId + "_" + runtimeSessionId;
    }

    protected CompressionAlgorithm getCompressionAlgorithm(WebsocketSession session) {
        return (CompressionAlgorithm) session.getUserProperties().get(SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY);
    }

    protected WebSocketTransportFormat getTransportFormat(WebsocketSession session) {
        return ofNullable(session.getUserProperties().get(SELECTED_TRANSPORT_FORMAT_USER_PROPERTY))
                .filter(WebSocketTransportFormat.class::isInstance)
                .map(WebSocketTransportFormat.class::cast)
                .orElse(WebSocketTransportFormat.JSON);
    }

    protected WebSocketTransportCodec transportCodec(WebsocketSession session) {
        return transportCodecs.computeIfAbsent(getTransportFormat(session),
                                               format -> WebSocketTransportCodecs.forFormat(format, objectMapper));
    }

    protected Optional<String> getRuntimeVersion(WebsocketSession session) {
        return ofNullable(session.getUserProperties().get(RUNTIME_VERSION_USER_PROPERTY))
                .map(Object::toString)
                .filter(version -> !version.isBlank());
    }

    protected record ConnectionSetup(WebsocketConnectionOptions options, ClientHandshakeConfigurator configurator) {
    }

    @FunctionalInterface
    interface PingSchedulerFactory {
        TaskScheduler create(AbstractWebsocketClient client, int numberOfSessions);
    }

    @RequiredArgsConstructor
    protected static class ClientHandshakeConfigurator {
        @Getter
        private final String clientSessionId;
        private final ClientConfig clientConfig;
        private final String replacedSessionId;
        @Getter
        private volatile String runtimeSessionId;
        @Getter
        private volatile String runtimeVersion;
        @Getter
        private volatile CompressionAlgorithm selectedCompressionAlgorithm;
        @Getter
        private volatile WebSocketTransportFormat selectedTransportFormat;

        public void beforeRequest(Map<String, List<String>> headers) {
            headers.put(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, new ArrayList<>(List.of(clientSessionId)));
            if (replacedSessionId != null && !replacedSessionId.isBlank()) {
                headers.put(WebSocketCapabilities.REPLACES_SESSION_ID_HEADER,
                            new ArrayList<>(List.of(replacedSessionId)));
            }
            SdkVersion.version().ifPresent(sdkVersion ->
                                                   headers.put(WebSocketCapabilities.CLIENT_SDK_VERSION_HEADER,
                                                               new ArrayList<>(List.of(sdkVersion))));
            WebSocketCapabilities.asHeaders(clientConfig.getSupportedCompressionAlgorithms()).forEach(
                    (name, values) -> headers.put(name, new ArrayList<>(values)));
            WebSocketCapabilities.asTransportHeaders(clientConfig.getSupportedTransportFormats()).forEach(
                    (name, values) -> headers.put(name, new ArrayList<>(values)));
        }

        public void afterResponse(Map<String, List<String>> headers) {
            runtimeSessionId = WebSocketCapabilities.getRuntimeSessionId(headers).orElse(null);
            runtimeVersion = WebSocketCapabilities.getRuntimeVersion(headers).orElse(null);
            selectedCompressionAlgorithm =
                    WebSocketCapabilities.getSelectedCompressionAlgorithm(headers).orElse(null);
            selectedTransportFormat = WebSocketCapabilities.getSelectedTransportFormat(headers).orElse(null);
        }
    }

}
