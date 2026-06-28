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

import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.apidoc.excluded.ExcludedApiDocHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.List;

import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiDocExtractorTest {

    @Test
    void infersRoutesParametersBodyAndResponse() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(AutoHandler.class);

        assertEquals(1, catalog.endpoints().size());
        ApiDocEndpoint endpoint = catalog.endpoints().getFirst();

        assertEquals("/v1/meters/{meterId}/readings", endpoint.path());
        assertEquals(POST, endpoint.method());
        assertEquals(ReadingResponse.class, endpoint.responseType());
        assertEquals(List.of(CreateReading.class), endpoint.requestBodies().stream()
                .map(ApiDocRequestBody::type).toList());
        assertParameter(endpoint, "meterId", WebParameterSource.PATH, String.class);
        assertParameter(endpoint, "limit", WebParameterSource.QUERY, int.class);
        assertParameter(endpoint, "X-Tenant", WebParameterSource.HEADER, String.class);
        assertParameter(endpoint, "session", WebParameterSource.COOKIE, String.class);
        assertParameter(endpoint, "comment", WebParameterSource.FORM, String.class);
    }

    @Test
    void expandsOptionalRouteFragments() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(OptionalHandler.class);

        assertEquals(List.of("/optional/users/{id}", "/optional/users"),
                     catalog.endpoints().stream().map(ApiDocEndpoint::path).toList());
        ApiDocEndpoint withId = catalog.endpoints().getFirst();
        ApiDocEndpoint withoutId = catalog.endpoints().get(1);

        assertParameter(withId, "id", WebParameterSource.PATH, String.class);
        assertTrue(withoutId.parameters().isEmpty());
    }

    @Test
    void mergesDocumentationMetadataAndRepeatableResponses() {
        ApiDocCatalog catalog = ApiDocExtractor.extract(DocumentedHandler.class);

        ApiDocEndpoint endpoint = catalog.endpoints().getFirst();

        assertEquals("Find meter", endpoint.documentation().summary());
        assertEquals("Meter endpoints", endpoint.documentation().description());
        assertEquals("findMeter", endpoint.documentation().operationId());
        assertEquals(List.of("Meters", "Reads"), endpoint.documentation().tags());
        assertTrue(endpoint.documentation().deprecated());
        assertEquals(List.of(401, 404), endpoint.responses().stream().map(ApiDocResponseDescriptor::status).toList());
        assertEquals("Endpoint-specific unauthorized response", endpoint.responses().getFirst().description());
        assertEquals(NotFound.class, endpoint.responses().get(1).type());
    }

    @Test
    void excludesPackagesTypesAndMethodsFromDocsOnly() {
        assertEquals(List.of("/visible"), ApiDocExtractor.extract(PartiallyExcludedHandler.class)
                .endpoints().stream().map(ApiDocEndpoint::path).toList());
        assertTrue(ApiDocExtractor.extract(ExcludedHandler.class).endpoints().isEmpty());
        assertTrue(ApiDocExtractor.extract(ExcludedApiDocHandler.class).endpoints().isEmpty());
    }

    @Test
    void onlyIncludesHandlersOptedInWithApiDoc() {
        assertTrue(ApiDocExtractor.extract(UndocumentedHandler.class).endpoints().isEmpty());
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForUnregisteredApiDocMetadata() {
        @Path("/unregistered")
        @ApiDoc(description = "Unregistered docs")
        class UnregisteredGeneratedOnlyDocHandler {
            @ApiDoc(summary = "Find unregistered item")
            @HandleGet("/items/{id}")
            ReadingResponse find(@PathParam("id") String id) {
                return null;
            }
        }

        GeneratedOnlyMetadataMode.run(() -> {
            ApiDocCatalog catalog = ApiDocExtractor.extract(UnregisteredGeneratedOnlyDocHandler.class);

            assertTrue(catalog.endpoints().isEmpty());
        });
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForApiDocMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(GeneratedOnlyDocHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                ApiDocCatalog catalog = ApiDocExtractor.extract(GeneratedOnlyDocHandler.class);

                assertEquals(1, catalog.endpoints().size());
                ApiDocEndpoint endpoint = catalog.endpoints().getFirst();
                assertEquals("/generated/items/{id}", endpoint.path());
                assertEquals("Find generated item", endpoint.documentation().summary());
                assertEquals("Generated docs", endpoint.documentation().description());
                assertEquals(List.of(401, 404), endpoint.responses().stream()
                        .map(ApiDocResponseDescriptor::status).toList());
                assertParameter(endpoint, "id", WebParameterSource.PATH, String.class);
                assertParameter(endpoint, "view", WebParameterSource.QUERY, String.class);
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForUnregisteredAutomaticApiDocEndpoints() throws Exception {
        @Path("/unregistered-api")
        @ApiDocInfo(
                title = "Unregistered API",
                serveOpenApi = true,
                openApiPath = "spec.json",
                serveApiReference = true,
                apiReferencePath = "reference")
        class UnregisteredGeneratedOnlyInfoHandler {
            @ApiDoc
            @HandleGet("/items")
            String list() {
                return "ok";
            }
        }

        GeneratedOnlyMetadataMode.run(() -> {
            List<OpenApiDocumentEndpoint> documentEndpoints = OpenApiDocumentEndpoint.forHandler(
                    UnregisteredGeneratedOnlyInfoHandler.class,
                    new UnregisteredGeneratedOnlyInfoHandler());
            List<ApiReferenceEndpoint> referenceEndpoints =
                    ApiReferenceEndpoint.forHandler(UnregisteredGeneratedOnlyInfoHandler.class);

            assertTrue(documentEndpoints.isEmpty());
            assertTrue(referenceEndpoints.isEmpty());
        });

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(GeneratedOnlyInfoHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                List<OpenApiDocumentEndpoint> documentEndpoints = OpenApiDocumentEndpoint.forHandler(
                        GeneratedOnlyInfoHandler.class, new GeneratedOnlyInfoHandler());
                List<ApiReferenceEndpoint> referenceEndpoints = ApiReferenceEndpoint.forHandler(
                        GeneratedOnlyInfoHandler.class);

                assertFalse(documentEndpoints.isEmpty());
                assertFalse(referenceEndpoints.isEmpty());
                assertEquals("/api/spec.json", endpointPath(documentEndpoints.getFirst()));
                assertEquals("/api/reference", endpointPath(referenceEndpoints.getFirst()));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static String endpointPath(Object endpoint) throws ReflectiveOperationException {
        Field field = endpoint.getClass().getDeclaredField("path");
        field.setAccessible(true);
        return (String) field.get(endpoint);
    }

    private static void assertParameter(ApiDocEndpoint endpoint, String name, WebParameterSource source, Type type) {
        ApiDocParameter parameter = endpoint.parameters().stream()
                .filter(p -> p.source() == source && p.name().equals(name))
                .findFirst().orElseThrow();
        assertEquals(type, parameter.type());
    }

    @Path("/v1")
    @ApiDoc
    static class AutoHandler {
        @HandlePost("/meters/{meterId}/readings")
        ReadingResponse createReading(
                @PathParam("meterId") String meterId,
                @QueryParam("limit") int limit,
                @HeaderParam("X-Tenant") String tenant,
                @CookieParam("session") String session,
                @FormParam("comment") String comment,
                CreateReading body,
                WebRequest request,
                User user) {
            return null;
        }
    }

    @Path("/optional")
    @ApiDoc
    static class OptionalHandler {
        @HandleGet("/users[/{id}]")
        String getUser(@PathParam("id") String id) {
            return id;
        }
    }

    @ApiDoc(description = "Meter endpoints", tags = "Meters")
    @ApiDocResponse(status = 401, description = "Unauthorized")
    static class DocumentedHandler {
        @ApiDoc(summary = "Find meter", operationId = "findMeter", tags = "Reads", deprecated = true)
        @ApiDocResponse(status = 401, description = "Endpoint-specific unauthorized response")
        @ApiDocResponse(status = 404, description = "Meter not found", type = NotFound.class)
        @HandleGet("/meters/{id}")
        ReadingResponse findMeter(@PathParam("id") String id) {
            return null;
        }
    }

    @ApiDoc
    static class PartiallyExcludedHandler {
        @HandleGet("/visible")
        String visible() {
            return "visible";
        }

        @ApiDocExclude
        @HandleGet("/hidden")
        String hidden() {
            return "hidden";
        }
    }

    @ApiDocExclude
    static class ExcludedHandler {
        @HandleGet("/hidden")
        String hidden() {
            return "hidden";
        }
    }

    static class UndocumentedHandler {
        @HandleGet("/internal")
        String internal() {
            return "internal";
        }
    }

    @Path("/generated")
    @ApiDoc(description = "Generated docs")
    @ApiDocResponse(status = 401, description = "Unauthorized")
    static class GeneratedOnlyDocHandler {
        @ApiDoc(summary = "Find generated item")
        @ApiDocResponse(status = 404, description = "Not found", type = NotFound.class)
        @HandleGet("/items/{id}")
        ReadingResponse find(@PathParam("id") String id, @QueryParam("view") String view) {
            return null;
        }
    }

    @Path("/api")
    @ApiDocInfo(
            title = "Generated API",
            serveOpenApi = true,
            openApiPath = "spec.json",
            serveApiReference = true,
            apiReferencePath = "reference")
    static class GeneratedOnlyInfoHandler {
        @ApiDoc
        @HandleGet("/items")
        String list() {
            return "ok";
        }
    }

    record CreateReading(String value) {
    }

    record ReadingResponse(String value) {
    }

    record NotFound(String message) {
    }
}
