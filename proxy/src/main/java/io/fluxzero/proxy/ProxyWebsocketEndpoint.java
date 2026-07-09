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

package io.fluxzero.proxy;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.DisconnectEvent;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebRequest;
import lombok.Builder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.fluxzero.common.ObjectUtils.getBytes;
import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.sdk.tracking.client.DefaultTracker.start;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
public class ProxyWebsocketEndpoint {
    static final String metadataPrefix = "_metadata:", clientIdKey = "_clientId", trackerIdKey = "_trackerId";
    static final Duration CLOSE_NOTIFICATION_TIMEOUT = Duration.ofSeconds(5);
    static final String keepAlivePingPrefix = "__fluxzero_proxy_keepalive__:";
    private static final Serializer metricsSerializer = new JacksonSerializer();

    private final Map<String, ProxyWebsocketSession> openSessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingCloseNotifications = new ConcurrentHashMap<>();
    private final Map<String, KeepAliveState> keepAliveStates = new ConcurrentHashMap<>();

    private final Client client;
    private final GatewayClient requestGateway;
    private final RequestHandler requestHandler;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Registration registration;
    private volatile Duration closeNotificationTimeout = CLOSE_NOTIFICATION_TIMEOUT;
    private volatile boolean awaitCloseAcknowledgements = true;
    private volatile Duration keepAlivePingDelay = ProxyServer.getConfiguredWebsocketPingDelay();
    private volatile Duration keepAlivePingTimeout = ProxyServer.getConfiguredWebsocketPingTimeout();
    private volatile ScheduledExecutorService keepAliveExecutor;

    public ProxyWebsocketEndpoint(Client client, RequestHandler requestHandler) {
        this.client = client;
        this.requestGateway = client.getGatewayClient(MessageType.WEBREQUEST);
        this.requestHandler = requestHandler;
    }

    void setKeepAlive(Duration pingDelay, Duration pingTimeout) {
        if (pingDelay == null || pingDelay.isNegative()) {
            throw new IllegalArgumentException("pingDelay must be >= 0");
        }
        if (pingTimeout == null || pingTimeout.isZero() || pingTimeout.isNegative()) {
            throw new IllegalArgumentException("pingTimeout must be > 0");
        }
        this.keepAlivePingDelay = pingDelay;
        this.keepAlivePingTimeout = pingTimeout;
    }

    public void onOpen(ProxyWebsocketSession session) {
        ensureStarted();
        openSessions.put(session.getId(), session);
        sendRequest(session, HttpRequestMethod.WS_OPEN, null);
        scheduleKeepAlive(session);
    }

    public void onBinary(ProxyWebsocketSession session, byte[] message) {
        sendRequest(session, HttpRequestMethod.WS_MESSAGE, message);
    }

    public void onText(ProxyWebsocketSession session, String message) {
        sendRequest(session, HttpRequestMethod.WS_MESSAGE, message.getBytes(UTF_8));
    }

    public void onPong(ProxyWebsocketSession session, ByteBuffer message) {
        byte[] payload = getBytes(message);
        if (handleKeepAlivePong(session, payload)) {
            return;
        }
        sendRequest(session, HttpRequestMethod.WS_PONG, payload);
    }

    @SneakyThrows
    public void onClose(ProxyWebsocketSession session, WebsocketCloseReason closeReason) {
        cancelKeepAlive(session.getId());
        openSessions.remove(session.getId());
        Duration timeout = closeNotificationTimeout;
        Throwable failure = null;
        try {
            sendCloseRequest(session, closeReason).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            failure = unwrapCloseFailure(e);
            throw (RuntimeException) failure;
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            completeCloseNotification(session.getId(), failure);
        }
    }

