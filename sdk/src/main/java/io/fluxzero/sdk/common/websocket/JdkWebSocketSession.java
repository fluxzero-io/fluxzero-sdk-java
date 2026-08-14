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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

class JdkWebSocketSession implements WebsocketSession {
    private static final Object WEBSOCKET_ASSEMBLY_KEY = new Object();
    private static final CompletableFuture<Void> COMPLETED_RECEIVE = CompletableFuture.completedFuture(null);
    private static final int RECEIVE_DEMAND_OUTSTANDING = 1 << 30;
    private static final int ACTIVE_RECEIVE_INVOCATIONS_MASK = RECEIVE_DEMAND_OUTSTANDING - 1;
    static final String SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkRuntimeDataDispatch";
    static final String SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkRuntimeDataMaxConcurrency";
    static final String SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkRuntimeDataMaxRetainedMessages";
    static final String SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkRuntimeDataMaxRetainedBytes";
    static final String SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkTransportMetricsEnabled";
    static final String SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkRuntimeIngressProgressEnabled";
    static final int DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES = 3;
    static final int DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES = 128;
    static final long DEFAULT_MAX_RETAINED_RUNTIME_BYTES = 16L * 1024 * 1024;

    private final JdkWebsocketConnector connector;
    private final WebsocketEndpoint endpoint;
    private final JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse;
    private final Executor callbackExecutor;
    private final RuntimeIngressController<WebsocketEndpoint.ReceiveTiming> runtimeDataDispatcher;
    private final String runtimeDataWorkerMode;
    private final boolean trackInboundActivity;
    private final URI requestUri;
    private final Map<String, Object> userProperties = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> openFuture = new CompletableFuture<>();
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicInteger receiveState = new AtomicInteger();
    private final AtomicBoolean runtimeIngressBackpressured = new AtomicBoolean();
    private final AtomicBoolean runtimeDataStopping = new AtomicBoolean();
    private final AtomicBoolean closeNotified = new AtomicBoolean();
    private final AtomicBoolean runtimeDataFailureNotified = new AtomicBoolean();
    private final CompletableFuture<Void> closeHandshakeFuture = new CompletableFuture<>();
    private final Object closeInitiationLock = new Object();
    private final Object binaryMessageLock = new Object();
    /*
     * Keep calls into java.net.http.WebSocket ordered, but do not hold this monitor while waiting for the returned
     * CompletableFuture to complete. Slow network completion should not make the monitor itself a throughput bottleneck.
     */
    private final Object sendInitiationLock = new Object();
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> closeSendFuture;
    private volatile ByteArrayOutputStream binaryMessage = new ByteArrayOutputStream();
    private boolean binaryMessageFragmented;
    private DeferredBinaryFrame deferredBinaryFrame;
    private volatile WebSocket webSocket;
    private volatile long lastInboundNanos;

    JdkWebSocketSession(JdkWebsocketConnector connector, WebsocketEndpoint endpoint,
                        WebsocketConnectionOptions options, URI requestUri,
                        JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse,
                        Executor callbackExecutor) {
        this(connector, endpoint, options, requestUri, handshakeResponse, callbackExecutor, callbackExecutor);
    }

    JdkWebSocketSession(JdkWebsocketConnector connector, WebsocketEndpoint endpoint,
                        WebsocketConnectionOptions options, URI requestUri,
                        JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse,
                        Executor callbackExecutor, Executor runtimeDataExecutor) {
        this(connector, endpoint, options, requestUri, handshakeResponse, callbackExecutor, runtimeDataExecutor,
             runtimeDataMaxConcurrency(options), runtimeDataMaxRetainedMessages(options),
             runtimeDataMaxRetainedBytes(options));
    }

    private JdkWebSocketSession(JdkWebsocketConnector connector, WebsocketEndpoint endpoint,
                                WebsocketConnectionOptions options, URI requestUri,
                                JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse,
                                Executor callbackExecutor, Executor runtimeDataExecutor,
                                int maxConcurrentRuntimeMessages, int maxRetainedRuntimeMessages,
                                long maxRetainedRuntimeBytes) {
        this.connector = connector;
        this.endpoint = endpoint;
        this.handshakeResponse = handshakeResponse;
        this.callbackExecutor = callbackExecutor;
        this.requestUri = requestUri;
        this.userProperties.putAll(options.userProperties());
        this.userProperties.remove(SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY);
        this.userProperties.remove(SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY);
        this.userProperties.remove(SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY);
        this.runtimeDataWorkerMode = JdkWebsocketConnector.runtimeDataWorkerMode(
                callbackExecutor, runtimeDataExecutor);
        this.runtimeDataDispatcher = Boolean.TRUE.equals(
                options.userProperties().get(SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY))
                ? new RuntimeIngressController<>(
                        runtimeDataExecutor, maxConcurrentRuntimeMessages, maxRetainedRuntimeMessages,
                        maxRetainedRuntimeBytes, this::dispatchBinaryMessage, this::handleRuntimeIngressFailure,
                        this::resumeRuntimeIngress,
                        Boolean.TRUE.equals(options.userProperties().get(
                                SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY))
                                ? this::handleRuntimeIngressProgress : null,
                        endpoint.captureReceiveTiming()) : null;
        this.trackInboundActivity = Boolean.TRUE.equals(
                options.userProperties().get(SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY));
        this.lastInboundNanos = trackInboundActivity ? System.nanoTime() : 0L;
    }

