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
package io.fluxzero.common.api;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.search.RewriteModelGraphDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelGraphRewriteWireTest {
    @Test
    void rewriteCommandHasAStablePolymorphicWireType() throws Exception {
        var document = new SerializedDocument("root", 10L, 20L, "graphs",
                new Data<>("{}".getBytes(StandardCharsets.UTF_8), "example.Project", 2), null, Set.of(), Set.of());
        var command = new RewriteModelGraphDocument(document, "expected-manifest", Guarantee.STORED);
        String wire = JsonUtils.writer.writeValueAsString(command);
        assertTrue(wire.contains("\"@type\":\"rewriteModelGraphDocument\""));
        var decoded = assertInstanceOf(RewriteModelGraphDocument.class, JsonUtils.reader.readValue(wire, JsonType.class));
        assertEquals(command.getRequestId(), decoded.getRequestId());
        assertEquals(command.getExpectedManifest(), decoded.getExpectedManifest());
        assertEquals(command.getGuarantee(), decoded.getGuarantee());
        assertEquals("root", decoded.getDocument().getId());
        assertEquals("graphs", decoded.getDocument().getCollection());
        assertEquals("example.Project", decoded.getDocument().getDocument().getType());
        assertEquals(2, decoded.getDocument().getDocument().getRevision());
    }
}