    public void onError(ProxyWebsocketSession session, Throwable error) {
        if (error instanceof ClosedChannelException) {
            log.debug("Websocket session {} was closed while the channel was already closing",
                      session == null ? "<unknown>" : session.getId());
            return;
        }
        if (isIdleTimeout(error)) {
            log.warn("Websocket session {} closed after reaching the idle timeout. "
                     + "The client did not send websocket traffic or pings before the proxy timeout elapsed.",
                     session == null ? "<unknown>" : session.getId());
            return;
        }
        log.warn("Error in session {}", session == null ? "<unknown>" : session.getId(), error);
    }

    private boolean isIdleTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebSocketTimeoutException
                && String.valueOf(cause.getMessage()).toLowerCase().contains("idle timeout")) {
                return true;
            }
        }
        return false;
    }

    private void scheduleKeepAlive(ProxyWebsocketSession session) {
        Duration pingDelay = keepAlivePingDelay;
        if (pingDelay.isZero() || pingDelay.isNegative()) {
            return;
        }
        KeepAliveState state = keepAliveStates.computeIfAbsent(session.getId(), ignored -> new KeepAliveState());
        scheduleNextKeepAlive(session, state);
    }

    private void scheduleNextKeepAlive(ProxyWebsocketSession session, KeepAliveState state) {
        synchronized (state) {
            state.cancelPingTask();
            if (!session.isOpen() || keepAlivePingDelay.isZero() || keepAlivePingDelay.isNegative()
                || keepAliveStates.get(session.getId()) != state) {
                return;
            }
            state.pingTask = keepAliveExecutor().schedule(
                    () -> sendKeepAlivePing(session, state), keepAlivePingDelay.toMillis(), MILLISECONDS);
        }
    }

    private void sendKeepAlivePing(ProxyWebsocketSession session, KeepAliveState state) {
        String pingId = keepAlivePingPrefix + Fluxzero.generateId();
        synchronized (state) {
            state.pingTask = null;
            if (!session.isOpen() || keepAlivePingDelay.isZero() || keepAlivePingDelay.isNegative()
                || keepAliveStates.get(session.getId()) != state) {
                return;
            }
            state.expectedPong = pingId;
            state.cancelTimeoutTask();
            state.timeoutTask = keepAliveExecutor().schedule(
                    () -> handleKeepAliveTimeout(session, state, pingId),
                    keepAlivePingTimeout.toMillis(), MILLISECONDS);
        }
        try {
            session.sendPing(ByteBuffer.wrap(pingId.getBytes(UTF_8))).whenComplete((ignored, error) -> {
                if (error != null) {
                    handleKeepAliveSendFailure(session, unwrapException(error));
                }
            });
        } catch (Throwable e) {
            handleKeepAliveSendFailure(session, unwrapException(e));
        }
    }

    private boolean handleKeepAlivePong(ProxyWebsocketSession session, byte[] payload) {
        String pong = new String(payload, UTF_8);
        if (!pong.startsWith(keepAlivePingPrefix)) {
            return false;
        }
        KeepAliveState state = keepAliveStates.get(session.getId());
        if (state == null) {
            return true;
        }
        synchronized (state) {
            if (!pong.equals(state.expectedPong)) {
                return true;
            }
            state.expectedPong = null;
            state.cancelTimeoutTask();
        }
        scheduleNextKeepAlive(session, state);
        return true;
    }

    private void handleKeepAliveTimeout(ProxyWebsocketSession session, KeepAliveState state, String pingId) {
        synchronized (state) {
            if (!pingId.equals(state.expectedPong) || keepAliveStates.get(session.getId()) != state) {
                return;
            }
            state.expectedPong = null;
        }
        log.warn("Timed out waiting for websocket keep-alive pong for session {}. Closing connection.",
                 session.getId());
        closeAfterKeepAliveFailure(session);
    }

    private void handleKeepAliveSendFailure(ProxyWebsocketSession session, Throwable error) {
        if (error instanceof ClosedChannelException) {
            log.debug("Failed to send websocket keep-alive ping because session {} is already closing",
                      session.getId());
            return;
        }
        handleSendFailure(session, error);
        closeAfterKeepAliveFailure(session);
    }

    private void closeAfterKeepAliveFailure(ProxyWebsocketSession session) {
        cancelKeepAlive(session.getId());
        if (session.isOpen()) {
            session.close(new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "Proxy keep-alive failed"))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.warn("Failed to close websocket session {} after keep-alive failure",
                                     session.getId(), unwrapException(error));
                        }
                    });
        }
    }

    private void cancelKeepAlive(String sessionId) {
        KeepAliveState state = keepAliveStates.remove(sessionId);
        if (state != null) {
            synchronized (state) {
                state.cancel();
            }
        }
    }

    private void cancelKeepAlives() {
        keepAliveStates.keySet().forEach(this::cancelKeepAlive);
    }

    private ScheduledExecutorService keepAliveExecutor() {
        ScheduledExecutorService executor = keepAliveExecutor;
        if (executor == null || executor.isShutdown()) {
            synchronized (this) {
                executor = keepAliveExecutor;
                if (executor == null || executor.isShutdown()) {
                    executor = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform()
                                    .daemon(true)
                                    .name("fluxzero-proxy-websocket-keepalive-", 0L)
                                    .factory());
                    keepAliveExecutor = executor;
                }
            }
        }
        return executor;
    }

    private void shutdownKeepAliveExecutor() {
        cancelKeepAlives();
        ScheduledExecutorService executor = keepAliveExecutor;
        if (executor != null) {
            executor.shutdownNow();
            keepAliveExecutor = null;
        }
    }

    protected CompletableFuture<?> sendRequest(ProxyWebsocketSession session, String method, byte[] payload) {
        return requestGateway.append(Guarantee.STORED, createRequest(session, method, payload));
    }

    private SerializedMessage createRequest(ProxyWebsocketSession session, String method, byte[] payload) {
        Metadata metadata = getContext(session).metadata().with(WebRequest.methodKey, method);
        SerializedMessage request = new SerializedMessage(new Data<>(payload == null ? new byte[0] : payload,
                                                                    null, 0, "unknown"),
                                                         metadata, Fluxzero.generateId(),
                                                         Fluxzero.currentClock().millis());
        request.setSource(client.id());
        request.setTarget(getContext(session).trackerId());
        return request;
    }

    protected CompletableFuture<?> sendCloseRequest(ProxyWebsocketSession session, WebsocketCloseReason closeReason) {
        SerializedMessage request = createRequest(session, HttpRequestMethod.WS_CLOSE,
                                                  String.valueOf(closeReason.code()).getBytes(UTF_8));
        if (!awaitCloseAcknowledgements) {
            return requestGateway.append(Guarantee.NONE, request);
        }
        return requestHandler.sendRequest(request, m -> requestGateway.append(Guarantee.STORED, m),
                                          closeNotificationTimeout);
    }

    protected void handleResultMessages(List<SerializedMessage> resultMessages) {
        resultMessages.forEach(m -> {
            var sessionId = WebRequest.getSocketSessionId(m.getMetadata());
            if (sessionId != null) {
                var session = openSessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        CompletableFuture<Void> send = switch (m.getMetadata().getOrDefault("function", "message")) {
                            case "message" -> sendMessage(m, session);
                            case "ping" -> sendPing(m, session);
                            case "close" -> sendClose(m, session);
                            case "ack" -> CompletableFuture.completedFuture(null);
                            default -> CompletableFuture.completedFuture(null);
                        };
                        send.whenComplete((ignored, error) -> {
                            if (error != null) {
                                handleSendFailure(session, unwrapException(error));
                            }
                        });
                    } catch (Exception e) {
                        handleSendFailure(session, unwrapException(e));
                    }
                }
            }
        });
    }

    private void handleSendFailure(ProxyWebsocketSession session, Throwable error) {
        if (error instanceof WebsocketSendBacklogExceededException backlogExceeded) {
            publishBackpressureMetric(backlogExceeded);
            return;
        }
        log.warn("Failed to send websocket result to client (session {})", session.getId(), error);
    }

    private void publishBackpressureMetric(WebsocketSendBacklogExceededException failure) {
        try {
            ProxyWebsocketBackpressureEvent event = new ProxyWebsocketBackpressureEvent(
                    failure.sessionId(), failure.clientId(), failure.trackerId(), failure.namespace(),
                    failure.pendingSends(), failure.maxPendingSends());
            Metadata metadata = Metadata.of(
                    "sessionId", failure.sessionId(),
                    "clientId", failure.clientId(),
                    "trackerId", failure.trackerId(),
                    "namespace", failure.namespace(),
                    "pendingSends", failure.pendingSends(),
                    "maxPendingSends", failure.maxPendingSends());
            client.getGatewayClient(MessageType.METRICS)
                    .append(Guarantee.NONE, new Message(event, metadata).serialize(metricsSerializer))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.warn("Failed to publish websocket backpressure metric for session {}",
                                     failure.sessionId(), error);
                        }
                    });
        } catch (Throwable e) {
            log.warn("Failed to publish websocket backpressure metric for session {}", failure.sessionId(), e);
        }
    }

    private CompletableFuture<Void> sendMessage(SerializedMessage m, ProxyWebsocketSession session) {
        if (byte[].class.getName().equals(m.getData().getType())) {
            return session.sendBinary(ByteBuffer.wrap(m.getData().getValue()));
        }
        return session.sendText(new String(m.getData().getValue(), UTF_8));
    }

    private CompletableFuture<Void> sendPing(SerializedMessage m, ProxyWebsocketSession session) {
        return session.sendPing(ByteBuffer.wrap(m.getData().getValue()));
    }

    private CompletableFuture<Void> sendClose(SerializedMessage m, ProxyWebsocketSession session) {
        int code = Integer.parseInt(new String(m.getData().getValue(), UTF_8));
        return session.close(new WebsocketCloseReason(code, null));
    }

    protected void handleDisconnects(List<SerializedMessage> resultMessages) {
        Set<String> clientIds =
                resultMessages.stream().map(m -> JsonUtils.fromJson(m.getData().getValue(), DisconnectEvent.class))
                        .map(DisconnectEvent::getClientId).collect(Collectors.toSet());
        openSessions.values().stream().filter(s -> clientIds.contains(getContext(s).clientId())).forEach(
                session -> {
                    if (session.isOpen()) {
                        session.close(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "going away"))
                                .whenComplete((ignored, error) -> {
                                    if (error != null) {
                                        log.warn("Failed to close session {}", session.getId(), error);
                                    }
                                });
                    }
                });
    }

    protected SessionContext getContext(ProxyWebsocketSession session) {
        return (SessionContext) session.getUserProperties().computeIfAbsent("context", c -> {
            var contextBuilder = SessionContext.builder();
            Map<String, String> map = new LinkedHashMap<>();
            session.getRequestParameterMap().forEach((k, v) -> {
                if (k.startsWith(metadataPrefix)) {
                    String name = k.substring(metadataPrefix.length());
                    map.put(name, v.getFirst());
                } else if (k.equals(trackerIdKey)) {
                    contextBuilder.trackerId(v.getFirst());
                } else if (k.equals(clientIdKey)) {
                    contextBuilder.clientId(v.getFirst());
                }
            });
            contextBuilder.metadata(Metadata.of(map).with("sessionId", session.getId()));
            return contextBuilder.build();
        });
    }

    protected void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            registration = start(this::handleResultMessages, MessageType.WEBRESPONSE,
                                 ConsumerConfiguration.builder()
                                         .name(format("%s_%s", client.name(), "$websocket-handler")).ignoreSegment(true)
                                         .clientControlledIndex(true).filterMessageTarget(true)
                                         .namespace(client.namespace())
                                         .minIndex(IndexUtils.indexFromTimestamp(
                                                 Fluxzero.currentTime().minusSeconds(2))).build(),
                                 client).merge(start(this::handleDisconnects, MessageType.METRICS,
                                                     ConsumerConfiguration.builder()
                                                             .name(format("%s_%s", client.name(), "$websocket-handler"))
                                                             .ignoreSegment(true)
                                                             .clientControlledIndex(true)
                                                             .namespace(client.namespace())
                                                             .typeFilter(Pattern.quote(DisconnectEvent.class.getName()))
                                                             .minIndex(IndexUtils.indexFromTimestamp(
                                                                     Fluxzero.currentTime().minusSeconds(1)))
                                                             .build(),
                                                     client));
        }
    }

    public void shutDown() {
        shutDown(CLOSE_NOTIFICATION_TIMEOUT, true, true);
    }

    void shutDown(Duration closeNotificationTimeout) {
        shutDown(closeNotificationTimeout, true, true);
    }

    void shutDown(Duration closeNotificationTimeout, boolean warnOnTimeout) {
        shutDown(closeNotificationTimeout, true, warnOnTimeout);
    }

    void shutDown(Duration closeNotificationTimeout, boolean awaitCloseAcknowledgements, boolean warnOnTimeout) {
        try {
            if (started.compareAndSet(true, false) && registration != null) {
                this.closeNotificationTimeout = closeNotificationTimeout;
                this.awaitCloseAcknowledgements = awaitCloseAcknowledgements;
                registration.cancel();
                var closeNotifications =
                        openSessions.values().stream().map(this::closeSession).toArray(CompletableFuture[]::new);
                if (closeNotifications.length > 0) {
                    try {
                        CompletableFuture.allOf(closeNotifications)
                                .get(closeNotificationTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        if (warnOnTimeout) {
                            log.warn("Timed out waiting for websocket close notifications during shutdown", e);
                        }
                    }
                }
            }
        } finally {
            shutdownKeepAliveExecutor();
        }
    }

    private CompletableFuture<Void> closeSession(ProxyWebsocketSession session) {
        cancelKeepAlive(session.getId());
        var closeNotification = pendingCloseNotifications.computeIfAbsent(session.getId(), ignored -> new CompletableFuture<>());
        try {
            if (session.isOpen()) {
                session.close(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "Redeployment"))
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                log.warn("Failed to close session when leaving: {}", session.getId(), error);
                                completeCloseNotification(session.getId(), error);
                            }
                        });
            } else {
                openSessions.remove(session.getId());
                completeCloseNotification(session.getId(), null);
            }
        } catch (Throwable e) {
            log.warn("Failed to close session when leaving: {}", session.getId(), e);
            completeCloseNotification(session.getId(), e);
        }
        return closeNotification;
    }

    private void completeCloseNotification(String sessionId, Throwable failure) {
        var notification = pendingCloseNotifications.remove(sessionId);
        if (notification == null) {
            return;
        }
        if (failure == null) {
            notification.complete(null);
        } else {
            notification.completeExceptionally(failure);
        }
    }

    private RuntimeException unwrapCloseFailure(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Failed to process websocket close request", cause);
    }

    @Builder
    protected record SessionContext(Metadata metadata, String clientId, String trackerId) {
    }

    private static class KeepAliveState {
        private ScheduledFuture<?> pingTask;
        private ScheduledFuture<?> timeoutTask;
        private String expectedPong;

        private void cancelPingTask() {
            if (pingTask != null) {
                pingTask.cancel(false);
                pingTask = null;
            }
        }

        private void cancelTimeoutTask() {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
                timeoutTask = null;
            }
        }

        private void cancel() {
            expectedPong = null;
            cancelPingTask();
            cancelTimeoutTask();
        }
    }
}
