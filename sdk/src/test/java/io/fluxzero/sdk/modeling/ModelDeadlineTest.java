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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelDeadlineUpdate;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.scheduling.Deadline;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.scheduling.ScheduledCommand;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.User;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ModelDeadlineTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeChangesDoNotReviveConsumedDeadlines(boolean async) {
        var fixture = async ? TestFixture.createAsync(ExplicitAlarm.class, new Alarms())
                : TestFixture.create(ExplicitAlarm.class, new Alarms());
        fixture.atFixedTime(START)
                .givenCommands(new SetAlarm("alarm", DUE, "first"))
                .givenTimeAdvancedTo(DUE)
                .whenCommand(new SetAlarm("alarm", DUE.plusSeconds(60), "first"))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
                .andThen().whenCommand(new SetAlarm("alarm", DUE.minusSeconds(60), "first"))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
                .andThen().whenTimeAdvancesTo(DUE.plusSeconds(120))
                .expectNoEvents().expectNoErrors().expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTimeChangeDuringHandlingDoesNotCreateAnotherDeadline(boolean async) {
        var fixture = async ? TestFixture.createAsync(ExplicitAlarm.class, new MovingAlarms())
                : TestFixture.create(ExplicitAlarm.class, new MovingAlarms());
        fixture.atFixedTime(START)
                .givenCommands(new SetAlarm("alarm", DUE, "first"))
                .whenTimeAdvancesTo(DUE.plusSeconds(120))
                .expectNoErrors().expectOnlyActiveScheduledCommands()
                .expectOnlyEvents(new SetAlarm("alarm", DUE.plusSeconds(60), "first"), new Alarm("first"));
    }

    static class MovingAlarms {
        @HandleCommand
        void handle(Alarm alarm) {
            Fluxzero.assertAndApply(new SetAlarm("alarm", DUE.plusSeconds(60), alarm.payload()));
            Fluxzero.publishEvent(alarm);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedFutureDeadlineDoesNotReadOrPreserveExternalCancellation(boolean async) {
        var fixture = async ? TestFixture.createAsync(ExplicitAlarm.class, new Alarms())
                : TestFixture.create(ExplicitAlarm.class, new Alarms());
        fixture.atFixedTime(START)
                .givenCommands(new SetAlarm("alarm", DUE, "first"))
                .given(fc -> Fluxzero.cancelSchedule(ModelDeadlineUpdate.scheduleId("alarm", "default")))
                .whenCommand(new SetAlarm("alarm", DUE.plusSeconds(60), "first"))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands(new Alarm("first"))
                .andThen().whenCommand(new SetAlarm("alarm", DUE.plusSeconds(60), "second"))
                .expectSuccessfulResult().expectNoErrors()
                .expectOnlyActiveScheduledCommands(new Alarm("second"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ancestorTimingChangesDoNotReviveConsumedDeadlines(boolean async) {
        var fixture = async ? TestFixture.createAsync(Reservation.class, Policy.class, new PlainCommands())
                : TestFixture.create(Reservation.class, Policy.class, new PlainCommands());
        fixture.atFixedTime(START)
                .givenCommands(new SetPolicy("policy", DUE), new Reserve("reservation", "policy"))
                .givenTimeAdvancedTo(DUE)
                .whenCommand(new SetPolicy("policy", DUE.plusSeconds(60)))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
                .andThen().whenTimeAdvancesTo(DUE.plusSeconds(120))
                .expectNoEvents().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cronConfigurationChangesDoNotReviveConsumedDeadlines(boolean async) {
        var fixture = async ? TestFixture.createAsync(Timed.class, new Alarms())
                : TestFixture.create(Timed.class, new Alarms());
        fixture.withProperty("deadline.cron", "0 * * * *").atFixedTime(START)
                .givenCommands(new PutTimed("timed", "first", 0))
                .givenTimeAdvancedTo(DUE)
                .given(fc -> fixture.withProperty("deadline.cron", "30 * * * *"))
                .whenCommand(new PutTimed("timed", "first", 1))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
                .andThen().whenTimeAdvancesTo(DUE.plusSeconds(7200))
                .expectNoEvents().expectNoErrors();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changedCronConfigAndUnrelatedApplyPreserveFuturePlan(boolean async) {
        var fixture = async ? TestFixture.createAsync(Timed.class, new Alarms()) : TestFixture.create(Timed.class, new Alarms());
        fixture.withProperty("deadline.cron", "0 * * * *").atFixedTime(START)
                .givenCommands(new PutTimed("timed", "first", 0))
                .given(fc -> fixture.withProperty("deadline.cron", "30 * * * *"))
                .whenCommand(new PutTimed("timed", "first", 1))
                .expectSuccessfulResult().expectNoErrors().expectNoNewSchedules()
                .expectThat(fc -> assertEquals(DUE, Fluxzero.loadGraph("timed", Timed.class).deadlines().get("default").deadline()))
                .andThen().whenCommand(new PutTimed("timed", "changed", 1))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(START.plusSeconds(1800),
                        Fluxzero.loadGraph("timed", Timed.class).deadlines().get("default").deadline()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void siblingGraphChangesDoNotEvaluatePlainAncestorDeclarations(boolean async) {
        var fixture = async ? TestFixture.createAsync(Group.class, Entry.class, Scoped.class)
                : TestFixture.create(Group.class, Entry.class, Scoped.class);
        fixture.atFixedTime(START).givenCommands(new CreateGroup("group"), new PutEntry("entry", "group", 1),
                        new PutScoped("owner", "group"))
                .given(fc -> { Scoped.plainCalls.set(0); Scoped.graphCalls.set(0); })
                .whenCommand(new PutEntry("entry", "group", 2))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> { assertEquals(0, Scoped.plainCalls.get()); assertEquals(2, Scoped.graphCalls.get());
                    assertEquals(DUE.plusSeconds(2), Fluxzero.loadGraph("owner", Scoped.class).deadlines().get("graph").deadline()); });
    }

    @Model(searchable = false)
    record Scoped(@EntityId String scopedId, @Parent(Group.class) String groupId) {
        static final java.util.concurrent.atomic.AtomicInteger plainCalls = new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.atomic.AtomicInteger graphCalls = new java.util.concurrent.atomic.AtomicInteger();
        @Deadline("plain") Schedule plain(Group group) { plainCalls.incrementAndGet(); return new Schedule("plain", DUE); }
        @Deadline("graph") Schedule graph(Graph<Group> group) { graphCalls.incrementAndGet();
            return new Schedule("graph", DUE.plusSeconds(group.childModels(Entry.class).stream().mapToInt(Entry::amount).sum())); }
    }
    record PutScoped(String scopedId, String groupId) {
        @Apply Scoped apply(@jakarta.annotation.Nullable Scoped current) { return new Scoped(scopedId, groupId); }
    }

    @Model(searchable = false)
    record ExplicitAlarm(@EntityId String alarmId, Instant deadline, String payload) {
        @Deadline
        Schedule alarm() {
            return deadline == null ? null : new Schedule(new Alarm(payload), deadline);
        }
    }

    record SetAlarm(String alarmId, Instant deadline, String payload) {
        @Apply
        ExplicitAlarm apply(@jakarta.annotation.Nullable ExplicitAlarm current) {
            return new ExplicitAlarm(alarmId, deadline, payload);
        }
    }

    static class PlainCommands {
        @HandleCommand
        void handle(String message) {
            Fluxzero.publishEvent(message);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void anIgnoredTimeChangeDoesNotReserveAPublicIdAlreadyReusedByAnotherCategory(
            boolean async, boolean consumedFirst) {
        var fixture = async ? TestFixture.createAsync(SharedAlarm.class, new LocalAlarms())
                : TestFixture.create(SharedAlarm.class, new LocalAlarms());
        fixture.atFixedTime(START)
                .givenCommands(new SetSharedAlarm("alarm", consumedFirst, DUE, null))
                .givenTimeAdvancedTo(DUE)
                .givenCommands(new SetSharedAlarm("alarm", consumedFirst, DUE, "second"))
                .whenCommand(new SetSharedAlarm("alarm", consumedFirst, DUE.plusSeconds(30), "changed"))
                .expectSuccessfulResult().expectNoErrors()
                .expectOnlySchedules(new Alarm("changed"))
                .andThen().whenTimeAdvancesTo(DUE.plusSeconds(120))
                .expectOnlyEvents(new Alarm("changed")).expectNoErrors();
    }

    @Model(searchable = false)
    record SharedAlarm(@EntityId String alarmId, boolean consumedFirst, Instant firstDeadline, String secondPayload) {
        @Deadline(value = "first", command = false)
        Schedule first() {
            return consumedFirst ? consumed() : active();
        }

        @Deadline(value = "second", command = false)
        Schedule second() {
            return consumedFirst ? active() : consumed();
        }

        private Schedule consumed() {
            return new Schedule(new Alarm("first"), "shared", firstDeadline);
        }

        private Schedule active() {
            return secondPayload == null ? null
                    : new Schedule(new Alarm(secondPayload), "shared", DUE.plusSeconds(120));
        }
    }

    record SetSharedAlarm(String alarmId, boolean consumedFirst, Instant firstDeadline, String secondPayload) {
        @Apply
        SharedAlarm apply(@jakarta.annotation.Nullable SharedAlarm current) {
            return new SharedAlarm(alarmId, consumedFirst, firstDeadline, secondPayload);
        }
    }

    static class LocalAlarms {
        @io.fluxzero.sdk.tracking.handling.LocalHandler
        @HandleSchedule
        void handle(Alarm alarm) {
            Fluxzero.publishEvent(alarm);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitIdsCanSwapCategoriesAtomically(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(TwoDeadlines.class)
                        : TestFixture.create(TwoDeadlines.class);
        fixture.atFixedTime(START)
                .givenCommands(new PutTwoDeadlines("two", false))
                .whenCommand(new PutTwoDeadlines("two", true))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>)
                                schedule ->
                                        schedule.getScheduleId().equals("a")
                                                && schedule.getPayload().equals("second"),
                        (Predicate<Schedule>)
                                schedule ->
                                        schedule.getScheduleId().equals("b")
                                                && schedule.getPayload().equals("first"));
    }

    @Model(searchable = false)
    record TwoDeadlines(@EntityId String twoId, boolean swapped) {
        @Deadline("first")
        Schedule first() {
            return new Schedule("first", swapped ? "b" : "a", DUE);
        }

        @Deadline("second")
        Schedule second() {
            return new Schedule("second", swapped ? "a" : "b", DUE);
        }
    }

    record PutTwoDeadlines(String twoId, boolean swapped) {
        @Apply
        TwoDeadlines apply(@jakarta.annotation.Nullable TwoDeadlines current) {
            return new TwoDeadlines(twoId, swapped);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredAuthenticationCannotFallBackToTheCallerWhenSystemUserIsMissing(boolean async) {
        var builder = DefaultFluxzero.builder().registerUserProvider(new AbstractUserProvider(DeadlineUser.class) {
            @Override public User getUserById(Object id) { return new DeadlineUser(id.toString()); }
            @Override public User getSystemUser() { return null; }
        });
        var fixture = async ? TestFixture.createAsync(builder, Hold.class) : TestFixture.create(builder, Hold.class);
        fixture.atFixedTime(START).whenCommandByUser(new DeadlineUser("caller"), new Create(id, DUE))
                .expectExceptionalResult().expectOnlyActiveScheduledCommands()
                .expectThat(fc -> assertNull(Fluxzero.loadModel(id).get()));
    }

    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DUE = START.plusSeconds(3600);
    final HoldId id = new HoldId("hold");

    TestFixture fixture(boolean async) {
        return (async ? TestFixture.createAsync(Hold.class) : TestFixture.create(Hold.class))
                .atFixedTime(START);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsAndReplacesExactlyOneCommand(boolean async) {
        Instant next = DUE.plusSeconds(60);
        fixture(async)
                .givenCommands(new Create(id, DUE))
                .whenCommand(new Renew(id, next))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>)
                                s ->
                                        s.getScheduleId()
                                                        .equals(
                                                                ModelDeadlineUpdate.scheduleId(
                                                                        id.toString(), "default"))
                                                && s.getDeadline().equals(next)
                                                && s.getPayload().equals(new Expire(id)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedUpdateRetainsScheduledMessage(boolean async) {
        AtomicReference<String> generation = new AtomicReference<>();
        fixture(async)
                .givenCommands(new Create(id, DUE))
                .given(
                        fc ->
                                generation.set(
                                        fc.messageScheduler()
                                                .getSchedule(
                                                        ModelDeadlineUpdate.scheduleId(
                                                                id.toString(), "default"))
                                                .orElseThrow()
                                                .getMessageId()))
                .whenCommand(new Rename(id, "new label"))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectNoNewSchedules()
                .expectThat(
                        fc ->
                                assertEquals(
                                        generation.get(),
                                        fc.messageScheduler()
                                                .getSchedule(
                                                        ModelDeadlineUpdate.scheduleId(
                                                                id.toString(), "default"))
                                                .orElseThrow()
                                                .getMessageId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionCancelsWithoutPayloadParent(boolean async) {
        fixture(async)
                .givenCommands(new Create(id, DUE))
                .whenCommand(new Delete(id))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firesThroughExistingCommandScheduler(boolean async) {
        fixture(async)
                .givenCommands(new Create(id, DUE))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectThat(fc -> assertTrue(Fluxzero.loadModel(id).get().expired()))
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recalculatesOnInjectedAncestorChange(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Reservation.class, Policy.class)
                        : TestFixture.create(Reservation.class, Policy.class);
        fixture.atFixedTime(START)
                .givenCommands(new SetPolicy("policy", DUE), new Reserve("reservation", "policy"))
                .whenCommand(new SetPolicy("policy", DUE.plusSeconds(60)))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>)
                                schedule -> schedule.getDeadline().equals(DUE.plusSeconds(60)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recalculatesWhenMovedBetweenAncestors(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Reservation.class, Policy.class)
                        : TestFixture.create(Reservation.class, Policy.class);
        fixture.atFixedTime(START)
                .givenCommands(
                        new SetPolicy("first", DUE),
                        new SetPolicy("second", DUE.plusSeconds(60)),
                        new Reserve("reservation", "first"))
                .whenCommand(new Reserve("reservation", "second"))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>)
                                schedule -> schedule.getDeadline().equals(DUE.plusSeconds(60)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void graphDependencyRecalculatesOnSiblingChange(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Group.class, Entry.class)
                        : TestFixture.create(Group.class, Entry.class);
        fixture.atFixedTime(START)
                .givenCommands(
                        new CreateGroup("group"),
                        new PutEntry("one", "group", 1),
                        new PutEntry("two", "group", 2))
                .whenCommand(new PutEntry("two", "group", 3))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>)
                                schedule -> schedule.getDeadline().equals(DUE.plusSeconds(4)),
                        (Predicate<Schedule>)
                                schedule -> schedule.getDeadline().equals(DUE.plusSeconds(4)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationCanBeDisabledAndPayloadCanBeAnOrdinarySchedule(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Reminder.class, new Reminders())
                        : TestFixture.create(Reminder.class, new Reminders());
        fixture.atFixedTime(START)
                .givenCommands(new CreateReminder("reminder"), new DeleteReminder("reminder"))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectEvents("reminded")
                .expectNoSchedules();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitScheduleIdentityIsComparedAndOldIdentityIsCancelled(boolean async) {
        var fixture =
                async ? TestFixture.createAsync(Custom.class) : TestFixture.create(Custom.class);
        fixture.atFixedTime(START)
                .givenCommands(new SetCustom("custom", "first", "payload"))
                .whenCommand(new SetCustom("custom", "second", "payload"))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>) schedule -> "second".equals(schedule.getScheduleId()))
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("first").isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalCancellationStaysCancelledWhenDesiredScheduleDoesNotChange(boolean async) {
        var fixture =
                async ? TestFixture.createAsync(Custom.class) : TestFixture.create(Custom.class);
        fixture.atFixedTime(START)
                .givenCommands(new SetCustom("custom", "public-id", "payload"))
                .given(fc -> Fluxzero.cancelSchedule("public-id"))
                .whenCommand(new SetCustom("custom", "public-id", "payload"))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectNoNewSchedules()
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cronResolvesPropertiesAndFiresOnlyOnce(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Timed.class, new Alarms())
                        : TestFixture.create(Timed.class, new Alarms());
        fixture.withProperty("deadline.cron", "0 * * * *")
                .atFixedTime(START)
                .givenCommands(new PutTimed("timed", "first", 0))
                .whenTimeAdvancesTo(DUE.plusSeconds(7200))
                .expectNoErrors()
                .expectOnlyEvents(new Alarm("first"))
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consumedCronIsNotRecreatedByAnUnrelatedChange(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Timed.class, new Alarms())
                        : TestFixture.create(Timed.class, new Alarms());
        fixture.withProperty("deadline.cron", "0 * * * *")
                .atFixedTime(START)
                .givenCommands(new PutTimed("timed", "first", 0))
                .givenTimeAdvancedTo(DUE)
                .whenCommand(new PutTimed("timed", "first", 1))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectNoNewSchedules()
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedPayloadDoesNotRestartAnElapsedCronDeadline(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Timed.class, new Alarms())
                        : TestFixture.create(Timed.class, new Alarms());
        fixture.withProperty("deadline.cron", "0 * * * *")
                .atFixedTime(START)
                .givenCommands(new PutTimed("timed", "first", 0))
                .givenTimeAdvancedTo(DUE)
                .whenCommand(new PutTimed("timed", "second", 0))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cronCanBeDisabledThroughProperties(boolean async) {
        var fixture =
                async ? TestFixture.createAsync(Timed.class) : TestFixture.create(Timed.class);
        fixture.withProperty("deadline.cron", Deadline.DISABLED)
                .atFixedTime(START)
                .whenCommand(new PutTimed("timed", "first", 0))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectOnlyActiveScheduledCommands();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delayDoesNotSlideOnUnrelatedWrites(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Delayed.class, new Alarms())
                        : TestFixture.create(Delayed.class, new Alarms());
        fixture.atFixedTime(START)
                .givenCommands(new PutDelayed("delayed", "first", 0))
                .givenTimeAdvancedTo(START.plusSeconds(60))
                .whenCommand(new PutDelayed("delayed", "first", 1))
                .expectSuccessfulResult()
                .expectNoErrors()
                .expectNoNewSchedules()
                .expectOnlyActiveScheduledCommands(
                        (Predicate<Schedule>) s -> s.getDeadline().equals(DUE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawScheduleCanValidateBeforeItsAtomicModelWrite(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Checked.class, Hold.class, new Checks())
                        : TestFixture.create(Checked.class, Hold.class, new Checks());
        fixture.atFixedTime(START)
                .givenCommands(
                        new Create(id, DUE.plusSeconds(3600)), new PutChecked("check", id, true))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectThat(fc -> assertTrue(Fluxzero.loadModel(id).get().expired()))
                .expectOnlySchedules();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validationOnlyScheduleUsesOrdinaryOneShotDelivery(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(Checked.class, Hold.class, new Checks())
                        : TestFixture.create(Checked.class, Hold.class, new Checks());
        fixture.atFixedTime(START)
                .givenCommands(
                        new Create(id, DUE.plusSeconds(3600)), new PutChecked("check", id, false))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectThat(
                        fc -> {
                            assertEquals(DUE, Fluxzero.loadGraph("check", Checked.class).deadlines().get("default").deadline());
                            assertFalse(Fluxzero.loadModel(id).get().expired());
                        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commandRunsAsTheSystemUserRatherThanTheModelWriter(boolean async) {
        var system = new DeadlineUser("system");
        var caller = new DeadlineUser("caller");
        var builder =
                DefaultFluxzero.builder()
                        .registerUserProvider(
                                new AbstractUserProvider(DeadlineUser.class) {
                                    @Override
                                    public User getUserById(Object id) {
                                        return new DeadlineUser(id.toString());
                                    }

                                    @Override
                                    public User getSystemUser() {
                                        return system;
                                    }
                                });
        var fixture =
                async
                        ? TestFixture.createAsync(builder, Timed.class, new UserAlarms())
                        : TestFixture.create(builder, Timed.class, new UserAlarms());
        fixture.withProperty("deadline.cron", "0 * * * *")
                .atFixedTime(START)
                .givenCommandsByUser(caller, new PutTimed("timed", "first", 0))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectOnlyEvents("system");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asynchronousRawHandlerCanCommitModelChanges(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(
                                AsyncReminder.class, Hold.class, new AsyncChecks())
                        : TestFixture.create(AsyncReminder.class, Hold.class, new AsyncChecks());
        fixture.atFixedTime(START)
                .givenCommands(
                        new Create(id, DUE.plusSeconds(3600)),
                        new PutAsyncReminder("async", id, false, true))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectOnlySchedules()
                .expectThat(fc -> assertTrue(Fluxzero.loadModel(id).get().expired()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asynchronousRawHandlerWithoutAModelWriteRemainsOneShot(boolean async) {
        var fixture =
                async
                        ? TestFixture.createAsync(AsyncReminder.class, new AsyncChecks())
                        : TestFixture.create(AsyncReminder.class, new AsyncChecks());
        fixture.atFixedTime(START)
                .givenCommands(new PutAsyncReminder("async", id, false, false))
                .whenTimeAdvancesTo(DUE)
                .expectNoErrors()
                .expectOnlyEvents("async completed")
                .expectOnlySchedules();
    }

    @Model(searchable = false)
    record AsyncReminder(
            @EntityId String asyncReminderId, HoldId holdId, boolean fail, boolean mutate) {
        @Deadline(command = false)
        Schedule deadline() {
            return new Schedule(new AsyncCheck(holdId, fail, mutate), DUE);
        }
    }

    record PutAsyncReminder(String asyncReminderId, HoldId holdId, boolean fail, boolean mutate) {
        @Apply
        AsyncReminder apply() {
            return new AsyncReminder(asyncReminderId, holdId, fail, mutate);
        }
    }

    record AsyncCheck(HoldId holdId, boolean fail, boolean mutate) {}

    static class AsyncChecks {
        @HandleSchedule
        CompletionStage<Void> handle(AsyncCheck check) {
            var context = DeserializingMessage.getCurrent().captureContext();
            return io.fluxzero.sdk.common.AsyncCompletionScope.register(CompletableFuture.runAsync(
                    context.wrap(
                            () -> {
                                if (check.fail()) {
                                    throw new IllegalStateException("async failed");
                                }
                                if (check.mutate()) {
                                    Fluxzero.assertAndApply(new Expire(check.holdId()));
                                } else {
                                    Fluxzero.publishEvent("async completed");
                                }
                            })));
        }
    }

    public record DeadlineUser(String id) implements User {
        @Override
        public boolean hasRole(String role) {
            return false;
        }
    }

    static class UserAlarms {
        @HandleCommand
        void handle(Alarm alarm, DeadlineUser user) {
            Fluxzero.publishEvent(user.id());
        }
    }

    @Model(searchable = false)
    record Checked(@EntityId String checkedId, HoldId holdId, boolean mutate) {
        @Deadline(command = false)
        Schedule check() {
            return new Schedule(new Check(holdId, mutate), DUE);
        }
    }

    record PutChecked(String checkedId, HoldId holdId, boolean mutate) {
        @Apply
        Checked apply() {
            return new Checked(checkedId, holdId, mutate);
        }
    }

    record Check(HoldId holdId, boolean mutate) {}

    static class Checks {
        @HandleSchedule
        void handle(Check check) {
            Fluxzero.assertLegal(new Expire(check.holdId()));
            if (check.mutate()) {
                Fluxzero.assertAndApply(new Expire(check.holdId()));
            }
        }
    }

    @Model(searchable = false)
    record Timed(@EntityId String timedId, String payload, int unrelated) {
        @Deadline(cron = "${deadline.cron}")
        Alarm alarm() {
            return new Alarm(payload);
        }
    }

    record PutTimed(String timedId, String payload, int unrelated) {
        @Apply
        Timed apply(@jakarta.annotation.Nullable Timed previous) {
            return new Timed(timedId, payload, unrelated);
        }
    }

    @Model(searchable = false)
    record Delayed(@EntityId String delayedId, String payload, int unrelated) {
        @Deadline(delay = 1, timeUnit = TimeUnit.HOURS)
        Alarm alarm() {
            return new Alarm(payload);
        }
    }

    record PutDelayed(String delayedId, String payload, int unrelated) {
        @Apply
        Delayed apply(@jakarta.annotation.Nullable Delayed previous) {
            return new Delayed(delayedId, payload, unrelated);
        }
    }

    record Alarm(String payload) {}

    static class Alarms {
        @HandleCommand
        void handle(Alarm alarm) {
            Fluxzero.publishEvent(alarm);
        }
    }

    @Model(searchable = false)
    record Custom(@EntityId String customId, String scheduleId, String payload) {
        @Deadline
        Schedule reminder() {
            return new Schedule(payload, scheduleId, DUE);
        }
    }

    record SetCustom(String customId, String scheduleId, String payload) {
        @Apply
        Custom apply(@jakarta.annotation.Nullable Custom previous) {
            return new Custom(customId, scheduleId, payload);
        }
    }

    @Model(searchable = false)
    record Policy(@EntityId String policyId, Instant deadline) {}

    record SetPolicy(String policyId, Instant deadline) {
        @Apply
        Policy apply(@jakarta.annotation.Nullable Policy previous) {
            return new Policy(policyId, deadline);
        }
    }

    @Model(searchable = false)
    record Reservation(
            @EntityId String reservationId, @Parent(value = Policy.class) String policyId) {
        @Deadline
        Schedule expiry(Policy policy) {
            return policy == null ? null : new Schedule("expired", policy.deadline());
        }
    }

    record Reserve(String reservationId, String policyId) {
        @Apply
        Reservation apply(@jakarta.annotation.Nullable Reservation previous) {
            return new Reservation(reservationId, policyId);
        }
    }

    @Model(searchable = false)
    record Group(@EntityId String groupId) {}

    record CreateGroup(String groupId) {
        @Apply
        Group apply() {
            return new Group(groupId);
        }
    }

    @Model(searchable = false)
    record Entry(
            @EntityId String entryId, @Parent(value = Group.class) String groupId, int amount) {
        @Deadline
        Schedule expiry(Graph<Group> group) {
            return new Schedule(
                    "entry expired",
                    DUE.plusSeconds(
                            group.childModels(Entry.class).stream().mapToInt(Entry::amount).sum()));
        }
    }

    record PutEntry(String entryId, String groupId, int amount) {
        @Apply
        Entry apply(@jakarta.annotation.Nullable Entry previous) {
            return new Entry(entryId, groupId, amount);
        }
    }

    @Model(searchable = false)
    record Reminder(@EntityId String reminderId) {
        @Deadline(command = false, cancelOnDeletion = false)
        Schedule reminder() {
            return new Schedule("remind", DUE);
        }
    }

    record CreateReminder(String reminderId) {
        @Apply
        Reminder apply() {
            return new Reminder(reminderId);
        }
    }

    record DeleteReminder(String reminderId) {
        @Apply
        Reminder apply(Reminder reminder) {
            return null;
        }
    }

    static class Reminders {
        @HandleSchedule
        void handle(String message) {
            Fluxzero.publishEvent("reminded");
        }
    }

    @Model(searchable = false)
    record Hold(@EntityId HoldId id, Instant deadline, String label, boolean expired) {
        @Deadline
        Schedule expiry() {
            return expired
                    ? null
                    : new Schedule(new Expire(id), deadline)
                            .withTimestamp(Instant.now())
                            .addMetadata("$traceId", UUID.randomUUID());
        }
    }

    static class HoldId extends Id<Hold> {
        HoldId(String value) {
            super(value);
        }
    }

    record Create(HoldId id, Instant deadline) {
        @Apply
        Hold apply() {
            return new Hold(id, deadline, "hold", false);
        }
    }

    record Renew(HoldId id, Instant deadline) {
        @Apply
        Hold apply(Hold h) {
            return new Hold(id, deadline, h.label(), false);
        }
    }

    record Rename(HoldId id, String label) {
        @Apply
        Hold apply(Hold h) {
            return new Hold(id, h.deadline(), label, h.expired());
        }
    }

    record Delete(HoldId id) {
        @Apply
        Hold apply(Hold h) {
            return null;
        }
    }

    record Expire(HoldId id) {
        @Apply
        Hold apply(Hold h) {
            return new Hold(id, h.deadline(), h.label(), true);
        }
    }
}
