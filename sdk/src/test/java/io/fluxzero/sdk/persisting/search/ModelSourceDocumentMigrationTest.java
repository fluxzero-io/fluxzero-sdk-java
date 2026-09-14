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
package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelSourceDocumentMigrationTest {
    private static final String SOURCE = "$modelGraphComponents/SourceProject";
    private static final String TYPE = "io.fluxzero.sdk.persisting.search.ModelSourceDocumentMigrationTest$Project";

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void handlerReindexesOnlyTheInternalSourceWithoutAdvancingTheModel(boolean async, boolean randomEnvelope) {
        JacksonSerializer serializer = randomEnvelope ? new JacksonSerializer() {
            @Override
            public SerializedDocument toDocument(Object value, String id, String collection,
                                                  java.time.Instant timestamp, java.time.Instant end, Metadata metadata) {
                return super.toDocument(value, id, collection, timestamp, end,
                                        metadata.with("nonce", java.util.UUID.randomUUID().toString()));
            }
        } : new JacksonSerializer();
        TestFixture fixture = TestFixture.create(io.fluxzero.sdk.configuration.DefaultFluxzero.builder()
                .replaceSerializer(serializer), new Rewrite()).registerCasters(new MoveName());
        if (async) { fixture = fixture.async(); }
        fixture.whenExecuting(ModelSourceDocumentMigrationTest::seed)
                .expectNoErrors().expectNoEvents().expectThat(fc -> {
                    var stored = fc.client().getSearchClient().fetchModelDocument(new GetDocument("project", SOURCE, true, true));
                    assertTrue(stored.isModelStateVerified());
                    assertEquals(-1, stored.getModelHead().getSequenceNumber());
                    assertEquals(1, stored.getDocument().getDocument().getRevision());
                    assertEquals(new Project("project", new Details("Legacy name")),
                                 Fluxzero.loadCurrentModelState("project", Project.class).value());
                    assertEquals(1, Fluxzero.searchGraph(Project.class, true).match("Legacy name", "details/name").fetchAll().size());
                    assertTrue(Fluxzero.searchGraph(Project.class, true).match("Legacy name", "name").fetchAll().isEmpty());
                    assertEquals(1, Fluxzero.search(Project.class).match("Legacy name", "name").fetchAll().size());
                    assertTrue(Fluxzero.search(Project.class).match("Legacy name", "details/name").fetchAll().isEmpty());
                });
    }

    @org.junit.jupiter.api.Test
    void sourceHandlerUsesItsConsumerNamespaceInsteadOfTheApplicationNamespace() {
        TestFixture.createAsync(new ArchiveRewrite()).registerCasters(new MoveName())
                .whenExecuting(fc -> {
                    seed(fc);
                    seed(fc, fc.client().forNamespace("archive"));
                    io.fluxzero.common.TimingUtils.retryOnFailure(() -> {
                        if (fc.client().forNamespace("archive").getSearchClient().fetchModelDocument(
                                new GetDocument("project", SOURCE, true, true)).getDocument().getDocument().getRevision() != 1) {
                            throw new IllegalStateException("Archive consumer has not reindexed its source");
                        }
                        return null;
                    }, io.fluxzero.common.RetryConfiguration.builder().delay(java.time.Duration.ofMillis(5))
                            .maxRetries(400).exceptionLogger(status -> {}).build());
                }).expectNoErrors().expectThat(fc -> {
                    assertEquals(0, fc.client().getSearchClient().fetchModelDocument(
                            new GetDocument("project", SOURCE, true, true)).getDocument().getDocument().getRevision());
                    assertEquals(1, fc.client().forNamespace("archive").getSearchClient().fetchModelDocument(
                            new GetDocument("project", SOURCE, true, true)).getDocument().getDocument().getRevision());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentTrackingRegistrationRetainsUpdatesBeforeTheFirstRead(boolean registerTracking) {
        TestFixture.create().whenExecuting(fc -> {
            var archive = fc.client().forNamespace("archive");
            if (registerTracking) {
                archive.getTrackingClient(io.fluxzero.common.MessageType.DOCUMENT, SOURCE);
            }
            seed(fc, archive);
            assertEquals(0, archive.getSearchClient().fetchModelDocument(new GetDocument("project", SOURCE, true, true))
                    .getDocument().getDocument().getRevision());
            var store = (io.fluxzero.sdk.persisting.search.client.InMemorySearchStore) archive.getSearchClient();
            assertEquals(registerTracking ? 1 : 0, store.openStream(SOURCE, -1L, 10).count());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void businessChangesCannotBeCertifiedAsSchemaMigration(boolean async) {
        TestFixture fixture = TestFixture.create().registerCasters(new MoveName());
        if (async) { fixture = fixture.async(); }
        fixture.whenExecuting(fc -> {
                    seed(fc);
                    var stored = fc.client().getSearchClient().fetchModelDocument(new GetDocument("project", SOURCE, true, true));
                    var raw = new io.fluxzero.common.api.SerializedMessage(stored.getDocument().getDocument(), Metadata.empty(), "project", 0L);
                    var message = fc.serializer().deserializeMessages(java.util.stream.Stream.of(raw),
                            io.fluxzero.common.MessageType.DOCUMENT, SOURCE).findFirst().orElseThrow();
                    var migration = ModelSourceDocumentMigration.prepare(message, Project.class, SOURCE,
                            fc.documentStore().getSerializer(), fc.client().getSearchClient());
                    assertThrows(IllegalArgumentException.class,
                                 () -> migration.finish(new Project("project", new Details("changed"))));
                }).expectNoErrors().expectThat(fc -> {
                    var stored = fc.client().getSearchClient().fetchModelDocument(new GetDocument("project", SOURCE, true, true));
                    assertEquals(0, stored.getDocument().getDocument().getRevision());
                    assertEquals("Legacy name", Fluxzero.loadCurrentModelState("project", Project.class).value().details().name());
                });
    }

    private static void seed(Fluxzero fc) {
        seed(fc, fc.client());
    }

    private static void seed(Fluxzero fc, io.fluxzero.sdk.configuration.client.Client client) {
        var serializer = (JacksonSerializer) fc.documentStore().getSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode().put("id", "project").put("name", "Legacy name");
        SerializedDocument source = new SerializedDocument(serializer.toDocument(json, "project", SOURCE, null, null, Metadata.empty())
                .deserializeDocument().toBuilder().type(TYPE).revision(0).build());
        var target = ModelCommitTarget.builder().modelId("project").modelType("SourceProject").expectedSequenceNumber(-1L)
                .updateState(true).document(new ModelDocumentMutation(SOURCE, source))
                .documentProjection(new ModelDocumentMutation("SourceProject", source.withCollection("SourceProject")))
                .relationships(List.of()).aliases(List.of()).build();
        var commit = new CommitModels("seed-source", -1L, List.of("project"),
                List.of(new ModelCommitStep(null, false, List.of(target))), ModelConflictPolicy.RETRY, Guarantee.STORED, false);
        client.getEventStoreClient().commitModels(new CommitModelsWithDocumentProjections(commit)).join();
    }

    @Model(name = "SourceProject", persistence = ModelPersistence.DOCUMENT)
    @Revision(1)
    record Project(@EntityId String id, Details details) {}
    record Details(String name) {}

    static class MoveName {
        @Upcast(type = TYPE, revision = 0)
        JsonNode upcast(ObjectNode value) {
            JsonNode name = value.remove("name");
            if (name == null) { throw new IllegalArgumentException("Missing legacy name"); }
            value.putObject("details").set("name", name);
            return value;
        }
    }

    static class Rewrite {
        @HandleDocument(modelState = Project.class)
        Project rewrite(Project project) { return project; }
    }

    @io.fluxzero.sdk.tracking.Consumer(name = "archive-source", namespace = "archive", minIndex = 0)
    static class ArchiveRewrite {
        @HandleDocument(modelState = Project.class)
        Project rewrite(Project project) { return project; }
    }
}
