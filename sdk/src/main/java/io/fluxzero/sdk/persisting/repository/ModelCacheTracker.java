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

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Keeps cached independent models coherent by long-polling the durable model-commit position.
 * <p>
 * Updates first fence an affected cache entry as stale. A coalescing refresh worker then advances the cached value.
 * This deliberately retains an event-sourced value as a replay base while it is stale; foreground callers only use
 * entries that the tracker has proved current.
 */
@Slf4j
final class ModelCacheTracker implements AutoCloseable {

    private static final int TRACK_BATCH_SIZE = 2_048;
    private static final long TRACK_WAIT_MILLIS = 30_000L;
    private static final int REFRESH_BATCH_SIZE = 1_024;

    private final EventStoreClient eventStoreClient;
    private final Cache cache;
    private final Refresher refresher;
    private final ConcurrentHashMap<String, Entry> entries =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer>
            pendingLocalCommits =
            new ConcurrentHashMap<>();
    private final ExecutorService refreshExecutor =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual()
                            .name("fluxzero-model-cache-refresh-", 0L)
                            .factory());
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean refreshScheduled =
            new AtomicBoolean();
    private final AtomicBoolean refreshRequested =
            new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object startMonitor = new Object();
    private final Object trackMonitor = new Object();
    private final Registration evictionRegistration;

    private volatile boolean healthy;
    private volatile boolean unsupported;
    private volatile long cursor = -1L;
    private volatile long materializedCursor = -1L;
    private volatile CompletableFuture<Boolean>
            bootstrap;
    private volatile CompletableFuture<TrackModelUpdatesResult>
            pendingBootstrap;
    private volatile CompletableFuture<TrackModelUpdatesResult>
            pendingTrack;
    private volatile Thread trackerThread;

    ModelCacheTracker(
            EventStoreClient eventStoreClient,
            Cache cache,
            Refresher refresher) {
        this.eventStoreClient =
                Objects.requireNonNull(
                        eventStoreClient,
                        "eventStoreClient");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.refresher =
                Objects.requireNonNull(refresher, "refresher");
        this.evictionRegistration =
                cache.registerEvictionListener(
                        eviction -> {
                            if (eviction.getId() != null
                                && cache.get(
                                        eviction.getId())
                                   == null) {
                                entries.remove(
                                        eviction.getId()
                                                .toString());
                            }
                        });
    }

    /**
     * Returns a cache entry only when every tracked update since its load boundary has been accounted for.
     */
    Entity<?> current(String modelId, Class<?> modelType) {
        if (!healthy || unsupported || closed.get()) {
            return null;
        }
        Entry entry = entries.get(modelId);
        if (entry == null) {
            return null;
        }
        if (entry.stale) {
            if (entry.latestUpdate
                > materializedCursor) {
                return null;
            }
            scheduleRefresh();
            CompletableFuture<Void> refresh =
                    entry.refresh;
            if (refresh != null) {
                try {
                    refresh.join();
                } catch (CompletionException ignored) {
                    return null;
                }
            }
            if (entry.stale) {
                return null;
            }
        }
        Entity<?> cached = cache.get(modelId);
        if (cached == null) {
            entries.remove(modelId, entry);
            return null;
        }
        if (!modelType.equals(cached.type())) {
            return null;
        }
        return cached;
    }

    /**
     * Starts boundary observation without waiting for its websocket round trip.
     */
    void prepare() {
        start();
    }

    /**
     * Publishes a freshly loaded current value and its inclusive runtime read boundary.
     */
    void loaded(String modelId, Class<?> modelType, long readStateIndex) {
        publish(
                modelId, modelType,
                readStateIndex, true);
    }

    private void publish(
            String modelId,
            Class<?> modelType,
            long readStateIndex,
            boolean requireGlobalBoundary) {
        if (closed.get() || unsupported) {
            return;
        }
        if (!start()) {
            /*
             * Loading can complete on a websocket result callback. Blocking that callback on another request routed
             * to the same session deadlocks response delivery. Finish publication after the asynchronous bootstrap;
             * until then current() deliberately bypasses this cache entry.
             */
            if (!started.get()) {
                CompletableFuture<Boolean> readiness =
                        bootstrap;
                if (readiness != null) {
                    readiness.thenAccept(ready -> {
                        if (ready) {
                            publish(
                                    modelId,
                                    modelType,
                                    readStateIndex,
                                    requireGlobalBoundary);
                        }
                    });
                }
            }
            return;
        }
        AtomicBoolean created = new AtomicBoolean();
        Entry entry = entries.computeIfAbsent(
                modelId, ignored -> {
                    created.set(true);
                    return new Entry();
                });
        synchronized (entry) {
            entry.modelType = modelType;
            entry.loaded = true;
            entry.validThrough =
                    Math.max(
                            entry.validThrough,
                            readStateIndex);
            entry.stale =
                    entry.latestUpdate
                    > entry.validThrough
                    || (requireGlobalBoundary
                        || created.get())
                       && started.get()
                       && readStateIndex
                          < cursor;
            if (!entry.stale
                && entry.refresh != null) {
                entry.refresh.complete(null);
            } else if (entry.stale
                       && (entry.refresh == null
                           || entry.refresh.isDone())) {
                entry.refresh =
                        new CompletableFuture<>();
            }
        }
        if (entry.stale) {
            scheduleRefresh();
        }
    }

    /**
     * Records an authoritative local commit before its update is eventually observed through the tracker.
     */
    void committed(
            String modelId,
            Class<?> modelType,
            long stateIndex) {
        /*
         * An entry that participated in the commit already recorded every relevant tracked update. A newer global
         * cursor may therefore consist entirely of unrelated model commits and must not force an event-store suffix
         * load after this authoritative local commit. A newly created entry keeps the global-boundary fence because
         * it may have missed an update for this model before it was registered.
         */
        publish(
                modelId, modelType,
                stateIndex, false);
    }

    /**
     * Prevents a tracker update for an in-flight local commit from starting a redundant suffix refresh before the
     * accepted result can seed the same cache entry.
     */
    Runnable beginLocalCommit(
            Collection<String> modelIds) {
        List<String> targets =
                modelIds.stream()
                        .distinct()
                        .toList();
        targets.forEach(
                modelId ->
                        pendingLocalCommits.merge(
                                modelId, 1,
                                Integer::sum));
        AtomicBoolean completed =
                new AtomicBoolean();
        return () -> {
            if (!completed.compareAndSet(
                    false, true)) {
                return;
            }
            AtomicBoolean refreshNeeded =
                    new AtomicBoolean();
            targets.forEach(
                    modelId -> {
                        pendingLocalCommits.computeIfPresent(
                                    modelId,
                                    (ignored, count) ->
                                            count == 1
                                                    ? null
                                                    : count
                                                      - 1);
                        Entry entry =
                                entries.get(modelId);
                        if (entry != null
                            && entry.stale) {
                            refreshNeeded.set(true);
                        }
                    });
            if (refreshNeeded.get()) {
                scheduleRefresh();
            }
        };
    }

    void forget(String modelId) {
        entries.remove(modelId);
    }

    void forgetAll() {
        entries.clear();
    }

    /**
     * Returns a boundary through which a direct document is known to be materialized. Callers must obtain this before
     * fetching the document and publish the fetched value with the returned boundary.
     */
    Long safeDocumentBoundary() {
        if (!start() || !healthy) {
            return null;
        }
        long processed = cursor;
        long materialized = materializedCursor;
        return materialized >= processed
                ? materialized
                : null;
    }

    private boolean start() {
        if (started.get()) {
            return healthy && !unsupported && !closed.get();
        }
        synchronized (startMonitor) {
            if (started.get()) {
                return healthy && !unsupported
                       && !closed.get();
            }
            if (closed.get() || unsupported) {
                return false;
            }
            if (bootstrap == null
                || bootstrap.isDone()) {
                CompletableFuture<Boolean> readiness =
                        new CompletableFuture<>();
                bootstrap = readiness;
                Thread.ofVirtual()
                        .name("fluxzero-model-cache-bootstrap")
                        .start(() ->
                                       bootstrap(
                                               readiness));
            }
            return false;
        }
    }

    private void bootstrap(
            CompletableFuture<Boolean> readiness) {
        try {
            /*
             * One zero-wait request per namespace establishes a cursor that cannot skip a commit whose direct
             * document materialization is still pending. Run it outside any websocket callback: JDK websocket
             * callbacks are ordered per session, so joining a nested request from such a callback can prevent its
             * own response from being delivered.
             */
            CompletableFuture<TrackModelUpdatesResult> request =
                    eventStoreClient.trackModelUpdates(
                            new TrackModelUpdates(
                                    -1L, 1, 0L));
            pendingBootstrap = request;
            TrackModelUpdatesResult position =
                    request.join();
            pendingBootstrap = null;
            validatePosition(position);
            if (closed.get()) {
                readiness.complete(false);
                return;
            }
            /*
             * No cache entries predate this tracker, so historical target updates need not be replayed. Start update
             * observation at the durable head while retaining the older materialized head separately; direct
             * documents remain uncacheable until that second head catches up.
             */
            cursor = position.getCurrentStateIndex();
            materializedCursor =
                    position.getMaterializedStateIndex();
            healthy = true;
            started.set(true);
            trackerThread = Thread.ofVirtual()
                    .name("fluxzero-model-cache-tracker")
                    .start(this::track);
            readiness.complete(true);
        } catch (Throwable failure) {
            pendingBootstrap = null;
            Throwable cause = unwrap(failure);
            healthy = false;
            if (cause
                instanceof UnsupportedOperationException) {
                unsupported = true;
                entries.clear();
                log.debug(
                        "Model update tracking is not supported by this event store");
            } else if (!closed.get()) {
                log.warn(
                        "Could not establish the model cache tracking boundary; current loads will bypass the cache",
                        cause);
            }
            readiness.complete(false);
        }
    }

    private void track() {
        long backoffMillis = 25L;
        while (!closed.get() && !unsupported) {
            try {
                CompletableFuture<TrackModelUpdatesResult> request;
                synchronized (trackMonitor) {
                    if (closed.get()) {
                        return;
                    }
                    request = eventStoreClient.trackModelUpdates(
                            new TrackModelUpdates(
                                    cursor,
                                    TRACK_BATCH_SIZE,
                                    TRACK_WAIT_MILLIS));
                    pendingTrack = request;
                }
                TrackModelUpdatesResult result =
                        request.join();
                pendingTrack = null;
                healthy = false;
                process(result);
                healthy =
                        cursor
                        >= result.getCurrentStateIndex();
                backoffMillis = 25L;
            } catch (Throwable failure) {
                pendingTrack = null;
                Throwable cause = unwrap(failure);
                healthy = false;
                if (cause
                    instanceof UnsupportedOperationException) {
                    unsupported = true;
                    entries.clear();
                    log.debug(
                            "Model update tracking is not supported by this event store");
                    return;
                }
                if (!closed.get()) {
                    log.warn(
                            "Model cache tracking failed; current loads will validate against the model store while tracking recovers",
                            cause);
                    LockSupport.parkNanos(
                            java.util.concurrent.TimeUnit.MILLISECONDS
                                    .toNanos(
                                            backoffMillis));
                    backoffMillis =
                            Math.min(
                                    5_000L,
                                    backoffMillis * 2L);
                }
            }
        }
    }

    private void process(TrackModelUpdatesResult result) {
        Objects.requireNonNull(result, "Model update result");
        validatePosition(result);
        List<ModelUpdate> updates =
                Objects.requireNonNull(
                        result.getUpdates(),
                        "Model updates");
        long previous = cursor;
        for (ModelUpdate update : updates) {
            if (update.getStateIndex() <= previous) {
                throw new IllegalStateException(
                        "Model updates are not strictly ordered after cursor "
                        + previous);
            }
            if (update.getKind()
                == ModelUpdateKind.HARD_DELETE) {
                cache.clear();
                entries.clear();
            } else {
                for (ModelCommitTargetResult target :
                        update.getTargets()) {
                    markUpdated(
                            target,
                            update.getStateIndex());
                }
            }
            previous = update.getStateIndex();
        }
        if (result.getLastStateIndex() != previous) {
            throw new IllegalStateException(
                    "Model update response cursor "
                    + result.getLastStateIndex()
                    + " does not match its last update "
                    + previous);
        }
        cursor = previous;
        materializedCursor =
                result.getMaterializedStateIndex();
        if (!entries.isEmpty()) {
            scheduleRefresh();
        }
    }

    private void markUpdated(
            ModelCommitTargetResult target,
            long stateIndex) {
        Entry entry =
                entries.get(
                        target.getModelId());
        if (entry == null) {
            return;
        }
        if (!target.isHistoryComplete()
            && entry.modelType != null
            && ModelMetadata.validate(
                            entry.modelType)
                    .model().orElseThrow()
                    .eventSourced()) {
            cache.remove(
                    target.getModelId());
            entries.remove(
                    target.getModelId(),
                    entry);
            return;
        }
        synchronized (entry) {
            entry.latestUpdate =
                    Math.max(
                            entry.latestUpdate,
                            stateIndex);
            if (entry.loaded
                && stateIndex
                   > entry.validThrough) {
                entry.stale = true;
                if (entry.refresh == null
                    || entry.refresh.isDone()) {
                    entry.refresh =
                            new CompletableFuture<>();
                }
            }
        }
    }

    private void scheduleRefresh() {
        if (closed.get()) {
            return;
        }
        refreshRequested.set(true);
        if (!refreshScheduled
                    .compareAndSet(
                            false, true)) {
            return;
        }
        refreshExecutor.execute(this::refresh);
    }

    private void refresh() {
        Map<String, Class<?>> targets =
                new LinkedHashMap<>();
        long refreshBoundary =
                Math.min(
                        cursor,
                        materializedCursor);
        Throwable refreshFailure = null;
        boolean fullBatch = false;
        try {
            refreshRequested.set(false);
            //Collapse one tracker page and very closely following commits into one batched suffix load.
            LockSupport.parkNanos(
                    java.util.concurrent.TimeUnit.MILLISECONDS
                            .toNanos(1L));
            for (Map.Entry<String, Entry> candidate :
                    entries.entrySet()) {
                Entry entry = candidate.getValue();
                if (entry.stale
                    && entry.latestUpdate
                       <= refreshBoundary
                    && !pendingLocalCommits
                            .containsKey(
                                    candidate.getKey())
                    && entry.modelType != null
                    && cache.containsKey(
                            candidate.getKey())) {
                    targets.put(
                            candidate.getKey(),
                            entry.modelType);
                    if (entry.refresh == null
                        || entry.refresh.isDone()) {
                        entry.refresh =
                                new CompletableFuture<>();
                    }
                    if (targets.size()
                        == REFRESH_BATCH_SIZE) {
                        fullBatch = true;
                        break;
                    }
                }
            }
            if (!targets.isEmpty()) {
                RefreshedBatch refreshed =
                        refresher.refresh(
                                Map.copyOf(
                                        targets),
                                refreshBoundary);
                if (refreshed.readStateIndex()
                    > refreshBoundary) {
                    throw new IllegalStateException(
                            "Model cache refresh advanced beyond its safe tracking boundary "
                            + refreshBoundary);
                }
                targets.forEach(
                        (modelId, modelType) ->
                                publish(
                                        modelId,
                                        modelType,
                                        refreshed
                                                .readStateIndex(),
                                        false));
            }
            if (fullBatch) {
                refreshRequested.set(true);
            }
        } catch (Throwable failure) {
            refreshFailure = unwrap(failure);
            if (!closed.get()) {
                log.warn(
                        "Failed to refresh stale model cache entries; they remain unavailable to the cache fast path",
                        refreshFailure);
                LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS
                                .toNanos(100L));
            }
        } finally {
            if (refreshFailure != null) {
                Throwable failure =
                        refreshFailure;
                targets.forEach(
                        (modelId, ignored) -> {
                            Entry entry =
                                    entries.get(modelId);
                            if (entry != null
                                && entry.refresh
                                   != null) {
                                entry.refresh
                                        .completeExceptionally(
                                                failure);
                            }
                        });
            }
            refreshScheduled.set(false);
            if (!closed.get()
                && refreshRequested.get()) {
                scheduleRefresh();
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            healthy = false;
            CompletableFuture<TrackModelUpdatesResult>
                    bootstrapRequest =
                    pendingBootstrap;
            if (bootstrapRequest != null) {
                bootstrapRequest.cancel(true);
            }
            synchronized (trackMonitor) {
                CompletableFuture<TrackModelUpdatesResult>
                        request = pendingTrack;
                if (request != null) {
                    request.cancel(true);
                }
            }
            Thread thread = trackerThread;
            if (thread != null) {
                thread.interrupt();
            }
            IllegalStateException closedFailure =
                    new IllegalStateException(
                            "Model cache tracker is closed");
            entries.values().forEach(entry -> {
                CompletableFuture<Void> refresh =
                        entry.refresh;
                if (refresh != null) {
                    refresh.completeExceptionally(
                            closedFailure);
                }
            });
            pendingLocalCommits.clear();
            evictionRegistration.cancel();
            refreshExecutor.shutdownNow();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException
                || result
                   instanceof java.util.concurrent.ExecutionException)
               && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static void validatePosition(
            TrackModelUpdatesResult result) {
        Objects.requireNonNull(result, "Model update result");
        if (result.getCurrentStateIndex() < -1L
            || result.getMaterializedStateIndex() < -1L
            || result.getMaterializedStateIndex()
               > result.getCurrentStateIndex()
            || result.getLastStateIndex()
               > result.getCurrentStateIndex()) {
            throw new IllegalStateException(
                    "Invalid model update position: current="
                    + result.getCurrentStateIndex()
                    + ", materialized="
                    + result.getMaterializedStateIndex()
                    + ", last="
                    + result.getLastStateIndex());
        }
    }

    @FunctionalInterface
    interface Refresher {
        RefreshedBatch refresh(
                Map<String, Class<?>> targets,
                long safeStateIndex);
    }

    record RefreshedBatch(long readStateIndex) {
    }

    private static final class Entry {
        private volatile Class<?> modelType;
        private volatile boolean loaded;
        private volatile boolean stale;
        private volatile long validThrough = -1L;
        private volatile long latestUpdate = -1L;
        private volatile CompletableFuture<Void> refresh;
    }
}
