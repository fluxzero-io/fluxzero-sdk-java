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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.tracking.BatchInterceptor.preservingConsumerFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiMessageConsumerFilterTest {
    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void combinedAnnotationsKeepTheirTypedSelectionAndOptOut(boolean async, boolean filtering) {
        Map<String, List<String>> delivered = new ConcurrentHashMap<>();
        var builder = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.CONSUMER_FILTERING_PROPERTY, Boolean.toString(filtering))).andThen(existing))
                .addBatchInterceptor(preservingConsumerFilter((consumer, tracker) -> batch -> {
                    delivered.computeIfAbsent(tracker.getName() + ":" + tracker.getMessageType(),
                                              ignored -> new CopyOnWriteArrayList<>())
                            .addAll(batch.getMessages().stream().map(SerializedMessage::getType).toList());
                    consumer.accept(batch);
                }), EVENT, SCHEDULE);
        Object[] handlers = {new EventFirst(), new ScheduleFirst()};
        var fixture = async ? TestFixture.createAsync(builder, handlers) : TestFixture.create(builder, handlers);
        var start = Instant.parse("2026-01-01T00:00:00Z");
        try (var ignored = fixture.getFluxzero()) {
            fixture.atFixedTime(start).givenEvents(new Unrelated()).whenEvent(new Tick())
                    .expectOnlyCommands("event-first", "schedule-first").expectNoErrors().andThen()
                    .givenSchedules(new Schedule(new Unrelated(), "unrelated", start.plusSeconds(1)),
                                    new Schedule(new Tick(), "tick", start.plusSeconds(1)))
                    .whenTimeAdvancesTo(start.plusSeconds(1))
                    .expectOnlyCommands("event-first", "schedule-first").expectNoErrors();
            if (async) {
                for (String name : List.of("event-first", "schedule-first")) {
                    for (MessageType type : List.of(EVENT, SCHEDULE)) {
                        assertEquals(filtering ? List.of(Tick.class.getName())
                                             : List.of(Tick.class.getName(), Unrelated.class.getName()),
                                     delivered.getOrDefault(name + ":" + type, List.of()).stream().sorted().toList(),
                                     name + ":" + type);
                    }
                }
            }
        }
    }

    record Tick() {}
    record Unrelated() {}

    @Consumer(name = "event-first")
    static class EventFirst {
        @HandleEvent @HandleSchedule
        void handle(Tick tick) { Fluxzero.sendAndForgetCommand("event-first"); }
    }

    @Consumer(name = "schedule-first")
    static class ScheduleFirst {
        @HandleSchedule @HandleEvent
        void handle(Tick tick) { Fluxzero.sendAndForgetCommand("schedule-first"); }
    }
}
