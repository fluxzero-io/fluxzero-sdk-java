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

package io.fluxzero.common.tracking;

import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.jfr.FluxzeroJfr;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.fluxzero.common.ConsistentHashing.computeSegment;
import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.api.tracking.Position.newPosition;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;

/**
 * Streaming strategy that allows multiple clients to concurrently consume a message stream. Messages are routed to
 * clients based on the value of their segment. Each connected client handles a distinct range of segments.
 * <p>
 * Message segments are determined by the clients that publish the messages (usually based on the consistent hash of
 * some routing key, like the value of a user id).
 * <p>
 * If a client joins or leaves the cluster the segment range mapped to each client is recalculated so messages may get
 * routed differently than before.
 * <p>
 * Clients can safely join or leave the cluster at any time. The strategy guarantees that a message is not consumed by
 * more than one client.
 */
@Slf4j
public class DefaultTrackingStrategy implements TrackingStrategy {

    private final MessageStore source;
    private final PositionStore positionStore;
    private final TaskScheduler scheduler;
    private final int segments;
    private final String traceMessageType;
    private final String traceComponent;
    private final ConcurrentHashMap<Tracker, WaitingTracker> waitingTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Tracker, TrackerRequest<?>> openRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrackerCluster> clusters = new ConcurrentHashMap<>();
    private final AtomicBoolean updateNotificationPending = new AtomicBoolean();
    private final AtomicBoolean updateNotificationRunning = new AtomicBoolean();
    private final AtomicLong updateNotificationVersion = new AtomicLong();
    private final AtomicLong updateNotificationPendingSinceNanos = new AtomicLong();

    private final Registration sourceRegistration;

    private volatile boolean stopped;

    public DefaultTrackingStrategy(MessageStore source, PositionStore positionStore) {
        this(source, positionStore, new InMemoryTaskScheduler(
                "tracking-scheduler-%s".formatted(source),
                newWorkerPool("tracking-worker-%s".formatted(source), 8)), MAX_SEGMENT, null);
    }

    /**
     * Creates a strategy whose JFR-only request stages identify the logical message log.
     *
     * @param traceMessageType logical message type used only while request-stage recording is enabled
     */
    public DefaultTrackingStrategy(
            MessageStore source, PositionStore positionStore, String traceMessageType) {
        this(source, positionStore, new InMemoryTaskScheduler(
                "tracking-scheduler-%s".formatted(source),
                newWorkerPool("tracking-worker-%s".formatted(source), 8)), MAX_SEGMENT, traceMessageType);
    }

    public DefaultTrackingStrategy(MessageStore source, PositionStore positionStore, TaskScheduler scheduler) {
        this(source, positionStore, scheduler, MAX_SEGMENT, null);
    }

    protected DefaultTrackingStrategy(MessageStore source, PositionStore positionStore, TaskScheduler scheduler,
                                      int segments) {
        this(source, positionStore, scheduler, segments, null);
    }

    protected DefaultTrackingStrategy(
            MessageStore source, PositionStore positionStore, TaskScheduler scheduler,
            int segments, String traceMessageType) {
        this.source = source;
        this.positionStore = positionStore;
        this.scheduler = scheduler;
        this.segments = segments;
        this.traceMessageType = traceMessageType;
        this.traceComponent = "COMMAND".equals(traceMessageType)
                ? "runtime.tracking-strategy.COMMAND"
                : "RESULT".equals(traceMessageType)
                        ? "runtime.tracking-strategy.RESULT" : null;
        sourceRegistration = source.registerMonitor(this::onUpdate);
        purgeCeasedTrackers(Duration.ofSeconds(2));
    }

    @Override
    public CompletableFuture<MessageBatch> getBatch(Tracker tracker) {
        TrackerRequest<MessageBatch> request = openRequest(tracker, Function.identity());
        getBatch(tracker, request);
        return request.future();
    }

