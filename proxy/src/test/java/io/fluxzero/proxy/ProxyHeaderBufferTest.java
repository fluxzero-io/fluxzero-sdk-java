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

import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.fluxzero.proxy.ProxyServer.RESPONSE_HEADER_BUFFER_SIZE_PROPERTY;
import static io.fluxzero.proxy.ProxyServer.USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("Proxy startup properties")
class ProxyHeaderBufferTest {
    @Test
    void defaultUsesEightKiBAndPreservesTheMaximum() throws Exception {
        String previous = System.getProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
        try {
            System.clearProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
            try (var support = new HeaderBufferTestSupport(true, true)) {
                var pool = (HeaderBufferPool) support.pool;
                try (var client = client(HttpClient.Version.HTTP_1_1)) {
                    assertEquals(200, client.send(HttpRequest.newBuilder(
                                    URI.create(support.url("/proxy/health"))).build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode());
                }
                assertEquals(1, pool.sends(ProxyServer.DEFAULT_RESPONSE_HEADER_BUFFER_SIZE));
                assertEquals(0, pool.sends(ProxyServer.DEFAULT_MAX_HEADER_SIZE));
                assertTrue(raw(support, "/buffer", "X-Header-Bytes: 1048577\r\n").startsWith("HTTP/1.1 500"));
            }
        } finally { restore(previous); }
    }

    @Test
    void preservesEffectiveMaximumAndExplicitLegacyBuffer() {
        for (int maximum : new int[]{1024, 8192, 16384, 32768, 1048576}) {
            HttpConfiguration legacy = new HttpConfiguration();
            legacy.setResponseHeaderSize(maximum);
            HttpConfiguration actual = new HttpConfiguration();
            ProxyServer.configureResponseHeaders(actual, maximum, null);
            assertEquals(Math.min(ProxyServer.DEFAULT_RESPONSE_HEADER_BUFFER_SIZE,
                                  legacy.getMaxResponseHeaderSize()), actual.getResponseHeaderSize());
            assertEquals(legacy.getMaxResponseHeaderSize(), actual.getMaxResponseHeaderSize());
            ProxyServer.configureResponseHeaders(actual, maximum, maximum);
            assertEquals(maximum, actual.getResponseHeaderSize());
            assertEquals(legacy.getMaxResponseHeaderSize(), actual.getMaxResponseHeaderSize());
            ProxyServer.configureResponseHeaders(actual, maximum, 512);
            assertEquals(512, actual.getResponseHeaderSize());
            assertEquals(legacy.getMaxResponseHeaderSize(), actual.getMaxResponseHeaderSize());
        }
    }

    @Test
    void outputBuffersCanBeMovedToTheHeap() throws Exception {
        String previous = System.getProperty(USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY);
        try {
            System.setProperty(USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY, "false");
            try (var support = new HeaderBufferTestSupport(true, true);
                 var client = client(HttpClient.Version.HTTP_1_1)) {
                assertEquals(200, client.send(HttpRequest.newBuilder(
                                URI.create(support.url("/proxy/health"))).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode());
                var pool = (HeaderBufferPool) support.pool;
                assertEquals(1, pool.sends(ProxyServer.DEFAULT_RESPONSE_HEADER_BUFFER_SIZE, false));
                assertEquals(0, pool.sends(ProxyServer.DEFAULT_RESPONSE_HEADER_BUFFER_SIZE, true));
                assertTrue(raw(support, "/buffer", "X-Header-Bytes: 9000\r\n").startsWith("HTTP/1.1 200"));
                assertEquals(1, pool.sends(ProxyServer.DEFAULT_MAX_HEADER_SIZE, false));
                assertEquals(0, pool.sends(ProxyServer.DEFAULT_MAX_HEADER_SIZE, true));

                var connector = (ServerConnector) support.server.getConnectors()[0];
                assertFalse(connector.getConnectionFactory(HttpConnectionFactory.class)
                                    .getHttpConfiguration().isUseOutputDirectByteBuffers());
                assertFalse(connector.getConnectionFactory(HTTP2CServerConnectionFactory.class)
                                    .isUseOutputDirectByteBuffers());
            }
        } finally {
            if (previous == null) { System.clearProperty(USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY); }
            else { System.setProperty(USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY, previous); }
        }
    }

    @Test
    void rejectsInvalidBufferSizesAtStartup() {
        String previous = System.getProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
        try {
            for (String value : List.of("0", "-1", "1048577", "invalid")) {
                System.setProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY, value);
                assertThrows(IllegalArgumentException.class, () -> new HeaderBufferTestSupport(false, true));
            }
        } finally {
            restore(previous);
        }
    }

    @Test
    void smallHeadersAndLargeBodiesDoNotGrowButLargeHeadersDo() throws Exception {
        withSmallBuffer(() -> {
            HeaderBufferPool pool;
            try (var support = new HeaderBufferTestSupport(true, true);
                 var client = client(HttpClient.Version.HTTP_1_1)) {
                pool = (HeaderBufferPool) support.pool;
                for (String path : List.of("/proxy/health", "/proxy/ready")) {
                    var response = client.send(HttpRequest.newBuilder(URI.create(support.url(path))).build(),
                                               HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertEquals(path.endsWith("health") ? "Healthy" : "Ready", response.body());
                }
                assertEquals(2, pool.sends(8192));
                assertEquals(0, pool.sends(1048576));
                assertEquals(2 * 1024 * 1024, send(client, support, 10, true).body().length);
                assertEquals(0, pool.sends(1048576));
                for (int bytes : new int[]{8000, 9000, 65000}) {
                    var response = send(client, support, bytes, false);
                    assertEquals(200, response.statusCode());
                    assertEquals(bytes, response.headers().firstValue("X-Padding").orElseThrow().length());
                    assertArrayEquals(new byte[]{'o', 'k'}, response.body());
                }
                String nearMax = raw(support, "/buffer", "X-Header-Bytes: 1048000\r\n");
                assertTrue(nearMax.startsWith("HTTP/1.1 200"));
                assertTrue(nearMax.contains("h".repeat(1048000)));
                assertEquals(3, pool.sends(1048576));
                assertTrue(raw(support, "/buffer", "X-Header-Bytes: 1048577\r\n").startsWith("HTTP/1.1 500"));
                assertTrue(raw(support, "/proxy/health", "X-Oversized: " + "x".repeat(1048577) + "\r\n")
                                   .startsWith("HTTP/1.1 431"));
            }
            assertEquals(0, pool.outstanding(), "All acquired buffers must be released on shutdown");
        });
    }

    @Test
    void http2AndConcurrentResponsesPreserveHeaders() throws Exception {
        withSmallBuffer(() -> {
            try (var support = new HeaderBufferTestSupport(true, true);
                 var client = client(HttpClient.Version.HTTP_2)) {
                send(client, support, 1, false); // h2c upgrade
                var responses = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
                for (int i = 0; i < 12; i++) {
                    responses.add(client.sendAsync(request(support, i % 2 == 0 ? 9000 : 100, false),
                                                   HttpResponse.BodyHandlers.ofByteArray()));
                }
                for (var future : responses) {
                    var response = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(HttpClient.Version.HTTP_2, response.version());
                    assertEquals(200, response.statusCode());
                    int expectedBytes = responses.indexOf(future) % 2 == 0 ? 9000 : 100;
                    assertEquals("h".repeat(expectedBytes), response.headers().firstValue("X-Padding").orElseThrow());
                    assertArrayEquals(new byte[]{'o', 'k'}, response.body());
                }
                assertEquals(0, ((HeaderBufferPool) support.pool).sends(1048576));
            }
        });
    }

    @Test
    void notReadyAndConnectionCloseUseSmallBuffers() throws Exception {
        withSmallBuffer(() -> {
            try (var support = new HeaderBufferTestSupport(true, false)) {
                for (String path : List.of("/proxy/health", "/proxy/ready")) {
                    try (Socket socket = new Socket("127.0.0.1", support.proxy.getPort())) {
                        socket.setSoTimeout(10000);
                        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                        assertTrue(response.startsWith("HTTP/1.1 " + (path.endsWith("ready") ? 503 : 200)));
                    }
                }
                assertEquals(0, ((HeaderBufferPool) support.pool).sends(1048576));
            }
        });
    }

    @Test
    void http10AndHttp11KeepAliveSurviveGrowthAndNextRequestCloses() throws Exception {
        withSmallBuffer(() -> {
            try (var support = new HeaderBufferTestSupport(true, true)) {
                for (String version : List.of("HTTP/1.0", "HTTP/1.1")) {
                    try (Socket socket = new Socket("127.0.0.1", support.proxy.getPort())) {
                        socket.setSoTimeout(3000);
                        var input = new java.io.BufferedInputStream(socket.getInputStream());
                        socket.getOutputStream().write(("GET /buffer " + version + "\r\nHost: localhost\r\n"
                                + "X-Header-Bytes: 9000\r\nConnection: keep-alive\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        String first = framedResponse(input);
                        assertTrue(first.contains("200 OK"));
                        assertTrue(first.contains("h".repeat(9000)));
                        socket.getOutputStream().write(("GET /proxy/health " + version + "\r\nHost: localhost\r\n"
                                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        assertTrue(framedResponse(input).endsWith("Healthy"));
                        assertEquals(-1, input.read());
                    }
                }
            }
        });
    }

    @Test
    void smallConfiguredMaximumRetainsLegacyResponseCeiling() throws Exception {
        String previousMax = System.getProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY);
        String previousInitial = System.getProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
        try {
            System.setProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY, "1024");
            System.setProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY, "512");
            try (var support = new HeaderBufferTestSupport(true, true)) {
                assertTrue(raw(support, "/buffer", "X-Header-Bytes: 9000\r\n").startsWith("HTTP/1.1 200"));
                assertTrue(raw(support, "/buffer", "X-Header-Bytes: 17000\r\n").startsWith("HTTP/1.1 500"));
                assertTrue(raw(support, "/proxy/health", "X-Padding: " + "x".repeat(1024) + "\r\n")
                                   .startsWith("HTTP/1.1 431"));
            }
        } finally {
            if (previousMax == null) { System.clearProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY); }
            else { System.setProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY, previousMax); }
            restore(previousInitial);
        }
    }

    @Test
    void http2RetainsCustomResponseLimit() throws Exception {
        String previousMax = System.getProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY);
        String previousInitial = System.getProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
        try {
            System.setProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY, "16384");
            for (String initial : new String[]{null, "512"}) {
                restore(initial);
                try (var support = new HeaderBufferTestSupport(false, true);
                     var client = client(HttpClient.Version.HTTP_2)) {
                    var accepted = send(client, support, 9000, false);
                    assertEquals(HttpClient.Version.HTTP_2, accepted.version());
                    assertEquals(200, accepted.statusCode());
                    assertEquals(9000, accepted.headers().firstValue("X-Padding").orElseThrow().length());
                    var rejected = assertThrows(java.io.IOException.class,
                                                () -> send(client, support, 17000, false));
                    assertTrue(rejected.getMessage().contains("RST_STREAM"));
                }
            }
        } finally {
            if (previousMax == null) { System.clearProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY); }
            else { System.setProperty(ProxyServer.MAX_HEADER_SIZE_PROPERTY, previousMax); }
            restore(previousInitial);
        }
    }

    @Test
    void benchmarkCleanupPreservesIndicesWithinOneMillisecond() {
        try (var support = new HeaderBufferTestSupport(false, true)) {
            support.fluxzero.withClock(java.time.Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC));
            support.fluxzero.apply(fc -> {
                var store = new HeaderBufferTestSupport.DiscardableStore(io.fluxzero.common.MessageType.WEBRESPONSE);
                var first = new io.fluxzero.common.api.SerializedMessage(
                        new io.fluxzero.common.api.Data<>(new byte[0], "test", 0),
                        io.fluxzero.common.api.Metadata.empty(), "first", 0L);
                var second = new io.fluxzero.common.api.SerializedMessage(
                        new io.fluxzero.common.api.Data<>(new byte[0], "test", 0),
                        io.fluxzero.common.api.Metadata.empty(), "second", 0L);
                store.append(List.of(first)).join();
                store.discardCompleted();
                assertTrue(store.getBatch(null, 10).isEmpty());
                store.append(List.of(second)).join();
                assertTrue(second.getIndex() > first.getIndex());
                assertEquals(List.of(second), store.getBatch(first.getIndex(), 10));
                return null;
            });
        }
    }

    private static String framedResponse(java.io.InputStream input) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        int tail = 0;
        while (tail != 0x0d0a0d0a) {
            int b = input.read();
            if (b == -1) { throw new java.io.EOFException("Incomplete response headers"); }
            bytes.write(b);
            tail = (tail << 8) | b;
        }
        String headers = bytes.toString(StandardCharsets.US_ASCII);
        var length = java.util.regex.Pattern.compile("(?im)^Content-Length: (\\d+)").matcher(headers);
        assertTrue(length.find(), headers);
        int count = Integer.parseInt(length.group(1));
        byte[] body = input.readNBytes(count);
        assertEquals(count, body.length);
        return headers + new String(body, StandardCharsets.US_ASCII);
    }

    @Test
    void earlyResponseToExpectContinueClosesAfterGrowth() throws Exception {
        String previous = System.getProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
        try {
            System.setProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY, "1");
            try (var support = new HeaderBufferTestSupport(true, true)) {
                assertTrue(raw(support, "/proxy/health", "Expect: 100-continue\r\nContent-Length: 1\r\n",
                               "HTTP/1.1", "").startsWith("HTTP/1.1 200"));
            }
        } finally { restore(previous); }
    }

    static String raw(HeaderBufferTestSupport support, String path, String headers) throws Exception {
        return raw(support, path, headers, "HTTP/1.1", "Connection: close\r\n");
    }

    static String raw(HeaderBufferTestSupport support, String path, String headers, String version,
                      String connection) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", support.proxy.getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET " + path + " " + version + "\r\nHost: localhost\r\n"
                    + headers + connection + "\r\n").getBytes(StandardCharsets.US_ASCII));
            var bytes = new java.io.ByteArrayOutputStream();
            try {
                socket.getInputStream().transferTo(bytes);
            } catch (java.net.SocketTimeoutException e) {
                throw new AssertionError("Connection did not close; received " + bytes.size() + " bytes: "
                                         + bytes.toString(StandardCharsets.US_ASCII).substring(0, Math.min(150, bytes.size())), e);
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }
    }

    static HttpClient client(HttpClient.Version version) {
        return HttpClient.newBuilder().version(version).connectTimeout(Duration.ofSeconds(5)).build();
    }

    static HttpRequest request(HeaderBufferTestSupport support, int headerBytes, boolean large) {
        return HttpRequest.newBuilder(URI.create(support.url("/buffer"))).timeout(Duration.ofSeconds(10))
                .header("X-Header-Bytes", "" + headerBytes).header("X-Body", large ? "large" : "small").build();
    }

    static HttpResponse<byte[]> send(HttpClient client, HeaderBufferTestSupport support, int bytes, boolean large)
            throws Exception {
        return client.send(request(support, bytes, large), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void withSmallBuffer(CheckedRunnable action) throws Exception {
        String previous = System.getProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY);
        try {
            System.setProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY, "8192");
            action.run();
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) { System.clearProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY); }
        else { System.setProperty(RESPONSE_HEADER_BUFFER_SIZE_PROPERTY, previous); }
    }

    @FunctionalInterface
    interface CheckedRunnable { void run() throws Exception; }
}