    private static int runtimeDataMaxConcurrency(WebsocketConnectionOptions options) {
        return integerUserProperty(options, SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY,
                                   DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
    }

    private static int runtimeDataMaxRetainedMessages(WebsocketConnectionOptions options) {
        return integerUserProperty(options, SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY,
                                   DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES);
    }

    private static long runtimeDataMaxRetainedBytes(WebsocketConnectionOptions options) {
        Object value = options.userProperties().get(SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY);
        if (value == null) {
            return DEFAULT_MAX_RETAINED_RUNTIME_BYTES;
        }
        if (value instanceof Long result) {
            return result;
        }
        throw new IllegalArgumentException(
                SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY + " must be a long");
    }

    private static int integerUserProperty(WebsocketConnectionOptions options, String name, int defaultValue) {
        Object value = options.userProperties().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer result) {
            return result;
        }
        throw new IllegalArgumentException(name + " must be an integer");
    }

    WebSocket.Listener createListener() {
        return trackInboundActivity ? new ActivityTrackingListener() : new Listener();
    }

    void awaitOpen() throws IOException {
        try {
            openFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening websocket endpoint " + requestUri, e);
        } catch (ExecutionException e) {
            throw new IOException("Websocket endpoint failed to open " + requestUri, e.getCause());
        }
    }

    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public Map<String, List<String>> getHandshakeResponseHeaders() {
        return handshakeResponse.headers();
    }

    @Override
    public Set<WebsocketSession> getOpenSessions() {
        return connector.getOpenSessions();
    }

    @Override
    public boolean isOpen() {
        WebSocket webSocket = this.webSocket;
        return open.get() && webSocket != null && !webSocket.isInputClosed() && !webSocket.isOutputClosed();
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        await(sendBinaryAsync(data), 0);
    }

    @Override
    public CompletableFuture<Void> sendBinaryAsync(ByteBuffer data) {
        return sendBinaryAsync(data, 0);
    }

