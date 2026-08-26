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
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        ArgumentCaptor<SerializedMessage> response = ArgumentCaptor.forClass(SerializedMessage.class);
        verify(responseGateway).append(eq(STORED), response.capture());
        assertEquals(42, response.getValue().getRequestId());
        assertEquals("requester", response.getValue().getTarget());
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

    @Test
    void retriesTransportFailuresWithinRequestTimeout() throws Exception {
        Client client = mock(Client.class);
        GatewayClient responseGateway = mock(GatewayClient.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> httpResponse = mock(HttpResponse.class);
        when(client.id()).thenReturn("client");
        when(client.name()).thenReturn("proxy");
        when(client.getGatewayClient(MessageType.WEBRESPONSE)).thenReturn(responseGateway);
        when(responseGateway.append(eq(STORED), any(SerializedMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok".getBytes());
        when(httpResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("first")))
                .thenReturn(CompletableFuture.failedFuture(new IOException("second")))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, CONSUMER_NAME, 0L, true, false, httpClient, new AtomicBoolean());
        WebRequestSettings settings = WebRequestSettings.builder().consumer(CONSUMER_NAME)
                .timeout(Duration.ofSeconds(5)).maxRetries(2).retryDelay(Duration.ZERO).build();
        WebRequest request = WebRequest.get("https://example.com").metadata(
                Metadata.of("settings", settings)).build();
        SerializedMessage serializedRequest = request.serialize(ForwardProxyConsumer.serializer);
        serializedRequest.setIndex(IndexUtils.indexForCurrentTime());
        serializedRequest.setRequestId(42);
        serializedRequest.setSource("requester");
        WebRequestSettings deserializedSettings = consumer.getSettings(serializedRequest);

        consumer.handle(serializedRequest, URI.create(request.getPath()), deserializedSettings);

        assertEquals(2, deserializedSettings.getMaxRetries());
        verify(httpClient, times(3)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(responseGateway).append(eq(STORED), any(SerializedMessage.class));
    }

    @Test
    void retriesConfiguredResponseStatusWithinRequestTimeout() throws Exception {
        Client client = mock(Client.class);
        GatewayClient responseGateway = mock(GatewayClient.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> retryResponse = mock(HttpResponse.class);
        HttpResponse<byte[]> successResponse = mock(HttpResponse.class);
        when(client.id()).thenReturn("client");
        when(client.name()).thenReturn("proxy");
        when(client.getGatewayClient(MessageType.WEBRESPONSE)).thenReturn(responseGateway);
        when(responseGateway.append(eq(STORED), any(SerializedMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(retryResponse.statusCode()).thenReturn(429);
        when(retryResponse.body()).thenReturn("retry".getBytes());
        when(retryResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(successResponse.statusCode()).thenReturn(200);
        when(successResponse.body()).thenReturn("ok".getBytes());
        when(successResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(retryResponse),
                            CompletableFuture.completedFuture(successResponse));
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, CONSUMER_NAME, 0L, true, false, httpClient, new AtomicBoolean());
        WebRequestSettings settings = WebRequestSettings.builder().consumer(CONSUMER_NAME)
                .timeout(Duration.ofSeconds(5)).maxRetries(1).retryDelay(Duration.ofMillis(1))
                .retryableStatusCodes(Set.of(429)).build();
        WebRequest request = WebRequest.get("https://example.com").metadata(
                Metadata.of("settings", settings)).build();
        SerializedMessage serializedRequest = request.serialize(ForwardProxyConsumer.serializer);
        serializedRequest.setIndex(IndexUtils.indexForCurrentTime());
        serializedRequest.setRequestId(42);
        serializedRequest.setSource("requester");
        WebRequestSettings deserializedSettings = consumer.getSettings(serializedRequest);

        consumer.handle(serializedRequest, URI.create(request.getPath()), deserializedSettings);

        assertEquals(Set.of(429), deserializedSettings.getRetryableStatusCodes());
        assertEquals(Duration.ofMillis(1), deserializedSettings.getRetryDelay());
        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(responseGateway).append(eq(STORED), any(SerializedMessage.class));
    }

    @Test
    void expiredRequestIsNotSentAfterTimeoutResponse() {
        Client client = mock(Client.class);
        GatewayClient responseGateway = mock(GatewayClient.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(client.id()).thenReturn("client");
        when(client.name()).thenReturn("proxy");
        when(client.getGatewayClient(MessageType.WEBRESPONSE)).thenReturn(responseGateway);
        when(responseGateway.append(eq(STORED), any(SerializedMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, CONSUMER_NAME, 0L, true, false, httpClient, new AtomicBoolean());
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], Object.class.getName(), 0), Metadata.empty(), "request", 0L);
        request.setIndex(0L);
        request.setRequestId(42);
        request.setSource("requester");

        consumer.handle(request, URI.create("https://example.com"),
                        WebRequestSettings.builder().timeout(Duration.ofMillis(1)).build());

        verify(responseGateway).append(eq(STORED), any(SerializedMessage.class));
        verifyNoInteractions(httpClient);
    }

    @Test
    void boundsConcurrentRequestsAndResumesWhenCapacityBecomesAvailable() throws Exception {
        Client client = mockForwardingClient();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = httpResponse(200, "ok");
        CompletableFuture<HttpResponse<byte[]>> first = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> second = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> third = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(first, second, third);
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, CONSUMER_NAME, 0L, true, false, httpClient, new AtomicBoolean(), 2);

        CompletableFuture<Void> batch = runAsync(() -> consumer.accept(List.of(
                serializedRequest("one", CONSUMER_NAME), serializedRequest("two", CONSUMER_NAME),
                serializedRequest("three", CONSUMER_NAME))));

        verify(httpClient, timeout(1_000).times(2))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertFalse(batch.isDone());
        first.complete(response);
        verify(httpClient, timeout(1_000).times(3))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        second.complete(response);
        third.complete(response);
        batch.get(1, TimeUnit.SECONDS);
    }

    @Test
    void consumersHaveIndependentConcurrentRequestCapacity() throws Exception {
        Client client = mockForwardingClient();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = httpResponse(200, "ok");
        CompletableFuture<HttpResponse<byte[]>> first = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> second = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(first, second);
        ForwardProxyConsumer firstConsumer = new ForwardProxyConsumer(
                client, "first-consumer", 0L, false, false, httpClient, new AtomicBoolean(), 1);
        ForwardProxyConsumer secondConsumer = new ForwardProxyConsumer(
                client, "second-consumer", 0L, false, false, httpClient, new AtomicBoolean(), 1);

        CompletableFuture<Void> firstBatch = runAsync(
                () -> firstConsumer.accept(List.of(serializedRequest("one", "first-consumer"))));
        CompletableFuture<Void> secondBatch = runAsync(
                () -> secondConsumer.accept(List.of(serializedRequest("two", "second-consumer"))));

        verify(httpClient, timeout(1_000).times(2))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertFalse(firstBatch.isDone());
        assertFalse(secondBatch.isDone());
        first.complete(response);
        second.complete(response);
        CompletableFuture.allOf(firstBatch, secondBatch).get(1, TimeUnit.SECONDS);
    }

    @Test
    void retryDelayRetainsCapacityWithoutBlockingAnotherHttpCall() throws Exception {
        Client client = mockForwardingClient();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = httpResponse(200, "ok");
        CompletableFuture<Void> retryDelay = new CompletableFuture<>();
        CompletableFuture<Duration> observedDelay = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("retry")))
                .thenReturn(CompletableFuture.completedFuture(response))
                .thenReturn(CompletableFuture.completedFuture(response));
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, CONSUMER_NAME, 0L, true, false, httpClient, new AtomicBoolean(), 1, delay -> {
            observedDelay.complete(delay);
            return retryDelay;
        });
        WebRequestSettings retrySettings = WebRequestSettings.builder().consumer(CONSUMER_NAME)
                .timeout(Duration.ofSeconds(5)).maxRetries(1).retryDelay(Duration.ofMillis(250)).build();

        CompletableFuture<Void> batch = runAsync(() -> consumer.accept(List.of(
                serializedRequest("retrying", retrySettings), serializedRequest("waiting", CONSUMER_NAME))));

        assertEquals(Duration.ofMillis(250), observedDelay.get(1, TimeUnit.SECONDS));
        verify(httpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertFalse(batch.isDone());
        retryDelay.complete(null);
        verify(httpClient, timeout(1_000).times(3))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        batch.get(1, TimeUnit.SECONDS);
    }

    @Test
    void forcedStopCancelsPendingRetryDelayAndDoesNotStartAnotherAttempt() throws Exception {
        Client client = mockForwardingClient();
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<Void> retryDelay = new CompletableFuture<>();
        CompletableFuture<Duration> observedDelay = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("retry")));
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, CONSUMER_NAME, 0L, true, false, httpClient, new AtomicBoolean(), 1, delay -> {
            observedDelay.complete(delay);
            return retryDelay;
        });
        WebRequestSettings settings = WebRequestSettings.builder().consumer(CONSUMER_NAME)
                .timeout(Duration.ofSeconds(5)).maxRetries(1).retryDelay(Duration.ofSeconds(1)).build();
        CompletableFuture<Void> batch = runAsync(
                () -> consumer.accept(List.of(serializedRequest("retrying", settings))));
        assertEquals(Duration.ofSeconds(1), observedDelay.get(1, TimeUnit.SECONDS));

        consumer.forceActiveRequests();

        batch.get(1, TimeUnit.SECONDS);
        assertTrue(retryDelay.isCancelled());
        verify(httpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(httpClient).shutdownNow();
    }

    @Test
    void trackerFetchMatchesPerConsumerCapacity() {
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                testFixture.getFluxzero().client(), CONSUMER_NAME, 0L, false, false,
                mock(HttpClient.class), new AtomicBoolean(), 3);

        ConsumerConfiguration configuration = consumer.consumerConfiguration();

        assertEquals(3, configuration.getThreads());
        assertEquals(1, configuration.getMaxFetchSize());
    }

    @Test
    @ResourceLock(ForwardProxyConsumer.MAX_CONCURRENT_REQUESTS_PROPERTY)
    void validatesConfiguredConcurrentRequestCapacity() {
        String previous = System.getProperty(ForwardProxyConsumer.MAX_CONCURRENT_REQUESTS_PROPERTY);
        try {
            System.clearProperty(ForwardProxyConsumer.MAX_CONCURRENT_REQUESTS_PROPERTY);
            assertEquals(ForwardProxyConsumer.DEFAULT_MAX_CONCURRENT_REQUESTS,
                         ForwardProxyConsumer.configuredMaxConcurrentRequests());
            System.setProperty(ForwardProxyConsumer.MAX_CONCURRENT_REQUESTS_PROPERTY, "7");
            assertEquals(7, ForwardProxyConsumer.configuredMaxConcurrentRequests());
            System.setProperty(ForwardProxyConsumer.MAX_CONCURRENT_REQUESTS_PROPERTY, "0");
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, ForwardProxyConsumer::configuredMaxConcurrentRequests);
            assertTrue(error.getMessage().contains("must be >= 1"));
        } finally {
            restoreProperty(ForwardProxyConsumer.MAX_CONCURRENT_REQUESTS_PROPERTY, previous);
        }
    }

    private static Client mockForwardingClient() {
        Client client = mock(Client.class);
        GatewayClient responseGateway = mock(GatewayClient.class);
        when(client.id()).thenReturn("client");
        when(client.name()).thenReturn("proxy");
        when(client.getGatewayClient(MessageType.WEBRESPONSE)).thenReturn(responseGateway);
        when(responseGateway.append(eq(STORED), any(SerializedMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> httpResponse(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes());
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        return response;
    }

    private static SerializedMessage serializedRequest(String id, String consumer) {
        return serializedRequest(id, WebRequestSettings.builder().consumer(consumer)
                .timeout(Duration.ofSeconds(5)).build());
    }

    private static SerializedMessage serializedRequest(String id, WebRequestSettings settings) {
        SerializedMessage request = WebRequest.get("https://example.com/" + id)
                .metadata(Metadata.of("settings", settings)).build().serialize(ForwardProxyConsumer.serializer);
        request.setIndex(IndexUtils.indexForCurrentTime());
        request.setRequestId(id.hashCode());
        request.setSource("requester");
        return request;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static CompletableFuture<Void> runAsync(Runnable task) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                task.run();
                result.complete(null);
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}
