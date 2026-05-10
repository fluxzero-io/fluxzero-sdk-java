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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiRendererTest {

    @Test
    void rendersOpenApiDocumentForJsonEndpoint() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(MeterHandler.class);

        JsonNode document = OpenApiRenderer.render(catalog, new OpenApiOptions(
                "Measurements API", "1.2.3", "Measurement endpoints", List.of("https://api.example.com")));
        JsonNode operation = document.path("paths").path("/v1/meters/{meterId}/readings").path("post");

        assertEquals("3.1.0", document.path("openapi").asText());
        assertEquals("Measurements API", document.path("info").path("title").asText());
        assertEquals("1.2.3", document.path("info").path("version").asText());
        assertEquals("Measurement endpoints", document.path("info").path("description").asText());
        assertEquals("https://api.example.com", document.path("servers").get(0).path("url").asText());

        assertEquals("Create reading", operation.path("summary").asText());
        assertEquals("Meter endpoints", operation.path("description").asText());
        assertEquals("createReading", operation.path("operationId").asText());
        assertEquals("Meters", operation.path("tags").get(0).asText());
        assertEquals("Readings", operation.path("tags").get(1).asText());

        JsonNode pathParameter = operation.path("parameters").get(0);
        assertEquals("meterId", pathParameter.path("name").asText());
        assertEquals("path", pathParameter.path("in").asText());
        assertTrue(pathParameter.path("required").asBoolean());
        assertEquals("string", pathParameter.path("schema").path("type").asText());

        JsonNode queryParameter = operation.path("parameters").get(1);
        assertEquals("limit", queryParameter.path("name").asText());
        assertEquals("query", queryParameter.path("in").asText());
        assertFalse(queryParameter.path("required").asBoolean());
        assertEquals("integer", queryParameter.path("schema").path("type").asText());
        assertEquals("int32", queryParameter.path("schema").path("format").asText());

        JsonNode bodySchema = operation.path("requestBody").path("content")
                .path("application/json").path("schema");
        assertEquals("object", bodySchema.path("type").asText());
        assertEquals("number", bodySchema.path("properties").path("value").path("type").asText());

        JsonNode response200 = operation.path("responses").path("200");
        assertEquals("OK", response200.path("description").asText());
        assertEquals("string", response200.path("content").path("application/json")
                .path("schema").path("properties").path("readingId").path("type").asText());

        JsonNode response404 = operation.path("responses").path("404");
        assertEquals("Meter not found", response404.path("description").asText());
        assertEquals("object", response404.path("content").path("application/json").path("schema").path("type").asText());
        assertEquals("string", response404.path("content").path("application/json").path("schema")
                .path("properties").path("message").path("type").asText());
    }

    @Test
    void rendersFormParametersAsMultipartRequestBody() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(FormHandler.class);

        JsonNode operation = OpenApiRenderer.render(catalog)
                .path("paths").path("/uploads").path("post");
        JsonNode schema = operation.path("requestBody").path("content")
                .path("multipart/form-data").path("schema");

        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.path("properties").path("name").path("type").asText());
        assertEquals("binary", schema.path("properties").path("file").path("format").asText());
        assertEquals("204", operation.path("responses").fieldNames().next());
    }

    @Test
    void rendersBodyParamsAsJsonObjectRequestBody() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(BodyParamHandler.class);

        JsonNode operation = OpenApiRenderer.render(catalog)
                .path("paths").path("/filters").path("post");
        JsonNode schema = operation.path("requestBody").path("content")
                .path("application/json").path("schema");

        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.path("properties").path("from").path("type").asText());
        assertEquals("date-time", schema.path("properties").path("from").path("format").asText());
        assertEquals("integer", schema.path("properties").path("count").path("type").asText());
    }

    @Test
    void skipsFluxzeroWebsocketPseudoMethods() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(WebsocketHandler.class);

        JsonNode paths = OpenApiRenderer.render(catalog).path("paths");

        assertTrue(paths.isEmpty());
    }

    @ApiDoc(description = "Meter endpoints", tags = "Meters")
    static class MeterHandler {
        @ApiDoc(summary = "Create reading", operationId = "createReading", tags = "Readings")
        @ApiDocResponse(status = 404, description = "Meter not found", type = NotFound.class)
        @HandlePost("/v1/meters/{meterId}/readings")
        ReadingCreated createReading(
                @PathParam("meterId") String meterId,
                @QueryParam("limit") int limit,
                CreateReading body) {
            return null;
        }
    }

    static class FormHandler {
        @HandlePost("/uploads")
        void upload(@FormParam("name") String name, @FormParam("file") WebFormPart file) {
        }
    }

    static class BodyParamHandler {
        @HandlePost("/filters")
        String search(@BodyParam("from") java.time.Instant from, @BodyParam("count") int count) {
            return null;
        }
    }

    static class WebsocketHandler {
        @HandleSocketMessage("/socket")
        void message(String message) {
        }
    }

    record CreateReading(java.math.BigDecimal value) {
    }

    record ReadingCreated(String readingId) {
    }

    record NotFound(String message) {
    }
}
