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
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.DocumentProjection;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;
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
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void restoresHealthOnlyAfterAValidRecoveryPageHasBeenProcessed() throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        ModelCache cache = new ModelCache(new DefaultCache());
        Entity<?> cached = entity(SampleModel.class);
        cache.put("sample-1", cached);
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch continueProcessing = new CountDownLatch(1);
        try (ModelCacheTracker tracker = new ModelCacheTracker(
                eventStore, cache,
                (ignored, boundary) -> new ModelCacheTracker.RefreshedBatch(boundary, Map.of()))) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> first = awaitNext(polls);
            assertSame(cached, awaitCurrent(tracker, "sample-1", SampleModel.class));
            first.completeExceptionally(new IllegalStateException("temporary transport failure"));
            CompletableFuture<TrackModelUpdatesResult> recovery = awaitNext(polls);
            assertNull(tracker.current("sample-1", SampleModel.class));
            assertNull(tracker.safeDocumentBoundary());

            // An empty page cannot claim that it processed an update beyond the request cursor.
            recovery.complete(new TrackModelUpdatesResult(1L, 11L, 11L, 11L, List.of()));
            CompletableFuture<TrackModelUpdatesResult> valid = awaitNext(polls);
            assertNull(tracker.current("sample-1", SampleModel.class));
            List<ModelCommitTargetResult> blockingTargets = new AbstractList<>() {
                @Override
                public ModelCommitTargetResult get(int index) {
                    processing.countDown();
                    awaitLatch(continueProcessing);
                    return new ModelCommitTargetResult("unrelated", 0L, true);
                }

                @Override
                public int size() {
                    return 1;
                }
            };
            valid.complete(new TrackModelUpdatesResult(
                    2L, 11L, 20L, 20L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "recovered", 0,
                                            11L, null, blockingTargets))));
            assertTrue(processing.await(5, TimeUnit.SECONDS));
            assertNull(tracker.current("sample-1", SampleModel.class));
            assertNull(tracker.safeDocumentBoundary());
            continueProcessing.countDown();
            awaitNext(polls);

            ModelCacheTracker.CurrentModel current = tracker.currentVersion("sample-1", SampleModel.class);
            assertNotNull(current);
            assertSame(cached, current.entity());
            assertEquals(11L, current.validThrough(), "Only the processed prefix is a cache proof");
            assertEquals(20L, tracker.safeDocumentBoundary());
        } finally {
            continueProcessing.countDown();
            cache.close();
        }
    }

    @Test
    void forgettingOneModelReleasesItsReaderAndDiscardsLateRefreshPublication() throws Exception {
        forgetDuringRefresh(false, false);
    }

    @Test
    void forgettingAllModelsReleasesTheirReadersAndDiscardsLateRefreshPublication() throws Exception {
        forgetDuringRefresh(true, false);
    }

    @Test
    void unsupportedTrackingReleasesWaitingReadersAndPreservesCachedValues() throws Exception {
        forgetDuringRefresh(false, true);
    }

    private void forgetDuringRefresh(boolean all, boolean unsupported) throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        ConcurrentLinkedQueue<Runnable> evictions = new ConcurrentLinkedQueue<>();
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, evictions::add, null));
        Entity<?> cached = entity(SampleModel.class);
        cache.put("sample-1", cached);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch continueRefresh = new CountDownLatch(1);
        CountDownLatch nextRefresh = new CountDownLatch(1);
        try (ModelCacheTracker tracker = new ModelCacheTracker(eventStore, cache, (targets, boundary) -> {
            if (targets.containsKey("sample-1")) {
                refreshStarted.countDown();
                awaitLatch(continueRefresh);
                // Reconstruction finishes after the tracking entry that initiated this refresh was retired.
            } else {
                nextRefresh.countDown();
            }
            return new ModelCacheTracker.RefreshedBatch(
                    boundary, targets.containsKey("sample-1") ? Map.of("sample-1", cached) : Map.of());
        })) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> first = awaitNext(polls);
            assertSame(cached, awaitCurrent(tracker, "sample-1", SampleModel.class));
            first.complete(new TrackModelUpdatesResult(
                    1L, 11L, 11L, 11L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "remote", 0, 11L, null,
                                            List.of(new ModelCommitTargetResult("sample-1", 1L, true))))));
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<Entity<?>> lookup = new CompletableFuture<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    lookup.complete(tracker.current("sample-1", SampleModel.class));
                } catch (Throwable failure) {
                    lookup.completeExceptionally(failure);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (reader.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, reader.getState());
            if (unsupported) {
                awaitNext(polls).completeExceptionally(new UnsupportedOperationException("old runtime"));
            } else if (all) {
                cache.clear();
                tracker.forgetAll();
            } else {
                cache.remove("sample-1");
                tracker.forget("sample-1");
            }
            evictions.forEach(Runnable::run);
            assertNull(lookup.get(1, TimeUnit.SECONDS));
            if (unsupported) {
                assertSame(cached, cache.get("sample-1"));
                assertNull(tracker.current("sample-1", SampleModel.class));
                return;
            }

            // A second refresh is a completion barrier for the first refresh's publication on the serial executor.
            cache.put("other", entity(SampleModel.class));
            tracker.loaded("other", SampleModel.class, 11L);
            awaitNext(polls).complete(new TrackModelUpdatesResult(
                    2L, 12L, 12L, 12L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "other-update", 0, 12L, null,
                                            List.of(new ModelCommitTargetResult("other", 1L, true))))));
            awaitNext(polls);
            continueRefresh.countDown();
            assertTrue(nextRefresh.await(5, TimeUnit.SECONDS));
            assertNull(cache.get("sample-1"), "An invalidated refresh must not write the discarded value");
            assertNull(tracker.current("sample-1", SampleModel.class),
                       "An invalidated refresh must not publish a new cache proof");
        } finally {
            continueRefresh.countDown();
            cache.close();
        }
    }

    @Test
    void synchronousEvictionDoesNotWaitForAnInProgressCacheRead() throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch continueReading = new CountDownLatch(1);
        AtomicBoolean pauseRead = new AtomicBoolean();
        ModelCache cache = new ModelCache(new SoftReferenceCache(10, Runnable::run, null) {
            @Override
            public <T> T get(Object id) {
                if (pauseRead.compareAndSet(true, false)) {
                    reading.countDown();
                    awaitLatch(continueReading);
                }
                return super.get(id);
            }
        });
        Entity<?> cached = entity(SampleModel.class);
        cache.put("sample-1", cached);
        try (ModelCacheTracker tracker = new ModelCacheTracker(
                eventStore, cache,
                (ignored, boundary) -> new ModelCacheTracker.RefreshedBatch(boundary, Map.of()))) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            awaitNext(polls);
            assertSame(cached, awaitCurrent(tracker, "sample-1", SampleModel.class));
            pauseRead.set(true);
            CompletableFuture<Entity<?>> reader = CompletableFuture.supplyAsync(
                    () -> tracker.current("sample-1", SampleModel.class),
                    task -> Thread.ofVirtual().start(task));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            // A synchronous cache listener must not wait for the tracker read: a custom cache may be holding
            // its own lock while notifying listeners, and that read may need the same cache lock to finish.
            CompletableFuture<Void> eviction = CompletableFuture.runAsync(
                    () -> cache.remove("sample-1"), task -> Thread.ofVirtual().start(task));
            eviction.get(1, TimeUnit.SECONDS);
            continueReading.countDown();
            assertNull(reader.get(1, TimeUnit.SECONDS));
            assertNull(tracker.current("sample-1", SampleModel.class));
        } finally {
            continueReading.countDown();
            cache.close();
        }
    }

    @Test
    void lateDocumentRefreshDoesNotOverwriteANewerLocalCommit() throws Exception {
        documentRefreshRace(false, false, false);
    }

    @Test
    void lateHeadlessDocumentRefreshDoesNotOverwriteANewerLocalCommit() throws Exception {
        documentRefreshRace(false, false, true);
    }

    @Test
    void lateDocumentRefreshCannotRepopulateAHardDeletedModel() throws Exception {
        documentRefreshRace(true, false, false);
    }

    @Test
    void lateDocumentRefreshCannotReplaceANewlyLoadedAbsenceAfterHardDelete() throws Exception {
        documentRefreshRace(true, true, false);
    }

    @Test
    void lateDocumentRefreshPreservesAHardDeletedAndRecreatedModel() throws Exception {
        documentRefreshRace(true, true, true);
    }

    private void documentRefreshRace(boolean hardDelete, boolean reload, boolean alternateValue) throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        CountDownLatch cacheUpdated = new CountDownLatch(1);
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public <U, T> void updateAll(
                    Iterable<? extends U> updates, Function<? super U, ?> keyFunction,
                    BiFunction<? super U, ? super T, ? extends T> updateFunction) {
                super.updateAll(updates, keyFunction, updateFunction);
                cacheUpdated.countDown();
            }
        });
        Entity<?> initial = documentEntity(10L, "initial");
        boolean headlessRead = !hardDelete && alternateValue;
        Entity<?> older = documentEntity(headlessRead ? -1L : 11L, headlessRead ? null : "older");
        ModelHeadState oldHead = headlessRead ? null
                : new ModelHeadState("document-1", TrackedDocument.class.getSimpleName(), 1L, 11L, false, false);
        ModelReplayCursor.DocumentReader reader = (id, type, migration) -> {
            readStarted.countDown();
            awaitLatch(continueRead);
            return new ModelReplayCursor.DocumentVersion(older, oldHead);
        };
        ModelReplayCursor cursor = new ModelReplayCursor(
                eventStore, null, null, null, cache, null, reader, null);
        cache.put("document-1", initial);
        try (ModelCacheTracker tracker = new ModelCacheTracker(eventStore, cache, cursor::refresh)) {
            tracker.loaded("document-1", TrackedDocument.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> update = awaitNext(polls);
            assertSame(initial, awaitCurrent(tracker, "document-1", TrackedDocument.class));
            update.complete(new TrackModelUpdatesResult(
                    1L, 11L, 11L, 11L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "remote-document", 0, 11L, null,
                                            List.of(new ModelCommitTargetResult("document-1", 1L, false))))));
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            Entity<?> expected;
            if (hardDelete) {
                awaitNext(polls).complete(new TrackModelUpdatesResult(
                        2L, 12L, 12L, 12L,
                        List.of(new ModelUpdate(ModelUpdateKind.HARD_DELETE, "delete-document", 0, 12L,
                                                null, List.of()))));
                awaitNext(polls);
                expected = reload ? documentEntity(alternateValue ? 13L : -1L,
                                                   alternateValue ? "recreated" : null) : null;
                if (reload) {
                    cache.put("document-1", expected);
                    tracker.loaded("document-1", TrackedDocument.class, alternateValue ? 13L : 12L);
                }
            } else {
                expected = documentEntity(12L, "newer-local-commit");
                cache.put("document-1", expected);
                tracker.committed("document-1", TrackedDocument.class, 12L);
            }
            continueRead.countDown();
            assertTrue(cacheUpdated.await(5, TimeUnit.SECONDS));
            assertSame(expected, cache.get("document-1"));
            if (expected != null) {
                assertSame(expected, awaitCurrent(tracker, "document-1", TrackedDocument.class));
            } else {
                assertNull(tracker.current("document-1", TrackedDocument.class));
            }
        } finally {
            continueRead.countDown();
            cache.close();
        }
    }

    @Test
    void explicitInvalidationMasksACacheUpdateThatAlreadySelectedItsValue() throws Exception {
        invalidationDuringCachePublication(false, false);
    }

    @Test
    void explicitInvalidationMasksAPublicationDetachedByAnEvictionListener() throws Exception {
        invalidationDuringCachePublication(true, false);
    }

    @Test
    void reentrantInvalidationDoesNotWaitForItsOwnCachePublication() throws Exception {
        invalidationDuringCachePublication(false, true);
    }

    @Test
    void retirementCleansItsOwnLateWriteAfterAnEarlierCacheRemoval() throws Exception {
        invalidationDuringCachePublication(true, false, false);
    }

    private void invalidationDuringCachePublication(boolean detach, boolean reentrant) throws Exception {
        invalidationDuringCachePublication(detach, reentrant, true);
    }

    private void invalidationDuringCachePublication(boolean detach, boolean reentrant, boolean clearAfter)
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        CountDownLatch candidateSelected = new CountDownLatch(1);
        CountDownLatch continuePublication = new CountDownLatch(1);
        CountDownLatch publicationComplete = new CountDownLatch(1);
        AtomicReference<ModelCacheTracker> trackerReference = new AtomicReference<>();
        AtomicReference<ModelCache> cacheReference = new AtomicReference<>();
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public <U, T> void updateAll(
                    Iterable<? extends U> updates, Function<? super U, ?> keyFunction,
                    BiFunction<? super U, ? super T, ? extends T> updateFunction) {
                super.<U, T>updateAll(updates, keyFunction, (update, current) -> {
                    T selected = updateFunction.apply(update, current);
                    if (detach) {
                        // The listener removes the entry after its publication check, while compute is still active.
                        remove(keyFunction.apply(update));
                    }
                    if (reentrant) {
                        trackerReference.get().forgetAll();
                        cacheReference.get().clear();
                    }
                    candidateSelected.countDown();
                    awaitLatch(continuePublication);
                    return selected;
                });
                publicationComplete.countDown();
            }

            @Override
            public <T> T remove(Object id) {
                T removed = super.remove(id);
                if (continuePublication.getCount() == 0) {
                    publicationComplete.countDown();
                }
                return removed;
            }
        });
        cacheReference.set(cache);
        Entity<?> initial = modelEntity(10L);
        Entity<?> updated = modelEntity(11L);
        cache.put("sample-1", initial);
        try (ModelCacheTracker tracker = new ModelCacheTracker(eventStore, cache,
                (targets, boundary) -> new ModelCacheTracker.RefreshedBatch(
                        boundary, Map.of("sample-1", updated)))) {
            trackerReference.set(tracker);
            tracker.loaded("sample-1", SampleModel.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> update = awaitNext(polls);
            assertSame(initial, awaitCurrent(tracker, "sample-1", SampleModel.class));
            update.complete(new TrackModelUpdatesResult(
                    1L, 11L, 11L, 11L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "update", 0, 11L, null,
                                            List.of(new ModelCommitTargetResult("sample-1", 1L, true))))));
            assertTrue(candidateSelected.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> invalidated = new CompletableFuture<>();
            if (!reentrant) {
                Thread invalidator = Thread.ofVirtual().start(() -> {
                    try {
                        tracker.forgetAll();
                        if (clearAfter) {
                            cache.clear();
                        }
                        invalidated.complete(null);
                    } catch (Throwable failure) {
                        invalidated.completeExceptionally(failure);
                    }
                });
                invalidated.get(5, TimeUnit.SECONDS);
                assertNull(cache.get("sample-1"), "The active physical write must already be invisible");
                assertNull(tracker.current("sample-1", SampleModel.class));
            }
            continuePublication.countDown();
            if (reentrant) {
                assertTrue(publicationComplete.await(5, TimeUnit.SECONDS));
            } else {
                invalidated.get(5, TimeUnit.SECONDS);
            }
            assertNull(cache.get("sample-1"));
            assertNull(tracker.current("sample-1", SampleModel.class));
        } finally {
            continuePublication.countDown();
            cache.close();
        }
    }

    @Test
    void lateMissingEventHeadDoesNotRemoveANewerLocalCommit() throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        CountDownLatch nextRefresh = new CountDownLatch(1);
        ModelCache cache = new ModelCache(new DefaultCache());
        Entity<?> initial = modelEntity(10L);
        Entity<?> newer = modelEntity(12L);
        cache.put("sample-1", initial);
        try (ModelCacheTracker tracker = new ModelCacheTracker(eventStore, cache, (targets, boundary) -> {
            if (targets.containsKey("sample-1")) {
                readStarted.countDown();
                awaitLatch(continueRead);
                Map<String, Entity<?>> absent = new java.util.HashMap<>();
                absent.put("sample-1", null);
                return new ModelCacheTracker.RefreshedBatch(boundary, absent);
            }
            nextRefresh.countDown();
            return new ModelCacheTracker.RefreshedBatch(boundary, Map.of());
        })) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> update = awaitNext(polls);
            assertSame(initial, awaitCurrent(tracker, "sample-1", SampleModel.class));
            update.complete(new TrackModelUpdatesResult(
                    1L, 11L, 11L, 11L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "update", 0, 11L, null,
                                            List.of(new ModelCommitTargetResult("sample-1", 1L, true))))));
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            cache.put("sample-1", newer);
            tracker.committed("sample-1", SampleModel.class, 12L);
            cache.put("other", entity(SampleModel.class));
            tracker.loaded("other", SampleModel.class, 11L);
            awaitNext(polls).complete(new TrackModelUpdatesResult(
                    2L, 12L, 12L, 12L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "other-update", 0, 12L, null,
                                            List.of(new ModelCommitTargetResult("other", 1L, true))))));
            awaitNext(polls);
            continueRead.countDown();
            assertTrue(nextRefresh.await(5, TimeUnit.SECONDS));
            assertSame(newer, cache.get("sample-1"));
            assertSame(newer, tracker.current("sample-1", SampleModel.class));
        } finally {
            continueRead.countDown();
            cache.close();
        }
    }

    @Test
    void headlessDocumentRefreshReplacesACachedValueOlderThanItsSafeBoundary() throws Exception {
        headlessDocumentRefresh(false);
    }

    @Test
    void headlessDocumentAbsenceReplacesACachedValueOlderThanItsSafeBoundary() throws Exception {
        headlessDocumentRefresh(true);
    }

    private void headlessDocumentRefresh(boolean absent) throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>> polls = polls(eventStore);
        Entity<?> initial = documentEntity(10L, "old");
        Entity<?> refreshed = documentEntity(-1L, absent ? null : "refreshed-without-head");
        ModelCache cache = new ModelCache(new DefaultCache());
        ModelReplayCursor cursor = new ModelReplayCursor(
                eventStore, null, null, null, cache, null,
                (id, type, migration) -> new ModelReplayCursor.DocumentVersion(refreshed, null), null);
        cache.put("document-1", initial);
        try (ModelCacheTracker tracker = new ModelCacheTracker(eventStore, cache, cursor::refresh)) {
            tracker.loaded("document-1", TrackedDocument.class, 10L);
            CompletableFuture<TrackModelUpdatesResult> update = awaitNext(polls);
            assertSame(initial, awaitCurrent(tracker, "document-1", TrackedDocument.class));
            update.complete(new TrackModelUpdatesResult(
                    1L, 11L, 11L, 11L,
                    List.of(new ModelUpdate(ModelUpdateKind.COMMIT, "update", 0, 11L, null,
                                            List.of(new ModelCommitTargetResult("document-1", 1L, false))))));
            awaitNext(polls);
            assertSame(refreshed, awaitCurrent(tracker, "document-1", TrackedDocument.class));
        } finally {
            cache.close();
        }
    }

    private static Entity<?> documentEntity(long stateIndex, String value) {
        return ImmutableModelRoot.<TrackedDocument>builder()
                .id("document-1").type(TrackedDocument.class)
                .value(value == null ? null : new TrackedDocument("document-1", value))
                .stateIndex(stateIndex).sequenceNumber(stateIndex < 0L ? -1L : stateIndex - 10L).build();
    }

    @Model(persistence = ModelPersistence.DOCUMENT, document = @DocumentProjection(collection = "trackedDocuments"))
    private record TrackedDocument(@EntityId String id, String value) {
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

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
        ModelCache cache = new ModelCache(new DefaultCache());
        Entity<?> loaded =
                entity(SampleModel.class);
        cache.put("sample-1", loaded);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex, Map.of()))) {
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
        ModelCache cache = new ModelCache(new DefaultCache());
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex, Map.of()))) {
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                 refreshCount.incrementAndGet();
                                 refreshed.countDown();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(11L, Map.of("sample-1", after));
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                         .RefreshedBatch(safeStateIndex, Map.of());
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
        ModelCache cache = new ModelCache(new AdaptiveObjectCache() {
            @Override
            public boolean containsKey(Object id) {
                if (missing.get()) {
                    missObserved.countDown();
                    return false;
                }
                return super.containsKey(id);
            }
        });
        Entity<?> before = entity(SampleModel.class);
        cache.put("sample-1", before);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker
                                             .RefreshedBatch(
                                                     safeStateIndex, Map.of()))) {
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                 refreshCount
                                         .incrementAndGet();
                                 refreshed.countDown();
                                 return new ModelCacheTracker
                                         .RefreshedBatch(
                                                 safeStateIndex, Map.of("sample-1", after));
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
            awaitUnavailable(
                    tracker, "sample-1",
                    SampleModel.class);
            CompletableFuture<TrackModelUpdatesResult>
                    materializationPoll =
                    awaitNext(polls);

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
                    awaitCurrent(tracker, "sample-1", SampleModel.class));
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                                 safeStateIndex, Map.of());
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
        ModelCache cache = new ModelCache(new DefaultCache());
        Entity<?> before = modelEntity(10L);
        Entity<?> committed = modelEntity(11L);
        cache.put("sample-1", before);
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
                                                 safeStateIndex, Map.of());
                             })) {
            tracker.loaded(
                    "sample-1",
                    SampleModel.class,
                    10L);
            assertSame(before, awaitCurrent(
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

            cache.put("sample-1", committed);
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                 return new ModelCacheTracker.RefreshedBatch(safeStateIndex, Map.of());
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
        ModelCache cache = new ModelCache(new DefaultCache());
        Entity<?> cached = modelEntity(10L);
        cache.put("sample-1", cached);
        try (ModelCacheTracker tracker =
                     new ModelCacheTracker(
                             eventStore, cache,
                             (ignored, safeStateIndex) ->
                                     new ModelCacheTracker.RefreshedBatch(
                                             safeStateIndex, Map.of()))) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            // Loading publishes asynchronously during bootstrap. Establish the cached proof before advancing
            // the tracker; otherwise a delayed load correctly requires a refresh at the newer cursor.
            assertSame(cached, awaitCurrent(tracker, "sample-1", SampleModel.class));
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                     new ModelCacheTracker.RefreshedBatch(safeStateIndex, Map.of()))) {
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                                 safeStateIndex, Map.of());
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                                 safeStateIndex, Map.of());
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                                     safeStateIndex, Map.of()))) {
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
    void hardDeleteReleasesLookupBeforeDeferredEvictionNotification()
            throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ConcurrentLinkedQueue<CompletableFuture<TrackModelUpdatesResult>>
                polls = polls(eventStore);
        ConcurrentLinkedQueue<Runnable> evictionNotifications =
                new ConcurrentLinkedQueue<>();
        ModelCache cache = new ModelCache(new SoftReferenceCache(
                100, evictionNotifications::add, null));
        cache.put("sample-1", entity(SampleModel.class));
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
                                 return new ModelCacheTracker.RefreshedBatch(
                                         safeStateIndex, Map.of());
                             })) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            2L, 11L, 11L, 11L,
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
            assertTrue(refreshStarted.await(5L, TimeUnit.SECONDS));

            CompletableFuture<Entity<?>> lookup = new CompletableFuture<>();
            CountDownLatch lookupStarted = new CountDownLatch(1);
            Thread lookupThread = Thread.ofVirtual().start(() -> {
                lookupStarted.countDown();
                lookup.complete(tracker.current(
                        "sample-1", SampleModel.class));
            });
            assertTrue(lookupStarted.await(5L, TimeUnit.SECONDS));
            long waitingDeadline = System.nanoTime()
                                   + TimeUnit.SECONDS.toNanos(5L);
            while (lookupThread.getState() != Thread.State.WAITING
                   && !lookup.isDone()
                   && System.nanoTime() < waitingDeadline) {
                Thread.onSpinWait();
            }
            assertFalse(lookup.isDone());

            completeNext(
                    polls,
                    new TrackModelUpdatesResult(
                            3L, 12L, 12L, 12L,
                            List.of(
                                    new ModelUpdate(
                                            ModelUpdateKind.HARD_DELETE,
                                            "deletion-1", 0,
                                            12L, null,
                                            List.of()))));

            assertTimeoutPreemptively(
                    Duration.ofSeconds(1L),
                    () -> assertNull(lookup.join()));
            assertFalse(evictionNotifications.isEmpty());
        } finally {
            continueRefresh.countDown();
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
        ModelCache cache = new ModelCache(new DefaultCache());
        cache.put(
                "sample-1",
                entity(SampleModel.class));
        ModelCacheTracker tracker =
                new ModelCacheTracker(
                        eventStore, cache,
                        (ignored, safeStateIndex) ->
                                new ModelCacheTracker
                                        .RefreshedBatch(
                                                safeStateIndex, Map.of()));
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
    void unsupportedTrackingDisablesTheFastPath() throws Exception {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        when(eventStore.trackModelUpdates(any())).thenReturn(
                CompletableFuture.failedFuture(new UnsupportedOperationException("old runtime")));
        ModelCache cache = new ModelCache(new DefaultCache());
        Entity<?> cached = entity(SampleModel.class);
        cache.put("sample-1", cached);
        try (ModelCacheTracker tracker = new ModelCacheTracker(
                eventStore, cache,
                (ignored, safeStateIndex) -> new ModelCacheTracker.RefreshedBatch(safeStateIndex, Map.of()))) {
            tracker.loaded("sample-1", SampleModel.class, 10L);
            // current() is already unavailable during bootstrap. Await the unsupported response itself.
            assertFalse(tracker.readiness().get(5, TimeUnit.SECONDS));

            assertNull(tracker.current("sample-1", SampleModel.class));
            assertSame(cached, cache.get("sample-1"));
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
        ModelCache cache = new ModelCache(new DefaultCache());
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
                                                     safeStateIndex, Map.of()))) {
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

    private static void awaitUnavailable(
            ModelCacheTracker tracker,
            String modelId,
            Class<?> modelType) {
        long deadline =
                System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5L);
        while (tracker.current(modelId, modelType) != null
               && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertNull(
                tracker.current(modelId, modelType),
                "tracker did not fence the stale cache entry");
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