    protected void getBatch(Tracker tracker, TrackerRequest<MessageBatch> request) {
        TrackerCluster oldCluster = clusters.get(tracker.getConsumerName());
        if (request.isDone()) {
            return;
        }
        int[] newSegment = claimSegmentRange(tracker);
        if (request.isDone()) {
            disconnectClosedRequest(tracker, request);
            return;
        }
        try {
            if (newSegment[0] == newSegment[1]) {
                waitForMessages(tracker, new MessageBatch(newSegment, emptyList(), null, newPosition(), true),
                                request);
                return;
            }
            int batchSize = adjustMaxSize(tracker, tracker.getMaxSize());

            long updateVersion = updateNotificationVersion.get();
            MessageStoreBatch batch;
            Position position;
            do {
                position = position(tracker, newSegment);
                batch = scanBatch(newSegment, position, batchSize, tracker.getMaxBytes(),
                                  filterPredicate(newSegment, position, tracker));

                if (batch.scannedSize() > 0 && batch.messages().isEmpty()) {
                    long batchIndex = batch.lastScannedIndex();

                    if (batchIndex < indexFromMillis(System.currentTimeMillis() - tracker.maxTimeout())) {
                        //if the index is old, send back an empty batch.
                        // Prevents rushing through potentially billions of messages
                        MessageBatch emptyBatch =
                                new MessageBatch(newSegment, batch.messages(), batchIndex, position, false);
                        completeRequest(tracker, request, emptyBatch);
                        return;
                    } else {
                        //update stored position and tracker, otherwise client may stay endlessly waiting
                        positionStore.storePosition(tracker.getConsumerName(), newSegment, batchIndex);
                        tracker = tracker.withLastTrackerIndex(batchIndex);
                    }
                }
            } while (batch.scannedSize() > 0 && batch.messages().isEmpty() && !tracker.hasMissedDeadline());

            if (batch.messages().isEmpty()) {
                MessageBatch messageBatch =
                        new MessageBatch(newSegment, batch.messages(), batch.lastScannedIndex(), position, true);
                /*
                 * A new consumer starts shortly before the current wall clock. Pin that boundary while this
                 * long-poll request waits. Recomputing it after every update would make the boundary move forward and
                 * could skip messages that arrived during a long or high-volume first publication.
                 */
                Tracker waitingTracker = tracker.getLastTrackerIndex() == null
                        ? position.lowestIndexForSegment(newSegment)
                                .map(tracker::withLastTrackerIndex)
                                .orElse(tracker)
                        : tracker;
                waitForMessages(waitingTracker, messageBatch, request);
                if (updateVersion < updateNotificationVersion.get()) {
                    var task = waitingTrackers.get(waitingTracker);
                    if (task != null && task.tracker == waitingTracker && task.request == request) {
                        task.run();
                    }
                }
            } else {
                MessageBatch messageBatch = new MessageBatch(
                        newSegment, batch.messages(),
                        batch.byteLimited() ? getLastIndex(batch.messages()) : batch.lastScannedIndex(), position,
                        !batch.byteLimited() && batch.scannedSize() < batchSize);
                completeRequest(tracker, request, messageBatch);
            }
        } catch (Throwable e) {
            log.error("Failed to get a batch for tracker {}", tracker, e);
            waitForMessages(tracker, new MessageBatch(newSegment, emptyList(), null, newPosition(), false),
                            request);
        } finally {
            if (oldCluster != null && !Objects.deepEquals(oldCluster.getSegment(tracker), newSegment)) {
                onClusterUpdate(oldCluster);
            }
        }
    }

    @Override
    public CompletableFuture<ClaimResult> claimSegment(Tracker tracker) {
        TrackerRequest<ClaimResult> request = openRequest(
                tracker, batch -> new ClaimResult(batch.getPosition(), batch.getSegment()));
        claimSegment(tracker, request);
        return request.future();
    }

    protected void claimSegment(Tracker tracker, TrackerRequest<ClaimResult> request) {
        if (request.isDone()) {
            return;
        }
        int[] newSegment = claimSegmentRange(tracker);
        if (request.isDone()) {
            disconnectClosedRequest(tracker, request);
            return;
        }
        if (newSegment[0] == newSegment[1]) {
            waitForUpdate(tracker, new MessageBatch(newSegment, emptyList(), null, newPosition(), true),
                          () -> claimSegment(tracker, request), request);
        } else {
            completeRequest(tracker, request, new MessageBatch(newSegment, emptyList(), null,
                                                               position(tracker, newSegment), true));
        }
    }

