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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.GenericGateway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultWebRequestGatewayTest {

    @Test
    void usesProxyByDefault() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        WebResponse expected = WebResponse.builder().status(200).payload("proxy").build();
        when(delegate.sendForMessage(any(WebRequest.class), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(expected));
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        WebResponse result = gateway.sendAndWait(WebRequest.get("https://example.com").build(),
                                                 WebRequestSettings.builder().build());
        gateway.close();

        assertEquals(expected, result);
        verify(delegate).sendForMessage(any(WebRequest.class), any(Duration.class));
        verify(delegate).close();
        verifyNoInteractions(httpClient);
    }

    @Test
    void nativeHttpClientBypassesProxy() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> nativeResponse = response(201, "native");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(nativeResponse));
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        WebResponse result = gateway.send(
                        WebRequest.post("https://example.com/resource").header("X-Test", "value").body("request").build(),
                        WebRequestSettings.builder().useNativeHttpClient(true).timeout(Duration.ofSeconds(5)).build())
                .join();
        gateway.close();

        assertEquals(201, result.getStatus());
        assertArrayEquals("native".getBytes(UTF_8), result.getPayload());
        verify(delegate, never()).sendForMessage(any(WebRequest.class), any(Duration.class));
        verify(httpClient).sendAsync(
                org.mockito.ArgumentMatchers.argThat(request -> request.uri().equals(
                        URI.create("https://example.com/resource")) && "POST".equals(request.method())
                        && request.headers().allValues("X-Test").equals(List.of("value"))),
                anyByteArrayBodyHandler());
        verify(httpClient).close();
    }

    @Test
    void withDelegateUsesIndependentlyOwnedNativeHttpClient() {
        GenericGateway firstDelegate = mock(GenericGateway.class);
        GenericGateway secondDelegate = mock(GenericGateway.class);
        HttpClient firstHttpClient = mock(HttpClient.class);
        HttpClient secondHttpClient = mock(HttpClient.class);
        HttpResponse<byte[]> firstResponse = response(200, "first");
        HttpResponse<byte[]> secondResponse = response(200, "second");
        when(firstHttpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(firstResponse));
        when(secondHttpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(secondResponse));
        AtomicInteger clientIndex = new AtomicInteger();
        List<NativeWebRequestClient> clients = List.of(
                new NativeWebRequestClient(firstHttpClient, new JacksonSerializer()),
                new NativeWebRequestClient(secondHttpClient, new JacksonSerializer()));
        DefaultWebRequestGateway firstGateway = new DefaultWebRequestGateway(
                firstDelegate, () -> clients.get(clientIndex.getAndIncrement()));
        DefaultWebRequestGateway secondGateway = firstGateway.withDelegate(secondDelegate);
        WebRequestSettings settings = WebRequestSettings.builder().useNativeHttpClient(true).build();

        WebResponse first = firstGateway.sendAndWait(WebRequest.get("https://example.com/first").build(), settings);
        WebResponse second = secondGateway.sendAndWait(WebRequest.get("https://example.com/second").build(), settings);
        firstGateway.close();
        secondGateway.close();

        assertArrayEquals("first".getBytes(UTF_8), first.getPayload());
        assertArrayEquals("second".getBytes(UTF_8), second.getPayload());
        assertEquals(2, clientIndex.get());
        verify(firstHttpClient).close();
        verify(secondHttpClient).close();
    }

    @Test
    void cancellingNativeSendCancelsActiveHttpRequest() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> httpRequest = new CompletableFuture<>();
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler())).thenReturn(httpRequest);
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        CompletableFuture<WebResponse> result = gateway.send(
                WebRequest.get("https://example.com").build(),
                WebRequestSettings.builder().useNativeHttpClient(true).build());

        assertTrue(result.cancel(true));
        assertTrue(httpRequest.isCancelled());
        gateway.close();
    }

    @Test
    void cancellingProxySendDoesNotCancelDelegateRequest() {
        GenericGateway delegate = mock(GenericGateway.class);
        CompletableFuture<Message> delegateRequest = new CompletableFuture<>();
        when(delegate.sendForMessage(any(WebRequest.class), any(Duration.class))).thenReturn(delegateRequest);
        DefaultWebRequestGateway gateway = new DefaultWebRequestGateway(delegate);

        CompletableFuture<WebResponse> result = gateway.send(
                WebRequest.get("https://example.com").build(), WebRequestSettings.builder().build());

        assertTrue(result.cancel(true));
        assertFalse(delegateRequest.isCancelled());
        gateway.close();
    }

    @Test
    void cancellingNativeSendDuringRetryDelayPreventsNextAttempt() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> unavailable = response(503, "unavailable");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(unavailable));
        CompletableFuture<Void> retryDelay = new CompletableFuture<>();
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient, ignored -> retryDelay);

        CompletableFuture<WebResponse> result = gateway.send(
                WebRequest.get("https://example.com").build(),
                WebRequestSettings.builder().useNativeHttpClient(true).maxRetries(1).build());

        assertTrue(result.cancel(true));
        assertTrue(retryDelay.isCancelled());
        verify(httpClient).sendAsync(any(), anyByteArrayBodyHandler());
        gateway.close();
    }

    @Test
    void retriesNativeTransportFailures() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> firstFailure = CompletableFuture.failedFuture(
                new IOException("first"));
        CompletableFuture<HttpResponse<byte[]>> secondFailure = CompletableFuture.failedFuture(
                new IOException("second"));
        HttpResponse<byte[]> successfulResponse = response(200, "ok");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(firstFailure, secondFailure, CompletableFuture.completedFuture(successfulResponse));
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        WebResponse result = gateway.sendAndWait(WebRequest.get("https://example.com").build(),
                                                 WebRequestSettings.builder().useNativeHttpClient(true)
                                                         .maxRetries(2).retryDelay(Duration.ZERO)
                                                         .timeout(Duration.ofSeconds(5)).build());

        assertEquals(200, result.getStatus());
        verify(httpClient, org.mockito.Mockito.times(3)).sendAsync(any(), anyByteArrayBodyHandler());
    }

    @Test
    void retriesDefaultTransientServerErrorResponse() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> errorResponse = response(503, "unavailable");
        HttpResponse<byte[]> successResponse = response(200, "ok");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(errorResponse),
                            CompletableFuture.completedFuture(successResponse));
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        WebResponse result = gateway.sendAndWait(WebRequest.get("https://example.com").build(),
                                                 WebRequestSettings.builder().useNativeHttpClient(true)
                                                         .maxRetries(1).retryDelay(Duration.ZERO)
                                                         .timeout(Duration.ofSeconds(5)).build());

        assertEquals(200, result.getStatus());
        verify(httpClient, org.mockito.Mockito.times(2)).sendAsync(any(), anyByteArrayBodyHandler());
    }

    @Test
    void doesNotRetryResponseStatusWhenRetriesAreDisabled() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> errorResponse = response(503, "unavailable");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(errorResponse));
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        WebResponse result = gateway.sendAndWait(WebRequest.get("https://example.com").build(),
                                                 WebRequestSettings.builder().useNativeHttpClient(true).build());

        assertEquals(503, result.getStatus());
        verify(httpClient).sendAsync(any(), anyByteArrayBodyHandler());
    }

    @Test
    void waitsAsynchronouslyBeforeNativeRetry() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> errorResponse = response(503, "unavailable");
        HttpResponse<byte[]> successResponse = response(200, "ok");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(errorResponse),
                            CompletableFuture.completedFuture(successResponse));
        CompletableFuture<Void> retryPermit = new CompletableFuture<>();
        AtomicReference<Duration> scheduledDelay = new AtomicReference<>();
        DefaultWebRequestGateway gateway = gateway(
                delegate, httpClient, delay -> {
                    scheduledDelay.set(delay);
                    return retryPermit;
                });

        CompletableFuture<WebResponse> result = gateway.send(
                WebRequest.get("https://example.com").build(),
                WebRequestSettings.builder().useNativeHttpClient(true).maxRetries(1)
                        .retryDelay(Duration.ofMillis(250)).timeout(Duration.ofSeconds(5)).build());

        assertEquals(Duration.ofMillis(250), scheduledDelay.get());
        assertFalse(result.isDone());
        verify(httpClient).sendAsync(any(), anyByteArrayBodyHandler());

        retryPermit.complete(null);

        assertEquals(200, result.join().getStatus());
        verify(httpClient, org.mockito.Mockito.times(2)).sendAsync(any(), anyByteArrayBodyHandler());
    }

    @Test
    void skipsNativeRetryWhoseDelayDoesNotFitDeadline() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> errorResponse = response(503, "unavailable");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(errorResponse));
        AtomicReference<Duration> scheduledDelay = new AtomicReference<>();
        DefaultWebRequestGateway gateway = gateway(
                delegate, httpClient, delay -> {
                    scheduledDelay.set(delay);
                    return CompletableFuture.completedFuture(null);
                });

        WebResponse result = gateway.sendAndWait(
                WebRequest.get("https://example.com").build(),
                WebRequestSettings.builder().useNativeHttpClient(true).maxRetries(1)
                        .retryDelay(Duration.ofSeconds(2)).timeout(Duration.ofSeconds(1)).build());

        assertEquals(503, result.getStatus());
        assertNull(scheduledDelay.get());
        verify(httpClient).sendAsync(any(), anyByteArrayBodyHandler());
    }

    @Test
    void doesNotRetryResponseStatusExcludedBySettings() {
        GenericGateway delegate = mock(GenericGateway.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> errorResponse = response(503, "unavailable");
        when(httpClient.sendAsync(any(), anyByteArrayBodyHandler()))
                .thenReturn(CompletableFuture.completedFuture(errorResponse));
        DefaultWebRequestGateway gateway = gateway(delegate, httpClient);

        WebResponse result = gateway.sendAndWait(WebRequest.get("https://example.com").build(),
                                                 WebRequestSettings.builder().useNativeHttpClient(true)
                                                         .maxRetries(3).retryableStatusCodes(Set.of())
                                                         .timeout(Duration.ofSeconds(5)).build());

        assertEquals(503, result.getStatus());
        verify(httpClient).sendAsync(any(), anyByteArrayBodyHandler());
    }

    @Test
    void readsCompatibilityDefaultsWhenNewSettingsAreAbsent() {
        ObjectNode oldSettings = Metadata.objectMapper.valueToTree(WebRequestSettings.builder().build());
        oldSettings.remove("useNativeHttpClient");
        oldSettings.remove("maxRetries");
        oldSettings.remove("retryDelay");
        oldSettings.remove("retryableStatusCodes");

        WebRequestSettings result = Metadata.of("settings", oldSettings.toString())
                .get("settings", WebRequestSettings.class);

        assertFalse(result.isUseNativeHttpClient());
        assertEquals(0, result.getMaxRetries());
        assertEquals(Duration.ofSeconds(1), result.getRetryDelay());
        assertEquals(Set.of(500, 502, 503, 504), result.getRetryableStatusCodes());
    }

    private DefaultWebRequestGateway gateway(GenericGateway delegate, HttpClient httpClient) {
        return new DefaultWebRequestGateway(delegate,
                                            new NativeWebRequestClient(httpClient, new JacksonSerializer()));
    }

    private DefaultWebRequestGateway gateway(GenericGateway delegate, HttpClient httpClient,
                                             Function<Duration, CompletableFuture<Void>> retryDelay) {
        return new DefaultWebRequestGateway(
                delegate, new NativeWebRequestClient(httpClient, new JacksonSerializer(), retryDelay));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse.BodyHandler<byte[]> anyByteArrayBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/plain")),
                                                           (name, value) -> true));
        return response;
    }
}
