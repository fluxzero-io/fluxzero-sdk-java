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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MaterializeModelActionTest {

    @Test
    void extractsDirectDocumentsAndOnlyAuthoritativeSnapshots() {
        ModelDocumentMutation document = new ModelDocumentMutation("orders", null);
        ModelSnapshotMutation retainedSnapshot =
                new ModelSnapshotMutation(new Data<>(new byte[]{1}, "Order", 0, "application/json"),
                                          100L, 10, 2);
        ModelSnapshotMutation incompleteSnapshot =
                new ModelSnapshotMutation(new Data<>(new byte[]{2}, "Order", 0, "application/json"),
                                          101L, 10, 2);
        CommitModelAction action = new CommitModelAction(
                "action-1", 40L, List.of("order-1", "order-2", "order-3"),
                List.of(ModelActionSubstep.builder()
                                .targets(List.of(
                                        ModelActionTarget.builder()
                                                .modelId("order-1")
                                                .updateState(true)
                                                .document(document)
                                                .relationships(List.of())
                                                .build(),
                                        ModelActionTarget.builder()
                                                .modelId("order-2")
                                                .storeEvent(true)
                                                .updateState(true)
                                                .snapshot(retainedSnapshot)
                                                .relationships(List.of())
                                                .build(),
                                        ModelActionTarget.builder()
                                                .modelId("order-3")
                                                .storeEvent(true)
                                                .updateState(true)
                                                .snapshot(incompleteSnapshot)
                                                .relationships(List.of())
                                                .build()))
                                .build()),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED);
        List<ModelActionSubstepResult> assigned = List.of(
                new ModelActionSubstepResult(
                        42L, null,
                        List.of(
                                new ModelActionTargetResult("order-1", -1L, true),
                                new ModelActionTargetResult("order-2", 7L, true),
                                new ModelActionTargetResult("order-3", 8L, false))));

        MaterializeModelAction result = MaterializeModelAction.from(action, assigned);

        assertEquals("action-1", result.getActionId());
        assertEquals(42L, result.getLastStateIndex());
        assertEquals(1, result.getDocuments().size());
        assertEquals("order-1", result.getDocuments().getFirst().getModelId());
        assertSame(document, result.getDocuments().getFirst().getMutation());
        assertEquals(1, result.getSnapshots().size());
        assertEquals("order-2", result.getSnapshots().getFirst().getModelId());
        assertEquals(7L, result.getSnapshots().getFirst().getSequenceNumber());
        assertSame(retainedSnapshot, result.getSnapshots().getFirst().getMutation());
    }
}
