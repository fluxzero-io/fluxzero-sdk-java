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

import io.fluxzero.common.api.ConnectEvent;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.VoidResult;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.Guarantee.SENT;
import static io.fluxzero.common.Guarantee.STORED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsocketEndpointTest {

    @Test
    void capabilityHeaderTakesPrecedenceOverLegacyCompressionParameter() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)))));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.GZIP, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void selectedCompressionAlgorithmTakesPrecedenceOverSupportedList() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)),
                WebsocketDeploymentUtils.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                CompressionAlgorithm.LZ4)));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("GZIP"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void legacyCompressionParameterRemainsFallbackWhenNoCapabilitiesAreSent() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void clientSdkVersionIsAddedToSessionMetadataWhenAdvertisedInHandshake() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of("cli1234567890"),
                       WebSocketCapabilities.CLIENT_SDK_VERSION_HEADER, List.of("1.2.3")),
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY,
                "srv123456789")));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of("client"), "clientName", List.of("test-client")));

        TestEndpoint endpoint = new TestEndpoint();

        assertEquals("1.2.3", endpoint.getClientSdkVersionForTest(session));
        assertEquals("1.2.3", endpoint.sessionMetadataForTest(session).getEntries().get("$clientSdkVersion"));
    }

    @Test
    void connectEventContainsSdkAndRuntimeVersion() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of("cli1234567890"),
                       WebSocketCapabilities.CLIENT_SDK_VERSION_HEADER, List.of("1.2.3")),
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY,
                "srv123456789")));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of("client"), "clientName", List.of("test-client")));

        TestEndpoint endpoint = new TestEndpoint();

        ConnectEvent event = endpoint.createConnectEventForTest(session);

        assertEquals("1.2.3", event.getSdkVersion());
        assertEquals("9.8.7", event.getRuntimeVersion());
    }

    @Test
    void duplicateStoredCommandReplaysCachedResponseWithoutInvokingHandlerAgain() {
        TestEndpoint endpoint = new TestEndpoint(Runnable::run);
        ServerWebsocketSession firstSession = session("session-1");
        ServerWebsocketSession retrySession = session("session-2");
        TestCommand command = new TestCommand(CompletableFuture.completedFuture(null), STORED);

        endpoint.onOpen(firstSession);
        endpoint.onOpen(retrySession);
        endpoint.dispatch(firstSession, command);
        endpoint.dispatch(retrySession, command);

        assertEquals(1, endpoint.invocations());
        assertVoidResult(command, endpoint.results("session-1").getFirst());
        assertVoidResult(command, endpoint.results("session-2").getFirst());
        endpoint.shutDown();
    }

    @Test
    void duplicateCommandWithoutStoredResponseIsNotCached() {
        TestEndpoint endpoint = new TestEndpoint(Runnable::run);
        ServerWebsocketSession firstSession = session("session-1");
        ServerWebsocketSession retrySession = session("session-2");
        TestCommand command = new TestCommand(CompletableFuture.completedFuture(null), SENT);

        endpoint.onOpen(firstSession);
        endpoint.onOpen(retrySession);
        endpoint.dispatch(firstSession, command);
        endpoint.dispatch(retrySession, command);

        assertEquals(2, endpoint.invocations());
        assertTrue(endpoint.results("session-1").isEmpty());
        assertTrue(endpoint.results("session-2").isEmpty());
        endpoint.shutDown();
    }

    @Test
    void queuedRequestForClosedSessionIsIgnoredWhenItRunsAfterClose() {
        Queue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        TestEndpoint endpoint = new TestEndpoint(queuedTasks::add);
        ServerWebsocketSession session = session("session-1");
        TestCommand command = new TestCommand(CompletableFuture.completedFuture(null), STORED);

        endpoint.onOpen(session);
        endpoint.dispatch(session, command);
        endpoint.onClose(session, new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "closed"));

        queuedTasks.remove().run();

        assertEquals(0, endpoint.invocations());
        assertTrue(endpoint.results("session-1").isEmpty());
        endpoint.shutDown();
    }

    @Test
    void serverPingTimeoutClosesSession() throws Exception {
        Duration originalPingTimeout = WebsocketEndpoint.pingTimeout;
        WebsocketEndpoint.pingTimeout = Duration.ofMillis(10);
        TestEndpoint endpoint = new TestEndpoint(Runnable::run);
        FakeSession session = new FakeSession("session-1");
        try {
            endpoint.onOpen(session);

            endpoint.sendPingForTest(session);

            assertTrue(session.awaitPing(1, TimeUnit.SECONDS));
            assertTrue(session.awaitClose(1, TimeUnit.SECONDS));
        } finally {
            endpoint.shutDown();
            WebsocketEndpoint.pingTimeout = originalPingTimeout;
        }
    }

    private static class TestEndpoint extends WebsocketEndpoint {
        private final AtomicInteger invocations = new AtomicInteger();
        private final Map<String, List<RequestResult>> results = new ConcurrentHashMap<>();

        TestEndpoint() {
        }

        TestEndpoint(Executor executor) {
            super(executor);
        }

        @Override
        protected String getRuntimeVersion() {
            return "9.8.7";
        }

        void dispatch(ServerWebsocketSession session, TestCommand command) {
            dispatchRequest(session, command);
        }

        int invocations() {
            return invocations.get();
        }

        List<RequestResult> results(String sessionId) {
            return results.getOrDefault(sessionId, List.of());
        }

        void sendPingForTest(ServerWebsocketSession session) {
            sendPing(session);
        }

        @Handle
        CompletableFuture<Void> handle(TestCommand command) {
            invocations.incrementAndGet();
            return command.result;
        }

        @Override
        protected void doSendResult(ServerWebsocketSession session, RequestResult result) {
            results.computeIfAbsent(session.getUserProperties().get("testSessionId").toString(),
                                    ignored -> new ArrayList<>()).add(result);
        }

        CompressionAlgorithm getCompressionAlgorithmForTest(ServerWebsocketSession session) {
            return getCompressionAlgorithm(session);
        }

        String getClientSdkVersionForTest(ServerWebsocketSession session) {
            return getClientSdkVersion(session);
        }

        io.fluxzero.common.api.Metadata sessionMetadataForTest(ServerWebsocketSession session) {
            return sessionMetadata(session);
        }

        ConnectEvent createConnectEventForTest(ServerWebsocketSession session) {
            return new ConnectEvent(getClientName(session), getClientId(session), getNegotiatedSessionId(session),
                                    toString(), getClientSdkVersion(session), getRuntimeVersion());
        }
    }

    private static ServerWebsocketSession session(String sessionId) {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of(sessionId)),
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY,
                "runtime-" + sessionId,
                "testSessionId", sessionId)));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of("client-1"), "clientName", List.of("test-client")));
        return session;
    }

    private static void assertVoidResult(TestCommand command, RequestResult result) {
        assertInstanceOf(VoidResult.class, result);
        assertEquals(command.getRequestId(), result.getRequestId());
    }

    private static class TestCommand extends Command {
        private final CompletableFuture<Void> result;
        private final io.fluxzero.common.Guarantee guarantee;

        private TestCommand(CompletableFuture<Void> result, io.fluxzero.common.Guarantee guarantee) {
            this.result = result;
            this.guarantee = guarantee;
        }

        @Override
        public io.fluxzero.common.Guarantee getGuarantee() {
            return guarantee;
        }
    }

    private static class FakeSession implements ServerWebsocketSession {
        private final CountDownLatch pingSent = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final Map<String, Object> userProperties;
        private volatile boolean open = true;

        FakeSession(String sessionId) {
            this.userProperties = new ConcurrentHashMap<>(Map.of(
                    WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                    Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of(sessionId)),
                    WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY,
                    "runtime-" + sessionId,
                    "testSessionId", sessionId));
        }

        @Override
        public URI getRequestURI() {
            return URI.create("ws://localhost/test");
        }

        @Override
        public Map<String, List<String>> getRequestParameterMap() {
            return Map.of("clientId", List.of("client-1"), "clientName", List.of("test-client"));
        }

        @Override
        public Map<String, List<String>> getRequestHeaders() {
            return Map.of();
        }

        @Override
        public Map<String, Object> getUserProperties() {
            return userProperties;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void sendBinary(ByteBuffer data) {
        }

        @Override
        public void sendPing(ByteBuffer applicationData) {
            pingSent.countDown();
        }

        @Override
        public void close() throws IOException {
            close(new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "Normal closure"));
        }

        @Override
        public void close(WebsocketCloseReason closeReason) {
            open = false;
            closed.countDown();
        }

        @Override
        public void abort(WebsocketCloseReason closeReason) {
            open = false;
            closed.countDown();
        }

        boolean awaitPing(long timeout, TimeUnit unit) throws InterruptedException {
            return pingSent.await(timeout, unit);
        }

        boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
            return closed.await(timeout, unit);
        }
    }
}
