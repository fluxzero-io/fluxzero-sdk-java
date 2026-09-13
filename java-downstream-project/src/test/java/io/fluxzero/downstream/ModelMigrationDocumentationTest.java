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
package io.fluxzero.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.testserver.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.fluxzero.sdk.modeling.GraphProjectionCompletion.AWAIT;
import static io.fluxzero.sdk.modeling.ModelPersistence.DOCUMENT;
import static io.fluxzero.sdk.modeling.ModelPersistence.EVENT_SOURCED;
import static org.junit.jupiter.api.Assertions.*;

/** Public-API recipe: a separate legacy-schema writer, retained storage, and a fresh reader without Given seeding. */
class ModelMigrationDocumentationTest {
    @TempDir Path temporary;

    @Test
    void readsLegacyStorageAndRematerializesOnlyTheDerivedGraph() throws Exception {
        var server = TestServer.startServer(0);
        String url = "ws://localhost:" + server.getURI().getPort();
        String namespace = "migration-" + UUID.randomUUID();
        try {
            // TestServer creates document update logs lazily. Activate this collection before the legacy writer;
            // otherwise a later consumer has no update log to replay, even though search can read its documents.
            var observer = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl(url).namespace(namespace).name("migration-log-initializer").build());
            try {
                observer.getTrackingClient(io.fluxzero.common.MessageType.DOCUMENT, "migration-project-graphs")
                        .readFromIndex(0, 1);
            } finally {
                observer.shutDown();
            }
            writeLegacyState(url, namespace);
            var builder = DefaultFluxzero.builder();
            builder.serializer().registerCasters(new ProjectUpcaster());
            // Returning a migrated Graph also requires a stable, content-independent type-rename mapping.
            builder.serializer().registerTypeAlias("LegacyModelWriter$Project", Project.class.getName());
            builder.serializer().registerTypeAlias("LegacyModelWriter$RenameProject", RenameProject.class.getName());
            builder.serializer().registerTypeAlias("LegacyModelWriter$Note", Note.class.getName());
            builder.serializer().registerTypeAlias("LegacyModelWriter$CreateNote", CreateNote.class.getName());
            var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl(url).namespace(namespace).name("migration-reader").build());
            var originalGraph = client.getSearchClient().fetch(new io.fluxzero.common.api.search.GetDocument(
                    "project-1", "migration-project-graphs")).orElseThrow();
            var fixture = TestFixture.createAsync(builder, client).consumerTimeout(Duration.ofSeconds(10));
            try (var reader = fixture.getFluxzero()) {
                fixture.whenExecuting(fc -> {
                    var model = Fluxzero.loadModel("project-1", Project.class);
                    assertEquals(new Project("project-1", new ProjectDetails("Renamed")), model.get());
                    assertEquals("Legacy name", model.previous().get().details().name());
                    assertEquals("Renamed", Fluxzero.loadCurrentModelState("project-1", Project.class).get().details().name());
                    assertEquals("note-1", Fluxzero.loadCurrentGraph("project-1", Project.class)
                            .children(Note.class).getFirst().get().noteId());
                    // Selection uses stored index paths; only returned values are upcast.
                    assertEquals(1L, Fluxzero.search(Project.class).match("Renamed", "name").count());
                    assertEquals(0L, Fluxzero.search(Project.class).match("Renamed", "details/name").count());
                    assertEquals(1L, Fluxzero.searchGraph(Project.class).match("Renamed", "name").count());
                    assertEquals(0L, Fluxzero.searchGraph(Project.class).match("Renamed", "details/name").count());
                }).expectNoErrors()
                        .andThen().whenExecuting(fc -> fc.registerHandlers(new RematerializeProjects()))
                        .expectNoErrors().expectThat(fc -> {
                            // External documents were written before this fixture existed, so its When trace cannot
                            // account for them. Await the observable migration result, not an arbitrary startup delay.
                            try {
                                TimingUtils.retryOnFailure(() -> {
                                    if (Fluxzero.searchGraph(Project.class).match("Renamed", "details/name").count() != 1) {
                                        throw new IllegalStateException("Graph migration has not caught up");
                                    }
                                    return null;
                                }, RetryConfiguration.builder().delay(Duration.ofMillis(25)).maxRetries(200)
                                        .exceptionLogger(status -> {}).build());
                            } catch (RuntimeException e) {
                                fc.client().getTrackingClient(io.fluxzero.common.MessageType.ERROR).readFromIndex(0, 10)
                                        .forEach(error -> {
                                            Object failure = fc.serializer().deserializeMessage(error,
                                                    io.fluxzero.common.MessageType.ERROR).getPayload();
                                            if (failure instanceof Throwable cause) { e.addSuppressed(cause); }
                                        });
                                throw e;
                            }
                            assertEquals(1L, Fluxzero.searchGraph(Project.class).match("Renamed", "details/name").count());
                            assertEquals(0L, Fluxzero.searchGraph(Project.class).match("Renamed", "name").count());
                            // Graph migration must not rewrite authoritative state, its proof, or historical events.
                            assertEquals(0L, Fluxzero.search(Project.class).match("Renamed", "details/name").count());
                            assertEquals("Renamed", Fluxzero.loadCurrentModelState("project-1", Project.class).get().details().name());
                            fc.cache().clear();
                            assertEquals("Legacy name", Fluxzero.loadModel("project-1", Project.class)
                                    .previous().get().details().name());
                            // A delayed migration must be acknowledged but must not overwrite the newer projection.
                            assertDoesNotThrow(() -> fc.client().getSearchClient().rewriteModelGraphDocument(originalGraph,
                                    originalGraph.getMetadata().get(io.fluxzero.common.search.ModelGraphDocumentManifest.METADATA_KEY),
                                    io.fluxzero.common.Guarantee.STORED).get(5, TimeUnit.SECONDS));
                            assertEquals(1L, Fluxzero.searchGraph(Project.class).match("Renamed", "details/name").count());
                        });
            }
        } finally {
            server.stop();
        }
    }

    private void writeLegacyState(String url, String namespace) throws Exception {
        Path source = Path.of("../docs/developer/examples/model-migration/LegacyModelWriter.java").toAbsolutePath();
        // Do not expose this reader's application classes/type index to the writer JVM.
        String defaultClasspath = Arrays.stream(System.getProperty("surefire.test.class.path").split(File.pathSeparator))
                .filter(p -> !Path.of(p).toAbsolutePath().startsWith(Path.of("target").toAbsolutePath()))
                .collect(Collectors.joining(File.pathSeparator));
        String writerClasspath = ApplicationProperties.getProperty("migration.writer.classpath", defaultClasspath);
        Path output = temporary.resolve("writer.log");
        Process writer = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--class-path", writerClasspath, source.toString(), url, namespace)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(writer.waitFor(30, TimeUnit.SECONDS), () -> "Legacy writer did not finish: " + readLog(output));
            assertEquals(0, writer.exitValue(), () -> readLog(output));
        } finally {
            if (writer.isAlive()) writer.destroyForcibly().waitFor();
        }
    }

    private String readLog(Path output) {
        try { return Files.readString(output); } catch (Exception e) { return e.toString(); }
    }

    @Model(name = "migration-project", persistence = {EVENT_SOURCED, DOCUMENT},
            document = @DocumentProjection(collection = "migration-projects"), materializeGraph = true,
            graphProjection = @GraphProjection(collection = "migration-project-graphs", completion = AWAIT))
    @Revision(2)
    record Project(@EntityId String projectId, ProjectDetails details) {}
    record ProjectDetails(String name) {}
    @Revision(2)
    record CreateProject(String projectId, ProjectDetails details) {
        @Apply Project apply() { return new Project(projectId, details); }
    }
    record RenameProject(String projectId, String name) {
        @Apply Project apply(Project current) { return new Project(projectId, new ProjectDetails(name)); }
    }
    @Model(name = "migration-note")
    record Note(@EntityId String noteId, @Parent(value = Project.class, pathInParent = "notes") String projectId) {}
    record CreateNote(String noteId, String projectId) {
        @Apply Note apply() { return new Note(noteId, projectId); }
    }

    static class ProjectUpcaster {
        @Upcast(type = "LegacyModelWriter$Project", revision = 1)
        Data<JsonNode> project(Data<ObjectNode> data) { return move(data, Project.class); }
        @Upcast(type = "LegacyModelWriter$CreateProject", revision = 1)
        Data<JsonNode> creation(Data<ObjectNode> data) { return move(data, CreateProject.class); }
        private Data<JsonNode> move(Data<ObjectNode> data, Class<?> target) {
            ObjectNode payload = data.getValue();
            JsonNode name = payload.remove("name");
            if (name == null || !name.isTextual()) {
                throw new IllegalArgumentException("Revision 1 requires a textual name");
            }
            payload.putObject("details").set("name", name);
            return data.<JsonNode>map(ignored -> payload).withType(target.getName()).withRevision(2);
        }
    }

    @Consumer(name = "migration-project-graph-revision-2", minIndex = 0)
    static class RematerializeProjects {
        @HandleDocument(modelGraph = Project.class)
        Graph<Project> migrate(Graph<Project> graph) { return graph; }
    }
}
