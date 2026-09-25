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
import io.fluxzero.common.Registration;
import io.fluxzero.common.TestTask;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.AdhocDispatchInterceptor;
import io.fluxzero.sdk.publishing.DefaultResultGateway;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTrackingAsyncResultTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @Timeout(10)
    void deferredFailurePublishesErrorHandlerRecoveryOnce(boolean asynchronousRecovery, boolean awaitResults)
            throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway gateway = mock(ResultGateway.class);
        when(gateway.forNamespace(null)).thenReturn(gateway);
        CompletableFuture<Object> published = new CompletableFuture<>();
        when(gateway.respond(any(), eq("benchmark-app"), eq(7))).thenAnswer(invocation -> {
            published.complete(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        TestTracking tracking = tracking(gateway, serializer);
        CompletableFuture<Object> deferred = new CompletableFuture<>();
        CompletableFuture<Object> recovery = new CompletableFuture<>();
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch errorHandled = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger();
        AtomicReference<CompletableFuture<Void>> owned = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("commit rejected");
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("web").awaitAsyncResults(awaitResults)
                .errorHandler((error, description, retry) -> {
                    assertSame(failure, error);
                    errorCount.incrementAndGet();
                    errorHandled.countDown();
                    return asynchronousRecovery ? recovery : "recovered";
                }).build();
        CompletableFuture<Void> batch = runIsolatedBatch(() -> tracking.handleBatch(
                List.of(message(serializer)), List.of(handler(() -> {
                    owned.set(Invocation.observeDeferredResult(deferred, () -> { }));
                    DeserializingMessage.whenBatchCompletes(ignored -> AsyncCompletionScope.register(owned.get()));
                    observed.countDown();
                })), config, true));
        try {
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            deferred.completeExceptionally(failure);
            assertTrue(errorHandled.await(1, TimeUnit.SECONDS));
            if (asynchronousRecovery) {
                assertFalse(owned.get().isDone());
                assertFalse(batch.isDone());
                assertFalse(published.isDone());
                recovery.complete("recovered");
            }
            batch.get(2, TimeUnit.SECONDS);
            assertEquals("recovered", published.get(2, TimeUnit.SECONDS));
            assertEquals(1, errorCount.get());
        } finally {
            deferred.complete(null);
            recovery.complete("recovered");
            tracking.close();
        }
    }

    @Test
    @Timeout(10)
    void throwingErrorHandlerKeepsDeferredBatchFailureExceptional() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway gateway = mock(ResultGateway.class);
        when(gateway.forNamespace(null)).thenReturn(gateway);
        TestTracking tracking = tracking(gateway, serializer);
        CompletableFuture<Object> deferred = new CompletableFuture<>();
        CountDownLatch observed = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger();
        IllegalStateException stop = new IllegalStateException("stop tracking");
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("web").awaitAsyncResults(true)
                .errorHandler((error, description, retry) -> {
                    errorCount.incrementAndGet();
                    throw stop;
                }).build();
        CompletableFuture<Void> batch = runIsolatedBatch(() -> tracking.handleBatch(
                List.of(message(serializer)), List.of(handler(() -> {
                    CompletableFuture<Void> owned = Invocation.observeDeferredResult(deferred, () -> { });
                    DeserializingMessage.whenBatchCompletes(ignored -> AsyncCompletionScope.register(owned));
                    observed.countDown();
                })), config, false));
        try {
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            deferred.completeExceptionally(new IllegalStateException("commit failed"));
            Throwable failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> batch.get(2, TimeUnit.SECONDS));
            assertSame(stop, io.fluxzero.common.ObjectUtils.unwrapException(failure));
            assertEquals(1, errorCount.get());
        } finally {
            deferred.complete(null);
            tracking.close();
        }
    }

    @Test
    @Timeout(10)
    void deferredRetryRequestsProgressAndRetainsOneBatchOwner() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway gateway = mock(ResultGateway.class);
        when(gateway.forNamespace(null)).thenReturn(gateway);
        when(gateway.respond(any(), eq("benchmark-app"), eq(7)))
                .thenReturn(CompletableFuture.completedFuture(null));
        TestTracking tracking = tracking(gateway, serializer);
        CompletableFuture<Object> first = new CompletableFuture<>();
        CompletableFuture<Object> retried = new CompletableFuture<>();
        CountDownLatch observed = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicBoolean progressRequested = new AtomicBoolean();
        AtomicReference<CompletableFuture<Void>> owner = new AtomicReference<>();
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("web").awaitAsyncResults(true)
                .errorHandler((error, description, retry) -> {
                    errors.incrementAndGet();
                    try {
                        return retry.call();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).build();
        Handler<DeserializingMessage> handler = handler(() -> {
            boolean initial = attempts.incrementAndGet() == 1;
            CompletableFuture<Void> owned = Invocation.observeDeferredResult(initial ? first : retried, () -> {
                progressRequested.set(true);
                retried.complete("retried");
            });
            if (initial) {
                owner.set(owned);
                DeserializingMessage.whenBatchCompletes(ignored -> AsyncCompletionScope.register(owned));
                observed.countDown();
            } else {
                assertSame(owner.get(), owned);
            }
        });
        CompletableFuture<Void> batch = runIsolatedBatch(() -> tracking.handleBatch(
                List.of(message(serializer)), List.of(handler), config, true));
        try {
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            first.completeExceptionally(new IllegalStateException("retry this commit"));
            batch.get(2, TimeUnit.SECONDS);
            assertTrue(progressRequested.get());
            assertEquals(2, attempts.get());
            assertEquals(1, errors.get());
            verify(gateway).respond("retried", "benchmark-app", 7);
        } finally {
            first.complete(null);
            retried.complete(null);
            tracking.close();
        }
    }

    @Test
    @Timeout(10)
    void asynchronousRecoveryCanAcquireItsFirstDeferredResultOwner() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway gateway = mock(ResultGateway.class);
        when(gateway.forNamespace(null)).thenReturn(gateway);
        when(gateway.respond(any(), eq("benchmark-app"), eq(7)))
                .thenReturn(CompletableFuture.completedFuture(null));
        TestTracking tracking = tracking(gateway, serializer);
        CompletableFuture<Object> recovery = new CompletableFuture<>();
        CompletableFuture<Object> deferred = new CompletableFuture<>();
        AtomicReference<Runnable> resumeRecovery = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> owner = new AtomicReference<>();
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        CountDownLatch initialInvocationReturned = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("web").awaitAsyncResults(true)
                .errorHandler((error, description, retry) -> {
                    resumeRecovery.set(DeserializingMessage.getCurrent().captureContext().wrap(() -> {
                        try {
                            recovery.complete(retry.call());
                        } catch (Throwable failure) {
                            recovery.completeExceptionally(failure);
                        }
                    }));
                    recoveryStarted.countDown();
                    return recovery;
                }).build();
        Handler<DeserializingMessage> handler = handler(() -> {
            if (attempts.incrementAndGet() == 1) {
                DeserializingMessage.whenBatchCompletes(ignored -> initialInvocationReturned.countDown());
                throw new IllegalStateException("fail before starting a commit");
            }
            owner.set(Invocation.observeDeferredResult(deferred, () -> deferred.complete("recovered")));
        });
        CompletableFuture<Void> batch = runIsolatedBatch(() -> tracking.handleBatch(
                List.of(message(serializer)), List.of(handler), config, true));
        try {
            assertTrue(recoveryStarted.await(1, TimeUnit.SECONDS));
            assertTrue(initialInvocationReturned.await(1, TimeUnit.SECONDS));
            assertFalse(batch.isDone());
            resumeRecovery.get().run();
            batch.get(2, TimeUnit.SECONDS);
            owner.get().get(2, TimeUnit.SECONDS);
            assertEquals(2, attempts.get());
            verify(gateway).respond("recovered", "benchmark-app", 7);
        } finally {
            deferred.complete(null);
            recovery.complete(null);
            tracking.close();
        }
    }

    @Test
    @Timeout(10)
    void nestedDeferredWorkPreservesOuterResultAndItsOwnBatchFailure() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway gateway = mock(ResultGateway.class);
        when(gateway.forNamespace(null)).thenReturn(gateway);
        CompletableFuture<Object> published = new CompletableFuture<>();
        when(gateway.respond(any(), eq("benchmark-app"), eq(7))).thenAnswer(invocation -> {
            published.complete(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        TestTracking tracking = tracking(gateway, serializer);
        DeserializingMessage outer = message(serializer);
        DeserializingMessage nested = message(serializer, "nested", null);
        CompletableFuture<Object> deferred = new CompletableFuture<>();
        AtomicInteger errors = new AtomicInteger();
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("web").awaitAsyncResults(true)
                .errorHandler((error, description, retry) -> {
                    errors.incrementAndGet();
                    return error;
                }).build();
        Handler<DeserializingMessage> handler = handlerInvoker(HandlerInvoker.call(() -> {
            nested.apply(current -> Invocation.performInvocation(() -> {
                assertNull(Invocation.observeDeferredResult(deferred, () -> { }));
                return null;
            }));
            DeserializingMessage.whenBatchCompletes(ignored -> AsyncCompletionScope.register(deferred));
            return "outer result";
        }));
        CompletableFuture<Void> batch = runIsolatedBatch(() -> tracking.handleBatch(
                List.of(outer), List.of(handler), config, true));
        try {
            assertEquals("outer result", published.get(2, TimeUnit.SECONDS));
            assertFalse(batch.isDone());
            IllegalStateException nestedFailure = new IllegalStateException("nested commit failed");
            deferred.completeExceptionally(nestedFailure);
            Throwable failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> batch.get(2, TimeUnit.SECONDS));
            assertSame(nestedFailure, io.fluxzero.common.ObjectUtils.unwrapException(failure));
            assertEquals(0, errors.get());
        } finally {
            deferred.complete(null);
            tracking.close();
        }
    }

    private static CompletableFuture<Void> runIsolatedBatch(Runnable task) {
        // A JUnit ForkJoin worker must not steal this task while waiting for an intermediate result: batch
        // completion deliberately blocks until the test supplies a commit or recovery outcome.
        return CompletableFuture.runAsync(task, command -> Thread.ofVirtual().start(command));
    }

    @Test
    void defaultResultGatewaySkipsPublicationCompletionWhenResultsAreNotAwaited() {
        JacksonSerializer serializer = new JacksonSerializer();
        DefaultResultGateway resultGateway = mock(DefaultResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);

        CompletionStage<Void> completion = tracking.report(
                "ok", descriptor(), message(serializer), ConsumerConfiguration.builder().name("web").build());

        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway).respondBatchedAndForget(
                eq("ok"), eq("benchmark-app"), eq(7),
                any(DefaultResultGateway.ResultPreparationErrorHandler.class));
        verify(resultGateway, never()).respondBatched(
                eq("ok"), eq("benchmark-app"), eq(7),
                any(DefaultResultGateway.ResultPreparationErrorHandler.class));
        tracking.close();
    }

    @Test
    void defaultResultGatewayPublicationCompletionIsPreservedWhenResultsAreAwaited() {
        JacksonSerializer serializer = new JacksonSerializer();
        DefaultResultGateway resultGateway = mock(DefaultResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        CompletableFuture<Void> publication = new CompletableFuture<>();
        when(resultGateway.respondBatched(
                eq("ok"), eq("benchmark-app"), eq(7),
                any(DefaultResultGateway.ResultPreparationErrorHandler.class))).thenReturn(publication);
        TestTracking tracking = tracking(resultGateway, serializer);

        CompletionStage<Void> completion = tracking.report(
                "ok", descriptor(), message(serializer),
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build());

        assertFalse(completion.toCompletableFuture().isDone());
        publication.complete(null);
        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway, never()).respondBatchedAndForget(
                eq("ok"), eq("benchmark-app"), eq(7),
                any(DefaultResultGateway.ResultPreparationErrorHandler.class));
        tracking.close();
    }

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
    void closeWaitsForOutstandingAsyncResults() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();
        tracking.report(
                handlerResult, descriptor(), message(serializer), ConsumerConfiguration.builder().name("web").build());

        try (var close = new TestTask(tracking::close, () -> handlerResult.complete("ok"))) {
            close.awaitBlockedIn(ClientUtils.class, "waitForResults", Duration.ofSeconds(1));
            handlerResult.complete("ok");
            close.awaitCompletion(Duration.ofSeconds(1));
        }
        verify(resultGateway).respond("ok", "benchmark-app", 7);
    }

    @Test
    void concurrentExternalCloseStillWaitsForOutstandingResults() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();
        tracking.report(handlerResult, descriptor(), message(serializer),
                ConsumerConfiguration.builder().name("web").build());
        try (var first = new TestTask(tracking::close, () -> handlerResult.complete("ok"))) {
            first.awaitBlockedIn(ClientUtils.class, "waitForResults", Duration.ofSeconds(5));
            try (var second = new TestTask(tracking::close, () -> handlerResult.complete("ok"))) {
                second.awaitBlockedIn(DefaultTracking.class, "close", Duration.ofSeconds(5));
                handlerResult.complete("ok");
                first.awaitCompletion(Duration.ofSeconds(5));
                second.awaitCompletion(Duration.ofSeconds(5));
            }
        }
        verify(resultGateway).respond("ok", "benchmark-app", 7);
    }

    @Test
    void failureCallbackDoesNotJoinExternalShutdownWaitingForAnotherResult() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        when(resultGateway.respond(any(), eq("benchmark-app"), eq(7)))
                .thenReturn(CompletableFuture.completedFuture(null));
        TestTracking tracking = tracking(resultGateway, serializer);
        CountDownLatch releaseShutdownWait = new CountDownLatch(1);
        CompletableFuture<String> failing = new CompletableFuture<>();
        CompletableFuture<String> pending = new CompletableFuture<>() {
            @Override public String get(long timeout, TimeUnit unit)
                    throws InterruptedException, java.util.concurrent.ExecutionException,
                           java.util.concurrent.TimeoutException {
                // Gate entry to the normal wait so a broken callback cannot pass merely when the grace expires.
                releaseShutdownWait.await();
                return super.get(timeout, unit);
            }
        };
        var config = ConsumerConfiguration.builder().name("web").build();
        tracking.report(failing, descriptor(), message(serializer), config);
        tracking.report(pending, descriptor(), message(serializer), config);
        try (var closer = new TestTask(tracking::close, () -> {
            failing.completeExceptionally(new IllegalStateException("test cleanup"));
            pending.complete("ok");
            releaseShutdownWait.countDown();
        })) {
            closer.awaitBlockedIn(ClientUtils.class, "waitForResults", Duration.ofSeconds(5));
            try (var callback = new TestTask(
                    () -> failing.completeExceptionally(new IllegalStateException("handler failed")),
                    () -> {
                        pending.complete("ok");
                        releaseShutdownWait.countDown();
                    })) {
                callback.awaitCompletion(Duration.ofSeconds(5));
                assertEquals(1L, releaseShutdownWait.getCount());
            }
            closer.awaitCompletion(Duration.ofSeconds(5));
        }
        verify(resultGateway).respond("ok", "benchmark-app", 7);
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

        try (var batchCompletion = new TestTask(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(handlerResult)),
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build(),
                true), () -> handlerResult.complete("ok"))) {
            batchCompletion.awaitBlockedIn(DefaultTracking.class, "awaitAsyncResultCompletions", Duration.ofSeconds(1));
            verify(resultGateway, never()).respond("ok", "benchmark-app", 7);
            handlerResult.complete("ok");
            batchCompletion.awaitCompletion(Duration.ofSeconds(1));
        } finally {
            tracking.close();
        }
        verify(resultGateway).respond("ok", "benchmark-app", 7);
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

        try (var batchCompletion = new TestTask(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> {
                    handlerStarted.countDown();
                    releaseHandler.join();
                })),
                asyncConfig(true),
                true), () -> releaseHandler.complete(null))) {
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));
            batchCompletion.awaitBlockedIn(DefaultTracking.class, "awaitAsyncResultCompletions", Duration.ofSeconds(1));

            releaseHandler.complete(null);

            batchCompletion.awaitCompletion(Duration.ofSeconds(1));
        } finally {
            releaseHandler.complete(null);
            tracking.close();
        }
    }

    @Test
    @Timeout(5)
    void asyncInvokerRegistersBatchLifecycleBeforeWorkerHandoff() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> batchClosed = new CompletableFuture<>();
        AtomicBoolean preparationCleaned = new AtomicBoolean();
        AtomicReference<Thread> preparationThread = new AtomicReference<>();
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        Thread dispatcherThread = Thread.currentThread();
        HandlerInvoker invoker = new HandlerInvoker.DelegatingHandlerInvoker(HandlerInvoker.noOp()) {
            @Override
            public Registration prepareAsyncInvocation() {
                preparationThread.set(Thread.currentThread());
                DeserializingMessage.whenBatchCompletes(error -> {
                    if (error == null) {
                        batchClosed.complete(null);
                    } else {
                        batchClosed.completeExceptionally(error);
                    }
                });
                return () -> preparationCleaned.set(true);
            }

            @Override
            public Object invoke(java.util.function.BiFunction<Object, Object, Object> resultCombiner) {
                invocationThread.set(Thread.currentThread());
                return batchClosed.join();
            }
        };

        try {
            tracking.handleBatch(
                    List.of(message(serializer)),
                    List.of(handlerInvoker(invoker)),
                    asyncConfig(true),
                    false);
        } finally {
            tracking.close();
        }

        assertTrue(batchClosed.isDone());
        assertTrue(preparationCleaned.get());
        assertEquals(dispatcherThread, preparationThread.get());
        assertNotEquals(dispatcherThread, invocationThread.get());
    }

    @Test
    @Timeout(5)
    void preparedAsyncInvokerDoesNotImplyResultPublicationWaiting() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> invocationStarted = new CompletableFuture<>();
        CompletableFuture<Void> publication = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HandlerInvoker invoker = new HandlerInvoker.DelegatingHandlerInvoker(HandlerInvoker.noOp()) {
            @Override
            public Registration prepareAsyncInvocation() {
                return Registration.noOp();
            }

            @Override
            public Object invoke(java.util.function.BiFunction<Object, Object, Object> resultCombiner) {
                Invocation.awaitBeforeResultPublication(publication);
                invocationStarted.complete(null);
                return null;
            }
        };

        try {
            CompletableFuture<Void> processing = CompletableFuture.runAsync(() -> tracking.handleBatch(
                    List.of(message(serializer)), List.of(handlerInvoker(invoker)), asyncConfig(true), false), executor);

            invocationStarted.get(1, TimeUnit.SECONDS);
            processing.get(1, TimeUnit.SECONDS);
            assertFalse(publication.isDone());
        } finally {
            publication.complete(null);
            executor.shutdownNow();
            tracking.close();
        }
    }

    @Test
    void failedAsyncWorkerHandoffCancelsPreparedLifecycle() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        AtomicBoolean cancelled = new AtomicBoolean();
        HandlerInvoker invoker = new HandlerInvoker.DelegatingHandlerInvoker(HandlerInvoker.noOp()) {
            @Override
            public Registration prepareAsyncInvocation() {
                return () -> cancelled.set(true);
            }

            @Override
            public Object invoke(java.util.function.BiFunction<Object, Object, Object> resultCombiner) {
                return delegate.invoke(resultCombiner);
            }
        };
        tracking.close();

        assertThrows(RuntimeException.class, () -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handlerInvoker(invoker)),
                asyncConfig(true),
                false));

        assertTrue(cancelled.get());
    }

    @Test
    void asyncHandlingQueuesMessagesWithTheSameSegment() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        try {
            tracking.handleBatch(
                    List.of(message(serializer, "first", 42), message(serializer, "second", 42)),
                    List.of(handler(message -> {
                        order.add(message.getMessageId() + "-start");
                        if (message.getMessageId().equals("first")) {
                            firstStarted.countDown();
                            releaseFirst.join();
                            order.add("first-end");
                        } else {
                            secondStarted.countDown();
                        }
                    })),
                    asyncConfig(false),
                    true);

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.complete(null);

            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("first-start", "first-end", "second-start"), order);
        } finally {
            releaseFirst.complete(null);
            tracking.close();
        }
    }

    @Test
    void asyncHandlingLetsDifferentOrMissingSegmentsRunInParallel() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch otherMessagesStarted = new CountDownLatch(2);

        try {
            tracking.handleBatch(
                    List.of(message(serializer, "first", 42),
                            message(serializer, "different-segment", 43),
                            message(serializer, "no-segment", null)),
                    List.of(handler(message -> {
                        if (message.getMessageId().equals("first")) {
                            firstStarted.countDown();
                            releaseFirst.join();
                        } else {
                            otherMessagesStarted.countDown();
                        }
                    })),
                    asyncConfig(false),
                    true);

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(otherMessagesStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.complete(null);
            tracking.close();
        }
    }

    @Test
    void asyncHandlingKeepsHandlersForOneMessageParallel() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try {
            tracking.handleBatch(
                    List.of(message(serializer, "message", 42)),
                    List.of(handler(message -> {
                                firstStarted.countDown();
                                releaseFirst.join();
                            }),
                            handler(message -> secondStarted.countDown())),
                    asyncConfig(false),
                    true);

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.complete(null);
            tracking.close();
        }
    }

    @Test
    void sameSegmentWaitsForAsynchronousHandlerResult() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> firstResult = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        Handler<DeserializingMessage> handler = new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return DefaultTrackingAsyncResultTest.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.of(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                return HandlerInvoker.call(() -> {
                    if (message.getMessageId().equals("first")) {
                        firstStarted.countDown();
                        return firstResult;
                    }
                    secondStarted.countDown();
                    return null;
                });
            }
        };

        try {
            tracking.handleBatch(
                    List.of(message(serializer, "first", 42), message(serializer, "second", 42)),
                    List.of(handler), asyncConfig(false), true);

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));

            firstResult.complete(null);

            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
        } finally {
            firstResult.complete(null);
            tracking.close();
        }
    }

    @Test
    void handlerOwnedOrderingBypassesCoarseBatchSegments() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> firstResult = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        Handler<DeserializingMessage> handler = new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return DefaultTrackingAsyncResultTest.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.of(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                return new HandlerInvoker.DelegatingHandlerInvoker(
                        HandlerInvoker.call(() -> {
                            if (message.getMessageId().equals("first")) {
                                firstStarted.countDown();
                                return firstResult;
                            }
                            secondStarted.countDown();
                            return null;
                        })) {
                    @Override
                    public boolean requiresBatchSegmentOrder() {
                        return false;
                    }

                    @Override
                    public Object invoke(
                            java.util.function.BiFunction<Object, Object, Object> resultCombiner) {
                        return delegate.invoke(resultCombiner);
                    }
                };
            }
        };

        try {
            tracking.handleBatch(
                    List.of(message(serializer, "first", 42), message(serializer, "second", 42)),
                    List.of(handler), asyncConfig(false), true);

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertFalse(firstResult.isDone());
        } finally {
            firstResult.complete(null);
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

        try (var batchCompletion = new TestTask(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> {
                    AsyncCompletionScope.register(sendCompletion);
                    handlerRan.countDown();
                })),
                asyncConfig(true),
                true), () -> sendCompletion.complete(null))) {
            assertTrue(handlerRan.await(1, TimeUnit.SECONDS));
            batchCompletion.awaitBlockedIn(AsyncCompletionScope.class, "await", Duration.ofSeconds(1));

            sendCompletion.complete(null);

            batchCompletion.awaitCompletion(Duration.ofSeconds(1));
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

        try (var batchCompletion = new TestTask(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(() -> AsyncCompletionScope.register(sendCompletion))),
                ConsumerConfiguration.builder().name("web").build(),
                false), () -> sendCompletion.complete(null))) {
            batchCompletion.awaitBlockedIn(AsyncCompletionScope.class, "await", Duration.ofSeconds(1));
            sendCompletion.complete(null);
            batchCompletion.awaitCompletion(Duration.ofSeconds(1));
        } finally {
            tracking.close();
        }
    }

    @Test
    void batchCompletionDoesNotWaitForSendAndForgetFuturesWhenDisabled() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<Void> sendCompletion = new CompletableFuture<>();

        CompletableFuture<Void> batchCompletion = runIsolatedBatch(() -> tracking.handleBatch(
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
            CompletableFuture<Void> processing = runIsolatedBatch(() -> {
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
        return message(serializer, "message-1", null);
    }

    private static DeserializingMessage message(JacksonSerializer serializer, String messageId, Integer segment) {
        SerializedMessage message = new SerializedMessage(
                serializer.serialize("request"),
                Metadata.of(WebRequest.methodKey, "POST", WebRequest.urlKey, "/benchmark"),
                messageId,
                System.currentTimeMillis());
        message.setSegment(segment);
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
        return handlerInvoker(HandlerInvoker.run(task::run));
    }

    private static Handler<DeserializingMessage> handlerInvoker(HandlerInvoker invoker) {
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

    private static Handler<DeserializingMessage> handler(java.util.function.Consumer<DeserializingMessage> task) {
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return DefaultTrackingAsyncResultTest.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.of(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                return HandlerInvoker.run(() -> task.accept(message));
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
