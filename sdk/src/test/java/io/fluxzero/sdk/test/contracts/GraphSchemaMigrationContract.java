/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.test.contracts;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Shared schema-migration behavior for local, TestServer and JDBC Runtime clients. */
public abstract class GraphSchemaMigrationContract extends GraphReindexContract {
    protected abstract Client client(String namespace);

    @ParameterizedTest
    @EnumSource(GraphProjectionMode.class)
    void returnedGraphMigratesCurrentRootAndChildSources(GraphProjectionMode mode) {
        Class<?> rootType = switch (mode) {
            case NONE -> SchemaLive.class;
            case ASYNC -> SchemaAsync.class;
            case AWAIT -> SchemaAwait.class;
        };
        var serializer = new JacksonSerializer(List.of(new Casters()));
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().replaceSerializer(serializer)
                .build(client("schema-contract-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                ((DefaultModelRepository) fc.modelRepository()).configureModelTypes(
                        () -> List.of(SchemaRoot.class, rootType, SchemaChild.class));
                Object handler = switch (mode) {
                    case NONE -> new LiveMigration();
                    case ASYNC -> new AsyncMigration();
                    case AWAIT -> new AwaitMigration();
                };
                var registration = fc.registerHandlers(handler);
                try {
                    String rootCollection = EntityMetadata.of(rootType).modelSourceDocumentCollection("").orElseThrow();
                    String childCollection = EntityMetadata.of(SchemaChild.class).modelSourceDocumentCollection("").orElseThrow();
                    var root = legacy(serializer, rootType, "root", rootCollection, Map.of("id", "root", "name", "root value"));
                    var child = legacy(serializer, SchemaChild.class, "child", childCollection,
                            Map.of("id", "child", "parent", "root", "name", "child value"));
                    var rootTarget = ModelCommitTarget.builder().modelId("root").modelType(rootType.getSimpleName())
                            .expectedSequenceNumber(-1L).updateState(true).updateRelationships(true)
                            .document(new ModelDocumentMutation(rootCollection, root)).relationships(List.of()).build();
                    var childTarget = ModelCommitTarget.builder().modelId("child").modelType("SchemaChild")
                            .expectedSequenceNumber(-1L).updateState(true).updateRelationships(true)
                            .document(new ModelDocumentMutation(childCollection, child))
                            .relationships(List.of(ModelRelationship.builder().parentId("root")
                                    .parentType(rootType.getSimpleName()).path("children").build())).build();
                    var committed = fc.client().getEventStoreClient().commitModels(new CommitModels("seed", -1L,
                            List.of("root", "child"), List.of(new ModelCommitStep(null, false, List.of(rootTarget, childTarget))),
                            ModelConflictPolicy.RETRY, Guarantee.STORED, false)).join();
                    assertTrue(committed.getConflicts().isEmpty());
                    io.fluxzero.common.TimingUtils.retryOnFailure(() -> {
                        var currentRoot = fc.client().getSearchClient().fetchModelDocument(new GetDocument("root", rootCollection, true, true));
                        var currentChild = fc.client().getSearchClient().fetchModelDocument(new GetDocument("child", childCollection, true, true));
                        if (currentRoot.getDocument().getDocument().getRevision() != 1
                                || currentChild.getDocument().getDocument().getRevision() != 1
                                || io.fluxzero.sdk.Fluxzero.searchGraph(rootType).match("child value", "children/details/name").fetchAll().size() != 1) {
                            throw new IllegalStateException("Node migration and affected Graph have not caught up");
                        }
                        assertEquals(-1L, currentRoot.getModelHead().getSequenceNumber());
                        assertEquals(-1L, currentChild.getModelHead().getSequenceNumber());
                        assertEquals(committed.getUpdates().getLast().getStateIndex(), currentChild.getModelHead().getStateIndex());
                        return null;
                    }, io.fluxzero.common.RetryConfiguration.builder().maxRetries(1000)
                            .delay(java.time.Duration.ofMillis(10)).exceptionLogger(status -> { }).build());
                    assertTrue(io.fluxzero.sdk.Fluxzero.searchGraph(rootType).match("child value", "children/name").fetchAll().isEmpty());
                } finally { registration.cancel(); }
                return null;
            });
        }
    }

    private static SerializedDocument legacy(JacksonSerializer serializer, Class<?> type, String id,
                                             String collection, Map<String, String> json) {
        return new SerializedDocument(serializer.toDocument(json, id, collection, null, null, Metadata.empty())
                .deserializeDocument().toBuilder().type(type.getName()).revision(0).build());
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT)
    public interface SchemaRoot { @EntityId String id(); }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT)
    @Revision(1)
    public record SchemaLive(@EntityId String id, Details details) implements SchemaRoot { }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC))
    @Revision(1)
    public record SchemaAsync(@EntityId String id, Details details) implements SchemaRoot { }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    @Revision(1)
    public record SchemaAwait(@EntityId String id, Details details) implements SchemaRoot { }
    @Model(persistence = ModelPersistence.DOCUMENT)
    @Revision(1)
    public record SchemaChild(@EntityId String id, @Parent(value = SchemaRoot.class, pathInParent = "children") String parent,
                              Details details) { }
    public record Details(String name) { }
    public static class LiveMigration { @HandleDocument Graph<SchemaLive> migrate(Graph<SchemaLive> graph) { return graph; } }
    public static class AsyncMigration { @HandleDocument Graph<SchemaAsync> migrate(Graph<SchemaAsync> graph) { return graph; } }
    public static class AwaitMigration { @HandleDocument Graph<SchemaAwait> migrate(Graph<SchemaAwait> graph) { return graph; } }
    public static class Casters {
        @Upcast(type = "io.fluxzero.sdk.test.contracts.GraphSchemaMigrationContract$SchemaLive", revision = 0)
        ObjectNode live(ObjectNode value) { return move(value); }
        @Upcast(type = "io.fluxzero.sdk.test.contracts.GraphSchemaMigrationContract$SchemaAsync", revision = 0)
        ObjectNode async(ObjectNode value) { return move(value); }
        @Upcast(type = "io.fluxzero.sdk.test.contracts.GraphSchemaMigrationContract$SchemaAwait", revision = 0)
        ObjectNode await(ObjectNode value) { return move(value); }
        @Upcast(type = "io.fluxzero.sdk.test.contracts.GraphSchemaMigrationContract$SchemaChild", revision = 0)
        ObjectNode child(ObjectNode value) { return move(value); }
        private static ObjectNode move(ObjectNode value) {
            var name = value.remove("name");
            value.putObject("details").set("name", name);
            return value;
        }
    }
}
