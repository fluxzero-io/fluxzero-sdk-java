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

import io.fluxzero.common.Backlog;
import io.fluxzero.common.DirectExecutorService;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.RetryStatus;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.SdkVersion;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.GZIP;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractWebsocketClientTest {

    @Test
    void supportedCompressionAlgorithmsDefaultToConfiguredCompressionFirst() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();

        assertEquals(GZIP, clientConfig.getSupportedCompressionAlgorithms().getFirst());
        assertEquals(Set.of(CompressionAlgorithm.values()),
                     Set.copyOf(clientConfig.getSupportedCompressionAlgorithms()));
    }

    @Test
    void connectionOptionsPublishSupportedCompressionAlgorithmsHeader() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();

        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        Map<String, List<String>> headers = connectionSetup.options().headers();

        assertEquals(clientConfig.getSupportedCompressionAlgorithms(),
                     WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
        assertEquals(connectionSetup.configurator().getClientSessionId(),
                     WebSocketCapabilities.getClientSessionId(headers).orElseThrow());
        assertEquals(SdkVersion.version().orElseThrow(),
                     WebSocketCapabilities.getClientSdkVersion(headers).orElseThrow());
        assertEquals(12, connectionSetup.configurator().getClientSessionId().length());
    }

    @Test
    void connectionOptionsCanSuppressCapabilityHeaders() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(List.of())
                .build();

        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        Map<String, List<String>> headers = connectionSetup.options().headers();

        assertEquals(List.of(), WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
        assertEquals(connectionSetup.configurator().getClientSessionId(),
                     WebSocketCapabilities.getClientSessionId(headers).orElseThrow());
    }

    @Test
    void connectionOptionsPublishConfiguredJdkConnectionTimeout() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .connectionTimeout(Duration.ofMillis(1500))
                .build();

        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);

        assertEquals(Duration.ofMillis(1500), connectionSetup.options().connectTimeout());
    }

    @Test
    void connectionOptionsCaptureNegotiatedHandshakeResponse() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        Map<String, List<String>> responseHeaders = Map.of(
                WebSocketCapabilities.RUNTIME_SESSION_ID_HEADER, List.of("srv123456789"),
                WebSocketCapabilities.RUNTIME_VERSION_HEADER, List.of("1.2.3"),
                WebSocketCapabilities.SELECTED_COMPRESSION_ALGORITHM_HEADER, List.of("LZ4"));

        connectionSetup.configurator().afterResponse(responseHeaders);

        assertEquals("srv123456789", connectionSetup.configurator().getRuntimeSessionId());
        assertEquals("1.2.3", connectionSetup.configurator().getRuntimeVersion());
        assertEquals(CompressionAlgorithm.LZ4, connectionSetup.configurator().getSelectedCompressionAlgorithm());
    }

    @Test
    void connectionOptionsLeaveNegotiatedValuesEmptyForLegacyRuntime() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);

        connectionSetup.configurator().afterResponse(Map.of());

        assertNull(connectionSetup.configurator().getRuntimeSessionId());
        assertNull(connectionSetup.configurator().getRuntimeVersion());
        assertNull(connectionSetup.configurator().getSelectedCompressionAlgorithm());
    }

    @Test
    void sendBatchIgnoresClosedChannelDuringShutdown() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mock(WebsocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doThrow(new ClosedChannelException()).when(session).sendBinary(any());
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, "client123",
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime456",
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE)));

        client.close();

        Method sendBatch = AbstractWebsocketClient.class.getDeclaredMethod("sendBatch", List.class,
                                                                           WebsocketSession.class);
        sendBatch.setAccessible(true);

        List<Request> requests = List.of(new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE));
        assertDoesNotThrow(() -> sendBatch.invoke(client, requests, session));
    }

    @Test
    void metricsPublishingIsIgnoredAfterClientClose() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);

        client.close();

        assertDoesNotThrow(() -> client.publishTestMetric(
                new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE)));
    }

    @Test
    void metricsPublishingIsIgnoredWhenMetricsClientIsClosed() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);

        try {
            websocketClient(client).getGatewayClient(MessageType.METRICS).close();

            assertDoesNotThrow(() -> client.publishTestMetric(
                    new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE)));
        } finally {
            client.close();
        }
    }

    @Test
    void pingSchedulerUsesDedicatedWorkerPool() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, 2);

        try {
            assertFalse(pingSchedulerWorkerPool(client) instanceof DirectExecutorService);
        } finally {
            client.close();
        }
    }

    void connectionRetryConfigurationLogsInitialAndPeriodicFailures() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        LoggingObservingClient client = new LoggingObservingClient(mock(WebsocketConnector.class), clientConfig);

        RetryConfiguration configuration = client.retryConfiguration(URI.create("ws://localhost"), Duration.ofSeconds(1));

        configuration.getExceptionLogger().accept(retryStatus(configuration, 0));
        configuration.getExceptionLogger().accept(retryStatus(configuration, 1));
        configuration.getExceptionLogger().accept(retryStatus(configuration, 9));
        configuration.getExceptionLogger().accept(retryStatus(configuration, 10));
        configuration.getExceptionLogger().accept(retryStatus(configuration, 20));

        assertEquals(List.of(0, 10, 20), client.loggedFailureRetryCounts());
        client.close();
    }

    @Test
    void connectionRetryConfigurationLogsReconnectSuccessWithRetryCount() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        LoggingObservingClient client = new LoggingObservingClient(mock(WebsocketConnector.class), clientConfig);

        RetryConfiguration configuration = client.retryConfiguration(URI.create("ws://localhost"), Duration.ofSeconds(1));
        configuration.getSuccessLogger().accept(RetryStatus.builder()
                                                   .retryConfiguration(configuration)
                                                   .task("connect")
                                                   .numberOfTimesRetried(3)
                                                   .build());

        assertEquals(List.of(3), client.loggedSuccessRetryCounts());
        client.close();
    }

    @Test
    void connectAttemptUsesOuterFailsafeTimeout() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .connectionTimeout(Duration.ofMillis(100))
                .build();
        BlockingWebsocketConnector container = new BlockingWebsocketConnector();

        try (TimeoutObservingClient client = new TimeoutObservingClient(container, clientConfig,
                                                                        Duration.ofMillis(50))) {
            assertTimeout(Duration.ofSeconds(2), () -> assertThrows(TimeoutException.class, client::connectOnce));
            assertTrue(container.connectStarted.await(1, TimeUnit.SECONDS));
            assertTrue(container.connectInterrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void onCloseRetriesOutstandingRequestsAsynchronouslyWhenBacklogExists() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        RetryObservingClient client = new RetryObservingClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");
        @SuppressWarnings("unchecked")
        Backlog<Request> backlog = mock(Backlog.class);
        sessionBacklogs(client).put("client123_runtime456", backlog);

        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<String> onCloseThread = new AtomicReference<>();
        try {
            Future<?> onCloseFuture = callerExecutor.submit(() -> {
                onCloseThread.set(Thread.currentThread().getName());
                client.onClose(session, new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "boom"));
            });

            assertTrue(client.retryStarted.await(1, TimeUnit.SECONDS));
            assertTrue(onCloseFuture.isDone());
            assertEquals("client123_runtime456", client.retrySessionId.get());
            assertNotEquals(onCloseThread.get(), client.retryThread.get());
            verify(backlog).shutDown();
        } finally {
            client.allowRetryToFinish.countDown();
            callerExecutor.shutdownNow();
            client.close();
        }
    }

    @Test
    void onCloseSkipsOutstandingRequestRetryWhenNoBacklogExists() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        RetryObservingClient client = new RetryObservingClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.onClose(session, new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "boom"));

            assertEquals(0, client.retrySchedules.get());
        } finally {
            client.allowRetryToFinish.countDown();
            client.close();
        }
    }

    @Test
    void onCloseSkipsOutstandingRequestRetryWhenClientIsClosed() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        RetryObservingClient client = new RetryObservingClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");
        @SuppressWarnings("unchecked")
        Backlog<Request> backlog = mock(Backlog.class);
        sessionBacklogs(client).put("client123_runtime456", backlog);
        client.close();

        client.onClose(session, new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "boom"));

        assertEquals(0, client.retrySchedules.get());
        verify(backlog).shutDown();
    }

    @Test
    void onCloseAbortsOpenSessionsWhileClientIsOpen() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mock(WebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, "client123",
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime456")));
        when(session.getRequestURI()).thenReturn(URI.create("ws://localhost"));
        when(session.isOpen()).thenReturn(true);

        try {
            client.handleClose(session, new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "shutdown"));
            client.handleClose(session, new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "done"));
            client.handleClose(session, new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "abort"));

            verify(session, times(3)).abort(any());
            client.close();
            client.handleClose(session, new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "shutdown"));

            verify(session, times(3)).abort(any());
        } finally {
            client.close();
        }
    }

    @Test
    void onPongIsHandledAsynchronously() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        CallbackObservingClient client = new CallbackObservingClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<String> callerThread = new AtomicReference<>();

        try {
            Future<?> onPongFuture = callerExecutor.submit(() -> {
                callerThread.set(Thread.currentThread().getName());
                client.onPong(ByteBuffer.allocate(0), session);
            });

            assertTrue(client.pongHandled.await(1, TimeUnit.SECONDS));
            onPongFuture.get(1, TimeUnit.SECONDS);
            assertNotEquals(callerThread.get(), client.pongThread.get());
        } finally {
            client.allowPongToFinish.countDown();
            callerExecutor.shutdownNow();
            client.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Backlog<Request>> sessionBacklogs(AbstractWebsocketClient client) throws Exception {
        Field field = AbstractWebsocketClient.class.getDeclaredField("sessionBacklogs");
        field.setAccessible(true);
        return (Map<String, Backlog<Request>>) field.get(client);
    }

    private static WebsocketSession mockSession(String sessionId) {
        WebsocketSession session = mock(WebsocketSession.class);
        String[] parts = sessionId.split("_", 2);
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, parts[0],
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, parts[1])));
        when(session.getRequestURI()).thenReturn(URI.create("ws://localhost"));
        when(session.isOpen()).thenReturn(false);
        return session;
    }

    private static RetryStatus retryStatus(RetryConfiguration configuration, int retryCount) {
        return RetryStatus.builder()
                .retryConfiguration(configuration)
                .task("connect")
                .exception(new IllegalStateException("boom-" + retryCount))
                .numberOfTimesRetried(retryCount)
                .build();
    }

    private static TaskScheduler pingScheduler(AbstractWebsocketClient client) throws Exception {
        Field field = AbstractWebsocketClient.class.getDeclaredField("pingScheduler");
        field.setAccessible(true);
        return (TaskScheduler) field.get(client);
    }

    private static Object pingSchedulerWorkerPool(AbstractWebsocketClient client) throws Exception {
        Object scheduler = pingScheduler(client);
        Field field = scheduler.getClass().getDeclaredField("workerPool");
        field.setAccessible(true);
        return field.get(scheduler);
    }

    private static WebSocketClient websocketClient(AbstractWebsocketClient client) throws Exception {
        Field field = AbstractWebsocketClient.class.getDeclaredField("client");
        field.setAccessible(true);
        return (WebSocketClient) field.get(client);
    }

    private static class TestClient extends AbstractWebsocketClient {
        TestClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig) {
            this(container, clientConfig, 1);
        }

        TestClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig, int numberOfSessions) {
            super(container, URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  true, Duration.ofSeconds(1), defaultObjectMapper, numberOfSessions);
        }

        void publishTestMetric(Append append) {
            tryPublishMetrics(append, Metadata.empty());
        }
    }

    private static class RetryObservingClient extends TestClient {
        private final CountDownLatch retryStarted = new CountDownLatch(1);
        private final CountDownLatch allowRetryToFinish = new CountDownLatch(1);
        private final AtomicReference<String> retryThread = new AtomicReference<>();
        private final AtomicReference<String> retrySessionId = new AtomicReference<>();
        private final AtomicInteger retrySchedules = new AtomicInteger();
        private final ExecutorService retryExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory());

        RetryObservingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig) {
            super(container, clientConfig);
        }

        @Override
        protected void retryOutstandingRequestsAsync(String sessionId) {
            retrySchedules.incrementAndGet();
            retryExecutor.execute(() -> retryOutstandingRequests(sessionId));
        }

        @Override
        protected void retryOutstandingRequests(String sessionId) {
            retryThread.set(Thread.currentThread().getName());
            retrySessionId.set(sessionId);
            retryStarted.countDown();
            try {
                allowRetryToFinish.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            retryExecutor.shutdownNow();
            super.close();
        }
    }

    private static class CallbackObservingClient extends TestClient {
        private final CountDownLatch pongHandled = new CountDownLatch(1);
        private final CountDownLatch allowPongToFinish = new CountDownLatch(1);
        private final AtomicReference<String> pongThread = new AtomicReference<>();

        CallbackObservingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig) {
            super(container, clientConfig);
        }

        @Override
        protected void handlePong(WebsocketSession session) {
            pongThread.set(Thread.currentThread().getName());
            pongHandled.countDown();
            try {
                allowPongToFinish.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class LoggingObservingClient extends TestClient {
        private final List<RetryStatus> loggedFailures = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<RetryStatus> loggedSuccesses = new java.util.concurrent.CopyOnWriteArrayList<>();

        LoggingObservingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig) {
            super(container, clientConfig);
        }

        RetryConfiguration retryConfiguration(URI endpointUri, Duration reconnectDelay) {
            return createConnectionRetryConfiguration(endpointUri, reconnectDelay);
        }

        @Override
        protected void logConnectionRetryStatus(URI endpointUri, RetryStatus status) {
            int retryCount = status.getNumberOfTimesRetried();
            if (retryCount == 0 || retryCount > 0 && retryCount % CONNECTION_RETRY_LOG_INTERVAL == 0) {
                loggedFailures.add(status);
            }
        }

        @Override
        protected void logSuccessfulReconnect(URI endpointUri, RetryStatus status) {
            loggedSuccesses.add(status);
        }

        List<Integer> loggedFailureRetryCounts() {
            return loggedFailures.stream().map(RetryStatus::getNumberOfTimesRetried).collect(Collectors.toList());
        }

        List<Integer> loggedSuccessRetryCounts() {
            return loggedSuccesses.stream().map(RetryStatus::getNumberOfTimesRetried).collect(Collectors.toList());
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "test-reconnect-thread");
        }
    }

    private static class TimeoutObservingClient extends TestClient {
        private final Duration connectionTimeoutFailsafeGrace;
        private final WebsocketConnector container;

        TimeoutObservingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig,
                               Duration connectionTimeoutFailsafeGrace) {
            super(container, clientConfig);
            this.container = container;
            this.connectionTimeoutFailsafeGrace = connectionTimeoutFailsafeGrace;
        }

        WebsocketSession connectOnce() throws Exception {
            return connectToServer(container, URI.create("ws://localhost"));
        }

        @Override
        protected Duration getConnectionTimeoutFailsafeGrace() {
            return connectionTimeoutFailsafeGrace;
        }
    }

    private static class BlockingWebsocketConnector implements WebsocketConnector {
        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch connectInterrupted = new CountDownLatch(1);

        @Override
        public WebsocketSession connect(WebsocketEndpoint endpoint, WebsocketConnectionOptions options, URI uri)
                throws IOException {
            connectStarted.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                connectInterrupted.countDown();
                throw new IOException("Interrupted while connecting", e);
            }
            throw new IOException("Connection unexpectedly completed");
        }
    }
}
