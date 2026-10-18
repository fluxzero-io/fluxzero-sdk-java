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
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.Stateful;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Characterizes a current-state projection using the public handler APIs. */
class StatefulGraphProjectionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentGraphMaintainsOneSearchableStatefulRow(boolean async) {
        var id = new ProjectId("one");
        var fixture = async ? TestFixture.createAsync(ProjectView.class) : TestFixture.create(ProjectView.class);
        fixture.givenCommands(new CreateProject(id, "initial"))
                .whenCommand(new RenameProject(id, "renamed"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(List.of(new ProjectView(id.toString(), "renamed", 0)),
                                              Fluxzero.search(ProjectView.class).fetch(10)))
                .andThen().whenCommand(new AddTask(new TaskId("first"), id))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(List.of(new ProjectView(id.toString(), "renamed", 1)),
                                              Fluxzero.search(ProjectView.class).fetch(10)));
    }

    @Model(searchable = true)
    record Project(@EntityId ProjectId projectId, String name) {}
    static final class ProjectId extends Id<Project> {
        ProjectId(String id) { super(id, "projection-project-"); }
    }
    @Model
    record Task(@EntityId TaskId taskId, @Parent(pathInParent = "tasks") ProjectId projectId) {}
    static final class TaskId extends Id<Task> {
        TaskId(String id) { super(id, "projection-task-"); }
    }
    record CreateProject(ProjectId projectId, String name) {
        @Apply Project apply() { return new Project(projectId, name); }
    }
    record RenameProject(ProjectId projectId, String name) {
        @Apply Project apply(Project current) { return new Project(projectId, name); }
    }
    record AddTask(TaskId taskId, ProjectId projectId) {
        @Apply Task apply() { return new Task(taskId, projectId); }
    }

    @Stateful
    record ProjectView(@EntityId String id, String name, int taskCount) {
        @HandleDocument
        @Association(always = true)
        static ProjectView project(Graph<Project> graph) {
            return new ProjectView(graph.id().toString(), graph.get().name(),
                                   graph.childModels("tasks", Task.class).size());
        }
    }
}
