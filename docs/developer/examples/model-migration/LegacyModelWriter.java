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

import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import static io.fluxzero.sdk.modeling.GraphProjectionCompletion.AWAIT;
import static io.fluxzero.sdk.modeling.ModelPersistence.DOCUMENT;
import static io.fluxzero.sdk.modeling.ModelPersistence.EVENT_SOURCED;

/** Run in a separate JVM with the writer SDK's dependency classpath, against an isolated retained test store. */
public class LegacyModelWriter {
    public static void main(String[] args) {
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl(args[0]).namespace(args[1]).name("migration-writer").build());
        try (var app = DefaultFluxzero.builder().build(client)) {
            app.registerHandlers(Project.class, Note.class);
            app.execute(fc -> {
                Fluxzero.assertAndApply(new CreateProject("project-1", "Legacy name"));
                Fluxzero.assertAndApply(new RenameProject("project-1", "Renamed"));
                Fluxzero.assertAndApply(new CreateNote("note-1", "project-1"));
            });
        }
    }

    @Model(name = "migration-project", persistence = {EVENT_SOURCED, DOCUMENT},
            document = @DocumentProjection(collection = "migration-projects"), materializeGraph = true,
            graphProjection = @GraphProjection(collection = "migration-project-graphs", completion = AWAIT))
    @Revision(1)
    record Project(@EntityId String projectId, String name) {}

    @Revision(1)
    record CreateProject(String projectId, String name) {
        @Apply Project apply() { return new Project(projectId, name); }
    }

    record RenameProject(String projectId, String name) {
        @Apply Project apply(Project current) { return new Project(projectId, name); }
    }

    @Model(name = "migration-note")
    record Note(@EntityId String noteId, @Parent(value = Project.class, pathInParent = "notes") String projectId) {}

    record CreateNote(String noteId, String projectId) {
        @Apply Note apply() { return new Note(noteId, projectId); }
    }
}
