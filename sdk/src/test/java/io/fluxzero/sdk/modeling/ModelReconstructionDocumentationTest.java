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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ModelReconstructionDocumentationTest {
    static final String RESOURCE = "/model-migration/create-project-rev1.json";

    @Test
    void firstExampleRegistersCasterAndPreservesNameAndIdentity() {
        TestFixture.create().registerCasters(new ProjectUpcaster())
                .whenUpcasting(RESOURCE)
                .expectResult(new CreateProject("project-1", new ProjectDetails("Legacy name")));
    }

    @Test
    void casterIsNotAHandlerComponent() {
        TestFixture.create(new ProjectUpcaster()).whenUpcasting(RESOURCE)
                .expectResult(new CreateProject("project-1", null));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void givenModelEventsAppliesSerializedHistoryAndRetainsPreviousValues(boolean async) {
        (async ? TestFixture.createAsync() : TestFixture.create())
                .registerCasters(new ProjectUpcaster())
                .givenModelEvents("project-1", Project.class, RESOURCE, new RenameProject("project-1", "Renamed"))
                .whenExecuting(fc -> {
                    var model = Fluxzero.loadModel("project-1", Project.class);
                    assertEquals(new Project("project-1", new ProjectDetails("Renamed")), model.get());
                    assertEquals("Legacy name", model.previous().get().details().name());
                }).expectNoEvents().expectNoErrors();
    }

    @Test
    void missingLegacyNameFailsInsteadOfInventingOne() {
        ObjectNode payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        payload.put("projectId", "project-1");
        assertThrows(IllegalArgumentException.class, () -> new ProjectUpcaster().fromRevision1(payload));
    }

    @Model @Revision(2)
    record Project(@EntityId String projectId, ProjectDetails details) {}
    record ProjectDetails(String name) {}
    @Revision(2)
    record CreateProject(String projectId, ProjectDetails details) {
        @Apply Project apply() { return new Project(projectId, details); }
    }
    record RenameProject(String projectId, String name) {
        @Apply Project apply(Project current) { return new Project(projectId, new ProjectDetails(name)); }
    }
    static class ProjectUpcaster {
        @Upcast(type = "io.fluxzero.sdk.modeling.ModelReconstructionDocumentationTest$CreateProject", revision = 1)
        JsonNode fromRevision1(ObjectNode payload) {
            JsonNode name = payload.remove("name");
            if (name == null || !name.isTextual()) {
                throw new IllegalArgumentException("Revision 1 requires a textual name");
            }
            payload.putObject("details").set("name", name);
            return payload;
        }
    }
}
