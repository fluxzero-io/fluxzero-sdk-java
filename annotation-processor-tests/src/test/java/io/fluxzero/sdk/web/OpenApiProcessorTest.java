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
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ParentId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiProcessorTest {
    @Test
    void generatesOpenApiDocumentDuringCompilation() throws IOException {
        byte[] json;
        try (var input = getClass().getClassLoader().getResourceAsStream(OpenApiProcessor.DEFAULT_OUTPUT)) {
            assertNotNull(input);
            json = input.readAllBytes();
        }

        JsonNode document = JsonUtils.fromJson(json, JsonNode.class);
        assertEquals("3.0.1", document.path("openapi").asText());
        assertEquals("Processor API", document.path("info").path("title").asText());
        assertEquals("v9", document.path("info").path("version").asText());
        assertEquals("Processor docs", document.path("info").path("description").asText());
        assertEquals("Fluxzero", document.path("info").path("contact").path("name").asText());
        assertEquals("support@example.com", document.path("info").path("contact").path("email").asText());
        assertEquals("https://example.com/logo.png", document.path("info").path("x-logo").path("url").asText());
        assertEquals("Processor logo", document.path("info").path("x-logo").path("altText").asText());
        assertEquals("https://processor.example.com", document.path("servers").get(0).path("url").asText());
        assertEquals("Processor prod", document.path("servers").get(0).path("description").asText());
        assertTrue(document.path("x-code-samples-enabled").asBoolean());
        assertEquals("Invalid processor request", document.path("components").path("responses").path("error")
                .path("description").asText());
        assertEquals("bearerAuth", document.path("security").get(0).fieldNames().next());
        assertEquals("http", document.path("components").path("securitySchemes").path("bearerAuth")
                .path("type").asText());

        JsonNode paths = document.path("paths");
        assertTrue(paths.has("/processor/meters/{meterId}/{readingId}"));
        assertTrue(paths.has("/processor/meters/{meterId}"));
        assertFalse(paths.has("/processor/internal"));
        assertFalse(paths.has("/processor/undocumented"));
        assertFalse(paths.has("/processor/socket"));

        JsonNode get = paths.path("/processor/meters/{meterId}/{readingId}").path("get");
        assertEquals("Fetch meter", get.path("summary").asText());
        assertEquals("Fetch meter operation", get.path("description").asText());
        assertEquals("processor", get.path("tags").get(0).asText());
        assertEquals("getProcessorMeter", get.path("operationId").asText());
        assertEquals("bearerAuth", get.path("security").get(0).fieldNames().next());
        assertEquals("Found", get.path("responses").path("200").path("description").asText());
        JsonNode responseSchema = get.path("responses").path("200").path("content")
                .path("application/json").path("schema");
        assertEquals("array", responseSchema.path("type").asText());
        assertFalse(responseSchema.has("description"));
        assertEquals("#/components/schemas/MeterDto", responseSchema.path("items").path("allOf").get(0)
                .path("$ref").asText());
        assertEquals("Meter item", responseSchema.path("items").path("description").asText());
        assertEquals("string", document.path("components").path("schemas").path("MeterDto")
                .path("properties").path("id").path("type").asText());
        assertEquals("Meter value", document.path("components").path("schemas").path("MeterDto")
                .path("properties").path("value").path("description").asText());
        assertEquals("Missing", get.path("responses").path("404").path("description").asText());
        assertEquals("#/components/responses/error", get.path("responses").path("400").path("$ref").asText());
        assertEquals("meterId", get.path("parameters").get(0).path("name").asText());
        assertEquals("path", get.path("parameters").get(0).path("in").asText());
        assertEquals("query", get.path("parameters").get(2).path("in").asText());
        assertEquals("Maximum result count", get.path("parameters").get(2).path("description").asText());
        assertEquals(1, get.path("parameters").get(2).path("schema").path("minimum").asInt());

        JsonNode accessor = document.path("components").path("schemas").path("AccessorDto").path("properties");
        assertEquals("DTO with accessor-style fields used by processor tests.",
                     document.path("components").path("schemas").path("AccessorDto").path("description").asText());
        assertEquals("string", accessor.path("id").path("type").asText());
        assertEquals("Identifier described by field Javadoc.", accessor.path("id").path("description").asText());
        assertEquals("Schema description wins", accessor.path("schemaDescription").path("description").asText());
        assertEquals("ApiDoc description wins", accessor.path("apiDocDescription").path("description").asText());
        assertEquals("boolean", accessor.path("active").path("type").asText());
        assertEquals("Whether this processor item is active", accessor.path("active").path("description").asText());
        assertTrue(accessor.path("active").path("default").asBoolean());
        assertEquals("string", accessor.path("jsonValueId").path("type").asText());
        assertEquals("Json value id", accessor.path("jsonValueId").path("description").asText());
        assertEquals("string", accessor.path("opensAt").path("type").asText());
        assertEquals("partial-time", accessor.path("opensAt").path("format").asText());
        assertEquals("string", accessor.path("timeZone").path("type").asText());
        assertEquals("IANA timezone", accessor.path("timeZone").path("format").asText());
        assertEquals("integer", accessor.path("attempts").path("type").asText());
        assertEquals(0, accessor.path("attempts").path("minimum").asInt());
        assertEquals(10, accessor.path("attempts").path("maximum").asInt());
        assertEquals(5, accessor.path("attempts").path("example").asInt());
        assertEquals(3, accessor.path("attempts").path("default").asInt());
        assertEquals("primary", accessor.path("status").path("enum").get(0).asText());
        assertEquals("secondary", accessor.path("status").path("enum").get(1).asText());
        assertEquals("secondary", accessor.path("status").path("example").asText());
        assertEquals(2, accessor.path("ranged").path("minimum").asInt());
        assertEquals(7, accessor.path("ranged").path("maximum").asInt());
        assertEquals(3, accessor.path("lengthLimited").path("minLength").asInt());
        assertEquals(12, accessor.path("lengthLimited").path("maxLength").asInt());
        assertEquals("uri", accessor.path("homepage").path("format").asText());
        assertEquals("uuid", accessor.path("externalId").path("format").asText());
        assertTrue(accessor.path("uniqueTags").path("uniqueItems").asBoolean());
        assertEquals("First paragraph.\n\nSecond paragraph with String link.",
                     accessor.path("javadocParagraphs").path("description").asText());
        assertFalse(accessor.has("secret"));
        assertFalse(contains(document.path("components").path("schemas").path("InputDto").path("required"),
                             "tags"));
        JsonNode required = document.path("components").path("schemas").path("AccessorDto").path("required");
        assertTrue(contains(required, "status"));
        assertTrue(contains(required, "aliases"));
        assertFalse(document.path("components").path("schemas").has("JsonValueId"));
        JsonNode lombokProperties = document.path("components").path("schemas").path("LombokDto").path("properties");
        assertEquals("Lombok-generated getter should keep field Javadoc.",
                     lombokProperties.path("lombokField").path("description").asText());
        assertEquals("Field ApiDoc survives Lombok-generated getter.",
                     lombokProperties.path("documentedField").path("description").asText());
        List<String> schemaNames = new ArrayList<>();
        document.path("components").path("schemas").fieldNames().forEachRemaining(schemaNames::add);
        assertEquals(schemaNames.stream().sorted().toList(), schemaNames);

        JsonNode upload = paths.path("/processor/uploads").path("post");
        JsonNode multipart = upload.path("requestBody").path("content").path("multipart/form-data")
                .path("schema").path("properties");
        assertEquals("binary", multipart.path("file").path("format").asText());
        assertTrue(upload.path("responses").has("204"));

        JsonNode graphSchema = paths.path("/processor/model-graphs/{id}").path("get")
                .path("responses").path("200").path("content").path("application/json").path("schema");
        assertEquals("#/components/schemas/ProcessorOrganisation", graphSchema.path("$ref").asText());
        JsonNode graphListSchema = paths.path("/processor/model-graphs").path("get")
                .path("responses").path("200").path("content").path("application/json").path("schema");
        assertEquals("array", graphListSchema.path("type").asText());
        assertFalse(graphListSchema.has("description"));
        assertEquals("List processor model graphs",
                     paths.path("/processor/model-graphs").path("get").path("description").asText());
        assertEquals("#/components/schemas/ProcessorOrganisation",
                     graphListSchema.path("items").path("$ref").asText());
        JsonNode graphArraySchema = paths.path("/processor/model-graphs/array").path("get")
                .path("responses").path("200").path("content").path("application/json").path("schema");
        assertEquals("array", graphArraySchema.path("type").asText());
        assertEquals("#/components/schemas/ProcessorOrganisation",
                     graphArraySchema.path("items").path("$ref").asText());
        JsonNode graphSchemas = document.path("components").path("schemas");
        JsonNode locations = graphSchemas.path("ProcessorOrganisation").path("properties").path("locations");
        assertEquals("array", locations.path("type").asText());
        assertEquals("Organisation locations", locations.path("description").asText());
        assertEquals("#/components/schemas/ProcessorLocation", locations.path("items").path("$ref").asText());
        assertTrue(contains(graphSchemas.path("ProcessorOrganisation").path("required"), "locations"));
        JsonNode connections = graphSchemas.path("ProcessorLocation").path("properties")
                .path("assets").path("properties").path("connections");
        assertEquals("array", connections.path("type").asText());
        assertEquals("Location connections", connections.path("description").asText());
        assertEquals("#/components/schemas/ProcessorConnection", connections.path("items").path("$ref").asText());
        assertEquals(ProcessorOrganisationInfo.class.getName(),
                     graphSchemas.path("ProcessorOrganisationInfo")
                             .path(OpenApiRenderer.JAVA_TYPE_EXTENSION).asText());
        JsonNode selectedSchemas = document.path("components").path("schemas");
        assertTrue(selectedSchemas.path("SelectedRoot").path("properties").has("children"));
        assertTrue(selectedSchemas.path("SelectedChild").path("properties").has("leaves"));
        assertFalse(selectedSchemas.path("SelectedRoot").path("properties").has("excludedChildren"));
    }

    @ApiDocInfo(
            title = "Processor API",
            version = "v9",
            description = "Processor docs",
            contactName = "Fluxzero",
            contactEmail = "support@example.com",
            logoUrl = "https://example.com/logo.png",
            logoAltText = "Processor logo",
            servers = @ApiDocServer(url = "https://processor.example.com", description = "Processor prod"),
            security = "bearerAuth",
            components = {
                    @ApiDocComponent(path = "responses.error", json = """
                            {"description":"Invalid processor request"}
                            """),
                    @ApiDocComponent(path = "securitySchemes.bearerAuth", json = """
                            {"type":"http","scheme":"bearer"}
                            """)
            },
            extensions = "x-code-samples-enabled=true")
    @Path("/processor")
    @ApiDoc(tags = "processor", security = "bearerAuth")
    static class ProcessorApi {
        @HandleGet("/meters/{meterId}[/{readingId}]")
        @ApiDoc(summary = "Fetch meter", description = "Fetch meter operation", operationId = "getProcessorMeter")
        @ApiDocResponse(status = 200, description = "Found")
        @ApiDocResponse(status = 400, ref = "error")
        @ApiDocResponse(status = 404, description = "Missing")
        List<@ApiDoc(description = "Meter item") MeterDto> get(
                @PathParam String meterId, @PathParam String readingId,
                @QueryParam @ApiDoc(description = "Maximum result count") @jakarta.validation.constraints.Min(1)
                int limit,
                @HeaderParam("X-Tenant") String tenant) {
            return List.of(new MeterDto(meterId, 42));
        }

        @HandlePost("/uploads")
        void upload(@FormParam("file") WebFormPart file, @FormParam String description) {
        }

        @HandlePost("/input")
        void input(InputDto body) {
        }

        @HandleGet("/accessor")
        AccessorDto accessor() {
            return null;
        }

        @HandleGet("/lombok")
        LombokDto lombok() {
            return null;
        }

        @ApiDoc
        @ApiDocResponse(status = 200, modelGraph = ProcessorOrganisation.class)
        @HandleGet("/model-graphs/{id}")
        JsonNode modelGraph(@PathParam("id") String id) {
            return null;
        }

        @ApiDoc(description = "List processor model graphs")
        @ApiDocResponse(status = 200, modelGraph = ProcessorOrganisation.class)
        @HandleGet("/model-graphs")
        List<JsonNode> modelGraphs() {
            return null;
        }

        @ApiDoc
        @ApiDocResponse(status = 200, modelGraph = ProcessorOrganisation.class)
        @HandleGet("/model-graphs/array")
        JsonNode[] modelGraphArray() {
            return null;
        }

        @ApiDoc
        @ApiDocResponse(status = 200, modelGraph = SelectedRoot.class,
                modelGraphPaths = "children/leaves")
        @HandleGet("/selected-model-graph")
        JsonNode selectedModelGraph() {
            return null;
        }

        @ApiDocExclude
        @HandleGet("/internal")
        String internal() {
            return "hidden";
        }

        @HandleSocketOpen("/socket")
        void socket(SocketSession session) {
        }
    }

    @Path("/processor")
    static class UndocumentedApi {
        @HandleGet("/undocumented")
        String undocumented() {
            return "hidden";
        }
    }

    record MeterDto(String id,
                    @ApiDoc(description = "Meter value") @jakarta.validation.constraints.PositiveOrZero int value) {
    }

    record InputDto(List<String> tags) {
    }

    /**
     * DTO with accessor-style fields used by processor tests.
     */
    static class AccessorDto {
        /**
         * Identifier described by field Javadoc.
         */
        String id;
        /**
         * Javadoc description loses.
         */
        @io.swagger.v3.oas.annotations.media.Schema(description = "Schema description wins")
        String schemaDescription;
        /**
         * Javadoc description loses.
         */
        @io.swagger.v3.oas.annotations.media.Schema(description = "Schema description loses")
        @ApiDoc(description = "ApiDoc description wins")
        String apiDocDescription;
        @ApiDoc(description = "Json value id")
        JsonValueId jsonValueId;
        java.time.LocalTime opensAt;
        java.time.ZoneId timeZone;
        @ApiDoc(description = "Attempt count", type = "integer", minimum = "0", maximum = "10", example = "5",
                defaultValue = "3")
        String attempts;
        @ApiDoc(description = "Status", allowableValues = {"primary", "secondary"}, example = "secondary",
                defaultValue = "primary", required = true)
        String status;
        @io.fluxzero.sdk.tracking.handling.validation.constraints.Range(min = 2, max = 7)
        int ranged;
        @io.fluxzero.sdk.tracking.handling.validation.constraints.Length(min = 3, max = 12)
        String lengthLimited;
        @io.fluxzero.sdk.tracking.handling.validation.constraints.URL
        String homepage;
        @io.fluxzero.sdk.tracking.handling.validation.constraints.UUID
        String externalId;
        @io.fluxzero.sdk.tracking.handling.validation.constraints.UniqueElements
        List<String> uniqueTags;
        @ApiDocExclude
        String secret;
        List<String> aliases;
        /**
         * First paragraph.
         *
         * Second paragraph with {@link String} link.
         *
         * @return ignored block tag
         */
        String javadocParagraphs;

        @ApiDoc(description = "Whether this processor item is active", defaultValue = "true")
        public boolean isActive() {
            return true;
        }
    }

    static class JsonValueId {
        @com.fasterxml.jackson.annotation.JsonValue
        String value;
    }

    @Model
    record ProcessorOrganisation(@EntityId String id, String name, ProcessorOrganisationInfo info) {
    }

    record ProcessorOrganisationInfo(String name) {
    }

    static class ProcessorOrganisationId extends Id<ProcessorOrganisation> {
        ProcessorOrganisationId(String id) {
            super(id);
        }
    }

    @Model
    record ProcessorLocation(
            @EntityId String id,
            @ParentId(path = "locations",
                    apiDoc = @ApiDoc(description = "Organisation locations", required = true,
                            type = "object"))
            ProcessorOrganisationId organisationId) {
    }

    @Model
    record ProcessorConnection(
            @EntityId String id,
            @ParentId(value = ProcessorLocation.class, path = "assets/connections",
                    apiDoc = @ApiDoc(description = "Location connections"))
            String locationId) {
    }

    @Model
    record SelectedRoot(@EntityId String id) {
    }

    @Model
    record SelectedChild(
            @EntityId String id,
            @ParentId(value = SelectedRoot.class, path = "children") String rootId) {
    }

    @Model
    record SelectedLeaf(
            @EntityId String id,
            @ParentId(value = SelectedChild.class, path = "leaves") String childId) {
    }

    @Model
    record ExcludedChild(
            @EntityId String id,
            @ParentId(value = SelectedRoot.class, path = "excludedChildren") String rootId) {
    }

    @lombok.Value
    static class LombokDto {
        /**
         * Lombok-generated getter should keep field Javadoc.
         */
        String lombokField;
        @ApiDoc(description = "Field ApiDoc survives Lombok-generated getter.")
        String documentedField;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
