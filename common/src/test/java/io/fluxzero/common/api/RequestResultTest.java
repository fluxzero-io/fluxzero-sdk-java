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

package io.fluxzero.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestResultTest {

    @Test
    void historicalReadEvidenceIsAdditiveAndOptional() throws Exception {
        var mapper = io.fluxzero.common.serialization.JsonUtils.writer;
        var details = new ErrorResult.ModelHistoryUnavailable("document", 12L);
        var typed = new ErrorResult(4, "unchanged failure message", details);
        var json = mapper.writeValueAsString(typed);
        assertEquals("error", mapper.readTree(json).get("@type").asText());
        var restored = assertInstanceOf(ErrorResult.class, mapper.readValue(json, JsonType.class));
        assertEquals(details, restored.getModelHistoryUnavailable());
        assertEquals(typed.getMessage(), restored.getMessage());
        var legacy = assertInstanceOf(ErrorResult.class, mapper.readValue(
                "{\"@type\":\"error\",\"requestId\":4,\"message\":\"ordinary failure\"}", JsonType.class));
        assertNull(legacy.getModelHistoryUnavailable());
        assertFalse(mapper.readTree(mapper.writeValueAsString(new ErrorResult(4, "ordinary failure")))
                            .has("modelHistoryUnavailable"));
        for (String invalid : java.util.List.of("{}", "{\"modelId\":\"document\"}",
                                               "{\"readStateIndex\":12}",
                                               "{\"modelId\":\"document\",\"readStateIndex\":-1}")) {
            assertThrows(Exception.class, () -> mapper.readValue(invalid, ErrorResult.ModelHistoryUnavailable.class));
        }
    }

    @Test
    void requestReceivedTimestampRoundTripsThroughJsonTypeSerialization() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        RequestResult result = new VoidResult(1);
        result.setRequestReceivedTimestamp(123456789L);

        String json = objectMapper.writeValueAsString(result);
        RequestResult restored = (RequestResult) objectMapper.readValue(json, JsonType.class);

        assertEquals(123456789L, restored.getRequestReceivedTimestamp());
    }
}
