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

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InputSchemasTest {
    @Test
    void namedEntriesAreNotConfusedWithOpaqueSchemaKeywords() {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        ObjectNode schema = document.withObject("/components/schemas/x-payload");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (String name : List.of("examples", "example", "default", "x-field", "$ref", "properties")) {
            properties.putObject(name).put("$ref", InputSchemas.reference("x-payload"));
        }
        for (String name : List.of("examples", "example", "default", "x-extension", "enum", "const")) {
            schema.putObject(name).put("$ref", InputSchemas.reference("literal"));
        }
        InputSchemas.resolve(document, Map.of("x-payload", schema.deepCopy()), Map.of());
        ObjectNode result = (ObjectNode) document.at("/components/schemas/x-payload");
        for (String name : List.of("examples", "example", "default", "x-field", "$ref", "properties")) {
            assertEquals("#/components/schemas/x-payload", result.path("properties").path(name).path("$ref").asText());
        }
        for (String name : List.of("examples", "example", "default", "x-extension", "enum", "const")) {
            assertEquals(InputSchemas.reference("literal"), result.path(name).path("$ref").asText());
        }
    }
}
