/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.scheduling.ScheduleId;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ThrowingErrorHandler;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import jakarta.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ModelSchedulingDocumentationTest {
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DEADLINE = START.plusSeconds(3600);
    final ProjectId projectId = new ProjectId("schedule-project");
    final ReminderId reminderId = new ReminderId("reminder");
    final ReminderSchedules schedules = new ReminderSchedules();
    final List<Graph<Reminder>> changes = new CopyOnWriteArrayList<>();

    TestFixture fixture(boolean async) {
        Object observation = new Object() {
            @HandleEvent void changed(Graph<Reminder> graph) { changes.add(graph); }
        };
        return (async ? TestFixture.createAsync(schedules, observation) : TestFixture.create(schedules, observation))
                .atFixedTime(START).givenCommands(new CreateProject(projectId));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void creationAndDeadlineReplacementConverge(boolean async) {
        Instant later = DEADLINE.plusSeconds(1800);
        fixture(async).givenCommands(new PlanReminder(reminderId, projectId, DEADLINE))
                .whenCommand(new RescheduleReminder(reminderId, later))
                .expectSuccessfulResult().expectNoErrors()
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        schedule.getScheduleId().equals(ReminderSchedules.scheduleId(reminderId).toString())
                        && schedule.getDeadline().equals(later))
                .expectThat(fc -> assertEquals(later, fc.messageScheduler()
                        .getSchedule(ReminderSchedules.scheduleId(reminderId)).orElseThrow().getDeadline()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oldGraphReconciliationCannotRestoreAnObsoleteDeadline(boolean async) {
        Instant later = DEADLINE.plusSeconds(1800);
        fixture(async).givenCommands(new PlanReminder(reminderId, projectId, DEADLINE),
                                     new RescheduleReminder(reminderId, later))
                .whenExecuting(fc -> {
                    fc.cache().clear();
                    assertEquals(DEADLINE, changes.getFirst().get().deadline());
                    schedules.changed(changes.getFirst());
                }).expectNoErrors()
                .expectThat(fc -> assertEquals(later, fc.messageScheduler()
                        .getSchedule(ReminderSchedules.scheduleId(reminderId)).orElseThrow().getDeadline()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cascadeRemovesSchedulesWithoutAParentSpecificHandler(boolean async) {
        fixture(async).givenCommands(new PlanReminder(reminderId, projectId, DEADLINE))
                .whenCommand(new DeleteProject(projectId))
                .expectSuccessfulResult().expectNoErrors().expectNoSchedules()
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule(ReminderSchedules.scheduleId(reminderId)).isEmpty()))
                .andThen().whenExecuting(fc -> schedules.changed(changes.getFirst()))
                .expectNoSchedules().expectNoErrors();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void dueCommandCompletesOnceAndOldGraphCannotRecreateIt(boolean async) {
        fixture(async).givenCommands(new PlanReminder(reminderId, projectId, DEADLINE))
                .whenTimeAdvancesTo(DEADLINE)
                .expectNoErrors().expectNoSchedules()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertTrue(Fluxzero.loadModel(reminderId).get().completed());
                }).andThen().whenExecuting(fc -> schedules.changed(changes.getFirst()))
                .expectNoErrors().expectNoSchedules();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void earlyOrStaleDueCommandsDoNotApply(boolean async) {
        fixture(async).givenCommands(new PlanReminder(reminderId, projectId, DEADLINE))
                .whenCommand(new RunReminder(reminderId, DEADLINE))
                .expectSuccessfulResult().expectNoEvents().expectNoErrors()
                .andThen().givenCommands(new RescheduleReminder(reminderId, DEADLINE.plusSeconds(1800)))
                .whenCommand(new RunReminder(reminderId, DEADLINE))
                .expectSuccessfulResult().expectNoEvents().expectNoErrors()
                .expectThat(fc -> assertFalse(Fluxzero.loadModel(reminderId).get().completed()));
    }

    @Model record Project(@EntityId ProjectId projectId) {}
    @Model record Reminder(@EntityId ReminderId reminderId, @Parent ProjectId projectId,
                           Instant deadline, boolean completed) {}
    record CreateProject(ProjectId projectId) { @Apply Project apply() { return new Project(projectId); } }
    record DeleteProject(ProjectId projectId) { @Apply Project apply(Project current) { return null; } }
    record PlanReminder(ReminderId reminderId, ProjectId projectId, Instant deadline) {
        @Apply Reminder apply() { return new Reminder(reminderId, projectId, deadline, false); }
    }
    record RescheduleReminder(ReminderId reminderId, Instant deadline) {
        @Apply Reminder apply(Reminder current) { return new Reminder(reminderId, current.projectId(), deadline, false); }
    }
    record RunReminder(ReminderId reminderId, Instant expectedDeadline) {
        @InterceptApply RunReminder applicable(@Nullable Reminder current) {
            return current == null || current.completed() || !current.deadline().equals(expectedDeadline)
                   || Fluxzero.currentTime().isBefore(expectedDeadline) ? null : this;
        }
        @Apply Reminder apply(Reminder current) {
            return new Reminder(reminderId, current.projectId(), current.deadline(), true);
        }
    }
    @Consumer(name = "reminder-schedules", singleTracker = true, errorHandler = ThrowingErrorHandler.class)
    static class ReminderSchedules {
        @HandleEvent void changed(Graph<Reminder> change) {
            Reminder current = Fluxzero.loadCurrentGraph(change.id(), Reminder.class).get();
            ScheduleId id = scheduleId(change.id());
            if (current == null || current.completed()) {
                Fluxzero.cancelSchedule(id);
            } else {
                Fluxzero.scheduleCommand(new RunReminder(current.reminderId(), current.deadline()), id, current.deadline());
            }
        }
        static ScheduleId scheduleId(Object id) { return ScheduleId.of("reminder", id); }
    }
    static class ProjectId extends Id<Project> { ProjectId(String value) { super(value); } }
    static class ReminderId extends Id<Reminder> { ReminderId(String value) { super(value); } }
}
