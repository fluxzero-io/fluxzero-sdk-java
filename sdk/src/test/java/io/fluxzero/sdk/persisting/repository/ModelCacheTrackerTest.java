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

import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCacheTrackerTest {

    @Test
    void bootstrapDoesNotBlockTheLoadingCallback() throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        CompletableFuture<TrackModelUpdatesResult>
                bootstrap = new CompletableFuture<>();
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls =
                new ConcurrentLinkedQueue<>();
        when(eventStore.trackModelUpdates(any()))
                .thenAnswer(invocation -> {
                    TrackModelUpdates request =
                            invocation.getArgument(0);
                    if (request.getMaxWaitMillis()
                        == 0L) {
                        return bootstrap;
                    }
                    CompletableFuture<TrackModelUpdatesResult>
                            poll =
                            new CompletableFuture<>();
                    polls.add(poll);
                    return poll;
                });
        Cache cache = new DefaultCache();
        Entity<?> loaded =
                entity(SampleModel.class);
        cache.put("sample-1", loaded);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex))) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(1L),
                    () -> tracker.loaded(
                            "sample-1",
                            SampleModel.class,
                            10L));
            assertNull(
                    tracker.current(
                            "sample-1",
                            SampleModel.class));

            bootstrap.complete(
                    new TrackModelUpdatesResult(
                            1L, -1L,
                            10L, 10L,
                            List.of()));
            awaitNext(polls);
            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (tracker.current(
                    "sample-1",
                    SampleModel.class) == null
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }
            assertSame(
                    loaded,
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
        } finally {
            cache.close();
        }
    }

    @Test
    void bootstrapSkipsHistoryButKeepsPendingDocumentsUncacheable()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        AtomicReference<TrackModelUpdates> longPoll =
                new AtomicReference<>();
        CompletableFuture<TrackModelUpdatesResult> pending =
                new CompletableFuture<>();
        when(eventStore.trackModelUpdates(any()))
                .thenAnswer(invocation -> {
                    TrackModelUpdates request =
                            invocation.getArgument(0);
                    if (request.getMaxWaitMillis()
                        == 0L) {
                        return CompletableFuture
                                .completedFuture(
                                        new TrackModelUpdatesResult(
                                                request.getRequestId(),
                                                request.getLastStateIndex(),
                                                10L, 8L,
                                                List.of()));
                    }
                    longPoll.set(request);
                    return pending;
                });
        Cache cache = new DefaultCache();
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex))) {
            Long boundary =
                    tracker.safeDocumentBoundary();
            assertNull(boundary);

            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (longPoll.get() == null
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(
                    longPoll.get() != null,
                    "tracker did not issue its first long poll");
            assertEquals(
                    10L,
                    longPoll.get()
                            .getLastStateIndex());
            pending.complete(
                    new TrackModelUpdatesResult(
                            longPoll.get()
                                    .getRequestId(),
                            10L, 10L, 10L,
                            List.of()));
            long materializedDeadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (tracker.safeDocumentBoundary()
                   == null
                   && System.nanoTime()
                      < materializedDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    10L,
                    tracker.safeDocumentBoundary());
        } finally {
            pending.cancel(true);
            cache.close();
        }
    }

    @Test
    void fencesThenRefreshesRemoteUpdatesWithoutEvictingReplayBase()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> before = entity(SampleModel.class);
        Entity<?> after = entity(SampleModel.class);
        cache.put("sample-1", before);
        CountDownLatch refreshed = new CountDownLatch(1);
        AtomicInteger refreshCount = new AtomicInteger();
        Fluxzero application =
                mock(Fluxzero.class, CALLS_REAL_METHODS);
        Fluxzero.instance.set(application);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (targets, safeStateIndex) -> {
                                 assertEquals(
                                         Map.of(
                                                 "sample-1",
                                                 SampleModel.class),
                                         targets);
                                 assertEquals(
                                         11L,
                                         safeStateIndex);
                                 assertSame(
                                         before,
                                         cache.get(
                                                 "sample-1"));
                                 assertSame(
                                         application,
                                         Fluxzero.get());
                                 cache.put(
                                         "sample-1",
                                         after);
                                 refreshCount.incrementAndGet();
                                 refreshed.countDown();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(11L);
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            CompletableFuture<TrackModelUpdatesResult>
                    firstPoll =
                    awaitNext(polls);
            assertSame(
                    before,
                    awaitCurrent(
                            tracker, "sample-1",
                            SampleModel.class));

            firstPoll.complete(
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "commit-1", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            1L,
                                                            true))))));

            assertTrue(
                    refreshed.await(
                            5L,
                            TimeUnit.SECONDS));
            assertEquals(
                    1,
                    refreshCount.get());
            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (tracker.current(
                    "sample-1",
                    SampleModel.class) == null
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }
            assertSame(
                    after,
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
        } finally {
            Fluxzero.instance.remove();
            cache.close();
        }
    }

    @Test
    void evictionReleasesLookupWaitingForStaleRefresh()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> before = entity(SampleModel.class);
        cache.put("sample-1", before);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch continueRefresh = new CountDownLatch(1);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) -> {
                                 refreshStarted.countDown();
                                 try {
                                     assertTrue(continueRefresh.await(
                                             5L, TimeUnit.SECONDS));
                                 } catch (InterruptedException failure) {
                                     Thread.currentThread().interrupt();
                                     throw new RuntimeException(failure);
                                 }
                                 return new ModelCacheTracker
                                         .RefreshedBatch(safeStateIndex);
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            CompletableFuture<TrackModelUpdatesResult> firstPoll =
                    awaitNext(polls);
            assertSame(
                    before,
                    awaitCurrent(
                            tracker, "sample-1",
                            SampleModel.class));

            firstPoll.complete(
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "commit-1", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            1L,
                                                            true))))));
            assertTrue(refreshStarted.await(
                    5L, TimeUnit.SECONDS));

            AtomicReference<Thread> lookupThread =
                    new AtomicReference<>();
            CompletableFuture<Entity<?>> lookup =
                    new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                lookupThread.set(Thread.currentThread());
                try {
                    lookup.complete(
                            tracker.current(
                                    "sample-1",
                                    SampleModel.class));
                } catch (Throwable failure) {
                    lookup.completeExceptionally(failure);
                }
            });
            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(5L);
            while ((lookupThread.get() == null
                    || lookupThread.get().getState()
                       != Thread.State.WAITING)
                   && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    Thread.State.WAITING,
                    lookupThread.get().getState());

            cache.remove("sample-1");

            assertNull(lookup.get(1L, TimeUnit.SECONDS));
        } finally {
            continueRefresh.countDown();
            cache.close();
        }
    }

    @Test
    void cacheMissBeforeRefreshReleasesStaleLookup()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        AtomicBoolean missing = new AtomicBoolean();
        CountDownLatch missObserved = new CountDownLatch(1);
        Cache cache = new AdaptiveObjectCache() {
            @Override
            public boolean containsKey(Object id) {
                if (missing.get()) {
                    missObserved.countDown();
                    return false;
                }
                return super.containsKey(id);
            }
        };
        Entity<?> before = entity(SampleModel.class);
        cache.put("sample-1", before);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex))) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            CompletableFuture<TrackModelUpdatesResult> firstPoll =
                    awaitNext(polls);
            assertSame(
                    before,
                    awaitCurrent(
                            tracker, "sample-1",
                            SampleModel.class));

            missing.set(true);
            firstPoll.complete(
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "commit-1", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            1L,
                                                            true))))));
            assertTrue(
                    missObserved.await(
                            5L, TimeUnit.SECONDS));

            CompletableFuture<Entity<?>> lookup =
                    new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    lookup.complete(
                            tracker.current(
                                    "sample-1",
                                    SampleModel.class));
                } catch (Throwable failure) {
                    lookup.completeExceptionally(failure);
                }
            });
            assertNull(
                    lookup.get(
                            1L, TimeUnit.SECONDS));
        } finally {
            cache.close();
        }
    }

    @Test
    void pendingDocumentUpdateFencesNowAndRefreshesAfterMaterialization()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> before =
                entity(SampleModel.class);
        Entity<?> after =
                entity(SampleModel.class);
        cache.put("sample-1", before);
        CountDownLatch refreshed =
                new CountDownLatch(1);
        AtomicInteger refreshCount =
                new AtomicInteger();
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (targets, safeStateIndex) -> {
                                 assertEquals(
                                         11L,
                                         safeStateIndex);
                                 cache.put(
                                         "sample-1",
                                         after);
                                 refreshCount
                                         .incrementAndGet();
                                 refreshed.countDown();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(
                                                 safeStateIndex);
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            assertSame(
                    before,
                    awaitCurrent(
                            tracker, "sample-1",
                            SampleModel.class));
            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 10L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "commit-1", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            1L,
                                                            true))))));
            CompletableFuture<TrackModelUpdatesResult>
                    materializationPoll =
                    awaitNext(polls);

            assertNull(
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
            assertEquals(
                    0,
                    refreshCount.get());
            assertSame(
                    before,
                    cache.get("sample-1"));

            materializationPoll.complete(
                    new TrackModelUpdatesResult(
                            2L, 11L, 11L, 11L,
                            List.of()));
            assertTrue(
                    refreshed.await(
                            5L,
                            TimeUnit.SECONDS));
            assertSame(
                    after,
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
        } finally {
            cache.close();
        }
    }

    @Test
    void unrelatedNewerUpdateDoesNotInvalidateAuthoritativeLocalCommit()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> committed =
                entity(SampleModel.class);
        cache.put("sample-1", committed);
        AtomicInteger refreshCount =
                new AtomicInteger();
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) -> {
                                 refreshCount.incrementAndGet();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(
                                                 safeStateIndex);
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            assertSame(
                    committed,
                    awaitCurrent(
                            tracker, "sample-1",
                            SampleModel.class));
            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "unrelated-commit", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "another-model",
                                                            0L,
                                                            true))))));
            awaitNext(polls);

            tracker.committed(
                    "sample-1",
                    SampleModel.class,
                    10L);

            ModelCacheTracker.CurrentModel current =
                    tracker.currentVersion(
                            "sample-1",
                            SampleModel.class);
            assertSame(committed, current.entity());
            assertEquals(11L, current.validThrough());
            assertEquals(0, refreshCount.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void inFlightLocalCommitDoesNotRaceItsTrackedUpdateIntoARefresh()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> committed =
                entity(SampleModel.class);
        cache.put("sample-1", committed);
        AtomicInteger refreshCount =
                new AtomicInteger();
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) -> {
                                 refreshCount
                                         .incrementAndGet();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(
                                                 safeStateIndex);
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            assertSame(committed, awaitCurrent(
                    tracker, "sample-1",
                    SampleModel.class));
            Runnable localCommitComplete =
                    tracker.beginLocalCommit(
                            List.of(
                                    "sample-1"));
            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "local-commit", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            1L,
                                                            true))))));
            awaitNext(polls);

            assertNull(assertTimeoutPreemptively(
                    Duration.ofSeconds(1L),
                    () -> tracker.current(
                            "sample-1",
                            SampleModel.class)));
            assertEquals(0, refreshCount.get());

            tracker.committed(
                    "sample-1",
                    SampleModel.class,
                    11L);
            localCommitComplete.run();

            assertSame(
                    committed,
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
            assertEquals(
                    0, refreshCount.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void localCommitAdvancesItsBoundaryWhileATrackedPageIsBeingProcessed()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls =
                polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> initial = modelEntity(10L);
        Entity<?> committed = modelEntity(20L);
        cache.put("sample-1", initial);
        AtomicInteger refreshCount = new AtomicInteger();
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch continueProcessing = new CountDownLatch(1);
        List<ModelCommitTargetResult> blockingTargets =
                new AbstractList<>() {
                    @Override
                    public ModelCommitTargetResult get(int index) {
                        processing.countDown();
                        try {
                            assertTrue(continueProcessing.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                        return new ModelCommitTargetResult("sample-1", 1L, true);
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                };
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) -> {
                                 refreshCount.incrementAndGet();
                                 return new ModelCacheTracker.RefreshedBatch(safeStateIndex);
                             })) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> poll = awaitNext(polls);
            poll.complete(
                    new TrackModelUpdatesResult(
                            1L, 20L, 20L, 20L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "local-commit", 0, 20L, null,
                                            blockingTargets))));
            assertTrue(processing.await(5, TimeUnit.SECONDS));

            Runnable localCommitComplete =
                    tracker.beginLocalCommit(
                            List.of("sample-1"));

            cache.put("sample-1", committed);
            continueProcessing.countDown();
            awaitNext(polls);

            tracker.committed("sample-1", SampleModel.class, 20L);
            localCommitComplete.run();

            ModelCacheTracker.CurrentModel current =
                    tracker.currentVersion("sample-1", SampleModel.class);
            assertSame(committed, current.entity());
            assertEquals(20L, current.validThrough());
            assertEquals(20L, current.modelStateIndex());
            assertEquals(0, refreshCount.get());
        } finally {
            continueProcessing.countDown();
            cache.close();
        }
    }

    @Test
    void currentCacheRemainsUsableAtTheProcessedCursorWhileTheRuntimeHeadAdvances()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls =
                polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> cached = modelEntity(10L);
        cache.put("sample-1", cached);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker.RefreshedBatch(
                                             safeStateIndex))) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            1L, 11L, 20L, 20L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "unrelated", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "other-1",
                                                            0L,
                                                            true))))));
            awaitNext(polls);

            ModelCacheTracker.CurrentModel current =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(1L),
                            () -> tracker.currentVersion(
                                    "sample-1",
                                    SampleModel.class));

            assertSame(cached, current.entity());
            assertEquals(11L, current.validThrough());
            assertEquals(10L, current.modelStateIndex());
        } finally {
            cache.close();
        }
    }

    @Test
    void currentCacheRemainsUsableAtThePreviousCursorWhileANewPageIsProcessed()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls =
                polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> cached = modelEntity(10L);
        cache.put("sample-1", cached);
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch continueProcessing = new CountDownLatch(1);
        List<ModelCommitTargetResult> blockingTargets =
                new AbstractList<>() {
                    @Override
                    public ModelCommitTargetResult get(int index) {
                        processing.countDown();
                        try {
                            assertTrue(continueProcessing.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                        return new ModelCommitTargetResult("other-1", 0L, true);
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                };
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker.RefreshedBatch(safeStateIndex))) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> poll = awaitNext(polls);
            poll.complete(
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "unrelated", 0, 11L, null,
                                            blockingTargets))));
            assertTrue(processing.await(5, TimeUnit.SECONDS));

            ModelCacheTracker.CurrentModel current =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(1L),
                            () -> tracker.currentVersion(
                                    "sample-1", SampleModel.class));

            assertSame(cached, current.entity());
            assertEquals(10L, current.validThrough());
            assertEquals(10L, current.modelStateIndex());
        } finally {
            continueProcessing.countDown();
            cache.close();
        }
    }

    @Test
    void tracksUpdatesForANewModelFromTheStartOfItsLocalCommit()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> committed =
                modelEntity(11L);
        AtomicInteger refreshCount =
                new AtomicInteger();
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) -> {
                                 refreshCount
                                         .incrementAndGet();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(
                                                 safeStateIndex);
                             })) {
            tracker.prepare();
            CompletableFuture<TrackModelUpdatesResult>
                    firstPoll =
                    awaitNext(polls);
            Runnable localCommitComplete =
                    tracker.beginLocalCommit(
                            List.of(
                                    "sample-1"));
            firstPoll.complete(
                    new TrackModelUpdatesResult(
                            1L, 12L, 12L, 12L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "local-commit", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            0L,
                                                            true))),
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "unrelated-commit", 0,
                                            12L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "another-model",
                                                            0L,
                                                            true))))));
            awaitNext(polls);

            cache.put(
                    "sample-1",
                    committed);
            tracker.committed(
                    "sample-1",
                    SampleModel.class,
                    11L);
            localCommitComplete.run();

            assertSame(
                    committed,
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
            assertEquals(
                    0, refreshCount.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void failedLocalCommitReleasesItsDeferredRemoteRefresh()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        cache.put(
                "sample-1",
                entity(SampleModel.class));
        CountDownLatch refreshed =
                new CountDownLatch(1);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) -> {
                                 refreshed.countDown();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(
                                                 safeStateIndex);
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            Runnable localCommitComplete =
                    tracker.beginLocalCommit(
                            List.of(
                                    "sample-1"));
            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            1L, 11L, 11L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.COMMIT,
                                            "remote-commit", 0,
                                            11L, null,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            "sample-1",
                                                            1L,
                                                            true))))));
            awaitNext(polls);
            assertEquals(
                    1L,
                    refreshed.getCount());

            localCommitComplete.run();

            assertTrue(
                    refreshed.await(
                            5L,
                            TimeUnit.SECONDS));
        } finally {
            cache.close();
        }
    }

    @Test
    void preparedHardDeleteClearsCacheWithoutRetainingDeletedIds()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        cache.put(
                "sample-1",
                entity(SampleModel.class));
        cache.put(
                "sample-2",
                entity(SampleModel.class));
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex))) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            tracker.loaded(
                    "sample-2",
                    SampleModel.class,
                    10L);

            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            2L, 12L, 12L, 11L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.HARD_DELETE,
                                            "deletion-1", 0,
                                            12L, null,
                                            List.of()))));

            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (!cache.isEmpty()
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(cache.isEmpty());
            assertNull(
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
            assertNull(
                    tracker.safeDocumentBoundary(),
                    "direct documents must not be cached while erasure is pending");

            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            3L, 12L, 12L, 12L,
                            List.of()));
            long materializedDeadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (tracker.safeDocumentBoundary()
                   == null
                   && System.nanoTime()
                      < materializedDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    12L,
                    tracker.safeDocumentBoundary());
        } finally {
            cache.close();
        }
    }

    @Test
    void shutdownCancelsTheOutstandingLongPoll()
            throws Exception {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        cache.put(
                "sample-1",
                entity(SampleModel.class));
        ModelCacheTracker tracker =
                new ModelCacheTracker(
                        eventStore, cache,
                        (ignored, safeStateIndex) ->
                                new ModelCacheTracker
                                        .RefreshedBatch(
                                                safeStateIndex));
        try {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            CompletableFuture<TrackModelUpdatesResult>
                    pending =
                    awaitNext(polls);

            tracker.close();

            assertTrue(
                    pending.isCancelled());
            assertNull(
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
        } finally {
            tracker.close();
            cache.close();
        }
    }

    @Test
    void unsupportedTrackingDisablesTheFastPath() {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        when(eventStore.trackModelUpdates(any()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new UnsupportedOperationException(
                                        "old runtime")));
        Cache cache = new DefaultCache();
        Entity<?> cached =
                entity(SampleModel.class);
        cache.put("sample-1", cached);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex))) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (tracker.current(
                    "sample-1",
                    SampleModel.class) != null
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }

            assertNull(
                    tracker.current(
                            "sample-1",
                            SampleModel.class));
            assertSame(
                    cached,
                    cache.get("sample-1"));
        } finally {
            cache.close();
        }
    }

    @Test
    void localCommitPublishesTheBoundaryOfTheNewCachedRevision() {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        Cache cache = new DefaultCache();
        Entity<?> initial =
                modelEntity(10L);
        Entity<?> committed =
                modelEntity(20L);
        cache.put("sample-1", initial);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex))) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            long deadline =
                    System.nanoTime()
                    + TimeUnit.SECONDS
                            .toNanos(5L);
            while (tracker.current(
                    "sample-1",
                    SampleModel.class) == null
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }
            assertSame(
                    initial,
                    tracker.current(
                            "sample-1",
                            SampleModel.class));

            cache.put("sample-1", committed);
            assertNull(
                    tracker.currentVersion(
                            "sample-1",
                            SampleModel.class));

            tracker.committed(
                    "sample-1",
                    SampleModel.class,
                    20L);

            ModelCacheTracker.CurrentModel current =
                    tracker.currentVersion(
                            "sample-1",
                            SampleModel.class);
            assertSame(committed, current.entity());
            assertEquals(20L, current.validThrough());
            assertEquals(20L, current.modelStateIndex());
        } finally {
            cache.close();
        }
    }

    private static ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
            polls(EventStoreClient eventStore) {
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                result =
                new ConcurrentLinkedQueue<>();
        when(eventStore.trackModelUpdates(any()))
                .thenAnswer(invocation -> {
                    TrackModelUpdates request =
                            invocation.getArgument(0);
                    if (request.getMaxWaitMillis()
                        == 0L) {
                        return CompletableFuture
                                .completedFuture(
                                        new TrackModelUpdatesResult(
                                                request.getRequestId(),
                                                request.getLastStateIndex(),
                                                10L, 10L,
                                                List.of()));
                    }
                    CompletableFuture<TrackModelUpdatesResult>
                            poll =
                            new CompletableFuture<>();
                    result.add(poll);
                    return poll;
                });
        return result;
    }

    private static Entity<?> awaitCurrent(
            ModelCacheTracker tracker,
            String modelId,
            Class<?> modelType) {
        long deadline =
                System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5L);
        Entity<?> current;
        while ((current = tracker.current(
                modelId, modelType)) == null
               && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(current != null);
        return current;
    }

    private static void completeNext(
            ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                    polls,
            TrackModelUpdatesResult result)
            throws InterruptedException {
        awaitNext(polls).complete(result);
    }

    private static CompletableFuture<TrackModelUpdatesResult>
            awaitNext(
                    ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                            polls)
            throws InterruptedException {
        long deadline =
                System.nanoTime()
                + TimeUnit.SECONDS
                        .toNanos(5L);
        CompletableFuture<TrackModelUpdatesResult>
                poll;
        while ((poll = polls.poll()) == null
               && System.nanoTime()
                  < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(
                poll != null,
                "tracker did not issue a long poll");
        return poll;
    }

    @SuppressWarnings("unchecked")
    private static Entity<?> entity(
            Class<?> modelType) {
        Entity<Object> entity =
                mock(Entity.class);
        when(entity.type())
                .thenReturn(
                        (Class<Object>) modelType);
        return entity;
    }

    private static Entity<?> modelEntity(
            long stateIndex) {
        return ImmutableModelRoot
                .<SampleModel>builder()
                .id("sample-1")
                .type(SampleModel.class)
                .value(new SampleModel("sample-1"))
                .stateIndex(stateIndex)
                .build();
    }

    private record SampleModel(String id) {
    }
}
