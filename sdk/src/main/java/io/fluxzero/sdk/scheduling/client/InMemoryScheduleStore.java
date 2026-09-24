/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.scheduling.client;

import io.fluxzero.common.DelegatingClock;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.common.api.scheduling.ScheduleAutoCancelled;
import io.fluxzero.sdk.persisting.eventsourcing.client.InMemoryEventStore;
import io.fluxzero.common.tracking.MessageStoreBatch;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.tracking.IndexUtils.indexFromMillis;
import static io.fluxzero.sdk.tracking.IndexUtils.maxIndexFromMillis;
import static io.fluxzero.sdk.tracking.IndexUtils.millisFromIndex;
import static io.fluxzero.sdk.tracking.IndexUtils.timestampFromIndex;

/**
 * An in-memory implementation of a scheduling store that allows the scheduling, retrieval, and management of scheduled
 * messages. It extends `InMemoryMessageStore` to reuse the functionalities for storing and managing messages and
 * implements `SchedulingClient` to support scheduling-specific operations.
 * <p>
 * This implementation provides thread-safe mechanisms for scheduling, retrieving, and cancelling messages. Messages are
 * scheduled to be processed at specific timestamps, with support for expiration and filtering of schedules.
 */
@Slf4j
public class InMemoryScheduleStore extends InMemoryMessageStore implements SchedulingClient {

    private final ConcurrentSkipListMap<Long, String> scheduleIdsByIndex = new ConcurrentSkipListMap<>();
    private final AtomicLong minScheduleIndex = new AtomicLong();
    private final DelegatingClock clock = new DelegatingClock();
    private Registration clockChangeRegistration = Registration.noOp();
    private InMemoryEventStore modelStore;
    private Consumer<ScheduleAutoCancelled> cancellationMetrics = ignored -> {};
    private final Map<String, Map<String, Long>> ownedSchedules = new HashMap<>();
    private final Map<String, Long> ownedScheduleIndices = new HashMap<>();
    private final Map<String, Set<String>> schedulesByParent = new HashMap<>();

    /** Links scheduling to the namespace's committed Model store and payload-free metrics sink. */
    public void configureParents(InMemoryEventStore modelStore, Consumer<ScheduleAutoCancelled> metrics) {
        this.modelStore = modelStore;
        this.cancellationMetrics = metrics;
        modelStore.setScheduleDeletionMonitor(this::cancelDeletedParents);
    }

    public InMemoryScheduleStore() {
        super(SCHEDULE);
    }

    public InMemoryScheduleStore(Duration messageExpiration) {
        super(SCHEDULE, messageExpiration);
    }

    public InMemoryScheduleStore(Duration messageExpiration, Clock clock) {
        super(SCHEDULE, messageExpiration);
        if (clock != null) {
            setClock(clock);
        }
    }

    @Override
    public CompletableFuture<Void> append(SerializedMessage... messages) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Void> schedule(Guarantee guarantee, SerializedSchedule... schedules) {
        return scheduleBound(Map.of(), schedules);
    }

