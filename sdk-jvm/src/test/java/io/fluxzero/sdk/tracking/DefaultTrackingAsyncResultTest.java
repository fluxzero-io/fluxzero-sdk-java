/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.AdhocDispatchInterceptor;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTrackingAsyncResultTest {

    @Test
    void asyncResultsAreNotAwaitedByDefault() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        CompletionStage<Void> completion = tracking.report(
                handlerResult, descriptor(), message(serializer), ConsumerConfiguration.builder().name("web").build());

        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        handlerResult.complete("ok");

        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void closeStopsAwaitingPendingAsyncResultsWithoutPublishingLateResponses() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        tracking.report(
                handlerResult, descriptor(), message(serializer), ConsumerConfiguration.builder().name("web").build());

        tracking.close();

        assertFalse(handlerResult.isCancelled());
        assertTrue(handlerResult.complete("late"));
        verify(resultGateway, never()).respond(eq("late"), eq("benchmark-app"), eq(7));
    }

    @Test
    void asyncResultsCanBeAwaitedBeforeBatchCompletion() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(MessageType.EVENT, resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        CompletionStage<Void> completion = tracking.report(
                handlerResult,
                descriptor(),
                message(serializer),
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build());

        assertFalse(completion.toCompletableFuture().isDone());
        handlerResult.complete("ok");
        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void batchCompletionWaitsForAsyncResultsWhenConfigured() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        CompletableFuture<Void> batchCompletion = CompletableFuture.runAsync(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(handlerResult)),
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build(),
                true));

        TimeUnit.MILLISECONDS.sleep(50L);
        assertFalse(batchCompletion.isDone());
        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        handlerResult.complete("ok");

        assertDoesNotThrow(() -> batchCompletion.get(1, TimeUnit.SECONDS));
        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void batchCompletionDoesNotWaitForAsyncResultsByDefault() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(handlerResult)),
                ConsumerConfiguration.builder().name("web").build(),
                true);

        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        handlerResult.complete("ok");

        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void asyncHandlingModeOffloadsSynchronousHandlerAndPropagatesContext() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        ConsumerConfiguration config = asyncConfig(true);
        Fluxzero fluxzero = mockFluxzero(config, MessageType.WEBREQUEST);
        Thread trackerThread = Thread.currentThread();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicReference<Boolean> trackerVisible = new AtomicReference<>();
        AtomicReference<Boolean> fluxzeroVisible = new AtomicReference<>();
        AtomicReference<Boolean> messageVisible = new AtomicReference<>();

        try {
            fluxzero.execute(fc -> {
                Tracker.current.set(new Tracker("tracker-id", MessageType.WEBREQUEST, null, config, null));
                try {
                    tracking.handleBatch(
                            List.of(message(serializer)),
                            List.of(handler(() -> {
                                handlerThread.set(Thread.currentThread());
                                trackerVisible.set(Tracker.current().map(Tracker::getTrackerId)
                                                           .filter("tracker-id"::equals).isPresent());
                                fluxzeroVisible.set(Fluxzero.getOptionally().orElse(null) == fluxzero);
                                messageVisible.set(DeserializingMessage.getOptionally().isPresent());
                            })),
                            config,
                            true);
                } finally {
                    Tracker.current.remove();
                }
            });
        } finally {
            tracking.close();
        }

        assertNotEquals(trackerThread, handlerThread.get());
        assertTrue(trackerVisible.get());
        assertTrue(fluxzeroVisible.get());
        assertTrue(messageVisible.get());
    }

    @Test
    void asyncHandlingModeDoesNotWaitByDefault() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> releaseHandler = new CompletableFuture<>();
        CountDownLatch handlerStarted = new CountDownLatch(1);

        try {
            tracking.handleBatch(
                    List.of(message(serializer)),
                    List.of(handler(() -> {
                        handlerStarted.countDown();
                        releaseHandler.join();
                    })),
                    ConsumerConfiguration.builder()
                            .name("web")
                            .handlingMode(ConsumerHandlingMode.ASYNC)
                            .build(),
                    true);

            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));
            assertFalse(releaseHandler.isDone());
        } finally {
            releaseHandler.complete(null);
            tracking.close();
        }
    }

    @Test
    void asyncHandlingModeWaitsWhenAwaitAsyncResultsIsEnabled() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> releaseHandler = new CompletableFuture<>();
        CountDownLatch handlerStarted = new CountDownLatch(1);

        CompletableFuture<Void> batchCompletion = CompletableFuture.runAsync(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> {
                    handlerStarted.countDown();
                    releaseHandler.join();
                })),
                asyncConfig(true),
                true));

        try {
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));
            TimeUnit.MILLISECONDS.sleep(50L);
            assertFalse(batchCompletion.isDone());

            releaseHandler.complete(null);

            assertDoesNotThrow(() -> batchCompletion.get(1, TimeUnit.SECONDS));
        } finally {
            releaseHandler.complete(null);
            tracking.close();
        }
    }

    @Test
    void asyncHandlingModePropagatesAdhocDispatchInterceptors() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        AtomicReference<Boolean> adhocInterceptorVisible = new AtomicReference<>();
        DispatchInterceptor interceptor = DispatchInterceptor.noOp;

        try {
            AdhocDispatchInterceptor.runWithAdhocInterceptor(
                    () -> tracking.handleBatch(
                            List.of(message(serializer)),
                            List.of(handler(() -> adhocInterceptorVisible.set(
                                    AdhocDispatchInterceptor.getAdhocInterceptor(MessageType.EVENT).isPresent()))),
                            asyncConfig(true),
                            true),
                    interceptor,
                    MessageType.EVENT);
        } finally {
            tracking.close();
        }

        assertTrue(adhocInterceptorVisible.get());
    }

    @Test
    void awaitedAsyncHandlingModeWaitsForSendAndForgetFutures() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> sendCompletion = new CompletableFuture<>();
        CountDownLatch handlerRan = new CountDownLatch(1);

        CompletableFuture<Void> batchCompletion = CompletableFuture.runAsync(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> {
                    AsyncCompletionScope.register(sendCompletion);
                    handlerRan.countDown();
                })),
                asyncConfig(true),
                true));

        try {
            assertTrue(handlerRan.await(1, TimeUnit.SECONDS));
            TimeUnit.MILLISECONDS.sleep(50L);
            assertFalse(batchCompletion.isDone());

            sendCompletion.complete(null);

            assertDoesNotThrow(() -> batchCompletion.get(1, TimeUnit.SECONDS));
        } finally {
            sendCompletion.complete(null);
            tracking.close();
        }
    }

    @Test
    void batchCompletionWaitsForSendAndForgetFuturesByDefault() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> sendCompletion = new CompletableFuture<>();

        CompletableFuture<Void> batchCompletion = CompletableFuture.runAsync(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> AsyncCompletionScope.register(sendCompletion))),
                ConsumerConfiguration.builder().name("web").build(),
                false));

        TimeUnit.MILLISECONDS.sleep(50L);
        assertFalse(batchCompletion.isDone());

        sendCompletion.complete(null);

        assertDoesNotThrow(() -> batchCompletion.get(1, TimeUnit.SECONDS));
        tracking.close();
    }

    @Test
    void batchCompletionDoesNotWaitForSendAndForgetFuturesWhenDisabled() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> sendCompletion = new CompletableFuture<>();

        CompletableFuture<Void> batchCompletion = CompletableFuture.runAsync(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> AsyncCompletionScope.register(sendCompletion))),
                ConsumerConfiguration.builder()
                        .name("web")
                        .awaitSendAndForgetFutures(false)
                        .build(),
                false));

        assertDoesNotThrow(() -> batchCompletion.get(1, TimeUnit.SECONDS));
        assertFalse(sendCompletion.isDone());
        tracking.close();
    }

    @Test
    void batchCompletionFailsWhenSendAndForgetFutureFails() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);

        CompletionException error = assertThrows(CompletionException.class, () -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> AsyncCompletionScope.register(
                        CompletableFuture.failedFuture(new IllegalStateException("append failed"))))),
                ConsumerConfiguration.builder().name("web").build(),
                false));

        assertInstanceOf(IllegalStateException.class, error.getCause());
        tracking.close();
    }

    @Test
    void trackingConsumerLetsErrorHandlerHandleSendAndForgetFutureFailures() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(MessageType.EVENT, resultGateway, serializer);
        AtomicReference<Throwable> handledError = new AtomicReference<>();
        CountDownLatch handlerRan = new CountDownLatch(1);
        CompletableFuture<Void> sendCompletion = new CompletableFuture<>();
        ConsumerConfiguration config = ConsumerConfiguration.builder()
                .name("web")
                .errorHandler((error, message, retry) -> {
                    handledError.set(error);
                    return error;
                })
                .build();
        java.util.function.Consumer<List<SerializedMessage>> consumer = tracking.consumer(
                config,
                List.of(handler(() -> {
                    AsyncCompletionScope.register(sendCompletion);
                    handlerRan.countDown();
                })));
        Fluxzero fluxzero = mockFluxzero(config, MessageType.EVENT);

        try {
            CompletableFuture<Void> processing = CompletableFuture.runAsync(() -> {
                Tracker.current.set(new Tracker("tracker-id", MessageType.EVENT, null, config, null));
                try {
                    fluxzero.execute(fc -> consumer.accept(List.of(new Message("event").serialize(serializer))));
                } finally {
                    Tracker.current.remove();
                }
            });

            assertTrue(handlerRan.await(1, TimeUnit.SECONDS));
            sendCompletion.completeExceptionally(new IllegalStateException("append failed"));

            assertDoesNotThrow(() -> processing.get(1, TimeUnit.SECONDS));
        } finally {
            tracking.close();
        }

        assertInstanceOf(CompletionException.class, handledError.get());
        assertInstanceOf(IllegalStateException.class, handledError.get().getCause());
    }

    @Test
    void resultIsDelayedUntilPostHandlerCompletionSucceeds() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        DeserializingMessage message = message(serializer);
        CompletableFuture<Void> postHandlerCompletion = new CompletableFuture<>();
        Invocation.awaitBeforeResultPublication(message, postHandlerCompletion);

        CompletionStage<Void> completion = tracking.report(
                "ok", descriptor(), message, ConsumerConfiguration.builder().name("web").build());

        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        postHandlerCompletion.complete(null);

        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void awaitedResultWaitsForPostHandlerCompletion() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        DeserializingMessage message = message(serializer);
        CompletableFuture<Void> postHandlerCompletion = new CompletableFuture<>();
        Invocation.awaitBeforeResultPublication(message, postHandlerCompletion);

        CompletionStage<Void> completion = tracking.report(
                "ok", descriptor(), message,
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build());

        assertFalse(completion.toCompletableFuture().isDone());
        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        postHandlerCompletion.complete(null);

        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void postHandlerCompletionFailurePublishesFailureResult() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        DeserializingMessage message = message(serializer);
        CompletableFuture<Void> postHandlerCompletion = new CompletableFuture<>();
        Invocation.awaitBeforeResultPublication(message, postHandlerCompletion);

        tracking.report("ok", descriptor(), message, ConsumerConfiguration.builder().name("web").build());

        postHandlerCompletion.completeExceptionally(new IllegalStateException("commit failed"));

        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);
        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(resultGateway).respond(response.capture(), eq("benchmark-app"), eq(7));
        assertInstanceOf(TechnicalException.class, response.getValue());
        tracking.close();
    }

    private static TestTracking tracking(ResultGateway resultGateway, JacksonSerializer serializer) {
        return tracking(MessageType.WEBREQUEST, resultGateway, serializer);
    }

    private static TestTracking tracking(MessageType messageType, ResultGateway resultGateway,
                                         JacksonSerializer serializer) {
        return new TestTracking(messageType, resultGateway, serializer);
    }

    private static HandlerDescriptor descriptor() {
        HandlerDescriptor descriptor = mock(HandlerDescriptor.class);
        when(descriptor.isPassive()).thenReturn(false);
        try {
            when(descriptor.getMethod()).thenReturn(DefaultTrackingAsyncResultTest.class.getDeclaredMethod(
                    "descriptor"));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        return descriptor;
    }

    private static DeserializingMessage message(JacksonSerializer serializer) {
        SerializedMessage message = new SerializedMessage(
                serializer.serialize("request"),
                Metadata.of(WebRequest.methodKey, "POST", WebRequest.urlKey, "/benchmark"),
                "message-1",
                System.currentTimeMillis());
        message.setSource("benchmark-app");
        message.setRequestId(7);
        return new DeserializingMessage(message, type -> "request", MessageType.WEBREQUEST, null, serializer);
    }

    private static Handler<DeserializingMessage> handler(CompletableFuture<String> result) {
        HandlerInvoker invoker = mock(HandlerInvoker.class);
        when(invoker.invoke()).thenReturn(result);
        when(invoker.isPassive()).thenReturn(false);
        Handler<DeserializingMessage> handler = mock(Handler.class);
        when(handler.getInvokerOrNull(org.mockito.ArgumentMatchers.any())).thenReturn(invoker);
        when(handler.getInvoker(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(invoker));
        return handler;
    }

    private static Handler<DeserializingMessage> handler(Runnable task) {
        HandlerInvoker invoker = HandlerInvoker.run(task::run);
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return DefaultTrackingAsyncResultTest.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.of(invoker);
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                return invoker;
            }
        };
    }

    private static ConsumerConfiguration asyncConfig(boolean awaitAsyncResults) {
        return ConsumerConfiguration.builder()
                .name("web")
                .handlingMode(ConsumerHandlingMode.ASYNC)
                .awaitAsyncResults(awaitAsyncResults)
                .build();
    }

    private static Fluxzero mockFluxzero(ConsumerConfiguration config, MessageType messageType) {
        Fluxzero fluxzero = mock(Fluxzero.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        Client client = mock(Client.class);
        TrackingClient trackingClient = mock(TrackingClient.class);
        when(fluxzero.client()).thenReturn(client);
        when(client.forNamespace(config.getNamespace())).thenReturn(client);
        when(client.getTrackingClient(messageType, null)).thenReturn(trackingClient);
        when(trackingClient.getMessageType()).thenReturn(messageType);
        return fluxzero;
    }

    private static class TestTracking extends DefaultTracking {

        TestTracking(MessageType messageType, ResultGateway resultGateway, JacksonSerializer serializer) {
            super(messageType, resultGateway, List.of(), List.of(), serializer, mock(HandlerFactory.class));
        }

        CompletionStage<Void> report(Object result, HandlerDescriptor descriptor, DeserializingMessage message,
                                     ConsumerConfiguration config) {
            return reportResult(result, descriptor, message, config);
        }

        java.util.function.Consumer<List<SerializedMessage>> consumer(
                ConsumerConfiguration config, List<Handler<DeserializingMessage>> handlers) {
            return createConsumer(config, handlers);
        }
    }
}
