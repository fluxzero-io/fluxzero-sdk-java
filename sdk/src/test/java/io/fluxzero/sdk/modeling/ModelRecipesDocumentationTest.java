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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.Consumer;
import jakarta.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executable sources for the companion, derived-preference and execution recipes. */
class ModelRecipesDocumentationTest {
    private static final ProjectId PROJECT = new ProjectId("example");
    private static final SpaceId LEFT = new SpaceId("left"), RIGHT = new SpaceId("right");
    private static final DeviceId LIGHT = new DeviceId("light");
    private static final JacksonSerializer SERIALIZER = new JacksonSerializer();

    private TestFixture fixture(boolean async, Object... handlers) {
        return async ? TestFixture.createAsync(handlers) : TestFixture.create(handlers);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void companionSharesFunctionalIdButNotRepositoryIdentity(boolean async) {
        fixture(async).givenCommands(new CreateProject(PROJECT), new PutStatus(PROJECT, false))
                .whenCommand(new PutStatus(PROJECT, true)).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    Graph<ProjectStatus> status = Fluxzero.loadGraph(PROJECT, ProjectStatus.class);
                    assertEquals("status-project-example", status.id());
                    assertEquals(new ProjectStatus(PROJECT, true), status.get());
                    assertEquals(PROJECT.toString(), status.parent(Project.class).orElseThrow().id());
                    assertEquals(List.of(status.get()), Fluxzero.loadGraph(PROJECT)
                            .childModels("status", ProjectStatus.class));
                    assertEquals(status.id(), status.current().id());
                }).andThen().whenCommand(new RemoveStatus(PROJECT)).expectNoErrors()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadGraph(PROJECT, ProjectStatus.class).isEmpty());
                    assertTrue(Fluxzero.loadGraph(PROJECT).isPresent());
                }).andThen().whenCommand(new PutStatus(PROJECT, true)).expectNoErrors()
                .andThen().whenCommand(new RemoveProject(PROJECT)).expectNoErrors()
                .expectThat(fc -> assertTrue(Fluxzero.loadGraph(PROJECT, ProjectStatus.class).isEmpty()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void derivedPreferenceUsesPinnedGraphAndIsNotRawState(boolean async) {
        fixture(async).givenCommands(new CreateSpace(LEFT), new AddDevice(LIGHT, LEFT))
                .whenExecuting(fc -> {
                    Graph<Space> historical = Fluxzero.loadCurrentGraph(LEFT);
                    Fluxzero.assertAndApply(new SelectPrimary(LEFT, LIGHT));
                    Graph<Space> selected = historical.current();
                    assertFalse(json(historical).path("devices").get(0).path("primary").asBoolean());
                    assertTrue(json(selected).path("devices").get(0).path("primary").asBoolean());
                    assertFalse(json(selected.childModels(Device.class).getFirst()).has("primary"));
                    Fluxzero.assertAndApply(new SelectPrimary(LEFT, null));
                    assertTrue(json(selected).path("devices").get(0).path("primary").asBoolean());
                    assertFalse(json(selected.current()).path("devices").get(0).path("primary").asBoolean());
                }).expectNoErrors();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedDeviceMustBeClearedBeforeMoveOrDelete(boolean async) {
        fixture(async).givenCommands(new CreateSpace(LEFT), new CreateSpace(RIGHT),
                        new AddDevice(LIGHT, LEFT), new SelectPrimary(LEFT, LIGHT))
                .whenCommand(new MoveDevice(LIGHT, RIGHT)).expectExceptionalResult(Rejected.class).expectNoEvents()
                .andThen().whenCommand(new DeleteDevice(LIGHT)).expectExceptionalResult(Rejected.class).expectNoEvents()
                .andThen().whenCommand(new SelectPrimary(LEFT, null)).expectSuccessfulResult()
                .andThen().whenCommand(new MoveDevice(LIGHT, RIGHT)).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadGraph(LEFT).children(Device.class).isEmpty());
                    assertEquals(LIGHT, Fluxzero.loadGraph(RIGHT).childModels(Device.class).getFirst().deviceId());
                }).andThen().whenCommand(new SelectPrimary(LEFT, LIGHT)).expectExceptionalResult(Rejected.class)
                .andThen().whenCommand(new DeleteDevice(LIGHT)).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertTrue(Fluxzero.loadGraph(RIGHT).children(Device.class).isEmpty()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preflightDoesNotRunAfterHandlerAssertionsOrCommitAndApplyRejectsAtomically(boolean async) {
        fixture(async).givenCommands(new CreateProject(PROJECT))
                .whenExecuting(fc -> Fluxzero.assertLegal(new ActivateProject(PROJECT, false)))
                .expectNoEvents().expectNoErrors()
                .expectThat(fc -> assertFalse(Fluxzero.loadModel(PROJECT).get().enabled()))
                .andThen().whenExecuting(fc -> Fluxzero.assertAndApply(new ActivateProject(PROJECT, false)))
                .expectExceptionalResult(Rejected.class).expectNoEvents()
                .expectThat(fc -> {
                    assertFalse(Fluxzero.loadModel(PROJECT).get().enabled());
                    assertTrue(Fluxzero.loadGraph(PROJECT, ProjectStatus.class).isEmpty());
                }).andThen().whenExecuting(fc -> Fluxzero.assertAndApply(new ActivateProject(PROJECT, true)))
                .expectNoErrors()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadModel(PROJECT).get().enabled());
                    assertTrue(Fluxzero.loadModel(PROJECT, ProjectStatus.class).get().online());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void orchestrationRecordsFunctionalRejectionInASeparateCommand(boolean async) {
        fixture(async, new LaunchHandler()).givenCommands(new CreateProject(PROJECT))
                .whenCommand(new LaunchProject(PROJECT, false)).expectResult(false)
                .expectThat(fc -> {
                    assertFalse(Fluxzero.loadModel(PROJECT).get().enabled());
                    assertEquals(Outcome.REJECTED, Fluxzero.loadModel(PROJECT, LaunchReport.class).get().outcome());
                }).andThen().whenCommand(new LaunchProject(PROJECT, true)).expectResult(true)
                .expectNoErrors()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadModel(PROJECT).get().enabled());
                    assertEquals(Outcome.STARTED, Fluxzero.loadModel(PROJECT, LaunchReport.class).get().outcome());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void technicalFailureIsNotReclassifiedAsFunctionalRejection(boolean async) {
        var builder = DefaultFluxzero.builder().configureAutomaticModelHandling(AutomaticModelHandling.DISABLED);
        (async ? TestFixture.createAsync(builder, new LaunchHandler(), new UnavailableActivation())
                : TestFixture.create(builder, new LaunchHandler(), new UnavailableActivation()))
                .whenCommand(new LaunchProject(PROJECT, true)).expectExceptionalResult(TechnicalException.class)
                .expectThat(fc -> assertTrue(Fluxzero.loadGraph(PROJECT, LaunchReport.class).isEmpty()));
    }

    private static JsonNode json(Object value) { return SERIALIZER.getObjectMapper().valueToTree(value); }

    @Model record Project(@EntityId ProjectId projectId, boolean enabled) {}
    static class ProjectId extends Id<Project> { ProjectId(String value) { super(value, "project-"); } }
    @Model record ProjectStatus(@EntityId(prefix = "status-") @Parent(pathInParent = "status") ProjectId projectId,
                                boolean online) {}
    record CreateProject(ProjectId projectId) {
        @Apply Project apply() { return new Project(projectId, false); }
    }
    record PutStatus(ProjectId projectId, boolean online) {
        @Apply ProjectStatus apply(Project parent, @Nullable ProjectStatus current) {
            return new ProjectStatus(projectId, online);
        }
    }
    record RemoveStatus(ProjectId projectId) {
        @Apply ProjectStatus apply(ProjectStatus current) { return null; }
    }
    record RemoveProject(ProjectId projectId) {
        @Apply Project apply(Project current) { return null; }
    }

    @Model record Space(@EntityId SpaceId spaceId, DeviceId primaryLightId) {}
    static class SpaceId extends Id<Space> { SpaceId(String value) { super(value); } }
    static class DeviceId extends Id<Device> { DeviceId(String value) { super(value); } }
    @Model record Device(@EntityId DeviceId deviceId, @Parent(pathInParent = "devices") SpaceId spaceId) {
        @GraphProperty boolean primary(Graph<Space> space) {
            return space.get() != null && deviceId.equals(space.get().primaryLightId());
        }
    }
    record CreateSpace(SpaceId spaceId) {
        @Apply Space apply() { return new Space(spaceId, null); }
    }
    record AddDevice(DeviceId deviceId, SpaceId spaceId) {
        @Apply Device apply(Space space) { return new Device(deviceId, spaceId); }
    }
    record SelectPrimary(SpaceId spaceId, @Nullable DeviceId primaryLightId) {
        @AssertLegal void validSelection(Graph<Space> space) {
            if (primaryLightId != null && space.children(Device.class).stream()
                    .noneMatch(device -> device.get().deviceId().equals(primaryLightId))) {
                throw new Rejected("Choose a device in this space");
            }
        }
        @Apply Space apply(Space space) { return new Space(spaceId, primaryLightId); }
    }
    static void requireUnselected(Graph<Device> device) {
        Device current = device.optional().orElseThrow(() -> new Rejected("Device not found"));
        Space parent = device.parent(Space.class).flatMap(Graph::optional)
                .orElseThrow(() -> new Rejected("Device has no current space"));
        if (current.deviceId().equals(parent.primaryLightId())) {
            throw new Rejected("Clear or replace the primary selection first");
        }
    }
    record MoveDevice(DeviceId deviceId, SpaceId newSpaceId) {
        @AssertLegal void legal(Graph<Device> device, Space destination) { requireUnselected(device); }
        @Apply Device apply(Device current) { return new Device(deviceId, newSpaceId); }
    }
    record DeleteDevice(DeviceId deviceId) {
        @AssertLegal void legal(Graph<Device> device) { requireUnselected(device); }
        @Apply Device apply(Device current) { return null; }
    }

    record ActivateProject(ProjectId projectId, boolean operational) {
        @Apply Project enable(Project current) { return new Project(projectId, true); }
        @Apply ProjectStatus status(@Nullable ProjectStatus current) { return new ProjectStatus(projectId, operational); }
        @AssertLegal(afterHandler = true) void ready(ProjectStatus status) {
            if (!status.online()) { throw new Rejected("Project is not operational"); }
        }
    }
    static class Rejected extends FunctionalException { public Rejected(String message) { super(message); } }
    enum Outcome { STARTED, REJECTED }
    @Model record LaunchReport(@EntityId(prefix = "launch-") @Parent ProjectId projectId, Outcome outcome) {}
    record RecordLaunchOutcome(ProjectId projectId, Outcome outcome) {
        @Apply LaunchReport apply(@Nullable LaunchReport current) { return new LaunchReport(projectId, outcome); }
    }
    record LaunchProject(ProjectId projectId, boolean operational) {}
    @Consumer(name = "project-launches")
    static class LaunchHandler {
        @HandleCommand boolean handle(LaunchProject request) {
            try {
                Fluxzero.sendCommandAndWait(new ActivateProject(request.projectId(), request.operational()));
            } catch (FunctionalException rejection) {
                Fluxzero.sendCommandAndWait(new RecordLaunchOutcome(request.projectId(), Outcome.REJECTED));
                return false;
            }
            Fluxzero.sendCommandAndWait(new RecordLaunchOutcome(request.projectId(), Outcome.STARTED));
            return true;
        }
    }
    static class UnavailableActivation {
        @HandleCommand void handle(ActivateProject command) { throw new TechnicalException("Unavailable"); }
    }
}
