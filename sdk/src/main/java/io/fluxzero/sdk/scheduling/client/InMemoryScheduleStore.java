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
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.tracking.IndexUtils.indexFromMillis;
import static io.fluxzero.sdk.tracking.IndexUtils.maxIndexFromMillis;
import static io.fluxzero.sdk.tracking.IndexUtils.millisFromIndex;
import static io.fluxzero.sdk.tracking.IndexUtils.timestampFromIndex;
import static java.util.stream.Collectors.toList;

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

    public InMemoryScheduleStore() {
        super(SCHEDULE);
    }

    public InMemoryScheduleStore(Duration messageExpiration) {
        super(SCHEDULE, messageExpiration);
    }

    @Override
    protected Collection<SerializedMessage> filterMessages(Collection<SerializedMessage> messages) {
        long maximumIndex = maxIndexFromMillis(clock.millis());
        return super.filterMessages(messages).stream()
                .filter(m -> m.getIndex() <= maximumIndex && scheduleIdsByIndex.containsKey(m.getIndex()))
                .collect(toList());
    }

    @Override
    public CompletableFuture<Void> append(SerializedMessage... messages) {
        throw new UnsupportedOperationException();
    }

    @SneakyThrows
    @Override
    public synchronized CompletableFuture<Void> schedule(Guarantee guarantee, SerializedSchedule... schedules) {
        List<SerializedSchedule> filtered = Arrays.stream(schedules)
                .filter(s -> !s.isIfAbsent() || !scheduleIdsByIndex.containsValue(s.getScheduleId())).toList();
        long now = clock.millis();
        for (SerializedSchedule schedule : filtered) {
            cancelSchedule(schedule.getScheduleId());

            long index = schedule.getTimestamp() > now ? indexFromMillis(schedule.getTimestamp())
                    : minScheduleIndex.updateAndGet(i -> Math.max(indexFromMillis(now), i + 1));
            while (scheduleIdsByIndex.putIfAbsent(index, schedule.getScheduleId()) != null) {
                index++;
            }
            schedule.getMessage().setIndex(index);
        }
        return super.append(filtered.stream().map(SerializedSchedule::getMessage).toList());
    }

    @Override
    public synchronized CompletableFuture<Void> cancelSchedule(String scheduleId, Guarantee guarantee) {
        scheduleIdsByIndex.values().removeIf(s -> s.equals(scheduleId));
        return CompletableFuture.completedFuture(null);
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

    public synchronized void setClock(@NonNull Clock clock) {
        this.clock.setDelegate(clock);
        this.minScheduleIndex.set(0L);
        notifyMonitors();
    }

    public synchronized List<Schedule> getFutureSchedules(Serializer serializer) {
        return asList(scheduleIdsByIndex.tailMap(indexFromMillis(clock.millis()), false), serializer);
    }

    public synchronized List<Schedule> removeExpiredSchedules(Serializer serializer) {
        Map<Long, String> expiredEntries = scheduleIdsByIndex.headMap(maxIndexFromMillis(clock.millis()), true);
        List<Schedule> result = asList(expiredEntries, serializer);
        expiredEntries.clear();
        return result;
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    protected List<Schedule> asList(Map<Long, String> scheduleIdsByIndex, Serializer serializer) {
        return scheduleIdsByIndex.entrySet().stream().map(e -> {
            SerializedMessage m = getMessage(e.getKey());
            return new Schedule(
                    serializer.deserializeMessages(Stream.of(m), SCHEDULE).findFirst().get().getPayload(),
                    m.getMetadata(), e.getValue(), timestampFromIndex(e.getKey()));
        }).toList();
    }

    @Override
    protected void purgeExpiredMessages(Duration messageExpiration) {
        scheduleIdsByIndex.headMap(maxIndexFromMillis(
                clock.millis() - messageExpiration.toMillis()), true).clear();
        super.purgeExpiredMessages(messageExpiration);
    }

    @Override
    public String toString() {
        return "InMemoryScheduleStore";
    }
}
