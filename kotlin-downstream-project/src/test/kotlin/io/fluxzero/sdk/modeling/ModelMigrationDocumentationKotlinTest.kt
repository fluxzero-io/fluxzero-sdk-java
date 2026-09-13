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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.fluxzero.common.serialization.Revision
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.serialization.casting.Upcast
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.scheduling.Schedule
import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.function.Predicate
import kotlin.test.assertEquals

class ModelMigrationDocumentationKotlinTest {
    private val resource = "/model-migration/create-project-rev1.json"

    @Test
    fun upcastPreservesIdentityAndName() {
        TestFixture.create().registerCasters(ProjectUpcaster())
            .whenUpcasting<CreateProject>(resource)
            .expectResult(CreateProject("project-1", ProjectDetails("Legacy name")))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun reconstructsModelEventsAndChecksActiveCommands(async: Boolean) {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val deadline = start.plusSeconds(3600)
        (if (async) TestFixture.createAsync() else TestFixture.create())
            .atFixedTime(start).registerCasters(ProjectUpcaster())
            .givenModelEvents("project-1", Project::class.java, resource, RenameProject("project-1", "Renamed"))
            .givenScheduledCommands(Schedule("run", "project-reminder", deadline))
            .whenExecuting {
                val model = Fluxzero.loadModel("project-1", Project::class.java)
                assertEquals("Renamed", model.get().details.name)
                assertEquals("Legacy name", model.previous().get().details.name)
            }.expectOnlyScheduledCommands()
            .expectOnlyActiveScheduledCommands(Predicate<Schedule> {
                it.scheduleId == "project-reminder" && it.deadline == deadline && it.getPayload<Any>() == "run"
            }).expectNoErrors()
    }

    @Model @Revision(2)
    data class Project(@EntityId val projectId: String, val details: ProjectDetails)
    data class ProjectDetails(val name: String)
    @Revision(2)
    data class CreateProject(val projectId: String, val details: ProjectDetails) {
        @Apply fun apply() = Project(projectId, details)
    }
    data class RenameProject(val projectId: String, val name: String) {
        @Apply fun apply(current: Project) = current.copy(details = current.details.copy(name = name))
    }
    class ProjectUpcaster {
        @Upcast(type = "io.fluxzero.sdk.modeling.ModelMigrationDocumentationKotlinTest\$CreateProject", revision = 1)
        fun fromRevision1(payload: ObjectNode): JsonNode {
            val name = payload.remove("name")
            require(name != null && name.isTextual) { "Revision 1 requires a textual name" }
            payload.putObject("details").set<JsonNode>("name", name)
            return payload
        }
    }
}
