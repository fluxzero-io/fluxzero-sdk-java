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
package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReindexModelTest {
    @Test
    void preservesWireEnvelopeButNeverExposesIdentifiersOrPayloadsInMetrics() throws Exception {
        var document = new SerializedDocument("private-id", null, null, "private-collection",
                new Data<>(new byte[]{1, 2, 3}, "private-type", 1, Data.JSON_FORMAT), null, Set.of(), Set.of());
        var request = new ReindexModel(document, new ModelHeadState("private-id", "private-type", 0, 1, true, false),
                "private-proof", 1L);
        var decoded = (ReindexModel) JsonUtils.reader.readValue(JsonUtils.writer.writeValueAsBytes(request), JsonType.class);
        assertEquals(request.getExpectedHead(), decoded.getExpectedHead());
        assertEquals(request.getExpectedProof(), decoded.getExpectedProof());
        assertEquals(request.getCutoff(), decoded.getCutoff());
        assertArrayEquals(document.getDocument().getValue(), decoded.getDocument().getDocument().getValue());
        assertEquals("{\"documentBytes\":3,\"bounded\":true}", JsonUtils.writer.writeValueAsString(request.toMetric()));
        assertThrows(IllegalArgumentException.class, () -> new ReindexModel(document,
                new ModelHeadState("private-id", "private-type", 0, 1, false, false), null, null).validate());
    }
}
