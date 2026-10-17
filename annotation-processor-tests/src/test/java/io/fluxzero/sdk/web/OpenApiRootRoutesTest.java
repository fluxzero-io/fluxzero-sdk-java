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
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiRootRoutesTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentsRootAndComposedRoutes(boolean compiled) throws IOException {
        JsonNode document;
        if (compiled) {
            try (var input = getClass().getClassLoader().getResourceAsStream(OpenApiProcessor.DEFAULT_OUTPUT)) {
                assertNotNull(input);
                document = JsonUtils.fromJson(input.readAllBytes(), JsonNode.class);
            }
        } else {
            document = OpenApiRenderer.render(new ApiDocCatalog(Stream.of(RootApi.class, ComposedApi.class)
                    .flatMap(type -> ApiDocExtractor.extract(type).endpoints().stream()).toList()));
        }
        JsonNode paths = document.path("paths");
        assertFalse(paths.has(""));
        for (String method : new String[]{"get", "post", "put", "delete"}) {
            JsonNode operation = paths.path("/").path(method);
            assertEquals("root_" + method, operation.path("operationId").asText());
            assertTrue(operation.path("responses").has("200"));
            if (method.equals("put") || method.equals("delete")) {
                assertEquals("https://root.example", operation.path("servers").get(0).path("url").asText());
            } else {
                assertFalse(operation.has("servers"));
            }
        }
        assertTrue(paths.path("/root-contract/items").has("post"));
        assertFalse(paths.has("/root-contract/items/"));
        JsonNode withId = paths.path("/root-contract/items/{id}").path("get");
        assertEquals("id", withId.path("parameters").get(0).path("name").asText());
        assertTrue(paths.path("/root-contract/items").has("get"));
        assertTrue(paths.path("/root-contract/items").path("get").path("parameters").isEmpty());
    }

    @ApiDoc
    static class RootApi {
        @HandleGet @ApiDoc(operationId = "root_get")
        String implicit() { return "ok"; }
        @HandlePost("/") @ApiDoc(operationId = "root_post")
        String explicit() { return "ok"; }
        @HandlePut("https://root.example") @ApiDoc(operationId = "root_put")
        String absolute() { return "ok"; }
        @HandleDelete("https://root.example/") @ApiDoc(operationId = "root_delete")
        String absoluteSlash() { return "ok"; }
    }

    @ApiDoc
    @Path("/root-contract")
    static class ComposedApi {
        @HandleGet("items[/{id}]")
        String optional(@PathParam String id) { return id; }
        @HandlePost("items/")
        String trailingSlash() { return "ok"; }
    }
}
