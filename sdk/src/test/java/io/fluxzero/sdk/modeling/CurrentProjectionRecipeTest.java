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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.search.DocumentStoreException;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ForeverRetryingErrorHandler;
import io.fluxzero.sdk.tracking.handling.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CurrentProjectionRecipeTest {
    static final String COLLECTION = "project-overviews";
    static final String CONSUMER = "project-overview-writer";
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DUE = START.plusSeconds(60);
    static final ProjectId PROJECT = new ProjectId("one");
    static final ProjectId OTHER = new ProjectId("two");
    static final PolicyId POLICY = new PolicyId("policy");
    static final TaskId TASK = new TaskId("task");

    TestFixture fixture(boolean async) { return fixture(async, false); }

    TestFixture fixture(boolean async, boolean spy) {
        var fixture = (async ? TestFixture.createAsync(new Updates(), Overview.class)
                      : TestFixture.create(new Updates(), Overview.class));
        return (spy ? fixture.spy() : fixture).atFixedTime(START).givenCommands(new SetPolicy(POLICY, "standard"),
                                                new CreateProject(PROJECT, POLICY, "initial", DUE));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rootUpdateReachesTheProjectionWithoutAnExplicitRefresh(boolean async) {
        fixture(async).whenCommand(new RenameProject(PROJECT, "renamed"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals("renamed", row(PROJECT).name()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void spyingPreservesDocumentDrivenRefresh(boolean async) {
        fixture(async, true).whenCommand(new RenameProject(PROJECT, "renamed"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals("renamed", row(PROJECT).name()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void updatesAndMovedChildrenReplaceExactlyTheAffectedRows(boolean async) {
        fixture(async).givenCommands(new CreateProject(OTHER, POLICY, "other", DUE),
                                    new AddTask(TASK, PROJECT))
                .whenCommand(new MoveTask(TASK, OTHER))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(2, Fluxzero.search(Overview.class).count());
                    assertEquals(0, row(PROJECT).taskCount());
                    assertEquals(1, row(OTHER).taskCount());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedSignalAndDeletedSourceCannotRestoreAnOldRow(boolean async) {
        fixture(async).givenCommands(new RenameProject(PROJECT, "latest"))
                .whenEvent(new Refresh(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertEquals("latest", row(PROJECT).name()))
                .andThen().whenCommand(new DeleteProject(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertEquals(0, Fluxzero.search(Overview.class).count()))
                .andThen().whenEvent(new Refresh(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertEquals(0, Fluxzero.search(Overview.class).count()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void coldRebuildAndRepeatedRefreshPreserveOneRowAndDeadline(boolean async) {
        fixture(async).givenCommands(new AddTask(TASK, PROJECT))
                .given(fc -> { fc.documentStore().deleteDocument(PROJECT, COLLECTION).join(); fc.cache().clear(); })
                .whenEvent(new Refresh(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertEquals(1, row(PROJECT).taskCount()))
                .andThen().whenEvent(new Refresh(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertEquals(1, Fluxzero.search(Overview.class).count()))
                .andThen().whenTimeAdvancesTo(DUE).expectNoErrors()
                .expectThat(fc -> assertTrue(row(PROJECT).overdue()))
                .andThen().whenCommand(new DeleteProject(PROJECT)).expectNoSchedules().expectNoErrors();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitRebuildRemovesAStaleRowWhoseSourceWasDeleted(boolean async) {
        fixture(async).givenCommands(new DeleteProject(PROJECT))
                .givenStateful(new Overview(PROJECT, POLICY, "stale", "old", 0, false))
                .whenEvent(new Refresh(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertEquals(0, Fluxzero.search(Overview.class).count()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharedPolicyUpdatesEveryAssociatedRowWithoutTouchingOtherPolicies(boolean async) {
        var otherPolicy = new PolicyId("other");
        var third = new ProjectId("three");
        fixture(async).givenCommands(new CreateProject(OTHER, POLICY, "other", DUE),
                                    new SetPolicy(otherPolicy, "separate"),
                                    new CreateProject(third, otherPolicy, "third", DUE))
                .whenCommand(new SetPolicy(POLICY, "revised"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals("revised", row(PROJECT).policy());
                    assertEquals("revised", row(OTHER).policy());
                    assertEquals("separate", row(third).policy());
                    assertEquals(3, Fluxzero.search(Overview.class).count());
                });
    }

    @Test void aHelperRetainsThePinnedCurrentBoundaryAfterAnotherCommit() {
        fixture(false).whenApplying(fc -> {
            var current = Fluxzero.loadCurrentGraph(PROJECT).strict(true);
            Fluxzero.sendCommandAndWait(new SetPolicy(POLICY, "new"));
            return List.of(Facts.read(current).policy(), Fluxzero.loadCurrentGraph(POLICY).get().name());
        }).expectResult(List.of("standard", "new"));
    }

    @Test void temporaryStorageFailureRetriesBeforeTheWriterAdvances() {
        var attempts = new AtomicInteger();
        fixture(true, true).whenExecuting(fc -> {
            doAnswer(invocation -> {
                List<io.fluxzero.common.api.search.SerializedDocument> documents = invocation.getArgument(0);
                if (documents.stream().noneMatch(d -> COLLECTION.equals(d.getCollection()))) return invocation.callRealMethod();
                if (!"after retry".equals(Fluxzero.loadCurrentGraph(PROJECT).get().name())) return invocation.callRealMethod();
                long failedIndex = io.fluxzero.sdk.common.serialization.DeserializingMessage.getCurrent().getIndex();
                if (attempts.incrementAndGet() == 1) return CompletableFuture.failedFuture(
                        new DocumentStoreException("Temporary outage", new IllegalStateException("Unavailable")));
                verify(fc.client().getTrackingClient(MessageType.EVENT), never())
                        .storePosition(eq(CONSUMER), any(), longThat(index -> index >= failedIndex));
                return invocation.callRealMethod();
            }).when(fc.client().getSearchClient()).index(anyList(), eq(Guarantee.STORED), anyBoolean());
            Fluxzero.sendCommandAndWait(new RenameProject(PROJECT, "after retry"));
        })
                .expectThat(fc -> { assertEquals("after retry", row(PROJECT).name()); assertEquals(2, attempts.get()); });
    }

    static Overview row(ProjectId id) {
        return Fluxzero.search(Overview.class).match(id, "projectId").fetch(1).getFirst();
    }

    @Model(searchable = true) record Project(@EntityId ProjectId projectId, PolicyId policyId, String name, Instant due) {}
    static final class ProjectId extends Id<Project> { ProjectId(String id) { super(id, "recipe-project-"); } }
    @Model record Policy(@EntityId PolicyId policyId, String name) {}
    static final class PolicyId extends Id<Policy> { PolicyId(String id) { super(id, "recipe-policy-"); } }
    @Model record Task(@EntityId TaskId taskId, @Parent(pathInParent = "tasks") ProjectId projectId) {}
    static final class TaskId extends Id<Task> { TaskId(String id) { super(id, "recipe-task-"); } }
    record SetPolicy(PolicyId policyId, String name) { @Apply Policy apply(@jakarta.annotation.Nullable Policy current) { return new Policy(policyId, name); } }
    record CreateProject(ProjectId projectId, PolicyId policyId, String name, Instant due) {
        @Apply Project apply() { return new Project(projectId, policyId, name, due); }
    }
    record RenameProject(ProjectId projectId, String name) {
        @Apply Project apply(Project current) { return new Project(projectId, current.policyId(), name, current.due()); }
    }
    record DeleteProject(ProjectId projectId) { @Apply Project apply(Project current) { return null; } }
    record AddTask(TaskId taskId, ProjectId projectId) { @Apply Task apply() { return new Task(taskId, projectId); } }
    record MoveTask(TaskId taskId, ProjectId projectId) { @Apply Task apply(Task current) { return new Task(taskId, projectId); } }
    record Refresh(ProjectId projectId) {}

    @Consumer(name = "project-overview-signals", errorHandler = ForeverRetryingErrorHandler.class)
    static class Updates {
        @HandleDocument void changed(Graph<Project> graph) {
            if (graph.get() != null) Fluxzero.publishEvent(new Refresh(graph.get().projectId()));
        }
        @HandleEvent void deleted(Graph<Project> graph) {
            if (graph.get() == null && graph.previous() != null && graph.previous().get() != null)
                Fluxzero.publishEvent(new Refresh(graph.previous().get().projectId()));
        }
        @HandleSchedule void due(Refresh refresh) { Fluxzero.publishEvent(refresh); }
    }

    record Facts(String name, String policy, int taskCount, Instant due) {
        static Facts read(Graph<Project> graph) {
            var project = graph.get();
            var policy = Fluxzero.loadGraphAt(project.policyId(), graph.stateIndex()).strict(true).get();
            return new Facts(project.name(), policy.name(), graph.childModels("tasks", Task.class).size(), project.due());
        }
    }

    @Stateful(collection = COLLECTION)
    @Consumer(name = CONSUMER, singleTracker = true, errorHandler = ForeverRetryingErrorHandler.class)
    record Overview(@EntityId @Association ProjectId projectId, @Association PolicyId policyId, String name, String policy, int taskCount, boolean overdue) {
        @HandleEvent static Overview create(Refresh refresh) { return read(refresh.projectId()); }
        @HandleEvent Overview refresh(Refresh refresh) { return read(refresh.projectId()); }
        @HandleEvent Overview policyChanged(SetPolicy changed) { return read(projectId); }
        static Overview read(ProjectId id) {
            var graph = Fluxzero.loadCurrentGraph(id).strict(true);
            var scheduleId = "project-overview:" + id;
            if (graph.get() == null) { Fluxzero.cancelSchedule(scheduleId); return null; }
            var facts = Facts.read(graph);
            var overdue = !Fluxzero.currentTime().isBefore(facts.due());
            if (overdue) Fluxzero.cancelSchedule(scheduleId);
            else Fluxzero.schedule(new Refresh(id), scheduleId, facts.due());
            return new Overview(id, graph.get().policyId(), facts.name(), facts.policy(), facts.taskCount(), overdue);
        }
    }
}
