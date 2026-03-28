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

import com.sun.net.httpserver.HttpServer;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandleSocketClose;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HandleSocketPong;
import io.fluxzero.sdk.web.PathParam;
import io.fluxzero.sdk.web.SocketSession;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.proxy.NamespaceSelector.FLUXZERO_NAMESPACE_HEADER;
import static io.fluxzero.proxy.NamespaceSelector.JWKS_URL_PROPERTY;
import static java.lang.String.format;
import static java.net.http.HttpRequest.newBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class ProxyServerTest {
    private final TestFixture testFixture = TestFixture.createAsync();
    private final ProxyRequestHandler proxyRequestHandler =
            new ProxyRequestHandler(testFixture.getFluxzero().client());
    private final ProxyServer proxyServer = ProxyServer.start(0, proxyRequestHandler);
    private final int proxyPort = proxyServer.getPort();

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    @AfterEach
    void tearDown() {
        proxyServer.cancel();
    }

    @Nested
    class Basic {

        @Test
        void healthCheck() {
            testFixture.whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/proxy/health", proxyPort))).GET()
                                    .build(), BodyHandlers.ofString()).body())
                    .expectResult("Healthy");
        }

        @Test
        void get() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/")
                        String hello(WebRequest request) {
                            return "Hello World";
                        }
                    })
                    .whenApplying(fc -> httpClient.send(newRequest().GET().build(),
                                                        BodyHandlers.ofString()).body())
                    .expectResult("Hello World");
        }

        @Test
        void post() {
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/")
                        String hello(String name) {
                            return "Hello " + name;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(newRequest().POST(BodyPublishers.ofString("Fluxzero")).build(),
                                                        BodyHandlers.ofString()).body())
                    .expectResult("Hello Fluxzero");
        }

        @Test
        void postChunkedOctetStreamUpload() {
            byte[] payload = "a".repeat(WebUtils.DEFAULT_CHUNK_SIZE + 1024).getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, @PathParam("library") String library) throws Exception {
                            return stream.readAllBytes().length + ":" + library;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "application/octet-stream")
                                    .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(payload.length + ":books");
        }

        @Test
        void postChunkedBinaryUploadAsByteArrayMatchesExpectedPayload() {
            byte[] payload = new byte[WebUtils.DEFAULT_CHUNK_SIZE + 4096];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            String expected = Base64.getEncoder().encodeToString(payload) + ":books";
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(byte[] body, @PathParam("library") String library) {
                            return Base64.getEncoder().encodeToString(body) + ":" + library;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "application/pdf")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(expected);
        }

        @Test
        void postLargePlainTextUploadIsChunkedBySize() {
            byte[] payload = "a".repeat(WebUtils.DEFAULT_CHUNK_SIZE + 1024).getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, @PathParam("library") String library) throws Exception {
                            return stream.readAllBytes().length + ":" + library;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "text/plain")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(payload.length + ":books");
        }

        @Test
        void postChunkedOctetStreamUploadBuffersSmallPublisherPartsIntoTwoRequests() {
            byte[] payload = "a".repeat((int) (WebUtils.DEFAULT_CHUNK_SIZE * 1.75)).getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, @PathParam("library") String library) throws Exception {
                            return stream.readAllBytes().length + ":" + library;
                        }
                    })
                    .whenApplying(fc -> {
                        AtomicInteger webRequestDispatches = new AtomicInteger();
                        var registration = fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                .registerMonitor(messages -> webRequestDispatches.addAndGet(messages.size()));
                        try {
                            String result = httpClient.send(
                                    newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                            .header("Content-Type", "application/octet-stream")
                                            .POST(chunkedBodyPublisher(payload, 8192))
                                            .build(),
                                    BodyHandlers.ofString()).body();
                            assertEquals(payload.length + ":books", result);
                            assertEquals(2, webRequestDispatches.get());
                            return result;
                        } finally {
                            registration.cancel();
                        }
                    })
                    .expectResult(payload.length + ":books");
        }

        @Test
        void postSmallOctetStreamUploadWithoutChunkMetadata() {
            byte[] payload = "small upload".getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, DeserializingMessage message,
                                      @PathParam("library") String library)
                                throws Exception {
                            return stream.readAllBytes().length + ":" + library + ":"
                                   + (message instanceof ChunkedDeserializingMessage);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "application/octet-stream")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .verifyResult(result -> {
                        assertTrue(result.startsWith(payload.length + ":books:"),
                                   () -> "Unexpected result: " + result);
                        assertEquals(payload.length + ":books:false", result);
                    });
        }

        @Test
        void postThresholdMinusOneUploadWithoutChunkMetadata() {
            assertChunkedFlagForPayloadSize(WebUtils.DEFAULT_CHUNK_SIZE - 1, false);
        }

        @Test
        void postExactThresholdUploadWithoutChunkMetadata() {
            assertChunkedFlagForPayloadSize(WebUtils.DEFAULT_CHUNK_SIZE, false);
        }

        @Test
        void postThresholdPlusOneUploadWithChunkMetadata() {
            assertChunkedFlagForPayloadSize(WebUtils.DEFAULT_CHUNK_SIZE + 1, true);
        }

        @Test
        void postSmallVideoUploadWithoutChunkMetadata() {
            byte[] payload = "small video".getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, DeserializingMessage message,
                                      @PathParam("library") String library) throws Exception {
                            return stream.readAllBytes().length + ":" + library + ":"
                                   + (message instanceof ChunkedDeserializingMessage);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/videos", proxyPort)))
                                    .header("Content-Type", "video/mp4")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(payload.length + ":videos:false");
        }

        @Test
        void postEmptyChunkedOctetStreamUpload() {
            byte[] payload = new byte[0];
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, @PathParam("library") String library) throws Exception {
                            return stream.readAllBytes().length + ":" + library;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "application/octet-stream")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(payload.length + ":books");
        }

        @Test
        void postChunkedOctetStreamUploadWithAsyncResponse() {
            byte[] payload = "a".repeat(WebUtils.DEFAULT_CHUNK_SIZE + 1024).getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        CompletionStage<String> upload(InputStream stream, @PathParam("library") String library) {
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    return stream.readAllBytes().length + ":" + library;
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }).completeOnTimeout("timeout", 1, TimeUnit.SECONDS);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "application/octet-stream")
                                    .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(payload.length + ":books");
        }

        @Test
        void requestChunkSizeCanBeConfiguredWithProxyProperty() {
            String previous = System.getProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY);
            System.setProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, "1024");
            ProxyRequestHandler configuredHandler = new ProxyRequestHandler(testFixture.getFluxzero().client());
            ProxyServer configuredServer = ProxyServer.start(0, configuredHandler);
            try {
                byte[] payload = "a".repeat(1500).getBytes(StandardCharsets.UTF_8);
                AtomicInteger webRequestDispatches = new AtomicInteger();
                var registration = testFixture.getFluxzero().client().getGatewayClient(MessageType.WEBREQUEST)
                        .registerMonitor(messages -> webRequestDispatches.addAndGet(messages.size()));
                try {
                    testFixture.registerHandlers(new Object() {
                                @HandlePost("/upload")
                                String upload(InputStream stream) throws Exception {
                                    return String.valueOf(stream.readAllBytes().length);
                                }
                            })
                            .whenApplying(fc -> httpClient.send(
                                    newBuilder(URI.create(format("http://0.0.0.0:%s/upload", configuredServer.getPort())))
                                            .header("Content-Type", "text/plain")
                                            .POST(BodyPublishers.ofByteArray(payload))
                                            .build(),
                                    BodyHandlers.ofString()).body())
                            .expectResult(String.valueOf(payload.length));
                    assertEquals(2, webRequestDispatches.get());
                } finally {
                    registration.cancel();
                }
            } finally {
                configuredServer.cancel();
                restoreProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, previous);
            }
        }

        @Test
        void postChunkedStreamUploadPreservesExactPayloadWithSmallConfiguredChunks() {
            String previous = System.getProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY);
            System.setProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, "1024");
            ProxyRequestHandler configuredHandler = new ProxyRequestHandler(testFixture.getFluxzero().client());
            ProxyServer configuredServer = ProxyServer.start(0, configuredHandler);
            try {
                byte[] payload = new byte[10 * 1024 + 333];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (i % 251);
                }
                String expected = Base64.getEncoder().encodeToString(payload);
                testFixture.registerHandlers(new Object() {
                            @HandlePost("/upload")
                            String upload(InputStream stream) throws Exception {
                                return Base64.getEncoder().encodeToString(stream.readAllBytes());
                            }
                        })
                        .whenApplying(fc -> httpClient.send(
                                newBuilder(URI.create(format("http://0.0.0.0:%s/upload", configuredServer.getPort())))
                                        .header("Content-Type", "application/octet-stream")
                                        .POST(BodyPublishers.ofByteArray(payload))
                                        .build(),
                                BodyHandlers.ofString()).body())
                        .expectResult(expected);
            } finally {
                configuredServer.cancel();
                restoreProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, previous);
            }
        }

        @Test
        void invalidRequestChunkSizePropertyIsRejected() {
            String previous = System.getProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY);
            System.setProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, "0");
            try {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                                                              () -> new ProxyRequestHandler(
                                                                      testFixture.getFluxzero().client()));
                assertEquals(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY + " must be greater than 0",
                             error.getMessage());
            } finally {
                restoreProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, previous);
            }
        }

        @Test
        void stalledChunkedUploadTimesOut() throws Exception {
            ProxyRequestHandler timeoutHandler =
                    new ProxyRequestHandler(testFixture.getFluxzero().client(), 1024, java.time.Duration.ofMillis(200));
            ProxyServer timeoutServer = ProxyServer.start(0, timeoutHandler);
            try (Socket socket = new Socket("0.0.0.0", timeoutServer.getPort())) {
                socket.setSoTimeout(3000);
                OutputStream output = socket.getOutputStream();
                output.write(("POST /upload HTTP/1.1\r\n"
                              + "Host: localhost\r\n"
                              + "Content-Type: application/octet-stream\r\n"
                              + "Content-Length: 2048\r\n"
                              + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(new byte[1024]);
                output.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                                                                                 StandardCharsets.UTF_8));
                assertEquals("HTTP/1.1 504 Gateway Time-out", reader.readLine());
            } finally {
                timeoutServer.cancel();
            }
        }

        @Test
        void disconnectedChunkedUploadDoesNotInvokeTypedPayloadHandler() throws Exception {
            ProxyRequestHandler timeoutHandler =
                    new ProxyRequestHandler(testFixture.getFluxzero().client(), 1024, java.time.Duration.ofMillis(200));
            ProxyServer timeoutServer = ProxyServer.start(0, timeoutHandler);
            CountDownLatch handled = new CountDownLatch(1);
            try {
                testFixture.registerHandlers(new Object() {
                    @HandlePost("/upload")
                    String upload(byte[] body) {
                        handled.countDown();
                        return String.valueOf(body.length);
                    }
                });

                try (Socket socket = new Socket("0.0.0.0", timeoutServer.getPort())) {
                    OutputStream output = socket.getOutputStream();
                    output.write(("POST /upload HTTP/1.1\r\n"
                                  + "Host: localhost\r\n"
                                  + "Content-Type: application/octet-stream\r\n"
                                  + "Content-Length: 2048\r\n"
                                  + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(new byte[1024]);
                    output.flush();
                }

                assertFalse(handled.await(500, TimeUnit.MILLISECONDS),
                            "Typed payload handler should not be invoked for a disconnected chunked upload");
            } finally {
                timeoutServer.cancel();
            }
        }

        private void assertChunkedFlagForPayloadSize(int size, boolean expectedChunked) {
            byte[] payload = "a".repeat(size).getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/upload/{library}")
                        String upload(InputStream stream, DeserializingMessage message,
                                      @PathParam("library") String library) throws Exception {
                            return stream.readAllBytes().length + ":" + library + ":"
                                   + (message instanceof ChunkedDeserializingMessage);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://0.0.0.0:%s/upload/books", proxyPort)))
                                    .header("Content-Type", "text/plain")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(),
                            BodyHandlers.ofString()).body())
                    .expectResult(payload.length + ":books:" + expectedChunked);
        }

        private HttpRequest.Builder newRequest() {
            return newBuilder(baseUri());
        }

        private URI baseUri() {
            return URI.create(format("http://0.0.0.0:%s/", proxyPort));
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static HttpRequest.BodyPublisher chunkedBodyPublisher(byte[] payload, int publisherChunkSize) {
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return payload.length;
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private int offset;
                    private boolean cancelled;
                    private boolean completed;
                    private long demand;
                    private boolean draining;

                    @Override
                    public synchronized void request(long n) {
                        if (cancelled || completed || n <= 0) {
                            return;
                        }
                        demand = Math.addExact(demand, n);
                        if (draining) {
                            return;
                        }
                        draining = true;
                        try {
                            while (!cancelled && !completed && demand > 0 && offset < payload.length) {
                                demand--;
                                int start = offset;
                            int length = Math.min(publisherChunkSize, payload.length - offset);
                                offset += length;
                                subscriber.onNext(ByteBuffer.wrap(Arrays.copyOfRange(payload, start, start + length)));
                            }
                            if (!cancelled && !completed && offset >= payload.length) {
                                completed = true;
                                subscriber.onComplete();
                            }
                        } finally {
                            draining = false;
                        }
                    }

                    @Override
                    public synchronized void cancel() {
                        cancelled = true;
                    }
                });
            }
        };
    }

    @Nested
    class namespaceSwitching {


        @Test
        void getNamespaced() {
            testFixture.registerHandlers(new NamespacedHandler())
                    .whenApplying(
                            fc -> httpClient.send(newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, "test").build(),
                                                  BodyHandlers.ofString()).body())
                    .expectResult("Hello test");
        }

        @Test
        void getNamespacedWithJwt() {
            var pair = TestJwtUtil.create("test", "test_kid");
            String jwt = pair.getKey();
            String jwksResponse = pair.getValue();
            withJwksServer(jwksResponse, url ->
                    testFixture
                            .registerHandlers(new NamespacedHandler())
                            .whenApplying(fc -> httpClient.send(
                                    newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, jwt).build(),
                                    BodyHandlers.ofString()).body())
                            .expectResult("Hello test")
                            .andThen()
                            .whenApplying(fc -> httpClient.send(
                                    newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, jwt).build(),
                                    BodyHandlers.ofString()).body())
                            .expectResult("Hello test"));


        }

        @SneakyThrows
        private synchronized static void withJwksServer(String jwksResponse, ThrowingConsumer<String> task) {
            int port = TestUtils.getAvailablePort();
            var server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/jwks", exchange -> {
                exchange.sendResponseHeaders(200, jwksResponse.length());
                exchange.getResponseBody().write(jwksResponse.getBytes());
                exchange.close();
            });
            String url = format("http://0.0.0.0:%s/jwks", port);
            server.start();
            try {
                System.setProperty(JWKS_URL_PROPERTY, url);
                task.accept(url);
            } finally {
                System.clearProperty(JWKS_URL_PROPERTY);
                server.stop(0);
            }
        }

        @Consumer(name = "namespaced", namespace = "test")
        static class NamespacedHandler {
            @HandleGet("/")
            String hello() {
                return "Hello " + Tracker.current().map(Tracker::getConfiguration)
                        .map(ConsumerConfiguration::getNamespace).orElse(null);
            }
        }

        private HttpRequest.Builder newRequest() {
            return newBuilder(baseUri());
        }

        private URI baseUri() {
            return URI.create(format("http://0.0.0.0:%s/", proxyPort));
        }
    }

    @Nested
    class Websocket {
        @Test
        void openSocket() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        String hello() {
                            return "Hello World";
                        }
                    })
                    .whenApplying(openSocketAndWait())
                    .expectResult("Hello World");
        }

        @Test
        @SneakyThrows
        void protocolIsSplitAndConvertedToHeaders() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        String hello(WebRequest request) {
                            return "%s_%s".formatted(request.getHeader("X-Foo"),
                                                     request.getHeader("X-Bar"));
                        }
                    })
                    .whenApplying(openSocketAndWait("X-Foo", URLEncoder.encode(
                            "fo o", StandardCharsets.UTF_8), "X-Bar", "bar"))
                    .expectResult("fo o_bar");
        }

        @Test
        void sendMessage() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketMessage("/")
                        String hello(String name) {
                            return "Hello " + name;
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> webSocket.sendText("Fluxzero", true)))
                    .expectResult("Hello Fluxzero");
        }

        @Test
        void sendMessageViaSocketParam() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketMessage("/")
                        void hello(String name, SocketSession session) {
                            session.sendMessage("Hello " + name);
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> webSocket.sendText("Fluxzero", true)))
                    .expectResult("Hello Fluxzero");
        }

        @Test
        void sendPing() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        void open(SocketSession session) {
                            session.sendPing("ping");
                        }

                        @HandleSocketPong("/")
                        void pong(String pong, SocketSession session) {
                            session.sendMessage("got pong " + pong);
                        }
                    })
                    .whenApplying(openSocketAndWait())
                    .expectResult("got pong ping");
        }

        @Test
        void closeSocketExternally() {
            CountDownLatch socketClosed = new CountDownLatch(1);
            testFixture.registerHandlers(new Object() {
                        @HandleSocketClose("/")
                        void close(Integer reason) {
                            Fluxzero.publishEvent("ws closed with " + reason);
                            socketClosed.countDown();
                        }
                    })
                    .whenApplying(openSocketAnd(ws -> {
                        ws.sendClose(1000, "bla");
                        assertTrue(socketClosed.await(1, TimeUnit.SECONDS),
                                   "Timed out waiting for websocket close callback");
                    }))
                    .expectResult("1000")
                    .expectEvents("ws closed with 1000");
        }

        @Test
        void closeSocketFromApplication() {
            CountDownLatch socketClosed = new CountDownLatch(1);
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        void open(SocketSession session) {
                            session.close(1001);
                        }

                        @HandleSocketClose("/")
                        void close(Integer reason) {
                            log.info("ws closed with " + reason);
                            Fluxzero.publishEvent("ws closed with " + reason);
                            socketClosed.countDown();
                        }
                    })
                    .whenApplying(openSocketAnd(ws -> assertTrue(socketClosed.await(1, TimeUnit.SECONDS),
                                                                 "Timed out waiting for websocket close callback")))
                    .expectResult("1001")
                    .expectEvents("ws closed with 1001");
        }

        @Test
        void closeProxy() {
            CountDownLatch socketOpened = new CountDownLatch(1);
            CountDownLatch socketClosed = new CountDownLatch(1);
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        void open() {
                            socketOpened.countDown();
                        }

                        @HandleSocketClose("/")
                        void close(Integer code) {
                            Fluxzero.publishEvent("ws closed with " + code);
                            socketClosed.countDown();
                        }
                    })
                    .whenApplying(openSocketAnd(ws -> {
                        assertTrue(socketOpened.await(5, TimeUnit.SECONDS),
                                   "Timed out waiting for the websocket open handler");
                        proxyRequestHandler.close();
                        assertTrue(socketClosed.await(5, TimeUnit.SECONDS),
                                   "Timed out waiting for the websocket close handler");
                    }))
                    .expectEvents("ws closed with 1001");
        }

        private ThrowingFunction<Fluxzero, ?> openSocketAndWait(String... protocols) {
            return openSocketAnd(ws -> {
            }, protocols);
        }

        private ThrowingFunction<Fluxzero, ?> openSocketAnd(ThrowingConsumer<WebSocket> followUp, String... protocols) {
            return fc -> {
                CompletableFuture<String> result = new CompletableFuture<>();
                WebSocket webSocket = openSocket(result, protocols);
                followUp.accept(webSocket);
                try {
                    return result.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new AssertionError("Timed out waiting for websocket result", e);
                }
            };
        }

        @SneakyThrows
        private WebSocket openSocket(CompletableFuture<String> callback, String... protocols) {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            if (protocols.length > 0) {
                builder.subprotocols(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length));
            }
            return builder.buildAsync(baseUri(), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket1, CharSequence data, boolean last) {
                    callback.complete(String.valueOf(data));
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    callback.complete(Integer.toString(statusCode));
                    return null;
                }
            }).get();
        }

        private URI baseUri() {
            return URI.create(format("ws://0.0.0.0:%s/", proxyPort));
        }
    }

    @Nested
    class CorsTests {

        @BeforeEach
        void setUpCors() {
            testFixture.registerHandlers(new Object() {
                @HandleGet("/users")
                String users() {
                    return "ok";
                }
            });
            System.setProperty("FLUXZERO_CORS_DOMAINS", "https://app.example.com");
        }

        @AfterEach
        void tearDown() {
            System.clearProperty("FLUXZERO_CORS_DOMAINS");
        }

        @Test
        void preflightIsHandledByProxy() {
            var preflightRequest = newRequest()
                    .method("OPTIONS", BodyPublishers.noBody())
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-Impersonation, Content-Type")
                    .build();
            testFixture
                    .whenApplying(fc -> httpClient.send(preflightRequest, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(204, resp.statusCode());
                        assertEquals("https://app.example.com",
                                     resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
                        assertEquals("true",
                                     resp.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
                        assertEquals("POST", resp.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
                        assertEquals("X-Impersonation, Content-Type",
                                     resp.headers().firstValue("Access-Control-Allow-Headers").orElse(null));
                        assertEquals(String.valueOf(java.time.Duration.ofDays(1).toSeconds()),
                                     resp.headers().firstValue("Access-Control-Max-Age").orElse(null));
                        assertEquals("Origin, Access-Control-Request-Method, Access-Control-Request-Headers",
                                     resp.headers().firstValue("Vary").orElse(null));
                    });
        }

        @Test
        void corsHeadersAreAddedToRuntimeResponses() {
            var request = newRequest().GET().header("Origin", "https://app.example.com").build();
            testFixture
                    .whenApplying(fc -> httpClient.send(request, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(200, resp.statusCode());
                        assertEquals("https://app.example.com",
                                     resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
                        assertEquals("true",
                                     resp.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
                        assertEquals("ok", resp.body());
                    });
        }

        @Test
        void corsHeadersAreNotAddedForDisallowedOrigin() {
            var request = newRequest().GET().header("Origin", "https://evil.example.com").build();
            testFixture
                    .whenApplying(fc -> httpClient.send(request, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(200, resp.statusCode());
                        assertNull(resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
                        assertEquals("ok", resp.body());
                    });
        }

        private HttpRequest.Builder newRequest() {
            return newBuilder(URI.create(String.format("http://0.0.0.0:%s/users", proxyPort)));
        }

    }
}
