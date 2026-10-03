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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.search.GetDocumentResult;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.RewriteModelSourceDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.modeling.ModelDocumentProof;
import io.fluxzero.common.search.ModelGraphDocumentStitcher;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.sdk.persisting.search.ModelSourceDocumentMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GraphSourceDocumentMigrationTest {
    enum Scenario { STALE_GRAPH, LOST_CAS, DELETED, ALREADY_MIGRATED, UNVERIFIED, CONTENTION, MUTATED }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    @SuppressWarnings("unchecked")
    void graphMigrationUsesOnlyVerifiedCurrentState(Scenario scenario) {
        TestFixture.create().registerCasters(new MoveName()).whenExecuting(fc -> {
            var serializer = fc.documentStore().getSerializer();
            var method = GraphRewrite.class.getDeclaredMethod("rewrite", Graph.class);
            String collection = "$modelGraphComponents/SourceProject";
            var old = legacy(serializer, "handled old value", collection);
            var projected = ModelGraphDocumentStitcher.stitch(List.of(old), List.of(), Map.of("project", old),
                    Map.of("project", "SourceProject"), ModelGraphComposition.builder().build(), 0).getFirst();
            var message = fc.serializer().deserializeMessages(Stream.of(new SerializedMessage(projected.getDocument(),
                    projected.getMetadata(), "project", 0L)), MessageType.DOCUMENT, "SourceProject-graphs")
                    .findFirst().orElseThrow();
            Graph<Project> graph = mock(Graph.class);
            when(graph.id()).thenReturn("project");
            when(graph.type()).thenReturn(Project.class);
            when(graph.isRoot()).thenReturn(true);
            when(graph.stateIndex()).thenReturn(0L);
            when(graph.children()).thenReturn(List.of());
            when(graph.get()).thenReturn(new Project("project", new Details("handled old value")));
            GraphSourceDocumentMigration.capture(message, method, graph, serializer);

            AtomicReference<SerializedDocument> current = new AtomicReference<>(legacy(serializer, "latest business value", collection));
            AtomicReference<ModelHeadState> head = new AtomicReference<>(new ModelHeadState("project", "SourceProject", 2, 5, true, false));
            AtomicInteger writes = new AtomicInteger();
            SearchClient client = mock(SearchClient.class);
            when(client.fetchModelDocument(any())).thenAnswer(invocation -> new GetDocumentResult(
                    0, current.get(), head.get(), scenario != Scenario.UNVERIFIED));
            when(client.rewriteModelSourceDocument(any())).thenAnswer(invocation -> {
                RewriteModelSourceDocument request = invocation.getArgument(0);
                int attempt = writes.incrementAndGet();
                assertEquals(head.get(), request.getExpectedHead());
                assertEquals(ModelDocumentProof.of(current.get(), head.get()), request.getExpectedProof());
                if (scenario == Scenario.LOST_CAS && attempt == 1) {
                    current.set(legacy(serializer, "concurrent business value", collection));
                    head.set(new ModelHeadState("project", "SourceProject", 3, 6, true, false));
                } else if (scenario != Scenario.CONTENTION) {
                    current.set(request.getDocument());
                }
                return CompletableFuture.completedFuture(null);
            });
            switch (scenario) {
                case DELETED -> { current.set(null); head.set(null); }
                case ALREADY_MIGRATED -> current.set(serializer.toDocument(
                        new Project("project", new Details("latest business value")), "project", collection, null, null));
                case MUTATED -> when(graph.get()).thenReturn(new Project("project", new Details("illegal mutation")));
                default -> { }
            }
            Runnable finish = () -> GraphSourceDocumentMigration.finish(message, method, graph, serializer, client);
            switch (scenario) {
                case UNVERIFIED -> assertThrows(IllegalStateException.class, finish::run);
                case CONTENTION -> { assertThrows(ConcurrentModificationException.class, finish::run); assertEquals(3, writes.get()); }
                case MUTATED -> { assertThrows(IllegalArgumentException.class, finish::run); verifyNoInteractions(client); }
                default -> {
                    finish.run();
                    if (scenario == Scenario.DELETED || scenario == Scenario.ALREADY_MIGRATED) {
                        assertEquals(0, writes.get());
                    } else {
                        assertEquals(scenario == Scenario.LOST_CAS ? 2 : 1, writes.get());
                        assertEquals(1, current.get().getDocument().getRevision());
                        assertEquals(scenario == Scenario.LOST_CAS ? "concurrent business value" : "latest business value",
                                ((Project) serializer.fromDocument(current.get(), Project.class)).details().name());
                    }
                }
            }
        }).expectNoErrors().expectNoEvents();
    }

    private static SerializedDocument legacy(DocumentSerializer serializer, String name, String collection) {
        var raw = serializer.toDocument(Map.of("id", "project", "name", name), "project", collection, null, null, Metadata.empty());
        return new SerializedDocument(raw.deserializeDocument().toBuilder().type(Project.class.getName()).revision(0).build());
    }
}
