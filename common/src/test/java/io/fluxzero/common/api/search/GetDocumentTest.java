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

package io.fluxzero.common.api.search;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetDocumentTest {

    @Test
    void ordinaryDocumentRequestDefaultsToNoModelHead() {
        ObjectNode json = JsonUtils.valueToTree(
                new GetDocument("model-1", "models"));
        json.remove("includeModelHead");
        GetDocument result = JsonUtils.convertValue(json, GetDocument.class);

        assertEquals("model-1", result.getId());
        assertEquals("models", result.getCollection());
        assertFalse(result.isIncludeModelHead());
    }

    @Test
    void roundTripsVersionedModelDocumentResult() {
        ModelHeadState head = new ModelHeadState(
                "model-1", "example.Model", 3L, 11L, true, true);
        GetDocumentResult result = JsonUtils.convertValue(
                new GetDocumentResult(42L, null, head), GetDocumentResult.class);

        assertEquals(42L, result.getRequestId());
        assertNull(result.getDocument());
        assertEquals(head, result.getModelHead());
        assertTrue(result.getModelHead().isDeleted());
    }
}
