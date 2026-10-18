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

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

final class DecimalOpenApiAssertions {
    static void assertOverride(JsonNode document) {
        var input = document.path("paths").path("/override").path("post").path("requestBody")
                .path("content").path("application/json").path("schema");
        assertEquals("#/components/schemas/OverridePayload", input.path("$ref").asText());
        var schema = document.path("components").path("schemas").path("OverridePayload");
        assertEquals("value", schema.path("required").get(0).asText());
        assertEquals("#/x-fluxzero-input/literal", schema.path("example").path("$ref").asText());
    }

    static void assertShared(JsonNode document) {
        var operation = document.path("paths").path("/shared-decimal").path("post");
        var request = operation.path("requestBody").path("content").path("application/json").path("schema");
        var response = operation.path("responses").path("200").path("content").path("application/json").path("schema");
        assertNotEquals(request.path("$ref"), response.path("$ref"));
        var input = document.at(request.path("$ref").asText().substring(1));
        var output = document.at(response.path("$ref").asText().substring(1));
        assertEquals(2, input.path("properties").path("amount").path("oneOf").size());
        assertEquals("string", output.path("properties").path("amount").path("type").asText());
        assertEquals(request.path("$ref"), input.path("properties").path("children").path("items").path("$ref"));
        assertEquals(response.path("$ref"), output.path("properties").path("children").path("items").path("$ref"));
        for (String name : java.util.List.of("examples", "example", "properties", "$ref")) {
            assertEquals(request.path("$ref"), input.path("properties").path(name).path("$ref"), name);
            assertEquals(response.path("$ref"), output.path("properties").path(name).path("$ref"), name);
        }
        assertFalse(operation.toString().contains("#/x-fluxzero-input/"));
    }
}
