/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.sdk.Fluxzero;
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
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.proxy.NamespaceSelector.FLUXZERO_NAMESPACE_HEADER;
import static io.fluxzero.proxy.NamespaceSelector.JWKS_URL_PROPERTY;
import static java.lang.String.format;
import static java.net.http.HttpRequest.newBuilder;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class ProxyServerTest {
    private final TestFixture testFixture = TestFixture.createAsync();
    private final ProxyRequestHandler proxyRequestHandler =
            new ProxyRequestHandler(testFixture.getFluxzero().client());
    private final ProxyServer proxyServer = ProxyServer.startHttpProxyOnly(0, proxyRequestHandler);
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
                            newBuilder(URI.create(format("http://localhost:%s/proxy/health", proxyPort))).GET()
                                    .build(), BodyHandlers.ofString()).body())
                    .expectResult("Healthy");
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

    private static String bufferToString(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
