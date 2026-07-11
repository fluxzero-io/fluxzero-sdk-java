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
import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.web.ApiDocInfo;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleOptions;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandleSocketClose;
import io.fluxzero.sdk.web.HandleSocketHandshake;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HandleSocketPong;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.SocketSession;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.sdk.web.WebResponseGateway;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.proxy.NamespaceSelector.FLUXZERO_NAMESPACE_HEADER;
import static io.fluxzero.proxy.NamespaceSelector.JWKS_URL_PROPERTY;
import static io.fluxzero.proxy.NamespaceSelector.NAMESPACE_HEADER_MODE_PROPERTY;
import static java.lang.String.format;
import static java.net.http.HttpRequest.newBuilder;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class ProxyServerTest {
    private final TestFixture testFixture = TestFixture.createAsync();
    private final TestProxyRequestHandler proxyRequestHandler =
            new TestProxyRequestHandler(testFixture.getFluxzero().client());
    private final ProxyServer proxyServer = ProxyServer.startHttpProxyOnly(0, proxyRequestHandler);
    private final int proxyPort = proxyServer.getPort();

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    @AfterEach
    void tearDown() {
        try {
            proxyServer.cancel();
        } finally {
            httpClient.shutdownNow();
        }
    }

    @Nested
    class Basic {

        @Test
        void healthCheck() {
            testFixture.whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/proxy/health", proxyPort))).GET()
                                    .build(), BodyHandlers.ofString()).body())
                    .expectResult("Healthy");
        }

        @Test
        void readinessCheck() {
            testFixture.whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/proxy/ready", proxyPort))).GET()
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("Ready", response.body());
                    });
        }

        @Test
        void responsesDoNotExposeServerVersion() {
            testFixture.whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/proxy/health", proxyPort))).GET()
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> assertHeaderAbsent(response.headers(), "Server"));
        }

        @Test
        void jettyGeneratedErrorResponsesDoNotExposeServerVersion() throws Exception {
            String response;
            try (Socket socket = new Socket("localhost", proxyPort)) {
                socket.setSoTimeout(3000);
                OutputStream output = socket.getOutputStream();
                output.write(("GET /proxy/health HTTP/1.1\r\n"
                              + "Host: localhost\r\n"
                              + "X-Oversized: %s\r\n"
                              + "Connection: close\r\n"
                              + "\r\n").formatted("x".repeat(ProxyServer.DEFAULT_MAX_HEADER_SIZE + 1))
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            assertTrue(response.startsWith("HTTP/1.1 431"), () -> "Expected oversized headers to be rejected, got: "
                                                                   + response);
            assertRawHeaderAbsent(response, "Server");
            assertRawHeaderAbsent(response, "X-Powered-By");
        }

        @Test
        @ResourceLock("PROXY_HEALTH_ENDPOINT")
        void healthEndpointCanBeConfigured() throws Exception {
            String previousValue = System.getProperty("PROXY_HEALTH_ENDPOINT");
            ProxyServer configuredProxyServer = null;
            try {
                System.setProperty("PROXY_HEALTH_ENDPOINT", "/internal/ready");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                assertEquals("Healthy", httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/internal/ready", configuredPort))).GET()
                                .build(), BodyHandlers.ofString()).body());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty("PROXY_HEALTH_ENDPOINT", previousValue);
            }
        }

        @Test
        void get() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/")
                        String hello(WebRequest request) {
                            assertEquals(String.valueOf(ProxyRequestHandler.REQUEST_TIMEOUT.toMillis()),
                                         request.getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
                            return "Hello World";
                        }
                    })
                    .whenApplying(fc -> httpClient.send(newRequest().GET().build(),
                                                        BodyHandlers.ofString()).body())
                    .expectResult("Hello World");
        }

        @Test
        void requestPathQueryAndRepeatedHeadersAreForwarded() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/echo")
                        String echo(WebRequest request) {
                            return "%s|%s|%s".formatted(request.getPath(),
                                                        String.join(",", request.getHeaders("X-Repeat")),
                                                        request.getHeader("X-Single"));
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create("http://localhost:%s/echo?alpha=1&alpha=2&space=a%%20b"
                                                           .formatted(proxyPort)))
                                    .GET()
                                    .header("X-Repeat", "one")
                                    .header("X-Repeat", "two")
                                    .header("X-Single", "single")
                                    .build(), BodyHandlers.ofString()).body())
                    .expectResult("/echo?alpha=1&alpha=2&space=a%20b|one,two|single");
        }

        @Test
        @ResourceLock(ProxyRequestHandler.SEGMENT_HEADER_PROPERTY)
        void requestSegmentCanBeSelectedFromConfiguredHeader() throws Exception {
            String previousValue = System.getProperty(ProxyRequestHandler.SEGMENT_HEADER_PROPERTY);
            ProxyServer configuredProxyServer = null;
            var observedRequests = new CopyOnWriteArrayList<SerializedMessage>();
            var monitor = testFixture.getFluxzero().client().getGatewayClient(MessageType.WEBREQUEST)
                    .registerMonitor(observedRequests::addAll);
            try {
                System.setProperty(ProxyRequestHandler.SEGMENT_HEADER_PROPERTY, "X-Routing-Key");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                            @HandleGet("/segment/with-header")
                            String withHeader() {
                                return "routed";
                            }
                        })
                        .whenApplying(fc -> httpClient.send(newBuilder(URI.create(format(
                                                "http://localhost:%s/segment/with-header", configuredPort)))
                                                .header("X-Routing-Key", "customer-123")
                                                .GET().build(), BodyHandlers.ofString()).body())
                        .expectResult("routed");

                SerializedMessage routedRequest = observedRequest(observedRequests, "/segment/with-header");
                assertEquals(ConsistentHashing.computeSegment("customer-123"), routedRequest.getSegment());
            } finally {
                monitor.cancel();
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyRequestHandler.SEGMENT_HEADER_PROPERTY, previousValue);
            }
        }

        @Test
        void configuredSegmentHeaderLeavesExistingSegmentUnchangedWhenHeaderIsAbsent() {
            proxyRequestHandler.setSegmentHeader("X-Routing-Key");
            WebRequest request = WebRequest.builder()
                    .url("/segment/without-header")
                    .method("GET")
                    .build();
            SerializedMessage requestMessage = request.serialize(new ProxySerializer());
            requestMessage.setSegment(42);

            proxyRequestHandler.applyConfiguredSegment(request, requestMessage);

            assertEquals(42, requestMessage.getSegment());
        }

        @Test
        void statusContentTypeAndRepeatedResponseHeadersArePreserved() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/response-headers")
                        WebResponse response() {
                            return WebResponse.builder()
                                    .status(202)
                                    .contentType("text/custom")
                                    .header("X-Reply", List.of("one", "two"))
                                    .payload("accepted")
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/response-headers", proxyPort)))
                                    .GET().build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(202, response.statusCode());
                        assertEquals("accepted", response.body());
                        assertEquals("text/custom", response.headers().firstValue("Content-Type").orElse(null));
                        assertEquals(List.of("one", "two"), response.headers().allValues("X-Reply"));
                    });
        }

        @Test
        @ResourceLock(ProxyRequestHandler.BENCHMARK_TRACE_HEADERS_ENABLED_PROPERTY)
        void benchmarkTraceHeadersRequireStartupOptInAndTraceHeader() {
            String previousValue = System.getProperty(ProxyRequestHandler.BENCHMARK_TRACE_HEADERS_ENABLED_PROPERTY);
            ProxyServer disabledProxyServer = null;
            ProxyServer enabledProxyServer = null;
            try {
                System.clearProperty(ProxyRequestHandler.BENCHMARK_TRACE_HEADERS_ENABLED_PROPERTY);
                disabledProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int disabledPort = disabledProxyServer.getPort();

                System.setProperty(ProxyRequestHandler.BENCHMARK_TRACE_HEADERS_ENABLED_PROPERTY, "true");
                enabledProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int enabledPort = enabledProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                            @HandleGet("/benchmark-trace")
                            String response() {
                                return "benchmark";
                            }
                        })
                        .whenApplying(fc -> {
                            var disabledWithTrace = httpClient.send(
                                    newBuilder(URI.create(format(
                                            "http://localhost:%s/benchmark-trace", disabledPort)))
                                            .GET()
                                            .header(ProxyRequestHandler.BENCHMARK_TRACE_ID_HEADER, "trace-1")
                                            .build(), BodyHandlers.ofString());
                            var enabledWithoutTrace = httpClient.send(
                                    newBuilder(URI.create(format(
                                            "http://localhost:%s/benchmark-trace", enabledPort)))
                                            .GET().build(), BodyHandlers.ofString());
                            var enabledWithTrace = httpClient.send(
                                    newBuilder(URI.create(format(
                                            "http://localhost:%s/benchmark-trace", enabledPort)))
                                            .GET()
                                            .header(ProxyRequestHandler.BENCHMARK_TRACE_ID_HEADER, "trace-1")
                                            .build(), BodyHandlers.ofString());
                            return List.of(disabledWithTrace, enabledWithoutTrace, enabledWithTrace);
                        })
                        .verifyResult(responses -> {
                            assertEquals(200, responses.getFirst().statusCode());
                            assertEquals("benchmark", responses.getFirst().body());
                            assertNoBenchmarkTraceHeaders(responses.getFirst().headers());

                            assertEquals(200, responses.get(1).statusCode());
                            assertEquals("benchmark", responses.get(1).body());
                            assertNoBenchmarkTraceHeaders(responses.get(1).headers());

                            assertEquals(200, responses.get(2).statusCode());
                            assertEquals("benchmark", responses.get(2).body());
                            assertBenchmarkInstantHeader(
                                    responses.get(2).headers(),
                                    ProxyRequestHandler.BENCHMARK_RUNTIME_WEBRESPONSE_INDEX_HEADER);
                            Instant received = assertBenchmarkInstantHeader(
                                    responses.get(2).headers(),
                                    ProxyRequestHandler.BENCHMARK_PROXY_WEBRESPONSE_RECEIVED_HEADER);
                            Instant sendStart = assertBenchmarkInstantHeader(
                                    responses.get(2).headers(),
                                    ProxyRequestHandler.BENCHMARK_PROXY_HTTP_RESPONSE_SEND_START_HEADER);
                            assertFalse(received.isAfter(sendStart));
                        });
            } finally {
                if (enabledProxyServer != null) {
                    enabledProxyServer.cancel();
                }
                if (disabledProxyServer != null) {
                    disabledProxyServer.cancel();
                }
                restoreProperty(ProxyRequestHandler.BENCHMARK_TRACE_HEADERS_ENABLED_PROPERTY, previousValue);
            }
        }

        @Test
        void setCookieResponseHeadersRemainSeparate() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/cookies")
                        WebResponse response() {
                            return WebResponse.builder()
                                    .header("Set-Cookie", List.of("a=1; Path=/", "b=2; Path=/"))
                                    .payload("cookies")
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/cookies", proxyPort)))
                                    .GET().build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(List.of("a=1; Path=/", "b=2; Path=/"),
                                     response.headers().allValues("Set-Cookie"));
                    });
        }

        @Test
        void http2NoContentResponsesDoNotForwardHandlerContentLength() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/http2-no-content")
                        WebResponse response() {
                            return WebResponse.builder()
                                    .status(204)
                                    .header("Content-Length", "123")
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/http2-no-content", proxyPort)))
                                    .version(HttpClient.Version.HTTP_2)
                                    .GET().build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(HttpClient.Version.HTTP_2, response.version());
                        assertEquals(204, response.statusCode());
                        assertEquals("", response.body());
                        assertTrue(response.headers().firstValue("Content-Length").filter("123"::equals).isEmpty());
                    });
        }

        @Test
        void pseudoAndHopByHopResponseHeadersAreNotForwarded() {
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/filtered-response-headers")
                        WebResponse response() {
                            return WebResponse.builder()
                                    .header(":status", "599")
                                    .header(":path", "/not-a-response-header")
                                    .header("Connection", "close")
                                    .header("Keep-Alive", "timeout=5")
                                    .header("Proxy-Connection", "keep-alive")
                                    .header("TE", "trailers")
                                    .header("Trailer", "Expires")
                                    .header("Transfer-Encoding", "chunked")
                                    .header("Upgrade", "websocket")
                                    .header("Server", "backend/1.2.3")
                                    .header("X-Powered-By", "runtime")
                                    .header("X-AspNet-Version", "4.0.30319")
                                    .header("X-AspNetMvc-Version", "5.2")
                                    .header("X-Keep", "yes")
                                    .payload("ok")
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/filtered-response-headers", proxyPort)))
                                    .version(HttpClient.Version.HTTP_2)
                                    .GET().build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("ok", response.body());
                        assertEquals("yes", response.headers().firstValue("X-Keep").orElse(null));
                        assertTrue(response.headers().firstValue(":path").isEmpty());
                        assertTrue(response.headers().firstValue(":status").filter("599"::equals).isEmpty());
                        assertTrue(response.headers().firstValue("Connection").isEmpty());
                        assertTrue(response.headers().firstValue("Transfer-Encoding").isEmpty());
                        assertTrue(response.headers().firstValue("Upgrade").isEmpty());
                        assertHeaderAbsent(response.headers(), "Server");
                        assertHeaderAbsent(response.headers(), "X-Powered-By");
                        assertHeaderAbsent(response.headers(), "X-AspNet-Version");
                        assertHeaderAbsent(response.headers(), "X-AspNetMvc-Version");
                    });
        }

        @Test
        void largeRequestHeadersAreForwarded() {
            String largeHeader = "x".repeat(128 * 1024);
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/large-request-header")
                        String echo(WebRequest request) {
                            return String.valueOf(request.getHeader("X-Large").length());
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/large-request-header", proxyPort)))
                                    .GET()
                                    .header("X-Large", largeHeader)
                                    .build(), BodyHandlers.ofString()).body())
                    .expectResult(String.valueOf(largeHeader.length()));
        }

        @Test
        void largeResponseHeadersAreServed() {
            String largeHeader = "y".repeat(128 * 1024);
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/large-response-header")
                        WebResponse response() {
                            return WebResponse.builder()
                                    .header("X-Large", largeHeader)
                                    .payload("large-header")
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/large-response-header", proxyPort)))
                                    .GET().build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("large-header", response.body());
                        assertEquals(largeHeader.length(),
                                     response.headers().firstValue("X-Large").orElseThrow().length());
                    });
        }

        @Test
        void singleChunkInputStreamResponsesAreServedThroughProxy() {
            byte[] payload = "streamed through proxy".getBytes(StandardCharsets.UTF_8);
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/small-stream")
                        WebResponse stream() {
                            return WebResponse.builder()
                                    .status(200)
                                    .contentType("text/plain")
                                    .payload(new ByteArrayInputStream(payload))
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/small-stream", proxyPort)))
                                    .GET().build(), BodyHandlers.ofByteArray()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertArrayEquals(payload, response.body());
                        assertEquals("text/plain", response.headers().firstValue("Content-Type").orElse(null));
                    });
        }

        @Test
        void multiChunkInputStreamResponsesRemoveLengthHeadersAndStreamBody() {
            byte[] payload = chunkedPayload();
            testFixture.registerHandlers(new Object() {
                        @HandleGet("/stream")
                        WebResponse stream() {
                            return WebResponse.builder()
                                    .status(200)
                                    .singleValuedHeaders(Map.of(
                                            "Accept-Ranges", "bytes",
                                            "Content-Length", "1",
                                            "X-Stream", "yes"))
                                    .payload(new ByteArrayInputStream(payload))
                                    .build();
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/stream", proxyPort)))
                                    .GET().build(), BodyHandlers.ofByteArray()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertArrayEquals(payload, response.body());
                        assertEquals("yes", response.headers().firstValue("X-Stream").orElse(null));
                        assertTrue(response.headers().firstValue("Content-Length").isEmpty());
                        assertTrue(response.headers().firstValue("Accept-Ranges").isEmpty());
                    });
        }

        @Test
        @ResourceLock("FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE")
        void maxRequestBodySizeRejectsOversizedBodiesBeforeRuntime() throws Exception {
            String previousValue = System.getProperty("FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE");
            ProxyServer configuredProxyServer = null;
            AtomicInteger invocations = new AtomicInteger();
            try {
                System.setProperty("FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE", "8");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                    @HandlePost("/limited")
                    String handle(String body) {
                        invocations.incrementAndGet();
                        return body;
                    }
                });

                var response = httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/limited", configuredPort)))
                                .POST(BodyPublishers.ofString("0123456789"))
                                .build(), BodyHandlers.ofString());

                assertTrue(response.statusCode() >= 400,
                           "Expected oversized request to be rejected, got HTTP " + response.statusCode());
                assertEquals(0, invocations.get());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty("FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE", previousValue);
            }
        }

        @Test
        @ResourceLock(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY)
        @ResourceLock(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY)
        void multipartRequestBodySizeUsesMultipartLimit() throws Exception {
            String previousMaxBody = System.getProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY);
            String previousMaxMultipart = System.getProperty(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY);
            ProxyServer configuredProxyServer = null;
            AtomicInteger invocations = new AtomicInteger();
            String payload = "0123456789abcdef0123456789abcdef";
            try {
                System.setProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY, "8");
                System.setProperty(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY, "64");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                    @HandlePost("/multipart")
                    String handle(WebRequest request) {
                        invocations.incrementAndGet();
                        return String.valueOf(((byte[]) request.getPayload()).length);
                    }
                });

                var response = httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/multipart", configuredPort)))
                                .header("Content-Type", "multipart/form-data; boundary=x")
                                .POST(BodyPublishers.ofString(payload))
                                .build(), BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertEquals(String.valueOf(payload.length()), response.body());
                assertEquals(1, invocations.get());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY, previousMaxBody);
                restoreProperty(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY, previousMaxMultipart);
            }
        }

        @Test
        @ResourceLock(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY)
        @ResourceLock(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY)
        void multipartRequestBodySizeRejectsOversizedMultipartBodiesBeforeRuntime() throws Exception {
            String previousMaxBody = System.getProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY);
            String previousMaxMultipart = System.getProperty(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY);
            ProxyServer configuredProxyServer = null;
            AtomicInteger invocations = new AtomicInteger();
            try {
                System.setProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY, "64");
                System.setProperty(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY, "8");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                    @HandlePost("/multipart")
                    String handle(WebRequest request) {
                        invocations.incrementAndGet();
                        return String.valueOf(((byte[]) request.getPayload()).length);
                    }
                });

                var response = httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/multipart", configuredPort)))
                                .header("Content-Type", "multipart/form-data; boundary=x")
                                .POST(BodyPublishers.ofString("0123456789abcdef"))
                                .build(), BodyHandlers.ofString());

                assertTrue(response.statusCode() >= 400,
                           "Expected oversized multipart request to be rejected, got HTTP " + response.statusCode());
                assertEquals(0, invocations.get());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY, previousMaxBody);
                restoreProperty(ProxyServer.MAX_MULTIPART_REQUEST_BODY_SIZE_PROPERTY, previousMaxMultipart);
            }
        }

        @Test
        @ResourceLock(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY)
        void requestTimeoutCanBeConfigured() {
            String previousValue = System.getProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY);
            ProxyServer configuredProxyServer = null;
            try {
                System.setProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY, "17");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                            @HandleGet("/configured-timeout")
                            String handle(WebRequest request) {
                                assertEquals("17000",
                                             request.getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
                                return "configured";
                            }
                        })
                        .whenApplying(fc -> httpClient.send(
                                newBuilder(URI.create(format(
                                        "http://localhost:%s/configured-timeout", configuredPort)))
                                        .GET().build(), BodyHandlers.ofString()).body())
                        .expectResult("configured");
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                if (previousValue == null) {
                    System.clearProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY);
                } else {
                    System.setProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY, previousValue);
                }
            }
        }

        @Test
        @ResourceLock(ProxyServer.MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY)
        void maxInFlightWebRequestsCanOptionallyRejectExcessRequests() throws Exception {
            String previousValue = System.getProperty(ProxyServer.MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY);
            ProxyServer configuredProxyServer = null;
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger invocations = new AtomicInteger();
            try {
                assertEquals(0, ProxyServer.DEFAULT_MAX_IN_FLIGHT_WEB_REQUESTS);
                System.setProperty(ProxyServer.MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY, "1");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();

                testFixture.registerHandlers(new Object() {
                    @HandleGet("/in-flight")
                    String handle() throws Exception {
                        invocations.incrementAndGet();
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS), "Timed out waiting to release held request");
                        return "ok";
                    }
                });

                var first = httpClient.sendAsync(
                        newBuilder(URI.create(format("http://localhost:%s/in-flight", configuredPort)))
                                .GET().build(), BodyHandlers.ofString());
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Expected first request to reach the runtime handler");

                var rejected = httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/in-flight", configuredPort)))
                                .GET().build(), BodyHandlers.ofString());

                assertEquals(503, rejected.statusCode());
                assertEquals("Too many in-flight proxy requests", rejected.body());
                assertEquals(1, invocations.get());

                release.countDown();
                assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());

                var acceptedAfterRelease = httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/in-flight", configuredPort)))
                                .GET().build(), BodyHandlers.ofString());

                assertEquals(200, acceptedAfterRelease.statusCode());
                assertEquals("ok", acceptedAfterRelease.body());
                assertEquals(2, invocations.get());
            } finally {
                release.countDown();
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY, previousValue);
            }
        }

        @Test
        @ResourceLock(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY)
        void idleTimeoutCanBeConfigured() {
            String previousValue = System.getProperty(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY);
            ProxyServer configuredProxyServer = null;
            try {
                System.setProperty(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY, "1234");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));

                assertEquals(1234L, configuredProxyServer.getIdleTimeoutMillis());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY, previousValue);
            }
        }

        @Test
        @ResourceLock(ProxyServer.WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY)
        void websocketIdleTimeoutCanBeConfigured() {
            String previousValue = System.getProperty(ProxyServer.WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY);
            ProxyServer configuredProxyServer = null;
            try {
                System.setProperty(ProxyServer.WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY, "4321");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));

                assertEquals(4321L, configuredProxyServer.getWebsocketIdleTimeoutMillis());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.WEBSOCKET_IDLE_TIMEOUT_MILLIS_PROPERTY, previousValue);
            }
        }

        @Test
        @ResourceLock(ProxyServer.MAX_THREADS_PROPERTY)
        @ResourceLock(ProxyServer.MIN_THREADS_PROPERTY)
        @ResourceLock(ProxyServer.USE_VIRTUAL_THREADS_PROPERTY)
        void threadPoolCanBeConfigured() {
            String previousMaxThreads = System.getProperty(ProxyServer.MAX_THREADS_PROPERTY);
            String previousMinThreads = System.getProperty(ProxyServer.MIN_THREADS_PROPERTY);
            String previousUseVirtualThreads = System.getProperty(ProxyServer.USE_VIRTUAL_THREADS_PROPERTY);
            ProxyServer configuredProxyServer = null;
            try {
                System.setProperty(ProxyServer.MAX_THREADS_PROPERTY, "37");
                System.setProperty(ProxyServer.MIN_THREADS_PROPERTY, "3");
                System.setProperty(ProxyServer.USE_VIRTUAL_THREADS_PROPERTY, "true");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));

                assertEquals(37, configuredProxyServer.getMaxThreads());
                assertEquals(3, configuredProxyServer.getMinThreads());
                assertEquals(ObjectUtils.supportsVirtualThreadWorkers(), configuredProxyServer.isUsingVirtualThreads());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.MAX_THREADS_PROPERTY, previousMaxThreads);
                restoreProperty(ProxyServer.MIN_THREADS_PROPERTY, previousMinThreads);
                restoreProperty(ProxyServer.USE_VIRTUAL_THREADS_PROPERTY, previousUseVirtualThreads);
            }
        }

        @Test
        @ResourceLock(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY)
        void websocketCompressionAlgorithmsCanBeConfigured() {
            String previousValue = System.getProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY);
            try {
                System.setProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, "LZ4,NONE");

                assertEquals(List.of(CompressionAlgorithm.LZ4, CompressionAlgorithm.NONE),
                             ProxyServer.getConfiguredCompressionAlgorithms().orElseThrow());
            } finally {
                restoreProperty(ProxyServer.COMPRESSION_ALGORITHMS_PROPERTY, previousValue);
            }
        }

        @Test
        void openApiDocumentIsServedAsJsonDocument() {
            testFixture.registerHandlers(new OpenApiDemoHandler())
                    .whenApplying(fc -> httpClient.send(newBuilder(
                                                                    URI.create(format(
                                                                            "http://localhost:%s/openapi-demo/openapi.json",
                                                                            proxyPort)))
                                                            .GET().build(),
                                                        BodyHandlers.ofString()))
                    .expectResult(response -> response.statusCode() == 200
                                              && response.headers().firstValue("Content-Type")
                                                      .orElse("").equals("application/json")
                                              && response.body().stripLeading().startsWith("{")
                                              && response.body().contains("\"openapi\"")
                                              && !response.body().stripLeading().startsWith("\"{"));
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
        void largeRequestBodyIsChunkedAndPreservedForInputStreamHandlers() {
            enableRequestChunking(proxyRequestHandler, 17);
            byte[] payload = requestPayload(97);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunked-stream")
                        String handle(InputStream body, DeserializingMessage message, WebRequest request)
                                throws Exception {
                            assertTrue(message instanceof ChunkedDeserializingMessage);
                            assertEquals("yes", request.getHeader("X-Chunked-Test"));
                            assertArrayEquals(payload, body.readAllBytes());
                            return "stream:" + payload.length;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunked-stream", proxyPort)))
                                    .header("X-Chunked-Test", "yes")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("stream:" + payload.length, response.body());
                    });
        }

        @Test
        void configuredHeaderSelectsSegmentForEveryRequestChunk() {
            enableRequestChunking(proxyRequestHandler, 17);
            proxyRequestHandler.setSegmentHeader("X-Routing-Key");
            byte[] payload = requestPayload(53);
            var observedRequests = new CopyOnWriteArrayList<SerializedMessage>();
            var monitor = testFixture.getFluxzero().client().getGatewayClient(MessageType.WEBREQUEST)
                    .registerMonitor(observedRequests::addAll);
            try {
                testFixture.registerHandlers(new Object() {
                            @HandlePost("/chunked-segment")
                            String handle(InputStream body) throws Exception {
                                assertArrayEquals(payload, body.readAllBytes());
                                return "segmented";
                            }
                        })
                        .whenApplying(fc -> httpClient.send(
                                newBuilder(URI.create(format("http://localhost:%s/chunked-segment", proxyPort)))
                                        .header("X-Routing-Key", "customer-123")
                                        .POST(BodyPublishers.ofByteArray(payload))
                                        .build(), BodyHandlers.ofString()).body())
                        .expectResult("segmented");

                int expectedSegment = ConsistentHashing.computeSegment("customer-123");
                assertTrue(observedRequests.size() > 1);
                assertTrue(observedRequests.stream().allMatch(message ->
                        Integer.valueOf(expectedSegment).equals(message.getSegment())));
            } finally {
                monitor.cancel();
            }
        }

        @Test
        void largeRequestBodyIsAggregatedForTypedHandlers() {
            enableRequestChunking(proxyRequestHandler, 11);
            String payload = "Fluxzero request chunking over Jetty";
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunked-string")
                        String handle(String body, DeserializingMessage message) {
                            assertTrue(message instanceof ChunkedDeserializingMessage);
                            return body;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunked-string", proxyPort)))
                                    .POST(BodyPublishers.ofString(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(payload, response.body());
                    });
        }

        @Test
        void largeBinaryRequestBodyIsAggregatedForByteArrayHandlers() {
            enableRequestChunking(proxyRequestHandler, 17);
            byte[] payload = requestPayload(73);
            String expected = Base64.getEncoder().encodeToString(payload);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunked-bytes")
                        String handle(byte[] body, DeserializingMessage message) {
                            assertTrue(message instanceof ChunkedDeserializingMessage);
                            return Base64.getEncoder().encodeToString(body);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunked-bytes", proxyPort)))
                                    .header("Content-Type", "application/octet-stream")
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(expected, response.body());
                    });
        }

        @Test
        void largeRequestBodyIsAggregatedForWebRequestHandlers() {
            enableRequestChunking(proxyRequestHandler, 9);
            String payload = "webrequest payload stays usable";
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunked-webrequest")
                        String handle(WebRequest request, DeserializingMessage message) {
                            assertTrue(message instanceof ChunkedDeserializingMessage);
                            return request.getPayloadAs(String.class);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunked-webrequest", proxyPort)))
                                    .POST(BodyPublishers.ofString(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(payload, response.body());
                    });
        }

        @Test
        void unknownLengthRequestBodyIsChunkedAndPreserved() {
            enableRequestChunking(proxyRequestHandler, 13);
            byte[] payload = requestPayload(53);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/unknown-length")
                        String handle(InputStream body, DeserializingMessage message) throws Exception {
                            assertTrue(message instanceof ChunkedDeserializingMessage);
                            assertArrayEquals(payload, body.readAllBytes());
                            return "unknown:" + payload.length;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/unknown-length", proxyPort)))
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("unknown:" + payload.length, response.body());
                    });
        }

        @Test
        void unknownLengthRequestBodyBelowChunkSizeUsesRegularRequestPath() {
            enableRequestChunking(proxyRequestHandler, 64);
            byte[] payload = requestPayload(17);
            String expected = Base64.getEncoder().encodeToString(payload);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/unknown-length-small")
                        String handle(byte[] body, DeserializingMessage message) {
                            assertFalse(message instanceof ChunkedDeserializingMessage);
                            return Base64.getEncoder().encodeToString(body);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/unknown-length-small", proxyPort)))
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .header("Content-Type", "application/octet-stream")
                                    .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(expected, response.body());
                    });
        }

        @Test
        void requestBodyBelowChunkBoundaryUsesRegularRequestPath() {
            assertChunkedFlagForPayloadSize(16, false);
        }

        @Test
        void requestBodyAtChunkBoundaryUsesRegularRequestPath() {
            String payload = "exactly-sixteen!";
            enableRequestChunking(proxyRequestHandler, payload.getBytes(StandardCharsets.UTF_8).length);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunk-boundary")
                        String handle(String body, DeserializingMessage message) {
                            assertFalse(message instanceof ChunkedDeserializingMessage);
                            return body;
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunk-boundary", proxyPort)))
                                    .POST(BodyPublishers.ofString(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(payload, response.body());
                    });
        }

        @Test
        void requestBodyAboveChunkBoundaryIsChunked() {
            assertChunkedFlagForPayloadSize(18, true);
        }

        @Test
        void largeRequestBodyAtChunkMultipleUsesFinalDataChunk() {
            enableRequestChunking(proxyRequestHandler, 17);
            byte[] payload = requestPayload(34);
            AtomicInteger webRequestDispatches = new AtomicInteger();
            var registration = testFixture.getFluxzero().client().getGatewayClient(MessageType.WEBREQUEST)
                    .registerMonitor(messages -> webRequestDispatches.addAndGet(messages.size()));
            try {
                testFixture.registerHandlers(new Object() {
                            @HandlePost("/chunk-multiple")
                            String handle(InputStream body, DeserializingMessage message) throws Exception {
                                assertTrue(message instanceof ChunkedDeserializingMessage);
                                assertArrayEquals(payload, body.readAllBytes());
                                return "multiple:" + payload.length;
                            }
                        })
                        .whenApplying(fc -> httpClient.send(
                                newBuilder(URI.create(format("http://localhost:%s/chunk-multiple", proxyPort)))
                                        .POST(BodyPublishers.ofByteArray(payload))
                                        .build(), BodyHandlers.ofString()))
                        .verifyResult(response -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("multiple:" + payload.length, response.body());
                            assertEquals(2, webRequestDispatches.get());
                        });
            } finally {
                registration.cancel();
            }
        }

        @Test
        void smallPublisherPartsAreBufferedIntoRequestChunks() {
            enableRequestChunking(proxyRequestHandler, 17);
            byte[] payload = requestPayload(29);
            AtomicInteger webRequestDispatches = new AtomicInteger();
            var registration = testFixture.getFluxzero().client().getGatewayClient(MessageType.WEBREQUEST)
                    .registerMonitor(messages -> webRequestDispatches.addAndGet(messages.size()));
            try {
                testFixture.registerHandlers(new Object() {
                            @HandlePost("/chunked-small-parts")
                            String handle(InputStream body, DeserializingMessage message) throws Exception {
                                assertTrue(message instanceof ChunkedDeserializingMessage);
                                assertArrayEquals(payload, body.readAllBytes());
                                return "parts:" + payload.length;
                            }
                        })
                        .whenApplying(fc -> httpClient.send(
                                newBuilder(URI.create(format("http://localhost:%s/chunked-small-parts", proxyPort)))
                                        .POST(chunkedBodyPublisher(payload, 3))
                                        .build(), BodyHandlers.ofString()))
                        .verifyResult(response -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("parts:" + payload.length, response.body());
                            assertEquals(2, webRequestDispatches.get());
                        });
            } finally {
                registration.cancel();
            }
        }

        @Test
        void chunkedUploadCanReturnAsyncResponse() {
            enableRequestChunking(proxyRequestHandler, 17);
            byte[] payload = requestPayload(73);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunked-async")
                        CompletionStage<String> handle(InputStream body, DeserializingMessage message) {
                            assertTrue(message instanceof ChunkedDeserializingMessage);
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    return "async:" + body.readAllBytes().length;
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            });
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunked-async", proxyPort)))
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("async:" + payload.length, response.body());
                    });
        }

        @Test
        void disconnectedChunkedUploadDoesNotInvokeTypedPayloadHandler() throws Exception {
            enableRequestChunking(proxyRequestHandler, 1024);
            CountDownLatch handled = new CountDownLatch(1);
            CountDownLatch failedBeforeDispatch = new CountDownLatch(1);
            proxyRequestHandler.expectResponseFailure(failedBeforeDispatch);
            testFixture.registerHandlers(new Object() {
                @HandlePost("/disconnect-upload")
                String handle(byte[] body) {
                    handled.countDown();
                    return String.valueOf(body.length);
                }
            });

            try (Socket socket = new Socket("localhost", proxyPort)) {
                OutputStream output = socket.getOutputStream();
                output.write(("POST /disconnect-upload HTTP/1.1\r\n"
                              + "Host: localhost\r\n"
                              + "Content-Type: application/octet-stream\r\n"
                              + "Content-Length: 2048\r\n"
                              + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(new byte[1024]);
                output.flush();
            }

            assertTrue(failedBeforeDispatch.await(1, TimeUnit.SECONDS),
                       "Expected proxy to complete the disconnected upload as a failed request");
            assertEquals(1L, handled.getCount(),
                         "Typed payload handler should not be invoked for a disconnected chunked upload");
        }

        @Test
        @ResourceLock(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY)
        @ResourceLock(ProxyRequestHandler.REQUEST_CHUNKING_ENABLED_PROPERTY)
        void requestChunkSizeCanBeConfigured() throws Exception {
            String previousChunkSize = System.getProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY);
            String previousEnabled = System.getProperty(ProxyRequestHandler.REQUEST_CHUNKING_ENABLED_PROPERTY);
            ProxyServer configuredProxyServer = null;
            try {
                System.setProperty(ProxyRequestHandler.REQUEST_CHUNKING_ENABLED_PROPERTY, "true");
                System.setProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, "5");
                configuredProxyServer = ProxyServer.startHttpProxyOnly(
                        0, new ProxyRequestHandler(testFixture.getFluxzero().client()));
                int configuredPort = configuredProxyServer.getPort();
                String payload = "configured chunks";

                testFixture.registerHandlers(new Object() {
                            @HandlePost("/configured-chunks")
                            String handle(String body, DeserializingMessage message) {
                                assertTrue(message instanceof ChunkedDeserializingMessage);
                                return body;
                            }
                        })
                        .whenApplying(fc -> httpClient.send(
                                newBuilder(URI.create(format(
                                        "http://localhost:%s/configured-chunks", configuredPort)))
                                        .POST(BodyPublishers.ofString(payload))
                                        .build(), BodyHandlers.ofString()))
                        .verifyResult(response -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(payload, response.body());
                        });
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, previousChunkSize);
                restoreProperty(ProxyRequestHandler.REQUEST_CHUNKING_ENABLED_PROPERTY, previousEnabled);
            }
        }

        @Test
        @ResourceLock(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY)
        void invalidRequestChunkSizePropertyIsRejected() {
            String previousValue = System.getProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY);
            try {
                System.setProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, "0");
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                                                              () -> new ProxyRequestHandler(
                                                                      testFixture.getFluxzero().client()));
                assertEquals(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY
                             + " must be greater than 0 and at most " + Integer.MAX_VALUE, error.getMessage());
            } finally {
                restoreProperty(ProxyRequestHandler.REQUEST_CHUNK_SIZE_PROPERTY, previousValue);
            }
        }

        @Test
        @ResourceLock(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY)
        void unknownLengthChunkedRequestCannotBypassTotalBodyLimit() throws Exception {
            String previousValue = System.getProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY);
            ProxyServer configuredProxyServer = null;
            AtomicInteger invocations = new AtomicInteger();
            try {
                System.setProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY, "64");
                TestProxyRequestHandler configuredHandler =
                        new TestProxyRequestHandler(testFixture.getFluxzero().client());
                enableRequestChunking(configuredHandler, 16);
                configuredProxyServer = ProxyServer.startHttpProxyOnly(0, configuredHandler);
                int configuredPort = configuredProxyServer.getPort();
                byte[] payload = requestPayload(96);

                testFixture.registerHandlers(new Object() {
                    @HandlePost("/limited-unknown")
                    String handle(InputStream body) throws Exception {
                        invocations.incrementAndGet();
                        return String.valueOf(body.readAllBytes().length);
                    }
                });

                var response = httpClient.send(
                        newBuilder(URI.create(format("http://localhost:%s/limited-unknown", configuredPort)))
                                .version(HttpClient.Version.HTTP_1_1)
                                .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                                .build(), BodyHandlers.ofString());

                assertTrue(response.statusCode() >= 400,
                           "Expected oversized request to be rejected, got HTTP " + response.statusCode());
                assertEquals(0, invocations.get());
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.MAX_REQUEST_BODY_SIZE_PROPERTY, previousValue);
            }
        }

        @Test
        @ResourceLock(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY)
        void stalledChunkedUploadClosesWithoutInvokingTypedPayloadHandler() throws Exception {
            String previousValue = System.getProperty(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY);
            ProxyServer configuredProxyServer = null;
            CountDownLatch handled = new CountDownLatch(1);
            try {
                System.setProperty(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY, "200");
                TestProxyRequestHandler configuredHandler =
                        new TestProxyRequestHandler(testFixture.getFluxzero().client());
                enableRequestChunking(configuredHandler, 1024);
                configuredProxyServer = ProxyServer.startHttpProxyOnly(0, configuredHandler);
                int configuredPort = configuredProxyServer.getPort();
                testFixture.registerHandlers(new Object() {
                    @HandlePost("/stalled-upload")
                    String handle(byte[] body) {
                        handled.countDown();
                        return String.valueOf(body.length);
                    }
                });

                try (Socket socket = new Socket("localhost", configuredPort)) {
                    socket.setSoTimeout(3000);
                    OutputStream output = socket.getOutputStream();
                    output.write(("POST /stalled-upload HTTP/1.1\r\n"
                                  + "Host: localhost\r\n"
                                  + "Content-Type: application/octet-stream\r\n"
                                  + "Content-Length: 2048\r\n"
                                  + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(new byte[1024]);
                    output.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                                                                                     StandardCharsets.UTF_8));
                    String statusLine = reader.readLine();
                    assertTrue(statusLine == null || statusLine.startsWith("HTTP/1.1 4")
                               || statusLine.startsWith("HTTP/1.1 5"),
                               () -> "Expected stalled upload to close or fail, got: " + statusLine);
                }

                assertEquals(1L, handled.getCount(),
                             "Typed payload handler should not be invoked for a stalled chunked upload");
            } finally {
                if (configuredProxyServer != null) {
                    configuredProxyServer.cancel();
                }
                restoreProperty(ProxyServer.IDLE_TIMEOUT_MILLIS_PROPERTY, previousValue);
            }
        }

        @Test
        void cancelReleasesListeningPort() throws Exception {
            proxyServer.cancel();
            try (ServerSocket serverSocket = new ServerSocket()) {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("0.0.0.0", proxyPort));
            }
        }

        private HttpRequest.Builder newRequest() {
            return newBuilder(baseUri());
        }

        private URI baseUri() {
            return URI.create(format("http://localhost:%s/", proxyPort));
        }

        private void assertChunkedFlagForPayloadSize(int size, boolean expectedChunked) {
            enableRequestChunking(proxyRequestHandler, 17);
            byte[] payload = requestPayload(size);
            testFixture.registerHandlers(new Object() {
                        @HandlePost("/chunk-flag")
                        String handle(InputStream body, DeserializingMessage message) throws Exception {
                            return body.readAllBytes().length + ":" + (message instanceof ChunkedDeserializingMessage);
                        }
                    })
                    .whenApplying(fc -> httpClient.send(
                            newBuilder(URI.create(format("http://localhost:%s/chunk-flag", proxyPort)))
                                    .POST(BodyPublishers.ofByteArray(payload))
                                    .build(), BodyHandlers.ofString()))
                    .verifyResult(response -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(payload.length + ":" + expectedChunked, response.body());
                    });
        }
    }

    @Path("/openapi-demo")
    @ApiDocInfo(title = "Proxy OpenAPI Demo", version = "1.0.0", serveOpenApi = true)
    @ApiDoc(tags = "OpenAPI")
    static class OpenApiDemoHandler {
        @HandleGet("/items")
        @ApiDoc(summary = "List items")
        java.util.List<String> items() {
            return java.util.List.of("one", "two");
        }
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
        @ResourceLock(NAMESPACE_HEADER_MODE_PROPERTY)
        @ResourceLock(JWKS_URL_PROPERTY)
        void signedModeRejectsUnsignedNamespaceHeader() {
            String previousMode = System.getProperty(NAMESPACE_HEADER_MODE_PROPERTY);
            try {
                System.setProperty(NAMESPACE_HEADER_MODE_PROPERTY, "signed");

                testFixture.whenApplying(fc -> httpClient.send(
                                newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, "test").build(),
                                BodyHandlers.ofString()))
                        .verifyResult(response -> {
                            assertEquals(401, response.statusCode());
                            assertEquals("Namespace headers should be signed", response.body());
                        });
            } finally {
                restoreProperty(NAMESPACE_HEADER_MODE_PROPERTY, previousMode);
            }
        }

        @Test
        @ResourceLock(NAMESPACE_HEADER_MODE_PROPERTY)
        void disabledModeRejectsNamespaceHeader() {
            String previousMode = System.getProperty(NAMESPACE_HEADER_MODE_PROPERTY);
            try {
                System.setProperty(NAMESPACE_HEADER_MODE_PROPERTY, "disabled");

                testFixture.whenApplying(fc -> httpClient.send(
                                newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, "test").build(),
                                BodyHandlers.ofString()))
                        .verifyResult(response -> {
                            assertEquals(401, response.statusCode());
                            assertEquals("Namespace header is disabled", response.body());
                        });
            } finally {
                restoreProperty(NAMESPACE_HEADER_MODE_PROPERTY, previousMode);
            }
        }

        @Test
        @ResourceLock(NAMESPACE_HEADER_MODE_PROPERTY)
        @ResourceLock(JWKS_URL_PROPERTY)
        void getNamespacedWithJwt() {
            var pair = TestJwtUtil.create("test", "test_kid");
            String jwt = pair.getKey();
            String jwksResponse = pair.getValue();
            String previousMode = System.getProperty(NAMESPACE_HEADER_MODE_PROPERTY);
            try {
                System.setProperty(NAMESPACE_HEADER_MODE_PROPERTY, "signed");
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
            } finally {
                restoreProperty(NAMESPACE_HEADER_MODE_PROPERTY, previousMode);
            }

        }

        @Test
        @ResourceLock(JWKS_URL_PROPERTY)
        void signedNamespaceHeaderIsRevalidatedForRepeatedValues() {
            Instant expiresAt = Instant.now().plusSeconds(2);
            var pair = TestJwtUtil.create("test", "expiring_kid", expiresAt);
            String jwt = pair.getKey();
            String jwksResponse = pair.getValue();
            withJwksServer(jwksResponse, url ->
                    testFixture.registerHandlers(new NamespacedHandler())
                            .whenApplying(fc -> {
                                var accepted = httpClient.send(
                                        newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, jwt).build(),
                                        BodyHandlers.ofString());
                                assertEquals(200, accepted.statusCode());
                                assertEquals("Hello test", accepted.body());

                                long waitMillis = Math.max(0L,
                                                           expiresAt.plusSeconds(1).toEpochMilli()
                                                           - Instant.now().toEpochMilli());
                                Thread.sleep(waitMillis);

                                return httpClient.send(
                                        newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, jwt).build(),
                                        BodyHandlers.ofString());
                            })
                            .verifyResult(rejected -> {
                                assertEquals(401, rejected.statusCode());
                                assertEquals("JWT expired", rejected.body());
                            }));
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
            String url = format("http://localhost:%s/jwks", port);
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
            return URI.create(format("http://localhost:%s/", proxyPort));
        }
    }

    @Nested
    @Isolated
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
        void configuredHeaderSelectsSegmentForWebsocketLifecycleMessages() {
            proxyRequestHandler.setSegmentHeader("X-Routing-Key");
            var observedRequests = new CopyOnWriteArrayList<SerializedMessage>();
            var monitor = testFixture.getFluxzero().client().getGatewayClient(MessageType.WEBREQUEST)
                    .registerMonitor(observedRequests::addAll);
            try {
                testFixture.registerHandlers(new Object() {
                            @HandleSocketMessage("/")
                            String handle(String message) {
                                return message;
                            }
                        })
                        .whenApplying(openSocketAnd(
                                baseUri(), builder -> builder.header("X-Routing-Key", "customer-123"),
                                webSocket -> webSocket.sendText("handled", true)))
                        .expectResult("handled");

                int expectedSegment = ConsistentHashing.computeSegment("customer-123");
                Set<String> lifecycleMethods = Set.of(
                        HttpRequestMethod.WS_HANDSHAKE, HttpRequestMethod.WS_OPEN, HttpRequestMethod.WS_MESSAGE);
                lifecycleMethods.forEach(method -> assertTrue(observedRequests.stream().anyMatch(message ->
                        method.equals(WebRequest.getMethod(message.getMetadata())))));
                assertTrue(observedRequests.stream()
                                   .filter(message -> lifecycleMethods.contains(
                                           WebRequest.getMethod(message.getMetadata())))
                                   .allMatch(message -> Integer.valueOf(expectedSegment).equals(
                                           message.getSegment())));
            } finally {
                monitor.cancel();
            }
        }

        @Test
        void sendBinaryMessage() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketMessage("/")
                        String hello(byte[] payload) {
                            return "binary %s:%s:%s".formatted(payload.length, payload[0], payload[2]);
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> webSocket.sendBinary(
                            ByteBuffer.wrap(new byte[]{7, 8, 9}), true)))
                    .expectResult("binary 3:7:9");
        }

        @Test
        void largeTextMessagesAreAccepted() {
            String payload = "x".repeat(128 * 1024);
            testFixture.registerHandlers(new Object() {
                        @HandleSocketMessage("/")
                        String hello(String message) {
                            return "text " + message.length();
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> await(webSocket.sendText(payload, true))))
                    .expectResult("text " + payload.length());
        }

        @Test
        void largeBinaryMessagesAreAccepted() {
            byte[] payload = new byte[128 * 1024 + 3];
            Arrays.fill(payload, (byte) 5);
            payload[0] = 7;
            payload[payload.length - 1] = 9;
            testFixture.registerHandlers(new Object() {
                        @HandleSocketMessage("/")
                        String hello(byte[] message) {
                            return "binary %s:%s:%s".formatted(
                                    message.length, message[0], message[message.length - 1]);
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> webSocket.sendBinary(
                            ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS)))
                    .expectResult("binary " + payload.length + ":7:9");
        }

        @Test
        void fragmentedTextMessagesAreReassembled() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketMessage("/")
                        String hello(String message) {
                            return "Hello " + message;
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> webSocket.sendText("Flux", false)
                            .thenCompose(ignored -> webSocket.sendText("zero", true)).get(5, TimeUnit.SECONDS)))
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
        void clientPingReceivesPong() {
            CountDownLatch closed = new CountDownLatch(1);
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        String open() {
                            return "opened";
                        }

                        @HandleSocketClose("/")
                        void close() {
                            closed.countDown();
                        }
                    })
                    .whenApplying(fc -> {
                        CompletableFuture<String> pong = new CompletableFuture<>();
                        WebSocket webSocket = httpClient.newWebSocketBuilder()
                                .buildAsync(baseUri(), new WebSocket.Listener() {
                                    @Override
                                    public void onOpen(WebSocket webSocket) {
                                        webSocket.request(1);
                                    }

                                    @Override
                                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
                                                                     boolean last) {
                                        webSocket.request(1);
                                        return null;
                                    }

                                    @Override
                                    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                                        pong.complete(bufferToString(message));
                                        webSocket.request(1);
                                        return null;
                                    }
                                }).get(5, TimeUnit.SECONDS);
                        try {
                            webSocket.sendPing(ByteBuffer.wrap("client-ping".getBytes(StandardCharsets.UTF_8)))
                                    .get(5, TimeUnit.SECONDS);
                            return pong.get(5, TimeUnit.SECONDS);
                        } finally {
                            await(webSocket.sendClose(WebSocket.NORMAL_CLOSURE, ""));
                            assertTrue(closed.await(5, TimeUnit.SECONDS),
                                       "Timed out waiting for the websocket close handler");
                        }
                    })
                    .expectResult("client-ping");
        }

        @Test
        void proxySendsKeepAlivePingWithoutRuntimePongEvent() {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch runtimePong = new CountDownLatch(1);
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        String open() {
                            opened.countDown();
                            return "opened";
                        }

                        @HandleSocketPong("/")
                        void pong() {
                            runtimePong.countDown();
                        }
                    })
                    .whenApplying(fc -> {
                        proxyRequestHandler.setWebsocketKeepAlive(Duration.ofMillis(50), Duration.ofSeconds(2));
                        CompletableFuture<String> keepAlivePing = new CompletableFuture<>();
                        WebSocket webSocket = null;
                        try {
                            webSocket = httpClient.newWebSocketBuilder()
                                    .buildAsync(baseUri(), new WebSocket.Listener() {
                                        @Override
                                        public void onOpen(WebSocket webSocket) {
                                            webSocket.request(1);
                                        }

                                        @Override
                                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
                                                                         boolean last) {
                                            webSocket.request(1);
                                            return null;
                                        }

                                        @Override
                                        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                                            String payload = bufferToString(message);
                                            keepAlivePing.complete(payload);
                                            webSocket.request(1);
                                            return webSocket.sendPong(
                                                    ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)));
                                        }
                                    }).get(5, TimeUnit.SECONDS);
                            assertTrue(opened.await(5, TimeUnit.SECONDS),
                                       "Timed out waiting for the websocket open handler");
                            String payload = keepAlivePing.get(5, TimeUnit.SECONDS);
                            assertTrue(payload.startsWith(ProxyWebsocketEndpoint.keepAlivePingPrefix),
                                       "Expected proxy keep-alive ping payload, got: " + payload);
                            assertFalse(runtimePong.await(200, TimeUnit.MILLISECONDS),
                                        "Proxy keep-alive pong should not be forwarded to runtime handlers");
                            return "proxy-ping";
                        } finally {
                            if (webSocket != null) {
                                await(webSocket.sendClose(WebSocket.NORMAL_CLOSURE, ""));
                            }
                            proxyRequestHandler.setWebsocketKeepAlive(ProxyServer.getConfiguredWebsocketPingDelay(),
                                                                      ProxyServer.getConfiguredWebsocketPingTimeout());
                        }
                    })
                    .expectResult("proxy-ping");
        }

        @Test
        void selectedSubprotocolIsReturnedToClient() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        String hello() {
                            return "opened";
                        }
                    })
                    .whenApplying(openSocketAnd(webSocket -> assertEquals("fluxzero", webSocket.getSubprotocol()),
                                                "fluxzero"))
                    .expectResult("opened");
        }

        @Test
        void openSocketPreservesRequestPathQueryAndHeaders() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/socket")
                        String hello(WebRequest request) {
                            return "%s|%s".formatted(request.getPath(), request.getHeader("X-Trace"));
                        }
                    })
                    .whenApplying(openSocketAnd(baseUri("/socket?alpha=1&space=a%20b"),
                                                builder -> builder.header("X-Trace", "trace-1"),
                                                webSocket -> {
                                                }))
                    .expectResult("/socket?alpha=1&space=a%20b|trace-1");
        }

        @Test
        void openSocketUsesNamespaceHeader() {
            testFixture.registerHandlers(new NamespacedSocketHandler())
                    .whenApplying(openSocketAnd(baseUri("/namespaced"),
                                                builder -> builder.header(FLUXZERO_NAMESPACE_HEADER, "test"),
                                                webSocket -> {
                                                }))
                    .expectResult("Hello test");
        }

        @Test
        void rejectedHandshakeReturnsHttpStatusWithoutOpeningSocket() {
            testFixture.registerHandlers(new Object() {
                @HandleSocketHandshake("/")
                WebResponse reject() {
                    return WebResponse.builder().status(403).payload("forbidden").build();
                }

                @HandleSocketOpen("/")
                void open() {
                    throw new AssertionError("Rejected websocket handshakes must not open a websocket session");
                }
            });

            CompletionException exception = assertThrows(CompletionException.class, () ->
                    httpClient.newWebSocketBuilder()
                            .buildAsync(baseUri(), new WebSocket.Listener() {
                            })
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join());

            assertTrue(exception.getCause() instanceof WebSocketHandshakeException,
                       "Expected failed websocket handshake, got " + exception.getCause());
            WebSocketHandshakeException handshakeException = (WebSocketHandshakeException) exception.getCause();
            assertEquals(403, handshakeException.getResponse().statusCode());
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
                        await(ws.sendClose(1000, "bla"));
                        assertTrue(socketClosed.await(5, TimeUnit.SECONDS),
                                   "Timed out waiting for the websocket close handler");
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
                    .whenApplying(openSocketAnd(ws -> assertTrue(socketClosed.await(5, TimeUnit.SECONDS),
                                                                 "Timed out waiting for the websocket close handler")))
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

        @Consumer(name = "namespaced-websocket", namespace = "test")
        static class NamespacedSocketHandler {
            @HandleSocketOpen("/namespaced")
            String hello() {
                return "Hello " + Tracker.current().map(Tracker::getConfiguration)
                        .map(ConsumerConfiguration::getNamespace).orElse(null);
            }
        }

        private <T> T await(CompletableFuture<T> future) throws Exception {
            return future.get(5, TimeUnit.SECONDS);
        }

        private ThrowingFunction<Fluxzero, ?> openSocketAndWait(String... protocols) {
            return openSocketAnd(ws -> {
            }, protocols);
        }

        private ThrowingFunction<Fluxzero, ?> openSocketAnd(ThrowingConsumer<WebSocket> followUp, String... protocols) {
            return openSocketAnd(baseUri(), builder -> {
            }, followUp, protocols);
        }

        private ThrowingFunction<Fluxzero, ?> openSocketAnd(URI uri,
                                                            ThrowingConsumer<WebSocket.Builder> builderCustomizer,
                                                            ThrowingConsumer<WebSocket> followUp,
                                                            String... protocols) {
            return fc -> {
                CompletableFuture<String> result = new CompletableFuture<>();
                WebSocket webSocket = openSocket(uri, result, builderCustomizer, protocols);
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
            return openSocket(baseUri(), callback, builder -> {
            }, protocols);
        }

        @SneakyThrows
        private WebSocket openSocket(URI uri, CompletableFuture<String> callback,
                                     ThrowingConsumer<WebSocket.Builder> builderCustomizer, String... protocols) {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            builderCustomizer.accept(builder);
            if (protocols.length > 0) {
                builder.subprotocols(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length));
            }
            return builder.buildAsync(uri, new WebSocket.Listener() {
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
            }).get(5, TimeUnit.SECONDS);
        }

        private URI baseUri() {
            return baseUri("/");
        }

        private URI baseUri(String pathAndQuery) {
            return URI.create(format("ws://localhost:%s%s", proxyPort, pathAndQuery));
        }
    }

    @Nested
    @ResourceLock("FLUXZERO_CORS_DOMAINS")
    class CorsTests {
        private final AtomicInteger optionsInvocations = new AtomicInteger();

        @BeforeEach
        void setUpCors() {
            testFixture.registerHandlers(new Object() {
                @HandleGet("/users")
                String users() {
                    return "ok";
                }

                @HandleOptions("/explicit-options")
                String options() {
                    optionsInvocations.incrementAndGet();
                    return "runtime-options";
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
        void preflightBypassesRuntimeOptionsHandler() {
            var preflightRequest = newRequest("/explicit-options")
                    .method("OPTIONS", BodyPublishers.noBody())
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "POST")
                    .build();
            testFixture
                    .whenApplying(fc -> httpClient.send(preflightRequest, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(204, resp.statusCode());
                        assertEquals("POST", resp.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
                        assertEquals("", resp.body());
                        assertEquals(0, optionsInvocations.get());
                    });
        }

        @Test
        void nonPreflightOptionsReachesRuntimeHandlers() {
            var optionsRequest = newRequest()
                    .method("OPTIONS", BodyPublishers.noBody())
                    .header("Origin", "https://app.example.com")
                    .build();
            testFixture
                    .whenApplying(fc -> httpClient.send(optionsRequest, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(204, resp.statusCode());
                        assertEquals("GET, HEAD, OPTIONS", resp.headers().firstValue("Allow").orElse(null));
                        assertNull(resp.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
                        assertEquals("https://app.example.com",
                                     resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
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
        void corsHeadersAreAddedToProxyGeneratedErrorResponses() {
            proxyRequestHandler.setMaxRequestBodySize(8);
            try {
                var request = newRequest("/too-large")
                        .POST(BodyPublishers.ofString("0123456789"))
                        .header("Origin", "https://app.example.com")
                        .build();
                testFixture
                        .whenApplying(fc -> httpClient.send(request, BodyHandlers.ofString()))
                        .verifyResult(resp -> {
                            assertEquals(413, resp.statusCode());
                            assertEquals("Request body is too large", resp.body());
                            assertEquals(List.of("https://app.example.com"),
                                         resp.headers().allValues("Access-Control-Allow-Origin"));
                            assertEquals(List.of("true"),
                                         resp.headers().allValues("Access-Control-Allow-Credentials"));
                        });
            } finally {
                proxyRequestHandler.setMaxRequestBodySize(ProxyServer.DEFAULT_MAX_REQUEST_BODY_SIZE);
            }
        }

        @Test
        void corsHeadersAreNotDuplicatedWhenApplicationAlreadyAddsThem() {
            testFixture.registerHandlers(new Object() {
                @HandleGet("/application-cors")
                WebResponse response() {
                    return WebResponse.builder()
                            .header("Access-Control-Allow-Origin", "https://app.example.com")
                            .header("Access-Control-Allow-Credentials", "true")
                            .payload("ok")
                            .build();
                }
            });

            var request = newRequest("/application-cors").GET().header("Origin", "https://app.example.com").build();
            testFixture
                    .whenApplying(fc -> httpClient.send(request, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(200, resp.statusCode());
                        assertEquals(List.of("https://app.example.com"),
                                     resp.headers().allValues("Access-Control-Allow-Origin"));
                        assertEquals(List.of("true"),
                                     resp.headers().allValues("Access-Control-Allow-Credentials"));
                        assertEquals("ok", resp.body());
                    });
        }

        @Test
        void proxyCorsHeadersOverrideDifferentApplicationCorsHeaders() {
            testFixture.registerHandlers(new Object() {
                @HandleGet("/different-application-cors")
                WebResponse response() {
                    return WebResponse.builder()
                            .header("Access-Control-Allow-Origin", "https://application.example.com")
                            .header("Access-Control-Allow-Credentials", "false")
                            .payload("ok")
                            .build();
                }
            });

            var request = newRequest("/different-application-cors")
                    .GET()
                    .header("Origin", "https://app.example.com")
                    .build();
            testFixture
                    .whenApplying(fc -> httpClient.send(request, BodyHandlers.ofString()))
                    .verifyResult(resp -> {
                        assertEquals(200, resp.statusCode());
                        assertEquals(List.of("https://app.example.com"),
                                     resp.headers().allValues("Access-Control-Allow-Origin"));
                        assertEquals(List.of("true"),
                                     resp.headers().allValues("Access-Control-Allow-Credentials"));
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
            return newRequest("/users");
        }

        private HttpRequest.Builder newRequest(String path) {
            return newBuilder(URI.create(String.format("http://localhost:%s%s", proxyPort, path)));
        }

    }

    private static byte[] chunkedPayload() {
        byte[] payload = new byte[WebResponseGateway.MAX_RESPONSE_SIZE + 17];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        return payload;
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
                    private boolean completed;

                    @Override
                    public void request(long n) {
                        long remainingDemand = n;
                        while (remainingDemand-- > 0 && offset < payload.length && !completed) {
                            int length = Math.min(publisherChunkSize, payload.length - offset);
                            subscriber.onNext(ByteBuffer.wrap(Arrays.copyOfRange(payload, offset, offset + length)));
                            offset += length;
                        }
                        if (offset >= payload.length && !completed) {
                            completed = true;
                            subscriber.onComplete();
                        }
                    }

                    @Override
                    public void cancel() {
                        completed = true;
                    }
                });
            }
        };
    }

    private static class TestProxyRequestHandler extends ProxyRequestHandler {
        private volatile CountDownLatch responseFailure = new CountDownLatch(0);

        TestProxyRequestHandler(io.fluxzero.sdk.configuration.client.Client client) {
            super(client);
        }

        void expectResponseFailure(CountDownLatch responseFailure) {
            this.responseFailure = responseFailure;
        }

        @Override
        protected void completeResponse(io.fluxzero.common.api.SerializedMessage response, Throwable error,
                                        ProxyResponseContext responseContext) {
            super.completeResponse(response, error, responseContext);
            if (error != null) {
                responseFailure.countDown();
            }
        }
    }

    private static void enableRequestChunking(ProxyRequestHandler handler, int chunkSize) {
        handler.setRequestChunkingEnabled(true);
        handler.setRequestChunkSize(chunkSize);
    }

    private static byte[] requestPayload(int length) {
        byte[] payload = new byte[length];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 127);
        }
        return payload;
    }

    private static String bufferToString(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void assertNoBenchmarkTraceHeaders(HttpHeaders headers) {
        assertTrue(headers.firstValue(ProxyRequestHandler.BENCHMARK_RUNTIME_WEBRESPONSE_INDEX_HEADER).isEmpty());
        assertTrue(headers.firstValue(ProxyRequestHandler.BENCHMARK_PROXY_WEBRESPONSE_RECEIVED_HEADER).isEmpty());
        assertTrue(headers.firstValue(ProxyRequestHandler.BENCHMARK_PROXY_HTTP_RESPONSE_SEND_START_HEADER).isEmpty());
    }

    private static void assertHeaderAbsent(HttpHeaders headers, String name) {
        assertTrue(headers.map().keySet().stream().noneMatch(name::equalsIgnoreCase),
                   () -> "Expected response header to be absent: " + name);
    }

    private static void assertRawHeaderAbsent(String response, String name) {
        String headerBlock = response.split("\\R\\R", 2)[0];
        boolean present = headerBlock.lines()
                .skip(1)
                .map(line -> line.split(":", 2)[0].trim())
                .anyMatch(name::equalsIgnoreCase);
        assertFalse(present, () -> "Expected raw response header to be absent: " + name + "\n" + headerBlock);
    }

    private static Instant assertBenchmarkInstantHeader(HttpHeaders headers, String name) {
        return Instant.parse(headers.firstValue(name)
                                     .orElseThrow(() -> new AssertionError("Missing benchmark header " + name)));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static SerializedMessage observedRequest(List<SerializedMessage> requests, String path) {
        return requests.stream().filter(request -> path.equals(WebRequest.getUrl(request.getMetadata())))
                .findFirst().orElseThrow(() -> new AssertionError("No request observed for " + path));
    }
}
