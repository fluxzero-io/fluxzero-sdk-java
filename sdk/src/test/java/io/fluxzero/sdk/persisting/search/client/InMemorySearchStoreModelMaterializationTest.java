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

import io.fluxzero.common.api.modeling.MaterializeModelAction;
import io.fluxzero.common.api.modeling.ModelDocumentMaterialization;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private void materialize(
            long stateIndex, SerializedDocument document) {
        subject.materializeModelAction(
                        new MaterializeModelAction(
                                "action-" + stateIndex,
                                stateIndex,
                                List.of(
                                        new ModelDocumentMaterialization(
                                                "model-1",
                                                stateIndex,
                                                new ModelDocumentMutation(
                                                        "models",
                                                        document))),
                                List.of()))
                .join();
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
}
