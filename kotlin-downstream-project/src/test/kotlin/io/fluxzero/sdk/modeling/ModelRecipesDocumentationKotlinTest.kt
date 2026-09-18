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
package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.exception.FunctionalException
import io.fluxzero.sdk.common.exception.TechnicalException
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer
import io.fluxzero.sdk.configuration.DefaultFluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.HandleCommand
import io.fluxzero.sdk.tracking.Consumer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelRecipesDocumentationKotlinTest {
    private val projectId = ProjectId("example")
    private val left = SpaceId("left")
    private val right = SpaceId("right")
    private val light = DeviceId("light")
    private fun fixture(async: Boolean, vararg handlers: Any) =
        if (async) TestFixture.createAsync(*handlers) else TestFixture.create(*handlers)

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun companionUpsertLoadAndDeletion(async: Boolean) {
        fixture(async).givenCommands(CreateProject(projectId), PutStatus(projectId, false))
            .whenCommand(PutStatus(projectId, true)).expectSuccessfulResult().expectNoErrors()
            .expectThat {
                val status = Fluxzero.loadGraph(projectId, ProjectStatus::class.java)
                assertEquals("status-project-example", status.id())
                assertEquals(ProjectStatus(projectId, true), status.get())
                assertEquals(listOf(status.get()), Fluxzero.loadGraph(projectId)
                    .childModels("status", ProjectStatus::class.java))
                assertEquals(status.id(), status.current().id())
            }.andThen().whenCommand(RemoveStatus(projectId)).expectNoErrors()
            .expectThat {
                assertTrue(Fluxzero.loadGraph(projectId, ProjectStatus::class.java).isEmpty)
                assertTrue(Fluxzero.loadGraph(projectId).isPresent)
            }.andThen().whenCommand(PutStatus(projectId, true)).expectNoErrors()
            .andThen().whenCommand(RemoveProject(projectId)).expectNoErrors()
            .expectThat { assertTrue(Fluxzero.loadGraph(projectId, ProjectStatus::class.java).isEmpty) }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun derivedPropertiesUsePinnedStateAndMoveDeleteRespectSelection(async: Boolean) {
        fixture(async).givenCommands(CreateSpace(left), CreateSpace(right), AddDevice(light, left))
            .whenExecuting {
                val historical = Fluxzero.loadCurrentGraph(left)
                Fluxzero.assertAndApply(SelectPrimary(left, light))
                val selected = historical.current()
                val mapper = JacksonSerializer().objectMapper
                assertFalse(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(historical)
                    .path("devices").get(0).path("primary").asBoolean())
                assertTrue(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(selected)
                    .path("devices").get(0).path("primary").asBoolean())
                assertFalse(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    selected.childModels(Device::class.java).first()).has("primary"))
            }.expectNoErrors()
            .andThen().whenCommand(MoveDevice(light, right)).expectExceptionalResult(Rejected::class.java)
            .andThen().whenCommand(DeleteDevice(light)).expectExceptionalResult(Rejected::class.java)
            .andThen().whenCommand(SelectPrimary(left, null)).expectSuccessfulResult()
            .andThen().whenCommand(MoveDevice(light, right)).expectSuccessfulResult().expectNoErrors()
            .andThen().whenCommand(SelectPrimary(left, light)).expectExceptionalResult(Rejected::class.java)
            .andThen().whenCommand(DeleteDevice(light)).expectSuccessfulResult().expectNoErrors()
            .expectThat { assertTrue(Fluxzero.loadGraph(right).children(Device::class.java).isEmpty()) }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun preflightAndAtomicExecutionAreDifferent(async: Boolean) {
        fixture(async).givenCommands(CreateProject(projectId))
            .whenExecuting { Fluxzero.assertLegal(ActivateProject(projectId, false)) }
            .expectNoEvents().expectNoErrors()
            .expectThat { assertFalse(Fluxzero.loadModel(projectId).get().enabled) }
            .andThen().whenExecuting { Fluxzero.assertAndApply(ActivateProject(projectId, false)) }
            .expectExceptionalResult(Rejected::class.java).expectNoEvents()
            .expectThat {
                assertFalse(Fluxzero.loadModel(projectId).get().enabled)
                assertTrue(Fluxzero.loadGraph(projectId, ProjectStatus::class.java).isEmpty)
            }.andThen().whenExecuting { Fluxzero.assertAndApply(ActivateProject(projectId, true)) }
            .expectNoErrors()
            .expectThat {
                assertTrue(Fluxzero.loadModel(projectId).get().enabled)
                assertTrue(Fluxzero.loadModel(projectId, ProjectStatus::class.java).get().online)
            }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun orchestrationRecordsFunctionalButNotTechnicalFailures(async: Boolean) {
        fixture(async, LaunchHandler()).givenCommands(CreateProject(projectId))
            .whenCommand(LaunchProject(projectId, false)).expectResult(false)
            .expectThat {
                assertEquals(Outcome.REJECTED, Fluxzero.loadModel(projectId, LaunchReport::class.java).get().outcome)
                assertFalse(Fluxzero.loadModel(projectId).get().enabled)
            }
        val builder = DefaultFluxzero.builder().configureAutomaticModelHandling(AutomaticModelHandling.DISABLED)
        (if (async) TestFixture.createAsync(builder, LaunchHandler(), UnavailableActivation())
         else TestFixture.create(builder, LaunchHandler(), UnavailableActivation()))
            .whenCommand(LaunchProject(projectId, true)).expectExceptionalResult(TechnicalException::class.java)
            .expectThat { assertTrue(Fluxzero.loadGraph(projectId, LaunchReport::class.java).isEmpty) }
    }

    @Model data class Project(@EntityId val projectId: ProjectId, val enabled: Boolean)
    class ProjectId(value: String) : Id<Project>(value, "project-")
    @Model data class ProjectStatus(
        @EntityId(prefix = "status-") @Parent(pathInParent = "status") val projectId: ProjectId,
        val online: Boolean
    )
    data class CreateProject(val projectId: ProjectId) {
        @Apply fun apply() = Project(projectId, false)
    }
    data class PutStatus(val projectId: ProjectId, val online: Boolean) {
        @Apply fun apply(parent: Project, current: ProjectStatus?) = ProjectStatus(projectId, online)
    }
    data class RemoveStatus(val projectId: ProjectId) {
        @Apply fun apply(current: ProjectStatus): ProjectStatus? = null
    }
    data class RemoveProject(val projectId: ProjectId) {
        @Apply fun apply(current: Project): Project? = null
    }

    @Model data class Space(@EntityId val spaceId: SpaceId, val primaryLightId: DeviceId?)
    class SpaceId(value: String) : Id<Space>(value)
    class DeviceId(value: String) : Id<Device>(value)
    @Model data class Device(@EntityId val deviceId: DeviceId,
                            @Parent(pathInParent = "devices") val spaceId: SpaceId) {
        @GraphProperty fun primary(space: Graph<Space>) = deviceId == space.get()?.primaryLightId
    }
    data class CreateSpace(val spaceId: SpaceId) {
        @Apply fun apply() = Space(spaceId, null)
    }
    data class AddDevice(val deviceId: DeviceId, val spaceId: SpaceId) {
        @Apply fun apply(space: Space) = Device(deviceId, spaceId)
    }
    data class SelectPrimary(val spaceId: SpaceId, val primaryLightId: DeviceId?) {
        @AssertLegal fun validSelection(space: Graph<Space>) {
            if (primaryLightId != null && space.children(Device::class.java)
                    .none { it.get()?.deviceId == primaryLightId }) {
                throw Rejected("Choose a device in this space")
            }
        }
        @Apply fun apply(space: Space) = space.copy(primaryLightId = primaryLightId)
    }
    companion object {
        fun requireUnselected(device: Graph<Device>) {
            val current = device.get() ?: throw Rejected("Device not found")
            val parent = device.parent(Space::class.java).orElseThrow { Rejected("Device has no current space") }
                .get() ?: throw Rejected("Device has no current space")
            if (current.deviceId == parent.primaryLightId) {
                throw Rejected("Clear or replace the primary selection first")
            }
        }
    }
    data class MoveDevice(val deviceId: DeviceId, val newSpaceId: SpaceId) {
        @AssertLegal fun legal(device: Graph<Device>, destination: Space) = requireUnselected(device)
        @Apply fun apply(current: Device) = current.copy(spaceId = newSpaceId)
    }
    data class DeleteDevice(val deviceId: DeviceId) {
        @AssertLegal fun legal(device: Graph<Device>) = requireUnselected(device)
        @Apply fun apply(current: Device): Device? = null
    }

    data class ActivateProject(val projectId: ProjectId, val operational: Boolean) {
        @Apply fun enable(current: Project) = current.copy(enabled = true)
        @Apply fun status(current: ProjectStatus?) = ProjectStatus(projectId, operational)
        @AssertLegal(afterHandler = true) fun ready(status: ProjectStatus) {
            if (!status.online) throw Rejected("Project is not operational")
        }
    }
    class Rejected(message: String) : FunctionalException(message)
    enum class Outcome { STARTED, REJECTED }
    @Model data class LaunchReport(@EntityId(prefix = "launch-") @Parent val projectId: ProjectId,
                                   val outcome: Outcome)
    data class RecordLaunchOutcome(val projectId: ProjectId, val outcome: Outcome) {
        @Apply fun apply(current: LaunchReport?) = LaunchReport(projectId, outcome)
    }
    data class LaunchProject(val projectId: ProjectId, val operational: Boolean)
    @Consumer(name = "project-launches")
    class LaunchHandler {
        @HandleCommand fun handle(request: LaunchProject): Boolean {
            try {
                Fluxzero.sendCommandAndWait<Any?>(ActivateProject(request.projectId, request.operational))
            } catch (rejection: FunctionalException) {
                Fluxzero.sendCommandAndWait<Any?>(RecordLaunchOutcome(request.projectId, Outcome.REJECTED))
                return false
            }
            Fluxzero.sendCommandAndWait<Any?>(RecordLaunchOutcome(request.projectId, Outcome.STARTED))
            return true
        }
    }
    class UnavailableActivation {
        @HandleCommand fun handle(command: ActivateProject) { throw TechnicalException("Unavailable") }
    }
}
