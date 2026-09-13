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

import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.exception.TechnicalException
import io.fluxzero.sdk.configuration.DefaultFluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException
import io.fluxzero.sdk.tracking.handling.authentication.User
import io.fluxzero.sdk.tracking.handling.validation.ValidationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exercises the Kotlin Model-state documentation's complete details/command example. */
class ModelDetailsDocumentationKotlinTest {
    private val projectId = ProjectId("details-project")
    private val owner = Sender(UserId("owner"))
    private val details = ProjectDetails("Launch", "Keep this description")

    private fun fixture(async: Boolean): TestFixture {
        val builder = DefaultFluxzero.builder().registerUserProvider(object : AbstractUserProvider(Sender::class.java) {
            override fun getUserById(id: Any): User = if (id is Sender) id else Sender(UserId(id.toString()))
            override fun getSystemUser(): User = owner
        })
        return if (async) TestFixture.createAsync(builder) else TestFixture.create(builder)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun createsWithValidatedDetails(async: Boolean) {
        fixture(async).whenCommandByUser(owner, CreateProject(projectId, details))
            .expectSuccessfulResult()
            .expectThat { assertEquals(Project(projectId, details, owner.userId()), Fluxzero.loadModel(projectId).get()) }
            .expectNoErrors()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun renamePreservesOtherDetailsAndOwnership(async: Boolean) {
        val rename = RenameProject(projectId, "Release")
        fixture(async).givenCommandsByUser(owner, CreateProject(projectId, details))
            .whenCommandByUser(owner, rename)
            .expectSuccessfulResult().expectEvents(rename)
            .expectThat {
                assertEquals(Project(projectId, details.copy(name = "Release"), owner.userId()),
                             Fluxzero.loadModel(projectId).get())
            }.expectNoErrors()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun rejectsInvalidNestedCreationDetails(async: Boolean) {
        for (invalid in listOf(ProjectDetails("", null), ProjectDetails("Launch", "x".repeat(501)))) {
            fixture(async).whenCommandByUser(owner, CreateProject(projectId, invalid))
                .verifyExceptionalResult<Throwable> { error ->
                    assertInputRejected(error, async && invalid.name.isBlank())
                }.expectNoEvents()
                .expectThat { assertNull(Fluxzero.loadModel(projectId).get()) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun invalidRenameDoesNotChangeState(async: Boolean) {
        fixture(async).givenCommandsByUser(owner, CreateProject(projectId, details))
            .whenCommandByUser(owner, RenameProject(projectId, ""))
            .verifyExceptionalResult<Throwable> { error -> assertInputRejected(error, async) }.expectNoEvents()
            .expectThat { assertEquals(details, Fluxzero.loadModel(projectId).get().details) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun unauthorizedRenameDoesNotChangeState(async: Boolean) {
        fixture(async).givenCommandsByUser(owner, CreateProject(projectId, details))
            .whenCommandByUser(Sender(UserId("other")), RenameProject(projectId, "Release"))
            .expectExceptionalResult(UnauthorizedException::class.java).expectNoEvents()
            .expectThat { assertEquals(Project(projectId, details, owner.userId()), Fluxzero.loadModel(projectId).get()) }
    }

    private fun assertInputRejected(error: Throwable, failsDuringDeserialization: Boolean) {
        if (failsDuringDeserialization) {
            // Serialized blank strings normalize to null before Kotlin's non-null constructor can accept them.
            assertIs<TechnicalException>(error)
            assertTrue(error.message.orEmpty().contains("DeserializationException"))
        } else {
            assertIs<ValidationException>(error)
        }
    }

    @Model
    data class Project(
        @EntityId val projectId: ProjectId,
        val details: ProjectDetails,
        val ownerId: UserId
    )

    data class ProjectDetails(
        @field:NotBlank val name: String,
        @field:Size(max = 500) val description: String?
    )

    data class CreateProject(
        val projectId: ProjectId,
        @field:Valid val details: ProjectDetails
    ) {
        @Apply
        fun apply(sender: Sender) = Project(projectId, details, sender.userId())
    }

    data class RenameProject(
        val projectId: ProjectId,
        @field:NotBlank val name: String
    ) {
        @AssertLegal
        fun assertOwner(project: Project, sender: Sender) {
            if (project.ownerId != sender.userId()) {
                throw UnauthorizedException("Not allowed to rename project")
            }
        }

        @Apply
        fun apply(project: Project) =
            project.copy(details = project.details.copy(name = name))
    }

    class ProjectId(value: String) : Id<Project>(value)
    class UserId(value: String) : Id<Sender>(value)

    data class Sender(val identifier: UserId) : User {
        fun userId() = identifier
        override fun id() = identifier.toString()
        override fun hasRole(role: String) = false
    }
}
