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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

class JdkWebSocketSession implements WebsocketSession {
    static final String SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkRuntimeDataDispatch";
    static final String SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY =
            JdkWebSocketSession.class.getName() + ".sdkTransportMetricsEnabled";
    static final int MAX_CONCURRENT_RUNTIME_MESSAGES = 3;
    // The production dispatcher retains these in addition to its three submitted messages.
    static final int MAX_PENDING_RUNTIME_MESSAGES = 16;
    static final int MAX_RETAINED_RUNTIME_MESSAGES =
            MAX_CONCURRENT_RUNTIME_MESSAGES + MAX_PENDING_RUNTIME_MESSAGES;
    static final long MAX_RETAINED_RUNTIME_BYTES = 16L * 1024 * 1024;

    private final JdkWebsocketConnector connector;
    private final WebsocketEndpoint endpoint;
    private final JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse;
    private final Executor callbackExecutor;
    private final RuntimeDataDispatcher runtimeDataDispatcher;
    private final String runtimeDataWorkerMode;
    private final boolean trackInboundActivity;
    private final URI requestUri;
    private final Map<String, Object> userProperties = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> openFuture = new CompletableFuture<>();
    private final AtomicBoolean open = new AtomicBoolean();
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
             MAX_CONCURRENT_RUNTIME_MESSAGES);
    }

    JdkWebSocketSession(JdkWebsocketConnector connector, WebsocketEndpoint endpoint,
                        WebsocketConnectionOptions options, URI requestUri,
                        JdkWebsocketConnector.CapturedHandshakeResponse handshakeResponse,
                        Executor callbackExecutor, Executor runtimeDataExecutor, int maxConcurrentRuntimeMessages) {
        this.connector = connector;
        this.endpoint = endpoint;
        this.handshakeResponse = handshakeResponse;
        this.callbackExecutor = callbackExecutor;
        this.requestUri = requestUri;
        this.userProperties.putAll(options.userProperties());
        this.runtimeDataWorkerMode = JdkWebsocketConnector.runtimeDataWorkerMode(
                callbackExecutor, runtimeDataExecutor);
        this.runtimeDataDispatcher = Boolean.TRUE.equals(
                options.userProperties().get(SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY))
                ? new RuntimeDataDispatcher(runtimeDataExecutor, maxConcurrentRuntimeMessages) : null;
        this.trackInboundActivity = Boolean.TRUE.equals(
                options.userProperties().get(SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY));
        this.lastInboundNanos = trackInboundActivity ? System.nanoTime() : 0L;
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
            webSocket.request(1);
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
        if (!closeNotified.get()) {
            webSocket.request(1);
        }
    }

    private boolean handleBinary(ByteBuffer message, boolean last) {
        return handleBinary(message, last, null);
    }

    private boolean handleBinary(ByteBuffer message, boolean last, WebsocketEndpoint.ReceiveTiming receiveTiming) {
        if (runtimeDataDispatcher == null) {
            byte[] bytes = appendBinary(message, last);
            if (bytes == null) {
                return true;
            }
            dispatchBinaryMessage(bytes, receiveTiming, null);
            return true;
        }

        byte[] bytes;
        RuntimeDataDispatcher.DispatchStatus status = RuntimeDataDispatcher.DispatchStatus.ACCEPTED;
        synchronized (binaryMessageLock) {
            status = binaryMessageFragmented
                    ? runtimeDataDispatcher.retainMessageFragmentBytes(message.remaining())
                    : runtimeDataDispatcher.beginMessage(message.remaining());
            bytes = status == RuntimeDataDispatcher.DispatchStatus.ACCEPTED
                    ? appendBinaryLocked(message, last) : null;
        }
        if (status == RuntimeDataDispatcher.DispatchStatus.OVERFLOW) {
            failRuntimeDataDispatch(RuntimeDataDispatchException.overflow(runtimeDataDispatcher.state()));
            return false;
        }
        if (status == RuntimeDataDispatcher.DispatchStatus.CLOSED) {
            return false;
        }
        if (bytes == null) {
            return true;
        }
        status = runtimeDataDispatcher.dispatchAssembledMessage(bytes, receiveTiming);
        return status == RuntimeDataDispatcher.DispatchStatus.ACCEPTED;
    }

    private void dispatchBinaryMessage(byte[] bytes, WebsocketEndpoint.ReceiveTiming receiveTiming,
                                       SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming runtimeDispatchTiming) {
        if (runtimeDispatchTiming != null && endpoint instanceof SdkRuntimeWebsocketEndpoint runtimeEndpoint) {
            runtimeEndpoint.onRuntimeMessage(bytes, this, receiveTiming, runtimeDispatchTiming);
        } else if (receiveTiming == null) {
            endpoint.onMessage(bytes, this);
        } else {
            endpoint.onMessage(bytes, this, receiveTiming);
        }
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

    private Runnable closeRuntimeDataDispatcher() {
        if (runtimeDataDispatcher == null) {
            return null;
        }
        synchronized (binaryMessageLock) {
            binaryMessage = new ByteArrayOutputStream();
            binaryMessageFragmented = false;
            return runtimeDataDispatcher.close();
        }
    }

    private static void runDeferredClose(Runnable deferredClose) {
        if (deferredClose != null) {
            deferredClose.run();
        }
    }

    RuntimeDataState runtimeDataState() {
        RuntimeDataState state = runtimeDataDispatcher == null
                ? RuntimeDataState.empty() : runtimeDataDispatcher.state();
        long inboundNanos = lastInboundNanos;
        long inboundAgeMillis = inboundNanos == 0L ? 0L
                : NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - inboundNanos));
        return state.withLastInboundAgeMillis(inboundAgeMillis);
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
            runtimeDataDispatcher.discardMessageAssembly();
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

    private class RuntimeDataDispatcher {
        private final Executor executor;
        private final int maxConcurrency;
        private final ArrayDeque<Object> pendingMessages = new ArrayDeque<>();
        private final ArrayDeque<RuntimeTask> availableTasks = new ArrayDeque<>();
        private int createdTaskCount;
        private long retainedBytes;
        private int retainedMessages;
        private int inFlightMessages;
        private long inFlightBytes;
        private int activeMessages;
        private long activeBytes;
        private boolean messageAssemblyRetained;
        private long messageAssemblyBytes;
        private boolean accepting = true;
        private boolean discardPending;
        private boolean stopping;
        private Runnable terminalCallback;

        private RuntimeDataDispatcher(Executor executor, int maxConcurrency) {
            if (maxConcurrency < 1 || maxConcurrency > MAX_CONCURRENT_RUNTIME_MESSAGES) {
                throw new IllegalArgumentException(
                        "Runtime message concurrency must be between 1 and " + MAX_CONCURRENT_RUNTIME_MESSAGES);
            }
            this.executor = executor;
            this.maxConcurrency = maxConcurrency;
        }

        synchronized DispatchStatus beginMessage(int firstFrameBytes) {
            if (!accepting) {
                return DispatchStatus.CLOSED;
            }
            if (messageAssemblyRetained) {
                throw new IllegalStateException("A runtime message is already being assembled");
            }
            if (!hasCapacity(firstFrameBytes)) {
                return DispatchStatus.OVERFLOW;
            }
            messageAssemblyRetained = true;
            messageAssemblyBytes = firstFrameBytes;
            retainedMessages++;
            retainedBytes += firstFrameBytes;
            return DispatchStatus.ACCEPTED;
        }

        synchronized DispatchStatus retainMessageFragmentBytes(int nextFragmentBytes) {
            if (!accepting) {
                return DispatchStatus.CLOSED;
            }
            if (!messageAssemblyRetained) {
                throw new IllegalStateException("No runtime message is being assembled");
            }
            if (retainedMessages > 1 && retainedBytes + nextFragmentBytes > MAX_RETAINED_RUNTIME_BYTES) {
                return DispatchStatus.OVERFLOW;
            }
            messageAssemblyBytes += nextFragmentBytes;
            retainedBytes += nextFragmentBytes;
            return DispatchStatus.ACCEPTED;
        }

        DispatchStatus dispatchAssembledMessage(byte[] bytes, WebsocketEndpoint.ReceiveTiming receiveTiming) {
            Object message = receiveTiming == null ? bytes : new RuntimeMessage(
                    bytes, receiveTiming, System.currentTimeMillis(), System.nanoTime());
            synchronized (this) {
                if (!accepting || !messageAssemblyRetained) {
                    return DispatchStatus.CLOSED;
                }
                if (messageAssemblyBytes != bytes.length) {
                    throw new IllegalStateException("Retained bytes do not match the assembled runtime message");
                }
                messageAssemblyRetained = false;
                messageAssemblyBytes = 0L;
                pendingMessages.add(message);
            }
            scheduleAvailable();
            return DispatchStatus.ACCEPTED;
        }

        private boolean hasCapacity(int nextMessageBytes) {
            return retainedMessages < MAX_RETAINED_RUNTIME_MESSAGES
                    && (retainedMessages == 0
                    || retainedBytes + nextMessageBytes <= MAX_RETAINED_RUNTIME_BYTES);
        }

        private void scheduleAvailable() {
            Object message;
            RuntimeTask task;
            synchronized (this) {
                if (discardPending || stopping || inFlightMessages >= maxConcurrency) {
                    return;
                }
                message = pendingMessages.poll();
                if (message == null) {
                    return;
                }
                inFlightMessages++;
                inFlightBytes += messageBytes(message).length;
                task = availableTasks.poll();
                if (task == null) {
                    if (createdTaskCount >= maxConcurrency) {
                        throw new IllegalStateException("Missing reusable runtime dispatch task");
                    }
                    task = new RuntimeTask();
                    createdTaskCount++;
                }
                task.message = message;
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                RuntimeDataState rejectedState = discardRejected(task, message);
                failRuntimeDataDispatch(RuntimeDataDispatchException.executorRejected(rejectedState, e));
            }
        }

        private void process(RuntimeTask task, Object message) {
            Throwable failure = null;
            boolean active = markActive(message);
            if (active) {
                try {
                    RuntimeMessage timedMessage = message instanceof RuntimeMessage runtimeMessage
                            ? runtimeMessage : null;
                    SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming runtimeDispatchTiming = null;
                    if (timedMessage != null) {
                        long startedNanos = System.nanoTime();
                        runtimeDispatchTiming = new SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming(
                                timedMessage.queuedTimestamp(), System.currentTimeMillis(),
                                NANOSECONDS.toMillis(Math.max(0L, startedNanos - timedMessage.queuedNanos())));
                    }
                    dispatchBinaryMessage(messageBytes(message),
                                          timedMessage == null ? null : timedMessage.receiveTiming(),
                                          runtimeDispatchTiming);
                } catch (Throwable e) {
                    failure = e;
                }
            }
            complete(task, message, active, failure);
        }

        private synchronized boolean markActive(Object message) {
            if (discardPending || stopping) {
                return false;
            }
            activeMessages++;
            activeBytes += messageBytes(message).length;
            return true;
        }

        private void complete(RuntimeTask task, Object message, boolean active, Throwable failure) {
            Runnable terminal;
            boolean scheduleMore;
            synchronized (this) {
                inFlightMessages--;
                inFlightBytes -= messageBytes(message).length;
                if (active) {
                    activeMessages--;
                    activeBytes -= messageBytes(message).length;
                }
                retainedMessages--;
                retainedBytes -= messageBytes(message).length;
                task.message = null;
                availableTasks.add(task);
                if (failure != null) {
                    accepting = false;
                    stopping = true;
                }
                if (failure == null && !stopping && retainedMessages == 0 && terminalCallback != null) {
                    terminal = terminalCallback;
                    terminalCallback = null;
                    discardPending = true;
                } else {
                    terminal = null;
                }
                scheduleMore = failure == null && !discardPending && !pendingMessages.isEmpty();
            }
            if (failure != null) {
                failRuntimeDataDispatch(failure);
            } else {
                if (scheduleMore) {
                    scheduleAvailable();
                }
                if (terminal != null) {
                    terminal.run();
                }
            }
        }

        private synchronized RuntimeDataState discardRejected(RuntimeTask task, Object rejectedMessage) {
            RuntimeDataState rejectedState = state();
            accepting = false;
            stopping = true;
            inFlightMessages--;
            inFlightBytes -= messageBytes(rejectedMessage).length;
            retainedMessages--;
            retainedBytes -= messageBytes(rejectedMessage).length;
            task.message = null;
            availableTasks.add(task);
            return rejectedState;
        }

        synchronized Runnable close() {
            if (discardPending) {
                return null;
            }
            accepting = false;
            discardPending = true;
            Runnable deferredClose = terminalCallback;
            terminalCallback = null;
            discardMessageAssembly();
            Object message;
            while ((message = pendingMessages.poll()) != null) {
                retainedMessages--;
                retainedBytes -= messageBytes(message).length;
            }
            return deferredClose;
        }

        synchronized void discardMessageAssembly() {
            if (messageAssemblyRetained) {
                retainedMessages--;
                retainedBytes -= messageAssemblyBytes;
                messageAssemblyRetained = false;
                messageAssemblyBytes = 0L;
            }
        }

        private byte[] messageBytes(Object message) {
            return message instanceof byte[] bytes ? bytes : ((RuntimeMessage) message).bytes();
        }

        synchronized RuntimeDataState state() {
            int pendingMessageCount = pendingMessages.size() + (messageAssemblyRetained ? 1 : 0);
            return new RuntimeDataState(
                    retainedMessages, retainedBytes, inFlightMessages, inFlightBytes, activeMessages, activeBytes,
                    pendingMessageCount, retainedBytes - inFlightBytes, maxConcurrency,
                    MAX_RETAINED_RUNTIME_MESSAGES, MAX_RETAINED_RUNTIME_BYTES, 0L);
        }

        void closeAfterDrain(Runnable closeCallback) {
            boolean runNow;
            synchronized (this) {
                if (stopping && !discardPending) {
                    terminalCallback = closeCallback;
                    runNow = false;
                } else if (!accepting) {
                    runNow = true;
                } else {
                    accepting = false;
                    runNow = retainedMessages == 0;
                    if (runNow) {
                        discardPending = true;
                    } else {
                        terminalCallback = closeCallback;
                    }
                }
            }
            if (runNow) {
                closeCallback.run();
            }
        }

        private enum DispatchStatus {
            ACCEPTED, CLOSED, OVERFLOW
        }

        private record RuntimeMessage(byte[] bytes, WebsocketEndpoint.ReceiveTiming receiveTiming,
                                      long queuedTimestamp, long queuedNanos) {
        }

        private class RuntimeTask implements Runnable {
            private Object message;

            @Override
            public void run() {
                Object currentMessage = message;
                if (currentMessage == null) {
                    throw new IllegalStateException("Runtime dispatch task has no message");
                }
                process(this, currentMessage);
            }
        }
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
            if (endpoint.captureReceiveTiming()) {
                long frameReceivedTimestamp = System.currentTimeMillis();
                long frameDispatchQueuedTimestamp = System.currentTimeMillis();
                return dispatchCallback(frameDispatchStartedTimestamp -> {
                    try {
                        if (handleBinary(data, last, new WebsocketEndpoint.ReceiveTiming(
                                frameReceivedTimestamp, frameDispatchQueuedTimestamp, frameDispatchStartedTimestamp))) {
                            requestNext(webSocket);
                        }
                    } catch (Throwable e) {
                        notifyError(e);
                    }
                });
            }
            return dispatchCallback(() -> {
                try {
                    if (handleBinary(data, last)) {
                        requestNext(webSocket);
                    }
                } catch (Throwable e) {
                    notifyError(e);
                }
            });
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket webSocket, ByteBuffer message) {
            return dispatchCallback(() -> {
                try {
                    sendPong(copyBuffer(message)).exceptionally(e -> {
                        notifyError(e);
                        return null;
                    });
                } finally {
                    requestNext(webSocket);
                }
            });
        }

        @Override
        public CompletableFuture<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (runtimeDataDispatcher != null && endpoint instanceof SdkRuntimeWebsocketEndpoint) {
                try {
                    handlePong(message);
                } catch (Throwable e) {
                    notifyError(e);
                } finally {
                    requestNext(webSocket);
                }
                return CompletableFuture.completedFuture(null);
            }
            return dispatchCallback(() -> {
                try {
                    handlePong(message);
                } catch (Throwable e) {
                    notifyError(e);
                } finally {
                    requestNext(webSocket);
                }
            });
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return dispatchCallback(() -> notifyPeerClose(new WebsocketCloseReason(statusCode, reason)));
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            dispatchCallback(() -> notifyError(error));
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
                            int activeMessages, long activeBytes, int pendingMessages, long pendingBytes,
                            int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes,
                            long lastInboundAgeMillis) {
        static RuntimeDataState empty() {
            return new RuntimeDataState(
                    0, 0L, 0, 0L, 0, 0L, 0, 0L, MAX_CONCURRENT_RUNTIME_MESSAGES,
                    MAX_RETAINED_RUNTIME_MESSAGES, MAX_RETAINED_RUNTIME_BYTES, 0L);
        }

        RuntimeDataState withLastInboundAgeMillis(long lastInboundAgeMillis) {
            return new RuntimeDataState(
                    retainedMessages, retainedBytes, inFlightMessages, inFlightBytes, activeMessages, activeBytes,
                    pendingMessages, pendingBytes, maxConcurrency, maxRetainedMessages, maxRetainedBytes,
                    lastInboundAgeMillis);
        }
    }

    static final class RuntimeDataDispatchException extends RejectedExecutionException {
        private final Reason reason;
        private final RuntimeDataState state;

        private RuntimeDataDispatchException(Reason reason, RuntimeDataState state, Throwable cause) {
            super(reason == Reason.OVERFLOW
                          ? "SDK runtime websocket ingress exceeded its retained message or byte limit; "
                            + "reconnect is required"
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
