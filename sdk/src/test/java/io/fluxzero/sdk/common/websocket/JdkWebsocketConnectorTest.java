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
 */

package io.fluxzero.sdk.common.websocket;

import com.sun.management.ThreadMXBean;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.VoidResult;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.common.websocket.WebSocketTransportCodec;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdkWebsocketConnectorTest {

    @Test
    void defaultClientConnectorUsesJdkWebsocketConnector() {
        assertInstanceOf(JdkWebsocketConnector.class, AbstractWebsocketClient.defaultWebsocketConnector);
    }

    @Test
    void inboundActivityTrackingIsDisabledWithoutTransportMetricsOptIn() throws Exception {
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new RecordingEndpoint(),
                new WebsocketConnectionOptions(Map.of(), Map.of(), null, List.of()),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run);

        Thread.sleep(10);

        assertEquals(0L, session.runtimeDataState().lastInboundAgeMillis());
    }

    @Test
    void inboundActivityTrackingCanBeEnabledForTransportMetrics() throws Exception {
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new RecordingEndpoint(),
                new WebsocketConnectionOptions(Map.of(), Map.of(
                        JdkWebSocketSession.SDK_TRANSPORT_METRICS_ENABLED_USER_PROPERTY, true), null, List.of()),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run);

        Thread.sleep(10);

        assertTrue(session.runtimeDataState().lastInboundAgeMillis() > 0L);
    }

    @Test
    void connectAppliesHeadersAndCapturesResponseHeaders() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();
            WebsocketConnectionOptions options = new WebsocketConnectionOptions(
                    Map.of("Fluxzero-Test", List.of("value")), Map.of(), Duration.ofSeconds(2), List.of("fluxzero"));

            WebsocketSession session = connector.connect(endpoint, options, server.uri());

            assertSame(session, endpoint.session.get());
            assertTrue(session.isOpen());
            assertEquals(List.of("value"), server.requestHeaders().get("Fluxzero-Test"));
            assertEquals(List.of("fluxzero"), server.requestHeaders().get("Sec-WebSocket-Protocol"));
            assertEquals(List.of("runtime123"),
                         session.getHandshakeResponseHeaders().get(WebSocketCapabilities.RUNTIME_SESSION_ID_HEADER));
            assertEquals(List.of("runtime123"),
                         session.getHandshakeResponseHeaders().get(
                                 WebSocketCapabilities.RUNTIME_SESSION_ID_HEADER.toLowerCase()));
            assertEquals("runtime123",
                         WebSocketCapabilities.getRuntimeSessionId(
                                 session.getHandshakeResponseHeaders()).orElseThrow());
            assertEquals("9.8.7",
                         WebSocketCapabilities.getRuntimeVersion(session.getHandshakeResponseHeaders()).orElseThrow());
            assertEquals(CompressionAlgorithm.GZIP,
                         WebSocketCapabilities.getSelectedCompressionAlgorithm(
                                 session.getHandshakeResponseHeaders()).orElseThrow());
            assertEquals(1, session.getOpenSessions().size());
        }
    }

    @Test
    void connectPreservesConfiguredCookieHandlerWhileCapturingResponseHeaders() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            AtomicBoolean cookieGetCalled = new AtomicBoolean();
            AtomicBoolean cookiePutCalled = new AtomicBoolean();
            CookieHandler cookieHandler = new CookieHandler() {
                @Override
                public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
                    cookieGetCalled.set(true);
                    return Map.of("Cookie", List.of("session=abc"));
                }

                @Override
                public void put(URI uri, Map<String, List<String>> responseHeaders) {
                    cookiePutCalled.set(true);
                }
            };
            HttpClient httpClient = HttpClient.newBuilder().cookieHandler(cookieHandler).build();
            JdkWebsocketConnector connector = new JdkWebsocketConnector(httpClient);

            WebsocketSession session = connector.connect(new RecordingEndpoint(),
                                                         new WebsocketConnectionOptions(
                                                                 Map.of(), Map.of(), null, List.of()), server.uri());

            assertTrue(cookieGetCalled.get());
            assertTrue(cookiePutCalled.get());
            assertEquals(List.of("session=abc"), server.requestHeaders().get("Cookie"));
            assertEquals("runtime123",
                         WebSocketCapabilities.getRuntimeSessionId(
                                 session.getHandshakeResponseHeaders()).orElseThrow());
        }
    }

    @Test
    void explicitConnectorExecutorRetainsSdkRuntimeCallbackAffinity() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "custom-websocket-executor"));
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CountDownLatch messageReceived = new CountDownLatch(1);
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector(
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), executor);
            RecordingEndpoint endpoint = new RecordingEndpoint() {
                @Override
                public void onMessage(byte[] bytes, WebsocketSession session) {
                    callbackThread.set(Thread.currentThread().getName());
                    messageReceived.countDown();
                }
            };

            JdkWebSocketSession session = (JdkWebSocketSession) connector.connect(
                    endpoint, sdkRuntimeOptions(), server.uri());
            server.sendFrame(true, 0x2, new byte[]{1});

            assertTrue(messageReceived.await(1, TimeUnit.SECONDS));
            assertEquals("custom-websocket-executor", callbackThread.get());
            assertEquals("shared-caller-owned", session.runtimeDataWorkerMode());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void abortedConnectingSessionIgnoresLateOpenCallback() {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint,
                new WebsocketConnectionOptions(Map.of(), Map.of(), null, List.of()),
                URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run);
        WebSocket webSocket = mock(WebSocket.class);

        session.abortConnecting();
        session.createListener().onOpen(webSocket);

        assertFalse(session.isOpen());
        assertNull(endpoint.session.get());
        verify(webSocket).abort();
    }

    @Test
    void openCallbackDoesNotDependOnCallbackExecutorCapacity() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        JdkWebsocketConnector connector = new JdkWebsocketConnector();
        JdkWebSocketSession session = new JdkWebSocketSession(
                connector, endpoint,
                new WebsocketConnectionOptions(Map.of(), Map.of(), null, List.of()),
                URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(),
                task -> {
                    throw new RejectedExecutionException("executor is saturated");
                });
        WebSocket webSocket = mock(WebSocket.class);

        session.createListener().onOpen(webSocket);
        session.awaitOpen();

        assertSame(session, endpoint.session.get());
        assertTrue(session.isOpen());
        assertEquals(Set.of(session), connector.getOpenSessions());
        verify(webSocket).request(1);
    }

    @Test
    void connectorDerivesJdkHttpClientOnlyOnceAcrossConnections() throws Exception {
        CountingHttpClient httpClient = new CountingHttpClient();
        JdkWebsocketConnector connector = new JdkWebsocketConnector(httpClient);
        List<TestWebSocketServer> servers = new ArrayList<>();
        List<WebsocketSession> sessions = new ArrayList<>();
        int configurationReadsAfterConstruction = httpClient.configurationReads();
        try {
            for (int i = 0; i < 4; i++) {
                String runtimeSessionId = "runtime" + i;
                TestWebSocketServer server = TestWebSocketServer.start(runtimeSessionId);
                servers.add(server);

                WebsocketSession session = connector.connect(new RecordingEndpoint(), null, server.uri());
                sessions.add(session);

                assertEquals(runtimeSessionId,
                             WebSocketCapabilities.getRuntimeSessionId(
                                     session.getHandshakeResponseHeaders()).orElseThrow());
            }

            assertEquals(configurationReadsAfterConstruction, httpClient.configurationReads(),
                         "Base HttpClient configuration should not be recopied for every websocket connection");
        } finally {
            WebsocketCloseReason closeReason =
                    new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "test complete");
            sessions.forEach(session -> session.abort(closeReason));
            for (TestWebSocketServer server : servers) {
                server.close();
            }
        }
    }

    @Test
    void parallelConnectsCaptureHandshakeHeadersForOverlappingHandshakes() throws Exception {
        int connectionCount = 4;
        JdkWebsocketConnector connector = new JdkWebsocketConnector();
        ExecutorService connectExecutor = Executors.newFixedThreadPool(connectionCount);
        CountDownLatch requestsRead = new CountDownLatch(connectionCount);
        CountDownLatch releaseResponses = new CountDownLatch(1);
        List<TestWebSocketServer> servers = new ArrayList<>();
        List<CompletableFuture<WebsocketSession>> connections = new ArrayList<>();
        List<WebsocketSession> sessions = new ArrayList<>();
        try {
            for (int i = 0; i < connectionCount; i++) {
                TestWebSocketServer server =
                        TestWebSocketServer.startDelayed("parallel-runtime" + i, requestsRead, releaseResponses);
                servers.add(server);
                connections.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return connector.connect(new RecordingEndpoint(), null, server.uri());
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, connectExecutor));
            }

            assertTrue(requestsRead.await(5, TimeUnit.SECONDS),
                       "Timed out waiting for overlapping websocket handshakes");
            releaseResponses.countDown();

            for (int i = 0; i < connectionCount; i++) {
                WebsocketSession session = connections.get(i).get(5, TimeUnit.SECONDS);
                sessions.add(session);
                assertEquals("parallel-runtime" + i,
                             WebSocketCapabilities.getRuntimeSessionId(
                                     session.getHandshakeResponseHeaders()).orElseThrow());
            }
        } finally {
            releaseResponses.countDown();
            WebsocketCloseReason closeReason =
                    new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "test complete");
            sessions.forEach(session -> session.abort(closeReason));
            connections.forEach(connection -> connection.cancel(true));
            connectExecutor.shutdownNow();
            for (TestWebSocketServer server : servers) {
                server.close();
            }
        }
    }

    @Test
    void connectUsesDefaultOptionsWhenOptionsAreNull() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();

            WebsocketSession session = connector.connect(endpoint, null, server.uri());

            assertTrue(session.isOpen());
            assertSame(session, endpoint.session.get());
            assertTrue(session.getUserProperties().isEmpty());
            assertTrue(server.requestHeaders().containsKey("Sec-WebSocket-Key"));
            assertEquals(1, connector.getOpenSessions().size());
        }
    }

    @Test
    void connectFailsWithHttpStatusWhenHandshakeIsRejected() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.startRejected(403)) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();

            IOException exception = assertThrows(IOException.class, () -> connector.connect(endpoint, null, server.uri()));

            assertTrue(exception.getMessage().contains("HTTP 403"));
            assertTrue(server.requestHeaders().containsKey("Sec-WebSocket-Key"));
            assertNull(endpoint.session.get());
            assertTrue(connector.getOpenSessions().isEmpty());
        }
    }

    @Test
    void connectFailsAndReportsErrorWhenEndpointOpenCallbackFails() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch onErrorReported = new CountDownLatch(1);
            WebsocketEndpoint endpoint = new RecordingEndpoint() {
                @Override
                public void onOpen(WebsocketSession session) {
                    super.onOpen(session);
                    throw new IllegalStateException("boom");
                }

                @Override
                public void onError(WebsocketSession session, Throwable throwable) {
                    error.set(throwable);
                    onErrorReported.countDown();
                }
            };

            IOException exception = assertThrows(IOException.class, () -> connector.connect(endpoint, null, server.uri()));

            assertTrue(onErrorReported.await(5, TimeUnit.SECONDS));
            assertTrue(exception.getMessage().contains("failed to open"));
            assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertEquals("boom", exception.getCause().getMessage());
            assertSame(exception.getCause(), error.get());
            assertTrue(connector.getOpenSessions().isEmpty());
        }
    }

    @Test
    void sessionDispatchesBinaryMessagesAndPongs() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();

            JdkWebSocketSession session = (JdkWebSocketSession) connector.connect(
                    endpoint, sdkRuntimeOptions(), server.uri());
            server.sendFrame(false, 0x2, new byte[]{1});
            server.sendFrame(true, 0xA, new byte[]{4});
            server.sendFrame(true, 0x0, new byte[]{2, 3});

            assertTrue(endpoint.awaitBinaryMessage());
            assertTrue(endpoint.awaitPongMessage());
            assertArrayEquals(new byte[]{1, 2, 3}, endpoint.binaryMessage.get());
            assertArrayEquals(new byte[]{4}, endpoint.pongMessage.get());
            assertTrue(session.runtimeDataWorkerMode().startsWith("isolated-sdk-default-"));
        }
    }

    @Test
    void pongIsDeliveredAheadOfQueuedBinaryMessageWhilePreviousBinaryMessageIsStillProcessing() throws Exception {
        CountDownLatch binaryProcessingStarted = new CountDownLatch(1);
        CountDownLatch allowBinaryProcessingToFinish = new CountDownLatch(1);
        CountDownLatch binaryMessagesProcessed = new CountDownLatch(2);
        CountDownLatch secondMessageProcessed = new CountDownLatch(1);
        List<Integer> processedMessages = Collections.synchronizedList(new ArrayList<>());
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint() {
                @Override
                public void onMessage(byte[] bytes, WebsocketSession session) {
                    if (bytes[0] == 1) {
                        binaryProcessingStarted.countDown();
                        try {
                            allowBinaryProcessingToFinish.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while blocking binary message processing", e);
                        }
                    }
                    processedMessages.add((int) bytes[0]);
                    if (bytes[0] == 2) {
                        secondMessageProcessed.countDown();
                    }
                    binaryMessagesProcessed.countDown();
                }
            };

            connector.connect(endpoint, sdkRuntimeOptions(), server.uri());
            server.sendFrame(true, 0x2, new byte[]{1});
            assertTrue(binaryProcessingStarted.await(1, TimeUnit.SECONDS));

            try {
                server.sendFrame(true, 0x2, new byte[]{2});
                server.sendFrame(true, 0xA, new byte[]{3});

                assertTrue(endpoint.awaitPongMessage(),
                           "Pong delivery should bypass queued binary message processing");
                assertTrue(secondMessageProcessed.await(1, TimeUnit.SECONDS),
                           "The independent second message should complete while the first remains blocked");
            } finally {
                allowBinaryProcessingToFinish.countDown();
            }
            assertTrue(binaryMessagesProcessed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(2, 1), processedMessages,
                         "Complete message processing may finish out of ingress order");
        }
    }

    @Test
    void sdkRuntimePongIsHandledWhileBinaryRuntimeMessagesAreQueued() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start();
             BlockingSdkRuntimeClient client = new BlockingSdkRuntimeClient()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            WebsocketSession session = connector.connect(
                    new SdkRuntimeWebsocketEndpoint(client), sdkRuntimeOptions(), server.uri());
            try {
                server.sendFrame(true, 0x2, new byte[]{1});
                assertTrue(client.binaryProcessingStarted.await(1, TimeUnit.SECONDS));
                server.sendFrame(true, 0x2, new byte[]{2});
                server.sendFrame(true, 0xA, new byte[]{3});

                try {
                    assertTrue(client.pongHandled.await(1, TimeUnit.SECONDS),
                               "SDK pong handling should not wait for runtime message processing");
                    assertTrue(client.secondMessageProcessed.await(1, TimeUnit.SECONDS),
                               "The independent second SDK message should complete in parallel");
                } finally {
                    client.allowBinaryProcessingToFinish.countDown();
                }
                assertTrue(client.binaryMessagesProcessed.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(2, 1), client.processedMessages,
                             "SDK runtime message processing should retain Undertow-era parallel completion");
            } finally {
                client.allowBinaryProcessingToFinish.countDown();
                session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "test complete"));
            }
        }
    }

    @Test
    void sustainedBoundedBurstDoesNotLoseBinaryMessagesOrPongs() throws Exception {
        int binaryMessageCount = 1_024;
        int pongInterval = 16;
        int pongCount = binaryMessageCount / pongInterval;
        CountDownLatch binaryMessagesReceived = new CountDownLatch(binaryMessageCount);
        CountDownLatch pongsReceived = new CountDownLatch(pongCount);
        List<Integer> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedMessageCount = new AtomicInteger();
        AtomicInteger receivedPongs = new AtomicInteger();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        Semaphore processedPermits = new Semaphore(0);
        try (ExecutorService callbackExecutor = Executors.newFixedThreadPool(4);
             ExecutorService runtimeDataExecutor = Executors.newFixedThreadPool(
                     JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
             TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector(
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    callbackExecutor, runtimeDataExecutor);
            RecordingEndpoint endpoint = new RecordingEndpoint() {
                @Override
                public void onMessage(byte[] bytes, WebsocketSession session) {
                    receivedMessages.add(ByteBuffer.wrap(bytes).getInt());
                    completedMessageCount.incrementAndGet();
                    binaryMessagesReceived.countDown();
                    processedPermits.release();
                }

                @Override
                public void onPong(ByteBuffer data, WebsocketSession session) {
                    receivedPongs.incrementAndGet();
                    pongsReceived.countDown();
                }

                @Override
                public void onError(WebsocketSession session, Throwable error) {
                    reportedError.compareAndSet(null, error);
                }
            };

            JdkWebSocketSession session = (JdkWebSocketSession) connector.connect(
                    new SdkRuntimeWebsocketEndpoint(endpoint), sdkRuntimeOptions(), server.uri());
            try {
                int messagesInBatch = 0;
                for (int i = 0; i < binaryMessageCount; i++) {
                    server.sendFrame(true, 0x2, ByteBuffer.allocate(Integer.BYTES).putInt(i).array());
                    messagesInBatch++;
                    if ((i + 1) % pongInterval == 0) {
                        server.sendFrame(true, 0xA, new byte[]{(byte) i});
                    }
                    if (messagesInBatch == JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES) {
                        int expectedCompletedMessages = i + 1;
                        assertTrue(awaitProcessedBatch(processedPermits, messagesInBatch, session),
                                   () -> burstFailureDiagnostics(
                                           expectedCompletedMessages, completedMessageCount.get(),
                                           binaryMessageCount, receivedPongs.get(), pongCount,
                                           session.runtimeDataState(), reportedError.get()));
                        messagesInBatch = 0;
                    }
                }
                if (messagesInBatch > 0) {
                    assertTrue(awaitProcessedBatch(processedPermits, messagesInBatch, session),
                               () -> burstFailureDiagnostics(
                                       binaryMessageCount, completedMessageCount.get(), binaryMessageCount,
                                       receivedPongs.get(), pongCount, session.runtimeDataState(), reportedError.get()));
                }

                assertTrue(binaryMessagesReceived.await(5, TimeUnit.SECONDS),
                           () -> burstFailureDiagnostics(
                                   binaryMessageCount, completedMessageCount.get(), binaryMessageCount,
                                   receivedPongs.get(), pongCount, session.runtimeDataState(), reportedError.get()));
                assertTrue(pongsReceived.await(5, TimeUnit.SECONDS),
                           () -> burstFailureDiagnostics(
                                   binaryMessageCount, completedMessageCount.get(), binaryMessageCount,
                                   receivedPongs.get(), pongCount, session.runtimeDataState(), reportedError.get()));
                assertNull(reportedError.get(), () -> "Unexpected transport error: " + reportedError.get());
                List<Integer> receivedMessageSnapshot;
                synchronized (receivedMessages) {
                    receivedMessageSnapshot = List.copyOf(receivedMessages);
                }
                assertEquals(binaryMessageCount, receivedMessageSnapshot.size());
                assertEquals(pongCount, receivedPongs.get());
                assertEquals(binaryMessageCount, Set.copyOf(receivedMessageSnapshot).size());
                assertTrue(receivedMessageSnapshot.stream()
                                   .allMatch(value -> value >= 0 && value < binaryMessageCount));
            } finally {
                session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "test complete"));
            }
        }
    }

    @Test
    void fasterProducerWaitsForTemporarilyBlockedSmallResultConsumerWithoutReconnect() throws Exception {
        int resultCount = 512;
        WebSocketTransportCodec codec = WebSocketTransportCodecs.json(AbstractWebsocketClient.defaultObjectMapper);
        List<byte[]> responses = new ArrayList<>(resultCount);
        for (int i = 0; i < resultCount; i++) {
            responses.add(CompressionAlgorithm.LZ4.compress(codec.encode(new VoidResult(i))));
        }
        assertTrue(responses.stream().mapToInt(response -> response.length).max().orElseThrow() < 128,
                   "The overload regression should retain customer-sized compressed responses");

        try (BlockingBurstResultCompletionClient client = new BlockingBurstResultCompletionClient(resultCount);
             TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            JdkWebSocketSession session = (JdkWebSocketSession) connector.connect(
                    new SdkRuntimeWebsocketEndpoint(client), sdkRuntimeOptions(), server.uri());
            try {
                for (byte[] response : responses) {
                    server.sendFrame(true, 0x2, response);
                }
                server.sendFrame(true, 0xA, new byte[]{42});

                assertTrue(client.activeResultsBlocked.await(5, TimeUnit.SECONDS),
                           "All client-wide completion workers should be occupied by customer callbacks");
                assertTrue(awaitRetainedMessages(
                                   session, JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                                   Duration.ofSeconds(5)),
                           () -> "Ingress did not stop at its retained bound: " + session.runtimeDataState());
                assertTrue(awaitAdmittedMessages(
                                   session, BlockingBurstResultCompletionClient.TEST_COMPLETION_CONCURRENCY,
                                   Duration.ofSeconds(5)),
                           () -> "Completion admission did not settle at its configured bound: "
                                 + session.runtimeDataState());

                JdkWebSocketSession.RuntimeDataState blockedState = session.runtimeDataState();
                assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                             blockedState.retainedMessages());
                assertEquals(JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                             blockedState.inFlightMessages());
                assertEquals(JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                             blockedState.activeMessages());
                assertEquals(BlockingBurstResultCompletionClient.TEST_COMPLETION_CONCURRENCY,
                             blockedState.admittedMessages());
                assertEquals(blockedState.retainedMessages() - blockedState.inFlightMessages()
                                     - blockedState.admittedMessages(),
                             blockedState.pendingMessages());
                assertTrue(blockedState.retainedBytes()
                                   < (long) JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES * 128,
                           blockedState::toString);
                assertTrue(session.isOpen());
                assertNull(client.reportedError.get());
                assertEquals(0, client.closeCount.get());
                assertEquals(0, client.resultsHandled.size());
                assertEquals(1L, client.pongHandled.getCount(),
                             "The pong should remain at the transport while local ingress demand is paused");

                client.allowResultHandlingToFinish.countDown();

                assertTrue(client.allResultsHandled.await(10, TimeUnit.SECONDS),
                           () -> "Only handled %d/%d results; state=%s, error=%s"
                                   .formatted(client.resultsHandled.size(), resultCount,
                                              session.runtimeDataState(), client.reportedError.get()));
                assertTrue(client.pongHandled.await(5, TimeUnit.SECONDS));
                assertTrue(awaitRetainedMessages(session, 0, Duration.ofSeconds(5)),
                           () -> "Ingress did not fully recover: " + session.runtimeDataState());
                assertEquals(resultCount, client.resultsHandled.size());
                assertTrue(session.isOpen());
                assertNull(client.reportedError.get());
                assertEquals(0, client.closeCount.get());
            } finally {
                client.allowResultHandlingToFinish.countDown();
                session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "test complete"));
            }
        }
    }

    private static boolean awaitRetainedMessages(
            JdkWebSocketSession session, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (session.runtimeDataState().retainedMessages() != expected) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
        return true;
    }

    private static boolean awaitAdmittedMessages(
            JdkWebSocketSession session, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (session.runtimeDataState().admittedMessages() != expected) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
        return true;
    }

    private static boolean awaitProcessedBatch(Semaphore processedPermits, int messagesInBatch,
                                               JdkWebSocketSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        if (!processedPermits.tryAcquire(messagesInBatch, 5, TimeUnit.SECONDS)) {
            return false;
        }
        while (session.runtimeDataState().retainedMessages() != 0) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1)));
        }
        return true;
    }

    private static String burstFailureDiagnostics(int expectedCompletedMessages, int completedMessages,
                                                  int totalMessages, int receivedPongs, int totalPongs,
                                                  JdkWebSocketSession.RuntimeDataState state, Throwable error) {
        return ("Timed out during bounded websocket burst: completed=%d/%d (batch target %d), pongs=%d/%d, "
                + "runtimeState=%s, transportError=%s")
                .formatted(completedMessages, totalMessages, expectedCompletedMessages, receivedPongs, totalPongs,
                           state, error);
    }

    private static void assertRetainedStateUnchanged(
            JdkWebSocketSession.RuntimeDataState expected, JdkWebSocketSession.RuntimeDataState actual,
            long expectedDeferredFrameBytes) {
        assertEquals(expected, actual.withTransportState(
                expected.deferredFrameBytes(), expected.lastInboundAgeMillis()));
        assertEquals(expectedDeferredFrameBytes, actual.deferredFrameBytes());
    }

    @Test
    void boundedBurstFailureDiagnosticsIncludeProgressAndRuntimeState() {
        JdkWebSocketSession.RuntimeDataState state = new JdkWebSocketSession.RuntimeDataState(
                1, 4L, 1, 4L, 0, 0L, 0, 0L, 0, 0L,
                JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES,
                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES, 0L, 12L);

        String diagnostics = burstFailureDiagnostics(
                3, 2, 1_024, 4, 64, state, new IllegalStateException("transport failed"));

        assertTrue(diagnostics.contains("completed=2/1024 (batch target 3)"), diagnostics);
        assertTrue(diagnostics.contains("pongs=4/64"), diagnostics);
        assertTrue(diagnostics.contains("runtimeState=" + state), diagnostics);
        assertTrue(diagnostics.contains("transportError=java.lang.IllegalStateException: transport failed"),
                   diagnostics);
    }

    @Test
    void smallBurstBeyondWorkerConcurrencyQueuesWithinRetainedBound() throws Exception {
        int expectedConcurrency = JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES;
        ExecutorService runtimeDataExecutor = Executors.newFixedThreadPool(
                expectedConcurrency);
        CountDownLatch processingStarted = new CountDownLatch(expectedConcurrency);
        CountDownLatch allowProcessingToFinish = new CountDownLatch(1);
        CountDownLatch processingFinished = new CountDownLatch(expectedConcurrency + 1);
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                processingStarted.countDown();
                try {
                    allowProcessingToFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while blocking runtime message processing", e);
                } finally {
                    processingFinished.countDown();
                }
            }

            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket.Listener listener = session.createListener();
        WebSocket webSocket = mock(WebSocket.class);
        listener.onOpen(webSocket);

        try {
            for (int i = 0; i < expectedConcurrency; i++) {
                listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
            }

            assertTrue(processingStarted.await(1, TimeUnit.SECONDS),
                       "All bounded runtime workers should start without waiting for the first message");
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{9}), true);

            assertNull(reportedError.get(), "A normal small burst should not reconnect a healthy session");
            JdkWebSocketSession.RuntimeDataState state = session.runtimeDataState();
            assertEquals(expectedConcurrency + 1, state.retainedMessages());
            assertEquals(expectedConcurrency, state.inFlightMessages());
            assertEquals(expectedConcurrency, state.activeMessages());
            assertEquals(1, state.pendingMessages());
            assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES, state.maxRetainedMessages());
            assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES, state.maxRetainedBytes());
            allowProcessingToFinish.countDown();
            assertTrue(processingFinished.await(5, TimeUnit.SECONDS));
        } finally {
            allowProcessingToFinish.countDown();
            runtimeDataExecutor.shutdownNow();
        }
    }

    @Test
    void configuredRetainedMessagesDerivePendingCapacityAndPauseAdditionalIngress() {
        int maxConcurrency = 2;
        int maxRetainedMessages = 5;
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint,
                sdkRuntimeOptions(maxConcurrency, maxRetainedMessages, 1_024L),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        assertFalse(session.getUserProperties().containsKey(
                JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY));
        assertFalse(session.getUserProperties().containsKey(
                JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY));
        assertFalse(session.getUserProperties().containsKey(
                JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY));

        for (int i = 0; i < maxRetainedMessages; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }

        JdkWebSocketSession.RuntimeDataState fullState = session.runtimeDataState();
        assertEquals(maxRetainedMessages, fullState.retainedMessages());
        assertEquals(maxConcurrency, fullState.inFlightMessages());
        assertEquals(maxRetainedMessages - maxConcurrency, fullState.pendingMessages());
        assertEquals(maxConcurrency, fullState.maxConcurrency());
        assertEquals(maxRetainedMessages, fullState.maxRetainedMessages());
        assertEquals(1_024L, fullState.maxRetainedBytes());

        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{99}), true).toCompletableFuture();

        assertFalse(deferred.isDone());
        assertRetainedStateUnchanged(fullState, session.runtimeDataState(), 1L);
        assertNull(reportedError.get());
        assertTrue(session.isOpen());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertEquals(maxConcurrency - 1, session.runtimeDataState().retainedMessages());
        assertNull(reportedError.get());
    }

    @Test
    void runtimeIngressPausesDemandAtCapacityAndResumesAfterCompletion() {
        int maxConcurrency = 2;
        int maxRetainedMessages = 5;
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint,
                sdkRuntimeOptions(maxConcurrency, maxRetainedMessages, 1_024L),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        for (int i = 0; i < maxRetainedMessages; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }

        assertTrue(session.isOpen());
        assertNull(reportedError.get());
        verify(webSocket, times(maxRetainedMessages)).request(1);

        runtimeDataExecutor.runNext();

        assertTrue(session.isOpen());
        assertNull(reportedError.get());
        verify(webSocket, times(maxRetainedMessages + 1)).request(1);
    }

    @Test
    void runtimeIngressCapacityTransitionIsReportedOnceToTheSdkClient() {
        int maxRetainedMessages = 5;
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AbstractWebsocketClient client = mock(AbstractWebsocketClient.class);
        when(client.dispatchStagedRuntimeMessage(any(byte[].class), any(), any())).thenReturn(
                RuntimeIngressController.MessageDispatch.admitted(CompletableFuture.completedFuture(null)));
        SdkRuntimeWebsocketEndpoint endpoint = new SdkRuntimeWebsocketEndpoint(client);
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(2, maxRetainedMessages, 1_024L),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        for (int i = 0; i < maxRetainedMessages; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }

        verify(client, times(1)).onRuntimeIngressBackpressure(eq(session), eq(true), any());

        runtimeDataExecutor.runNext();

        verify(client, times(1)).onRuntimeIngressBackpressure(eq(session), eq(false), any());
    }

    @Test
    void sdkRuntimeIoErrorBypassesTheCallbackExecutor() {
        ManuallyTriggeredExecutor callbackExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        IllegalStateException ioFailure = new IllegalStateException("connection reset");
        RecordingEndpoint delegate = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(delegate), sdkRuntimeOptions(),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                callbackExecutor, Runnable::run);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onError(webSocket, ioFailure);

        assertSame(ioFailure, reportedError.get());
        assertEquals(0, callbackExecutor.pendingTaskCount());
    }

    @Test
    void demandRequestFailureAfterRuntimeCallbackFailsSessionWithoutReceiveAccountingUnderflow() {
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        IllegalStateException requestFailure = new IllegalStateException("request failed");
        RecordingEndpoint delegate = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(delegate), sdkRuntimeOptions(),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, Runnable::run);
        WebSocket webSocket = mock(WebSocket.class);
        AtomicInteger requests = new AtomicInteger();
        doAnswer(invocation -> {
            if (requests.incrementAndGet() == 2) {
                throw requestFailure;
            }
            return null;
        }).when(webSocket).request(1);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        CompletableFuture<?> completion = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{1}), true).toCompletableFuture();

        assertTrue(completion.isCompletedExceptionally());
        assertSame(requestFailure, reportedError.get());
        assertFalse(session.isOpen());
    }

    @Test
    void demandRequestFailureAfterDirectPongFailsSessionWithoutEscapingTheListener() {
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        IllegalStateException requestFailure = new IllegalStateException("pong request failed");
        RecordingEndpoint delegate = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(delegate), sdkRuntimeOptions(),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, Runnable::run);
        WebSocket webSocket = mock(WebSocket.class);
        AtomicInteger requests = new AtomicInteger();
        doAnswer(invocation -> {
            if (requests.incrementAndGet() == 2) {
                throw requestFailure;
            }
            return null;
        }).when(webSocket).request(1);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        CompletableFuture<?> completion = assertDoesNotThrow(
                () -> listener.onPong(webSocket, ByteBuffer.wrap(new byte[]{1}))).toCompletableFuture();

        assertTrue(completion.isCompletedExceptionally());
        assertSame(requestFailure, reportedError.get());
        assertFalse(session.isOpen());
    }

    @Test
    void demandRequestFailureWhileResumingCapacityFailsSessionWithoutDoubleCompletion() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        IllegalStateException requestFailure = new IllegalStateException("resume request failed");
        RecordingEndpoint delegate = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(delegate),
                sdkRuntimeOptions(1, 1, 1_024L), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        AtomicInteger requests = new AtomicInteger();
        doAnswer(invocation -> {
            if (requests.incrementAndGet() == 2) {
                throw requestFailure;
            }
            return null;
        }).when(webSocket).request(1);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);

        runtimeDataExecutor.runNext();

        assertSame(requestFailure, reportedError.get());
        assertFalse(session.isOpen());
        assertEquals(0, session.runtimeDataState().retainedMessages());
        verify(webSocket).abort();
    }

    @Test
    void localAbortWithADeferredRuntimeFrameDoesNotReportAnInternalCloseAsTransportError() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint delegate = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.compareAndSet(null, error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(delegate),
                sdkRuntimeOptions(1, 1, 1_024L), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{2}), true).toCompletableFuture();

        session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "test close"));

        assertTrue(deferred.isCompletedExceptionally());
        assertNull(reportedError.get());
        verify(webSocket, times(1)).request(1);
    }

    @Test
    void configuredByteLimitStillAllowsOneSoleOversizedMessage() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(1, 4, 2L),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[3]), true);

        JdkWebSocketSession.RuntimeDataState retainedState = session.runtimeDataState();
        assertEquals(1, retainedState.retainedMessages());
        assertEquals(3L, retainedState.retainedBytes());
        assertEquals(2L, retainedState.maxRetainedBytes());
        assertNull(reportedError.get());

        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{1}), true).toCompletableFuture();

        assertFalse(deferred.isDone());
        assertRetainedStateUnchanged(retainedState, session.runtimeDataState(), 1L);
        assertNull(reportedError.get());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertEquals(1, session.runtimeDataState().retainedMessages());
        assertEquals(1L, session.runtimeDataState().retainedBytes());
    }

    @Test
    void binaryDispatchPausesAtItsBoundWithoutBufferingOrDisconnecting() {
        int expectedDispatchCapacity = JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES;
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        for (int i = 0; i < expectedDispatchCapacity; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }
        JdkWebSocketSession.RuntimeDataState fullState = session.runtimeDataState();

        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{99}), true).toCompletableFuture();

        assertFalse(deferred.isDone());
        assertEquals(expectedDispatchCapacity, fullState.retainedMessages());
        assertRetainedStateUnchanged(fullState, session.runtimeDataState(), 1L);
        assertNull(reportedError.get());
        assertTrue(session.isOpen());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertTrue(session.runtimeDataState().retainedMessages() < expectedDispatchCapacity);
        runtimeDataExecutor.runAll();
        assertEquals(0, session.runtimeDataState().retainedMessages());
        assertNull(reportedError.get());
    }

    @Test
    void retainedAccountingIncludesSubmittedAndPendingRuntimeMessages() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new RecordingEndpoint(), sdkRuntimeOptions(
                1, JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES,
                JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{2}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{3}), true);

        JdkWebSocketSession.RuntimeDataState state = session.runtimeDataState();
        assertEquals(3, state.retainedMessages());
        assertEquals(3L, state.retainedBytes());
        assertEquals(1, state.inFlightMessages());
        assertEquals(1L, state.inFlightBytes());
        assertEquals(0, state.activeMessages());
        assertEquals(0L, state.activeBytes());
        assertEquals(2, state.pendingMessages());
        assertEquals(2L, state.pendingBytes());
        assertEquals(1, state.maxConcurrency());

        runtimeDataExecutor.runAll();
        assertEquals(0, session.runtimeDataState().retainedMessages());
    }

    @Test
    void retainedAccountingIncludesIncompleteFragmentReassembly() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new RecordingEndpoint(), sdkRuntimeOptions(),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[4]), false);

        JdkWebSocketSession.RuntimeDataState firstFragment = session.runtimeDataState();
        assertEquals(1, firstFragment.retainedMessages());
        assertEquals(4L, firstFragment.retainedBytes());
        assertEquals(0, firstFragment.inFlightMessages());
        assertEquals(1, firstFragment.pendingMessages());
        assertEquals(4L, firstFragment.pendingBytes());

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[6]), false);

        JdkWebSocketSession.RuntimeDataState secondFragment = session.runtimeDataState();
        assertEquals(1, secondFragment.retainedMessages());
        assertEquals(10L, secondFragment.retainedBytes());
        assertEquals(1, secondFragment.pendingMessages());
        assertEquals(10L, secondFragment.pendingBytes());

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[2]), true);

        JdkWebSocketSession.RuntimeDataState completeMessage = session.runtimeDataState();
        assertEquals(1, completeMessage.retainedMessages());
        assertEquals(12L, completeMessage.retainedBytes());
        assertEquals(1, completeMessage.inFlightMessages());
        assertEquals(0, completeMessage.pendingMessages());

        runtimeDataExecutor.runAll();
        assertEquals(0, session.runtimeDataState().retainedMessages());
    }

    @Test
    void runtimeIngressPausesDemandAtItsBoundAndResumesAfterFunctionalCompletion() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new RecordingEndpoint(), sdkRuntimeOptions(),
                URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        for (int i = 0; i < JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }

        verify(webSocket, times(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES)).request(1);
        JdkWebSocketSession.RuntimeDataState fullState = session.runtimeDataState();
        assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES, fullState.retainedMessages());
        assertEquals(JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES, fullState.inFlightMessages());
        assertEquals((JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES - JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES), fullState.pendingMessages());
        listener.onPong(webSocket, ByteBuffer.wrap(new byte[]{9}));
        verify(webSocket, times(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES)).request(1);

        runtimeDataExecutor.runNext();

        verify(webSocket, times(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES + 1)).request(1);
    }

    @Test
    void sdkRuntimePongBypassesAdditionalCallbackDispatch() throws Exception {
        ManuallyTriggeredExecutor callbackExecutor = new ManuallyTriggeredExecutor();
        RecordingEndpoint endpoint = new RecordingEndpoint();
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(endpoint), sdkRuntimeOptions(),
                URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), callbackExecutor, Runnable::run);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onPong(webSocket, ByteBuffer.wrap(new byte[]{9}));

        assertTrue(endpoint.awaitPongMessage());
        assertEquals(0, callbackExecutor.pendingTaskCount());
    }

    @Test
    void runtimeMessageRemainsRetainedThroughSynchronousCustomerResultContinuation() throws Exception {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        try (BlockingResultCompletionClient client = new BlockingResultCompletionClient()) {
            JdkWebSocketSession session = new JdkWebSocketSession(
                    new JdkWebsocketConnector(), new SdkRuntimeWebsocketEndpoint(client),
                    sdkRuntimeOptionsWithProgress(),
                    URI.create("ws://localhost/test"), new JdkWebsocketConnector.CapturedHandshakeResponse(),
                    Runnable::run, runtimeDataExecutor);
            WebSocket webSocket = mock(WebSocket.class);
            WebSocket.Listener listener = session.createListener();
            listener.onOpen(webSocket);
            byte[] response = WebSocketTransportCodecs.json(AbstractWebsocketClient.defaultObjectMapper)
                    .encode(new VoidResult(1L));

            listener.onBinary(webSocket, ByteBuffer.wrap(response), true);
            Thread runtimeWorker = Thread.ofPlatform().start(runtimeDataExecutor::runNext);

            assertTrue(client.resultHandlingStarted.await(1, TimeUnit.SECONDS));
            JdkWebSocketSession.RuntimeDataState handlingState = session.runtimeDataState();
            assertEquals(1, handlingState.retainedMessages());
            assertEquals(1, handlingState.inFlightMessages() + handlingState.admittedMessages(),
                         "The retained message may still be transitioning from decode to functional dispatch");

            client.allowResultHandlingToFinish.countDown();
            assertTrue(runtimeWorker.join(Duration.ofSeconds(1)));

            assertTrue(client.runtimeMessageCompleted.await(1, TimeUnit.SECONDS));
            assertEquals(0, session.runtimeDataState().retainedMessages());
        }
    }

    @Test
    void dataFragmentDeliveredAtFullRuntimeBoundIsDeferredWithoutReassembly() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        for (int i = 0; i < JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }

        JdkWebSocketSession.RuntimeDataState fullState = session.runtimeDataState();
        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{9}), false).toCompletableFuture();

        assertFalse(deferred.isDone());
        assertRetainedStateUnchanged(fullState, session.runtimeDataState(), 1L);
        assertNull(reportedError.get());
        assertTrue(session.isOpen());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertTrue(session.runtimeDataState().retainedMessages()
                   < JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES);
        runtimeDataExecutor.runAll();
        assertEquals(1, session.runtimeDataState().retainedMessages());
        assertEquals(1L, session.runtimeDataState().retainedBytes());
        assertNull(reportedError.get());
    }

    @Test
    void completeMessageDeliveredAtFullRuntimeBoundIsDeferredWithoutPayloadCopy() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        for (int i = 0; i < JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES; i++) {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{(byte) i}), true);
        }
        ByteBuffer rejectedPayload = ByteBuffer.allocate(4 * 1024 * 1024);
        ThreadMXBean allocationBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(allocationBean.isThreadAllocatedMemorySupported());
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean.getThreadAllocatedBytes(threadId);

        CompletableFuture<?> deferred = listener.onBinary(webSocket, rejectedPayload, true).toCompletableFuture();

        long allocatedBytes = allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertFalse(deferred.isDone());
        assertEquals(rejectedPayload.remaining(), session.runtimeDataState().deferredFrameBytes());
        assertTrue(allocatedBytes < rejectedPayload.capacity() / 2,
                   () -> "Deferred payload allocated " + allocatedBytes + " bytes before admission");
        assertNull(reportedError.get());
        assertTrue(session.isOpen());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertTrue(session.runtimeDataState().retainedMessages()
                   < JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_MESSAGES);
        runtimeDataExecutor.runAll();
        assertEquals(0, session.runtimeDataState().retainedMessages());
        assertNull(reportedError.get());
    }

    @Test
    void fragmentContinuationBeyondRetainedByteBoundWaitsForCapacityBeforeReassembly() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onBinary(webSocket,
                          ByteBuffer.wrap(new byte[Math.toIntExact(
                                  JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES - 1)]), false);

        assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES,
                     session.runtimeDataState().retainedBytes());
        JdkWebSocketSession.RuntimeDataState fullState = session.runtimeDataState();
        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{2}), false).toCompletableFuture();

        assertFalse(deferred.isDone());
        assertRetainedStateUnchanged(fullState, session.runtimeDataState(), 1L);
        assertNull(reportedError.get());
        assertTrue(session.isOpen());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertEquals(1, session.runtimeDataState().retainedMessages());
        assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES,
                     session.runtimeDataState().retainedBytes());
    }

    @Test
    void peerCloseWaitsForAllParallelRuntimeMessagesDespiteReverseCompletion() throws Exception {
        ExecutorService runtimeDataExecutor = Executors.newFixedThreadPool(
                JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
        CountDownLatch processingStarted = new CountDownLatch(JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch allowLaterMessagesToFinish = new CountDownLatch(1);
        CountDownLatch laterMessagesFinished = new CountDownLatch(2);
        CountDownLatch processingFinished = new CountDownLatch(JdkWebSocketSession.DEFAULT_MAX_CONCURRENT_RUNTIME_MESSAGES);
        List<String> callbacks = Collections.synchronizedList(new ArrayList<>());
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                processingStarted.countDown();
                try {
                    (bytes[0] == 1 ? allowFirstToFinish : allowLaterMessagesToFinish).await();
                    callbacks.add("binary-" + bytes[0]);
                    if (bytes[0] != 1) {
                        laterMessagesFinished.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while awaiting test completion order", e);
                } finally {
                    processingFinished.countDown();
                }
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
                callbacks.add("close");
                super.onClose(session, closeReason);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        try {
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{2}), true);
            listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{3}), true);
            assertTrue(processingStarted.await(1, TimeUnit.SECONDS));
            listener.onClose(webSocket, WebsocketCloseReason.NORMAL_CLOSURE, "done");

            allowLaterMessagesToFinish.countDown();
            assertTrue(laterMessagesFinished.await(1, TimeUnit.SECONDS),
                       "Both later messages should finish while the first remains active");
            assertFalse(callbacks.contains("close"), "Peer close must remain behind every accepted message");

            allowFirstToFinish.countDown();
            assertTrue(processingFinished.await(5, TimeUnit.SECONDS));
            assertTrue(endpoint.awaitClose());
            assertEquals(4, callbacks.size());
            assertTrue(callbacks.contains("binary-1"));
            assertTrue(callbacks.contains("binary-2"));
            assertTrue(callbacks.contains("binary-3"));
            assertEquals("close", callbacks.getLast());
        } finally {
            allowLaterMessagesToFinish.countDown();
            allowFirstToFinish.countDown();
            runtimeDataExecutor.shutdownNow();
        }
    }

    @Test
    void peerCloseIsDeliveredAfterAlreadyReceivedRuntimeMessages() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        List<String> callbacks = new ArrayList<>();
        WebsocketEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                callbacks.add("binary-" + bytes[0]);
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
                callbacks.add("close");
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{2}), true);
        listener.onClose(webSocket, WebsocketCloseReason.NORMAL_CLOSURE, "done");

        assertTrue(callbacks.isEmpty(), "Peer close should not overtake runtime messages already accepted for dispatch");
        runtimeDataExecutor.runAll();
        assertEquals(List.of("binary-1", "binary-2", "close"), callbacks);
    }

    @Test
    void runtimeMessageFailureStillDeliversPeerCloseAfterReportingError() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        IllegalStateException processingFailure = new IllegalStateException("decode failed");
        List<String> callbacks = new ArrayList<>();
        WebsocketEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                callbacks.add("binary");
                throw processingFailure;
            }

            @Override
            public void onError(WebsocketSession session, Throwable error) {
                assertSame(processingFailure, error);
                callbacks.add("error");
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
                callbacks.add("close");
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onClose(webSocket, WebsocketCloseReason.NORMAL_CLOSURE, "done");
        runtimeDataExecutor.runAll();

        assertEquals(List.of("binary", "error", "close"), callbacks);
        verify(webSocket).abort();
    }

    @Test
    void localAbortStillDeliversPeerCloseThatWasAlreadyDeferred() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        List<String> callbacks = new ArrayList<>();
        WebsocketEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                callbacks.add("binary");
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
                callbacks.add("close-" + closeReason.reason());
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onClose(webSocket, WebsocketCloseReason.NORMAL_CLOSURE, "peer done");
        session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "local abort"));
        runtimeDataExecutor.runAll();

        assertEquals(List.of("close-peer done"), callbacks);
        verify(webSocket).abort();
    }

    @Test
    void localAbortDiscardsRuntimeMessagesThatHaveNotStarted() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        List<String> callbacks = new ArrayList<>();
        WebsocketEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                callbacks.add("binary-" + bytes[0]);
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
                callbacks.add("close");
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{2}), true);

        session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "shutdown"));
        runtimeDataExecutor.runAll();

        assertEquals(List.of("close"), callbacks);
        verify(webSocket).abort();
    }

    @Test
    void runtimeDataExecutorRejectionFailsSession() {
        RejectedExecutionException rejection = new RejectedExecutionException("executor saturated");
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, task -> {
                    throw rejection;
                });
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);

        JdkWebSocketSession.RuntimeDataDispatchException reported = assertInstanceOf(
                JdkWebSocketSession.RuntimeDataDispatchException.class, reportedError.get());
        assertEquals(JdkWebSocketSession.RuntimeDataDispatchException.Reason.EXECUTOR_REJECTED, reported.reason());
        assertSame(rejection, reported.getCause());
        assertFalse(session.isOpen());
        verify(webSocket).abort();
    }

    @Test
    void runtimeMessageFailureFailsSessionAndDiscardsLaterMessages() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        IllegalStateException processingFailure = new IllegalStateException("decode failed");
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        AtomicInteger processedMessages = new AtomicInteger();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                processedMessages.incrementAndGet();
                throw processingFailure;
            }

            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{2}), true);

        runtimeDataExecutor.runAll();

        assertSame(processingFailure, reportedError.get());
        assertEquals(1, processedMessages.get());
        assertFalse(session.isOpen());
        verify(webSocket).abort();
    }

    @Test
    void runtimeMessageFailureDoesNotRequestMoreIngressAfterFailure() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
                throw new IllegalStateException("decode failed");
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{2}), true);
        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{3}), true);

        runtimeDataExecutor.runAll();

        verify(webSocket, times(4)).request(1);
        verify(webSocket).abort();
    }

    @Test
    void retainedRuntimeDataBytesPauseAfterOneOversizedActiveMessage() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public void onError(WebsocketSession session, Throwable error) {
                reportedError.set(error);
            }
        };
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), endpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket,
                          ByteBuffer.wrap(new byte[Math.toIntExact(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES + 1)]),
                          true);
        CompletableFuture<?> deferred = listener.onBinary(
                webSocket, ByteBuffer.wrap(new byte[]{2}), true).toCompletableFuture();

        assertFalse(deferred.isDone());
        assertEquals(1, session.runtimeDataState().retainedMessages());
        assertEquals(JdkWebSocketSession.DEFAULT_MAX_RETAINED_RUNTIME_BYTES + 1,
                     session.runtimeDataState().retainedBytes());
        assertNull(reportedError.get());
        assertTrue(session.isOpen());
        verify(webSocket, never()).abort();

        runtimeDataExecutor.runNext();

        assertTrue(deferred.isDone());
        assertEquals(1, session.runtimeDataState().retainedMessages());
        assertEquals(1L, session.runtimeDataState().retainedBytes());
    }

    @Test
    void heavyReceiveTimingIncludesRuntimeQueueTiming() {
        ManuallyTriggeredExecutor runtimeDataExecutor = new ManuallyTriggeredExecutor();
        AtomicReference<WebsocketEndpoint.ReceiveTiming> receiveTiming = new AtomicReference<>();
        AtomicReference<SdkRuntimeWebsocketEndpoint.RuntimeDispatchTiming> runtimeTiming = new AtomicReference<>();
        AtomicReference<SdkRuntimeWebsocketEndpoint> runtimeEndpointReference = new AtomicReference<>();
        RecordingEndpoint endpoint = new RecordingEndpoint() {
            @Override
            public boolean captureReceiveTiming() {
                return true;
            }

            @Override
            public void onMessage(byte[] bytes, WebsocketSession session, ReceiveTiming frameTiming) {
                receiveTiming.set(frameTiming);
                runtimeTiming.set(runtimeEndpointReference.get().currentDispatchTiming());
            }
        };
        SdkRuntimeWebsocketEndpoint runtimeEndpoint = new SdkRuntimeWebsocketEndpoint(endpoint);
        runtimeEndpointReference.set(runtimeEndpoint);
        JdkWebSocketSession session = new JdkWebSocketSession(
                new JdkWebsocketConnector(), runtimeEndpoint, sdkRuntimeOptions(), URI.create("ws://localhost/test"),
                new JdkWebsocketConnector.CapturedHandshakeResponse(), Runnable::run, runtimeDataExecutor);
        WebSocket webSocket = mock(WebSocket.class);
        WebSocket.Listener listener = session.createListener();
        listener.onOpen(webSocket);

        listener.onBinary(webSocket, ByteBuffer.wrap(new byte[]{1}), true);
        assertNull(runtimeTiming.get());
        runtimeDataExecutor.runAll();

        assertTrue(receiveTiming.get().frameReceivedTimestamp() > 0L);
        assertTrue(runtimeTiming.get().queuedTimestamp() > 0L);
        assertTrue(runtimeTiming.get().startedTimestamp() >= runtimeTiming.get().queuedTimestamp());
        assertTrue(runtimeTiming.get().queueDuration() >= 0L);
    }

    @Test
    void sessionRespondsToServerPingWithPong() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();

            connector.connect(new RecordingEndpoint(), null, server.uri());
            server.sendFrame(true, 0x9, new byte[]{9});

            assertFrame(new Frame(0xA, new byte[]{9}), server.readFrame());
        }
    }

    @Test
    void sessionSendsBinaryAndPingsThroughNativeWebSocket() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();

            WebsocketSession session = connector.connect(
                    endpoint, new WebsocketConnectionOptions(Map.of(), Map.of(), null, List.of()), server.uri());
            session.sendBinary(ByteBuffer.wrap(new byte[]{5, 6}));
            session.sendPing(ByteBuffer.wrap(new byte[]{7}));

            assertFrame(new Frame(0x2, new byte[]{5, 6}), server.readFrame());
            assertFrame(new Frame(0x9, new byte[]{7}), server.readFrame());
        }
    }

    @Test
    void sessionSendsOnlyRemainingBufferBytesWithoutAdvancingCallerBuffer() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            WebsocketSession session = connector.connect(new RecordingEndpoint(), null, server.uri());
            ByteBuffer bytes = ByteBuffer.wrap(new byte[]{0, 5, 6, 9});
            bytes.position(1);
            bytes.limit(3);

            session.sendBinary(bytes);

            assertEquals(1, bytes.position());
            assertEquals(3, bytes.limit());
            assertFrame(new Frame(0x2, new byte[]{5, 6}), server.readFrame());
        }
    }

    @Test
    void sessionSendsLargeBinaryMessageAsOrderedFragments() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            WebsocketSession session = connector.connect(new RecordingEndpoint(), null, server.uri());

            session.sendBinaryAsync(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}), 2).get(5, TimeUnit.SECONDS);

            Frame first = server.readFrame();
            Frame second = server.readFrame();
            Frame third = server.readFrame();
            assertFalse(first.fin());
            assertEquals(0x2, first.opcode());
            assertArrayEquals(new byte[]{1, 2}, first.payload());
            assertFalse(second.fin());
            assertEquals(0x0, second.opcode());
            assertArrayEquals(new byte[]{3, 4}, second.payload());
            assertTrue(third.fin());
            assertEquals(0x0, third.opcode());
            assertArrayEquals(new byte[]{5}, third.payload());
        }
    }

    @Test
    void closeSendsCloseFrameRemovesOpenSessionAndNotifiesEndpointOnce() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();
            WebsocketSession session = connector.connect(endpoint, null, server.uri());
            WebsocketCloseReason closeReason = new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "done");

            session.close(closeReason);
            session.close(new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "ignored"));

            assertTrue(endpoint.awaitClose());
            assertFalse(session.isOpen());
            assertTrue(session.getOpenSessions().isEmpty());
            assertEquals(closeReason, endpoint.closeReason.get());
            assertEquals(1, endpoint.closeCount.get());
            assertThrows(ClosedChannelException.class, () -> session.sendBinary(ByteBuffer.wrap(new byte[]{1})));
            assertFrame(new Frame(0x8, closePayload(WebsocketCloseReason.NORMAL_CLOSURE, "done")),
                        server.readFrame());
        }
    }

    @Test
    void asynchronousCloseWaitsForPeerAfterLogicallyClosingSession() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();
            WebsocketSession session = connector.connect(endpoint, null, server.uri());
            WebsocketCloseReason closeReason =
                    new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "Ping failed");

            CompletableFuture<Void> closeHandshake = session.closeAsync(closeReason);

            assertFalse(session.isOpen());
            assertTrue(session.getOpenSessions().isEmpty());
            assertTrue(endpoint.awaitClose());
            assertFalse(closeHandshake.isDone());
            assertFrame(new Frame(0x8, closePayload(closeReason.code(), closeReason.reason())), server.readFrame());

            server.sendFrame(true, 0x8, closePayload(closeReason.code(), closeReason.reason()));

            closeHandshake.get(5, TimeUnit.SECONDS);
            assertEquals(1, endpoint.closeCount.get());
        }
    }

    @Test
    void abortRemovesOpenSessionAndNotifiesEndpointOnce() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            RecordingEndpoint endpoint = new RecordingEndpoint();
            WebsocketSession session = connector.connect(endpoint, null, server.uri());
            WebsocketCloseReason closeReason =
                    new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "abort");

            session.abort(closeReason);
            session.abort(new WebsocketCloseReason(WebsocketCloseReason.GOING_AWAY, "ignored"));

            assertTrue(endpoint.awaitClose());
            assertFalse(session.isOpen());
            assertTrue(session.getOpenSessions().isEmpty());
            assertEquals(closeReason, endpoint.closeReason.get());
            assertEquals(1, endpoint.closeCount.get());
        }
    }

    private static void assertFrame(Frame expected, Frame actual) {
        assertEquals(expected.opcode(), actual.opcode());
        assertArrayEquals(expected.payload(), actual.payload());
    }

    private static WebsocketConnectionOptions sdkRuntimeOptions() {
        return new WebsocketConnectionOptions(
                Map.of(), Map.of(JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true), null, List.of());
    }

    private static WebsocketConnectionOptions sdkRuntimeOptionsWithProgress() {
        return new WebsocketConnectionOptions(Map.of(), Map.of(
                JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true,
                JdkWebSocketSession.SDK_RUNTIME_INGRESS_PROGRESS_ENABLED_USER_PROPERTY, true), null, List.of());
    }

    private static WebsocketConnectionOptions sdkRuntimeOptions(
            int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes) {
        return new WebsocketConnectionOptions(Map.of(), Map.of(
                JdkWebSocketSession.SDK_RUNTIME_DATA_DISPATCH_USER_PROPERTY, true,
                JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_CONCURRENCY_USER_PROPERTY, maxConcurrency,
                JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_MESSAGES_USER_PROPERTY, maxRetainedMessages,
                JdkWebSocketSession.SDK_RUNTIME_DATA_MAX_RETAINED_BYTES_USER_PROPERTY, maxRetainedBytes),
                                              null, List.of());
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return payload;
    }

    private static class RecordingEndpoint implements WebsocketEndpoint {
        private final AtomicReference<WebsocketSession> session = new AtomicReference<>();
        private final AtomicReference<String> openThreadName = new AtomicReference<>();
        private final AtomicReference<byte[]> binaryMessage = new AtomicReference<>();
        private final AtomicReference<byte[]> pongMessage = new AtomicReference<>();
        private final AtomicReference<WebsocketCloseReason> closeReason = new AtomicReference<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch binaryReceived = new CountDownLatch(1);
        private final CountDownLatch pongReceived = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void onOpen(WebsocketSession session) {
            this.session.set(session);
            this.openThreadName.set(Thread.currentThread().getName());
        }

        @Override
        public void onMessage(byte[] bytes, WebsocketSession session) {
            binaryMessage.set(bytes);
            binaryReceived.countDown();
        }

        @Override
        public void onPong(ByteBuffer data, WebsocketSession session) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            pongMessage.set(bytes);
            pongReceived.countDown();
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
            this.closeReason.set(closeReason);
            closeCount.incrementAndGet();
            closed.countDown();
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
        }

        boolean awaitBinaryMessage() throws InterruptedException {
            return binaryReceived.await(1, TimeUnit.SECONDS);
        }

        boolean awaitPongMessage() throws InterruptedException {
            return pongReceived.await(1, TimeUnit.SECONDS);
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    private static class ManuallyTriggeredExecutor implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.poll();
                }
                if (task == null) {
                    return;
                }
                task.run();
            }
        }

        void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.poll();
            }
            if (task == null) {
                throw new IllegalStateException("No task is pending");
            }
            task.run();
        }

        synchronized int pendingTaskCount() {
            return tasks.size();
        }
    }

    private static class BlockingSdkRuntimeClient extends AbstractWebsocketClient {
        private final CountDownLatch binaryProcessingStarted = new CountDownLatch(1);
        private final CountDownLatch allowBinaryProcessingToFinish = new CountDownLatch(1);
        private final CountDownLatch binaryMessagesProcessed = new CountDownLatch(2);
        private final CountDownLatch secondMessageProcessed = new CountDownLatch(1);
        private final CountDownLatch pongHandled = new CountDownLatch(1);
        private final List<Integer> processedMessages = Collections.synchronizedList(new ArrayList<>());

        BlockingSdkRuntimeClient() {
            super(mock(WebsocketConnector.class), URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("test-client")
                                                      .build()),
                  false, Duration.ofSeconds(1), defaultObjectMapper, 1);
        }

        @Override
        public void onOpen(WebsocketSession session) {
            // This session is opened directly by the transport test rather than by this client's session pool.
            session.getUserProperties().put(CLIENT_SESSION_ID_USER_PROPERTY, "test-client-session");
            session.getUserProperties().put(RUNTIME_SESSION_ID_USER_PROPERTY, "test-runtime-session");
        }

        @Override
        protected void handleMessage(byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming) {
            if (bytes[0] == 1) {
                binaryProcessingStarted.countDown();
                try {
                    allowBinaryProcessingToFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while blocking runtime message processing", e);
                }
            }
            processedMessages.add((int) bytes[0]);
            if (bytes[0] == 2) {
                secondMessageProcessed.countDown();
            }
            binaryMessagesProcessed.countDown();
        }

        @Override
        protected void handlePong(WebsocketSession session) {
            pongHandled.countDown();
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
            // The directly connected transport session is explicitly aborted by the test.
        }
    }

    private static class BlockingResultCompletionClient extends AbstractWebsocketClient {
        private final CountDownLatch resultHandlingStarted = new CountDownLatch(1);
        private final CountDownLatch allowResultHandlingToFinish = new CountDownLatch(1);
        private final CountDownLatch runtimeMessageCompleted = new CountDownLatch(1);

        BlockingResultCompletionClient() {
            super(mock(WebsocketConnector.class), URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("result-completion-test-client")
                                                      .build()),
                  false, Duration.ofSeconds(1), defaultObjectMapper, 1);
        }

        @Override
        public void onOpen(WebsocketSession session) {
            session.getUserProperties().put(CLIENT_SESSION_ID_USER_PROPERTY, "test-client-session");
            session.getUserProperties().put(RUNTIME_SESSION_ID_USER_PROPERTY, "test-runtime-session");
            session.getUserProperties().put(
                    NEGOTIATED_SESSION_ID_USER_PROPERTY, "test-client-session_test-runtime-session");
            session.getUserProperties().put(SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE);
            session.getUserProperties().put(SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);
        }

        @Override
        protected void handleResult(RequestResult result, String batchId, String sessionId,
                                    WebsocketResultDiagnostics.ResultTiming timing) {
            resultHandlingStarted.countDown();
            try {
                allowResultHandlingToFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while blocking result completion", e);
            }
        }

        @Override
        void onRuntimeIngressProgress(
                WebsocketSession session, RuntimeIngressController.Progress progress, int retainedMessages,
                long sequence) {
            super.onRuntimeIngressProgress(session, progress, retainedMessages, sequence);
            if (progress == RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED) {
                runtimeMessageCompleted.countDown();
            }
        }

        @Override
        public void close() {
            allowResultHandlingToFinish.countDown();
            super.close();
        }
    }

    private static class BlockingBurstResultCompletionClient extends AbstractWebsocketClient {
        private static final int TEST_COMPLETION_CONCURRENCY = 8;
        private final CountDownLatch activeResultsBlocked = new CountDownLatch(TEST_COMPLETION_CONCURRENCY);
        private final CountDownLatch allowResultHandlingToFinish = new CountDownLatch(1);
        private final CountDownLatch allResultsHandled;
        private final CountDownLatch pongHandled = new CountDownLatch(1);
        private final Set<Long> resultsHandled = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Throwable> reportedError = new AtomicReference<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        BlockingBurstResultCompletionClient(int resultCount) {
            super(mock(WebsocketConnector.class), URI.create("ws://localhost"),
                  WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                      .runtimeBaseUrl("ws://localhost")
                                                      .name("blocked-small-result-client")
                                                      .build()),
                  false, Duration.ofSeconds(1), defaultObjectMapper, 1);
            allResultsHandled = new CountDownLatch(resultCount);
        }

        @Override
        public void onOpen(WebsocketSession session) {
            session.getUserProperties().put(CLIENT_SESSION_ID_USER_PROPERTY, "test-client-session");
            session.getUserProperties().put(RUNTIME_SESSION_ID_USER_PROPERTY, "test-runtime-session");
            session.getUserProperties().put(
                    NEGOTIATED_SESSION_ID_USER_PROPERTY, "test-client-session_test-runtime-session");
            session.getUserProperties().put(SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.LZ4);
            session.getUserProperties().put(SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);
        }

        @Override
        protected void handleResult(RequestResult result, String batchId, String sessionId,
                                    WebsocketResultDiagnostics.ResultTiming timing) {
            activeResultsBlocked.countDown();
            try {
                allowResultHandlingToFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while blocking result completion", e);
            }
            if (resultsHandled.add(result.getRequestId())) {
                allResultsHandled.countDown();
            }
        }

        @Override
        protected void handlePong(WebsocketSession session) {
            pongHandled.countDown();
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
            reportedError.compareAndSet(null, error);
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
            closeCount.incrementAndGet();
        }

        @Override
        public void close() {
            allowResultHandlingToFinish.countDown();
            super.close();
        }
    }

    private static class CountingHttpClient extends HttpClient {
        private final AtomicInteger configurationReads = new AtomicInteger();
        private final SSLContext sslContext;

        private CountingHttpClient() throws Exception {
            this.sslContext = SSLContext.getDefault();
        }

        private int configurationReads() {
            return configurationReads.get();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            configurationReads.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            configurationReads.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            configurationReads.incrementAndGet();
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            configurationReads.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            configurationReads.incrementAndGet();
            return sslContext;
        }

        @Override
        public SSLParameters sslParameters() {
            configurationReads.incrementAndGet();
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            configurationReads.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public Version version() {
            configurationReads.incrementAndGet();
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            configurationReads.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record Frame(boolean fin, int opcode, byte[] payload) {
        private Frame(int opcode, byte[] payload) {
            this(true, opcode, payload);
        }
    }

    private static class TestWebSocketServer implements Closeable {
        private static final String ACCEPT_SUFFIX = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final ServerSocket serverSocket;
        private final CountDownLatch handshakeComplete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        private final AtomicReference<InputStream> input = new AtomicReference<>();
        private final AtomicReference<OutputStream> output = new AtomicReference<>();
        private final Handshake handshake;
        private volatile Map<String, List<String>> requestHeaders = Map.of();

        private TestWebSocketServer(ServerSocket serverSocket, Handshake handshake) {
            this.serverSocket = serverSocket;
            this.handshake = handshake;
        }

        static TestWebSocketServer start() throws IOException {
            return start(new SuccessfulHandshake());
        }

        static TestWebSocketServer start(String runtimeSessionId) throws IOException {
            return start(new SuccessfulHandshake(runtimeSessionId));
        }

        static TestWebSocketServer startDelayed(String runtimeSessionId, CountDownLatch requestsRead,
                                                CountDownLatch releaseResponses) throws IOException {
            return start(new DelayedSuccessfulHandshake(runtimeSessionId, requestsRead, releaseResponses));
        }

        static TestWebSocketServer startRejected(int statusCode) throws IOException {
            return start(new RejectedHandshake(statusCode));
        }

        private static TestWebSocketServer start(Handshake handshake) throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            TestWebSocketServer server = new TestWebSocketServer(serverSocket, handshake);
            Thread thread = new Thread(server::accept, "test-jdk-websocket-server");
            thread.setDaemon(true);
            thread.start();
            return server;
        }

        URI uri() {
            return URI.create("ws://%s:%s/runtime".formatted(
                    serverSocket.getInetAddress().getHostAddress(), serverSocket.getLocalPort()));
        }

        Map<String, List<String>> requestHeaders() throws Exception {
            awaitHandshake();
            return requestHeaders;
        }

        void sendFrame(boolean fin, int opcode, byte[] payload) throws Exception {
            awaitHandshake();
            output.get().write(createFrame(fin, opcode, payload));
            output.get().flush();
        }

        Frame readFrame() throws Exception {
            awaitHandshake();
            InputStream input = this.input.get();
            int first = readByte(input);
            int second = readByte(input);
            int length = second & 0x7F;
            if (length == 126) {
                length = readByte(input) << 8 | readByte(input);
            } else if (length == 127) {
                throw new IOException("Test server does not support 64-bit websocket frame lengths");
            }
            byte[] mask = (second & 0x80) == 0x80 ? input.readNBytes(4) : null;
            byte[] payload = input.readNBytes(length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            return new Frame((first & 0x80) == 0x80, first & 0x0F, payload);
        }

        private void awaitHandshake() throws Exception {
            assertTrue(handshakeComplete.await(2, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new IOException("Test websocket server failed", failure.get());
            }
        }

        @Override
        public void close() throws IOException {
            closeQuietly(socket.get());
            serverSocket.close();
        }

        private void accept() {
            try {
                Socket accepted = serverSocket.accept();
                accepted.setSoTimeout(2_000);
                socket.set(accepted);
                input.set(accepted.getInputStream());
                output.set(accepted.getOutputStream());
                requestHeaders = readRequestHeaders(accepted.getInputStream());
                handshake.write(accepted.getOutputStream(), requestHeaders);
                handshakeComplete.countDown();
            } catch (Throwable e) {
                failure.set(e);
                handshakeComplete.countDown();
            }
        }

        private static Map<String, List<String>> readRequestHeaders(InputStream input) throws IOException {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            List<String> lines = readHeaderLines(input);
            for (String line : lines.subList(1, lines.size())) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    headers.put(line.substring(0, separator), List.of(line.substring(separator + 1).trim()));
                }
            }
            return headers;
        }

        private static List<String> readHeaderLines(InputStream input) throws IOException {
            List<String> lines = new java.util.ArrayList<>();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) >= 0) {
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = line.toByteArray();
                    String value = new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                    if (value.isEmpty()) {
                        return lines;
                    }
                    lines.add(value);
                    line.reset();
                } else {
                    line.write(current);
                }
                previous = current;
            }
            throw new IOException("Unexpected end of stream while reading websocket handshake");
        }

        private interface Handshake {
            void write(OutputStream output, Map<String, List<String>> requestHeaders) throws Exception;
        }

        private static class SuccessfulHandshake implements Handshake {
            private final String runtimeSessionId;

            private SuccessfulHandshake() {
                this("runtime123");
            }

            private SuccessfulHandshake(String runtimeSessionId) {
                this.runtimeSessionId = runtimeSessionId;
            }

            @Override
            public void write(OutputStream output, Map<String, List<String>> requestHeaders) throws Exception {
                String key = requestHeaders.get("Sec-WebSocket-Key").getFirst();
                String response = """
                        HTTP/1.1 101 Switching Protocols\r
                        Upgrade: websocket\r
                        Connection: Upgrade\r
                        Sec-WebSocket-Accept: %s\r
                        Fluxzero-Runtime-Session-Id: %s\r
                        Fluxzero-Runtime-Version: 9.8.7\r
                        Fluxzero-Selected-Compression-Algorithm: GZIP\r
                        \r
                        """.formatted(acceptKey(key), runtimeSessionId);
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
        }

        private static class DelayedSuccessfulHandshake extends SuccessfulHandshake {
            private final CountDownLatch requestsRead;
            private final CountDownLatch releaseResponses;

            private DelayedSuccessfulHandshake(String runtimeSessionId, CountDownLatch requestsRead,
                                               CountDownLatch releaseResponses) {
                super(runtimeSessionId);
                this.requestsRead = requestsRead;
                this.releaseResponses = releaseResponses;
            }

            @Override
            public void write(OutputStream output, Map<String, List<String>> requestHeaders) throws Exception {
                requestsRead.countDown();
                assertTrue(releaseResponses.await(5, TimeUnit.SECONDS),
                           "Timed out waiting to release websocket handshake response");
                super.write(output, requestHeaders);
            }
        }

        private record RejectedHandshake(int statusCode) implements Handshake {
            @Override
            public void write(OutputStream output, Map<String, List<String>> requestHeaders) throws IOException {
                String response = """
                        HTTP/1.1 %s Rejected\r
                        Content-Length: 0\r
                        Connection: close\r
                        \r
                        """.formatted(statusCode);
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
        }

        private static String acceptKey(String key) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((key + ACCEPT_SUFFIX).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(bytes);
        }

        private static byte[] createFrame(boolean fin, int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write((fin ? 0x80 : 0) | opcode);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else {
                frame.write(126);
                frame.write(payload.length >> 8);
                frame.write(payload.length);
            }
            frame.write(payload);
            return frame.toByteArray();
        }

        private static int readByte(InputStream input) throws IOException {
            int value = input.read();
            if (value < 0) {
                throw new IOException("Unexpected end of websocket frame");
            }
            return value;
        }

        private static void closeQuietly(Socket socket) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
