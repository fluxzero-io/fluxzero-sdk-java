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
 */

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSchedulerScheduleIdTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final ScheduleId SCHEDULE_ID = ScheduleId.of("expiry", "42");

    @Test
    void factoryCreatesStableScheduleId() {
        assertEquals(new ScheduleId("expiry", "42"), SCHEDULE_ID);
        assertEquals("expiry:42", SCHEDULE_ID.toString());
    }

    @Test
    void typedScheduleIdSurvivesSchedulingLookupAndCancellation() {
        Instant deadline = START.plus(Duration.ofHours(1));

        TestFixture.create().atFixedTime(START)
                .whenExecuting(fluxzero -> fluxzero.messageScheduler().schedule("payload", SCHEDULE_ID, deadline))
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        SCHEDULE_ID.toString().equals(schedule.getScheduleId()))
                .andThen()
                .whenApplying(fluxzero -> fluxzero.messageScheduler().getSchedule(SCHEDULE_ID)
                        .map(Schedule::getScheduleId).orElse(null))
                .expectResult("expiry:42")
                .andThen()
                .whenExecuting(fluxzero -> fluxzero.messageScheduler().cancelSchedule(SCHEDULE_ID))
                .expectNoSchedules();
    }

    @Test
    void typedScheduleIdSurvivesCommandSchedulingAndCancellation() {
        Instant deadline = START.plus(Duration.ofHours(1));

        TestFixture.create().atFixedTime(START)
                .whenExecuting(fluxzero ->
                        fluxzero.messageScheduler().scheduleCommand("command", SCHEDULE_ID, deadline))
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        SCHEDULE_ID.toString().equals(schedule.getScheduleId()))
                .andThen()
                .whenExecuting(fluxzero -> fluxzero.messageScheduler().cancelSchedule(SCHEDULE_ID))
                .expectNoSchedules()
                .andThen()
                .whenTimeAdvancesTo(deadline)
                .expectNoCommands();
    }

    @Test
    void typedScheduleIdSurvivesPeriodicScheduling() {
        TestFixture.create().atFixedTime(START)
                .whenApplying(fluxzero ->
                        fluxzero.messageScheduler().schedulePeriodic(new PeriodicTask(), SCHEDULE_ID))
                .expectResult("expiry:42")
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        SCHEDULE_ID.toString().equals(schedule.getScheduleId()));
    }

    @Test
    void nullScheduleIdKeepsExistingPayloadFallback() {
        Instant deadline = START.plus(Duration.ofHours(1));

        TestFixture.create().atFixedTime(START)
                .whenExecuting(fluxzero -> fluxzero.messageScheduler().schedule("payload", null, deadline))
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        "payload".equals(schedule.getScheduleId()));
    }

    @Test
    void typedScheduleIdOverloadsAreInterfaceDefaults() throws NoSuchMethodException {
        List<Method> overloads = List.of(
                method("schedulePeriodic", Object.class, ScheduleId.class),
                method("schedule", Object.class, ScheduleId.class, Instant.class),
                method("schedule", Object.class, ScheduleId.class, Duration.class),
                method("schedule", Object.class, Metadata.class, ScheduleId.class, Instant.class),
                method("schedule", Object.class, Metadata.class, ScheduleId.class, Duration.class),
                method("scheduleCommand", Object.class, ScheduleId.class, Instant.class),
                method("scheduleCommand", Object.class, ScheduleId.class, Duration.class),
                method("scheduleCommand", Object.class, Metadata.class, ScheduleId.class, Instant.class),
                method("scheduleCommand", Object.class, Metadata.class, ScheduleId.class, Duration.class),
                method("cancelSchedule", ScheduleId.class),
                method("getSchedule", ScheduleId.class));

        assertEquals(11, overloads.size());
        assertTrue(overloads.stream().allMatch(Method::isDefault));
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return MessageScheduler.class.getMethod(name, parameterTypes);
    }

    @Periodic(delay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS, autoStart = false)
    private record PeriodicTask() {
    }
}
