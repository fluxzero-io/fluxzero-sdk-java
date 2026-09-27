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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
@Isolated
class ProxyRequestPublicationTest {
    @Test
    void ordinaryRequestUsesStorageAcknowledgmentWithoutBlockingBusinessResponse() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.handler.doSendWebRequest(null, request());
            var publication = fixture.publications.getFirst();
            assertEquals(Guarantee.STORED, publication.guarantee());
            assertFalse(fixture.handler.completion.isDone());
            fixture.respond(publication.messages()[0]);
            var completed = fixture.handler.completion.get(2, TimeUnit.SECONDS);
            assertNull(completed.error());
            assertFalse(publication.acknowledgment().isDone());
            publication.acknowledgment().completeExceptionally(new IOException("late ack failure"));
            assertSame(completed, fixture.handler.completion.join());
            assertEquals(1, fixture.handler.completionCount.get());
            assertEquals(1, fixture.publications.size(), "No HTTP-level retry");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ordinaryPublicationFailureCompletesThePendingResponse(boolean synchronous) throws Exception {
        try (var fixture = new Fixture()) {
            var failure = new IllegalStateException("append failed");
            if (synchronous) {
                doThrow(failure).when(fixture.gateway).append(any(), any(SerializedMessage[].class));
            }
            fixture.handler.doSendWebRequest(null, request());
            if (!synchronous) {
                fixture.publications.getFirst().acknowledgment().completeExceptionally(failure);
            }
            assertSame(failure, fixture.handler.completion.get(2, TimeUnit.SECONDS).error());
        }
    }

