/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.proxy;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.websocket.SessionPool;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.fluxzero.common.Guarantee.STORED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForwardProxyConsumerReconnectTest {
    @ParameterizedTest(name = "response session already open: {0}")
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void responsePublicationLeavesHttpCompletionWorkersAvailable(boolean sessionAlreadyOpen) throws Exception {
        Client client = mock(Client.class);
        GatewayClient gateway = mock(GatewayClient.class);
        when(client.id()).thenReturn("proxy");
        when(client.name()).thenReturn("proxy");
        when(client.getGatewayClient(MessageType.WEBRESPONSE)).thenReturn(gateway);

        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[0]);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (key, value) -> true));
        CompletableFuture<HttpResponse<byte[]>> firstResponse = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> secondResponse = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> ((HttpRequest) invocation.getArgument(0)).uri().getPath().equals("/first")
                        ? firstResponse : secondResponse);

        WebsocketSession session = mock(WebsocketSession.class);
        when(session.isOpen()).thenReturn(true);
        CompletableFuture<WebsocketSession> handshake = new CompletableFuture<>();
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch publicationAttempts = new CountDownLatch(2);
        CountDownLatch publishedResponses = new CountDownLatch(2);
        SessionPool sessions = new SessionPool(1, () -> {
            connecting.countDown();
            return handshake.join();
        });
        when(gateway.append(eq(STORED), any(SerializedMessage.class))).thenAnswer(invocation -> {
            publicationAttempts.countDown();
            sessions.get();
            publishedResponses.countDown();
            return CompletableFuture.completedFuture(null);
        });
        if (sessionAlreadyOpen) {
            handshake.complete(session);
            sessions.get();
        }

        ForkJoinPool httpCompletions = new ForkJoinPool(1);
        ForwardProxyConsumer consumer = new ForwardProxyConsumer(
                client, ForwardProxyConsumer.defaultSettings.getConsumer(), 0L, true, false,
                httpClient, new AtomicBoolean());
        List<RequestHandler> handlers = List.of(startRequest(consumer, "first", 1), startRequest(consumer, "second", 2));
        try {
            handlers.forEach(RequestHandler::awaitResponseWait);

            httpCompletions.execute(() -> firstResponse.complete(response));
            if (!sessionAlreadyOpen) {
                assertTrue(connecting.await(5, SECONDS), "First response must start opening the session");
            }
            httpCompletions.execute(() -> secondResponse.complete(response));
            assertTrue(publicationAttempts.await(5, SECONDS), "Both responses must reach publication");

            CompletableFuture<Void> handshakeCallback = new CompletableFuture<>();
            httpCompletions.execute(() -> {
                handshake.complete(session);
                handshakeCallback.complete(null);
            });
            assertDoesNotThrow(() -> handshakeCallback.get(2, SECONDS),
                               "Response publication must leave a worker available to complete the handshake");
            assertTrue(publishedResponses.await(5, SECONDS), "Both responses must be published");
            verify(gateway, times(2)).append(eq(STORED), any(SerializedMessage.class));
        } finally {
            handshake.complete(session);
            firstResponse.complete(response);
            secondResponse.complete(response);
            httpCompletions.shutdown();
            try {
                assertTrue(httpCompletions.awaitTermination(5, SECONDS), "Completion workers must terminate");
                for (RequestHandler handler : handlers) {
                    handler.completion().get(5, SECONDS);
                    handler.thread().join(5000);
                    assertFalse(handler.thread().isAlive(), "Request handler must terminate");
                }
            } finally {
                httpCompletions.shutdownNow();
                sessions.close();
            }
        }
    }

    private static RequestHandler startRequest(ForwardProxyConsumer consumer, String id, int segment) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                consumer.handle(request(id, segment), URI.create("https://provider.example/" + id),
                                WebRequestSettings.builder().build());
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }, "forward-request-" + id);
        thread.start();
        return new RequestHandler(thread, completion);
    }

    private record RequestHandler(Thread thread, CompletableFuture<Void> completion) {
        void awaitResponseWait() {
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                StackTraceElement[] stack = thread.getStackTrace();
                if (thread.getState() == Thread.State.WAITING && Arrays.stream(stack).anyMatch(
                        frame -> frame.getClassName().equals(CompletableFuture.class.getName())
                                && frame.getMethodName().equals("join")) && Arrays.stream(stack).anyMatch(
                        frame -> frame.getClassName().equals(ForwardProxyConsumer.class.getName())
                                && frame.getMethodName().equals("handle"))) {
                    return;
                }
                LockSupport.parkNanos(1_000_000);
            }
            throw new AssertionError("Request handler did not reach its HTTP-response wait: " + thread.getName());
        }
    }

    private static SerializedMessage request(String id, int segment) {
        SerializedMessage request = WebRequest.get("https://provider.example/" + id).build()
                .serialize(ForwardProxyConsumer.serializer);
        request.setIndex(IndexUtils.indexForCurrentTime());
        request.setSegment(segment);
        request.setRequestId(segment);
        request.setSource("requester");
        return request;
    }
}