    protected List<SerializedMessage> getBatch(int[] segment, Position position, int batchSize) {
        return source.getBatch(position.lowestIndexForSegment(segment).orElse(null), batchSize);
    }

    protected MessageStoreBatch scanBatch(int[] segment, Position position, int batchSize, long maxBytes,
                                          Predicate<? super SerializedMessage> filter) {
        FluxzeroJfr.Batch event = startTrackingBatch("message-scan", 0);
        if (event == null) {
            return source.scanBatch(position.lowestIndexForSegment(segment).orElse(null), batchSize, false, maxBytes,
                                    filter);
        }
        long started = System.nanoTime();
        try {
            MessageStoreBatch result = source.scanBatch(
                    position.lowestIndexForSegment(segment).orElse(null), batchSize, false, maxBytes, filter);
            event.itemCount = result.scannedSize();
            event.outputItemCount = result.messages().size();
            event.storageNanos = System.nanoTime() - started;
            FluxzeroJfr.finish(event, null);
            return result;
        } catch (RuntimeException | Error failure) {
            event.storageNanos = System.nanoTime() - started;
            FluxzeroJfr.finish(event, failure);
            throw failure;
        }
    }

    protected void waitForMessages(Tracker tracker, MessageBatch emptyBatch) {
        waitForMessages(tracker, emptyBatch, currentOrOpenRequest(tracker));
    }

    protected void waitForMessages(Tracker tracker, MessageBatch emptyBatch, TrackerRequest<MessageBatch> request) {
        waitForUpdate(tracker, emptyBatch, () -> getBatch(tracker, request), request);
    }

    protected void waitForUpdate(Tracker tracker, MessageBatch emptyBatch, Runnable followUp) {
        waitForUpdate(tracker, emptyBatch, followUp, currentOrOpenRequest(tracker));
    }

    protected void waitForUpdate(Tracker tracker, MessageBatch emptyBatch, Runnable followUp,
                                 TrackerRequest<?> request) {
        if (request.isDone()) {
            return;
        }
        if (tracker.hasMissedDeadline()) {
            completeRequest(tracker, request, emptyBatch);
            return;
        }
        var trackerCluster = clusters.computeIfPresent(tracker.getConsumerName(),
                (p, c) -> c.contains(tracker) ? c.withWaitingTracker(tracker) : c);

        if (trackerCluster == null || !trackerCluster.contains(tracker)) {
            // this tracker has already been removed from the cluster
            cancelRequest(tracker, request);
            return;
        }

        Registration scheduleToken = scheduler.schedule(tracker.getDeadline(), () -> {
            if (removeWaitingTracker(tracker) != null && !request.isDone()) {
                clusters.compute(tracker.getConsumerName(), (p, cluster) -> cluster != null && cluster.contains(tracker)
                        ? cluster.withActiveTracker(tracker) : cluster);
                completeRequest(tracker, request, emptyBatch);
            }
        });
        WaitingTracker existing = waitingTrackers.put(
                tracker, new WaitingTracker(tracker, request, scheduleToken, followUp,
                                            updateNotificationVersion.get()));
        if (existing != null) {
            log.warn("Tracker replaced another waiting tracker. This should normally not happen. New tracker: {}",
                     tracker);
            completeRequest(existing.tracker, existing.request, emptyBatch);
        }
    }

    protected Position position(Tracker tracker, int[] segment) {
        if (tracker.clientControlledIndex()) {
            return new Position(segment, ofNullable(tracker.getLastTrackerIndex())
                    .orElseGet(() -> indexFromMillis(currentTimeMillis() - 1000L)));
        }
        Position position = positionStore.position(tracker.getConsumerName());
        if (position.isNew(segment)) {
            return new Position(segment, ofNullable(tracker.getLastTrackerIndex())
                    .orElseGet(() -> indexFromMillis(currentTimeMillis() - 1000L)));
        }
        if (tracker.singleTracker()) {
            return ofNullable(tracker.getLastTrackerIndex()).map(
                    lastIndex -> new Position(segment, lastIndex).merge(position)).orElse(position);
        } else {
            return position;
        }
    }