    @Test
    void chunkContinuationsWaitForStorageAcknowledgmentAndKeepRequestIdentity() throws Exception {
        try (var fixture = new Fixture()) {
            var chunks = fixture.handler.new ChunkedProxyRequest(request());
            chunks.append(new byte[]{1}, false);
            chunks.append(new byte[]{2}, true);
            assertEquals(1, fixture.publications.size());
            var first = fixture.publications.getFirst();
            assertEquals(Guarantee.STORED, first.guarantee());
            first.acknowledgment().complete(null);
            assertEquals(2, fixture.publications.size());
            var continuation = fixture.publications.getLast();
            assertEquals(Guarantee.STORED, continuation.guarantee());
            assertEquals(first.messages()[0].getRequestId(), continuation.messages()[0].getRequestId());
            assertEquals(first.messages()[0].getMessageId(), continuation.messages()[0].getMessageId());
            assertEquals(first.messages()[0].getSegment(), continuation.messages()[0].getSegment());
            assertFalse(chunks.dispatchFuture().isDone());
            fixture.respond(first.messages()[0]);
            assertNotNull(chunks.responseFuture().get(2, TimeUnit.SECONDS));
            continuation.acknowledgment().complete(null);
            chunks.dispatchFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void chunkedUploadReportsPublicationFailureWithoutWaitingForAnotherAcknowledgment(boolean firstChunk)
            throws Exception {
        try (var fixture = new Fixture()) {
            fixture.handler.setRequestChunkSize(1);
            var exchange = mock(ProxyRequestHandler.JettyExchange.class);
            when(exchange.getRequestInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
            when(exchange.getRequestBodyLength()).thenReturn(2L);
            when(exchange.maxRequestBodySize()).thenReturn(1024L);
            try (var reading = new ReadingTask(
                    () -> fixture.handler.readChunkedWebRequest(exchange, request()),
                    () -> fixture.publications.forEach(p -> p.acknowledgment().completeExceptionally(
                            new IllegalStateException("test cleanup"))))) {
                reading.awaitCompletion(java.time.Duration.ofSeconds(2));
                var first = fixture.publications.getFirst();
                assertFalse(fixture.handler.completion.isDone());
                if (!firstChunk) {
                    first.acknowledgment().complete(null);
                }
                var failure = new IllegalStateException("storage rejected upload");
                fixture.publications.getLast().acknowledgment().completeExceptionally(failure);
                assertSame(failure, fixture.handler.completion.get(2, TimeUnit.SECONDS).error());
                assertEquals(firstChunk ? 1 : 2, fixture.publications.size());
            }
        }
    }

    @Test
    void chunkedRequestTimeoutReachesHttpWhileStorageAcknowledgmentIsStillPending() throws Exception {
        String previous = System.getProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY);
        try {
            System.setProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY, "1");
            try (var fixture = new Fixture()) {
                fixture.handler.setRequestChunkSize(1);
                var exchange = mock(ProxyRequestHandler.JettyExchange.class);
                when(exchange.getRequestInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
                when(exchange.getRequestBodyLength()).thenReturn(2L);
                when(exchange.maxRequestBodySize()).thenReturn(1024L);
                try (var reading = new ReadingTask(
                        () -> fixture.handler.readChunkedWebRequest(exchange, request()),
                        () -> fixture.publications.forEach(p -> p.acknowledgment().completeExceptionally(
                                new IllegalStateException("test cleanup"))))) {
                    reading.awaitCompletion(java.time.Duration.ofSeconds(2));
                    assertInstanceOf(java.util.concurrent.TimeoutException.class,
                                     fixture.handler.completion.get(2, TimeUnit.SECONDS).error());
                    assertFalse(fixture.publications.getFirst().acknowledgment().isDone());
                    fixture.publications.getFirst().acknowledgment().complete(null);
                    assertEquals(1, fixture.publications.size(), "Timed-out upload must not resume");
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY);
            } else {
                System.setProperty(ProxyRequestHandler.REQUEST_TIMEOUT_SECONDS_PROPERTY, previous);
            }
        }
    }

    @Test
    void abortedUploadDoesNotResumeQueuedChunksAfterLateAcknowledgment() {
        try (var fixture = new Fixture()) {
            var chunks = fixture.handler.new ChunkedProxyRequest(request());
            chunks.append(new byte[]{1}, false);
            chunks.append(new byte[]{2}, true);
            var failure = new IOException("client disconnected");
            chunks.abort(failure);
            assertTrue(chunks.responseFuture().isCompletedExceptionally());
            fixture.publications.getFirst().acknowledgment().complete(null);
            assertTrue(chunks.dispatchFuture().isCompletedExceptionally());
            assertEquals(1, fixture.publications.size());
        }
    }

    private static WebRequest request() {
        return WebRequest.builder().url("/publication").method(HttpRequestMethod.POST).payload(new byte[]{1}).build();
    }

    private record Publication(Guarantee guarantee, SerializedMessage[] messages,
                               CompletableFuture<Void> acknowledgment) {}
    private record Completion(SerializedMessage response, Throwable error) {}

    private static class Fixture implements AutoCloseable {
        private final LocalClient client = spy(LocalClient.newInstance());
        private final GatewayClient gateway = mock(GatewayClient.class);
        private final List<Publication> publications = new CopyOnWriteArrayList<>();
        private final RecordingHandler handler;

        private Fixture() {
            doReturn(gateway).when(client).getGatewayClient(MessageType.WEBREQUEST);
            when(gateway.append(any(), any(SerializedMessage[].class))).thenAnswer(invocation -> {
                var acknowledgment = new CompletableFuture<Void>();
                publications.add(new Publication(invocation.getArgument(0),
                                                 (SerializedMessage[]) invocation.getRawArguments()[1], acknowledgment));
                return acknowledgment;
            });
            handler = new RecordingHandler(client);
        }

        private void respond(SerializedMessage request) {
            var response = WebResponse.builder().payload(new byte[]{42}).build().serialize(new ProxySerializer());
            response.setRequestId(request.getRequestId());
            response.setTarget(client.id());
            client.getGatewayClient(MessageType.WEBRESPONSE).append(Guarantee.STORED, response).join();
        }

        @Override
        public void close() {
            handler.close();
            client.shutDown();
        }
    }

    private static class RecordingHandler extends ProxyRequestHandler {
        private final CompletableFuture<Completion> completion = new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicInteger completionCount = new java.util.concurrent.atomic.AtomicInteger();

        private RecordingHandler(LocalClient client) {
            super(client);
        }

        @Override
        protected void completeResponse(SerializedMessage response, Throwable error, ProxyResponseContext context) {
            completionCount.incrementAndGet();
            completion.complete(new Completion(response, error));
        }
    }
    /** Owns blocked read work and releases fake acknowledgements even when an assertion fails. */
    private static final class ReadingTask implements AutoCloseable {
        private final java.util.concurrent.FutureTask<Void> result;
        private final Thread worker;
        private final Runnable release;

        private ReadingTask(Runnable action, Runnable release) {
            this.release = release;
            result = new java.util.concurrent.FutureTask<>(action, null);
            worker = Thread.ofPlatform().daemon().start(result);
        }

        private void awaitCompletion(java.time.Duration timeout) throws Exception {
            result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                release.run();
            } finally {
                if (!worker.join(java.time.Duration.ofSeconds(5))) {
                    worker.interrupt();
                    worker.join(java.time.Duration.ofSeconds(1));
                    fail("Read worker did not stop after releasing acknowledgements");
                }
                result.get();
            }
        }
    }

}
