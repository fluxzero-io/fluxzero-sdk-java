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
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.sdk.Fluxzero;
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
import io.fluxzero.sdk.web.SocketSession;
import io.fluxzero.sdk.web.WebRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.fluxzero.proxy.NamespaceSelector.FLUXZERO_NAMESPACE_HEADER;
import static io.fluxzero.proxy.NamespaceSelector.JWKS_URL_PROPERTY;
import static java.lang.String.format;
import static java.net.http.HttpRequest.newBuilder;

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

        private HttpRequest.Builder newRequest() {
            return newBuilder(baseUri());
        }

        private URI baseUri() {
            return URI.create(format("http://0.0.0.0:%s/", proxyPort));
        }
    }

    @Nested
    class namespaceSwitching {


        @Test
        void getNamespaced() {
            testFixture.registerHandlers(new NamespacedHandler())
                    .whenApplying(fc -> httpClient.send(newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, "test").build(),
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
                            .whenApplying(fc -> httpClient.send(newRequest().GET().header(FLUXZERO_NAMESPACE_HEADER, jwt).build(),
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
                return "Hello " + Tracker.current().map(Tracker::getConfiguration).map(ConsumerConfiguration::getNamespace).orElse(null);
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
            testFixture.registerHandlers(new Object() {
                        @HandleSocketClose("/")
                        void close(Integer reason) {
                            Fluxzero.publishEvent("ws closed with " + reason);
                        }
                    })
                    .whenApplying(openSocketAnd(ws -> {
                        ws.sendClose(1000, "bla");
                        Thread.sleep(100);
                    }))
                    .expectResult("1000")
                    .expectEvents("ws closed with 1000");
        }

        @Test
        void closeSocketFromApplication() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketOpen("/")
                        void open(SocketSession session) {
                            session.close(1001);
                        }

                        @HandleSocketClose("/")
                        void close(Integer reason) {
                            log.info("ws closed with " + reason);
                            Fluxzero.publishEvent("ws closed with " + reason);
                        }
                    })
                    .whenApplying(openSocketAnd(ws -> Thread.sleep(100)))
                    .expectResult("1001")
                    .expectEvents("ws closed with 1001");
        }

        @Test
        void closeProxy() {
            testFixture.registerHandlers(new Object() {
                        @HandleSocketClose("/")
                        void close(Integer code) {
                            Fluxzero.publishEvent("ws closed with " + code);
                        }
                    })
                    .whenApplying(openSocketAnd(ws -> {
                        Thread.sleep(100);
                        proxyRequestHandler.close();
                        Thread.sleep(100);
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
                return result.get();
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
}