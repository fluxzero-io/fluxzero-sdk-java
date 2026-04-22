package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.Backlog;
import io.fluxzero.common.DirectExecutorService;
import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.RetryStatus;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.SdkVersion;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.publishing.Append;
import io.undertow.websockets.jsr.ServerWebSocketContainer;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.PongMessage;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.net.URI;
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
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
    void endpointConfigPublishesSupportedCompressionAlgorithmsHeader() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();

        var headers = new HashMap<String, List<String>>();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        connectionSetup.endpointConfig().getConfigurator().beforeRequest(headers);

        assertEquals(clientConfig.getSupportedCompressionAlgorithms(),
                     WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
        assertEquals(connectionSetup.configurator().getClientSessionId(),
                     WebSocketCapabilities.getClientSessionId(headers).orElseThrow());
        assertEquals(SdkVersion.version().orElseThrow(),
                     WebSocketCapabilities.getClientSdkVersion(headers).orElseThrow());
        assertEquals(12, connectionSetup.configurator().getClientSessionId().length());
    }

    @Test
    void endpointConfigCanSuppressCapabilityHeaders() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(List.of())
                .build();

        var headers = new HashMap<String, List<String>>();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        connectionSetup.endpointConfig().getConfigurator().beforeRequest(headers);

        assertEquals(List.of(), WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
        assertEquals(connectionSetup.configurator().getClientSessionId(),
                     WebSocketCapabilities.getClientSessionId(headers).orElseThrow());
    }

    @Test
    void endpointConfigPublishesConfiguredUndertowConnectionTimeout() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .connectionTimeout(Duration.ofMillis(1500))
                .build();

        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);

        assertEquals(1, connectionSetup.endpointConfig().getUserProperties().get(ServerWebSocketContainer.TIMEOUT));
    }

    @Test
    void endpointConfigCapturesNegotiatedHandshakeResponse() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        HandshakeResponse response = mock(HandshakeResponse.class);
        when(response.getHeaders()).thenReturn(Map.of(
                WebSocketCapabilities.RUNTIME_SESSION_ID_HEADER, List.of("srv123456789"),
                WebSocketCapabilities.RUNTIME_VERSION_HEADER, List.of("1.2.3"),
                WebSocketCapabilities.SELECTED_COMPRESSION_ALGORITHM_HEADER, List.of("LZ4")));

        connectionSetup.endpointConfig().getConfigurator().afterResponse(response);

        assertEquals("srv123456789", connectionSetup.configurator().getRuntimeSessionId());
        assertEquals("1.2.3", connectionSetup.configurator().getRuntimeVersion());
        assertEquals(CompressionAlgorithm.LZ4, connectionSetup.configurator().getSelectedCompressionAlgorithm());
    }

    @Test
    void endpointConfigLeavesNegotiatedValuesEmptyForLegacyRuntime() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        HandshakeResponse response = mock(HandshakeResponse.class);
        when(response.getHeaders()).thenReturn(Map.of());

        connectionSetup.endpointConfig().getConfigurator().afterResponse(response);

        assertNull(connectionSetup.configurator().getRuntimeSessionId());
        assertNull(connectionSetup.configurator().getRuntimeVersion());
        assertNull(connectionSetup.configurator().getSelectedCompressionAlgorithm());
    }

    @Test
    void constructorRejectsSubclassesAnnotatedWithClientEndpoint() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                                                   () -> new InvalidAnnotatedClient(mock(WebSocketContainer.class),
                                                                                    clientConfig));

        assertTrue(error.getMessage().contains("@ClientEndpoint"));
        assertTrue(error.getMessage().contains(InvalidAnnotatedClient.class.getName()));
    }

    @Test
    void sendBatchIgnoresClosedChannelDuringShutdown() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebSocketContainer.class), clientConfig);
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(remote.getSendStream()).thenThrow(new ClosedChannelException());
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, "client123",
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime456")));

        client.close();

        Method sendBatch = AbstractWebsocketClient.class.getDeclaredMethod("sendBatch", List.class, Session.class);
        sendBatch.setAccessible(true);

        List<Request> requests = List.of(new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE));
        assertDoesNotThrow(() -> sendBatch.invoke(client, requests, session));
    }

    @Test
    void pingSchedulerUsesDedicatedWorkerPool() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebSocketContainer.class), clientConfig, 2);

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
        LoggingObservingClient client = new LoggingObservingClient(mock(WebSocketContainer.class), clientConfig);

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
        LoggingObservingClient client = new LoggingObservingClient(mock(WebSocketContainer.class), clientConfig);

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
        BlockingWebSocketContainer container = new BlockingWebSocketContainer();

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
        RetryObservingClient client = new RetryObservingClient(mock(WebSocketContainer.class), clientConfig);
        Session session = mockSession("client123_runtime456");
        @SuppressWarnings("unchecked")
        Backlog<Request> backlog = mock(Backlog.class);
        sessionBacklogs(client).put("client123_runtime456", backlog);

        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<String> onCloseThread = new AtomicReference<>();
        try {
            Future<?> onCloseFuture = callerExecutor.submit(() -> {
                onCloseThread.set(Thread.currentThread().getName());
                client.onClose(session, new jakarta.websocket.CloseReason(
                        jakarta.websocket.CloseReason.CloseCodes.UNEXPECTED_CONDITION, "boom"));
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
        RetryObservingClient client = new RetryObservingClient(mock(WebSocketContainer.class), clientConfig);
        Session session = mockSession("client123_runtime456");

        try {
            client.onClose(session, new jakarta.websocket.CloseReason(
                    jakarta.websocket.CloseReason.CloseCodes.UNEXPECTED_CONDITION, "boom"));

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
        RetryObservingClient client = new RetryObservingClient(mock(WebSocketContainer.class), clientConfig);
        Session session = mockSession("client123_runtime456");
        @SuppressWarnings("unchecked")
        Backlog<Request> backlog = mock(Backlog.class);
        sessionBacklogs(client).put("client123_runtime456", backlog);
        client.close();

        client.onClose(session, new jakarta.websocket.CloseReason(
                jakarta.websocket.CloseReason.CloseCodes.UNEXPECTED_CONDITION, "boom"));

        assertEquals(0, client.retrySchedules.get());
        verify(backlog).shutDown();
    }

    @Test
    void onPongIsHandledAsynchronously() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        CallbackObservingClient client = new CallbackObservingClient(mock(WebSocketContainer.class), clientConfig);
        Session session = mockSession("client123_runtime456");
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<String> callerThread = new AtomicReference<>();

        try {
            Future<?> onPongFuture = callerExecutor.submit(() -> {
                callerThread.set(Thread.currentThread().getName());
                client.onPong(mock(PongMessage.class), session);
            });

            assertTrue(client.pongHandled.await(1, TimeUnit.SECONDS));
            assertTrue(onPongFuture.isDone());
            assertNotEquals(callerThread.get(), client.pongThread.get());
        } finally {
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

    private static Session mockSession(String sessionId) {
        Session session = mock(Session.class);
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

    @ClientEndpoint
    private static class InvalidAnnotatedClient extends AbstractWebsocketClient {
        InvalidAnnotatedClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
            super(container, URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1);
        }
    }

    private static class TestClient extends AbstractWebsocketClient {
        TestClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
            this(container, clientConfig, 1);
        }

        TestClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig, int numberOfSessions) {
            super(container, URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  true, Duration.ofSeconds(1), defaultObjectMapper, numberOfSessions);
        }
    }

    private static class RetryObservingClient extends TestClient {
        private final CountDownLatch retryStarted = new CountDownLatch(1);
        private final CountDownLatch allowRetryToFinish = new CountDownLatch(1);
        private final AtomicReference<String> retryThread = new AtomicReference<>();
        private final AtomicReference<String> retrySessionId = new AtomicReference<>();
        private final AtomicInteger retrySchedules = new AtomicInteger();
        private final ExecutorService retryExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory());

        RetryObservingClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
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
                throw new IllegalStateException(e);
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
        private final AtomicReference<String> pongThread = new AtomicReference<>();

        CallbackObservingClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
            super(container, clientConfig);
        }

        @Override
        protected void handlePong(Session session) {
            pongThread.set(Thread.currentThread().getName());
            pongHandled.countDown();
        }
    }

    private static class LoggingObservingClient extends TestClient {
        private final List<RetryStatus> loggedFailures = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<RetryStatus> loggedSuccesses = new java.util.concurrent.CopyOnWriteArrayList<>();

        LoggingObservingClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
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
        private final WebSocketContainer container;

        TimeoutObservingClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig,
                               Duration connectionTimeoutFailsafeGrace) {
            super(container, clientConfig);
            this.container = container;
            this.connectionTimeoutFailsafeGrace = connectionTimeoutFailsafeGrace;
        }

        Session connectOnce() throws Exception {
            return connectToServer(container, URI.create("ws://localhost"));
        }

        @Override
        protected Duration getConnectionTimeoutFailsafeGrace() {
            return connectionTimeoutFailsafeGrace;
        }
    }

    private static class BlockingWebSocketContainer implements WebSocketContainer {
        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch connectInterrupted = new CountDownLatch(1);

        @Override
        public long getDefaultAsyncSendTimeout() {
            return 0;
        }

        @Override
        public void setAsyncSendTimeout(long timeout) {
        }

        @Override
        public Session connectToServer(Object endpoint, URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session connectToServer(Class<?> annotatedEndpointClass, URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session connectToServer(Endpoint endpoint, ClientEndpointConfig cec, URI path)
                throws DeploymentException, IOException {
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

        @Override
        public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDefaultMaxSessionIdleTimeout() {
            return 0;
        }

        @Override
        public void setDefaultMaxSessionIdleTimeout(long timeout) {
        }

        @Override
        public int getDefaultMaxBinaryMessageBufferSize() {
            return 0;
        }

        @Override
        public void setDefaultMaxBinaryMessageBufferSize(int max) {
        }

        @Override
        public int getDefaultMaxTextMessageBufferSize() {
            return 0;
        }

        @Override
        public void setDefaultMaxTextMessageBufferSize(int max) {
        }

        @Override
        public Set<Extension> getInstalledExtensions() {
            return Set.of();
        }
    }
}
