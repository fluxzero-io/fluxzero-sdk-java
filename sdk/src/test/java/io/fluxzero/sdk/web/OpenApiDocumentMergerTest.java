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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiDocumentMergerTest {
    @Test
    void mergesCompatibleDocumentsDeterministically() {
        JsonNode first = document("3.0.1", "/first", "getFirst", "First");
        JsonNode second = document("3.0.1", "/second", "getSecond", "Second");

        JsonNode merged = OpenApiDocumentMerger.merge(Map.of("b", second, "a", first));
        Map<String, JsonNode> reversed = new LinkedHashMap<>();
        reversed.put("b", second);
        reversed.put("a", first);

        assertEquals(merged, OpenApiDocumentMerger.merge(reversed));
        assertTrue(merged.path("paths").has("/first"));
        assertTrue(merged.path("paths").has("/second"));
        assertTrue(merged.path("components").path("schemas").has("First"));
        assertTrue(merged.path("components").path("schemas").has("Second"));
    }

    @Test
    void acceptsExactDuplicatesForOpenApi31() {
        JsonNode document = document("3.1.0", "/items", "getItems", "Item");

        assertEquals(document, OpenApiDocumentMerger.merge(Map.of("first", document, "second", document.deepCopy())));
    }

    @Test
    void rejectsConflictingGlobalMetadataWithSourceAndPointer() {
        JsonNode first = document("3.0.1", "/first", "getFirst", "First");
        JsonNode second = document("3.1.0", "/second", "getSecond", "Second");

        var error = assertThrows(IllegalArgumentException.class,
                                 () -> OpenApiDocumentMerger.merge(Map.of("module-a", first, "module-b", second)));

        assertTrue(error.getMessage().contains("/openapi"));
        assertTrue(error.getMessage().contains("module-a"));
        assertTrue(error.getMessage().contains("module-b"));
    }

    @Test
    void rejectsConflictingRoute() {
        JsonNode first = document("3.0.1", "/items", "getItems", "Item");
        JsonNode second = first.deepCopy();
        ((ObjectNode) second.path("paths").path("/items").path("get")).put("operationId", "findItems");

        var error = assertThrows(IllegalArgumentException.class,
                                 () -> OpenApiDocumentMerger.merge(Map.of("module-a", first, "module-b", second)));

        assertTrue(error.getMessage().contains("/paths/~1items/get/operationId"));
    }

    @Test
    void rejectsConflictingComponent() {
        JsonNode first = document("3.0.1", "/first", "getFirst", "Shared");
        JsonNode second = document("3.0.1", "/second", "getSecond", "Shared");
        ((ObjectNode) second.path("components").path("schemas").path("Shared")).put("type", "string");

        var error = assertThrows(IllegalArgumentException.class,
                                 () -> OpenApiDocumentMerger.merge(Map.of("module-a", first, "module-b", second)));

        assertTrue(error.getMessage().contains("/components/schemas/Shared/type"));
    }

    @Test
    void rejectsDuplicateOperationIdOnDifferentRoutes() {
        JsonNode first = document("3.0.1", "/first", "duplicate", "First");
        JsonNode second = document("3.0.1", "/second", "duplicate", "Second");

        var error = assertThrows(IllegalArgumentException.class,
                                 () -> OpenApiDocumentMerger.merge(Map.of("module-a", first, "module-b", second)));

        assertTrue(error.getMessage().contains("Duplicate OpenAPI operationId 'duplicate'"));
        assertTrue(error.getMessage().contains("/paths/~1first/get"));
        assertTrue(error.getMessage().contains("/paths/~1second/get"));
    }

    private static JsonNode document(String openApiVersion, String path, String operationId, String schema) {
        return JsonUtils.fromJson("""
                {
                  "openapi": "%s",
                  "info": {"title": "Test API", "version": "1.0.0"},
                  "paths": {
                    "%s": {
                      "get": {"operationId": "%s", "responses": {"200": {"description": "OK"}}}
                    }
                  },
                  "components": {"schemas": {"%s": {"type": "object"}}}
                }
                """.formatted(openApiVersion, path, operationId, schema), JsonNode.class);
    }
}
