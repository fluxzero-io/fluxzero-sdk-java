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

package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.IllegalCommandException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ModelCascadingDocumentationKotlinTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun guardsDescendantsAndAllowsExplicitCorrection(async: Boolean) {
        val fixture = if (async) TestFixture.createAsync() else TestFixture.create()
        try {
            val projectId = ProjectId("project")
            val taskId = TaskId("task")
            fixture.givenCommands(CreateProject(projectId), CreateTask(taskId, projectId), CloseProject(projectId))
                .whenCommand(CompleteTask(taskId)).expectExceptionalResult(IllegalCommandException::class.java)
            fixture.whenCommand(CorrectTaskStatus(taskId, true)).expectSuccessfulResult()
        } finally {
            fixture.fluxzero.close()
        }
    }

    interface ProjectChange
    class ProjectId(value: String) : Id<Project>(value)
    class TaskId(value: String) : Id<Task>(value)

    @Model
    data class Project(@EntityId val projectId: ProjectId, val closed: Boolean) {
        @AssertLegal(cascade = true, allowedClasses = [ProjectChange::class])
        fun assertOpen() {
            if (closed) throw IllegalCommandException("This project is closed.")
        }
    }
    @Model
    data class Task(@EntityId val taskId: TaskId, @Parent val projectId: ProjectId, val completed: Boolean)

    data class CreateProject(val projectId: ProjectId) {
        @Apply fun apply(): Project = Project(projectId, false)
    }
    data class CreateTask(val taskId: TaskId, val projectId: ProjectId) : ProjectChange {
        @Apply fun apply(): Task = Task(taskId, projectId, false)
    }
    data class CloseProject(val projectId: ProjectId) : ProjectChange {
        @Apply fun apply(project: Project): Project = project.copy(closed = true)
    }
    data class CompleteTask(val taskId: TaskId) : ProjectChange {
        @Apply fun apply(task: Task): Task = task.copy(completed = true)
    }
    data class CorrectTaskStatus(val taskId: TaskId, val completed: Boolean) : ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        fun apply(task: Task): Task = task.copy(completed = completed)
    }
}
