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

package io.fluxzero.sdk.persisting.repository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Isolated("Observes the shared ModelCacheTracker logger")
class ModelCacheTrackerBootstrapRaceTest {
    @ParameterizedTest
    @ValueSource(longs = {1L, 116986164019200000L})
    void successfulBootstrapCannotBeRestartedByAConcurrentStarter(long stateIndex) throws Exception {
        EventStoreClient store = mock(EventStoreClient.class);
        BlockingQueue<Poll> bootstraps = new LinkedBlockingQueue<>();
        BlockingQueue<Poll> tracks = new LinkedBlockingQueue<>();
        Queue<Poll> outstanding = new ConcurrentLinkedQueue<>();
        when(store.trackModelUpdates(any())).thenAnswer(invocation -> {
            var request = (TrackModelUpdates) invocation.getArgument(0);
            var poll = new Poll(request, new CompletableFuture<>());
            outstanding.add(poll);
            (request.getMaxWaitMillis() == 0 ? bootstraps : tracks).add(poll);
            return poll.response();
        });
        var failures = new ConcurrentLinkedQueue<String>();
        var logger = (Logger) LoggerFactory.getLogger(ModelCacheTracker.class);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getThrowableProxy() != null) {
                    failures.add(event.getThrowableProxy().getMessage());
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
        var enteredDoneCheck = new CountDownLatch(1);
        var finishDoneCheck = new CountDownLatch(1);
        var cache = new ModelCache(new DefaultCache());
        var tracker = new ModelCacheTracker(store, cache,
                (ids, boundary) -> new ModelCacheTracker.RefreshedBatch(boundary, Map.of()));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var firstReady = tracker.readiness();
                var firstBootstrap = take(bootstraps);
                // Gate precisely between start() observing started=false and observing bootstrap completion.
                // Only scheduling is controlled: the original readiness future and store responses stay real.
                var gatedReadiness = new CompletableFuture<Boolean>() {
                    @Override public boolean isDone() {
                        enteredDoneCheck.countDown();
                        await(finishDoneCheck);
                        return firstReady.isDone();
                    }
                };
                firstReady.whenComplete((value, failure) -> {
                    if (failure == null) gatedReadiness.complete(value);
                    else gatedReadiness.completeExceptionally(failure);
                });
                var bootstrapField = ModelCacheTracker.class.getDeclaredField("bootstrap");
                bootstrapField.setAccessible(true);
                bootstrapField.set(tracker, gatedReadiness);
                var contender = workers.submit(tracker::prepare);
                await(enteredDoneCheck);
                firstBootstrap.complete(-1L, List.of());
                assertTrue(firstReady.get(5, TimeUnit.SECONDS));
                var firstTrack = take(tracks);
                assertEquals(-1L, firstTrack.request().getLastStateIndex());
                finishDoneCheck.countDown();
                contender.get(5, TimeUnit.SECONDS);

                var update = new ModelUpdate(ModelUpdateKind.COMMIT, "first", 0, stateIndex, null, List.of());
                var currentReadiness = (CompletableFuture<?>) bootstrapField.get(tracker);
                boolean restarted = currentReadiness != gatedReadiness;
                if (restarted) {
                    // Both responses are valid for their own request. The extra bootstrap moves the shared
                    // cursor while the original tracker is still awaiting its first page.
                    take(bootstraps).complete(stateIndex, List.of(update));
                    assertEquals(true, currentReadiness.get(5, TimeUnit.SECONDS));
                    assertEquals(stateIndex, take(tracks).request().getLastStateIndex());
                }
                firstTrack.complete(stateIndex, List.of(update));
                // The next request proves processing (or failure handling) of that page has finished.
                assertEquals(stateIndex, take(tracks).request().getLastStateIndex());
                assertAll(
                        () -> assertFalse(restarted, "Successful bootstrap must start exactly one tracker"),
                        () -> assertTrue(failures.isEmpty(), "Valid ordered pages must not fail: " + failures));
            } finally {
                finishDoneCheck.countDown();
                tracker.close();
                outstanding.forEach(poll -> poll.response().cancel(true));
                cache.close();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void failedInitialBootstrapCanRetryWithoutRestartingAfterSuccess() throws Exception {
        EventStoreClient store = mock(EventStoreClient.class);
        BlockingQueue<Poll> requests = new LinkedBlockingQueue<>();
        when(store.trackModelUpdates(any())).thenAnswer(invocation -> {
            var poll = new Poll(invocation.getArgument(0), new CompletableFuture<>());
            requests.add(poll);
            return poll.response();
        });
        var cache = new ModelCache(new DefaultCache());
        try (var tracker = new ModelCacheTracker(store, cache,
                (ids, boundary) -> new ModelCacheTracker.RefreshedBatch(boundary, Map.of()))) {
            var firstReady = tracker.readiness();
            var first = take(requests);
            assertEquals(0L, first.request().getMaxWaitMillis());
            first.response().completeExceptionally(new IllegalStateException("Temporary store failure"));
            assertFalse(firstReady.get(5, TimeUnit.SECONDS));

            var retryReady = tracker.readiness();
            var retry = take(requests);
            assertEquals(0L, retry.request().getMaxWaitMillis());
            retry.complete(-1L, List.of());
            assertTrue(retryReady.get(5, TimeUnit.SECONDS));
            var tracking = take(requests);
            assertTrue(tracking.request().getMaxWaitMillis() > 0);
            tracker.prepare();
            assertTrue(tracker.readiness().get(5, TimeUnit.SECONDS));
            verify(store, times(3)).trackModelUpdates(any());
            tracker.close();
            assertTrue(tracking.response().isCancelled());
        } finally {
            cache.close();
        }
    }

    @Test
    void closingDuringInitialBootstrapCancelsTheRequestAndPreventsTracking() throws Exception {
        EventStoreClient store = mock(EventStoreClient.class);
        var waitingForResponse = new CountDownLatch(1);
        var response = new CompletableFuture<TrackModelUpdatesResult>() {
            @Override public TrackModelUpdatesResult join() {
                // pendingBootstrap has been published before the tracker starts waiting for this response.
                waitingForResponse.countDown();
                return super.join();
            }
        };
        when(store.trackModelUpdates(any())).thenReturn(response);
        var cache = new ModelCache(new DefaultCache());
        try (var tracker = new ModelCacheTracker(store, cache,
                (ids, boundary) -> new ModelCacheTracker.RefreshedBatch(boundary, Map.of()))) {
            var ready = tracker.readiness();
            await(waitingForResponse);
            tracker.close();
            assertTrue(response.isCancelled());
            assertFalse(ready.get(5, TimeUnit.SECONDS));
            tracker.prepare();
            assertFalse(tracker.readiness().get(5, TimeUnit.SECONDS));
            verify(store, times(1)).trackModelUpdates(any());
        } finally {
            cache.close();
        }
    }

    private static Poll take(BlockingQueue<Poll> queue) throws InterruptedException {
        var poll = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(poll, "Expected a tracking request");
        return poll;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected scheduling boundary");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Poll(TrackModelUpdates request, CompletableFuture<TrackModelUpdatesResult> response) {
        void complete(long head, List<ModelUpdate> updates) {
            long previous = request.getLastStateIndex();
            for (var update : updates) {
                assertTrue(update.getStateIndex() > previous, "Store page must respect its exclusive cursor");
                previous = update.getStateIndex();
            }
            response.complete(new TrackModelUpdatesResult(request.getRequestId(), previous, head, head, updates));
        }
    }
}
