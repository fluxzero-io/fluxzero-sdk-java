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
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.validation.constraints.Length;
import io.fluxzero.sdk.tracking.handling.validation.constraints.Range;
import io.fluxzero.sdk.tracking.handling.validation.constraints.URL;
import io.fluxzero.sdk.tracking.handling.validation.constraints.UUID;
import io.fluxzero.sdk.tracking.handling.validation.constraints.UniqueElements;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
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

        assertEquals("3.0.1", document.path("openapi").asText());
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
        assertEquals("#/components/schemas/CreateReading", bodySchema.path("$ref").asText());
        assertEquals("number", document.path("components").path("schemas").path("CreateReading")
                .path("properties").path("value").path("type").asText());
        assertEquals("Measured value", document.path("components").path("schemas").path("CreateReading")
                .path("properties").path("value").path("description").asText());
        assertTrue(document.path("components").path("schemas").path("CreateReading")
                .path("properties").path("value").has("minimum"));
        assertEquals(0, document.path("components").path("schemas").path("CreateReading")
                .path("properties").path("value").path("minimum").asInt());
        assertFalse(contains(document.path("components").path("schemas").path("CreateReading").path("required"),
                             "tags"));

        JsonNode response200 = operation.path("responses").path("200");
        assertEquals("Reading created", response200.path("description").asText());
        assertEquals("#/components/schemas/ReadingCreated", response200.path("content").path("application/json")
                .path("schema").path("$ref").asText());
        assertEquals("string", document.path("components").path("schemas").path("ReadingCreated")
                .path("properties").path("readingId").path("type").asText());

        JsonNode response404 = operation.path("responses").path("404");
        assertEquals("Meter not found", response404.path("description").asText());
        assertEquals("#/components/schemas/NotFound", response404.path("content").path("application/json")
                .path("schema").path("$ref").asText());
        assertEquals("string", document.path("components").path("schemas").path("NotFound")
                .path("properties").path("message").path("type").asText());

        List<String> schemaNames = new ArrayList<>();
        document.path("components").path("schemas").fieldNames().forEachRemaining(schemaNames::add);
        assertEquals(schemaNames.stream().sorted().toList(), schemaNames);
    }

    @Test
    void rendersAnnotatedDocumentInfo() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(InfoHandler.class);

        JsonNode document = OpenApiRenderer.render(catalog);

        assertEquals("3.1.0", document.path("openapi").asText());
        assertEquals("Annotated API", document.path("info").path("title").asText());
        assertEquals("v9", document.path("info").path("version").asText());
        assertEquals("Annotated description", document.path("info").path("description").asText());
        assertEquals("Fluxzero", document.path("info").path("contact").path("name").asText());
        assertEquals("support@example.com", document.path("info").path("contact").path("email").asText());
        assertEquals("https://example.com/logo.png", document.path("info").path("x-logo").path("url").asText());
        assertEquals("Example logo", document.path("info").path("x-logo").path("altText").asText());
        assertEquals("https://api.example.com", document.path("servers").get(0).path("url").asText());
        assertEquals("Production", document.path("servers").get(0).path("description").asText());
        assertTrue(document.path("x-code-samples-enabled").asBoolean());
        assertEquals("Invalid request", document.path("components").path("responses").path("error")
                .path("description").asText());
        assertEquals("bearerAuth", document.path("security").get(0).fieldNames().next());
        assertEquals("http", document.path("components").path("securitySchemes").path("bearerAuth")
                .path("type").asText());
        JsonNode operation = document.path("paths").path("/info").path("get");
        assertEquals("bearerAuth", operation.path("security").get(0).fieldNames().next());
        assertEquals("#/components/responses/error", operation.path("responses").path("400").path("$ref").asText());
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForDocumentInfo() throws Exception {
        ApiDocCatalog catalog = manualCatalog(InfoHandler.class, "info");

        GeneratedOnlyMetadataMode.run(() -> {
            JsonNode document = OpenApiRenderer.render(catalog);

            assertEquals("Fluxzero API", document.path("info").path("title").asText());
            assertFalse(document.path("components").path("securitySchemes").has("bearerAuth"));
        });

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(InfoHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                JsonNode document = OpenApiRenderer.render(catalog);

                assertEquals("Annotated API", document.path("info").path("title").asText());
                assertEquals("v9", document.path("info").path("version").asText());
                assertEquals("bearerAuth", document.path("security").get(0).fieldNames().next());
                assertEquals("http", document.path("components").path("securitySchemes").path("bearerAuth")
                        .path("type").asText());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForSchemaAnnotations() throws Exception {
        Method method = SchemaMetadataHandler.class.getDeclaredMethod("schema", String.class);
        ApiDocCatalog catalog = new ApiDocCatalog(List.of(new ApiDocEndpoint(
                SchemaMetadataHandler.class,
                method,
                "",
                "/schema/{id}",
                HttpRequestMethod.GET,
                false,
                false,
                new ApiDocDetails("", "", "", List.of(), false, List.of()),
                List.of(new ApiDocParameter("id", WebParameterSource.PATH, String.class, method.getParameters()[0])),
                List.of(),
                method.getGenericReturnType(),
                List.of())));

        GeneratedOnlyMetadataMode.run(() -> {
            JsonNode document = OpenApiRenderer.render(catalog);
            JsonNode id = document.path("paths").path("/schema/{id}").path("get").path("parameters").get(0);
            JsonNode schema = document.path("components").path("schemas").path("SchemaMetadataDto");

            assertFalse(id.has("description"));
            assertFalse(schema.path("properties").path("label").has("description"));
            assertTrue(schema.path("properties").has("hidden"));
        });

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(SchemaMetadataHandler.class, SchemaMetadataDto.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                JsonNode document = OpenApiRenderer.render(catalog);
                JsonNode id = document.path("paths").path("/schema/{id}").path("get").path("parameters").get(0);
                JsonNode schema = document.path("components").path("schemas").path("SchemaMetadataDto");
                JsonNode properties = schema.path("properties");

                assertEquals("Metadata id", id.path("description").asText());
                assertEquals("Metadata label", properties.path("label").path("description").asText());
                assertTrue(contains(schema.path("required"), "label"));
                assertEquals(2, properties.path("amount").path("minimum").asInt());
                assertEquals(7, properties.path("amount").path("maximum").asInt());
                assertTrue(properties.path("tags").path("uniqueItems").asBoolean());
                assertFalse(properties.has("hidden"));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
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

    @Test
    void keepsTypeUseMetadataOnCollectionItems() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(TypeUseHandler.class);

        JsonNode document = OpenApiRenderer.render(catalog);
        JsonNode operation = document.path("paths").path("/connections").path("get");
        JsonNode schema = operation.path("responses").path("200").path("content").path("application/json")
                .path("schema");
        JsonNode items = schema.path("items");

        assertEquals("List connections", operation.path("description").asText());
        assertFalse(schema.has("description"));
        assertEquals("#/components/schemas/ConnectionDto", items.path("allOf").get(0).path("$ref").asText());
        assertEquals("A connection item", items.path("description").asText());
        assertEquals("string", document.path("components").path("schemas").path("ConnectionDto")
                .path("properties").path("id").path("type").asText());
    }

    @Test
    void includesDocumentedBeanAccessorsAsProperties() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(AccessorHandler.class);

        JsonNode document = OpenApiRenderer.render(catalog);
        JsonNode properties = document.path("components").path("schemas").path("AccessorDto").path("properties");

        assertEquals("string", properties.path("id").path("type").asText());
        assertEquals("Lombok-generated getter should keep field ApiDoc.",
                     properties.path("lombokField").path("description").asText());
        assertEquals("boolean", properties.path("active").path("type").asText());
        assertEquals("Whether this item is active", properties.path("active").path("description").asText());
        assertTrue(properties.path("active").path("default").asBoolean());
        assertEquals("string", properties.path("jsonValueId").path("type").asText());
        assertEquals("Json value id", properties.path("jsonValueId").path("description").asText());
        assertEquals("string", properties.path("opensAt").path("type").asText());
        assertEquals("partial-time", properties.path("opensAt").path("format").asText());
        assertEquals("string", properties.path("timeZone").path("type").asText());
        assertEquals("IANA timezone", properties.path("timeZone").path("format").asText());
        assertEquals("integer", properties.path("attempts").path("type").asText());
        assertEquals(0, properties.path("attempts").path("minimum").asInt());
        assertEquals(10, properties.path("attempts").path("maximum").asInt());
        assertEquals(5, properties.path("attempts").path("example").asInt());
        assertEquals(3, properties.path("attempts").path("default").asInt());
        assertEquals("primary", properties.path("status").path("enum").get(0).asText());
        assertEquals("secondary", properties.path("status").path("enum").get(1).asText());
        assertEquals("secondary", properties.path("status").path("example").asText());
        assertEquals(2, properties.path("ranged").path("minimum").asInt());
        assertEquals(7, properties.path("ranged").path("maximum").asInt());
        assertEquals(3, properties.path("lengthLimited").path("minLength").asInt());
        assertEquals(12, properties.path("lengthLimited").path("maxLength").asInt());
        assertEquals("uri", properties.path("homepage").path("format").asText());
        assertEquals("uuid", properties.path("externalId").path("format").asText());
        assertTrue(properties.path("uniqueTags").path("uniqueItems").asBoolean());
        assertFalse(properties.has("secret"));
        JsonNode required = document.path("components").path("schemas").path("AccessorDto").path("required");
        assertTrue(contains(required, "status"));
        assertTrue(contains(required, "aliases"));
        assertFalse(document.path("components").path("schemas").has("JsonValueId"));
    }

    @ApiDoc(description = "Meter endpoints", tags = "Meters")
    static class MeterHandler {
        @ApiDoc(summary = "Create reading", operationId = "createReading", tags = "Readings")
        @ApiDocResponse(status = 200, description = "Reading created")
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
        @ApiDoc
        @HandlePost("/uploads")
        void upload(@FormParam("name") String name, @FormParam("file") WebFormPart file) {
        }
    }

    static class BodyParamHandler {
        @ApiDoc
        @HandlePost("/filters")
        String search(@BodyParam("from") java.time.Instant from, @BodyParam("count") int count) {
            return null;
        }
    }

    static class WebsocketHandler {
        @ApiDoc
        @HandleSocketMessage("/socket")
        void message(String message) {
        }
    }

    static class TypeUseHandler {
        @ApiDoc(description = "List connections")
        @HandleGet("/connections")
        List<@ApiDoc(description = "A connection item") ConnectionDto> list() {
            return null;
        }
    }

    @ApiDoc
    static class AccessorHandler {
        @HandleGet("/accessor")
        AccessorDto get() {
            return null;
        }
    }

    @ApiDocInfo(
            openApiVersion = "3.1.0",
            title = "Annotated API",
            version = "v9",
            description = "Annotated description",
            contactName = "Fluxzero",
            contactEmail = "support@example.com",
            logoUrl = "https://example.com/logo.png",
            logoAltText = "Example logo",
            servers = @ApiDocServer(url = "https://api.example.com", description = "Production"),
            security = "bearerAuth",
            components = {
                    @ApiDocComponent(path = "responses.error", json = """
                            {"description":"Invalid request"}
                            """),
                    @ApiDocComponent(path = "securitySchemes.bearerAuth", json = """
                            {"type":"http","scheme":"bearer"}
                            """)
            },
            extensions = "x-code-samples-enabled=true")
    @ApiDoc(security = "bearerAuth")
    static class InfoHandler {
        @HandleGet("/info")
        @ApiDocResponse(status = 400, ref = "error")
        String info() {
            return null;
        }
    }

    static class SchemaMetadataHandler {
        @HandleGet("/schema/{id}")
        SchemaMetadataDto schema(@ApiDoc(description = "Metadata id") @PathParam("id") String id) {
            return null;
        }
    }

    record CreateReading(
            @ApiDoc(description = "Measured value") @jakarta.validation.constraints.PositiveOrZero
            java.math.BigDecimal value,
            List<String> tags) {
    }

    record ReadingCreated(String readingId) {
    }

    record NotFound(String message) {
    }

    record ConnectionDto(String id) {
    }

    record SchemaMetadataDto(
            @ApiDoc(description = "Metadata label", required = true) String label,
            @Range(min = 2, max = 7) int amount,
            @UniqueElements List<String> tags,
            @ApiDocExclude String hidden) {
    }

    static class AccessorDto {
        String id;
        @ApiDoc(description = "Lombok-generated getter should keep field ApiDoc.")
        @lombok.Getter
        String lombokField;
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
        @Range(min = 2, max = 7)
        int ranged;
        @Length(min = 3, max = 12)
        String lengthLimited;
        @URL
        String homepage;
        @UUID
        String externalId;
        @UniqueElements
        List<String> uniqueTags;
        @ApiDocExclude
        String secret;
        List<String> aliases;

        @ApiDoc(description = "Whether this item is active", defaultValue = "true")
        public boolean isActive() {
            return true;
        }
    }

    static class JsonValueId {
        @com.fasterxml.jackson.annotation.JsonValue
        String value;
    }

    private static ApiDocCatalog manualCatalog(Class<?> handlerType, String methodName) throws NoSuchMethodException {
        Method method = handlerType.getDeclaredMethod(methodName);
        return new ApiDocCatalog(List.of(new ApiDocEndpoint(
                handlerType,
                method,
                "",
                "/" + methodName,
                HttpRequestMethod.GET,
                false,
                false,
                new ApiDocDetails("", "", "", List.of(), false, List.of()),
                List.of(),
                List.of(),
                method.getGenericReturnType(),
                List.of())));
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
