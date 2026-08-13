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
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.RetryStatus;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.VoidResult;
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.common.SdkVersion;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractWebsocketClientTest {

    @Test
    void malformedSdkRuntimeMessageFailsItsRetainedIngressCompletion() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.LZ4);
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);

        try {
            CompletableFuture<Void> completion = client.dispatchRuntimeMessage(
                    () -> client.handleMessage(CompressionAlgorithm.LZ4.compress(new byte[]{'x'}), session, null))
                    .toCompletableFuture();

            assertTrue(completion.isCompletedExceptionally(),
                       "Malformed SDK ingress must not release retained capacity as a successful message");
        } finally {
            client.close();
        }
    }

    @Test
    void nestedRuntimeDispatchRestoresOuterCompletionContext() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        AtomicInteger handledResults = new AtomicInteger();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig) {
            @Override
            protected void handleResult(RequestResult result, String batchId, String sessionId,
                                        WebsocketResultDiagnostics.ResultTiming timing) {
                handledResults.incrementAndGet();
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE);
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);
        byte[] result = io.fluxzero.common.websocket.WebSocketTransportCodecs.json(
                        AbstractWebsocketClient.defaultObjectMapper)
                .encode(new VoidResult(1L));

        try {
            CompletableFuture<Void> outerCompletion = client.dispatchRuntimeMessage(() -> {
                client.dispatchRuntimeMessage(() -> client.handleMessage(result, session, null))
                        .toCompletableFuture().join();
                client.handleMessage(result, session, null);
            }).toCompletableFuture();

            outerCompletion.get(1, TimeUnit.SECONDS);
            assertEquals(2, handledResults.get());
        } finally {
            client.close();
        }
    }

    @Test
    void singleAndBatchedResponsesUseTheSameDedicatedCompletionPath() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        CountDownLatch resultsHandled = new CountDownLatch(2);
        AtomicReference<Thread> runtimeDataThread = new AtomicReference<>();
        Map<Long, Thread> handlingThreads = new ConcurrentHashMap<>();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig) {
            @Override
            protected void handleResult(RequestResult result, String batchId, String sessionId,
                                        WebsocketResultDiagnostics.ResultTiming timing) {
                handlingThreads.put(result.getRequestId(), Thread.currentThread());
                resultsHandled.countDown();
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE);
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);
        byte[] single = io.fluxzero.common.websocket.WebSocketTransportCodecs.json(
                        AbstractWebsocketClient.defaultObjectMapper)
                .encode(new VoidResult(1L));
        byte[] batch = io.fluxzero.common.websocket.WebSocketTransportCodecs.json(
                        AbstractWebsocketClient.defaultObjectMapper)
                .encode(new ResultBatch(List.of(new VoidResult(2L))));
        ExecutorService runtimeDataExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("test-runtime-data").factory());

        try {
            runtimeDataExecutor.submit(() -> {
                runtimeDataThread.set(Thread.currentThread());
                client.handleMessage(single, session, null);
            }).get(1, TimeUnit.SECONDS);
            runtimeDataExecutor.submit(() -> client.handleMessage(batch, session, null)).get(1, TimeUnit.SECONDS);

            assertTrue(resultsHandled.await(1, TimeUnit.SECONDS));
            assertNotEquals(runtimeDataThread.get(), handlingThreads.get(1L));
            assertNotEquals(runtimeDataThread.get(), handlingThreads.get(2L));
        } finally {
            runtimeDataExecutor.shutdownNow();
            client.close();
        }
    }

    @Test
    void isolatedRuntimeWorkerCompletesSingleAndOneResultBatchWithoutSecondExecutorHop() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        Map<Long, Thread> handlingThreads = new ConcurrentHashMap<>();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig) {
            @Override
            protected void handleResult(RequestResult result, String batchId, String sessionId,
                                        WebsocketResultDiagnostics.ResultTiming timing) {
                handlingThreads.put(result.getRequestId(), Thread.currentThread());
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE);
        session.getUserProperties().put(
                AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);
        byte[] single = io.fluxzero.common.websocket.WebSocketTransportCodecs.json(
                        AbstractWebsocketClient.defaultObjectMapper)
                .encode(new VoidResult(1L));
        byte[] batch = io.fluxzero.common.websocket.WebSocketTransportCodecs.json(
                        AbstractWebsocketClient.defaultObjectMapper)
                .encode(new ResultBatch(List.of(new VoidResult(2L))));
        ExecutorService runtimeDataExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("test-runtime-data").factory());

        try {
            Thread runtimeDataThread = runtimeDataExecutor.submit(() -> {
                client.dispatchRuntimeMessage(() -> client.handleMessage(single, session, null))
                        .toCompletableFuture().join();
                client.dispatchRuntimeMessage(() -> client.handleMessage(batch, session, null))
                        .toCompletableFuture().join();
                return Thread.currentThread();
            }).get(1, TimeUnit.SECONDS);

            assertEquals(runtimeDataThread, handlingThreads.get(1L));
            assertEquals(runtimeDataThread, handlingThreads.get(2L));
        } finally {
            runtimeDataExecutor.shutdownNow();
            client.close();
        }
    }

    @Test
    void transportMetricsAreOptIn() {
        assertFalse(AbstractWebsocketClient.transportMetricsEnabled(new SimplePropertySource(Map.of())));
        assertFalse(AbstractWebsocketClient.transportMetricsEnabled(new SimplePropertySource(Map.of(
                AbstractWebsocketClient.TRANSPORT_METRICS_ENABLED_PROPERTY, "false"))));
        assertTrue(AbstractWebsocketClient.transportMetricsEnabled(new SimplePropertySource(Map.of(
                AbstractWebsocketClient.TRANSPORT_METRICS_ENABLED_PROPERTY, "true"))));
    }

    @Test
    void transportMetricsAreDisabledByDefault() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TransportMetricObservingClient client = new TransportMetricObservingClient(clientConfig, true);
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.handleError(session, JdkWebSocketSession.RuntimeDataDispatchException.overflow(
                    runtimeDataState(2, 4_096L, 2, 0)));

            assertNull(client.transportMetric.get());
        } finally {
            client.close();
        }
    }

    @Test
    void connectionSetupAlwaysMarksSdkRuntimeDataDispatch() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        AbstractWebsocketClient.ConnectionSetup defaults =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        AbstractWebsocketClient.ConnectionSetup metricsEnabled =
                AbstractWebsocketClient.createConnectionSetup(clientConfig, null, true);
        AbstractWebsocketClient.ConnectionSetup stallCloseEnabled =
                AbstractWebsocketClient.createConnectionSetup(
                        clientConfig.toBuilder().runtimeIngressStallCloseTimeout(Duration.ofSeconds(30)).build());

        assertEquals(true, defaults.options().userProperties().get(
                JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY));
        assertEquals(true, metricsEnabled.options().userProperties().get(
                JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY));
        assertFalse(defaults.options().userProperties().containsKey(
                JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY));
        assertFalse(defaults.options().userProperties().containsKey(
                JdkWebSocketSession.SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY));
        assertEquals(true, metricsEnabled.options().userProperties().get(
                JdkWebSocketSession.SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY));
        assertEquals(true, stallCloseEnabled.options().userProperties().get(
                JdkWebSocketSession.SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY));
    }

    @Test
    void connectionSetupPropagatesEffectiveRuntimeWebSocketCapacity() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .maxConcurrentRuntimeWebSocketMessages(2)
                .maxRetainedRuntimeWebSocketMessages(11)
                .maxRetainedRuntimeWebSocketBytes(8L * 1024 * 1024)
                .build();

        Map<String, Object> userProperties = AbstractWebsocketClient.createConnectionSetup(clientConfig)
                .options().userProperties();

        assertEquals(2, userProperties.get(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY));
        assertEquals(11, userProperties.get(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY));
        assertEquals(8L * 1024 * 1024,
                     userProperties.get(JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY));
    }

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
    void supportedCompressionAlgorithmsDefaultToZstdWithLz4Fallback() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        assertEquals(List.of(CompressionAlgorithm.ZSTD, CompressionAlgorithm.LZ4),
                     clientConfig.getSupportedCompressionAlgorithms());
    }

    @Test
    void supportedTransportFormatsDefaultToCborWithJsonFallback() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        assertEquals(List.of(WebSocketTransportFormat.CBOR, WebSocketTransportFormat.JSON),
                     clientConfig.getSupportedTransportFormats());
        assertEquals(Duration.ofSeconds(30), clientConfig.getWebSocketSendTimeout());
    }

    @Test
    void serviceUrlKeepsLz4AsLegacyCompressionHintForDefaultConfig() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        assertTrue(ServiceUrlBuilder.gatewayUrl(MessageType.EVENT, null, clientConfig).contains("compression=LZ4"));
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
        assertEquals(clientConfig.getSupportedTransportFormats(),
                     WebSocketCapabilities.getSupportedTransportFormats(headers));
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
    void replacementConnectionIdentifiesThePreviousNegotiatedSession() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig, "old-client_old-runtime");

        assertEquals("old-client_old-runtime", WebSocketCapabilities.getReplacedSessionId(
                connectionSetup.options().headers()).orElseThrow());
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
                WebSocketCapabilities.SELECTED_COMPRESSION_ALGORITHM_HEADER, List.of("LZ4"),
                WebSocketCapabilities.SELECTED_TRANSPORT_FORMAT_HEADER, List.of("CBOR"));

        connectionSetup.configurator().afterResponse(responseHeaders);

        assertEquals("srv123456789", connectionSetup.configurator().getRuntimeSessionId());
        assertEquals("1.2.3", connectionSetup.configurator().getRuntimeVersion());
        assertEquals(CompressionAlgorithm.LZ4, connectionSetup.configurator().getSelectedCompressionAlgorithm());
        assertEquals(WebSocketTransportFormat.CBOR,
                     connectionSetup.configurator().getSelectedTransportFormat());
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
        assertNull(connectionSetup.configurator().getSelectedTransportFormat());
    }

    @Test
    void onOpenUsesLegacyUrlCompressionWhenRuntimeDoesNotSelectCompression() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mock(WebsocketSession.class);
        Map<String, Object> userProperties = new HashMap<>();
        userProperties.put(AbstractWebsocketClient.CLIENT_HANDSHAKE_CONFIGURATOR_USER_PROPERTY,
                           AbstractWebsocketClient.createConnectionSetup(clientConfig).configurator());
        when(session.getUserProperties()).thenReturn(userProperties);
        when(session.getHandshakeResponseHeaders()).thenReturn(Map.of());
        when(session.getRequestURI()).thenReturn(URI.create("ws://localhost/tracking/readevent?compression=LZ4"));

        try {
            client.onOpen(session);

            assertEquals(CompressionAlgorithm.LZ4,
                         userProperties.get(AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY));
            assertEquals(WebSocketTransportFormat.JSON,
                         userProperties.get(AbstractWebsocketClient.SELECTED_TRANSPORT_FORMAT_USER_PROPERTY));
        } finally {
            client.close();
        }
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
    void sendBatchClosesSessionWhenTransportSendFails() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mock(WebsocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.sendBinaryAsync(any(), anyInt()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("No buffer space available")));
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, "client123",
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime456",
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE)));

        try {
            Method sendBatch = AbstractWebsocketClient.class.getDeclaredMethod("sendBatch", List.class,
                                                                               WebsocketSession.class);
            sendBatch.setAccessible(true);

            List<Request> requests = List.of(new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE));
            assertDoesNotThrow(() -> sendBatch.invoke(client, requests, session));

            verify(session).closeAsync(any());
        } finally {
            client.close();
        }
    }

    @Test
    void sendBatchClosesSessionWhenTransportSendTimesOut() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .webSocketSendTimeout(Duration.ofMillis(10))
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mock(WebsocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.sendBinaryAsync(any(), anyInt())).thenReturn(new CompletableFuture<>());
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, "client123",
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime456",
                AbstractWebsocketClient.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE)));

        try {
            Method sendBatch = AbstractWebsocketClient.class.getDeclaredMethod("sendBatch", List.class,
                                                                               WebsocketSession.class);
            sendBatch.setAccessible(true);

            List<Request> requests = List.of(new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE));
            assertTimeout(Duration.ofSeconds(2), () -> assertDoesNotThrow(() -> sendBatch.invoke(client, requests, session)));

            verify(session).closeAsync(any());
        } finally {
            client.close();
        }
    }

    @Test
    void abortStartsOrderlyCloseAndDoesNotAbortAfterPeerAcknowledgement() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        CloseObservingClient client = new CloseObservingClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");
        CompletableFuture<Void> closeHandshake = new CompletableFuture<>();
        when(session.closeAsync(any())).thenReturn(closeHandshake);

        try {
            client.abortForTest(session, "Ping failed");

            verify(session).closeAsync(any());
            verify(session, never()).abort(any());
            closeHandshake.complete(null);
            Thread.sleep(150);
            verify(session, never()).abort(any());
        } finally {
            client.close();
        }
    }

    @Test
    void abortFallsBackToTransportAbortWhenPeerDoesNotAcknowledgeClose() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        CloseObservingClient client = new CloseObservingClient(mock(WebsocketConnector.class), clientConfig);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.closeAsync(any())).thenReturn(new CompletableFuture<>());

        try {
            client.abortForTest(session, "Ping failed");

            verify(session).closeAsync(any());
            verify(session, timeout(1000)).abort(any());
        } finally {
            client.close();
        }
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
    void clientResultTimingMetadataIncludesClientTimestamps() {
        Metadata metadata = AbstractWebsocketClient.clientResultTimingMetadata(
                new WebsocketResultDiagnostics.ResultTiming(
                        1_000L, 1_005L, 1_008L, 1_010L, 1_012L, 2L, 1_015L, 3L, 1_020L, 1_030L));

        assertEquals("1000", metadata.get("clientFrameReceivedTimestamp"));
        assertEquals("1005", metadata.get("clientFrameDispatchQueuedTimestamp"));
        assertEquals("1008", metadata.get("clientFrameDispatchStartedTimestamp"));
        assertEquals("1010", metadata.get("clientRuntimeDispatchQueuedTimestamp"));
        assertEquals("1012", metadata.get("clientRuntimeDispatchStartedTimestamp"));
        assertEquals("2", metadata.get("clientRuntimeQueueDuration"));
        assertEquals("1015", metadata.get("clientDecodedTimestamp"));
        assertEquals("3", metadata.get("clientDecodeDuration"));
        assertEquals("1020", metadata.get("clientCallbackQueuedTimestamp"));
        assertEquals("1030", metadata.get("clientCallbackStartedTimestamp"));
    }

    @Test
    void clientResultTimingMetadataIsEmptyWithoutTiming() {
        assertTrue(AbstractWebsocketClient.clientResultTimingMetadata(null).getEntries().isEmpty());
    }

    @Test
    void resultDiagnosticsDefaultsToRuntimeTimingMetadata() {
        WebsocketResultDiagnostics diagnostics = WebsocketResultDiagnostics.from(name -> null);
        VoidResult result = new VoidResult(42L);
        result.setRequestReceivedTimestamp(2_000L);

        Metadata metadata = diagnostics.metadata(result, WebsocketResultDiagnostics.ResultTiming.none());

        assertEquals(WebsocketResultDiagnostics.DEFAULT, diagnostics);
        assertFalse(diagnostics.captureReceiveTiming());
        assertEquals("2000", metadata.get("requestReceivedTimestamp"));
        assertFalse(metadata.containsKey("clientDecodedTimestamp"));
    }

    @Test
    void legacyTimingPropertyEnablesHeavyDiagnostics() {
        WebsocketResultDiagnostics diagnostics = WebsocketResultDiagnostics.from(
                name -> WebsocketResultDiagnostics.LEGACY_TIMING_ENABLED_PROPERTY.equals(name) ? "true" : null);

        assertEquals(WebsocketResultDiagnostics.HEAVY, diagnostics);
        assertTrue(diagnostics.captureReceiveTiming());
    }

    @Test
    void explicitDiagnosticsModeOverridesLegacyTimingProperty() {
        WebsocketResultDiagnostics diagnostics = WebsocketResultDiagnostics.from(name -> switch (name) {
            case WebsocketResultDiagnostics.MODE_PROPERTY -> "none";
            case WebsocketResultDiagnostics.LEGACY_TIMING_ENABLED_PROPERTY -> "true";
            default -> null;
        });

        assertEquals(WebsocketResultDiagnostics.NONE, diagnostics);
        assertFalse(diagnostics.captureReceiveTiming());
    }

    @Test
    void heavyDiagnosticsIncludeRuntimeAndClientTimingMetadata() {
        VoidResult result = new VoidResult(42L);
        result.setRequestReceivedTimestamp(2_000L);
        result.setResponseQueuedTimestamp(2_260L);
        result.setResponseSendStartTimestamp(2_275L);
        WebsocketResultDiagnostics.FrameTiming frameTiming = WebsocketResultDiagnostics.HEAVY.frameTiming(
                new WebsocketEndpoint.ReceiveTiming(1_000L, 1_005L, 1_008L),
                new SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming(1_009L, 1_011L, 2L));
        WebsocketResultDiagnostics.ResultTiming resultTiming = WebsocketResultDiagnostics.HEAVY.resultTiming(
                frameTiming, 1_015L, 4L, 1_020L, 1_030L);

        Metadata metadata = WebsocketResultDiagnostics.HEAVY.metadata(result, resultTiming);

        assertEquals("2260", metadata.get("responseQueuedTimestamp"));
        assertEquals("2275", metadata.get("responseSendStartTimestamp"));
        assertEquals("1000", metadata.get("clientFrameReceivedTimestamp"));
        assertEquals("1009", metadata.get("clientRuntimeDispatchQueuedTimestamp"));
        assertEquals("1011", metadata.get("clientRuntimeDispatchStartedTimestamp"));
        assertEquals("2", metadata.get("clientRuntimeQueueDuration"));
        assertEquals("1015", metadata.get("clientDecodedTimestamp"));
        assertEquals("4", metadata.get("clientDecodeDuration"));
        assertEquals("1030", metadata.get("clientCallbackStartedTimestamp"));
    }

    @Test
    void responseTimingMetadataIncludesRuntimeDeliveryTimestamps() {
        VoidResult result = new VoidResult(42L);
        result.setRequestReceivedTimestamp(2_000L);
        result.setResponseQueuedTimestamp(2_260L);
        result.setResponseSendStartTimestamp(2_275L);

        Metadata metadata = AbstractWebsocketClient.responseTimingMetadata(result);

        assertEquals("2260", metadata.get("responseQueuedTimestamp"));
        assertEquals("2275", metadata.get("responseSendStartTimestamp"));
    }

    @Test
    void serverDurationIsZeroForReplayedResponseThatPredatesRetriedRequest() {
        RequestResult result = new RequestTimingResult(42L, 1_000L, 2_000L);
        Metadata metadata = AbstractWebsocketClient.responseTimingMetadata(result);

        assertEquals(0L, AbstractWebsocketClient.serverMsDuration(result));
        assertTrue(AbstractWebsocketClient.isReplayedResponse(result));
        assertEquals("0", metadata.get("serverMsDuration"));
        assertEquals("true", metadata.get(AbstractWebsocketClient.REPLAYED_RESPONSE_METADATA_KEY));
    }

    @Test
    void serverDurationIsCalculatedFromServerRequestAndResponseTimestamps() {
        RequestResult result = new RequestTimingResult(42L, 2_250L, 2_000L);
        Metadata metadata = AbstractWebsocketClient.responseTimingMetadata(result);

        assertEquals(250L, AbstractWebsocketClient.serverMsDuration(result));
        assertFalse(AbstractWebsocketClient.isReplayedResponse(result));
        assertEquals("250", metadata.get("serverMsDuration"));
        assertFalse(metadata.containsKey(AbstractWebsocketClient.REPLAYED_RESPONSE_METADATA_KEY));
    }

    @Test
    void pingTimeoutRunsOnDedicatedWorkerInsteadOfSchedulerThread() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .pingTimeout(Duration.ZERO)
                .build();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, 2);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);
        AtomicReference<String> closeThread = new AtomicReference<>();
        when(session.closeAsync(any())).thenAnswer(invocation -> {
            closeThread.set(Thread.currentThread().getName());
            return CompletableFuture.completedFuture(null);
        });

        try {
            client.sendPing(session);

            verify(session, org.mockito.Mockito.timeout(1_000)).closeAsync(any());
            assertFalse(closeThread.get().contains("pingScheduler"));
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
                .connectionTimeout(Duration.ofMillis(20))
                .build();
        BlockingWebsocketConnector container = new BlockingWebsocketConnector();

        try (TimeoutObservingClient client = new TimeoutObservingClient(container, clientConfig,
                                                                        Duration.ofMillis(10))) {
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
    void pongCancelsDeadlineBeforeResultExecutorCallbackRuns() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        CallbackObservingClient client = new CallbackObservingClient(
                mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.sendPing(session);
            ThrowingRunnable staleTimeout = taskScheduler.dequeue();
            client.onPong(ByteBuffer.allocate(0), session);
            assertTrue(client.pongHandled.await(1, TimeUnit.SECONDS));
            staleTimeout.run();

            verify(session, never()).closeAsync(any());
            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "Receiving a pong should replace its deadline before result callbacks can finish");
        } finally {
            client.allowPongToFinish.countDown();
            client.close();
        }
    }

    @Test
    void pongDoesNotScheduleAnotherPingForClosedSession() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true, false);

        try {
            client.sendPing(session);
            client.onPong(ByteBuffer.allocate(0), session);

            assertEquals(0, taskScheduler.pendingTaskCount());
        } finally {
            client.close();
        }
    }

    @Test
    void pingIsNotSentWhenSessionClosesBeforeDeadlineRegistration() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true, false);

        try {
            assertDoesNotThrow(() -> client.sendPing(session));

            verify(session, never()).sendPing(any());
            assertEquals(0, taskScheduler.pendingTaskCount());
        } finally {
            client.close();
        }
    }

    @Test
    void stalePingTimeoutDoesNotAbortSessionAfterPongWasHandled() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        PongSchedulingClient client = new PongSchedulingClient(
                mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.sendPing(session);
            ThrowingRunnable staleTimeout = taskScheduler.dequeue();

            client.onPong(ByteBuffer.allocate(0), session);
            assertTrue(client.pongHandled.await(1, TimeUnit.SECONDS));
            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "Pong handling should schedule the next ping before the stale timeout runs");
            staleTimeout.run();

            verify(session, never()).closeAsync(any());
            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "A stale timeout must not remove the next ping registration");
        } finally {
            client.close();
        }
    }

    @Test
    void activePingTimeoutStillAbortsSession() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.sendPing(session);
            ThrowingRunnable activeTimeout = taskScheduler.dequeue();

            activeTimeout.run();

            verify(session).closeAsync(any());
        } finally {
            client.close();
        }
    }

    @Test
    void localRuntimeBackpressureFencesAnAlreadyQueuedPingTimeoutAndResumesPings() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.sendPing(session);
            ThrowingRunnable staleTimeout = taskScheduler.dequeue();

            client.onRuntimeIngressBackpressure(session, true, runtimeIngressState(19));
            staleTimeout.run();

            verify(session, never()).closeAsync(any());
            verify(session, times(1)).sendPing(any());
            assertEquals(0, taskScheduler.pendingTaskCount());

            client.onRuntimeIngressBackpressure(session, false, runtimeIngressState(18));

            assertEquals(1, taskScheduler.pendingTaskCount());
        } finally {
            client.close();
        }
    }

    @Test
    void repeatedCapacityPausesKeepOneDelayedPingAndPublishPressureOncePerSession() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        AtomicInteger publishedMetrics = new AtomicInteger();
        CountDownLatch firstMetric = new CountDownLatch(1);
        CountDownLatch secondMetric = new CountDownLatch(2);
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler) {
            @Override
            void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
                publishedMetrics.incrementAndGet();
                firstMetric.countDown();
                secondMetric.countDown();
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.schedulePing(session);
            assertEquals(1, taskScheduler.pendingTaskCount());

            for (int i = 0; i < 100; i++) {
                client.onRuntimeIngressBackpressure(session, true, runtimeIngressState(19));
                client.onRuntimeIngressBackpressure(session, false, runtimeIngressState(18));
            }

            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "Capacity transitions must not cancel and recreate an unsent heartbeat");
            assertTrue(firstMetric.await(1, TimeUnit.SECONDS));
            assertFalse(secondMetric.await(50, TimeUnit.MILLISECONDS));
            assertEquals(1, publishedMetrics.get(),
                         "Normal sustained capacity pressure is diagnosed once per physical session");
        } finally {
            client.close();
        }
    }

    @Test
    void retainedRuntimeIngressStallsOnceAndRecoversOnFunctionalCompletion() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        CountDownLatch metricsPublished = new CountDownLatch(2);
        List<WebsocketTransportMetric.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler) {
            @Override
            void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
                events.add(metric.event());
                metricsPublished.countDown();
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.RETAINED_WORK_STARTED, 2, 1L);
            assertEquals(1, taskScheduler.pendingTaskCount());

            taskScheduler.advance(clientConfig.getPingTimeout());
            taskScheduler.dequeue().run();
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED, 1, 2L);

            assertTrue(metricsPublished.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(WebsocketTransportMetric.Event.RUNTIME_INGRESS_STALLED,
                                 WebsocketTransportMetric.Event.RUNTIME_INGRESS_RECOVERED), events);
            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "Remaining retained work should start a fresh progress deadline");
            verify(session, never()).closeAsync(any());
        } finally {
            client.close();
        }
    }

    @Test
    void idleRuntimeIngressNeverSchedulesOrEmitsStall() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED, 0, 1L);

            assertEquals(0, taskScheduler.pendingTaskCount());
            assertNull(client.transportMetric.get());
        } finally {
            client.close();
        }
    }

    @Test
    void olderParallelCompletionCannotRestartStallMonitoringAfterIngressDrained() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.RETAINED_WORK_STARTED, 2, 1L);
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED, 0, 3L);
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED, 1, 2L);

            assertEquals(0, taskScheduler.pendingTaskCount(),
                         "A stale completion snapshot must not re-arm a drained ingress watchdog");
        } finally {
            client.close();
        }
    }

    @Test
    void newIngressBurstGetsFreshStallDeadlineAfterPreviousBurstDrained() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .pingTimeout(Duration.ofSeconds(10))
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.RETAINED_WORK_STARTED, 1, 1L);
            taskScheduler.advance(Duration.ofSeconds(4));
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED, 0, 2L);
            taskScheduler.advance(Duration.ofSeconds(4));
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.RETAINED_WORK_STARTED, 1, 3L);

            assertEquals(18_000L, taskScheduler.nextDeadline());
        } finally {
            client.close();
        }
    }

    @Test
    void functionalProgressExtendsStallDeadlineFromLatestCompletionWithoutPerMessageRescheduling() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .pingTimeout(Duration.ofSeconds(10))
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.RETAINED_WORK_STARTED, 2, 1L);
            taskScheduler.advance(Duration.ofSeconds(4));
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED, 1, 2L);

            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "A completion should update the rolling watchdog instead of replacing its task");
            taskScheduler.advance(Duration.ofSeconds(6));
            taskScheduler.dequeue().run();

            assertNull(client.transportMetric.get());
            assertEquals(1, taskScheduler.pendingTaskCount(),
                         "The original deadline should retain one watchdog task");
            assertEquals(14_000L, taskScheduler.nextDeadline(),
                         "The next deadline should be one ping timeout after the latest completion");

            taskScheduler.advance(Duration.ofSeconds(4));
            taskScheduler.dequeue().run();

            assertTrue(client.transportMetricPublished.await(1, TimeUnit.SECONDS));
            assertEquals(WebsocketTransportMetric.Event.RUNTIME_INGRESS_STALLED,
                         client.transportMetric.get().event());
        } finally {
            client.close();
        }
    }

    @Test
    void explicitlyConfiguredStallCloseTimeoutClosesOnlyAfterStall() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .runtimeIngressStallCloseTimeout(Duration.ofSeconds(30))
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TestClient client = new TestClient(mock(WebsocketConnector.class), clientConfig, taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.onRuntimeIngressProgress(
                    session, RuntimeIngressController.Progress.RETAINED_WORK_STARTED, 1, 1L);

            taskScheduler.advance(clientConfig.getPingTimeout());
            taskScheduler.dequeue().run();
            verify(session, never()).closeAsync(any());
            assertEquals(1, taskScheduler.pendingTaskCount());

            taskScheduler.advance(clientConfig.getRuntimeIngressStallCloseTimeout());
            taskScheduler.dequeue().run();

            verify(session).closeAsync(any());
        } finally {
            client.close();
        }
    }

    @Test
    void runtimeIngressOverflowPublishesSparseTransportMetric() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .maxConcurrentRuntimeWebSocketMessages(2)
                .maxRetainedRuntimeWebSocketMessages(11)
                .maxRetainedRuntimeWebSocketBytes(8L * 1024 * 1024)
                .build();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), new ManuallyTriggeredTaskScheduler());
        WebsocketSession session = mockSession("client123_runtime456");
        session.getUserProperties().put(AbstractWebsocketClient.RUNTIME_VERSION_USER_PROPERTY, "9.8.7");
        JdkWebSocketSession.RuntimeDataDispatchException overflow =
                JdkWebSocketSession.RuntimeDataDispatchException.overflow(
                        runtimeDataState(2, 4_096L, 2, 0, 2, 11, 8L * 1024 * 1024));

        try {
            client.handleError(session, overflow);

            WebsocketTransportMetric metric = client.transportMetric.get();
            assertEquals(WebsocketTransportMetric.Event.RUNTIME_INGRESS_OVERFLOW, metric.event());
            assertEquals(2, metric.retainedMessages());
            assertEquals(4_096L, metric.retainedBytes());
            assertEquals(2, metric.inFlightMessages());
            assertEquals(4_096L, metric.inFlightBytes());
            assertEquals(2, metric.activeMessages());
            assertEquals(4_096L, metric.activeBytes());
            assertEquals(0, metric.pendingMessages());
            assertEquals(0L, metric.pendingBytes());
            assertEquals(clientConfig.getMaxConcurrentRuntimeWebSocketMessages(), metric.maxConcurrency());
            assertEquals(clientConfig.getMaxRetainedRuntimeWebSocketMessages(), metric.maxRetainedMessages());
            assertEquals(clientConfig.getMaxRetainedRuntimeWebSocketBytes(), metric.maxRetainedBytes());
            assertEquals(0L, metric.deferredFrameBytes());
            assertFalse(metric.ingressBackpressured());
            assertEquals(0, metric.completionWorkGroups());
            assertEquals(0, metric.activeResultCompletions());
            assertEquals(0, metric.pendingResultCompletions());
            assertEquals(clientConfig.getMaxConcurrentRuntimeResultCompletions(),
                         metric.maxCompletionConcurrency());
            assertEquals(0L, metric.stallCloseTimeoutMillis());
            assertEquals(Runtime.version().feature(), metric.javaFeatureVersion());
            assertEquals("custom-connector", metric.workerMode());
            assertEquals("9.8.7", metric.runtimeVersion());
        } finally {
            client.close();
        }
    }

    @Test
    void runtimeIngressBackpressureMetricIncludesEffectiveCompletionAndStallLimits() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .maxConcurrentRuntimeResultCompletions(5)
                .runtimeIngressStallCloseTimeout(Duration.ofSeconds(30))
                .build();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), new ManuallyTriggeredTaskScheduler());
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.onRuntimeIngressBackpressure(session, true, runtimeIngressState(19));

            assertTrue(client.transportMetricPublished.await(1, TimeUnit.SECONDS));
            WebsocketTransportMetric metric = client.transportMetric.get();
            assertEquals(WebsocketTransportMetric.Event.RUNTIME_INGRESS_BACKPRESSURED, metric.event());
            assertTrue(metric.ingressBackpressured());
            assertEquals(5, metric.maxCompletionConcurrency());
            assertEquals(Duration.ofSeconds(30).toMillis(), metric.stallCloseTimeoutMillis());
        } finally {
            client.close();
        }
    }

    @Test
    void runtimeExecutorRejectionPublishesSparseTransportMetric() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), new ManuallyTriggeredTaskScheduler());
        WebsocketSession session = mockSession("client123_runtime456");
        JdkWebSocketSession.RuntimeDataDispatchException rejection =
                JdkWebSocketSession.RuntimeDataDispatchException.executorRejected(
                        runtimeDataState(1, 1_024L, 0, 1),
                        new IllegalStateException("executor unavailable"));

        try {
            client.handleError(session, rejection);

            WebsocketTransportMetric metric = client.transportMetric.get();
            assertEquals(WebsocketTransportMetric.Event.RUNTIME_EXECUTOR_REJECTED, metric.event());
            assertEquals(1, metric.retainedMessages());
            assertEquals(1_024L, metric.retainedBytes());
            assertEquals(0, metric.inFlightMessages());
            assertEquals(0L, metric.inFlightBytes());
            assertEquals(0, metric.activeMessages());
            assertEquals(0L, metric.activeBytes());
            assertEquals(1, metric.pendingMessages());
            assertEquals(1_024L, metric.pendingBytes());
        } finally {
            client.close();
        }
    }

    @Test
    void transportMetricsAreSuppressedForMetricsWebsocket() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("metrics-client")
                .build();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, false, transportMetricsProperties(), new ManuallyTriggeredTaskScheduler());
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.handleError(session, JdkWebSocketSession.RuntimeDataDispatchException.overflow(
                    runtimeDataState(2, 4_096L, 2, 0)));

            assertNull(client.transportMetric.get());
        } finally {
            client.close();
        }
    }

    @Test
    void pingTimeoutPublishesSparseTransportMetricAfterStartingSessionClose() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler);
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.sendPing(session);
            taskScheduler.dequeue().run();

            assertTrue(client.transportMetricPublished.await(1, TimeUnit.SECONDS));
            assertEquals(WebsocketTransportMetric.Event.PING_TIMEOUT,
                         client.transportMetric.get().event());
            verify(session).closeAsync(any());
        } finally {
            client.close();
        }
    }

    @Test
    void transportMetricsAreSuppressedWhenMetricsAreDisabled() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .disableMetrics(true)
                .build();
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), new ManuallyTriggeredTaskScheduler());
        WebsocketSession session = mockSession("client123_runtime456");

        try {
            client.handleError(session, JdkWebSocketSession.RuntimeDataDispatchException.overflow(
                    runtimeDataState(2, 4_096L, 2, 0)));

            assertNull(client.transportMetric.get());
        } finally {
            client.close();
        }
    }

    @Test
    void transportMetricFailureDoesNotPreventPingTimeoutClose() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        CountDownLatch metricPublicationAttempted = new CountDownLatch(1);
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler) {
            @Override
            void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
                metricPublicationAttempted.countDown();
                throw new IllegalStateException("metrics unavailable");
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);

        try {
            client.sendPing(session);

            assertDoesNotThrow(() -> taskScheduler.dequeue().run());
            verify(session).closeAsync(any());
            assertTrue(metricPublicationAttempted.await(1, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }

    @Test
    void transportMetricPublicationCannotDelayPingTimeoutClose() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        ManuallyTriggeredTaskScheduler taskScheduler = new ManuallyTriggeredTaskScheduler();
        CountDownLatch metricStarted = new CountDownLatch(1);
        CountDownLatch releaseMetric = new CountDownLatch(1);
        TransportMetricObservingClient client = new TransportMetricObservingClient(
                clientConfig, true, transportMetricsProperties(), taskScheduler) {
            @Override
            void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
                metricStarted.countDown();
                try {
                    releaseMetric.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        WebsocketSession session = mockSession("client123_runtime456");
        when(session.isOpen()).thenReturn(true);
        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor();

        try {
            client.sendPing(session);
            Future<?> timeout = timeoutExecutor.submit(() -> {
                try {
                    taskScheduler.dequeue().run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertTrue(metricStarted.await(1, TimeUnit.SECONDS));
            verify(session, org.mockito.Mockito.timeout(250)).closeAsync(any());
            releaseMetric.countDown();
            timeout.get(1, TimeUnit.SECONDS);
        } finally {
            releaseMetric.countDown();
            timeoutExecutor.shutdownNow();
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

    private static SimplePropertySource transportMetricsProperties() {
        return new SimplePropertySource(Map.of(
                AbstractWebsocketClient.TRANSPORT_METRICS_ENABLED_PROPERTY, "true"));
    }

    private static JdkWebSocketSession.RuntimeDataState runtimeDataState(
            int retainedMessages, long retainedBytes, int inFlightMessages, int pendingMessages) {
        return runtimeDataState(retainedMessages, retainedBytes, inFlightMessages, pendingMessages,
                                JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES);
    }

    private static JdkWebSocketSession.RuntimeDataState runtimeDataState(
            int retainedMessages, long retainedBytes, int inFlightMessages, int pendingMessages,
            int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes) {
        return new JdkWebSocketSession.RuntimeDataState(
                retainedMessages, retainedBytes, inFlightMessages,
                inFlightMessages == 0 ? 0L : retainedBytes,
                inFlightMessages, inFlightMessages == 0 ? 0L : retainedBytes,
                pendingMessages, pendingMessages == 0 ? 0L : retainedBytes,
                maxConcurrency, maxRetainedMessages, maxRetainedBytes, 0L, 0L);
    }

    private static RuntimeIngressController.State runtimeIngressState(int retainedMessages) {
        return new RuntimeIngressController.State(
                retainedMessages, retainedMessages, retainedMessages, retainedMessages,
                retainedMessages, retainedMessages, 0, 0L,
                JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES);
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

        TestClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig,
                   TaskScheduler pingScheduler) {
            super(container, URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1,
                  new SimplePropertySource(Map.of()), (client, numberOfSessions) -> pingScheduler);
        }

        void publishTestMetric(Append append) {
            tryPublishMetrics(append, Metadata.empty());
        }

        @Override
        void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
            // Transport metric publication is tested independently by TransportMetricObservingClient.
        }
    }

    private static class CloseObservingClient extends TestClient {
        CloseObservingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig) {
            super(container, clientConfig);
        }

        void abortForTest(WebsocketSession session, String reason) {
            abort(session, reason);
        }

        @Override
        protected Duration getCloseHandshakeTimeout() {
            return Duration.ofMillis(100);
        }
    }

    private record RequestTimingResult(long requestId, long timestamp,
                                       long requestReceivedTimestamp) implements RequestResult {
        @Override
        public long getRequestId() {
            return requestId;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public long getRequestReceivedTimestamp() {
            return requestReceivedTimestamp;
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

        CallbackObservingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig,
                                TaskScheduler pingScheduler) {
            super(container, clientConfig, pingScheduler);
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

    private static class PongSchedulingClient extends TestClient {
        private final CountDownLatch pongHandled = new CountDownLatch(1);

        PongSchedulingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig) {
            super(container, clientConfig);
        }

        PongSchedulingClient(WebsocketConnector container, WebSocketClient.ClientConfig clientConfig,
                             TaskScheduler pingScheduler) {
            super(container, clientConfig, pingScheduler);
        }

        @Override
        protected void handlePong(WebsocketSession session) {
            try {
                super.handlePong(session);
            } finally {
                pongHandled.countDown();
            }
        }
    }

    private static class TransportMetricObservingClient extends AbstractWebsocketClient {
        private final AtomicReference<WebsocketTransportMetric> transportMetric = new AtomicReference<>();
        private final CountDownLatch transportMetricPublished = new CountDownLatch(1);

        TransportMetricObservingClient(WebSocketClient.ClientConfig clientConfig, boolean allowMetrics) {
            this(clientConfig, allowMetrics, new SimplePropertySource(Map.of()),
                 new ManuallyTriggeredTaskScheduler());
        }

        TransportMetricObservingClient(WebSocketClient.ClientConfig clientConfig, boolean allowMetrics,
                                       SimplePropertySource propertySource, TaskScheduler pingScheduler) {
            super(mock(WebsocketConnector.class), URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  allowMetrics, Duration.ofSeconds(1), defaultObjectMapper, 1, propertySource,
                  (client, numberOfSessions) -> pingScheduler);
        }

        @Override
        void publishTransportMetric(WebsocketTransportMetric metric, Metadata metadata) {
            transportMetric.set(metric);
            transportMetricPublished.countDown();
        }
    }

    private static class ManuallyTriggeredTaskScheduler implements TaskScheduler {
        private final ArrayDeque<ScheduledTask> scheduledTasks = new ArrayDeque<>();
        private Instant currentInstant = Instant.EPOCH;

        @Override
        public synchronized Registration schedule(long deadline, ThrowingRunnable task) {
            ScheduledTask scheduledTask = new ScheduledTask(deadline, task);
            scheduledTasks.add(scheduledTask);
            return () -> {
                synchronized (ManuallyTriggeredTaskScheduler.this) {
                    scheduledTasks.remove(scheduledTask);
                }
            };
        }

        synchronized ThrowingRunnable dequeue() {
            return scheduledTasks.remove().task();
        }

        synchronized int pendingTaskCount() {
            return scheduledTasks.size();
        }

        synchronized long nextDeadline() {
            return scheduledTasks.getFirst().deadline();
        }

        synchronized void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public synchronized Clock clock() {
            return Clock.fixed(currentInstant, ZoneOffset.UTC);
        }

        @Override
        public void executeExpiredTasks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized void shutdown() {
            scheduledTasks.clear();
        }

        private record ScheduledTask(long deadline, ThrowingRunnable task) {
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