    protected List<SerializedMessage> filter(List<SerializedMessage> messages, int[] segmentRange,
                                             Position position, Tracker tracker) {
        List<SerializedMessage> result = null;
        Predicate<SerializedMessage> predicate = filterPredicate(segmentRange, position, tracker);
        for (int i = 0; i < messages.size(); i++) {
            SerializedMessage message = messages.get(i);
            if (predicate.test(message)) {
                if (result == null) {
                    result = new ArrayList<>(messages.size() - i);
                }
                result.add(message);
            }
        }
        return result == null ? emptyList() : result;
    }

    protected Predicate<SerializedMessage> filterPredicate(int[] segmentRange, Position position, Tracker tracker) {
        return message -> {
            SerializedMessage segmentedMessage = ensureMessageSegment(message);
            return tracker.canHandle(segmentedMessage, segmentRange)
                   && (tracker.ignoreSegment() || position.isNewMessage(segmentedMessage));
        };
    }

    protected SerializedMessage ensureMessageSegment(SerializedMessage message) {
        message.setSegment(message.getSegment() == null ? computeSegment(
                message.getMessageId(), segments) : message.getSegment() % segments);
        return message;
    }

    protected int adjustMaxSize(Tracker tracker, int maxSize) {
        return ofNullable(clusters.get(tracker.getConsumerName()))
                .map(cluster -> cluster.getTrackers().size() * maxSize).orElse(maxSize);
    }

    protected int[] claimSegmentRange(Tracker tracker) {
        TrackerCluster cluster = clusters.compute(tracker.getConsumerName(), (p, c) -> ofNullable(c)
                .orElseGet(() -> new TrackerCluster(segments)).withActiveTracker(tracker));
        return cluster.getSegment(tracker);
    }

    protected void onUpdate(List<SerializedMessage> messages) {
        if (stopped) {
            return;
        }
        recordRequestStages(messages, "update-received");
        updateNotificationVersion.incrementAndGet();
        if (!updateNotificationPending.getAndSet(true) && FluxzeroJfr.batchEnabled()) {
            updateNotificationPendingSinceNanos.compareAndSet(0L, System.nanoTime());
        }
        if (updateNotificationRunning.compareAndSet(false, true)) {
            scheduler.submit(this::drainUpdateNotifications);
        }
    }

    protected void onClusterUpdate(TrackerCluster cluster) {
        if (!stopped) {
            List<WaitingTracker> trackers = cluster.getTrackers().stream().map(waitingTrackers::get)
                    .filter(Objects::nonNull).toList();
            trackers.forEach(WaitingTracker::run);
        }
    }

    @Override
    public Set<Tracker> disconnectTrackers(Predicate<Tracker> predicate, boolean sendFinalEmptyBatch) {
        Set<Tracker> removed = new HashSet<>();
        Set<Tracker> removedAndWaiting = new HashSet<>();
        Set<TrackerCluster> updatedClusters = new HashSet<>();
        closeOpenRequests(predicate, sendFinalEmptyBatch);
        waitingTrackers.forEach((tracker, waitingTracker) -> {
            if ((predicate.test(tracker) || predicate.test(waitingTracker.tracker))
                && waitingTrackers.remove(tracker, waitingTracker)) {
                removedAndWaiting.add(waitingTracker.tracker);
            }
        });
        clusters.replaceAll((key, cluster) -> {
            var updatedCluster = cluster.purgeTrackers(predicate);
            if (!Objects.equals(updatedCluster, cluster) && !updatedCluster.isEmpty()) {
                updatedClusters.add(updatedCluster);
            }
            var removedTrackers = new HashSet<>(cluster.getTrackers());
            removedTrackers.removeAll(updatedCluster.getTrackers());
            removed.addAll(removedTrackers);
            return updatedCluster;
        });
        clusters.values().removeIf(TrackerCluster::isEmpty);
        updatedClusters.forEach(this::onClusterUpdate);
        closeOpenRequests(t -> removed.contains(t) || removedAndWaiting.contains(t), sendFinalEmptyBatch);
        return removed;
    }

