/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeWebRequestRedirectTest {
    private HttpServer source;
    private HttpServer target;

    @BeforeEach
    void startServers() throws IOException {
        source = startServer();
        target = startServer();
    }

    @AfterEach
    void stopServers() {
        source.stop(0);
        target.stop(0);
    }

    @Test
    void sameOriginRedirectIsFollowed() {
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        source.createContext("/start", redirect(307, "/target"));
        source.createContext("/target", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 201, "followed");
        });

        JacksonSerializer serializer = new JacksonSerializer();
        WebRequest request = WebRequest.post(url(source, "/start"))
                .header("Authorization", "Bearer same-origin")
                .body("redirected body").build();
        byte[] expectedBody = request.serialize(serializer).data().getValue();
        try (NativeWebRequestClient client = new NativeWebRequestClient(serializer)) {
            WebResponse response = send(client, request,
                                        RedirectPolicy.SAME_ORIGIN, null);

            assertEquals(201, response.getStatus());
            assertArrayEquals(expectedBody, receivedBody.get());
            assertEquals("Bearer same-origin", receivedAuthorization.get());
        }
    }

    @Test
    void crossOriginRedirectIsReturnedAndReported() throws InterruptedException {
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicReference<NativeWebRequestMetric> metric = new AtomicReference<>();
        CountDownLatch metricReceived = new CountDownLatch(1);
        source.createContext("/start", redirect(302, url(target, "/target")));
        target.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });

        try (NativeWebRequestClient client = new NativeWebRequestClient(new JacksonSerializer())) {
            WebResponse response = send(
                    client, WebRequest.get(url(source, "/start")).build(), RedirectPolicy.SAME_ORIGIN, value -> {
                        metric.set(value);
                        metricReceived.countDown();
                    });

            assertEquals(302, response.getStatus());
            assertEquals(0, targetCalls.get());
            assertTrue(metricReceived.await(1, TimeUnit.SECONDS));
            assertTrue(metric.get().isRedirectRejected());
            assertEquals(302, metric.get().getStatus());
        }
    }

    @Test
    void neverDoesNotFollowSameOriginRedirect() {
        AtomicInteger targetCalls = new AtomicInteger();
        source.createContext("/start", redirect(302, "/target"));
        source.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });

        try (NativeWebRequestClient client = new NativeWebRequestClient(new JacksonSerializer())) {
            WebResponse response = send(client, WebRequest.get(url(source, "/start")).build(),
                                        RedirectPolicy.NEVER, null);

            assertEquals(302, response.getStatus());
            assertEquals(0, targetCalls.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"NEVER, 307", "NEVER, 308", "SAME_ORIGIN, 307", "SAME_ORIGIN, 308"})
    void restrictedPoliciesNeverLeakBodyOrAuthorizationToAnotherAuthority(
            RedirectPolicy policy, int status) {
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        source.createContext("/start", redirect(status, url(target, "/target")));
        target.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "unexpected");
        });

        try (NativeWebRequestClient client = new NativeWebRequestClient(new JacksonSerializer())) {
            WebResponse response = send(client,
                                        WebRequest.post(url(source, "/start"))
                                                .header("Authorization", "Bearer must-not-leak")
                                                .body("private body").build(),
                                        policy, null);

            assertEquals(status, response.getStatus());
            assertEquals(0, targetCalls.get());
            assertNull(receivedBody.get());
            assertNull(receivedAuthorization.get());
        }
    }

    @Test
    void allowRetainsNormalJdkCrossOriginRedirectBehavior() {
        AtomicInteger targetCalls = new AtomicInteger();
        source.createContext("/start", redirect(302, url(target, "/target")));
        target.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 202, "followed");
        });

        try (NativeWebRequestClient client = new NativeWebRequestClient(new JacksonSerializer())) {
            WebResponse response = send(client, WebRequest.get(url(source, "/start")).build(),
                                        RedirectPolicy.ALLOW, null);

            assertEquals(202, response.getStatus());
            assertEquals(1, targetCalls.get());
        }
    }

    @Test
    void allowDoesNotFollowHttpsDowngrade() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        target.createContext("/downgrade", exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        SSLContext serverContext = serverSslContext();
        HttpsServer secureSource = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        secureSource.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        secureSource.createContext("/start", redirect(307, url(target, "/downgrade")));
        secureSource.start();

        SSLContext clientContext = clientSslContext();
        HttpClient redirectingClient = HttpClient.newBuilder().sslContext(clientContext)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpClient nonRedirectingClient = HttpClient.newBuilder().sslContext(clientContext)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try (NativeWebRequestClient client = new NativeWebRequestClient(
                redirectingClient, nonRedirectingClient, new JacksonSerializer(), ignored -> null)) {
            WebResponse response = send(client,
                                        WebRequest.post("https://localhost:%d/start"
                                                                .formatted(secureSource.getAddress().getPort()))
                                                .header("Authorization", "Bearer must-not-downgrade")
                                                .body("private body").build(),
                                        RedirectPolicy.ALLOW, null);

            assertEquals(307, response.getStatus());
            assertEquals(0, targetCalls.get());
        } finally {
            secureSource.stop(0);
        }
    }

    @Test
    void sameOriginUsesEffectiveDefaultPorts() {
        assertTrue(NativeWebRequestClient.sameOrigin(
                URI.create("https://EXAMPLE.com/source"), URI.create("https://example.com:443/target")));
        assertFalse(NativeWebRequestClient.sameOrigin(
                URI.create("https://example.com/source"), URI.create("http://example.com:443/target")));
        assertFalse(NativeWebRequestClient.sameOrigin(
                URI.create("https://example.com/source"), URI.create("https://example.com:444/target")));
    }

    @Test
    void sameOriginRedirectChainIsBoundedAtFiveFollowedRedirects() {
        AtomicInteger calls = new AtomicInteger();
        for (int index = 0; index <= 6; index++) {
            int current = index;
            source.createContext("/chain/" + current, exchange -> {
                calls.incrementAndGet();
                if (current < 6) {
                    exchange.getResponseHeaders().set("Location", "/chain/" + (current + 1));
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                } else {
                    respond(exchange, 200, "must not be reached");
                }
            });
        }

        try (NativeWebRequestClient client = new NativeWebRequestClient(new JacksonSerializer())) {
            WebResponse response = send(client, WebRequest.get(url(source, "/chain/0")).build(),
                                        RedirectPolicy.SAME_ORIGIN, null);

            assertEquals(302, response.getStatus());
            assertEquals(6, calls.get());
        }
    }

    @Test
    void configuredFluxzeroAutomaticallyPublishesNativeRequestMetric() throws InterruptedException {
        source.createContext("/metric", exchange -> respond(exchange, 200, "ok"));
        MetricHandler handler = new MetricHandler();

        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableShutdownHook().disableKeepalive().build(LocalClient.newInstance())) {
            fluxzero.metricsGateway().registerHandler(handler);

            WebResponse response = fluxzero.webRequestGateway().sendAndWait(
                    WebRequest.get(url(source, "/metric")).build(),
                    WebRequestSettings.builder().useNativeHttpClient(true)
                            .redirectPolicy(RedirectPolicy.NEVER).build());

            assertEquals(200, response.getStatus());
            assertTrue(handler.received.await(1, TimeUnit.SECONDS));
            assertEquals(200, handler.metric.get().getStatus());
            assertEquals("GET", handler.metric.get().getMethod());
        }
    }

    private WebResponse send(NativeWebRequestClient client, WebRequest request, RedirectPolicy policy,
                             java.util.function.Consumer<NativeWebRequestMetric> metricConsumer) {
        return client.send(request,
                           WebRequestSettings.builder().useNativeHttpClient(true).redirectPolicy(policy)
                                   .timeout(Duration.ofSeconds(5)).build(),
                           RedirectPolicy.ALLOW, metricConsumer).join();
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://localhost:%d%s".formatted(server.getAddress().getPort(), path);
    }

    private static HttpHandler redirect(int status, String location) {
        return exchange -> {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        };
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static SSLContext serverSslContext() throws Exception {
        X509Certificate certificate = certificate();
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null);
        keyStore.setKeyEntry("server", privateKey(), "password".toCharArray(),
                             new Certificate[]{certificate});
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientSslContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null);
        trustStore.setCertificateEntry("server", certificate());
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), null);
        return context;
    }

    private static X509Certificate certificate() throws Exception {
        try (InputStream input = resource("native-http-test-certificate.pem")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey privateKey() throws Exception {
        String pem;
        try (InputStream input = resource("native-http-test-key.pkcs8.b64")) {
            pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        byte[] encoded = Base64.getMimeDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static InputStream resource(String name) {
        InputStream input = NativeWebRequestRedirectTest.class.getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IllegalStateException("Missing test resource " + name);
        }
        return input;
    }

    @LocalHandler
    private static class MetricHandler {
        private final AtomicReference<NativeWebRequestMetric> metric = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);

        @HandleMetrics
        void handle(NativeWebRequestMetric metric) {
            this.metric.set(metric);
            received.countDown();
        }
    }
}
