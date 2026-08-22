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

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.search.Document.EntryType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySearchStoreModelMaterializationTest {

    private final InMemorySearchStore subject =
            new InMemorySearchStore(Duration.ofDays(1));

    @Test
    void modelDocumentFenceRejectsOlderAndEqualWritesIncludingAfterDelete() {
        materialize(10, document("first"));
        materialize(10, document("conflicting-equal"));
        materialize(9, null);

        assertEquals(
                "first",
                subject.fetch(new GetDocument("model-1", "models"))
                        .orElseThrow()
                        .getSummary());

        materialize(11, null);
        materialize(11, document("late-equal"));

        assertTrue(subject.fetch(
                new GetDocument("model-1", "models")).isEmpty());

        materialize(12, document("recreated"));

        assertEquals(
                "recreated",
                subject.fetch(new GetDocument("model-1", "models"))
                        .orElseThrow()
                        .getSummary());
        var version = subject.fetchModelDocument(
                new GetDocument("model-1", "models"));
        assertEquals(12L, version.getModelHead().getStateIndex());
        assertEquals("TestModel", version.getModelHead().getModelType());
        assertFalse(version.getModelHead().isDeleted());
    }

    @Test
    void acceptedModelDocumentsStillNotifyDocumentTrackers() {
        AtomicInteger notifications =
                new AtomicInteger();
        subject.registerMonitor(
                "models",
                messages -> notifications.addAndGet(
                        messages.size()));

        materialize(10, document("first"));
        materialize(10, document("ignored"));
        materialize(9, document("older"));
        materialize(11, document("second"));

        assertEquals(2, notifications.get());
    }

    @Test
    void localGraphProjectionHonorsPathOverridesAndStateFence() {
        String rootId = "root-1";
        String childId = "child-1";
        Map<String, String> collections =
                Map.of(
                        rootId, "roots",
                        childId,
                        ModelDocumentMutation
                                .GRAPH_COMPONENT_COLLECTION);
        InMemorySearchStore graphStore =
                new InMemorySearchStore(
                        Duration.ofDays(1), null,
                        (ignored, composition) ->
                                List.of(
                                        new ModelGraphEdge(
                                                childId, rootId,
                                                "Root", "children",
                                                1L, null, false)),
                        modelIds -> collections.entrySet()
                                .stream()
                                .filter(entry ->
                                                modelIds.contains(
                                                        entry.getKey()))
                                .collect(
                                        java.util.stream.Collectors
                                                .toUnmodifiableMap(
                                                        Map.Entry::getKey,
                                                        Map.Entry::getValue)));
        graphStore.index(
                        List.of(
                                structuredDocument(
                                        rootId, "roots",
                                        "root"),
                                structuredDocument(
                                        childId,
                                        ModelDocumentMutation
                                                .GRAPH_COMPONENT_COLLECTION,
                                        "first")),
                        STORED, false)
                .join();
        ModelGraphProjectionConfiguration configuration =
                new ModelGraphProjectionConfiguration(
                        "Root", "roots",
                        "rootGraphs",
                        ModelGraphComposition.builder()
                                .build(),
                        List.of(
                                new ModelGraphPathOverride(
                                        "children",
                                        "components")));

        graphStore.materializeModelGraphProjection(
                configuration,
                Set.of(rootId), 10L, false);
        assertEquals(
                "first",
                graphStore.fetch(
                                new GetDocument(
                                        rootId,
                                        "rootGraphs"))
                        .orElseThrow()
                        .deserializeDocument()
                        .getEntryAtPath(
                                "components/0/name")
                        .orElseThrow()
                        .getValue());

        materialize(
                graphStore, childId, 11L,
                new ModelDocumentMutation(
                        ModelDocumentMutation
                                .GRAPH_COMPONENT_COLLECTION,
                        structuredDocument(
                                childId,
                                ModelDocumentMutation
                                        .GRAPH_COMPONENT_COLLECTION,
                                "second")));
        graphStore.materializeModelGraphProjection(
                configuration,
                Set.of(rootId), 10L, false);
        assertEquals(
                "first",
                graphStore.fetch(
                                new GetDocument(
                                        rootId,
                                        "rootGraphs"))
                        .orElseThrow()
                        .deserializeDocument()
                        .getEntryAtPath(
                                "components/0/name")
                        .orElseThrow()
                        .getValue());

        graphStore.materializeModelGraphProjection(
                configuration,
                Set.of(rootId), 11L, false);
        assertEquals(
                "second",
                graphStore.fetch(
                                new GetDocument(
                                        rootId,
                                        "rootGraphs"))
                        .orElseThrow()
                        .deserializeDocument()
                        .getEntryAtPath(
                                "components/0/name")
                        .orElseThrow()
                        .getValue());
    }

    private void materialize(
            long stateIndex, SerializedDocument document) {
        materialize(
                subject, "model-1", stateIndex,
                new ModelDocumentMutation(
                        "models", document));
    }

    private static void materialize(
            InMemorySearchStore store,
            String modelId,
            long stateIndex,
            ModelDocumentMutation mutation) {
        ModelCommitTarget target =
                ModelCommitTarget.builder()
                        .modelId(modelId)
                        .modelType("TestModel")
                        .updateState(true)
                        .delete(mutation.getDocument() == null)
                        .document(mutation)
                        .relationships(List.of())
                        .build();
        CommitModels commit =
                new CommitModels(
                        "commit-" + stateIndex,
                        stateIndex - 1L,
                        List.of(modelId),
                        List.of(
                                ModelCommitStep.builder()
                                        .targets(
                                                List.of(target))
                                        .build()),
                        ModelConflictPolicy.ACCEPT,
                        STORED, true);
        store.materializeModelCommit(
                commit,
                List.of(
                        new ModelUpdate(
                                ModelUpdateKind.COMMIT, commit.getCommitId(), 0,
                                stateIndex, null,
                                List.of(
                                        new ModelCommitTargetResult(
                                                modelId, -1L,
                                                true)))),
                Set.of());
    }

    private static SerializedDocument document(String summary) {
        return new SerializedDocument(
                Document.builder()
                        .id("model-1")
                        .type("TestModel")
                        .revision(0)
                        .collection("models")
                        .entries(Map.of())
                        .summary(() -> summary)
                        .build());
    }

    private static SerializedDocument structuredDocument(
            String id, String collection,
            String value) {
        return new SerializedDocument(
                Document.builder()
                        .id(id)
                        .type("TestModel")
                        .revision(0)
                        .collection(collection)
                        .entries(
                                Map.of(
                                        new Document.Entry(
                                                TEXT, value),
                                        List.of(
                                                new Document.Path(
                                                        "name"))))
                        .summary(() -> value)
                        .build());
    }
}