    protected void purgeCeasedTrackers(Duration delay) {
        scheduler.schedule(currentTimeMillis() + delay.toMillis(), () -> {
            clusters.replaceAll((key, cluster) -> {
                TrackerCluster after = cluster.purgeTrackers(
                        t -> t.getPurgeDelay() != null && cluster.getProcessingDuration(t)
                                .filter(d -> d.toMillis() > t.getPurgeDelay()).isPresent());
                if (after != cluster) {
                    Set<Tracker> removed = new HashSet<>(cluster.getTrackers());
                    removed.removeAll(after.getTrackers());
                    if (!removed.isEmpty()) {
                        log.warn("Purged trackers from consumer {} because they have ceased processing: {}", key,
                                 removed);
                        return after;
                    }
                }
                return cluster;
            });
            purgeCeasedTrackers(delay);
        });
    }

    private Long getLastIndex(List<SerializedMessage> messages) {
        return messages.isEmpty() ? null : messages.getLast().getIndex();
    }

    private void drainUpdateNotifications() {
        try {
            while (!stopped) {
                updateNotificationPending.set(false);
                long pendingSinceNanos = updateNotificationPendingSinceNanos.getAndSet(0L);
                long notificationReadyNanos = FluxzeroJfr.batchEnabled() ? System.nanoTime() : 0L;
                FluxzeroJfr.Batch event = startTrackingBatch("notification-drain", 0);
                Throwable failure = null;
                try {
                    long currentUpdateNotificationVersion = updateNotificationVersion.get();
                    List<WaitingTracker> trackers = new ArrayList<>(waitingTrackers.values());
                    if (event != null) {
                        event.itemCount = trackers.size();
                        event.queueDepth = trackers.size();
                        event.queueWaitNanos = pendingSinceNanos == 0L
                                ? 0L : Math.max(0L, notificationReadyNanos - pendingSinceNanos);
                    }
                    int resolved = 0;
                    for (WaitingTracker tracker : trackers) {
                        if (tracker.runIfBehind(currentUpdateNotificationVersion, notificationReadyNanos)) {
                            resolved++;
                        }
                    }
                    if (event != null) {
                        event.outputItemCount = resolved;
                        event.callbackNanos = System.nanoTime() - notificationReadyNanos;
                    }

                    if (!updateNotificationPending.get()) {
                        updateNotificationRunning.set(false);
                        if (!updateNotificationPending.get()
                            || !updateNotificationRunning.compareAndSet(false, true)) {
                            return;
                        }
                    }
                } catch (Throwable e) {
                    failure = e;
                    throw e;
                } finally {
                    FluxzeroJfr.finish(event, failure);
                }
            }
        } catch (Throwable e) {
            updateNotificationRunning.set(false);
            throw e;
        }
    }

