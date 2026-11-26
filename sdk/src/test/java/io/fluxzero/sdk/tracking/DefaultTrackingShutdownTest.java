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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.TestTask;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EventPublicationStrategy;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelCommitPolicy;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.client.DefaultTracker;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

@Execution(ExecutionMode.CONCURRENT)
class DefaultTrackingShutdownTest {

    @ParameterizedTest
    @CsvSource({"TIMEOUT, false", "TIMEOUT, true", "INTERRUPTED, false", "FROM_HANDLER, false", "FULL_CLOSE, false", "OFFLOADED_INTERRUPTED, false", "OFFLOADED_FROM_HANDLER, false", "ASYNC_INTERRUPTED, false"})
    void restartingAfterCancellationReturnsEarlyReplaysTheUnfinishedBatch(
            StopMode stopMode, boolean handlerFails) throws Exception {
        CountDownLatch batchReturned = new CountDownLatch(1);
        CountDownLatch interceptorClosed = new CountDownLatch(1);
        AtomicBoolean closedBeforeBatchReturned = new AtomicBoolean();
        SlowEventHandler handler = new SlowEventHandler();
        handler.failFirstEvent = handlerFails;
        TestFixture fixture = TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(
                        MessageType.EVENT, c -> c.toBuilder()
                                .handlingMode(stopMode == StopMode.ASYNC_INTERRUPTED
                                                      ? ConsumerHandlingMode.ASYNC : ConsumerHandlingMode.SYNC)
                                .batchInterceptor(StallingBatchInterceptor.builder().desiredBatchSize(2)
                                        .maximumStallingDuration(Duration.ofSeconds(5))
                                        .retryFrequency(Duration.ofMillis(10)).build())
                                .batchInterceptor(new BatchInterceptor() {
                                    @Override
                                    public java.util.function.Consumer<MessageBatch> intercept(
                                            java.util.function.Consumer<MessageBatch> consumer, Tracker tracker) {
                                        return batch -> {
                                            try {
                                                if (stopMode == StopMode.OFFLOADED_INTERRUPTED
                                                    || stopMode == StopMode.OFFLOADED_FROM_HANDLER) {
                                                    CompletableFuture.runAsync(ThreadLocalContext.capture().wrap(
                                                            () -> consumer.accept(batch))).join();
                                                } else {
                                                    consumer.accept(batch);
                                                }
                                            } finally {
                                                if (handler.firstEventStarted.getCount() == 0) {
                                                    batchReturned.countDown();
                                                }
                                            }
                                        };
                                    }

                                    @Override
                                    public void shutdown(Tracker tracker) {
                                        closedBeforeBatchReturned.set(batchReturned.getCount() != 0);
                                        interceptorClosed.countDown();
                                    }
                                }).build()))
                .suppressPendingConsumerWarning();
        var fluxzero = fixture.getFluxzero();
        var client = fluxzero.client().getTrackingClient(MessageType.EVENT);
        Registration registration = fluxzero.registerHandlers(handler);
        Registration restarted = Registration.noOp();
        try {
            if (stopMode == StopMode.FULL_CLOSE) {
                SlowEventHandler anotherRegistration = new SlowEventHandler();
                anotherRegistration.releaseFirstEvent();
                fluxzero.registerHandlers(anotherRegistration);
            }
            if (stopMode == StopMode.FROM_HANDLER || stopMode == StopMode.OFFLOADED_FROM_HANDLER) {
                handler.onFirstEvent = registration::cancel;
            }
            fluxzero.eventGateway().publish(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            handler.awaitFirstEventStarted();
            client.storePosition(handler.consumerName, new int[]{0, 128}, 0L).get(2, TimeUnit.SECONDS);
            var previousPosition = client.getPosition(handler.consumerName);

            if (stopMode == StopMode.INTERRUPTED || stopMode == StopMode.OFFLOADED_INTERRUPTED
                || stopMode == StopMode.ASYNC_INTERRUPTED) {
                Thread.currentThread().interrupt();
                try {
                    registration.cancel();
                    assertTrue(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
            } else if (stopMode == StopMode.TIMEOUT) {
                // Keep the handler blocked until the actual bounded shutdown has returned.
                registration.cancel();
            } else if (stopMode == StopMode.FULL_CLOSE) {
                fluxzero.tracking(MessageType.EVENT).close();
            }
            handler.releaseFirstEvent();
            assertTrue(batchReturned.await(2, TimeUnit.SECONDS));
            assertTrue(interceptorClosed.await(2, TimeUnit.SECONDS));
            assertFalse(closedBeforeBatchReturned.get());
            assertEquals(previousPosition, client.getPosition(handler.consumerName));

            if (stopMode != StopMode.FULL_CLOSE) {
                SlowEventHandler replacement = new SlowEventHandler();
                replacement.releaseFirstEvent();
                restarted = fluxzero.registerHandlers(replacement);
                assertTrue(replacement.bothEventsHandled.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2), replacement.handledEvents.stream().sorted().toList());
            }
        } finally {
            handler.releaseFirstEvent();
            restarted.cancel();
            registration.cancel();
            fluxzero.close(true);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppingPreservesTheConfiguredAsyncResultBoundary(boolean awaitResults) throws Exception {
        SlowEventHandler handler = new SlowEventHandler();
        handler.firstResult = new CompletableFuture<>();
        CountDownLatch batchCallbacks = new CountDownLatch(1);
        AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        handler.onFirstEvent = () -> DeserializingMessage.whenBatchCompletes(error -> {
            batchFailure.set(error);
            batchCallbacks.countDown();
        });
        var fixture = fixture(awaitResults);
        var fluxzero = fixture.getFluxzero();
        Registration registration = fluxzero.registerHandlers(handler);
        var client = fluxzero.client().getTrackingClient(MessageType.EVENT);
        try {
            fluxzero.eventGateway().publish(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            handler.awaitFirstEventStarted();
            client.storePosition(handler.consumerName, new int[]{0, 128}, 0L).get(2, TimeUnit.SECONDS);
            try (var stop = new TestTask(registration::cancel, () -> {
                handler.releaseFirstEvent();
                handler.firstResult.complete(null);
            })) {
                stop.awaitBlockedIn(DefaultTracker.class, "awaitCancellation", Duration.ofSeconds(1));
                handler.releaseFirstEvent();
                assertTrue(batchCallbacks.await(1, TimeUnit.SECONDS));
                assertNull(batchFailure.get());
                if (awaitResults) {
                    assertEquals(0L, client.getPosition(handler.consumerName)
                            .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
                    handler.firstResult.complete(null);
                }
                stop.awaitCompletion(Duration.ofSeconds(2));
                assertEquals(handler.lastIndex, client.getPosition(handler.consumerName)
                        .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
                assertEquals(awaitResults, handler.firstResult.isDone());
                assertEquals(List.of(1, 2), handler.handledEvents);
            }
        } finally {
            handler.releaseFirstEvent();
            handler.firstResult.complete(null);
            registration.cancel();
            fluxzero.close(true);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batchCheckpointWaitsForOutgoingWritesAndCannotFollowAnAbandonedFailure(boolean failWrite) throws Exception {
        SlowEventHandler handler = new SlowEventHandler();
        CompletableFuture<Void> write = new CompletableFuture<>();
        CountDownLatch batchCallbacks = new CountDownLatch(1);
        AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        handler.onFirstEvent = () -> DeserializingMessage.whenBatchCompletes(error -> {
            batchFailure.set(error);
            AsyncCompletionScope.register(write);
            batchCallbacks.countDown();
        });
        CountDownLatch trackerStopped = new CountDownLatch(1);
        var fixture = fixture(false, new BatchInterceptor() {
            @Override public java.util.function.Consumer<MessageBatch> intercept(
                    java.util.function.Consumer<MessageBatch> consumer, Tracker tracker) { return consumer; }
            @Override public void shutdown(Tracker tracker) { trackerStopped.countDown(); }
        });
        var fluxzero = fixture.getFluxzero();
        Registration registration = fluxzero.registerHandlers(handler);
        var client = fluxzero.client().getTrackingClient(MessageType.EVENT);
        try {
            fluxzero.eventGateway().publish(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            handler.awaitFirstEventStarted();
            client.storePosition(handler.consumerName, new int[]{0, 128}, 0L).get(2, TimeUnit.SECONDS);
            try (var stop = new TestTask(() -> {
                if (failWrite) { Thread.currentThread().interrupt(); }
                registration.cancel();
            }, () -> {
                handler.releaseFirstEvent();
                write.complete(null);
            })) {
                if (failWrite) {
                    stop.awaitCompletion(Duration.ofSeconds(1));
                } else {
                    stop.awaitBlockedIn(DefaultTracker.class, "awaitCancellation", Duration.ofSeconds(1));
                }
                handler.releaseFirstEvent();
                assertTrue(batchCallbacks.await(1, TimeUnit.SECONDS));
                assertNull(batchFailure.get());
                assertEquals(0L, client.getPosition(handler.consumerName)
                        .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
                if (failWrite) {
                    write.completeExceptionally(new IllegalStateException("Publication was not stored"));
                } else {
                    write.complete(null);
                }
                stop.awaitCompletion(Duration.ofSeconds(2));
                assertTrue(trackerStopped.await(1, TimeUnit.SECONDS));
                assertEquals(failWrite ? 0L : handler.lastIndex, client.getPosition(handler.consumerName)
                        .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
                assertEquals(List.of(1, 2), handler.handledEvents);
            }
        } finally {
            handler.releaseFirstEvent();
            write.complete(null);
            registration.cancel();
            fluxzero.close(true);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void cancellationCannotBeSwallowedByAnAlreadyRunningBatchErrorHandler(
            boolean retryForever, boolean offloaded) throws Exception {
        SlowEventHandler handler = new SlowEventHandler();
        handler.releaseFirstEvent();
        AtomicBoolean failPublication = new AtomicBoolean(true);
        handler.onFirstEvent = () -> {
            if (failPublication.getAndSet(false)) {
                AsyncCompletionScope.register(
                        CompletableFuture.failedFuture(new IllegalStateException("Publication failed")));
            }
        };
        CountDownLatch errorHandlerStarted = new CountDownLatch(1);
        CountDownLatch trackerStopped = new CountDownLatch(1);
        CompletableFuture<Void> releaseErrorHandler = new CompletableFuture<>();
        AtomicBoolean cleanup = new AtomicBoolean();
        var forever = new ForeverRetryingErrorHandler(RetryConfiguration.builder()
                .delay(Duration.ofMillis(5)).errorTest(error -> !cleanup.get()).exceptionLogger(status -> {}).build());
        var fixture = TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(MessageType.EVENT,
                c -> c.toBuilder().batchInterceptor(StallingBatchInterceptor.builder().desiredBatchSize(2)
                                .maximumStallingDuration(Duration.ofSeconds(5)).retryFrequency(Duration.ofMillis(10)).build())
                        .batchInterceptor(new BatchInterceptor() {
                            @Override public java.util.function.Consumer<MessageBatch> intercept(
                                    java.util.function.Consumer<MessageBatch> consumer, Tracker tracker) { return consumer; }
                            @Override public void shutdown(Tracker tracker) { trackerStopped.countDown(); }
                        }).errorHandler((error, description, retry) -> {
                            if (offloaded) {
                                CompletableFuture.runAsync(ThreadLocalContext.capture().wrap(() -> {
                                    errorHandlerStarted.countDown();
                                    releaseErrorHandler.join();
                                    try { retry.call(); } catch (Exception ignored) { }
                                })).join();
                                return null;
                            }
                            errorHandlerStarted.countDown();
                            releaseErrorHandler.join();
                            if (retryForever) { return forever.handleError(error, description, retry); }
                            try { retry.call(); } catch (Exception ignored) { }
                            return null;
                        }).build())).suppressPendingConsumerWarning();
        var fluxzero = fixture.getFluxzero();
        var client = fluxzero.client().getTrackingClient(MessageType.EVENT);
        Registration registration = fluxzero.registerHandlers(handler);
        try {
            fluxzero.eventGateway().publish(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            assertTrue(errorHandlerStarted.await(1, TimeUnit.SECONDS));
            client.storePosition(handler.consumerName, new int[]{0, 128}, 0L).get(2, TimeUnit.SECONDS);
            try (var stop = new TestTask(() -> {
                Thread.currentThread().interrupt();
                registration.cancel();
            }, () -> releaseErrorHandler.complete(null))) {
                stop.awaitCompletion(Duration.ofSeconds(1));
                releaseErrorHandler.complete(null);
                stop.awaitCompletion(Duration.ofSeconds(2));
                assertTrue(trackerStopped.await(1, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2, 1, 2), handler.handledEvents, "The configured error handler may retry the batch");
                assertEquals(0L, client.getPosition(handler.consumerName)
                        .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
            }
        } finally {
            cleanup.set(true);
            releaseErrorHandler.complete(null);
            registration.cancel();
            fluxzero.close(true);
        }
    }

    @Test
    void stoppingCommitsTheDeferredModelBatchBeforeItsCheckpoint() throws Exception {
        SlowEventHandler observer = new SlowEventHandler();
        var fluxzero = DefaultFluxzero.builder().disableShutdownHook().makeApplicationInstance(false)
                .configureDefaultConsumer(MessageType.COMMAND,
                c -> c.toBuilder().handlingMode(ConsumerHandlingMode.SYNC)
                        .handlerInterceptor((next, invoker) -> message -> {
                            Object result = next.apply(message);
                            io.fluxzero.common.ObjectUtils.run(() -> observer.handle(((PutCounter) message.getPayload()).value()));
                            return result;
                        })
                        .batchInterceptor(StallingBatchInterceptor.builder().desiredBatchSize(2)
                                .maximumStallingDuration(Duration.ofMillis(100))
                                .retryFrequency(Duration.ofMillis(10)).build()).build()).build(LocalClient.newInstance());
        Registration registration = fluxzero.registerHandlers(PutCounter.class);
        try {
            fluxzero.commandGateway().sendAndForget(Guarantee.STORED,
                    new PutCounter("first", 1), new PutCounter("second", 2)).get(2, TimeUnit.SECONDS);
            observer.awaitFirstEventStarted();
            assertNull(fluxzero.apply(fc -> Fluxzero.loadModel("first", Counter.class).get()),
                    "The Model write must still be waiting for batch completion");
            try (var stop = new TestTask(registration::cancel, observer::releaseFirstEvent)) {
                stop.awaitBlockedIn(DefaultTracker.class, "awaitCancellation", Duration.ofSeconds(1));
                observer.releaseFirstEvent();
                stop.awaitCompletion(Duration.ofSeconds(2));
            }
            assertEquals(new Counter("first", 1), fluxzero.apply(fc -> Fluxzero.loadModel("first", Counter.class).get()));
            assertEquals(new Counter("second", 2), fluxzero.apply(fc -> Fluxzero.loadModel("second", Counter.class).get()));
            assertEquals(observer.lastIndex, fluxzero.client().getTrackingClient(MessageType.COMMAND)
                    .getPosition(observer.consumerName).lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
            assertEquals(List.of(1, 2), observer.handledEvents);
            Registration restarted = fluxzero.registerHandlers(PutCounter.class);
            try {
                fluxzero.commandGateway().sendAndForget(Guarantee.STORED, new PutCounter("third", 3)).get(2, TimeUnit.SECONDS);
                assertTrue(observer.thirdEventHandled.await(1, TimeUnit.SECONDS));
            } finally {
                restarted.cancel();
            }
            assertEquals(List.of(1, 2, 3), observer.handledEvents);
            assertEquals(new Counter("first", 1), fluxzero.apply(fc -> Fluxzero.loadModel("first", Counter.class).get()));
            assertEquals(new Counter("second", 2), fluxzero.apply(fc -> Fluxzero.loadModel("second", Counter.class).get()));
        } finally {
            observer.releaseFirstEvent();
            registration.cancel();
            fluxzero.close(true);
        }
    }

    @Model(cached = false, searchable = false, commitPolicy = ModelCommitPolicy.SYNC_AFTER_BATCH,
            publicationStrategy = EventPublicationStrategy.STORE_ONLY)
    record Counter(@EntityId String counterId, int value) {}

    record PutCounter(String counterId, int value) {
        @Apply Counter apply() { return new Counter(counterId, value); }
    }

    private static TestFixture fixture(boolean awaitResults, BatchInterceptor... interceptors) {
        return TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(MessageType.EVENT,
                        c -> c.toBuilder().awaitAsyncResults(awaitResults).batchInterceptors(List.of(interceptors))
                                .batchInterceptor(StallingBatchInterceptor.builder().desiredBatchSize(2)
                                        .maximumStallingDuration(Duration.ofSeconds(5))
                                        .retryFrequency(Duration.ofMillis(10)).build()).build()))
                .suppressPendingConsumerWarning();
    }

    enum StopMode { TIMEOUT, INTERRUPTED, FROM_HANDLER, FULL_CLOSE, OFFLOADED_INTERRUPTED, OFFLOADED_FROM_HANDLER, ASYNC_INTERRUPTED }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unregisteringHandlersDoesNotSkipRemainingMessagesInCurrentBatch(boolean closeSharedConsumer) throws Exception {
        SlowEventHandler handler = new SlowEventHandler();
        TestFixture testFixture = TestFixture.createAsync(
                        DefaultFluxzero.builder().configureDefaultConsumer(
                                MessageType.EVENT,
                                c -> c.toBuilder().batchInterceptor(
                                        StallingBatchInterceptor.builder()
                                                .desiredBatchSize(2)
                                                .maximumStallingDuration(Duration.ofSeconds(5))
                                                .retryFrequency(Duration.ofMillis(10))
                                                .build()).build()))
                .consumerTimeout(Duration.ofSeconds(2))
                .suppressPendingConsumerWarning();
        Registration registration = testFixture.getFluxzero().registerHandlers(handler);
        SlowEventHandler anotherRegistration = new SlowEventHandler();
        anotherRegistration.releaseFirstEvent();
        if (closeSharedConsumer) {
            testFixture.getFluxzero().registerHandlers(anotherRegistration);
        }

        try {
            testFixture.getFluxzero().execute(fc -> fc.aggregateRepository().load("aggregate", EventRoot.class)
                    .apply(List.of(new Message(1), new Message(2))));
            handler.awaitFirstEventStarted();
            try (var unregister = new TestTask(closeSharedConsumer
                    ? testFixture.getFluxzero().tracking(MessageType.EVENT)::close : registration::cancel,
                    handler::releaseFirstEvent)) {
                unregister.awaitBlockedIn(DefaultTracker.class, "awaitCancellation", Duration.ofSeconds(1));
                handler.releaseFirstEvent();
                unregister.awaitCompletion(Duration.ofSeconds(2));
            }
            assertEquals(List.of(1, 2), handler.handledEvents);
            if (closeSharedConsumer) {
                assertEquals(List.of(1, 2), anotherRegistration.handledEvents);
            }
            assertEquals(handler.lastIndex, testFixture.getFluxzero().client()
                    .getTrackingClient(MessageType.EVENT).getPosition(handler.consumerName)
                    .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
        } finally {
            handler.releaseFirstEvent();
            registration.cancel();
            testFixture.getFluxzero().close(true);
        }
    }

    @Test
    void removingOneRegistrationLeavesTheOtherConsumerHandlersActive() throws Exception {
        SlowEventHandler first = new SlowEventHandler();
        SlowEventHandler second = new SlowEventHandler();
        second.releaseFirstEvent();
        var fixture = fixture(false);
        var fluxzero = fixture.getFluxzero();
        Registration firstRegistration = fluxzero.registerHandlers(first);
        Registration secondRegistration = fluxzero.registerHandlers(second);
        try {
            fluxzero.eventGateway().publish(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            first.awaitFirstEventStarted();
            secondRegistration.cancel();
            first.releaseFirstEvent();
            assertTrue(first.bothEventsHandled.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), first.handledEvents);
            assertEquals(List.of(), second.handledEvents);
        } finally {
            first.releaseFirstEvent();
            firstRegistration.cancel();
            secondRegistration.cancel();
            fluxzero.close(true);
        }
    }

    @io.fluxzero.sdk.modeling.Aggregate
    record EventRoot() {}

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppedTopicsCannotAcknowledgeAbandonedWorkWhileOtherTopicsRemainIndependent(boolean closeAll) throws Exception {
        SlowEventHandler first = new SlowEventHandler();
        SlowEventHandler second = new SlowEventHandler();
        if (!closeAll) { second.releaseFirstEvent(); }
        CountDownLatch firstStopped = new CountDownLatch(1);
        CountDownLatch secondStopped = new CountDownLatch(1);
        var fixture = customFixture(new BatchInterceptor() {
            @Override public java.util.function.Consumer<MessageBatch> intercept(
                    java.util.function.Consumer<MessageBatch> consumer, Tracker tracker) { return consumer; }
            @Override public void shutdown(Tracker tracker) {
                ("first".equals(tracker.getTopic()) ? firstStopped : secondStopped).countDown();
            }
        });
        var fluxzero = fixture.getFluxzero();
        var tracking = fluxzero.tracking(MessageType.CUSTOM);
        Registration firstRegistration = tracking.start(fluxzero, customHandler(FirstTopic.class, "first", first));
        Registration secondRegistration = tracking.start(fluxzero, customHandler(SecondTopic.class, "second", second));
        Registration restarted = Registration.noOp();
        try {
            fluxzero.customGateway("first").sendAndForget(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            first.awaitFirstEventStarted();
            var firstClient = fluxzero.client().getTrackingClient(MessageType.CUSTOM, "first");
            firstClient.storePosition(first.consumerName, new int[]{0, 128}, 0L).get(2, TimeUnit.SECONDS);
            if (closeAll) {
                fluxzero.customGateway("second").sendAndForget(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
                second.awaitFirstEventStarted();
                fluxzero.client().getTrackingClient(MessageType.CUSTOM, "second")
                        .storePosition(second.consumerName, new int[]{0, 128}, 0L).get(2, TimeUnit.SECONDS);
            }
            Thread.currentThread().interrupt();
            try {
                if (closeAll) { tracking.close(); } else { firstRegistration.cancel(); }
            } finally {
                Thread.interrupted();
            }
            first.releaseFirstEvent();
            second.releaseFirstEvent();
            assertTrue(firstStopped.await(2, TimeUnit.SECONDS));
            assertEquals(0L, firstClient.getPosition(first.consumerName)
                    .lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
            assertEquals(closeAll ? List.of(1, 2) : List.of(1), first.handledEvents);
            if (closeAll) {
                assertTrue(secondStopped.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2), second.handledEvents);
                assertEquals(0L, fluxzero.client().getTrackingClient(MessageType.CUSTOM, "second")
                        .getPosition(second.consumerName).lowestIndexForSegment(new int[]{0, 128}).orElseThrow());
            } else {
                fluxzero.customGateway("second").sendAndForget(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
                assertTrue(second.bothEventsHandled.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2), second.handledEvents);
                SlowEventHandler replacement = new SlowEventHandler();
                replacement.releaseFirstEvent();
                restarted = tracking.start(fluxzero, customHandler(FirstTopic.class, "first", replacement));
                assertTrue(replacement.bothEventsHandled.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2), replacement.handledEvents);
            }
        } finally {
            first.releaseFirstEvent();
            second.releaseFirstEvent();
            restarted.cancel();
            firstRegistration.cancel();
            secondRegistration.cancel();
            fluxzero.close(true);
        }
    }

    @Test
    void unregisteringOneHandlerWithoutTopicsPreservesOtherRegistrationsForLaterTopics() throws Exception {
        SlowEventHandler removed = new SlowEventHandler();
        SlowEventHandler remaining = new SlowEventHandler();
        SlowEventHandler topicHandler = new SlowEventHandler();
        removed.releaseFirstEvent();
        remaining.releaseFirstEvent();
        topicHandler.releaseFirstEvent();
        var fixture = customFixture(BatchInterceptor.noOp());
        var fluxzero = fixture.getFluxzero();
        var tracking = fluxzero.tracking(MessageType.CUSTOM);
        try {
            Registration registration = tracking.start(fluxzero, customHandler(NoTopic.class, null, removed));
            tracking.start(fluxzero, customHandler(NoTopic.class, null, remaining));
            registration.cancel();
            tracking.start(fluxzero, customHandler(FirstTopic.class, "first", topicHandler));
            fluxzero.customGateway("first").sendAndForget(Guarantee.STORED, 1, 2).get(2, TimeUnit.SECONDS);
            assertTrue(topicHandler.bothEventsHandled.await(2, TimeUnit.SECONDS));
            assertTrue(remaining.bothEventsHandled.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), remaining.handledEvents);
            assertEquals(List.of(), removed.handledEvents);
        } finally {
            fluxzero.close(true);
        }
    }

    private static TestFixture customFixture(BatchInterceptor interceptor) {
        return TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(MessageType.CUSTOM,
                        c -> c.toBuilder().handlingMode(ConsumerHandlingMode.SYNC)
                                .batchInterceptor(StallingBatchInterceptor.builder().desiredBatchSize(2)
                                        .maximumStallingDuration(Duration.ofSeconds(5))
                                        .retryFrequency(Duration.ofMillis(10)).build())
                                .batchInterceptor(interceptor).build()))
                .suppressPendingConsumerWarning();
    }

    private static io.fluxzero.common.handling.Handler<DeserializingMessage> customHandler(
            Class<?> target, String topic, SlowEventHandler delegate) {
        return new io.fluxzero.common.handling.Handler<>() {
            @Override public Class<?> getTargetClass() { return target; }
            @Override public java.util.Optional<io.fluxzero.common.handling.HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return topic == null || topic.equals(Tracker.current().orElseThrow().getTopic())
                        ? java.util.Optional.of(io.fluxzero.common.handling.HandlerInvoker.call(
                                () -> delegate.handle(message.getPayload()))) : java.util.Optional.empty();
            }
        };
    }

    @Consumer(name = "shared-topics")
    private static class FirstTopic {
        @io.fluxzero.sdk.tracking.handling.HandleCustom("first") void handle(Integer ignored) {}
    }
    @Consumer(name = "shared-topics")
    private static class SecondTopic {
        @io.fluxzero.sdk.tracking.handling.HandleCustom("second") void handle(Integer ignored) {}
    }
    @Consumer(name = "shared-topics")
    private static class NoTopic {}

    private static class SlowEventHandler {
        private final CountDownLatch firstEventStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        private final List<Integer> handledEvents = new CopyOnWriteArrayList<>();
        private final CountDownLatch bothEventsHandled = new CountDownLatch(2);
        private final CountDownLatch thirdEventHandled = new CountDownLatch(1);
        private boolean failFirstEvent;
        private String consumerName;
        private Long firstIndex;
        private Long lastIndex;
        private CompletableFuture<Void> firstResult;
        private Runnable onFirstEvent = () -> {};

        private void awaitFirstEventStarted() throws InterruptedException {
            assertTrue(firstEventStarted.await(1, TimeUnit.SECONDS));
        }

        private void releaseFirstEvent() {
            releaseFirstEvent.countDown();
        }

        @HandleEvent
        CompletableFuture<Void> handle(Integer event) throws InterruptedException {
            handledEvents.add(event);
            lastIndex = DeserializingMessage.getCurrent().getIndex();
            if (event == 1) {
                firstIndex = DeserializingMessage.getCurrent().getIndex();
                consumerName = Tracker.current().orElseThrow().getName();
                firstEventStarted.countDown();
                assertTrue(releaseFirstEvent.await(5, TimeUnit.SECONDS));
                onFirstEvent.run();
                if (failFirstEvent) {
                    throw new IllegalStateException("The in-flight operation failed during shutdown");
                }
            }
            bothEventsHandled.countDown();
            if (event == 3) { thirdEventHandled.countDown(); }
            return event == 1 ? firstResult : null;
        }
    }
}
