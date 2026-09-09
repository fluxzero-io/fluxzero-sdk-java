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

package io.fluxzero.common.api.eventsourcing;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetEventsTest {

    @Test
    void legacyJsonWithoutByteLimitRetainsCountOnlyBehavior() throws Exception {
        var objectMapper = JsonMapper.builder().findAndAddModules().build();
        ObjectNode legacyJson = objectMapper.valueToTree(new GetEvents("aggregate", 4L, 10));
        legacyJson.remove("maxBytes");
        GetEvents request = objectMapper.treeToValue(legacyJson, GetEvents.class);

        assertEquals("aggregate", request.getAggregateId());
        assertEquals(4L, request.getLastSequenceNumber());
        assertEquals(10, request.getBatchSize());
        assertEquals(0L, request.getMaxBytes());
    }

    @Test
    void legacyConstructorDisablesByteLimit() {
        assertEquals(0L, new GetEvents("aggregate", -1L, 10).getMaxBytes());
    }

    @Test
    void countOnlyRequestKeepsTheLegacyWireShape() throws Exception {
        String json = JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(new GetEvents("aggregate", -1L, 10));

        assertFalse(json.contains("\"maxBytes\""));
    }

    @Test
    void byteLimitIsSerializedAsOptionalProtocolExtension() throws Exception {
        String json = JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(new GetEvents("aggregate", -1L, 10, 1234L));

        assertTrue(json.contains("\"maxBytes\":1234"));
    }
}