    private WaitingTracker removeWaitingTracker(Tracker tracker) {
        WaitingTracker waitingTracker = waitingTrackers.get(tracker);
        if (waitingTracker != null && waitingTracker.tracker == tracker
            && waitingTrackers.remove(tracker, waitingTracker)) {
            return waitingTracker;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private TrackerRequest<MessageBatch> currentOrOpenRequest(Tracker tracker) {
        TrackerRequest<?> request = openRequests.get(tracker);
        return request == null ? openRequest(tracker, Function.identity()) : (TrackerRequest<MessageBatch>) request;
    }

    private <T> TrackerRequest<T> openRequest(Tracker tracker, Function<MessageBatch, T> mapper) {
        TrackerRequest<T> result = new TrackerRequest<>(mapper);
        TrackerRequest<?> existing = openRequests.put(tracker, result);
        if (existing != null) {
            existing.markReplaced();
            completeRequest(tracker, existing, finalEmptyBatch());
        }
        return result;
    }

    private void disconnectClosedRequest(Tracker tracker, TrackerRequest<?> request) {
        if (!request.isReplaced()) {
            disconnectTrackers(tracker::equals, false);
        }
    }

    private boolean completeRequest(Tracker tracker, TrackerRequest<?> result, MessageBatch batch) {
        recordRequestStages(batch.getMessages(), "batch-resolved");
        boolean completed = result.complete(batch);
        if (completed) {
            openRequests.remove(tracker, result);
        }
        return completed;
    }

    private void recordRequestStages(List<SerializedMessage> messages, String stage) {
        if (!FluxzeroJfr.requestStageEnabled()
                || messages.isEmpty()
                || !("COMMAND".equals(traceMessageType) || "RESULT".equals(traceMessageType))) {
            return;
        }
        int batchSize = messages.size();
        for (SerializedMessage message : messages) {
            Long boxedIndex = message.getIndex();
            long traceId = "RESULT".equals(traceMessageType)
                    ? message.getMetadataLongValue("$traceId", Long.MIN_VALUE)
                    : boxedIndex == null ? Long.MIN_VALUE : boxedIndex;
            if (traceId != Long.MIN_VALUE
                && FluxzeroJfr.requestTraceSampled(traceId)) {
                FluxzeroJfr.requestStage(
                        traceId, traceComponent, stage, batchSize,
                        boxedIndex == null ? -1L : boxedIndex);
            }
        }
    }

    private FluxzeroJfr.Batch startTrackingBatch(String operation, int itemCount) {
        return traceComponent == null ? null : FluxzeroJfr.startBatch(
                traceComponent, operation, traceMessageType, itemCount, 0L,
                waitingTrackers.size(), 0L);
    }

    private void cancelRequest(Tracker tracker, TrackerRequest<?> result) {
        result.cancel();
        openRequests.remove(tracker, result);
    }

    private void closeOpenRequests(Predicate<Tracker> predicate, boolean sendFinalEmptyBatch) {
        MessageBatch finalBatch = finalEmptyBatch();
        openRequests.forEach((tracker, result) -> {
            if (predicate.test(tracker)) {
                try {
                    if (sendFinalEmptyBatch) {
                        completeRequest(tracker, result, finalBatch);
                    } else {
                        cancelRequest(tracker, result);
                    }
                } catch (Exception e) {
                    log.error("Failed to close disconnecting tracker request: {}", tracker, e);
                }
            }
        });
    }

    private static MessageBatch finalEmptyBatch() {
        return new MessageBatch(new int[]{0, 0}, emptyList(), null, newPosition(), true);
    }

    private static long indexFromMillis(long millisSinceEpoch) {
        return millisSinceEpoch << 16;
    }

    @Override
    public void close() {
        stopped = true;
        scheduler.shutdown();
        sourceRegistration.cancel();
        source.close();
    }

    protected static class TrackerRequest<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final Function<MessageBatch, T> mapper;
        private volatile boolean replaced;

        protected TrackerRequest(Function<MessageBatch, T> mapper) {
            this.mapper = mapper;
        }

        protected CompletableFuture<T> future() {
            return result;
        }

        protected boolean isDone() {
            return result.isDone();
        }

        protected boolean isReplaced() {
            return replaced;
        }

        private void markReplaced() {
            replaced = true;
        }

        private boolean complete(MessageBatch batch) {
            return result.complete(mapper.apply(batch));
        }

        private void cancel() {
            result.cancel(false);
        }
    }

    @AllArgsConstructor
    protected class WaitingTracker implements Runnable {
        private final Tracker tracker;
        private final TrackerRequest<?> request;
        private final Registration scheduleToken;
        private final Runnable followUp;
        private final long waitingFromUpdateNotificationVersion;

        @Override
        public void run() {
            run(0L);
        }

        private boolean run(long notificationReadyNanos) {
            FluxzeroJfr.Batch event = notificationReadyNanos == 0L
                    ? null : startTrackingBatch("notification-tracker-resolution", 1);
            long started = event == null ? 0L : System.nanoTime();
            if (event != null) {
                event.queueWaitNanos = Math.max(0L, started - notificationReadyNanos);
            }
            Throwable failure = null;
            try {
                scheduleToken.cancel();
                if (waitingTrackers.remove(tracker, this) && !request.isDone()) {
                    long followUpStarted = event == null ? 0L : System.nanoTime();
                    if (event != null) {
                        event.preparationNanos = followUpStarted - started;
                        event.outputItemCount = 1;
                    }
                    followUp.run();
                    if (event != null) {
                        event.storageNanos = System.nanoTime() - followUpStarted;
                    }
                    return true;
                }
            } catch (Throwable e) {
                failure = e;
                log.error("Failed to execute tracker fetch / follow up", e);
            } finally {
                FluxzeroJfr.finish(event, failure);
            }
            return false;
        }

        boolean runIfBehind(long currentUpdateNotificationVersion, long notificationReadyNanos) {
            if (waitingFromUpdateNotificationVersion < currentUpdateNotificationVersion) {
                return run(notificationReadyNanos);
            }
            return false;
        }
    }
}
