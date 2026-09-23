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
package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply
import io.fluxzero.sdk.scheduling.ScheduleId
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.Consumer
import io.fluxzero.sdk.tracking.ThrowingErrorHandler
import io.fluxzero.sdk.tracking.handling.HandleEvent
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.time.Instant

class ModelLifecycleKotlinTest {
    private val taskId = TaskId("kotlin-task")
    private fun fixture(async: Boolean) = if (async) TestFixture.createAsync() else TestFixture.create()

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun atomicUpdatesAndConsumeOnce(async: Boolean) {
        fixture(async).givenCommands(CreateTask(taskId, null)).whenExecuting {
            val graph = Fluxzero.loadCurrentGraph(taskId)
            assertTrue(graph.compareAndSet(graph.get()!!.copy(completed = true)))
            assertFalse(graph.compareAndSet(graph.get()))
            val after = graph.updateAndGet({ current -> current.update { it.copy(completed = false) } }, 2)
            assertFalse(after.get()!!.completed)
            val consumed = after.getAndUpdate { it.delete() }
            assertFalse(consumed.get()!!.completed)
            assertNull(Fluxzero.loadCurrentGraph(taskId).get())
        }.expectSuccessfulResult().expectNoErrors()
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun duplicateFactoryRejects(async: Boolean) {
        fixture(async).givenCommands(CreateTask(taskId, null))
            .whenCommand(CreateTask(taskId, null))
            .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION).expectNoEvents()
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun nullableReferencePermitsAbsentParentId(async: Boolean) {
        fixture(async).whenCommand(CreateTask(taskId, null))
            .expectSuccessfulResult().expectNoErrors()
            .expectThat { assertEquals(Task(taskId, null, false), Fluxzero.loadModel(taskId).get()) }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun nullableCurrentStateExpressesUpsert(async: Boolean) {
        fixture(async).givenCommands(PutTask(taskId, false))
            .whenCommand(PutTask(taskId, true)).expectSuccessfulResult().expectNoErrors()
            .expectThat {
                it.cache().clear()
                assertEquals(Task(taskId, null, true), Fluxzero.loadModel(taskId).get())
            }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun reminderSchedulesConvergeAndCascadeCancels(async: Boolean) {
        val projectId = ProjectId("reminder-project")
        val reminderId = ReminderId("reminder")
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val deadline = start.plusSeconds(3600)
        val schedules = ReminderSchedules()
        val fixture = if (async) TestFixture.createAsync(schedules) else TestFixture.create(schedules)
        fixture.atFixedTime(start).givenCommands(CreateProject(projectId), PlanReminder(reminderId, projectId, deadline))
            .whenCommand(RescheduleReminder(reminderId, deadline.plusSeconds(60)))
            .expectSuccessfulResult().expectNoErrors()
            .expectThat {
                assertEquals(deadline.plusSeconds(60), it.messageScheduler()
                    .getSchedule(ScheduleId.of("reminder", reminderId)).orElseThrow().deadline)
            }.andThen().whenCommand(DeleteProject(projectId))
            .expectSuccessfulResult().expectNoErrors().expectNoSchedules()
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun deliveredReminderCompletesAndReplays(async: Boolean) {
        val projectId = ProjectId("reminder-project")
        val reminderId = ReminderId("reminder")
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val deadline = start.plusSeconds(3600)
        val fixture = if (async) TestFixture.createAsync(ReminderSchedules()) else TestFixture.create(ReminderSchedules())
        fixture.atFixedTime(start).givenCommands(CreateProject(projectId), PlanReminder(reminderId, projectId, deadline))
            .whenTimeAdvancesTo(deadline).expectNoErrors().expectNoSchedules()
            .expectThat {
                it.cache().clear()
                assertEquals(true, Fluxzero.loadModel(reminderId).get().completed)
            }
    }

    @Model data class Project(@EntityId val projectId: ProjectId)
    @Model data class Task(@EntityId val taskId: TaskId, @Parent val projectId: ProjectId?, val completed: Boolean)
    data class CreateTask(val taskId: TaskId, val projectId: ProjectId?) {
        @AssertLegal fun validParent(project: Project?) { assertNull(project) }
        @Apply fun apply() = Task(taskId, projectId, false)
    }
    data class PutTask(val taskId: TaskId, val completed: Boolean) {
        @Apply fun apply(current: Task?) = Task(taskId, current?.projectId, completed)
    }
    class ProjectId(value: String) : Id<Project>(value)
    class TaskId(value: String) : Id<Task>(value)

    @Model data class Reminder(@EntityId val reminderId: ReminderId, @Parent val projectId: ProjectId,
                              val deadline: Instant, val completed: Boolean)
    class ReminderId(value: String) : Id<Reminder>(value)
    data class CreateProject(val projectId: ProjectId) {
        @Apply fun apply() = Project(projectId)
    }
    data class DeleteProject(val projectId: ProjectId) {
        @Apply fun apply(current: Project): Project? = null
    }
    data class PlanReminder(val reminderId: ReminderId, val projectId: ProjectId, val deadline: Instant) {
        @Apply fun apply() = Reminder(reminderId, projectId, deadline, false)
    }
    data class RescheduleReminder(val reminderId: ReminderId, val deadline: Instant) {
        @Apply fun apply(current: Reminder) = current.copy(deadline = deadline, completed = false)
    }
    data class RunReminder(val reminderId: ReminderId, val expectedDeadline: Instant) {
        @InterceptApply
        fun applicable(current: Reminder?): RunReminder? =
            if (current == null || current.completed || current.deadline != expectedDeadline
                || Fluxzero.currentTime().isBefore(expectedDeadline)) null else this
        @Apply fun apply(current: Reminder) = current.copy(completed = true)
    }
    @Consumer(name = "reminder-schedules", singleTracker = true, errorHandler = ThrowingErrorHandler::class)
    class ReminderSchedules {
        @HandleEvent fun changed(change: Graph<Reminder>) {
            val current = Fluxzero.loadCurrentGraph(change.id(), Reminder::class.java).get()
            val id = ScheduleId.of("reminder", change.id())
            if (current == null || current.completed) {
                Fluxzero.cancelSchedule(id)
            } else {
                Fluxzero.scheduleCommand(RunReminder(current.reminderId, current.deadline), id, current.deadline)
            }
        }
    }
}
