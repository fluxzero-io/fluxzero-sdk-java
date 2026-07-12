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

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.BatchProcessingException;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
class ForwardProxyConsumerTest {
    private static final String CONSUMER_NAME = "forward-proxy-consumer-test";
    public static final Metadata requestSettingsMetadata = Metadata.of("settings", WebRequestSettings.builder()
            .consumer(CONSUMER_NAME)
            .build());
    private final TestFixture testFixture = TestFixture.createAsync().spy();
    private int port;

    private HttpContext serverContext;
    private Registration registration;

    @BeforeEach
    @SneakyThrows
    void setUp() {
        registration = new ForwardProxyConsumer(
                testFixture.getFluxzero().client(), CONSUMER_NAME, IndexUtils.indexForCurrentTime(), true, true)
                .start();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 0), 0);
        serverContext = server.createContext("/");
        port = server.getAddress().getPort();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
        log.info(" Server started on port {}", port);
        registration = registration.merge(() -> {
            server.stop(0);
            executor.shutdownNow();
        });
    }

    @AfterEach
    void tearDown() {
        registration.cancel();
    }

    @Test
    void getRequest() {
        serverContext.setHandler(exchange -> {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                String response = "test";
                exchange.sendResponseHeaders(200, response.length());
                outputStream.write(response.getBytes());
                outputStream.flush();
            }
        });
        testFixture.whenWebRequest(WebRequest.builder().url("http://localhost:" + port)
                        .metadata(requestSettingsMetadata)
                        .method(GET).build())
                .<WebResponse>expectResult(r -> r.getStatus() == 200
                                                       && "test".equals(new String(r.<byte[]>getPayload())));
    }

    @Test
    void handlerMetricsPublished() {
        serverContext.setHandler(exchange -> {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                String response = "test";
                exchange.sendResponseHeaders(200, response.length());
                outputStream.write(response.getBytes());
                outputStream.flush();
            }
        });
        testFixture
                .whenWebRequest(WebRequest.builder().url("http://localhost:" + port).method(GET)
                        .metadata(requestSettingsMetadata).build())
                .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.METRICS), atLeastOnce())
                        .append(any(), any(SerializedMessage.class)));
    }

    @Test
    void getRequestZipped() {
        serverContext.setHandler(exchange -> {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                byte[] compressed = CompressionAlgorithm.GZIP.compress("test".getBytes());
                exchange.sendResponseHeaders(200, compressed.length);
                outputStream.write(compressed);
                outputStream.flush();
            }
        });
        testFixture
                .whenWebRequest(WebRequest.builder().url("http://localhost:" + port).method(GET)
                        .metadata(requestSettingsMetadata).build())
                .expectWebResult(r -> r.getStatus() == 200 && "test".equals(new String(r.<byte[]>getPayload())));
    }

    @Test
    void postRequest() {
        serverContext.setHandler(exchange -> exchange.sendResponseHeaders(204, -1));
        testFixture
                .whenWebRequest(WebRequest.builder().url("http://localhost:" + port).method(POST)
                        .metadata(requestSettingsMetadata).payload("test").build())
                .<WebResponse>expectResult(r -> r.getStatus() == 204 && r.<byte[]>getPayload().length == 0)
                .expectWebResponse(r -> r.getStatus() == 204 && r.getMetadata().containsKey("$correlationId"));
    }

    @Test
    void drainsForwardedResponseOnShutdownWithoutBlockingHandler() throws Exception {
        Client client = mock(Client.class);
        GatewayClient responseGateway = mock(GatewayClient.class);
        CompletableFuture<Void> stored = new CompletableFuture<>();
        when(client.id()).thenReturn("client");
        when(client.name()).thenReturn("proxy");
        when(client.getGatewayClient(MessageType.WEBRESPONSE)).thenReturn(responseGateway);
        when(responseGateway.append(eq(STORED), any(SerializedMessage.class))).thenReturn(stored);
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(client, CONSUMER_NAME, 0L, true, false);
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], Object.class.getName(), 0), Metadata.empty(), "request", 0L);
        request.setRequestId(42);
        request.setSource("requester");

        consumer.sendResponse(WebResponse.builder().status(200).build(), request);
        CompletableFuture<Void> drain = CompletableFuture.runAsync(consumer::awaitPendingResponses);

        assertFalse(drain.isDone(), "Shutdown should await runtime storage without blocking normal response handling");
        stored.complete(null);
        drain.get(1, TimeUnit.SECONDS);
        verify(responseGateway).append(eq(STORED), any(SerializedMessage.class));
    }

    @Test
    void forcedShutdownLeavesRequestsThatHaveNotStartedForRedelivery() {
        AtomicBoolean forceStopping = new AtomicBoolean(true);
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                testFixture.getFluxzero().client(), CONSUMER_NAME, 0L, false, false,
                mock(HttpClient.class), forceStopping);
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], Object.class.getName(), 0), Metadata.empty(), "request", 0L);
        request.setIndex(42L);

        BatchProcessingException error = assertThrows(
                BatchProcessingException.class, () -> consumer.accept(List.of(request)));

        assertEquals(42L, error.getMessageIndex());
    }
}