    @Override
    public CompletableFuture<Map<String, Long>> bindScheduleParents(List<String> parentIds) {
        if (modelStore == null) {
            return SchedulingClient.super.bindScheduleParents(parentIds);
        }
        try {
            return CompletableFuture.completedFuture(modelStore.bindScheduleParents(parentIds));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<Void> scheduleBoundToParents(Guarantee guarantee, Map<String, Long> parents,
                                                          SerializedSchedule... schedules) {
        if (modelStore == null || parents.isEmpty()) {
            return SchedulingClient.super.scheduleBoundToParents(guarantee, parents, schedules);
        }
        return scheduleBound(Map.copyOf(parents), schedules);
    }

    private CompletableFuture<Void> scheduleBound(Map<String, Long> parents, SerializedSchedule... schedules) {
        List<ScheduleAutoCancelled> cancellations = new ArrayList<>();
        try {
            return doScheduleBound(parents, cancellations, schedules);
        } finally {
            publishCancellations(cancellations);
        }
    }

    private CompletableFuture<Void> doScheduleBound(Map<String, Long> parents,
                                                     List<ScheduleAutoCancelled> cancellations,
                                                     SerializedSchedule... schedules) {
        synchronized (monitorNotificationLock()) {
            List<SerializedMessage> storedMessages = null;
            try {
                synchronized (this) {
                    if (!parents.isEmpty() && !modelStore.validScheduleParentBindings(parents)) {
                        throw new IllegalStateException("Schedule parent lifetime has changed; the schedule was not accepted");
                    }
                    List<SerializedSchedule> filtered = Arrays.stream(schedules)
                            .filter(s -> !s.isIfAbsent() || !scheduleIdsByIndex.containsValue(s.getScheduleId()))
                            .toList();
                    long now = clock.millis();
                    for (SerializedSchedule schedule : filtered) {
                        removeOwnership(schedule.getScheduleId());
                        scheduleIdsByIndex.values().removeIf(s -> s.equals(schedule.getScheduleId()));

                        long index = schedule.getTimestamp() > now ? indexFromMillis(schedule.getTimestamp())
                                : minScheduleIndex.updateAndGet(i -> Math.max(indexFromMillis(now), i + 1));
                        while (scheduleIdsByIndex.putIfAbsent(index, schedule.getScheduleId()) != null) {
                            index++;
                        }
                        schedule.getMessage().setIndex(index);
                        if (!parents.isEmpty()) {
                            ownedSchedules.put(schedule.getScheduleId(), parents);
                            ownedScheduleIndices.put(schedule.getScheduleId(), index);
                            parents.keySet().forEach(id -> schedulesByParent.computeIfAbsent(id, ignored -> new HashSet<>())
                                    .add(schedule.getScheduleId()));
                        }
                    }
                    storedMessages = filtered.stream().map(SerializedSchedule::getMessage).toList();
                    appendMessages(storedMessages);
                    if (!parents.isEmpty()) {
                        // Deletion may have completed and its notification drained between binding and insertion.
                        parents.keySet().forEach(id -> cancelStaleParent(id, modelStore.scheduleParentEpoch(id), cancellations));
                    }
                }
                return CompletableFuture.completedFuture(null);
            } finally {
                if (storedMessages != null) {
                    notifyMonitors(storedMessages);
                }
            }
        }
    }

    @Override
    public synchronized CompletableFuture<Void> cancelSchedule(String scheduleId, Guarantee guarantee) {
        scheduleIdsByIndex.values().removeIf(s -> s.equals(scheduleId));
        removeOwnership(scheduleId);
        return CompletableFuture.completedFuture(null);
    }

    private void removeOwnership(String scheduleId) {
        ownedScheduleIndices.remove(scheduleId);
        var parents = ownedSchedules.remove(scheduleId);
        if (parents != null) {
            parents.keySet().forEach(parent -> {
                var schedules = schedulesByParent.get(parent);
                schedules.remove(scheduleId);
                if (schedules.isEmpty()) {
                    schedulesByParent.remove(parent);
                }
            });
        }
    }

    private void cancelDeletedParents(Map<String, Long> parents) {
        List<ScheduleAutoCancelled> cancellations = new ArrayList<>();
        synchronized (this) {
            parents.forEach((id, epoch) -> cancelStaleParent(id, epoch, cancellations));
        }
        publishCancellations(cancellations);
    }

    private void cancelStaleParent(String parent, long epoch, List<ScheduleAutoCancelled> cancellations) {
        for (String id : List.copyOf(schedulesByParent.getOrDefault(parent, Set.of()))) {
            if (ownedSchedules.get(id).get(parent) < epoch) {
                long index = ownedScheduleIndices.get(id);
                var message = getMessage(index);
                scheduleIdsByIndex.remove(index);
                removeOwnership(id);
                if (message != null) {
                    cancellations.add(new ScheduleAutoCancelled(id, message.getMessageId(), millisFromIndex(index)));
                }
            }
        }
    }

    private void publishCancellations(List<ScheduleAutoCancelled> cancellations) {
        cancellations.forEach(metric -> {
            try {
                cancellationMetrics.accept(metric);
            } catch (Exception e) {
                log.warn("Failed to publish schedule auto-cancellation metric", e);
            }
        });
    }

    @Override
    public synchronized SerializedSchedule getSchedule(String scheduleId) {
        return scheduleIdsByIndex.entrySet().stream().filter(e -> scheduleId.equals(e.getValue())).findFirst()
                .map(e -> {
                    SerializedMessage message = getMessage(e.getKey());
                    return new SerializedSchedule(scheduleId, millisFromIndex(e.getKey()), message, false);
                }).orElse(null);
    }

    @Override
    public CompletableFuture<Void> append(List<SerializedMessage> messages) {
        throw new UnsupportedOperationException("Use method #schedule instead");
    }

    /**
     * Returns a batch of schedules that are due for delivery.
     * <p>
     * Unlike a regular message store, this schedule store only exposes entries whose deadline has passed and whose
     * schedule id is still active.
     */
    @Override
    public synchronized List<SerializedMessage> getBatch(Long minIndex, int maxSize, boolean inclusive) {
        return getBatch(minIndex, maxSize, inclusive, false);
    }

    /**
     * Returns a batch of schedules from the active schedule index.
     * <p>
     * When {@code includeFuture} is {@code false}, only schedules whose deadline has passed are returned. When it is
     * {@code true}, future schedules are also included. This is used by the test server to discover the next hidden
     * deadline and wake waiting trackers as soon as that deadline expires.
     */
    public synchronized List<SerializedMessage> getBatch(Long minIndex, int maxSize, boolean inclusive,
                                                         boolean includeFuture) {
        if (includeFuture) {
            return scheduleIdsByIndex.tailMap(Optional.ofNullable(minIndex).orElse(-1L), inclusive).keySet().stream()
                    .map(this::getMessage)
                    .limit(maxSize)
                    .toList();
        }
        long maximumIndex = maxIndexFromMillis(clock.millis());
        return scheduleIdsByIndex.tailMap(Optional.ofNullable(minIndex).orElse(-1L), inclusive).keySet().stream()
                .filter(aLong -> aLong <= maximumIndex).map(this::getMessage).limit(maxSize).toList();
    }

    @Override
    public synchronized MessageStoreBatch scanBatch(Long minIndex, int maxSize, boolean inclusive, long maxBytes,
                                                    Predicate<? super SerializedMessage> filter) {
        long maximumIndex = maxIndexFromMillis(clock.millis());
        Iterable<SerializedMessage> messages = () -> scheduleIdsByIndex
                .tailMap(Optional.ofNullable(minIndex).orElse(-1L), inclusive)
                .keySet().stream()
                .filter(index -> index <= maximumIndex)
                .map(this::getMessage)
                .iterator();
        return MessageStoreBatch.scan(messages, maxSize, maxBytes, filter);
    }

    public void setClock(@NonNull Clock clock) {
        synchronized (monitorNotificationLock()) {
            synchronized (this) {
                clockChangeRegistration.cancel();
                this.clock.setDelegate(clock);
                clockChangeRegistration = clock instanceof DelegatingClock delegatingClock
                        ? delegatingClock.onChange(this::clockChanged) : Registration.noOp();
                this.minScheduleIndex.set(0L);
            }
            notifyMonitors();
        }
    }

    protected void clockChanged() {
        synchronized (monitorNotificationLock()) {
            synchronized (this) {
                this.minScheduleIndex.set(0L);
            }
            notifyMonitors();
        }
    }

    @Override
    public void truncate() {
        synchronized (monitorNotificationLock()) {
            synchronized (this) {
                scheduleIdsByIndex.clear();
                ownedSchedules.clear();
                ownedScheduleIndices.clear();
                schedulesByParent.clear();
                minScheduleIndex.set(0L);
                truncateMessages();
            }
            notifyMonitors();
        }
    }

    public synchronized List<Schedule> getFutureSchedules(Serializer serializer) {
        return asList(scheduleIdsByIndex.tailMap(indexFromMillis(clock.millis()), false), serializer);
    }

    public synchronized List<Schedule> removeExpiredSchedules(Serializer serializer) {
        Map<Long, String> expiredEntries = scheduleIdsByIndex.headMap(maxIndexFromMillis(clock.millis()), true);
        List<Schedule> result = asList(expiredEntries, serializer);
        List.copyOf(expiredEntries.values()).forEach(this::removeOwnership);
        expiredEntries.clear();
        return result;
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    protected List<Schedule> asList(Map<Long, String> scheduleIdsByIndex, Serializer serializer) {
        return scheduleIdsByIndex.entrySet().stream().map(e -> {
            SerializedMessage m = getMessage(e.getKey());
            DeserializingMessage deserializingMessage =
                    serializer.deserializeMessages(Stream.of(m), SCHEDULE).findFirst().get();
            return new Schedule(deserializingMessage.getPayload(),
                    m.getMetadata(), m.getMessageId(), deserializingMessage.getTimestamp(),
                                e.getValue(), timestampFromIndex(e.getKey()));
        }).toList();
    }

    @Override
    protected void purgeExpiredMessages(Duration messageExpiration) {
        synchronized (this) {
            var expired = scheduleIdsByIndex.headMap(maxIndexFromMillis(
                    clock.millis() - messageExpiration.toMillis()), true);
            List.copyOf(expired.values()).forEach(this::removeOwnership);
            expired.clear();
        }
        super.purgeExpiredMessages(messageExpiration);
    }

    @Override
    public String toString() {
        return "InMemoryScheduleStore";
    }

    @Override
    public void close() {
        synchronized (monitorNotificationLock()) {
            synchronized (this) {
                clockChangeRegistration.cancel();
                super.close();
            }
        }
    }
}
