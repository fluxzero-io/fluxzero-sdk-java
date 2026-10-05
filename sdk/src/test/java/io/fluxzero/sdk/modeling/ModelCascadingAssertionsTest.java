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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class ModelCascadingAssertionsTest {
    private static final IllegalCommandException CLOSED = new IllegalCommandException("This project is closed.");
    private static final AtomicInteger checks = new AtomicInteger();
    private static final AtomicInteger applies = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> observedUser = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> race = new java.util.concurrent.atomic.AtomicReference<>();
    private TestFixture fixture;

    private TestFixture fixture(boolean async) {
        checks.set(0);
        return fixture = async ? TestFixture.createAsync() : TestFixture.create();
    }

    @AfterEach void close() { race.set(null); if (fixture != null) { fixture.getFluxzero().close(); } }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void blocksChildCreationAndAllDescendantDepths(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", true))
                .whenCommand(new CreateTask("task", "project")).expectExceptionalResult(CLOSED);
        fixture.givenCommands(new SeedProject("project", false), new CreateTask("task", "project"),
                              new CreateLine("line", "task"), new CloseProject("project"))
                .whenCommand(new TouchLine("line")).expectExceptionalResult(CLOSED);
        assertEquals(0, fixture.getFluxzero().modelRepository().load("line", Line.class).get().revision());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownRuleAllowsClosingButRejectsLaterMutations(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", false))
                .whenCommand(new CloseProject("project")).expectSuccessfulResult();
        fixture.whenCommand(new CloseProject("project")).expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void applyExceptionDoesNotDisableOtherMutations(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", false), new CreateTask("task", "project"),
                                     new CloseProject("project"))
                .whenCommand(new CorrectTask("task")).expectSuccessfulResult();
        fixture.whenCommand(new TouchTask("task")).expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledParentStopsItsWholeSubtree(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", true), new SeedDetached("detached", "project"))
                .whenCommand(new CreateDetachedLine("line", "detached")).expectSuccessfulResult();
        assertEquals(0, checks.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void allParentsAreCheckedAndDiamondChecksOnce(boolean async) {
        fixture(async).givenCommands(new SeedProject("open", false), new SeedProject("closed", true))
                .whenCommand(new CreateShared("shared", "open", "closed")).expectExceptionalResult(CLOSED);
        checks.set(0);
        fixture.whenCommand(new CreateShared("shared", "open", "open")).expectSuccessfulResult();
        assertEquals(1, checks.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cannotEscapeClosedParentByMoving(boolean async) {
        fixture(async).givenCommands(new SeedProject("old", false), new SeedProject("new", false),
                                     new CreateTask("task", "old"), new CloseProject("old"))
                .whenCommand(new MoveTask("task", "new")).expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void effectiveSubstepsSeeEarlierParentChanges(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", false), new CreateTask("task", "project"))
                .whenCommand(new CloseAndTouch("project", "task")).expectExceptionalResult(CLOSED);
        assertEquals(false, fixture.getFluxzero().modelRepository().load("project", Project.class).get().closed());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitAssertionsAndBeforeTimingArePreserved(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", true));
        applies.set(0);
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertLegal(new CountedClose("project")))
                .expectExceptionalResult(CLOSED);
        fixture.whenCommand(new CountedClose("project")).expectExceptionalResult(CLOSED);
        assertEquals(0, applies.get());
        fixture.givenCommands(new SeedProject("project", false), new CreateTask("task", "project"),
                              new CloseProject("project"))
                .whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertLegal(new TouchTask("task")))
                .expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitAssertionsObserveClosedParentWhileChildCacheLags(boolean async) throws Exception {
        CountDownLatch polling = holdCacheUpdates(async);
        fixture.givenCommands(new SeedProject("project", false), new CreateTask("task", "project"));
        assertTrue(polling.await(5, TimeUnit.SECONDS));
        fixture.whenCommand(new CloseProject("project")).expectSuccessfulResult();
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertLegal(new TouchTask("task")))
                .expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mutationsObserveReopenedParentWhileChildCacheLags(boolean async) throws Exception {
        CountDownLatch polling = holdCacheUpdates(async);
        fixture.givenCommands(new SeedProject("project", true), new SeedTask("task", "project"));
        assertTrue(polling.await(5, TimeUnit.SECONDS));
        fixture.whenCommand(new SeedProject("project", false)).expectSuccessfulResult();
        fixture.whenCommand(new TouchTask("task")).expectSuccessfulResult();
        assertEquals(1, fixture.getFluxzero().modelRepository().load("task", Task.class).get().revision());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedMutationObservesReopenedParentWhileChildCacheLags(boolean async) throws Exception {
        CountDownLatch polling = holdCacheUpdates(async);
        fixture.givenCommands(new SeedProject("project", true), new SeedTask("first", "project"),
                              new SeedTask("second", "project"));
        assertTrue(polling.await(5, TimeUnit.SECONDS));
        // Establish both cached inputs at the same closed-parent boundary before reopening.
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertLegal(new TouchSelected("first", "second")))
                .expectExceptionalResult(CLOSED);
        fixture.whenCommand(new SeedProject("project", false)).expectSuccessfulResult();
        fixture.whenCommand(new TouchSelected("first", "second")).expectSuccessfulResult();
        assertEquals(1, fixture.getFluxzero().modelRepository().load("first", Task.class).get().revision());
        assertEquals(0, fixture.getFluxzero().modelRepository().load("second", Task.class).get().revision());
    }

    record TouchSelected(String first, String second) implements ProjectChange {
        @Apply Task apply(@io.fluxzero.sdk.tracking.handling.Association("first") Task first,
                          @io.fluxzero.sdk.tracking.handling.Association("second") Task second) {
            return new Task(first.taskId(), first.projectId(), first.revision() + 1);
        }
    }

    private CountDownLatch holdCacheUpdates(boolean async) {
        fixture = fixture(async).spy();
        CountDownLatch polling = new CountDownLatch(1);
        doAnswer(invocation -> {
            TrackModelUpdates request = invocation.getArgument(0);
            if (request.getMaxWaitMillis() == 0) { return invocation.callRealMethod(); }
            polling.countDown();
            // The repository cancels this pending poll when the fixture closes.
            return new CompletableFuture<TrackModelUpdatesResult>();
        }).when(fixture.getFluxzero().client().getEventStoreClient()).trackModelUpdates(any());
        return polling;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void followsRepeatedModelTypesWithoutDuplicateLocalChecks(boolean async) {
        fixture(async).givenCommands(new SeedFolder("root", null, true), new SeedFolder("middle", "root", false),
                                     new SeedFolder("leaf", "middle", false))
                .whenCommand(new TouchFolder("leaf")).expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void afterChecksSeeFinalStateAndAllowSameCommitRepair(boolean async) {
        fixture(async).givenCommands(new SeedAfter("after", false), new SeedAfterChild("child", "after"))
                .whenCommand(new BreakAndRepair("after", "child")).expectSuccessfulResult();
        fixture.whenCommand(new BreakAndTouch("after", "child")).expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void contextOnlyInjectionAndPayloadFilteringWork(boolean async) {
        var builder = io.fluxzero.sdk.configuration.DefaultFluxzero.builder().registerUserProvider(
                new io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider(new TestUser("caller")));
        fixture = async ? TestFixture.createAsync(builder) : TestFixture.create(builder);
        fixture.givenCommands(new SeedUserProject("user-project"))
                .whenCommand(new CreateUserChild("user-child", "user-project")).expectSuccessfulResult();
        assertEquals("caller", observedUser.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replayDoesNotRepeatAssertions(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", false), new CreateTask("task", "project"));
        checks.set(0);
        fixture.getFluxzero().cache().clear();
        fixture.getFluxzero().modelRepository().load("task", Task.class);
        assertEquals(0, checks.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void directGraphUpdateCannotBypassAncestor(boolean async) {
        fixture(async).givenCommands(new SeedFolder("root", null, true), new SeedFolder("leaf", "root", false))
                .whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.loadGraph("leaf", Folder.class)
                        .update(folder -> new Folder("leaf", folder.parentId(), true)).commit())
                .expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resultBoundApplyExceptionsUseActualTargets(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", false), new CreateTask("first", "project"),
                new CreateTask("second", "project"), new CloseProject("project"))
                .whenCommand(new CorrectCollection(List.of("first", "second"))).expectSuccessfulResult();
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertAndApply(new CorrectDynamic("first")))
                .expectSuccessfulResult();
        fixture.whenCommand(new CorrectSelected("first", "second")).expectSuccessfulResult();
        fixture.whenCommand(new TouchTask("first")).expectExceptionalResult(CLOSED);
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertLegal(new CorrectTask("first")))
                .expectExceptionalResult(CLOSED);
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertLegal(new EnabledCorrection("first")))
                .expectExceptionalResult(CLOSED);
    }

    record EnabledCorrection(String taskId) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.ENABLED)
        Task apply(Task task) { return task; }
    }
    record CorrectCollection(List<String> taskIds) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        List<Task> apply(List<Task> tasks) { return tasks; }
    }
    record CorrectDynamic(String taskId) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        Object apply(Task task) { return task; }
    }
    record CorrectSelected(String first, String second) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        Task apply(@io.fluxzero.sdk.tracking.handling.Association("first") Task first,
                   @io.fluxzero.sdk.tracking.handling.Association("second") Task second) { return first; }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void automaticDeletionKeepsCauseForOtherParentsRules(boolean async) {
        fixture(async).givenCommands(new SeedDeleteRoot("root"), new SeedDeleteGuard("guard"),
                new SeedDeleteChild("child", "root", "guard"))
                .whenCommand(new DeleteRoot("root")).expectExceptionalResult(CLOSED);
    }
    @Model(searchable = false) record DeleteOwner(@EntityId String rootId) {}
    @Model(searchable = false) record DeleteGuard(@EntityId String guardId) {
        @AssertLegal(cascade = true, allowedClasses = DeleteRoot.class)
        void prevent(DeleteRoot command) { throw CLOSED; }
    }
    @Model(searchable = false) record DeleteChild(@EntityId String childId,
            @Parent(DeleteOwner.class) String rootId,
            @Parent(value = DeleteGuard.class, deleteOnParentDeletion = false) String guardId) {}
    record SeedDeleteRoot(String rootId) { @Apply DeleteOwner apply() { return new DeleteOwner(rootId); } }
    record SeedDeleteGuard(String guardId) { @Apply DeleteGuard apply() { return new DeleteGuard(guardId); } }
    record SeedDeleteChild(String childId, String rootId, String guardId) {
        @Apply DeleteChild apply() { return new DeleteChild(childId, rootId, guardId); }
    }
    record DeleteRoot(String rootId) { @Apply DeleteOwner apply(DeleteOwner owner) { return null; } }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void filteredAndDisabledRoutesAddNoParentReads(boolean async) {
        fixture = fixture(async).spy();
        fixture.givenCommands(new SeedProject("project", true), new SeedDetached("detached", "project"),
                new SeedUserProject("filtered"));
        fixture.whenCommand(new CreateDetachedLine("line", "detached")).expectSuccessfulResult()
                .expectThat(fc -> assertNoParentRead(fc, "project"));
        fixture.whenCommand(new UnfilteredUserChild("child", "filtered")).expectSuccessfulResult()
                .expectThat(fc -> assertNoParentRead(fc, "filtered"));
    }

    private static void assertNoParentRead(io.fluxzero.sdk.Fluxzero fc, String parentId) {
        var client = fc.client().getEventStoreClient();
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).getModelGraph(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).getModelEvents(
                org.mockito.ArgumentMatchers.argThat(request -> request.getRequests().stream()
                        .anyMatch(stream -> stream.getModelId().equals(parentId))));
    }
    record UnfilteredUserChild(String childId, String projectId) {
        @Apply UserChild apply() { return new UserChild(childId, projectId); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modelApplyCanReenableValidationForPayloadResult(boolean async) {
        fixture = async ? TestFixture.createAsync(StrictTask.class) : TestFixture.create(StrictTask.class);
        fixture.givenCommands(new SeedProject("project", false), new SeedStrictTask("task", "project"),
                new CloseProject("project"))
                .whenCommand(new CorrectStrictTask("task")).expectExceptionalResult(CLOSED);
    }
    @Model(searchable = false)
    record StrictTask(@EntityId String taskId, @Parent(Project.class) String projectId, int count) {
        @Apply(ancestorValidation = AncestorValidation.ENABLED)
        StrictTask finalizeCorrection(CorrectStrictTask command) { return this; }
    }
    record SeedStrictTask(String taskId, String projectId) {
        @Apply StrictTask apply() { return new StrictTask(taskId, projectId, 0); }
    }
    record CorrectStrictTask(String taskId) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        StrictTask apply(StrictTask task) { return new StrictTask(taskId, task.projectId(), task.count() + 1); }
    }

    record CountedClose(String projectId) implements ProjectChange {
        @Apply Project apply(Project project) { applies.incrementAndGet(); return new Project(projectId, true); }
    }
    @Model(searchable = false)
    record Folder(@EntityId String folderId, @Parent(Folder.class) String parentId, boolean closed) {
        @AssertLegal(cascade = true)
        void open() { if (closed) { throw CLOSED; } }
    }
    record SeedFolder(String folderId, String parentId, boolean closed) {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        Folder apply() { return new Folder(folderId, parentId, closed); }
    }
    record TouchFolder(String folderId) {
        @Apply Folder apply(Folder folder) { return new Folder(folderId, folder.parentId(), true); }
    }
    @Model(searchable = false)
    record AfterProject(@EntityId String projectId, boolean closed) {
        @AssertLegal(cascade = true, afterHandler = true)
        void open() { if (closed) { throw CLOSED; } }
    }
    @Model(searchable = false)
    record AfterChild(@EntityId String childId, @Parent(AfterProject.class) String projectId, int count) {}
    record SeedAfter(String projectId, boolean closed) {
        @Apply AfterProject apply(@Nullable AfterProject existing) { return new AfterProject(projectId, closed); }
    }
    record SeedAfterChild(String childId, String projectId) {
        @Apply AfterChild apply() { return new AfterChild(childId, projectId, 0); }
    }
    record TouchAfterChild(String childId) {
        @Apply AfterChild apply(AfterChild child) { return new AfterChild(childId, child.projectId(), child.count() + 1); }
    }
    record BreakAndRepair(String projectId, String childId) {
        @InterceptApply List<?> split() { return List.of(new SeedAfter(projectId, true), new TouchAfterChild(childId),
                                                        new SeedAfter(projectId, false)); }
    }
    record BreakAndTouch(String projectId, String childId) {
        @InterceptApply List<?> split() { return List.of(new SeedAfter(projectId, true), new TouchAfterChild(childId)); }
    }
    record TestUser(String name) implements io.fluxzero.sdk.tracking.handling.authentication.User {
        @Override public String id() { return name; }
        @Override public String getName() { return name; }
        @Override public boolean hasRole(String role) { return false; }
    }
    @Model(searchable = false)
    record UserProject(@EntityId String projectId) {
        @AssertLegal(cascade = true, allowedClasses = ProjectChange.class)
        void check(io.fluxzero.sdk.tracking.handling.authentication.User user) { observedUser.set(user.id()); }
    }
    @Model(searchable = false)
    record UserChild(@EntityId String childId, @Parent(UserProject.class) String projectId) {}
    record SeedUserProject(String projectId) { @Apply UserProject apply() { return new UserProject(projectId); } }
    record CreateUserChild(String childId, String projectId) implements ProjectChange {
        @Apply UserChild apply() { return new UserChild(childId, projectId); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptCannotBypassConcurrentClosingOrCreation(boolean missingParent) {
        fixture(false);
        if (!missingParent) { fixture.givenCommands(new SeedRaceProject("race-project", false)); }
        fixture.givenCommands(new SeedRaceChild("race-child", "race-project"));
        race.set(() -> java.util.concurrent.CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            io.fluxzero.sdk.Fluxzero.assertAndApply(new SeedRaceProject("race-project", true));
            return null;
        })).join());
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertAndApply(new RaceTouch("race-child")))
                .expectExceptionalResult(ModelCommitConflictException.class);
        assertEquals(0, fixture.getFluxzero().modelRepository().load("race-child", RaceChild.class).get().count());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retryReevaluatesGuardAfterConcurrentClosure(boolean async) {
        fixture(async).givenCommands(new SeedRaceProject("race-project", false),
                                     new SeedRaceChild("race-child", "race-project"));
        race.set(() -> java.util.concurrent.CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            io.fluxzero.sdk.Fluxzero.assertAndApply(new SeedRaceProject("race-project", true));
            return null;
        })).join());
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertAndApply(new RetryTouch("race-child")))
                .expectExceptionalResult(CLOSED);
        assertEquals(0, fixture.getFluxzero().modelRepository().load("race-child", RaceChild.class).get().count());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptCannotRebaseIntoPreviouslyAbsentGuardedRoute(boolean async) {
        fixture(async).givenCommands(new SeedRaceProject("closed", true), new SeedRaceChild("child", null));
        race.set(() -> java.util.concurrent.CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            io.fluxzero.sdk.Fluxzero.assertAndApply(new ReparentRaceChild("child", "closed"));
            return null;
        })).join());
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertAndApply(new RaceTouch("child")))
                .expectExceptionalResult(ModelCommitConflictException.class);
        assertEquals(0, fixture.getFluxzero().modelRepository().load("child", RaceChild.class).get().count());
    }
    record ReparentRaceChild(String childId, String projectId) {
        @Apply RaceChild apply(RaceChild child) { return new RaceChild(childId, projectId, child.count()); }
    }

    interface RaceChange {}
    @Model(searchable = false, cached = false, conflictPolicy = ModelConflictPolicy.ACCEPT)
    record RaceProject(@EntityId String projectId, boolean closed) {
        @AssertLegal(cascade = true, allowedClasses = RaceChange.class)
        void check() { if (closed) { throw CLOSED; } }
    }
    @Model(searchable = false, conflictPolicy = ModelConflictPolicy.ACCEPT)
    record RaceChild(@EntityId String childId, @Parent(RaceProject.class) String projectId, int count) {}
    record SeedRaceProject(String projectId, boolean closed) {
        @Apply RaceProject apply(@Nullable RaceProject current) { return new RaceProject(projectId, closed); }
    }
    record SeedRaceChild(String childId, String projectId) {
        @Apply RaceChild apply() { return new RaceChild(childId, projectId, 0); }
    }
    record RaceTouch(String childId) implements RaceChange {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        RaceChild apply(RaceChild child) {
            Runnable action = race.getAndSet(null);
            if (action != null) { action.run(); }
            return new RaceChild(childId, child.projectId(), child.count() + 1);
        }
    }
    record RetryTouch(String childId) implements RaceChange {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        RaceChild apply(RaceChild child) {
            Runnable action = race.getAndSet(null);
            if (action != null) { action.run(); }
            return new RaceChild(childId, child.projectId(), child.count() + 1);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameTypeTargetsKeepSeparateApplyExceptions(boolean async) {
        fixture(async).givenCommands(new SeedProject("closed", true), new SeedProject("open", false),
                                     new SeedTask("exempt", "closed"), new SeedTask("protected", "open"))
                .whenCommand(new MixedTasks("exempt", "protected")).expectSuccessfulResult();
        fixture.givenCommands(new SeedProject("open", true))
                .whenCommand(new MixedTasks("exempt", "protected")).expectExceptionalResult(CLOSED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readingUnrelatedChildDoesNotRunItsAncestorChecks(boolean async) {
        fixture(async).givenCommands(new SeedProject("project", true), new SeedTask("task", "project"),
                                     new SeedUnrelated("unrelated"))
                .whenCommand(new ReadChild("unrelated", "task")).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nestedExplicitCheckProtectsOuterCommit(boolean missingParent) {
        fixture(false).givenCommands(new SeedUnrelated("unrelated"), new SeedRaceChild("race-child", "race-project"));
        if (!missingParent) { fixture.givenCommands(new SeedRaceProject("race-project", false)); }
        race.set(() -> java.util.concurrent.CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            io.fluxzero.sdk.Fluxzero.assertAndApply(new SeedRaceProject("race-project", true));
            return null;
        })).join());
        fixture.whenExecuting(fc -> io.fluxzero.sdk.Fluxzero.assertAndApply(new NestedTouch("unrelated", "race-child")))
                .expectExceptionalResult(ModelCommitConflictException.class);
        assertEquals(0, fixture.getFluxzero().modelRepository().load("unrelated", Unrelated.class).get().count());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void successiveGraphMutationsDoNotReuseEarlierPermission(boolean async) {
        fixture(async).givenCommands(new SeedFolder("root", null, false), new SeedFolder("leaf", "root", false))
                .whenCommand(new GraphSequence("root", "leaf")).expectExceptionalResult(CLOSED);
        assertEquals(false, fixture.getFluxzero().modelRepository().load("root", Folder.class).get().closed());
    }

    record SeedTask(String taskId, String projectId) {
        @Apply Task apply() { return new Task(taskId, projectId, 0); }
    }
    record MixedTasks(String first, String second) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        Task first(@io.fluxzero.sdk.tracking.handling.Association("first") Task task) {
            return new Task(task.taskId(), task.projectId(), task.revision() + 1);
        }
        @Apply Task second(@io.fluxzero.sdk.tracking.handling.Association("second") Task task) {
            return new Task(task.taskId(), task.projectId(), task.revision() + 1);
        }
    }
    @Model(searchable = false, conflictPolicy = ModelConflictPolicy.ACCEPT)
    record Unrelated(@EntityId String id, int count) {}
    record SeedUnrelated(String id) { @Apply Unrelated apply() { return new Unrelated(id, 0); } }
    record ReadChild(String id, String taskId) implements ProjectChange {
        @Apply Unrelated apply(Unrelated value, Task task) { return new Unrelated(id, value.count() + 1); }
    }
    record NestedTouch(String id, String childId) {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        Unrelated apply(Unrelated value) {
            io.fluxzero.sdk.Fluxzero.assertLegal(new RaceTouch(childId));
            Runnable action = race.getAndSet(null);
            if (action != null) { action.run(); }
            return new Unrelated(id, value.count() + 1);
        }
    }
    record GraphSequence(String folderId, String childId) {
        @InterceptApply List<?> split() {
            var parent = io.fluxzero.sdk.Fluxzero.loadGraph(folderId, Folder.class);
            var child = io.fluxzero.sdk.Fluxzero.loadGraph(childId, Folder.class);
            return List.of(parent.update(value -> new Folder(folderId, null, false)),
                           parent.update(value -> new Folder(folderId, null, true)),
                           child.update(value -> new Folder(childId, folderId, true)));
        }
    }

    interface ProjectChange {}
    @Model(searchable = false, cached = false)
    record Project(@EntityId String projectId, boolean closed) {
        @AssertLegal(cascade = true, allowedClasses = ProjectChange.class)
        void assertOpen() {
            checks.incrementAndGet();
            if (closed) { throw CLOSED; }
        }
    }
    @Model(searchable = false)
    record Task(@EntityId String taskId, @Parent(Project.class) String projectId, int revision) {}
    @Model(searchable = false)
    record Line(@EntityId String lineId, @Parent(Task.class) String taskId, int revision) {}
    @Model(searchable = false)
    record Detached(@EntityId String detachedId,
                    @Parent(value = Project.class, validateAncestors = false) String projectId) {}
    @Model(searchable = false)
    record DetachedLine(@EntityId String lineId, @Parent(Detached.class) String detachedId) {}
    @Model(searchable = false)
    record Shared(@EntityId String sharedId, @Parent(Project.class) String first, @Parent(Project.class) String second) {}

    record SeedProject(String projectId, boolean closed) {
        @Apply Project apply(@Nullable Project existing) { return new Project(projectId, closed); }
    }
    record CloseProject(String projectId) implements ProjectChange {
        @Apply Project apply(Project project) { return new Project(projectId, true); }
    }
    record CreateTask(String taskId, String projectId) implements ProjectChange {
        @Apply Task apply() { return new Task(taskId, projectId, 0); }
    }
    record TouchTask(String taskId) implements ProjectChange {
        @Apply Task apply(Task task) { return new Task(taskId, task.projectId(), task.revision() + 1); }
    }
    record CorrectTask(String taskId) implements ProjectChange {
        @Apply(ancestorValidation = AncestorValidation.DISABLED)
        Task apply(Task task) { return new Task(taskId, task.projectId(), task.revision() + 1); }
    }
    record MoveTask(String taskId, String projectId) implements ProjectChange {
        @Apply Task apply(Task task) { return new Task(taskId, projectId, task.revision()); }
    }
    record CreateLine(String lineId, String taskId) implements ProjectChange {
        @Apply Line apply() { return new Line(lineId, taskId, 0); }
    }
    record TouchLine(String lineId) implements ProjectChange {
        @Apply Line apply(Line line) { return new Line(lineId, line.taskId(), line.revision() + 1); }
    }
    record SeedDetached(String detachedId, String projectId) {
        @Apply Detached apply() { return new Detached(detachedId, projectId); }
    }
    record CreateDetachedLine(String lineId, String detachedId) implements ProjectChange {
        @Apply DetachedLine apply() { return new DetachedLine(lineId, detachedId); }
    }
    record CreateShared(String sharedId, String first, String second) implements ProjectChange {
        @Apply Shared apply() { return new Shared(sharedId, first, second); }
    }
    record CloseAndTouch(String projectId, String taskId) {
        @InterceptApply List<?> split() { return List.of(new CloseProject(projectId), new TouchTask(taskId)); }
    }
}
