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

package io.fluxzero.sdk.givenwhenthen;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.scheduling.CancelPeriodic;
import io.fluxzero.sdk.scheduling.Periodic;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.GivenWhenThenAssertionError;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.Stateful;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static io.fluxzero.sdk.Fluxzero.publishEvent;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GivenWhenThenSchedulingTest {

    private static final String atStartOfDay = "0 0 * * *";
    private static final String LEGACY_DEFAULTS_VERSION = "2026.05.20";
    private static final String NEW_DEFAULTS_VERSION = "2026.05.21";
    private final TestFixture subject = TestFixture.createJvmCompatibility(new CommandHandler(), new ScheduleHandler());

    @Test
    void testExpectCommandAfterDeadline() {
        Object command = "command";
        subject.givenSchedules(new Schedule(new YieldsCommand(command), "test",
                                            subject.getCurrentTime().plusSeconds(10)))
                .whenTimeElapses(Duration.ofSeconds(10))
                .expectCommands(command);
    }

    @Test
    void testExpectCommandAtTimestamp() {
        Object command = "command";
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        subject.givenSchedules(new Schedule(new YieldsCommand(command), "test", deadline))
                .whenTimeAdvancesTo(deadline)
                .expectCommands(command);
    }

    @Test
    void testGivenScheduleFromJsonFile() {
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        subject.givenSchedules(new Schedule("scheduling/yields-command.json", "test", deadline))
                .whenTimeAdvancesTo(deadline)
                .expectCommands("commandFromJson");
    }

    @Test
    void testGivenScheduleFromExtendedJsonFile() {
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        subject.givenSchedules(new Schedule("scheduling/extended/yields-command-extended.json", "test", deadline))
                .whenTimeAdvancesTo(deadline)
                .expectCommands("otherCommand");
    }

    @Test
    void testGivenExpiredSchedule() {
        Duration delay = Duration.ofMinutes(10);
        YieldsNewSchedule schedule = new YieldsNewSchedule(delay.toMillis());
        subject.givenExpiredSchedules(schedule)
                .whenTimeElapses(delay)
                .expectNewSchedules(schedule);
    }

    @Test
    void testGivenScheduleWithTimeInPastExecuteBeforeTest() {
        Object command = "command";
        Instant deadline = subject.getCurrentTime().minusSeconds(10);
        subject.givenSchedules(new Schedule(new YieldsCommand(command), "test", deadline))
                .whenExecuting(fc -> {
                })
                .expectNoCommands();
    }

    @Test
    void testExpectNoCommandBeforeDeadline() {
        Object command = "command";
        subject.givenSchedules(new Schedule(new YieldsCommand(command), "test",
                                            subject.getCurrentTime().plusSeconds(10)))
                .whenTimeElapses(Duration.ofSeconds(10).minusMillis(1))
                .expectNoCommands();
    }

    @Test
    void testExpectNoCommandAfterCancel() {
        Object command = "command";
        subject.givenSchedules(new Schedule(new YieldsCommand(command), "test",
                                            subject.getCurrentTime().plusSeconds(10)))
                .given(fc -> fc.messageScheduler().cancelSchedule("test"))
                .whenTimeElapses(Duration.ofSeconds(10))
                .expectNoCommands();
    }

    @Test
    void scheduleIndexCurrentTimeAlwaysNew() {
        String scheduleId = "test1323";
        AtomicReference<Long> firstIndex = new AtomicReference<>();
        TestFixture.createAsyncJvmCompatibility().spy()
                .whenExecuting(fc -> Fluxzero.schedule("foo1", scheduleId, subject.getCurrentTime()))
                .expectThat(fc -> firstIndex.set(fc.client().getSchedulingClient().getSchedule(scheduleId).getMessage().getIndex()))
                .andThen()
                .whenExecuting(fc -> Fluxzero.cancelSchedule(scheduleId))
                .expectTrue(fc -> fc.messageScheduler().getSchedule(scheduleId).isEmpty())
                .andThen()
                .whenExecuting(fc -> Fluxzero.schedule("foo2", scheduleId, subject.getCurrentTime()))
                .expectTrue(fc -> !firstIndex.get().equals(fc.client().getSchedulingClient()
                                                                   .getSchedule(scheduleId).getMessage().getIndex()));

    }

    @Nested
    class BranchIdTracing {
        private final Instant start = Instant.parse("2024-01-01T00:00:00Z");
        private final TestFixture fixture = TestFixture.createAsyncJvmCompatibility(new BranchIdCommandHandler(),
                                                                    new BranchIdScheduleHandler())
                .atFixedTime(start);

        @Test
        void propagatesBranchIdToDirectAndIndirectEffects() {
            Instant deadline = start.plusSeconds(10);
            String expectedBranchId = branchId("schedule-chain", deadline);

            fixture.whenCommand(new StartScheduleChain("schedule-chain", deadline))
                    .expectSchedule(s -> s.getScheduleId().equals("schedule-chain")
                                         && expectedBranchId.equals(s.getMetadata().get("$trace.$branchId")))
                    .andThen()
                    .whenTimeAdvancesTo(deadline)
                    .expectEvents((Predicate<Message>) e -> "direct-effect".equals(e.getPayload())
                                                     && expectedBranchId.equals(e.getMetadata().get("$trace.$branchId")))
                    .expectCommands((Predicate<Message>) c -> "indirect-command".equals(c.getPayload())
                                                       && expectedBranchId.equals(c.getMetadata().get("$trace.$branchId")))
                    .expectEvents((Predicate<Message>) e -> "indirect-effect".equals(e.getPayload())
                                                     && expectedBranchId.equals(e.getMetadata().get("$trace.$branchId")));
        }

        @Test
        void rescheduledScheduleGetsANewBranchId() {
            Instant firstDeadline = start.plusSeconds(10);
            Instant secondDeadline = firstDeadline.plusSeconds(5);
            String firstBranchId = branchId("reschedule-chain", firstDeadline);
            String secondBranchId = branchId("reschedule-chain", secondDeadline);

            assertNotEquals(firstBranchId, secondBranchId);

            fixture.whenCommand(new StartReschedulingChain("reschedule-chain", firstDeadline))
                    .expectSchedule(s -> s.getScheduleId().equals("reschedule-chain")
                                         && firstBranchId.equals(s.getMetadata().get("$trace.$branchId")))
                    .andThen()
                    .whenTimeAdvancesTo(firstDeadline)
                    .expectEvents((Predicate<Message>) e -> e.getPayload().equals(new BranchObserved(0))
                                                     && firstBranchId.equals(e.getMetadata().get("$trace.$branchId")))
                    .expectSchedule(s -> s.getScheduleId().equals("reschedule-chain")
                                         && secondDeadline.equals(s.getDeadline())
                                         && secondBranchId.equals(s.getMetadata().get("$trace.$branchId")))
                    .andThen()
                    .whenTimeAdvancesTo(secondDeadline)
                    .expectEvents((Predicate<Message>) e -> e.getPayload().equals(new BranchObserved(1))
                                                     && secondBranchId.equals(e.getMetadata().get("$trace.$branchId")));
        }

        private String branchId(String scheduleId, Instant deadline) {
            return "%s_%s".formatted(scheduleId, IndexUtils.indexFromTimestamp(deadline));
        }
    }

    @Nested
    class CronSchedules {
        private final Instant start = Instant.parse("2023-07-01T12:10:00Z");
        private final Instant afterOneHour = start.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));

        private final TestFixture testFixture = TestFixture.createJvmCompatibility().atFixedTime(start)
                .registerHandlers(new Object() {
                    @HandleSchedule
                    @Periodic(cron = "0 * * * *")
                    void handleSchedule(CronSchedule schedule, Schedule message) {
                        publishEvent(message.getDeadline());
                    }
                });

        @Test
        void testScheduleManualCron() {
            testFixture.whenExecuting(fc -> Fluxzero.schedulePeriodic(new PeriodicCronSchedule(), "123"))
                    .expectSchedule(s -> s.getScheduleId().equals("123") && s.getDeadline()
                            .atZone(ZoneId.of("Europe/Amsterdam"))
                            .equals(testFixture.getCurrentTime().atZone(ZoneId.of("Europe/Amsterdam"))
                                            .truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1))));
        }

        @Test
        void testPeriodicCronSchedule() {
            testFixture.whenTimeAdvancesTo(afterOneHour).expectOnlyEvents(afterOneHour);
        }

        @Test
        void testSecondPeriodicCronSchedule() {
            testFixture.givenTimeAdvancedTo(afterOneHour)
                    .whenTimeElapses(Duration.ofHours(1))
                    .expectOnlyEvents(afterOneHour.plus(Duration.ofHours(1)));
        }

        @Test
        void testPeriodicCronScheduleViaProperty() {
            TestFixture.createJvmCompatibility()
                    .withProperty("cronSchedule", "0 * * * *")
                    .atFixedTime(start)
                    .registerHandlers(new Object() {
                        @HandleSchedule
                        @Periodic(cron = "${cronSchedule:-}")
                        void handleSchedule(CronSchedule schedule, Schedule message) {
                            publishEvent(message.getDeadline());
                        }
                    }).whenTimeAdvancesTo(afterOneHour).expectOnlyEvents(afterOneHour);
        }

        @Test
        void testNoScheduleBeforeDeadline() {
            testFixture.whenTimeAdvancesTo(afterOneHour.minus(Duration.ofMinutes(1))).expectNoEvents();
        }

        @Test
        void disablePeriodicUsingSpecialExpression() {
            TestFixture.createJvmCompatibility().atFixedTime(start)
                    .registerHandlers(new Object() {
                        @HandleSchedule
                        @Periodic(cron = Periodic.DISABLED)
                        void handleSchedule(CronSchedule schedule, Schedule message) {
                            publishEvent(message.getDeadline());
                        }
                    }).whenTimeElapses(Duration.ofMinutes(10))
                    .expectNoEvents().expectNoSchedules();
        }

        @Test
        void useCronWithTimeZone() {
            TestFixture.createJvmCompatibility().atFixedTime(Instant.parse("2023-07-01T12:00:00+02:00"))
                    .registerHandlers(new Object() {
                        @HandleSchedule
                        @Periodic(cron = atStartOfDay, timeZone = "Europe/Amsterdam")
                        void handleSchedule(CronSchedule schedule, Schedule message) {
                            publishEvent(message.getDeadline());
                        }
                    }).whenTimeElapses(Duration.ofDays(1))
                    .expectOnlyEvents(Instant.parse("2023-07-02T00:00:00+02:00"));
        }

        @Test
        void disablePeriodicUsingSpecialExpression_viaMissingProperty() {
            TestFixture.createJvmCompatibility().atFixedTime(start)
                    .registerHandlers(new Object() {
                        @HandleSchedule
                        @Periodic(cron = "${someMissingProperty:-}")
                        void handleSchedule(CronSchedule schedule, Schedule message) {
                            publishEvent(message.getDeadline());
                        }
                    }).whenTimeElapses(Duration.ofMinutes(10))
                    .expectNoEvents().expectNoSchedules();
        }

        @Test
        void testScheduleExpiresBeforeEarlierScheduleIfClockIsChanged() {
            Object schedule = "schedule";
            TestFixture.createJvmCompatibility(new Object() {
                        @HandleSchedule
                        @Periodic(cron = "0 * * * *")
                        void handle(Object schedule) {
                            Fluxzero.publishEvent(schedule);
                        }
                    })
                    .atFixedTime(start)
                    .whenScheduleExpires(schedule)
                    .expectEvents(schedule);
        }
    }

    @Nested
    class PeriodicInitialDelayDefaults {
        private final Instant start = Instant.parse("2024-01-01T12:11:00Z");

        @Test
        void legacyDefaultsImplicitFixedDelayStartsImmediately() {
            fixtureWithDefaults(LEGACY_DEFAULTS_VERSION)
                    .whenExecuting(fc -> fc.registerHandlers(new ImplicitFixedDelayHandler("legacy delay")))
                    .expectOnlyEvents("legacy delay");
        }

        @Test
        void newDefaultsImplicitFixedDelayStartsAfterDelay() {
            Instant deadline = start.plusSeconds(60);

            fixtureWithDefaults(NEW_DEFAULTS_VERSION)
                    .whenExecuting(fc -> fc.registerHandlers(new ImplicitFixedDelayHandler("new delay")))
                    .expectNoEvents()
                    .expectSchedule(s -> s.getPayload() instanceof ImplicitFixedDelaySchedule
                                         && deadline.equals(s.getDeadline()))
                    .andThen()
                    .whenTimeAdvancesTo(deadline)
                    .expectOnlyEvents("new delay");
        }

        @Test
        void featureFlagEnablesImplicitFixedDelayDefault() {
            Instant deadline = start.plusSeconds(60);

            TestFixture.createJvmCompatibility().atFixedTime(start)
                    .withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true)
                    .whenExecuting(fc -> fc.registerHandlers(new ImplicitFixedDelayHandler("flag delay")))
                    .expectNoEvents()
                    .expectSchedule(s -> s.getPayload() instanceof ImplicitFixedDelaySchedule
                                         && deadline.equals(s.getDeadline()));
        }

        @Test
        void newDefaultsImplicitCronStartsAtNextCronFireTime() {
            Instant deadline = Instant.parse("2024-01-01T12:15:00Z");

            fixtureWithDefaults(NEW_DEFAULTS_VERSION)
                    .whenExecuting(fc -> fc.registerHandlers(new ImplicitCronHandler()))
                    .expectNoEvents()
                    .expectSchedule(s -> s.getPayload() instanceof ImplicitCronSchedule
                                         && deadline.equals(s.getDeadline()))
                    .andThen()
                    .whenTimeAdvancesTo(deadline)
                    .expectOnlyEvents(deadline);
        }

        @Test
        void explicitInitialDelayZeroStartsImmediatelyInBothDefaultsVersions() {
            assertInitialDelayZeroStartsImmediately(LEGACY_DEFAULTS_VERSION, "legacy zero");
            assertInitialDelayZeroStartsImmediately(NEW_DEFAULTS_VERSION, "new zero");
        }

        @Test
        void explicitPositiveInitialDelayWorksInBothDefaultsVersions() {
            assertPositiveInitialDelay(LEGACY_DEFAULTS_VERSION, "legacy positive");
            assertPositiveInitialDelay(NEW_DEFAULTS_VERSION, "new positive");
        }

        @Test
        void disabledCronDoesNotAutoStartInBothDefaultsVersions() {
            assertDisabledCronDoesNotAutoStart(LEGACY_DEFAULTS_VERSION);
            assertDisabledCronDoesNotAutoStart(NEW_DEFAULTS_VERSION);
        }

        private TestFixture fixtureWithDefaults(String defaultsVersion) {
            TestFixture fixture = TestFixture.createJvmCompatibility().atFixedTime(start);
            if (defaultsVersion != null) {
                fixture.withProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY, defaultsVersion);
            }
            return fixture;
        }

        private void assertInitialDelayZeroStartsImmediately(String defaultsVersion, String event) {
            fixtureWithDefaults(defaultsVersion)
                    .whenExecuting(fc -> fc.registerHandlers(new InitialDelayZeroHandler(event)))
                    .expectOnlyEvents(event);
        }

        private void assertPositiveInitialDelay(String defaultsVersion, String event) {
            Instant deadline = start.plusSeconds(5);

            fixtureWithDefaults(defaultsVersion)
                    .whenExecuting(fc -> fc.registerHandlers(new PositiveInitialDelayHandler(event)))
                    .expectNoEvents()
                    .expectSchedule(s -> s.getPayload() instanceof PositiveInitialDelaySchedule
                                         && deadline.equals(s.getDeadline()))
                    .andThen()
                    .whenTimeAdvancesTo(deadline)
                    .expectOnlyEvents(event);
        }

        private void assertDisabledCronDoesNotAutoStart(String defaultsVersion) {
            fixtureWithDefaults(defaultsVersion)
                    .whenExecuting(fc -> fc.registerHandlers(
                            new DisabledCronHandler(), new MissingPropertyDisabledCronHandler()))
                    .expectNoEvents()
                    .expectNoNewSchedules()
                    .expectNoSchedules();
        }
    }

    @Nested
    class SchedulingErrorTests {
        @Test
        void stopAfterError() {
            TestFixture.createJvmCompatibility(new Object() {
                        @HandleSchedule
                        @Periodic(continueOnError = false, delay = 60, initialDelay = 0,
                                timeUnit = TimeUnit.MINUTES)
                        void handleSchedule(Object schedule) {
                            throw new MockException();
                        }
                    })
                    .whenTimeElapses(Duration.ofMinutes(10))
                    .expectError()
                    .expectNoSchedules();
        }

        @Test
        void continueAfterError() {
            TestFixture.createJvmCompatibility(new Object() {
                        private int count = 0;

                        @HandleSchedule
                        @Periodic(delay = 60, initialDelay = 0, timeUnit = TimeUnit.MINUTES)
                        void handleSchedule(Object schedule) {
                            if (++count == 1) {
                                throw new MockException();
                            } else {
                                Fluxzero.publishEvent("success");
                            }
                        }
                    })
                    .whenTimeElapses(Duration.ofMinutes(10))
                    .expectSchedules(Object.class).expectNoEvents();
        }

        @Test
        void otherDelayAfterError() {
            TestFixture.createJvmCompatibility(new Object() {
                        private int count = 0;

                        @HandleSchedule
                        @Periodic(delayAfterError = 10, delay = 60, initialDelay = 0, timeUnit = TimeUnit.MINUTES)
                        void handleSchedule(Object schedule) {
                            if (++count == 1) {
                                throw new MockException();
                            } else {
                                Fluxzero.publishEvent("success");
                            }
                        }
                    })
                    .whenTimeElapses(Duration.ofMinutes(10))
                    .expectEvents("success");
        }
    }

    /*
        Test when expires
     */

    @Test
    void testWhenExpires() {
        Object command = "command";
        subject.whenScheduleExpires(new YieldsCommand(command)).expectCommands(command);
    }

    /*
        Test expect
     */

    @Test
    void testExpectSchedule() {
        YieldsSchedule command = new YieldsSchedule();
        subject.whenCommand(command)
                .expectOnlyNewSchedules(command.getSchedule())
                .expectSchedules(command.getSchedule());
    }

    @Test
    void testExpectScheduledCommand() {
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        Message command = new Message("command").addMetadata("a", "b");
        subject.whenCommand(new YieldsScheduledCommand(command, "testId", deadline))
                .expectOnlyScheduledCommands(command)
                .expectScheduledCommand(new Schedule(command.getPayload(), command.getMetadata(), "testId", deadline));
    }

    @Test
    void testExpectScheduledCommand_async() {
        Instant deadline = Instant.now().plusSeconds(10);
        TestFixture.createAsyncJvmCompatibility(new CommandHandler())
                .whenCommand(new YieldsScheduledCommand("command", "testId", deadline))
                .expectScheduledCommand("command");
    }

    @Test
    void testExpectNoScheduledCommandLike() {
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        subject.whenCommand(new YieldsScheduledCommand("command", "testId", deadline))
                .expectNoScheduledCommandsLike("otherCommand");
    }

    @Test
    void testExpectScheduleAnyTime() {
        YieldsSchedule command = new YieldsSchedule();
        subject.whenCommand(command).expectOnlyNewSchedules(command.getSchedule());
    }

    @Test
    void testExpectNoScheduleLike() {
        subject.whenCommand(new YieldsSchedule()).expectNoNewSchedulesLike("anotherPayload");
    }

    @Test
    void testExpectNoNewScheduleLike_predicate() {
        subject.whenCommand(new YieldsSchedule())
               .expectNoNewScheduleLike(s -> s.getPayload() instanceof Integer);
    }

    @Test
    void testExpectScheduleWithoutAnySchedules() {
        assertThrows(GivenWhenThenAssertionError.class,
                     () -> subject.whenCommand("command").expectOnlyNewSchedules("schedule"));
    }

    @Test
    void testExpectSchedulePayloadMismatch() {
        assertThrows(GivenWhenThenAssertionError.class,
                     () -> subject.whenCommand(new YieldsSchedule()).expectOnlyNewSchedules("otherPayload"));
    }

    /*
        Test rescheduling
     */

    @Test
    void testNoRescheduleOnVoid() {
        Duration delay = Duration.ofSeconds(10);
        Object payload = new YieldsCommand("whatever");
        subject.givenSchedules(new Schedule(payload, "test", subject.getCurrentTime().plus(delay)))
                .whenTimeElapses(delay)
                .expectNoNewSchedulesLike(YieldsCommand.class)
                .expectNoSchedulesLike(YieldsCommand.class);
    }

    @Test
    void testExpectNoScheduleLike_predicate() {
        subject.givenSchedules(new Schedule(new YieldsCommand("whatever"), Fluxzero.currentTime().plusSeconds(1)))
                .whenNothingHappens()
                .expectSchedule(s -> s.getPayload() instanceof YieldsCommand)
                .expectNoScheduleLike(s -> s.getPayload() instanceof String);
    }

    @Test
    void testReschedule() {
        Duration delay = Duration.ofSeconds(10);
        YieldsNewSchedule payload = new YieldsNewSchedule(delay.toMillis());
        subject.givenSchedules(new Schedule(payload, "test", subject.getCurrentTime().plus(delay)))
                .whenTimeElapses(delay).expectNewSchedules(payload);
    }

    @Test
    void testRescheduleAsync() {
        Duration delay = Duration.ofSeconds(10);
        var payload = new YieldsNewScheduleAsync(delay.toMillis());
        subject.givenSchedules(new Schedule(payload, "test", subject.getCurrentTime().plus(delay)))
                .whenTimeElapses(delay).expectNewSchedules(payload);
    }

    @Test
    void testScheduleOverride() {
        Duration delay = Duration.ofSeconds(10);
        Object expected = new YieldsCommand("original");
        Object notExpected = new YieldsCommand("override");
        subject.givenSchedules(new Schedule(expected, "test", subject.getCurrentTime().plus(delay)))
                .givenSchedules(
                        new Schedule(notExpected, "test", subject.getCurrentTime().plus(delay).minusSeconds(1)))
                .whenTimeElapses(delay).expectOnlyCommands("override");
    }

    @Test
    void testNoAutomaticRescheduleBeforeDeadline() {
        subject.givenElapsedTime(Duration.ofMillis(500)).whenExecuting(fc -> {
        }).expectNoNewSchedules();
    }

    @Test
    void testAutomaticReschedule() {
        subject.givenElapsedTime(Duration.ofMillis(500))
                .whenTimeElapses(Duration.ofMillis(1000)).expectOnlyNewSchedules(new PeriodicSchedule());
    }

    @Test
    void testAutomaticPeriodicSchedule() {
        subject.whenTimeElapses(Duration.ofMillis(1000))
                .expectNewSchedules(PeriodicSchedule.class);
    }

    @Test
    void testAutomaticPeriodicScheduleWithMethodAnnotation() {
        TestFixture.createJvmCompatibility(new MethodPeriodicHandler())
                .whenTimeElapses(Duration.ofMillis(1000)).expectNewSchedules(MethodPeriodicSchedule.class);
    }

    @Test
    void testNonAutomaticPeriodicSchedule() {
        subject.whenTimeElapses(Duration.ofMillis(1000))
                .expectNoNewSchedulesLike(NonAutomaticPeriodicSchedule.class);
    }

    @Test
    void testAlteredPayloadPeriodic() {
        TestFixture.createJvmCompatibility(new AlteredPayloadPeriodicHandler())
                .whenTimeElapses(Duration.ofMillis(1000)).expectOnlyNewSchedules(new YieldsAlteredSchedule(1));
    }

    @Test
    void testIncrementalSchedule() {
        subject.givenSchedules(new Schedule(new IncrementalSchedule(0), subject.getCurrentTime().plusSeconds(1)))
                .whenTimeElapses(Duration.ofMillis(10_100))
                .expectNewSchedules(IntStream.range(1, 11).mapToObj(IncrementalSchedule::new).toArray());
    }

    @Test
    void testIncrementalScheduleSingleJump() {
        subject.advanceTimeIncrementally(false)
                .givenSchedules(new Schedule(new IncrementalSchedule(0), subject.getCurrentTime().plusSeconds(1)))
                .whenTimeElapses(Duration.ofMillis(10_100))
                .expectNewSchedules(new IncrementalSchedule(1), new IncrementalSchedule(2))
                .expectNoNewSchedulesLike(new IncrementalSchedule(3));
    }

    @Test
    void testCancellingPeriodic() {
        TestFixture.createJvmCompatibility(new CancellingPeriodicHandler())
                .whenTimeElapses(Duration.ofMillis(1000))
                .expectNoNewSchedules()
                .expectNoSchedules();
    }

    @Test
    void testAlteredPayloadNonPeriodic() {
        TestFixture subject = TestFixture.createJvmCompatibility(new AlteredPayloadNonPeriodicHandler());
        Instant deadline = subject.getCurrentTime().plusSeconds(1);
        subject.givenSchedules(new Schedule(new YieldsAlteredSchedule(), "test", deadline))
                .whenTimeAdvancesTo(deadline).expectOnlyNewSchedules(new YieldsAlteredSchedule(1));
    }

    @Test
    void testAlteredPayloadNonPeriodicReturningSchedule() {
        TestFixture subject = TestFixture.createJvmCompatibility(new AlteredPayloadNonPeriodicHandlerReturningSchedule());
        Instant deadline = subject.getCurrentTime().plusSeconds(1);
        subject.givenSchedules(new Schedule(new YieldsAlteredSchedule(), "test", deadline))
                .whenTimeAdvancesTo(deadline).expectOnlyNewSchedules(new YieldsAlteredSchedule(1));
    }

    @Test
    void testInterfacePeriodicHandler() {
        TestFixture.createJvmCompatibility(new InterfacePeriodicHandler())
                .whenTimeElapses(Duration.ofMillis(1000))
                .expectNewSchedules(PeriodicScheduleFromInterface.class);
    }

    @Test
    void localScheduleHandlerIsTriggeredByTaskSchedulerInAsyncFixture() {
        TestFixture.createAsyncJvmCompatibility(new LocalScheduleHandler())
                .whenScheduleExpires(new LocalSchedule())
                .expectOnlyEvents("local schedule");
    }

    @Test
    void payloadScheduleHandlerWithoutTrackSelfIsHandledLocally() {
        TestFixture.createAsyncJvmCompatibility()
                .whenScheduleExpires(new LocalSelfSchedule())
                .expectOnlyEvents("local self schedule");
    }

    @Test
    void localPeriodicScheduleHandlerIsTriggeredByTaskSchedulerInAsyncFixture() {
        TestFixture.createAsyncJvmCompatibility(new LocalPeriodicScheduleHandler())
                .whenTimeElapses(Duration.ofMillis(1000))
                .expectEvents("local periodic schedule");
    }

    @Test
    void autoStartedLocalPeriodicScheduleIsScheduledWhenRegisteredDuringWhen() {
        TestFixture.createJvmCompatibility()
                .whenExecuting(fc -> fc.registerHandlers(new LocalPeriodicScheduleHandler()))
                .expectNoEvents()
                .expectNewSchedules(LocalPeriodicSchedule.class);
    }

    @Test
    void localPeriodicReturnValuesRescheduleThroughTaskSchedulerInAsyncFixture() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant firstDeadline = start.plusMillis(1000);

        TestFixture.createAsyncJvmCompatibility(new LocalPeriodicReturnHandler()).atFixedTime(start)
                .givenSchedules(new Schedule(new LocalPeriodicDurationReturn(), "local-duration", firstDeadline),
                                new Schedule(new LocalPeriodicInstantReturn(), "local-instant", firstDeadline),
                                new Schedule(new LocalPeriodicPayloadReturn(), "local-payload", firstDeadline),
                                new Schedule(new LocalPeriodicScheduleReturn(), "local-schedule", firstDeadline))
                .whenTimeElapses(Duration.ofMillis(1000))
                .expectOnlyNewSchedules(
                        scheduled(new LocalPeriodicDurationReturn(), firstDeadline.plusMillis(2000)),
                        scheduled(new LocalPeriodicInstantReturn(), firstDeadline.plusMillis(3000)),
                        scheduled(new LocalPeriodicPayloadReturn(1), firstDeadline.plusMillis(1000)),
                        scheduled(new LocalPeriodicScheduleReturn(1), firstDeadline.plusMillis(4000)));
    }

    @Test
    void statefulScheduleCreatedBeforeStateIsStoredIsTriggeredLocally() {
        TestFixture.createJvmCompatibility(StatefulScheduleProcess.class)
                .whenCommand(new StartStatefulScheduleProcess("process-1"))
                .expectNewSchedules(new StatefulSchedule("process-1"))
                .andThen()
                .whenTimeElapses(Duration.ofSeconds(1))
                .expectOnlyEvents("expired process-1");
    }

    @Test
    void testGetSchedule() {
        Schedule schedule = new Schedule(new YieldsCommand("bla"), "test",
                                         subject.getCurrentTime().plusSeconds(10));
        subject.givenSchedules(schedule)
                .whenApplying(fc -> fc.messageScheduler().getSchedule("test").orElse(null))
                .expectResult(schedule);
    }

    @Test
    void testScheduledCommand() {
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        subject.givenScheduledCommands(
                        new Schedule("some command", "testId", deadline).addMetadata("a", "b"))
                .whenTimeAdvancesTo(deadline)
                .expectCommands("some command");
    }

    @Test
    void testScheduledCommandFromJson() {
        Instant deadline = subject.getCurrentTime().plusSeconds(10);
        subject.givenScheduledCommands(
                        new Schedule("scheduling/command.json", "testId", deadline).addMetadata("a", "b"))
                .whenTimeAdvancesTo(deadline)
                .expectCommands("commandFromJson");
    }

    @Test
    void testScheduledCommand_async() {
        Instant deadline = Instant.now().plusSeconds(10);
        TestFixture.createAsyncJvmCompatibility(new CommandHandler())
                .givenScheduledCommands(new Schedule("some command", deadline).addMetadata("a", "b"))
                .whenTimeAdvancesTo(deadline)
                .expectCommands("some command");
    }

    @Test
    void testScheduledCommandCancellation() {
        Instant deadline = Instant.now().plusSeconds(10);
        String scheduleId = "testId";
        subject.givenScheduledCommands(new Schedule("some command", scheduleId, deadline))
                .given(fc -> Fluxzero.cancelSchedule(scheduleId))
                .whenTimeAdvancesTo(deadline).expectNoCommands();
    }

    static class CommandHandler {
        @HandleCommand
        void handle(YieldsSchedule command) {
            Fluxzero.get().messageScheduler().schedule(command.getSchedule());
        }

        @HandleCommand
        void handle(YieldsScheduledCommand command) {
            Fluxzero.scheduleCommand(command.getCommand(), command.getScheduleId(), command.getDeadline());
        }

        @HandleCommand
        void handle(String simpleCommand) {
        }
    }

    static class ScheduleHandler {
        @HandleSchedule
        void handle(YieldsCommand schedule) {
            Fluxzero.get().commandGateway().sendAndForget(schedule.getCommand());
        }

        @HandleSchedule
        Duration handle(YieldsNewSchedule schedule) {
            return Duration.ofMillis(schedule.getDelay());
        }

        @HandleSchedule
        CompletableFuture<Duration> handle(YieldsNewScheduleAsync schedule) {
            return CompletableFuture.completedFuture(Duration.ofMillis(schedule.getDelay()));
        }

        @HandleSchedule
        void handle(PeriodicSchedule schedule) {
        }

        @HandleSchedule
        void handle(NonAutomaticPeriodicSchedule schedule) {
        }

        @HandleSchedule
        IncrementalSchedule handle(IncrementalSchedule schedule) {
            return new IncrementalSchedule(schedule.count() + 1);
        }
    }

    static class BranchIdCommandHandler {
        @HandleCommand
        void handle(StartScheduleChain command) {
            Fluxzero.schedule("scheduled-root", command.scheduleId(), command.deadline());
        }

        @HandleCommand
        void handle(StartReschedulingChain command) {
            Fluxzero.schedule(new ReschedulingPayload(0), command.scheduleId(), command.firstDeadline());
        }

        @HandleCommand
        void handle(String command) {
            if ("indirect-command".equals(command)) {
                Fluxzero.publishEvent("indirect-effect");
            }
        }
    }

    static class BranchIdScheduleHandler {
        @HandleSchedule
        void handle(String payload) {
            if ("scheduled-root".equals(payload)) {
                Fluxzero.publishEvent("direct-effect");
                Fluxzero.sendAndForgetCommand("indirect-command");
            }
        }

        @HandleSchedule
        void handle(ReschedulingPayload payload, Schedule schedule) {
            Fluxzero.publishEvent(new BranchObserved(payload.iteration()));
            if (payload.iteration() == 0) {
                Fluxzero.schedule(schedule.withPayload(new ReschedulingPayload(1)).reschedule(Duration.ofSeconds(5)));
            }
        }
    }

    static class InterfacePeriodicHandler {
        @HandleSchedule
        void handle(PeriodicScheduleFromInterface schedule) {
        }
    }

    @LocalHandler
    static class LocalScheduleHandler {
        @HandleSchedule
        void handle(LocalSchedule schedule) {
            Fluxzero.publishEvent("local schedule");
        }
    }

    static class LocalSelfSchedule {
        @HandleSchedule
        void handle(LocalSelfSchedule schedule) {
            Fluxzero.publishEvent("local self schedule");
        }
    }

    @LocalHandler
    static class LocalPeriodicScheduleHandler {
        @HandleSchedule
        @Periodic(delay = 1000)
        void handle(LocalPeriodicSchedule schedule) {
            Fluxzero.publishEvent("local periodic schedule");
        }
    }

    @LocalHandler
    static class LocalPeriodicReturnHandler {
        @HandleSchedule
        @Periodic(delay = 1000, autoStart = false)
        Duration handle(LocalPeriodicDurationReturn schedule) {
            return Duration.ofMillis(2000);
        }

        @HandleSchedule
        @Periodic(delay = 1000, autoStart = false)
        Instant handle(LocalPeriodicInstantReturn schedule, Schedule message) {
            return message.getDeadline().plusMillis(3000);
        }

        @HandleSchedule
        @Periodic(delay = 1000, autoStart = false)
        LocalPeriodicPayloadReturn handle(LocalPeriodicPayloadReturn schedule) {
            return new LocalPeriodicPayloadReturn(schedule.getSequence() + 1);
        }

        @HandleSchedule
        @Periodic(delay = 1000, autoStart = false)
        Schedule handle(LocalPeriodicScheduleReturn schedule, Schedule message) {
            return message.withPayload(new LocalPeriodicScheduleReturn(schedule.getSequence() + 1))
                    .reschedule(Duration.ofMillis(4000));
        }
    }

    static class MethodPeriodicHandler {
        @HandleSchedule
        @Periodic(delay = 1000)
        void handle(MethodPeriodicSchedule schedule) {
        }
    }

    static class AlteredPayloadPeriodicHandler {
        @HandleSchedule
        @Periodic(delay = 1000)
        YieldsAlteredSchedule handle(YieldsAlteredSchedule schedule) {
            return new YieldsAlteredSchedule(schedule.getSequence() + 1);
        }
    }

    static class CancellingPeriodicHandler {
        @HandleSchedule
        @Periodic(delay = 1000)
        YieldsAlteredSchedule handle(YieldsAlteredSchedule schedule) {
            throw new CancelPeriodic();
        }
    }

    @LocalHandler
    static class ImplicitFixedDelayHandler {
        private final String event;

        ImplicitFixedDelayHandler(String event) {
            this.event = event;
        }

        @HandleSchedule
        @Periodic(delay = 60, timeUnit = TimeUnit.SECONDS)
        void handle(ImplicitFixedDelaySchedule schedule) {
            Fluxzero.publishEvent(event);
        }
    }

    @LocalHandler
    static class ImplicitCronHandler {
        @HandleSchedule
        @Periodic(cron = "*/5 * * * *")
        void handle(ImplicitCronSchedule schedule, Schedule message) {
            Fluxzero.publishEvent(message.getDeadline());
        }
    }

    @LocalHandler
    static class InitialDelayZeroHandler {
        private final String event;

        InitialDelayZeroHandler(String event) {
            this.event = event;
        }

        @HandleSchedule
        @Periodic(delay = 60, initialDelay = 0, timeUnit = TimeUnit.SECONDS)
        void handle(InitialDelayZeroSchedule schedule) {
            Fluxzero.publishEvent(event);
        }
    }

    @LocalHandler
    static class PositiveInitialDelayHandler {
        private final String event;

        PositiveInitialDelayHandler(String event) {
            this.event = event;
        }

        @HandleSchedule
        @Periodic(delay = 60, initialDelay = 5, timeUnit = TimeUnit.SECONDS)
        void handle(PositiveInitialDelaySchedule schedule) {
            Fluxzero.publishEvent(event);
        }
    }

    @LocalHandler
    static class DisabledCronHandler {
        @HandleSchedule
        @Periodic(cron = Periodic.DISABLED)
        void handle(DisabledCronSchedule schedule) {
            Fluxzero.publishEvent("disabled cron");
        }
    }

    @LocalHandler
    static class MissingPropertyDisabledCronHandler {
        @HandleSchedule
        @Periodic(cron = "${missingCronSchedule:-}")
        void handle(MissingPropertyDisabledCronSchedule schedule) {
            Fluxzero.publishEvent("missing property cron");
        }
    }

    static class AlteredPayloadNonPeriodicHandler {
        @HandleSchedule
        YieldsAlteredSchedule handle(YieldsAlteredSchedule payload) {
            return new YieldsAlteredSchedule(payload.getSequence() + 1);
        }
    }

    static class AlteredPayloadNonPeriodicHandlerReturningSchedule {
        @HandleSchedule
        Schedule handle(YieldsAlteredSchedule payload, Schedule schedule) {
            return schedule.withPayload(new YieldsAlteredSchedule(payload.getSequence() + 1))
                    .reschedule(Duration.ofSeconds(1));
        }
    }

    @AllArgsConstructor
    @Value
    class YieldsSchedule {
        Schedule schedule;

        public YieldsSchedule() {
            this(new Schedule("schedule", UUID.randomUUID().toString(),
                              subject.getCurrentTime().plusSeconds(10)));
        }
    }

    @Value
    static class YieldsScheduledCommand {
        Object command;
        String scheduleId;
        Instant deadline;
    }

    @Value
    static class YieldsCommand {
        Object command;
    }

    record StartScheduleChain(String scheduleId, Instant deadline) {
    }

    record StartReschedulingChain(String scheduleId, Instant firstDeadline) {
    }

    record ReschedulingPayload(int iteration) {
    }

    record BranchObserved(int iteration) {
    }

    record StartStatefulScheduleProcess(String processId) {
    }

    record StatefulSchedule(String processId) {
    }

    @Stateful
    record StatefulScheduleProcess(@EntityId @Association String processId) {
        @HandleCommand
        static StatefulScheduleProcess start(StartStatefulScheduleProcess command) {
            Fluxzero.schedule(new StatefulSchedule(command.processId()),
                              "stateful-schedule-" + command.processId(),
                              Duration.ofSeconds(1));
            return new StatefulScheduleProcess(command.processId());
        }

        @HandleSchedule
        void expire(StatefulSchedule schedule) {
            Fluxzero.publishEvent("expired " + schedule.processId());
        }
    }

    @Value
    static class YieldsNewSchedule {
        long delay;
    }

    @Value
    static class YieldsNewScheduleAsync {
        long delay;
    }

    @Value
    @AllArgsConstructor
    static class YieldsAlteredSchedule {
        int sequence;

        public YieldsAlteredSchedule() {
            this(0);
        }
    }

    @Value
    @Periodic(delay = 1, timeUnit = TimeUnit.SECONDS)
    static class PeriodicSchedule {
    }

    record IncrementalSchedule(int count) {}

    @Value
    static class CronSchedule {
    }

    @Value
    static class MethodPeriodicSchedule {
    }

    static class ImplicitFixedDelaySchedule {
        public ImplicitFixedDelaySchedule() {
        }
    }

    static class ImplicitCronSchedule {
        public ImplicitCronSchedule() {
        }
    }

    static class InitialDelayZeroSchedule {
        public InitialDelayZeroSchedule() {
        }
    }

    static class PositiveInitialDelaySchedule {
        public PositiveInitialDelaySchedule() {
        }
    }

    static class DisabledCronSchedule {
        public DisabledCronSchedule() {
        }
    }

    static class MissingPropertyDisabledCronSchedule {
        public MissingPropertyDisabledCronSchedule() {
        }
    }

    @Value
    static class LocalSchedule {
    }

    @Value
    static class LocalPeriodicSchedule {
    }

    @Value
    static class LocalPeriodicDurationReturn {
    }

    @Value
    static class LocalPeriodicInstantReturn {
    }

    @Value
    static class LocalPeriodicPayloadReturn {
        int sequence;

        public LocalPeriodicPayloadReturn() {
            this(0);
        }

        public LocalPeriodicPayloadReturn(int sequence) {
            this.sequence = sequence;
        }
    }

    @Value
    static class LocalPeriodicScheduleReturn {
        int sequence;

        public LocalPeriodicScheduleReturn() {
            this(0);
        }

        public LocalPeriodicScheduleReturn(int sequence) {
            this.sequence = sequence;
        }
    }

    private static Predicate<Schedule> scheduled(Object payload, Instant deadline) {
        return schedule -> payload.equals(schedule.getPayload()) && deadline.equals(schedule.getDeadline());
    }

    @Value
    @Periodic(delay = 1000, autoStart = false)
    static class NonAutomaticPeriodicSchedule {
    }

    @Value
    static class PeriodicScheduleFromInterface implements PeriodicInterface {
    }

    @Periodic(delay = 1000)
    interface PeriodicInterface {
    }

    @Value
    @Periodic(cron = atStartOfDay, timeZone = "Europe/Amsterdam")
    static class PeriodicCronSchedule {
    }

}
