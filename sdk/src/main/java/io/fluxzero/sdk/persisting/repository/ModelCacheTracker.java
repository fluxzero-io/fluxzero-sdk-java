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
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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

    private static final Runnable NO_OP = () -> {
    };

    private static final boolean CACHE_DIAGNOSTICS =
            Boolean.getBoolean("fluxzero.modelCacheDiagnostics");
    private static final ConcurrentHashMap<String, LongAdder> CACHE_OUTCOMES =
            new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_LOOKUPS = new AtomicLong();
    private static final AtomicLong TRACKED_TARGETS = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> TRACKED_OUTCOMES =
            new ConcurrentHashMap<>();
    private static final LongAdder REFRESHED_TARGETS = new LongAdder();
    private static final AtomicInteger REFRESH_SAMPLES = new AtomicInteger();
    private static final int TRACK_BATCH_SIZE = Math.max(
            1,
            Integer.getInteger(
                    "fluxzero.modelCacheTrackBatchSize",
                    65_536));
    private static final long TRACK_WAIT_MILLIS = 30_000L;
    private static final int REFRESH_BATCH_SIZE = 1_024;

    private final EventStoreClient eventStoreClient;
    private final Cache cache;
    private final Refresher refresher;
    private final ConcurrentHashMap<String, Entry> entries =
            new ConcurrentHashMap<>();
    private final Set<String> staleModelIds =
            ConcurrentHashMap.newKeySet();
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
    private volatile CompletableFuture<Void> processingPage;
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
                                staleModelIds.remove(
                                        eviction.getId()
                                                .toString());
                            }
                        });
    }

    /**
     * Returns a cache entry only when every tracked update since its load boundary has been accounted for.
     */
    Entity<?> current(String modelId, Class<?> modelType) {
        CurrentModel current = currentVersion(modelId, modelType);
        return current == null ? null : current.entity();
    }

    /**
     * Returns a current cached model together with the latest global boundary through which that
     * exact value is known to be valid.
     */
    CurrentModel currentVersion(
            String modelId,
            Class<?> modelType) {
        return currentVersion(modelId, modelType, null);
    }

    boolean supplyCurrentVersion(
            String modelId,
            Class<?> modelType,
            DefaultModelRepository.CurrentModelSink sink) {
        return currentVersion(modelId, modelType, sink) == SUPPLIED;
    }

    void supplyCurrentVersions(
            Iterable<? extends DefaultModelRepository.CurrentModelLookup> lookups) {
        if (!healthy || unsupported || closed.get()) {
            cacheOutcome("unhealthy");
            return;
        }
        List<BatchCandidate> candidates =
                new ArrayList<>();
        for (DefaultModelRepository.CurrentModelLookup lookup :
                lookups) {
            String modelId = lookup.modelId();
            Entry entry = entries.get(modelId);
            if (entry == null) {
                cacheOutcome("no-entry");
                continue;
            }
            if (entry.stale) {
                currentVersion(
                        modelId, lookup.modelType(),
                        lookup);
                continue;
            }
            long validThrough;
            boolean stale;
            synchronized (entry) {
                stale = entry.stale;
                validThrough = Math.max(
                        entry.validThrough, cursor);
            }
            if (stale) {
                currentVersion(
                        modelId, lookup.modelType(),
                        lookup);
            } else {
                candidates.add(
                        new BatchCandidate(
                                entry, lookup,
                                validThrough));
            }
        }
        cache.supplyAll(
                candidates,
                candidate ->
                        candidate.lookup.modelId(),
                (candidate, cached) ->
                        candidate.cached =
                                (Entity<?>) cached);
        for (BatchCandidate candidate :
                candidates) {
            String modelId =
                    candidate.lookup.modelId();
            Entry entry = candidate.entry;
            synchronized (entry) {
                if (entry.stale) {
                    continue;
                }
                Entity<?> cached = candidate.cached;
                if (cached == null) {
                    cacheOutcome("cache-missing");
                    entries.remove(modelId, entry);
                    continue;
                }
                if (!candidate.lookup.modelType()
                        .equals(cached.type())) {
                    cacheOutcome("type-mismatch");
                    continue;
                }
                long modelStateIndex =
                        cached instanceof ModelRoot<?> model
                                ? model.stateIndex() : -1L;
                if (modelStateIndex
                    > candidate.validThrough) {
                    cacheOutcome("ahead-of-proof");
                    continue;
                }
                entry.validThrough = Math.max(
                        entry.validThrough,
                        candidate.validThrough);
                cacheOutcome("hit");
                candidate.lookup.accept(
                        cached,
                        candidate.validThrough,
                        modelStateIndex);
            }
        }
    }

    private CurrentModel currentVersion(
            String modelId,
            Class<?> modelType,
            DefaultModelRepository.CurrentModelSink sink) {
        if (!healthy || unsupported || closed.get()) {
            cacheOutcome("unhealthy");
            return null;
        }
        Entry entry = entries.get(modelId);
        if (entry == null) {
            cacheOutcome("no-entry");
            return null;
        }
        if (entry.stale) {
            cacheOutcome("stale");
            if (entry.latestUpdate
                > materializedCursor) {
                cacheOutcome("stale-unmaterialized");
                return null;
            }
            staleModelIds.add(modelId);
            scheduleRefresh();
            CompletableFuture<Void> refresh =
                    entry.refresh;
            if (refresh != null) {
                try {
                    refresh.join();
                } catch (CompletionException ignored) {
                    cacheOutcome("refresh-failed");
                    return null;
                }
            }
            if (entry.stale) {
                cacheOutcome("stale-after-refresh");
                return null;
            }
        }
        synchronized (entry) {
            if (entry.stale) {
                return null;
            }
            Entity<?> cached = cache.get(modelId);
            if (cached == null) {
                cacheOutcome("cache-missing");
                entries.remove(modelId, entry);
                return null;
            }
            if (!modelType.equals(cached.type())) {
                cacheOutcome("type-mismatch");
                return null;
            }
            long modelStateIndex =
                    cached instanceof ModelRoot<?> model
                            ? model.stateIndex() : -1L;
            if (modelStateIndex > entry.validThrough) {
                cacheOutcome("ahead-of-proof");
                /*
                 * Cache replacement and tracker publication are deliberately separate operations.
                 * Refuse the tiny intervening window instead of assigning an older proof to a newer value.
                 */
                return null;
            }
            /*
             * A healthy tracker has examined every model update through its global cursor. If none of those updates
             * invalidated this entry, the cached value is valid through that cursor even when this particular model
             * last changed much earlier. Returning only the model's own last state index makes otherwise homogeneous
             * commit batches carry thousands of needlessly old read boundaries and forces the runtime to recheck
             * already observed history.
             */
            entry.validThrough = Math.max(
                    entry.validThrough, cursor);
            cacheOutcome("hit");
            if (sink != null) {
                sink.accept(
                        cached,
                        entry.validThrough,
                        modelStateIndex);
                return SUPPLIED;
            }
            return new CurrentModel(
                    cached,
                    entry.validThrough,
                    modelStateIndex);
        }
    }

    private static void cacheOutcome(String outcome) {
        if (!CACHE_DIAGNOSTICS) {
            return;
        }
        CACHE_OUTCOMES.computeIfAbsent(outcome, ignored -> new LongAdder()).increment();
        long lookups = CACHE_LOOKUPS.incrementAndGet();
        if ((lookups & 8_191L) == 0L) {
            System.out.printf("Model cache outcomes after %,d lookups: %s%n", lookups, CACHE_OUTCOMES);
        }
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

    private Entry publish(
            String modelId,
            Class<?> modelType,
            long readStateIndex,
            boolean requireGlobalBoundary) {
        if (closed.get() || unsupported) {
            return null;
        }
        if (!started.get()) {
            start();
        }
        if (!started.get()) {
            /*
             * Loading can complete on a websocket result callback. Blocking that callback on another request routed
             * to the same session deadlocks response delivery. Finish publication after the asynchronous bootstrap;
             * until then current() deliberately bypasses this cache entry.
             */
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
            return null;
        }
        boolean created = false;
        Entry entry = entries.get(modelId);
        if (entry == null) {
            Entry candidate = new Entry();
            Entry existing = entries.putIfAbsent(
                    modelId, candidate);
            entry = existing == null ? candidate : existing;
            created = existing == null;
        }
        CompletableFuture<Void> completedRefresh = null;
        boolean refreshNeeded = false;
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
                        || created)
                       && started.get()
                       && readStateIndex
                          < cursor;
            if (entry.stale
                && entry.pendingLocalCommits.get() == 0) {
                staleModelIds.add(modelId);
                refreshNeeded = true;
            } else if (!entry.stale) {
                staleModelIds.remove(modelId);
            } else {
                refreshNeeded = staleModelIds.contains(modelId);
            }
            if (!entry.stale
                && entry.refresh != null) {
                completedRefresh = entry.refresh;
                entry.refresh = null;
            } else if (entry.stale
                       && (entry.refresh == null
                           || entry.refresh.isDone())) {
                entry.refresh =
                        new CompletableFuture<>();
            }
        }
        if (completedRefresh != null) {
            completedRefresh.complete(null);
        }
        if (refreshNeeded) {
            scheduleRefresh();
        }
        return entry;
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
        if (!closed.get() && !unsupported) {
            Entry current = entries.get(modelId);
            if (current != null) {
                synchronized (current) {
                    if (current.loaded && !current.stale) {
                        current.modelType = modelType;
                        current.validThrough = Math.max(
                                current.validThrough, stateIndex);
                        current.latestLocalCommit = Math.max(
                                current.latestLocalCommit, stateIndex);
                        return;
                    }
                }
            }
        }
        Entry entry = publish(
                modelId, modelType,
                stateIndex, false);
        if (entry != null) {
            entry.latestLocalCommit = Math.max(
                    entry.latestLocalCommit, stateIndex);
        }
    }

    /**
     * Prevents a tracker update for an in-flight local commit from starting a redundant suffix refresh before the
     * accepted result can seed the same cache entry.
     */
    Runnable beginLocalCommit(
            Collection<String> modelIds) {
        List<String> targets =
                modelIds.size() == 1
                        ? List.of(modelIds.iterator().next())
                        : modelIds.stream()
                                .distinct()
                                .toList();
        start();
        /*
         * A tracker page is applied with healthy=false so readers cannot observe half a page. Local commits still
         * need to register during that short catch-up window; otherwise their own durable update can be mistaken for
         * a remote update and trigger a redundant event-store refresh after the commit succeeds.
         */
        boolean tracking =
                started.get()
                && !unsupported
                && !closed.get();
        if (!tracking || targets.isEmpty()) {
            return NO_OP;
        }
        if (targets.size() == 1) {
            String modelId = targets.getFirst();
            LocalCommit commit = beginLocalCommit(modelId);
            AtomicBoolean completed = new AtomicBoolean();
            return () -> {
                if (completed.compareAndSet(false, true)
                    && completeLocalCommit(commit)) {
                    scheduleRefresh();
                }
            };
        }
        List<LocalCommit> commits =
                new java.util.ArrayList<>(targets.size());
        targets.forEach(modelId ->
                commits.add(beginLocalCommit(modelId)));
        AtomicBoolean completed =
                new AtomicBoolean();
        return () -> {
            if (!completed.compareAndSet(
                    false, true)) {
                return;
            }
            boolean refreshNeeded = false;
            for (LocalCommit commit : commits) {
                refreshNeeded |= completeLocalCommit(commit);
            }
            if (refreshNeeded) {
                scheduleRefresh();
            }
        };
    }

    private LocalCommit beginLocalCommit(
            String modelId) {
        /*
         * Register the target before its request reaches the runtime. This lets the tracker remember
         * updates for a model that is being created and therefore has no cache entry yet. Without the
         * placeholder, a concurrent tracker page can advance the global cursor past the local commit;
         * the accepted revision is then needlessly treated as stale even when every intervening update
         * was unrelated.
         */
        Entry entry = entries.get(modelId);
        if (entry == null) {
            entry = entries.computeIfAbsent(
                    modelId, ignored -> new Entry());
        }
        entry.pendingLocalCommits.incrementAndGet();
        return new LocalCommit(modelId, entry);
    }

    private boolean completeLocalCommit(
            LocalCommit commit) {
        String modelId = commit.modelId();
        Entry entry = commit.entry();
        int pending = entry.pendingLocalCommits.decrementAndGet();
        if (entries.get(modelId) != entry) {
            return false;
        }
        if (!entry.loaded
            && pending == 0
            && cache.get(modelId) == null) {
            entries.remove(modelId, entry);
            staleModelIds.remove(modelId);
            return false;
        }
        if (!entry.stale) {
            return false;
        }
        staleModelIds.add(modelId);
        return true;
    }

    void forget(String modelId) {
        entries.remove(modelId);
        staleModelIds.remove(modelId);
    }

    void forgetAll() {
        entries.clear();
        staleModelIds.clear();
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
                CompletableFuture<Void> catchUp =
                        processingPage;
                if (healthy
                    || catchUp == null
                    || catchUp.isDone()) {
                    catchUp = new CompletableFuture<>();
                    processingPage = catchUp;
                }
                boolean globallyInvalidating =
                        result.getUpdates().stream()
                                .anyMatch(update ->
                                        update.getKind()
                                        == ModelUpdateKind.HARD_DELETE);
                if (globallyInvalidating) {
                    /*
                     * Erasure updates deliberately clear every cache entry because their target IDs may no longer be
                     * retained. Keep document loads fenced until both that clear and the page cursors are visible.
                     */
                    healthy = false;
                }
                process(result);
                /*
                 * The previous cursor remains a valid proof boundary while this page is applied. Entries affected by
                 * the page are fenced before the cursor advances, while unrelated entries remain usable at that
                 * previous boundary. A commit started there still carries the older cursor and is checked by the
                 * runtime against every intervening model update. Only an actual tracking failure makes the cache
                 * globally unhealthy.
                 */
                if (globallyInvalidating) {
                    healthy = true;
                }
                catchUp.complete(null);
                backoffMillis = 25L;
            } catch (Throwable failure) {
                pendingTrack = null;
                Throwable cause = unwrap(failure);
                healthy = false;
                CompletableFuture<Void> catchUp =
                        processingPage;
                if (catchUp != null) {
                    catchUp.complete(null);
                }
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
        long previousMaterializedCursor =
                materializedCursor;
        boolean refreshNeeded = false;
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
                staleModelIds.clear();
            } else {
                for (ModelCommitTargetResult target :
                        update.getTargets()) {
                    refreshNeeded |= markUpdated(
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
        if (refreshNeeded
            || materializedCursor
               > previousMaterializedCursor
               && !staleModelIds.isEmpty()) {
            scheduleRefresh();
        }
    }

    private boolean markUpdated(
            ModelCommitTargetResult target,
            long stateIndex) {
        String modelId =
                target.getModelId();
        Entry entry =
                entries.get(
                        modelId);
        if (entry == null) {
            trackedOutcome("no-entry");
            return false;
        }
        if (!target.isHistoryComplete()
            && entry.modelType != null
            && ModelMetadata.validate(
                            entry.modelType)
                    .model().orElseThrow()
                    .eventSourced()) {
            cache.remove(
                    modelId);
            entries.remove(
                    modelId,
                    entry);
            staleModelIds.remove(modelId);
            trackedOutcome("history-incomplete");
            return false;
        }
        if (entry.loaded
            && !entry.stale
            && stateIndex
               <= entry.latestLocalCommit) {
            /*
             * The accepted local revision already contains this tracked update. The tracker is
             * the sole writer of latestUpdate, so advancing it here needs neither the entry
             * monitor nor another pending-local lookup.
             */
            entry.latestUpdate = stateIndex;
            trackedOutcome("local-current");
            return false;
        }
        boolean stale;
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
            stale = entry.stale;
        }
        if (!stale
            || entry.pendingLocalCommits.get() > 0) {
            trackedOutcome(!stale ? "current" : "pending-local");
            return false;
        }
        staleModelIds.add(modelId);
        if (CACHE_DIAGNOSTICS
            && REFRESH_SAMPLES.getAndIncrement() < 20) {
            System.out.printf(
                    "Model cache refresh race model=%s update=%d validThrough=%d latest=%d local=%d loaded=%s pending=%s%n",
                    modelId, stateIndex, entry.validThrough,
                    entry.latestUpdate, entry.latestLocalCommit,
                    entry.loaded,
                    entry.pendingLocalCommits.get());
        }
        trackedOutcome("refresh-needed");
        return true;
    }

    private static void trackedOutcome(String outcome) {
        if (!CACHE_DIAGNOSTICS) {
            return;
        }
        TRACKED_OUTCOMES.computeIfAbsent(outcome, ignored -> new LongAdder()).increment();
        long tracked = TRACKED_TARGETS.incrementAndGet();
        if ((tracked & 65_535L) == 0L) {
            System.out.printf(
                    "Model cache tracked outcomes after %,d targets: %s, refreshed=%,d%n",
                    tracked, TRACKED_OUTCOMES, REFRESHED_TARGETS.sum());
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
            for (String modelId :
                    staleModelIds) {
                Entry entry =
                        entries.get(modelId);
                if (entry == null
                    || !entry.stale
                    || !cache.containsKey(modelId)) {
                    staleModelIds.remove(modelId);
                    continue;
                }
                if (entry.stale
                    && entry.latestUpdate
                       <= refreshBoundary
                    && entry.pendingLocalCommits.get() == 0
                    && entry.modelType != null) {
                    targets.put(
                            modelId,
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
                REFRESHED_TARGETS.add(targets.size());
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
            staleModelIds.clear();
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

    record CurrentModel(
            Entity<?> entity,
            long validThrough,
            long modelStateIndex) {
    }

    private static final CurrentModel SUPPLIED =
            new CurrentModel(null, -1L, -1L);

    private static final class BatchCandidate {
        private final Entry entry;
        private final DefaultModelRepository.CurrentModelLookup lookup;
        private final long validThrough;
        private Entity<?> cached;

        private BatchCandidate(
                Entry entry,
                DefaultModelRepository.CurrentModelLookup lookup,
                long validThrough) {
            this.entry = entry;
            this.lookup = lookup;
            this.validThrough = validThrough;
        }
    }

    private static final class Entry {
        private volatile Class<?> modelType;
        private volatile boolean loaded;
        private volatile boolean stale;
        private volatile long validThrough = -1L;
        private volatile long latestUpdate = -1L;
        private volatile long latestLocalCommit = -1L;
        private volatile CompletableFuture<Void> refresh;
        private final AtomicInteger pendingLocalCommits =
                new AtomicInteger();
    }

    private record LocalCommit(
            String modelId,
            Entry entry) {
    }

}
