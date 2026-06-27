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
 */

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingInterceptorTest {
    private static final Instant START = Instant.parse("2024-01-01T12:10:00Z");
    private static final Instant NEXT_HOURLY_DEADLINE = Instant.parse("2024-01-01T13:00:00Z");
    private static final Instant INTENTIONAL_DELAY_DEADLINE = Instant.parse("2024-01-01T18:00:00Z");

    @Test
    void autoStartReplacesCronScheduleCreatedWithDifferentCronConfig() {
        Schedule existingSchedule = new Schedule(
                new CurrentCronSchedule(),
                cronMetadata(periodic(PreviousCronSchedule.class)),
                CurrentCronSchedule.class.getName(), Instant.parse("2024-01-01T16:00:00Z"));

        TestFixture.create().withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true).atFixedTime(START)
                .givenSchedules(existingSchedule)
                .whenExecuting(fc -> fc.registerHandlers(new CurrentCronHandler()))
                .expectOnlyNewSchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof CurrentCronSchedule
                        && NEXT_HOURLY_DEADLINE.equals(schedule.getDeadline())
                        && hasCronSchema(schedule.getMetadata(), periodicMethod(CurrentCronHandler.class)))
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof CurrentCronSchedule
                        && NEXT_HOURLY_DEADLINE.equals(schedule.getDeadline()));
    }

    @Test
    void autoStartLeavesUnmarkedSchedulesUntouched() {
        Schedule existingSchedule = new Schedule(
                new CurrentCronSchedule(), CurrentCronSchedule.class.getName(), INTENTIONAL_DELAY_DEADLINE);

        TestFixture.create().withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true).atFixedTime(START)
                .givenSchedules(existingSchedule)
                .whenExecuting(fc -> fc.registerHandlers(new CurrentCronHandler()))
                .expectNoNewSchedules()
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof CurrentCronSchedule
                        && INTENTIONAL_DELAY_DEADLINE.equals(schedule.getDeadline())
                        && !wasCronScheduled(schedule.getMetadata()));
    }

    @Test
    void explicitDurationReturnClearsCronScheduleMetadata() {
        Schedule existingSchedule = new Schedule(
                new DynamicDelaySchedule(),
                cronMetadata(periodicMethod(DynamicDelayHandler.class)),
                DynamicDelaySchedule.class.getName(), NEXT_HOURLY_DEADLINE);

        TestFixture.create(new DynamicDelayHandler())
                .withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true)
                .atFixedTime(START)
                .givenSchedules(existingSchedule)
                .whenTimeAdvancesTo(NEXT_HOURLY_DEADLINE)
                .expectOnlyNewSchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof DynamicDelaySchedule
                        && INTENTIONAL_DELAY_DEADLINE.equals(schedule.getDeadline())
                        && !wasCronScheduled(schedule.getMetadata()));
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForPeriodicPayloads() throws Exception {
        try {
            TestFixture fixture = TestFixture.create().atFixedTime(START);

            GeneratedOnlyMetadataMode.run(() -> assertThrows(IllegalArgumentException.class,
                    () -> fixture.getFluxzero().messageScheduler()
                            .schedulePeriodic(new UnregisteredGeneratedOnlyPeriodicSchedule())));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForPeriodicPayloads() throws Exception {
        try {
            TestFixture fixture = TestFixture.create().atFixedTime(START);
            fixture.getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyPeriodicSchedule.class).registry());

            GeneratedOnlyMetadataMode.run(() -> assertEquals(
                    "registered-periodic",
                    fixture.getFluxzero().messageScheduler()
                            .schedulePeriodic(new RegisteredGeneratedOnlyPeriodicSchedule())));

            assertTrue(fixture.getFluxzero().messageScheduler().getSchedule("registered-periodic").isPresent());
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForPeriodicHandlerMethods() throws Exception {
        try {
            TestFixture fixture = TestFixture.create().withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true)
                    .atFixedTime(START);

            GeneratedOnlyMetadataMode.run(() ->
                    new SchedulingInterceptor().wrap(handler(UnregisteredGeneratedOnlyPeriodicHandler.class)));

            assertTrue(fixture.getFluxzero().messageScheduler()
                               .getSchedule(UnregisteredGeneratedOnlyMethodSchedule.class.getName()).isEmpty());
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForPeriodicHandlerMethods() throws Exception {
        try {
            TestFixture fixture = TestFixture.create().withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true)
                    .atFixedTime(START);
            fixture.getFluxzero().registerComponentRegistry(JvmComponentMetadataLookup.scan(
                    RegisteredGeneratedOnlyPeriodicHandler.class,
                    RegisteredGeneratedOnlyMethodSchedule.class).registry());

            GeneratedOnlyMetadataMode.run(() ->
                    new SchedulingInterceptor().wrap(handler(RegisteredGeneratedOnlyPeriodicHandler.class)));

            assertTrue(fixture.getFluxzero().messageScheduler()
                               .getSchedule(RegisteredGeneratedOnlyMethodSchedule.class.getName()).isPresent());
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static Periodic periodic(Class<?> type) {
        return type.getAnnotation(Periodic.class);
    }

    private static Periodic periodicMethod(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getAnnotation(Periodic.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();
    }

    private static Metadata cronMetadata(Periodic periodic) {
        return Metadata.of(PeriodicSchedulingDefaults.CRON_SCHEMA_METADATA_KEY,
                           PeriodicSchedulingDefaults.cronSchema(periodic));
    }

    private static boolean wasCronScheduled(Metadata metadata) {
        return metadata.containsKey(PeriodicSchedulingDefaults.CRON_SCHEMA_METADATA_KEY);
    }

    private static boolean hasCronSchema(Metadata metadata, Periodic periodic) {
        return PeriodicSchedulingDefaults.cronSchema(periodic)
                .equals(metadata.get(PeriodicSchedulingDefaults.CRON_SCHEMA_METADATA_KEY));
    }

    private static Handler<DeserializingMessage> handler(Class<?> targetClass) {
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.empty();
            }
        };
    }

    @Periodic(cron = "0 */4 * * *")
    static class PreviousCronSchedule {
    }

    static class CurrentCronSchedule {
        public CurrentCronSchedule() {
        }
    }

    static class CurrentCronHandler {
        @HandleSchedule
        @Periodic(cron = "0 * * * *")
        void handle(CurrentCronSchedule schedule) {
            Fluxzero.publishEvent(schedule);
        }
    }

    static class DynamicDelaySchedule {
        public DynamicDelaySchedule() {
        }
    }

    static class DynamicDelayHandler {
        @HandleSchedule
        @Periodic(cron = "0 * * * *", autoStart = false)
        Duration handle(DynamicDelaySchedule schedule) {
            return Duration.ofHours(5);
        }
    }

    @Periodic(delay = 1000, initialDelay = 0)
    static class UnregisteredGeneratedOnlyPeriodicSchedule {
    }

    @Periodic(delay = 1000, initialDelay = 0, scheduleId = "registered-periodic")
    static class RegisteredGeneratedOnlyPeriodicSchedule {
    }

    static class UnregisteredGeneratedOnlyMethodSchedule {
        public UnregisteredGeneratedOnlyMethodSchedule() {
        }
    }

    static class UnregisteredGeneratedOnlyPeriodicHandler {
        @HandleSchedule
        @Periodic(delay = 1000, initialDelay = 0)
        void handle(UnregisteredGeneratedOnlyMethodSchedule schedule) {
        }
    }

    static class RegisteredGeneratedOnlyMethodSchedule {
        public RegisteredGeneratedOnlyMethodSchedule() {
        }
    }

    static class RegisteredGeneratedOnlyPeriodicHandler {
        @HandleSchedule
        @Periodic(delay = 1000, initialDelay = 0)
        void handle(RegisteredGeneratedOnlyMethodSchedule schedule) {
        }
    }
}