    @Override
    public CompletableFuture<Void> sendBinaryAsync(ByteBuffer data, int maxFragmentBytes) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(new ClosedChannelException());
        }
        ByteBuffer message = data.slice();
        if (maxFragmentBytes <= 0 || message.remaining() <= maxFragmentBytes) {
            return sendBinary(message, true);
        }
        return sendBinaryFragments(message, maxFragmentBytes);
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        await(sendPingFrame(applicationData.slice()), 0);
    }

    @Override
    public void close() throws IOException {
        close(new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "Normal closure"));
    }

    @Override
    public void close(WebsocketCloseReason closeReason) throws IOException {
        await(initiateClose(closeReason), 0);
    }

    @Override
    public CompletableFuture<Void> closeAsync(WebsocketCloseReason closeReason) {
        try {
            initiateClose(closeReason);
            return closeHandshakeFuture;
        } catch (Throwable e) {
            closeHandshakeFuture.completeExceptionally(e);
            return closeHandshakeFuture;
        }
    }

    @Override
    public void abort(WebsocketCloseReason closeReason) {
        Runnable deferredClose = closeRuntimeDataDispatcher();
        WebSocket webSocket = this.webSocket;
        if (webSocket != null) {
            webSocket.abort();
        }
        closeHandshakeFuture.completeExceptionally(new ClosedChannelException());
        try {
            notifyClose(closeReason);
        } finally {
            runDeferredClose(deferredClose);
        }
    }

    void abortConnecting() {
        Runnable deferredClose = closeRuntimeDataDispatcher();
        closeNotified.set(true);
        open.set(false);
        WebSocket webSocket = this.webSocket;
        if (webSocket != null) {
            webSocket.abort();
        }
        connector.removeOpenSession(this);
        openFuture.completeExceptionally(new ClosedChannelException());
        closeHandshakeFuture.completeExceptionally(new ClosedChannelException());
        runDeferredClose(deferredClose);
    }

    private CompletableFuture<Void> initiateClose(WebsocketCloseReason closeReason) {
        CompletableFuture<Void> result;
        boolean notifyEndpoint;
        Runnable deferredClose;
        WebSocket currentWebSocket;
        synchronized (closeInitiationLock) {
            if (closeSendFuture != null) {
                return closeSendFuture;
            }
            if (closeHandshakeFuture.isDone()) {
                closeSendFuture = CompletableFuture.completedFuture(null);
                return closeSendFuture;
            }
            deferredClose = closeRuntimeDataDispatcher();
            open.set(false);
            connector.removeOpenSession(this);
            currentWebSocket = webSocket;
            result = currentWebSocket == null || currentWebSocket.isOutputClosed()
                    ? CompletableFuture.completedFuture(null)
                    : sendClose(currentWebSocket, closeReason).thenApply(ignored -> null);
            closeSendFuture = result;
            notifyEndpoint = closeNotified.compareAndSet(false, true);
        }
        result.whenComplete((ignored, error) -> {
            if (error != null) {
                closeHandshakeFuture.completeExceptionally(error);
            } else if (currentWebSocket == null || currentWebSocket.isInputClosed()) {
                closeHandshakeFuture.complete(null);
            }
        });
        if (notifyEndpoint) {
            endpoint.onClose(this, closeReason);
        } else {
            runDeferredClose(deferredClose);
        }
        return result;
    }

    private CompletableFuture<WebSocket> sendClose(WebSocket webSocket, WebsocketCloseReason closeReason) {
        return sendFrame(() -> webSocket.sendClose(closeReason.code(), closeReason.reason()))
                .thenApply(ignored -> webSocket);
    }

    private CompletableFuture<Void> sendBinary(ByteBuffer data, boolean last) {
        return sendFrame(() -> requireWebSocket().sendBinary(data, last));
    }

    private CompletableFuture<Void> sendBinaryFragments(ByteBuffer data, int maxFragmentBytes) {
        synchronized (sendInitiationLock) {
            CompletableFuture<Void> result = sendTail.handle((ignored, error) -> null);
            ByteBuffer remaining = data.slice();
            while (remaining.hasRemaining()) {
                ByteBuffer fragment = nextFragment(remaining, maxFragmentBytes);
                boolean last = !remaining.hasRemaining();
                result = result.thenCompose(ignored -> {
                    try {
                        return requireWebSocket().sendBinary(fragment, last).thenApply(frame -> null);
                    } catch (Throwable e) {
                        return CompletableFuture.failedFuture(e);
                    }
                });
            }
            sendTail = result;
            return result;
        }
    }

    private CompletableFuture<Void> sendPingFrame(ByteBuffer data) {
        return sendFrame(() -> requireWebSocket().sendPing(data));
    }

    private CompletableFuture<Void> sendPong(ByteBuffer data) {
        return sendFrame(() -> requireWebSocket().sendPong(data));
    }

    private CompletableFuture<Void> sendFrame(Supplier<CompletableFuture<?>> sender) {
        synchronized (sendInitiationLock) {
            CompletableFuture<Void> result = sendTail.handle((ignored, error) -> null)
                    .thenCompose(ignored -> {
                        try {
                            return sender.get().thenApply(frame -> null);
                        } catch (Throwable e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
            sendTail = result;
            return result;
        }
    }

    private static ByteBuffer nextFragment(ByteBuffer source, int maxFragmentBytes) {
        int length = Math.min(source.remaining(), maxFragmentBytes);
        ByteBuffer fragment = source.slice();
        fragment.limit(length);
        source.position(source.position() + length);
        return fragment;
    }

    private WebSocket requireWebSocket() {
        return ofNullable(webSocket).orElseThrow(() ->
                new IllegalStateException("Websocket connection to " + requestUri + " has not opened yet"));
    }

    private void notifyOpen(WebSocket webSocket) {
        if (closeNotified.get()) {
            webSocket.abort();
            return;
        }
        this.webSocket = webSocket;
        open.set(true);
        connector.addOpenSession(this);
        try {
            endpoint.onOpen(this);
            openFuture.complete(null);
            requestNext(webSocket);
        } catch (Throwable e) {
            open.set(false);
            openFuture.completeExceptionally(e);
            connector.removeOpenSession(this);
            try {
                endpoint.onError(this, e);
            } catch (Throwable ignored) {
            }
            webSocket.abort();
        }
    }

    private void notifyClose(WebsocketCloseReason closeReason) {
        Runnable deferredClose = closeRuntimeDataDispatcher();
        open.set(false);
        closeHandshakeFuture.complete(null);
        if (closeNotified.compareAndSet(false, true)) {
            connector.removeOpenSession(this);
            endpoint.onClose(this, closeReason);
        } else {
            runDeferredClose(deferredClose);
        }
    }

    private void notifyPeerClose(WebsocketCloseReason closeReason) {
        open.set(false);
        closeHandshakeFuture.complete(null);
        if (closeNotified.compareAndSet(false, true)) {
            connector.removeOpenSession(this);
            Runnable closeCallback = () -> endpoint.onClose(this, closeReason);
            if (runtimeDataDispatcher == null) {
                closeCallback.run();
            } else {
                discardIncompleteRuntimeMessage();
                runtimeDataDispatcher.closeAfterDrain(closeCallback);
            }
        }
    }

    private void notifyError(Throwable error) {
        open.set(false);
        try {
            endpoint.onError(this, error);
        } finally {
            notifyClose(new WebsocketCloseReason(
                    WebsocketCloseReason.UNEXPECTED_CONDITION,
                    ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName())));
        }
    }

    private void requestNext(WebSocket webSocket) {
        if (closeNotified.get() || runtimeDataStopping.get() || receiveState.get() != 0) {
            return;
        }
        if (!runtimeIngressHasDemandCapacity()) {
            setRuntimeIngressBackpressured(true);
            return;
        }
        if (receiveState.compareAndSet(0, RECEIVE_DEMAND_OUTSTANDING)) {
            try {
                setRuntimeIngressBackpressured(false);
                webSocket.request(1);
            } catch (Throwable e) {
                receiveState.compareAndSet(RECEIVE_DEMAND_OUTSTANDING, 0);
                throw e;
            }
        }
    }

    private boolean runtimeIngressHasDemandCapacity() {
        if (runtimeDataDispatcher == null) {
            return true;
        }
        synchronized (binaryMessageLock) {
            return binaryMessageFragmented || runtimeDataDispatcher.canBeginMessage();
        }
    }

    private void receiveInvocationStarted() {
        while (true) {
            int current = receiveState.get();
            int activeInvocations = current & ACTIVE_RECEIVE_INVOCATIONS_MASK;
            if (activeInvocations == ACTIVE_RECEIVE_INVOCATIONS_MASK) {
                throw new IllegalStateException("WebSocket receive invocation accounting overflow");
            }
            if (receiveState.compareAndSet(current, activeInvocations + 1)) {
                return;
            }
        }
    }

    private void receiveInvocationCompleted(WebSocket webSocket) {
        int activeInvocations;
        while (true) {
            int current = receiveState.get();
            activeInvocations = current & ACTIVE_RECEIVE_INVOCATIONS_MASK;
            if (activeInvocations == 0) {
                throw new IllegalStateException("WebSocket receive invocation accounting underflow");
            }
            activeInvocations--;
            if (receiveState.compareAndSet(current, activeInvocations)) {
                break;
            }
        }
        if (activeInvocations == 0) {
            requestNext(webSocket);
        }
    }

    private void resumeRuntimeIngress() {
        try {
            if (processDeferredBinaryFrame()) {
                return;
            }
            WebSocket webSocket = this.webSocket;
            if (webSocket != null) {
                requestNext(webSocket);
            }
        } catch (Throwable failure) {
            failRuntimeDataDispatch(failure instanceof CompletionException && failure.getCause() != null
                                            ? failure.getCause() : failure);
        }
    }

    private void handleRuntimeIngressProgress(
            RuntimeIngressController.Progress progress, int retainedMessages, long sequence) {
        if (endpoint instanceof SdkRuntimeWebsocketEndpoint runtimeEndpoint) {
            runtimeEndpoint.onRuntimeIngressProgress(this, progress, retainedMessages, sequence);
        }
    }

    private void setRuntimeIngressBackpressured(boolean backpressured) {
        if (runtimeIngressBackpressured.compareAndSet(!backpressured, backpressured)
            && endpoint instanceof SdkRuntimeWebsocketEndpoint runtimeEndpoint) {
            runtimeEndpoint.onRuntimeIngressBackpressure(
                    this, backpressured, trackInboundActivity ? runtimeDataDispatcher.state() : null);
        }
    }

    private CompletionStage<Void> handleRuntimeBinary(
            ByteBuffer message, boolean last, WebsocketEndpoint.ReceiveTiming receiveTiming) {
        byte[] bytes;
        CompletableFuture<Void> deferredCompletion = null;
        RuntimeIngressController.Admission status;
        synchronized (binaryMessageLock) {
            if (deferredBinaryFrame != null) {
                throw new IllegalStateException("A runtime WebSocket frame is already deferred");
            }
            status = binaryMessageFragmented
                    ? runtimeDataDispatcher.retainMessageFragmentBytes(WEBSOCKET_ASSEMBLY_KEY, message.remaining())
                    : runtimeDataDispatcher.beginMessage(WEBSOCKET_ASSEMBLY_KEY, message.remaining());
            if (status == RuntimeIngressController.Admission.BACKPRESSURED) {
                deferredCompletion = new CompletableFuture<>();
                deferredBinaryFrame = new DeferredBinaryFrame(
                        message.slice(), last, receiveTiming, deferredCompletion);
            }
            bytes = status == RuntimeIngressController.Admission.ACCEPTED ? appendBinaryLocked(message, last) : null;
        }
        if (status == RuntimeIngressController.Admission.BACKPRESSURED) {
            setRuntimeIngressBackpressured(true);
            return deferredCompletion;
        }
        if (status == RuntimeIngressController.Admission.OVERFLOW) {
            failRuntimeDataDispatch(RuntimeDataDispatchException.overflow(
                    runtimeDataState(runtimeDataDispatcher.state())));
            return CompletableFuture.completedFuture(null);
        }
        if (status != RuntimeIngressController.Admission.ACCEPTED) {
            return CompletableFuture.completedFuture(null);
        }
        if (bytes != null) {
            runtimeDataDispatcher.dispatchAssembledMessage(WEBSOCKET_ASSEMBLY_KEY, bytes, receiveTiming);
            if (!runtimeDataDispatcher.canBeginMessage()) {
                setRuntimeIngressBackpressured(true);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private boolean processDeferredBinaryFrame() {
        DeferredBinaryFrame deferred;
        byte[] bytes = null;
        RuntimeIngressController.Admission status;
        synchronized (binaryMessageLock) {
            deferred = deferredBinaryFrame;
            if (deferred == null) {
                return false;
            }
            status = binaryMessageFragmented
                    ? runtimeDataDispatcher.retainMessageFragmentBytes(
                            WEBSOCKET_ASSEMBLY_KEY, deferred.data.remaining())
                    : runtimeDataDispatcher.beginMessage(WEBSOCKET_ASSEMBLY_KEY, deferred.data.remaining());
            if (status == RuntimeIngressController.Admission.BACKPRESSURED) {
                return true;
            }
            deferredBinaryFrame = null;
            if (status == RuntimeIngressController.Admission.ACCEPTED) {
                bytes = appendBinaryLocked(deferred.data, deferred.last);
            }
        }
        if (status == RuntimeIngressController.Admission.ACCEPTED && bytes != null) {
            runtimeDataDispatcher.dispatchAssembledMessage(
                    WEBSOCKET_ASSEMBLY_KEY, bytes, deferred.receiveTiming);
        } else if (status == RuntimeIngressController.Admission.OVERFLOW) {
            failRuntimeDataDispatch(RuntimeDataDispatchException.overflow(
                    runtimeDataState(runtimeDataDispatcher.state())));
        }
        if (status == RuntimeIngressController.Admission.ACCEPTED) {
            deferred.completion.complete(null);
        } else {
            deferred.completion.completeExceptionally(new ClosedChannelException());
        }
        return true;
    }

    private RuntimeIngressController.MessageDispatch dispatchBinaryMessage(
            byte[] bytes, WebsocketEndpoint.ReceiveTiming receiveTiming,
            RuntimeIngressController.DispatchTiming ingressDispatchTiming) {
        SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming runtimeDispatchTiming = ingressDispatchTiming == null
                ? null : new SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming(
                        ingressDispatchTiming.queuedTimestamp(), ingressDispatchTiming.startedTimestamp(),
                        ingressDispatchTiming.queueDurationMillis());
        if (endpoint instanceof SdkRuntimeWebsocketEndpoint runtimeEndpoint) {
            return runtimeEndpoint.onRuntimeMessage(bytes, this, receiveTiming, runtimeDispatchTiming);
        } else if (receiveTiming == null) {
            endpoint.onMessage(bytes, this);
        } else {
            endpoint.onMessage(bytes, this, receiveTiming);
        }
        return RuntimeIngressController.MessageDispatch.admitted(CompletableFuture.completedFuture(null));
    }

    private void failRuntimeDataDispatch(Throwable error) {
        if (!runtimeDataFailureNotified.compareAndSet(false, true)) {
            return;
        }
        Runnable deferredClose = closeRuntimeDataDispatcher();
        WebSocket webSocket = this.webSocket;
        try {
            notifyError(error);
        } finally {
            try {
                runDeferredClose(deferredClose);
            } finally {
                if (webSocket != null) {
                    webSocket.abort();
                }
            }
        }
    }

    private void handleRuntimeIngressFailure(Throwable failure) {
        if (failure instanceof RuntimeIngressController.IngressException ingressException) {
            RuntimeDataState state = runtimeDataState(ingressException.state());
            failRuntimeDataDispatch(ingressException.reason()
                                            == RuntimeIngressController.IngressException.Reason.EXECUTOR_REJECTED
                                            ? RuntimeDataDispatchException.executorRejected(state, failure.getCause())
                                            : RuntimeDataDispatchException.overflow(state));
            return;
        }
        failRuntimeDataDispatch(failure);
    }

    private Runnable closeRuntimeDataDispatcher() {
        if (runtimeDataDispatcher == null) {
            return null;
        }
        runtimeDataStopping.set(true);
        synchronized (binaryMessageLock) {
            binaryMessage = new ByteArrayOutputStream();
            binaryMessageFragmented = false;
            if (deferredBinaryFrame != null) {
                deferredBinaryFrame.completion.completeExceptionally(new ClosedChannelException());
                deferredBinaryFrame = null;
            }
            return runtimeDataDispatcher.close();
        }
    }

    private static void runDeferredClose(Runnable deferredClose) {
        if (deferredClose != null) {
            deferredClose.run();
        }
    }

    RuntimeDataState runtimeDataState() {
        long deferredFrameBytes;
        synchronized (binaryMessageLock) {
            deferredFrameBytes = deferredBinaryFrame == null ? 0L : deferredBinaryFrame.data.remaining();
        }
        RuntimeDataState runtimeState = runtimeDataDispatcher == null
                ? RuntimeDataState.empty() : runtimeDataState(runtimeDataDispatcher.state());
        long inboundNanos = lastInboundNanos;
        long inboundAgeMillis = inboundNanos == 0L ? 0L
                : NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - inboundNanos));
        return runtimeState.withTransportState(deferredFrameBytes, inboundAgeMillis);
    }

    static RuntimeDataState runtimeDataState(RuntimeIngressController.State state) {
        return new RuntimeDataState(
                state.retainedMessages(), state.retainedBytes(), state.inFlightMessages(), state.inFlightBytes(),
                state.activeMessages(), state.activeBytes(), state.admittedMessages(), state.admittedBytes(),
                state.pendingMessages(), state.pendingBytes(), state.maxConcurrency(), state.maxRetainedMessages(),
                state.maxRetainedBytes(),
                0L, 0L);
    }

    String runtimeDataWorkerMode() {
        return runtimeDataWorkerMode;
    }

    private void handlePong(ByteBuffer message) {
        endpoint.onPong(copyBuffer(message), this);
    }

    private void discardIncompleteRuntimeMessage() {
        synchronized (binaryMessageLock) {
            binaryMessage = new ByteArrayOutputStream();
            binaryMessageFragmented = false;
            if (deferredBinaryFrame != null) {
                deferredBinaryFrame.completion.completeExceptionally(new ClosedChannelException());
                deferredBinaryFrame = null;
            }
            runtimeDataDispatcher.discardAssembly(WEBSOCKET_ASSEMBLY_KEY);
        }
    }

    private byte[] appendBinary(ByteBuffer message, boolean last) {
        synchronized (binaryMessageLock) {
            return appendBinaryLocked(message, last);
        }
    }

    private byte[] appendBinaryLocked(ByteBuffer message, boolean last) {
        if (last && !binaryMessageFragmented) {
            return copyBytes(message);
        }
        byte[] fragment = copyBytes(message);
        binaryMessageFragmented = true;
        try {
            binaryMessage.write(fragment);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to buffer websocket binary message", e);
        }
        if (!last) {
            return null;
        }
        byte[] bytes = binaryMessage.toByteArray();
        binaryMessage = new ByteArrayOutputStream();
        binaryMessageFragmented = false;
        return bytes;
    }

    private static ByteBuffer copyBuffer(ByteBuffer buffer) {
        return ByteBuffer.wrap(copyBytes(buffer));
    }

    private static byte[] copyBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private record DeferredBinaryFrame(ByteBuffer data, boolean last,
                                       WebsocketEndpoint.ReceiveTiming receiveTiming,
                                       CompletableFuture<Void> completion) {
    }

    private static void await(CompletableFuture<?> future, long timeoutMillis) throws IOException {
        try {
            if (timeoutMillis > 0) {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending websocket frame", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to send websocket frame", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Timed out while sending websocket frame", e);
        }
    }

    private CompletableFuture<Void> dispatchCallback(Runnable task) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            callbackExecutor.execute(() -> {
                try {
                    task.run();
                    result.complete(null);
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
            notifyError(e);
        }
        return result;
    }

    private CompletableFuture<Void> dispatchCallback(LongConsumer task) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            callbackExecutor.execute(() -> {
                try {
                    task.accept(System.currentTimeMillis());
                    result.complete(null);
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private CompletableFuture<Void> dispatchReceiveCallback(WebSocket webSocket, Runnable task) {
        return dispatchReceiveCallback(webSocket, ignored -> task.run());
    }

    private CompletableFuture<Void> dispatchReceiveCallback(WebSocket webSocket, LongConsumer task) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            callbackExecutor.execute(() -> {
                try {
                    task.accept(System.currentTimeMillis());
                    completeReceiveSuccess(webSocket, result);
                } catch (Throwable e) {
                    completeReceiveFailure(webSocket, result, e);
                }
            });
        } catch (RejectedExecutionException e) {
            completeReceiveFailure(webSocket, result, e);
        }
        return result;
    }

    private CompletableFuture<Void> dispatchReceiveCallbackStage(
            WebSocket webSocket, Supplier<CompletionStage<Void>> task) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            callbackExecutor.execute(() -> {
                try {
                    CompletableFuture<Void> completion = Objects.requireNonNull(
                            task.get(), "WebSocket receive completion").toCompletableFuture();
                    if (completion.isDone()) {
                        completion.join();
                        completeReceiveSuccess(webSocket, result);
                    } else {
                        completion.whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                completeReceiveSuccess(webSocket, result);
                            } else {
                                completeReceiveFailure(webSocket, result, failure);
                            }
                        });
                    }
                } catch (Throwable e) {
                    completeReceiveFailure(webSocket, result, e);
                }
            });
        } catch (RejectedExecutionException e) {
            completeReceiveFailure(webSocket, result, e);
        }
        return result;
    }

    private void completeReceiveSuccess(WebSocket webSocket, CompletableFuture<Void> completion) {
        try {
            receiveInvocationCompleted(webSocket);
            completion.complete(null);
        } catch (Throwable failure) {
            completion.completeExceptionally(reportReceiveError(failure));
        }
    }

    private void completeReceiveFailure(
            WebSocket webSocket, CompletableFuture<Void> completion, Throwable failure) {
        Throwable reportedFailure = reportReceiveError(failure);
        try {
            receiveInvocationCompleted(webSocket);
        } catch (Throwable receiveCompletionFailure) {
            if (receiveCompletionFailure != reportedFailure) {
                reportedFailure.addSuppressed(receiveCompletionFailure);
            }
        } finally {
            completion.completeExceptionally(reportedFailure);
        }
    }

    private CompletableFuture<Void> completeDirectReceive(WebSocket webSocket) {
        try {
            receiveInvocationCompleted(webSocket);
            return COMPLETED_RECEIVE;
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(reportReceiveError(failure));
        }
    }

    private Throwable reportReceiveError(Throwable failure) {
        Throwable unwrapped = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        try {
            if (!closeNotified.get()
                && !(runtimeDataStopping.get() && unwrapped instanceof ClosedChannelException)) {
                notifyError(unwrapped);
            }
        } catch (Throwable notificationFailure) {
            if (notificationFailure != unwrapped) {
                unwrapped.addSuppressed(notificationFailure);
            }
        }
        return unwrapped;
    }

    private void handleOpenDispatchFailure(WebSocket webSocket, Throwable error) {
        open.set(false);
        openFuture.completeExceptionally(error);
        connector.removeOpenSession(this);
        try {
            endpoint.onError(this, error);
        } catch (Throwable ignored) {
        }
        webSocket.abort();
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            try {
                notifyOpen(webSocket);
            } catch (Throwable e) {
                handleOpenDispatchFailure(webSocket, e);
            }
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            receiveInvocationStarted();
            if (runtimeDataDispatcher != null) {
                boolean captureReceiveTiming = endpoint.captureReceiveTiming();
                long frameReceivedTimestamp = captureReceiveTiming ? System.currentTimeMillis() : 0L;
                long frameDispatchQueuedTimestamp = captureReceiveTiming ? System.currentTimeMillis() : 0L;
                return dispatchReceiveCallbackStage(webSocket, () -> handleRuntimeBinary(
                        data, last, captureReceiveTiming
                                ? new WebsocketEndpoint.ReceiveTiming(
                                        frameReceivedTimestamp, frameDispatchQueuedTimestamp,
                                        System.currentTimeMillis()) : null));
            }
            if (endpoint.captureReceiveTiming()) {
                long frameReceivedTimestamp = System.currentTimeMillis();
                long frameDispatchQueuedTimestamp = System.currentTimeMillis();
                return dispatchReceiveCallback(webSocket, frameDispatchStartedTimestamp -> {
                    try {
                        byte[] bytes = appendBinary(data, last);
                        if (bytes != null) {
                            dispatchBinaryMessage(bytes, new WebsocketEndpoint.ReceiveTiming(
                                    frameReceivedTimestamp, frameDispatchQueuedTimestamp,
                                    frameDispatchStartedTimestamp), null);
                        }
                    } catch (Throwable e) {
                        notifyError(e);
                    }
                });
            }
            return dispatchReceiveCallback(webSocket, () -> {
                try {
                    byte[] bytes = appendBinary(data, last);
                    if (bytes != null) {
                        dispatchBinaryMessage(bytes, null, null);
                    }
                } catch (Throwable e) {
                    notifyError(e);
                }
            });
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket webSocket, ByteBuffer message) {
            receiveInvocationStarted();
            return dispatchReceiveCallback(webSocket, () ->
                    sendPong(copyBuffer(message)).exceptionally(e -> {
                        notifyError(e);
                        return null;
                    }));
        }

        @Override
        public CompletableFuture<?> onPong(WebSocket webSocket, ByteBuffer message) {
            receiveInvocationStarted();
            if (runtimeDataDispatcher != null && endpoint instanceof SdkRuntimeWebsocketEndpoint) {
                try {
                    handlePong(message);
                } catch (Throwable e) {
                    notifyError(e);
                }
                return completeDirectReceive(webSocket);
            }
            return dispatchReceiveCallback(webSocket, () -> {
                try {
                    handlePong(message);
                } catch (Throwable e) {
                    notifyError(e);
                }
            });
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            receiveInvocationStarted();
            return dispatchReceiveCallback(
                    webSocket, () -> notifyPeerClose(new WebsocketCloseReason(statusCode, reason)));
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (runtimeDataDispatcher != null && endpoint instanceof SdkRuntimeWebsocketEndpoint) {
                notifyError(error);
            } else {
                dispatchCallback(() -> notifyError(error));
            }
        }
    }

    private class ActivityTrackingListener extends Listener {
        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            lastInboundNanos = System.nanoTime();
            return super.onBinary(webSocket, data, last);
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastInboundNanos = System.nanoTime();
            return super.onPing(webSocket, message);
        }

        @Override
        public CompletableFuture<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastInboundNanos = System.nanoTime();
            return super.onPong(webSocket, message);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            lastInboundNanos = System.nanoTime();
            return super.onClose(webSocket, statusCode, reason);
        }
    }

    record RuntimeDataState(int retainedMessages, long retainedBytes, int inFlightMessages, long inFlightBytes,
                            int activeMessages, long activeBytes, int admittedMessages, long admittedBytes,
                            int pendingMessages, long pendingBytes, int maxConcurrency, int maxRetainedMessages,
                            long maxRetainedBytes,
                            long deferredFrameBytes, long lastInboundAgeMillis) {
        static RuntimeDataState empty() {
            return new RuntimeDataState(
                    0, 0L, 0, 0L, 0, 0L, 0, 0L, 0, 0L, DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                    DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES, DEFAULT_MAX_RETAINED_RUNTIME_BYTES, 0L, 0L);
        }

        RuntimeDataState withTransportState(long deferredFrameBytes, long lastInboundAgeMillis) {
            return new RuntimeDataState(
                    retainedMessages, retainedBytes, inFlightMessages, inFlightBytes, activeMessages, activeBytes,
                    admittedMessages, admittedBytes, pendingMessages, pendingBytes, maxConcurrency,
                    maxRetainedMessages, maxRetainedBytes, deferredFrameBytes, lastInboundAgeMillis);
        }

        RuntimeDataState withLastInboundAgeMillis(long lastInboundAgeMillis) {
            return withTransportState(deferredFrameBytes, lastInboundAgeMillis);
        }
    }

    static final class RuntimeDataDispatchException extends RejectedExecutionException {
        private final Reason reason;
        private final RuntimeDataState state;

        private RuntimeDataDispatchException(Reason reason, RuntimeDataState state, Throwable cause) {
            super(reason == Reason.OVERFLOW
                          ? "SDK runtime websocket ingress accounting overflowed; the session cannot continue safely"
                          : "SDK runtime websocket data executor rejected dispatch");
            this.reason = reason;
            this.state = state;
            if (cause != null) {
                initCause(cause);
            }
        }

        static RuntimeDataDispatchException overflow(RuntimeDataState state) {
            return new RuntimeDataDispatchException(Reason.OVERFLOW, state, null);
        }

        static RuntimeDataDispatchException executorRejected(RuntimeDataState state, Throwable cause) {
            return new RuntimeDataDispatchException(Reason.EXECUTOR_REJECTED, state, cause);
        }

        Reason reason() {
            return reason;
        }

        RuntimeDataState state() {
            return state;
        }

        enum Reason {
            OVERFLOW, EXECUTOR_REJECTED
        }
    }
}
