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

package io.fluxzero.common.serialization;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxzero.common.api.Data;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {

    @Test
    void modulesFromServiceLoaderOverrideDefaultModules() {
        Map<?,?> map = JsonUtils.fromJson(
                """
                        {
                              "foo": "bar "
                        }
                        """, Map.class);
        assertEquals("bar ", map.get("foo"));
    }

    @Test
    void disableJsonIgnoreKeepsIgnoredJavaFieldInRoundTrip() throws Exception {
        ObjectMapper mapper = JsonUtils.writer.copy();
        JsonUtils.disableJsonIgnore(mapper);
        JsonIgnoredSample input = new JsonIgnoredSample("public", "private");

        var tree = mapper.valueToTree(input);
        var output = mapper.treeToValue(tree, JsonIgnoredSample.class);

        assertTrue(tree.has("hidden"));
        assertEquals(input.visible, output.visible);
        assertEquals(input.hidden, output.hidden);
    }

    @Test
    void untypedJsonWithRevisionProducesSerializedData() {
        Data<JsonNode> data = JsonUtils.fromJson("""
                {
                  "@class": "com.example.LegacyEvent",
                  "@revision": 2,
                  "revision": 42,
                  "value": "test"
                }
                """);

        assertEquals("com.example.LegacyEvent", data.getType());
        assertEquals(2, data.getRevision());
        assertEquals(Data.JSON_FORMAT, data.getFormat());
        assertEquals(42, data.getValue().get("revision").intValue());
        assertEquals("test", data.getValue().get("value").textValue());
        assertFalse(data.getValue().has("@class"));
        assertFalse(data.getValue().has("@revision"));
    }

    @Test
    void untypedJsonWithoutRevisionStillDeserializesDeclaredClass() {
        SamplePayload payload = JsonUtils.fromJson("""
                {
                  "@class": "io.fluxzero.common.serialization.JsonUtilsTest$SamplePayload",
                  "revision": 42,
                  "value": "test"
                }
                """);

        assertEquals(new SamplePayload(42, "test"), payload);
    }

    @Test
    void untypedJsonBytesRecognizeRevisionMetadata() {
        Data<JsonNode> data = JsonUtils.fromJson("""
                {
                  "@class": "com.example.LegacyEvent",
                  "@revision": 2,
                  "value": "test"
                }
                """.getBytes());

        assertEquals("com.example.LegacyEvent", data.getType());
        assertEquals(2, data.getRevision());
    }

    @Test
    void revisionMetadataRequiresClassAndIntegerRevision() {
        assertThrows(IllegalArgumentException.class,
                     () -> JsonUtils.fromJson("{\"@revision\": 0, \"value\": \"test\"}"));
        assertThrows(IllegalArgumentException.class,
                     () -> JsonUtils.fromJson("{\"@class\": \"example.Event\", \"@revision\": \"old\"}"));
    }

    @Value
    private static class JsonIgnoredSample {
        String visible;
        @JsonIgnore
        String hidden;
    }

    private record SamplePayload(int revision, String value) {
    }
}
