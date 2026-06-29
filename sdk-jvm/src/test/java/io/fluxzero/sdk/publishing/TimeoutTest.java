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
 */

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.client.LocalTrackingClient;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutTest {
    @Test
    void testHandleSelf() {
        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> Fluxzero.queryAndWait(new HandleSelfRequest()))
                .expectExceptionalResult(TimeoutException.class);
        assertEmptyEventually(gatewayCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testHandleSelfAsync() {
        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> fc.queryGateway().send(new HandleSelfRequest()))
                .expectExceptionalResult(java.util.concurrent.TimeoutException.class);
        assertEmptyEventually(gatewayCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testUnhandled() {
        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> Fluxzero.queryAndWait(new TimeoutUnhandledRequest()))
                .verifyExceptionalResult((TimeoutException e) -> {
                    assertTrue(e.getMessage().contains("FZ-SDK-0002"));
                    assertTrue(e.getMessage().contains("What happened:"));
                    assertTrue(e.getMessage().contains("How to fix:"));
                    assertTrue(e.getMessage().contains("docs/errors#FZ-SDK-0002"));
                });
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testUnhandledAsync() {
        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> fc.queryGateway().send(new TimeoutUnhandledRequest()))
                .expectExceptionalResult(java.util.concurrent.TimeoutException.class);
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testTimeoutMetadataIsStoredForAsyncRequest() {
        TestFixture fixture = TestFixture.create();
        AtomicReference<SerializedMessage> appended = new AtomicReference<>();
        Registration registration = ((LocalTrackingClient) fixture.getFluxzero().client()
                .getTrackingClient(MessageType.QUERY)).registerMonitor(messages -> appended.set(messages.getFirst()));
        fixture.getFluxzero().queryGateway().send(new TimeoutUnhandledRequest());
        try {
            assertNotNull(appended.get());
            assertEquals("10", appended.get().getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
        } finally {
            registration.cancel();
        }
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testCustomRequestTimeoutOverridesAnnotatedTimeoutMetadata() {
        TestFixture fixture = TestFixture.create();
        AtomicReference<SerializedMessage> appended = new AtomicReference<>();
        Registration registration = ((LocalTrackingClient) fixture.getFluxzero().client()
                .getTrackingClient(MessageType.QUERY)).registerMonitor(messages -> appended.set(messages.getFirst()));
        DefaultGenericGateway gateway = getField(fixture.getFluxzero().queryGateway(), "delegate");
        gateway.sendForMessage(new Message(new TimeoutUnhandledRequest()), Duration.ofMillis(25));
        try {
            assertNotNull(appended.get());
            assertEquals("25", appended.get().getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
        } finally {
            registration.cancel();
        }
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testDefaultRequestHandlerTimeoutMetadataIsStored() {
        TestFixture fixture = TestFixture.create();
        DefaultRequestHandler requestHandler = new DefaultRequestHandler(
                fixture.getFluxzero().client(), MessageType.RESULT);
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], DefaultTimeoutRequest.class.getName(), 0),
                Metadata.empty(), "message-id", System.currentTimeMillis());
        CompletableFuture<SerializedMessage> future = requestHandler.prepareRequest(request, null, null);
        try {
            assertEquals("200000", request.getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
        } finally {
            future.cancel(true);
            requestHandler.close();
        }
    }

    @Test
    void defaultRequestHandlerCancelsTimeoutTaskAfterCompletion() throws Exception {
        TestFixture fixture = TestFixture.create();
        DefaultRequestHandler requestHandler = new DefaultRequestHandler(
                fixture.getFluxzero().client(), MessageType.RESULT, Duration.ofSeconds(60),
                "timeout-cancel-test");
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], DefaultTimeoutRequest.class.getName(), 0),
                Metadata.empty(), "message-id", System.currentTimeMillis());
        CompletableFuture<SerializedMessage> future = requestHandler.prepareRequest(request, Duration.ofSeconds(60),
                                                                                    null);
        ScheduledThreadPoolExecutor timeoutExecutor = getField(requestHandler, "timeoutExecutor");
        try {
            assertEquals(1, timeoutExecutor.getQueue().size());

            requestHandler.handleResults(List.of(chunk("final", true, request.getRequestId())));

            assertEquals("final", new String(future.get(1, TimeUnit.SECONDS).getData().getValue()));
            assertEquals(0, timeoutExecutor.getQueue().size());
        } finally {
            future.cancel(true);
            requestHandler.close();
        }
    }

    @Test
    void defaultRequestHandlerCompletesPendingRequestsWhenClosed() throws Exception {
        TestFixture fixture = TestFixture.create();
        DefaultRequestHandler requestHandler = new DefaultRequestHandler(
                fixture.getFluxzero().client(), MessageType.RESULT, Duration.ofSeconds(60),
                "timeout-close-test");
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], DefaultTimeoutRequest.class.getName(), 0),
                Metadata.empty(), "message-id", System.currentTimeMillis());
        CompletableFuture<SerializedMessage> future = requestHandler.prepareRequest(request, Duration.ofSeconds(60),
                                                                                    null);
        ScheduledThreadPoolExecutor timeoutExecutor = getField(requestHandler, "timeoutExecutor");
        try {
            assertEquals(1, timeoutExecutor.getQueue().size());

            requestHandler.close();

            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                                     () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalStateException);
            assertTrue(error.getCause().getMessage().contains("closed"));
            assertEquals(0, ((Map<?, ?>) getField(requestHandler, "callbacks")).size());
            assertEquals(0, timeoutExecutor.getQueue().size());
        } finally {
            future.cancel(true);
            requestHandler.close();
        }
    }

    @Test
    void defaultRequestHandlerProcessesChunkedResponsesInOrder() throws Exception {
        TestFixture fixture = TestFixture.create();
        ExecutorService responseExecutor = Executors.newFixedThreadPool(2);
        DefaultRequestHandler requestHandler = new DefaultRequestHandler(
                fixture.getFluxzero().client(), MessageType.RESULT, Duration.ofSeconds(1),
                "chunk-order-test", responseExecutor);
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], DefaultTimeoutRequest.class.getName(), 0),
                Metadata.empty(), "message-id", System.currentTimeMillis());
        CountDownLatch intermediateStarted = new CountDownLatch(1);
        CountDownLatch releaseIntermediate = new CountDownLatch(1);
        CompletableFuture<SerializedMessage> future = requestHandler.prepareRequest(request, Duration.ofSeconds(1),
                                                                                    response -> {
                                                                                        intermediateStarted.countDown();
                                                                                        await(releaseIntermediate);
                                                                                    });
        SerializedMessage intermediate = chunk("intermediate", false, request.getRequestId());
        SerializedMessage finalChunk = chunk("final", true, request.getRequestId());
        try {
            requestHandler.handleResults(List.of(intermediate, finalChunk));

            assertTrue(intermediateStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(future.isDone());

            releaseIntermediate.countDown();
            assertEquals("final", new String(future.get(1, TimeUnit.SECONDS).getData().getValue()));
        } finally {
            releaseIntermediate.countDown();
            future.cancel(true);
            requestHandler.close();
            responseExecutor.shutdownNow();
        }
    }

    @Test
    void defaultRequestHandlerAggregatesChunkedResponsesWithoutIntermediateCallback() throws Exception {
        TestFixture fixture = TestFixture.create();
        ExecutorService responseExecutor = Executors.newSingleThreadExecutor();
        DefaultRequestHandler requestHandler = new DefaultRequestHandler(
                fixture.getFluxzero().client(), MessageType.RESULT, Duration.ofSeconds(1),
                "chunk-aggregate-test", responseExecutor);
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], DefaultTimeoutRequest.class.getName(), 0),
                Metadata.empty(), "message-id", System.currentTimeMillis());
        CompletableFuture<SerializedMessage> future = requestHandler.prepareRequest(request, Duration.ofSeconds(1),
                                                                                    null);
        try {
            requestHandler.handleResults(List.of(
                    chunk("intermediate", false, request.getRequestId()),
                    chunk("final", true, request.getRequestId())));

            assertEquals("intermediatefinal", new String(future.get(1, TimeUnit.SECONDS).getData().getValue()));
        } finally {
            future.cancel(true);
            requestHandler.close();
            responseExecutor.shutdownNow();
        }
    }

    private static Map<?, ?> gatewayCallbacks(Object gateway) {
        return getField(getField(gateway, "delegate"), "callbacks");
    }

    @Timeout(10)
    private static class HandleSelfRequest {
        @HandleQuery
        CompletableFuture<String> handle() {
            return new CompletableFuture<>();
        }
    }

    @Timeout(10)
    private static class TimeoutUnhandledRequest {
    }

    private static class DefaultTimeoutRequest {
    }

    private static Map<?, ?> requestHandlerCallbacks(Object gateway) {
        return getField(getField(getField(gateway, "delegate"), "requestHandler"), "callbacks");
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("No field '%s' on %s".formatted(name, target.getClass().getName()));
    }

    private static SerializedMessage chunk(String payload, boolean finalChunk, Integer requestId) {
        SerializedMessage message = new SerializedMessage(
                new Data<>(payload.getBytes(), null, 0),
                Metadata.empty().with(HasMetadata.FINAL_CHUNK, String.valueOf(finalChunk)),
                payload + "-message-id", System.currentTimeMillis());
        message.setRequestId(requestId);
        return message;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertEmptyEventually(Map<?, ?> callbacks) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (callbacks.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertTrue(callbacks.isEmpty());
    }
}
