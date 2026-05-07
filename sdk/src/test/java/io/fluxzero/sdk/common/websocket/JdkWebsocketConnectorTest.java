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

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkWebsocketConnectorTest {

    @Test
    void defaultClientConnectorUsesJdkWebsocketConnector() {
        assertInstanceOf(JdkWebsocketConnector.class, AbstractWebsocketClient.defaultWebsocketConnector);
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
    void connectFailsAndRemovesOpenSessionWhenEndpointOpenCallbackFails() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.start()) {
            JdkWebsocketConnector connector = new JdkWebsocketConnector();
            AtomicReference<Throwable> error = new AtomicReference<>();
            WebsocketEndpoint endpoint = new RecordingEndpoint() {
                @Override
                public void onOpen(WebsocketSession session) {
                    super.onOpen(session);
                    throw new IllegalStateException("boom");
                }

                @Override
                public void onError(WebsocketSession session, Throwable throwable) {
                    error.set(throwable);
                }
            };

            IOException exception = assertThrows(IOException.class, () -> connector.connect(endpoint, null, server.uri()));

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

            connector.connect(endpoint, new WebsocketConnectionOptions(Map.of(), Map.of(), null, List.of()),
                              server.uri());
            server.sendFrame(false, 0x2, new byte[]{1});
            server.sendFrame(true, 0x0, new byte[]{2, 3});
            server.sendFrame(true, 0xA, new byte[]{4});

            assertTrue(endpoint.awaitBinaryMessage());
            assertTrue(endpoint.awaitPongMessage());
            assertArrayEquals(new byte[]{1, 2, 3}, endpoint.binaryMessage.get());
            assertArrayEquals(new byte[]{4}, endpoint.pongMessage.get());
        }
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

    private record Frame(int opcode, byte[] payload) {
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
            return new Frame(first & 0x0F, payload);
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
            @Override
            public void write(OutputStream output, Map<String, List<String>> requestHeaders) throws Exception {
                String key = requestHeaders.get("Sec-WebSocket-Key").getFirst();
                String response = """
                        HTTP/1.1 101 Switching Protocols\r
                        Upgrade: websocket\r
                        Connection: Upgrade\r
                        Sec-WebSocket-Accept: %s\r
                        Fluxzero-Runtime-Session-Id: runtime123\r
                        Fluxzero-Runtime-Version: 9.8.7\r
                        Fluxzero-Selected-Compression-Algorithm: GZIP\r
                        \r
                        """.formatted(acceptKey(key));
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
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
