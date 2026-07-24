/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
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

package io.fluxzero.common.api.search;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import static io.fluxzero.common.Guarantee.STORED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeleteDocumentsTest {

    private final SearchQuery query = SearchQuery.builder().collection("test").build();

    @Test
    void legacyConstructorUsesRuntimeDefault() {
        assertEquals(0, new DeleteDocuments(query, STORED).getBatchSize());
    }

    @Test
    void serializesRequestedBatchSize() {
        DeleteDocuments result = JsonUtils.convertValue(new DeleteDocuments(query, STORED, 5_000),
                                                        DeleteDocuments.class);

        assertEquals(5_000, result.getBatchSize());
    }

    @Test
    void missingBatchSizeUsesRuntimeDefault() {
        ObjectNode json = JsonUtils.valueToTree(new DeleteDocuments(query, STORED, 5_000));
        json.remove("batchSize");

        assertEquals(0, JsonUtils.convertValue(json, DeleteDocuments.class).getBatchSize());
    }

    @Test
    void nullBatchSizeUsesRuntimeDefault() {
        ObjectNode json = JsonUtils.valueToTree(new DeleteDocuments(query, STORED, 5_000));
        json.putNull("batchSize");

        assertEquals(0, JsonUtils.convertValue(json, DeleteDocuments.class).getBatchSize());
    }
}
