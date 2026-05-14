/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.FileUtils;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.ThrowingPredicate;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.Request;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthenticatedException;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.web.SocketEndpoint.AliveCheck;
import io.fluxzero.sdk.web.path.ClassPathHandler;
import io.fluxzero.sdk.web.path.PackagePathHandler;
import io.fluxzero.sdk.web.path.subpath.ExternalUrlHandler;
import io.fluxzero.sdk.web.path.subpath.ExternalUrlPathHandler;
import io.fluxzero.sdk.web.path.subpath.PrefixedExternalUrlHandler;
import io.fluxzero.sdk.web.path.subpath.ResetPathHandler;
import io.fluxzero.sdk.web.path.subpath.SubPathHandler;
import io.fluxzero.sdk.web.openapi.PackageDocHandler;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static io.fluxzero.sdk.web.HttpRequestMethod.ANY;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.HEAD;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static io.fluxzero.sdk.web.HttpRequestMethod.PUT;
import static io.fluxzero.sdk.web.HttpRequestMethod.TRACE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_CLOSE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_HANDSHAKE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_MESSAGE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_OPEN;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_PONG;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

public class HandleWebTest {

    @Nested
    class GenericTests {
        private final TestFixture testFixture = TestFixture.create(DefaultFluxzero.builder().registerUserProvider(
                new FixedUserProvider(() -> null)), new Handler());

        @Test
        void testGet() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("/get").build()).expectResult("get");
        }

        @Test
        void testGetViaPath() {
            testFixture.whenGet("/getViaPath").expectResult("getViaPath");
        }

        @Test
        void testGet_shortHand() {
            testFixture.whenGet("/get").expectResult("get");
        }

        @Test
        void testGetWithWildcardSegmentInMiddle() {
            testFixture.whenGet("/a/anything/b").expectResult("middleWildcard");
        }

        @Test
        void testMostSpecificGetWinsOverWildcardSegment() {
            testFixture.whenGet("/a/b/c").expectResult("specific");
        }

        @Test
        void testHeadFallsBackToGetWithoutBody() {
            testFixture.whenWebRequest(WebRequest.builder().method(HEAD).url("/get").build())
                    .expectWebResult(r -> r.getStatus() == 200
                                          && "text/plain".equals(r.getContentType())
                                          && r.getPayload() == null);
        }

        @Test
        void testHeadFallbackCanBeDisabled() {
            testFixture.whenWebRequest(WebRequest.builder().method(HEAD).url("/getWithoutAutoHead").build())
                    .expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void testExplicitHeadInOtherHandlerWinsOverAutomaticGetHead() {
            TestFixture.create(new GetHeadFallbackHandler(), new ExplicitHeadHandler())
                    .whenWebRequest(WebRequest.builder().method(HEAD).url("/sharedHead").build())
                    .expectWebResult(r -> r.getStatus() == 209
                                          && "explicitHead".equals(r.getHeader("X-Handler"))
                                          && r.getPayload() == null);
        }

        @Test
        void testExplicitWildcardHeadInOtherHandlerWinsOverAutomaticGetHead() {
            TestFixture.create(new GetHeadFallbackHandler(), new ExplicitWildcardHeadHandler())
                    .whenWebRequest(WebRequest.builder().method(HEAD).url("/wildcardHead/value").build())
                    .expectWebResult(r -> r.getStatus() == 210
                                          && "explicitWildcardHead".equals(r.getHeader("X-Handler"))
                                          && r.getPayload() == null);
        }

        @Test
        void testAnyHandlerInOtherHandlerWinsOverAutomaticGetHead() {
            TestFixture.create(new GetHeadFallbackHandler(), new ExplicitAnyHandler())
                    .whenWebRequest(WebRequest.builder().method(HEAD).url("/anyHead").build())
                    .expectWebResult(r -> r.getStatus() == 211
                                          && "anyHead".equals(r.getHeader("X-Handler"))
                                          && r.getPayload() == null);
        }

        @Test
        void testAutomaticOptionsIncludesAllowedMethods() {
            testFixture.whenWebRequest(WebRequest.builder().method(OPTIONS).url("/automaticOptions").build())
                    .expectWebResult(r -> r.getStatus() == 204
                                          && "GET, HEAD, POST, OPTIONS".equals(r.getHeader("Allow")));
        }

        @Test
        void testAutomaticOptionsCanBeDisabled() {
            testFixture.whenWebRequest(WebRequest.builder().method(OPTIONS).url("/withoutAutomaticOptions").build())
                    .expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void testExplicitOptionsInOtherHandlerWinsOverAutomaticOptions() {
            TestFixture.create(new GetOptionsFallbackHandler(), new ExplicitOptionsHandler())
                    .whenWebRequest(WebRequest.builder().method(OPTIONS).url("/sharedOptions").build())
                    .expectResult("explicitOptions");
        }

        @Test
        void testExplicitWildcardOptionsInOtherHandlerWinsOverAutomaticOptions() {
            TestFixture.create(new GetOptionsFallbackHandler(), new ExplicitWildcardOptionsHandler())
                    .whenWebRequest(WebRequest.builder().method(OPTIONS).url("/wildcardOptions/value").build())
                    .expectResult("explicitWildcardOptions");
        }

        @Test
        void testAnyHandlerInOtherHandlerWinsOverAutomaticOptions() {
            TestFixture.create(new GetOptionsFallbackHandler(), new ExplicitAnyHandler())
                    .whenWebRequest(WebRequest.builder().method(OPTIONS).url("/anyOptions").build())
                    .expectResult("anyOptions");
        }

        @Test
        void testApiDocInfoServesOpenApiBelowClassPath() {
            TestFixture.create(new OpenApiEndpointHandler())
                    .whenGet("/classDocs/openapi.json")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        JsonNode document = JsonUtils.fromJson(payload, JsonNode.class);
                        return response.getStatus() == 200
                               && "application/json".equals(response.getContentType())
                               && "Class Docs".equals(document.path("info").path("title").asText())
                               && document.path("paths").has("/classDocs/meters");
                    });
        }

        @Test
        void testApiDocInfoServesRedocReferenceBelowClassPath() {
            TestFixture.create(new OpenApiEndpointHandler())
                    .whenGet("/classDocs/docs")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        return response.getStatus() == 200
                               && "text/html; charset=utf-8".equals(response.getContentType())
                               && payload.contains("<redoc spec-url=\"/classDocs/openapi.json\"")
                               && payload.contains("cdn.redoc.ly/redoc");
                    });
        }

        @Test
        void testApiDocInfoServesOpenApiAtConfiguredAbsolutePath() {
            TestFixture.create(new AbsoluteOpenApiEndpointHandler())
                    .whenGet("/docs/openapi.json")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        JsonNode document = JsonUtils.fromJson(payload, JsonNode.class);
                        return response.getStatus() == 200
                               && "Absolute Docs".equals(document.path("info").path("title").asText())
                               && document.path("paths").has("/absoluteDocs/items");
                    });
        }

        @Test
        void testApiReferenceAlsoServesOpenApiDocument() {
            TestFixture.create(new ScalarReferenceEndpointHandler())
                    .whenGet("/scalarDocs/openapi.json")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        JsonNode document = JsonUtils.fromJson(payload, JsonNode.class);
                        return response.getStatus() == 200
                               && "Scalar Docs".equals(document.path("info").path("title").asText())
                               && document.path("paths").has("/scalarDocs/items");
                    });
        }

        @Test
        void testApiDocInfoServesScalarReference() {
            TestFixture.create(new ScalarReferenceEndpointHandler())
                    .whenGet("/scalarDocs/reference")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        return response.getStatus() == 200
                               && payload.contains("Scalar.createApiReference('#app', { url: '/scalarDocs/openapi.json' })")
                               && payload.contains("@scalar/api-reference");
                    });
        }

        @Test
        void testApiDocInfoServesSwaggerUiReferenceAtConfiguredAbsolutePath() {
            TestFixture.create(new SwaggerUiReferenceEndpointHandler())
                    .whenGet("/swagger-ui")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        return response.getStatus() == 200
                               && payload.contains("SwaggerUIBundle({ url: '/swaggerDocs/openapi.json'")
                               && payload.contains("swagger-ui-dist@5/swagger-ui.css")
                               && payload.contains("swagger-ui-dist@5/swagger-ui-bundle.js");
                    });
        }

        @Test
        void testApiReferenceUsesConfiguredAssetUrls() {
            TestFixture.create(new CustomReferenceAssetEndpointHandler())
                    .whenGet("/customDocs/docs")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        return response.getStatus() == 200
                               && payload.contains("<link rel=\"stylesheet\" href=\"/assets/swagger-ui.css\">")
                               && payload.contains("<script src=\"/assets/swagger-ui.js\"></script>")
                               && !payload.contains("swagger-ui-dist@5");
                    });
        }

        @Test
        void testPackageApiDocInfoServesOpenApiBelowPackagePath() {
            TestFixture.create(new PackageDocHandler())
                    .whenGet("/packageDocs/openapi.json")
                    .expectWebResult(response -> {
                        String payload = response.getPayloadAs(String.class);
                        JsonNode document = JsonUtils.fromJson(payload, JsonNode.class);
                        return response.getStatus() == 200
                               && "Package Docs".equals(document.path("info").path("title").asText())
                               && document.path("paths").has("/packageDocs/items");
                    });
        }

        @Test
        void testAutomaticOptionsIncludesAutomaticOpenApiEndpoint() {
            TestFixture.create(new OpenApiEndpointHandler())
                    .whenWebRequest(WebRequest.builder().method(OPTIONS).url("/classDocs/openapi.json").build())
                    .expectWebResult(response -> response.getStatus() == 204
                                                 && "GET, HEAD, OPTIONS".equals(response.getHeader("Allow")));
        }

        @Test
        void testAutomaticOptionsIncludesAutomaticApiReferenceEndpoint() {
            TestFixture.create(new OpenApiEndpointHandler())
                    .whenWebRequest(WebRequest.builder().method(OPTIONS).url("/classDocs/docs").build())
                    .expectWebResult(response -> response.getStatus() == 204
                                                 && "GET, HEAD, OPTIONS".equals(response.getHeader("Allow")));
        }

        @Test
        void testGet_withSpecificInstantParameters() {
            testFixture.atFixedTime(Instant.parse("2025-07-09T00:00:00Z"))
                    .whenWebRequest(WebRequest.post("/instantBinding/2025-07-05T00:00:00Z"
                                                      + "?query=2025-07-06T00:00:00Z")
                                              .header("header", "2025-07-07T00:00:00Z")
                            .body("""
                                    {
                                      "body": "2025-07-08T00:00:00Z"
                                    }
                                    """).build())
                    .mapWebResultMessage(WebResponse::getPayload)
                    .expectResult(Stream.of(
                            "2025-07-05T00:00:00Z",
                            "2025-07-06T00:00:00Z",
                            "2025-07-07T00:00:00Z",
                            "2025-07-08T00:00:00Z",
                            "2025-07-09T00:00:00Z")
                            .map(Instant::parse).toList());
        }

        @Test
        void testWebParameterSources() {
            testFixture.whenWebRequest(WebRequest.post("/parameterBinding/pathValue?query=queryValue")
                            .header("header", "headerValue")
                            .header("Cookie", "cookie=cookieValue")
                            .contentType("application/x-www-form-urlencoded")
                            .payload("form=formValue").build())
                    .expectResult(List.of("pathValue", "queryValue", "headerValue", "cookieValue", "formValue"));
        }

        @Test
        void testMultipartFormParameterSources() {
            String boundary = "FluxzeroBoundaryX";
            testFixture.whenWebRequest(WebRequest.post("/multipart")
                            .contentType("multipart/form-data; boundary=\"" + boundary + "\"")
                            .payload(multipartBody(boundary)).build())
                    .expectResult(List.of(
                            "meter readings",
                            "alpha,beta",
                            "readings.csv",
                            "text/csv",
                            "upload-1",
                            "meterId,reading\n123,42\n",
                            "meterId,reading\n123,42\n"));
        }

        @Test
        void testGet_disabled() {
            testFixture.whenGet("/disabled").expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void testGetFullUrl() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("http://localhost:8080/get").build())
                    .expectResult("get8080");
        }

        @Test
        void testGetFullUrl_other() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("http://localhost:8080/other").build())
                    .expectResult("other8080");
        }

        @Test
        void testGetFullUrl_otherPort() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("http://localhost:8081/get").build())
                    .expectResult("get8081");
        }

        @Test
        void testPostString() {
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/string").payload("payload").build())
                    .expectResult("payload")
                    .<WebResponse>expectResultMessage(r -> r.getStatus() == 200)
                    .mapResult(r -> (String) r)
                    .expectResult("payload");
        }

        @Test
        void testPostWithoutResult() {
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/noResult").payload("payload").build())
                    .expectNoResult()
                    .<WebResponse>expectResultMessage(r -> r.getStatus() == 204);
        }

        @Test
        void testPostString_shortHand() {
            testFixture.whenPost("/string", "payload")
                    .expectResult("payload")
                    .<WebResponse>expectResultMessage(r -> r.getStatus() == 200);
        }

        @Test
        void testGetStreamingSearchResponse() {
            TestFixture.create(new StreamingSearchHandler())
                    .givenDocument(new StreamingSearchDocument("foo", Instant.parse("2024-01-01T00:00:00Z")))
                    .givenDocument(new StreamingSearchDocument("bar", Instant.parse("2024-01-01T00:00:01Z")))
                    .whenGet("/searchStream")
                    .expectWebResult(r -> {
                        try (InputStream input = r.getPayload()) {
                            String expected = JsonUtils.asJson(new StreamingSearchDocument("foo",
                                                                                           Instant.parse("2024-01-01T00:00:00Z")))
                                              + "\n"
                                              + JsonUtils.asJson(new StreamingSearchDocument("bar",
                                                                                             Instant.parse("2024-01-01T00:00:01Z")))
                                              + "\n";
                            return expected.equals(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    });
        }

        @Test
        void testPostBytes() {
            testFixture.whenWebRequest(
                            WebRequest.builder().method(POST).url("/bytes").payload("payload".getBytes()).build())
                    .expectResult("payload".getBytes());
        }

        @Test
        void testPostObject() {
            var object = Map.of("foo", "bar");
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/object")
                                               .payload(object).build()).expectResult(object);
        }

        @Test
        void testPostPayload() {
            var object = new Payload();
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/payload")
                                               .payload(object).build()).expectResult(object);
        }

        @Test
        void testPostPayloadAsString() {
            var object = new Payload();
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/payload")
                                               .payload(JsonUtils.asPrettyJson(object)).build()).expectResult(object);
        }

        @Test
        void testGenericPayloadType() {
            var payload = List.of(new GenericPayload("a"), new GenericPayload("b"));
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/genericPayload")
                                               .contentType("application/json")
                                               .payload(JsonUtils.asJson(payload)).build())
                    .expectResult(List.of("a", "b"));
        }

        @Test
        void testPostJson() {
            var object = Map.of("foo", "bar");
            testFixture.whenWebRequest(
                            WebRequest.builder().method(POST).url("/json").payload(object).build())
                    .expectResult(JsonUtils.<JsonNode>valueToTree(object))
                    .expectResultMessage(r -> r.getPayloadAs(Map.class).equals(object));
        }

        @Test
        void testPostJsonFromFile() {
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/json").payload("/web/body.json").build())
                    .expectResult("/web/body.json");
        }

        @Test
        void testPostJsonFromFile_given() {
            testFixture.givenWebRequest(
                            WebRequest.builder().method(POST).url("/json").payload("/web/body.json").build())
                    .whenNothingHappens().expectNoErrors();
        }

        @Test
        void testPostJsonFromFile_given_shortHand() {
            testFixture.givenPost("/json", "/web/body.json")
                    .whenNothingHappens().expectNoErrors();
        }

        @Test
        void testWithoutSlash() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("get").build()).expectResult("get");
        }

        @Test
        void testTrailingSlashIsIgnored() {
            testFixture.whenGet("/get/").expectResult("get");
            testFixture.whenGet("/trailingSlash").expectResult("trailingSlash");
        }

        @Test
        void testOptionalSegment() {
            testFixture.whenGet("/optional").expectResult("optional");
            testFixture.whenGet("/optional/42").expectResult("optional:42");
        }

        @Test
        void testLiteralRouteWinsOverOptionalSegment() {
            testFixture.whenGet("/optional/literal").expectResult("optionalLiteral");
        }

        @Test
        void testWrongPath() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("/unknown").build())
                    .expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void testPostPayloadRequiringUser_validWithUser() {
            PayloadRequiringUser object = new PayloadRequiringUser();
            TestFixture.create(DefaultFluxzero.builder().registerUserProvider(
                            new FixedUserProvider(new MockUser())), new Handler())
                    .whenWebRequest(WebRequest.builder().method(POST).url("/requiresUser").payload(object).build())
                    .expectResult(object);
        }

        @Test
        void testPostPayloadRequiringUser_invalidWithoutUser() {
            testFixture.whenWebRequest(WebRequest.builder().method(POST).url("/requiresUser")
                                               .payload(new PayloadRequiringUser()).build())
                    .expectExceptionalResult(UnauthenticatedException.class);
        }

        @Test
        void testGetUser_validWithUser() {
            TestFixture.create(DefaultFluxzero.builder().registerUserProvider(
                            new FixedUserProvider(new MockUser())), new Handler())
                    .whenGet("/getUser")
                    .expectResult(User.class)
                    .expectWebResponse(r -> r.getStatus() == 200);
        }

        @Test
        void testGetUser_exceptionWithoutUser() {
            testFixture.whenGet("/getUser")
                    .expectExceptionalResult(UnauthenticatedException.class);
        }

        @Test
        void testGetUserIfAuthenticated_timeoutWithoutUser() {
            testFixture.whenGet("/getUserIfAuthenticated")
                    .expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void testExpectWebRequestEmptyPayload() {
            TestFixture.create(new Object() {
                        @HandlePost("/foo")
                        void foo() {
                        }
                    }).whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                            WebRequest.builder().method(POST).url("/foo").build()))
                    .expectWebRequest(r -> r.getMethod() == POST);

        }

        private class Handler {
            @Path("/getViaPath")
            @HandleWeb(method = GET)
            String getViaPath() {
                return "getViaPath";
            }

            @HandleGet("/get")
            String get() {
                return "get";
            }

            @HandleGet("/trailingSlash/")
            String trailingSlash() {
                return "trailingSlash";
            }

            @HandleGet("/optional[/{id}]")
            String optional(@PathParam("id") String id) {
                return id == null ? "optional" : "optional:" + id;
            }

            @HandleGet("/optional/literal")
            String optionalLiteral() {
                return "optionalLiteral";
            }

            @HandleGet(value = "/getWithoutAutoHead", autoHead = false)
            String getWithoutAutoHead() {
                return "getWithoutAutoHead";
            }

            @HandleGet("/automaticOptions")
            String getAutomaticOptions() {
                return "getAutomaticOptions";
            }

            @HandlePost("/automaticOptions")
            String postAutomaticOptions() {
                return "postAutomaticOptions";
            }

            @HandleGet(value = "/withoutAutomaticOptions", autoOptions = false)
            String getWithoutAutomaticOptions() {
                return "withoutAutomaticOptions";
            }

            @HandleGet("/a/*/b")
            String getWithWildcardSegmentInMiddle() {
                return "middleWildcard";
            }

            @HandleGet("/a/*/c")
            String getWithWildcardSegment() {
                return "wildcard";
            }

            @HandleGet("/a/b/c")
            String getSpecific() {
                return "specific";
            }

            @HandlePost("/instantBinding/{path}")
            List<Instant> instantBinding(@PathParam("path") Instant path,
                                         @QueryParam("query") Instant query,
                                         @HeaderParam("header") Instant header,
                                         @BodyParam("body") Instant body,
                                         Instant timestamp) {
                return List.of(path, query, header, body, timestamp);
            }

            @HandlePost("/parameterBinding/{path}")
            List<String> parameterBinding(@PathParam("path") String path,
                                          @QueryParam("query") String query,
                                          @HeaderParam("header") String header,
                                          @CookieParam("cookie") String cookie,
                                          @FormParam("form") String form) {
                return List.of(path, query, header, cookie, form);
            }

            @SneakyThrows
            @HandlePost("/multipart")
            List<String> multipart(@FormParam("description") String description,
                                   @FormParam("tag") List<WebFormPart> tags,
                                   @FormParam("upload") byte[] upload,
                                   @FormParam("upload") InputStream uploadStream,
                                   @FormParam("upload") WebFormPart uploadPart) {
                return List.of(description, tags.getFirst().asString() + "," + tags.get(1).asString(),
                               uploadPart.getFileName(), uploadPart.getContentType(),
                               uploadPart.getHeader("X-Part-Id").orElse(""),
                               new String(uploadStream.readAllBytes(), StandardCharsets.UTF_8),
                               new String(upload, StandardCharsets.UTF_8));
            }

            @HandleWeb(value = "/disabled", method = GET, disabled = true)
            String disabledGet() {
                return "get";
            }

            @HandleWeb(value = "http://localhost:8080/get", method = GET)
            String get_8080() {
                return "get8080";
            }

            @HandleWeb(value = "http://localhost:8080/other", method = GET)
            String getOther_8080() {
                return "other8080";
            }

            @HandleWeb(value = "http://localhost:8081/get", method = GET)
            String get_8081() {
                return "get8081";
            }

            @HandleWeb(value = "/string", method = POST)
            String post(String body) {
                return body;
            }

            @HandleWeb(value = "/noResult", method = POST)
            void postWithoutResult(String body) {
                //no op
            }

            @HandleWeb(value = "/bytes", method = POST)
            byte[] post(byte[] body) {
                return body;
            }

            @HandleWeb(value = "/object", method = POST)
            Object post(Object body) {
                return body;
            }

            @HandleWeb(value = "/payload", method = POST)
            Object post(Payload body) {
                return body;
            }

            @HandleWeb(value = "/genericPayload", method = POST)
            List<String> post(List<GenericPayload> body) {
                return body.stream().map(GenericPayload::getValue).toList();
            }

            @HandleWeb(value = "/requiresUser", method = POST)
            Object post(PayloadRequiringUser body) {
                return body;
            }

            @HandleGet("/getUser")
            @RequiresUser
            Object getUser(User user) {
                return user;
            }

            @HandleGet("/getUserIfAuthenticated")
            @RequiresUser(throwIfUnauthorized = false)
            Object getUserIfAuthenticated(User user) {
                return user;
            }

            @HandleWeb(value = "/json", method = POST)
            Object post(JsonNode body) {
                return body;
            }
        }

        private static class GetHeadFallbackHandler {
            @HandleGet("/sharedHead")
            String sharedHead() {
                return "getHead";
            }

            @HandleGet("/wildcardHead/value")
            String wildcardHead() {
                return "getWildcardHead";
            }

            @HandleGet("/anyHead")
            String anyHead() {
                return "getAnyHead";
            }
        }

        private static class ExplicitHeadHandler {
            @HandleHead("/sharedHead")
            WebResponse sharedHead() {
                return WebResponse.builder().status(209).header("X-Handler", "explicitHead")
                        .payload("explicitHead").build();
            }
        }

        private static class ExplicitWildcardHeadHandler {
            @HandleHead("/wildcardHead/*")
            WebResponse wildcardHead() {
                return WebResponse.builder().status(210).header("X-Handler", "explicitWildcardHead")
                        .payload("explicitWildcardHead").build();
            }
        }

        private static class GetOptionsFallbackHandler {
            @HandleGet("/sharedOptions")
            String sharedOptions() {
                return "getOptions";
            }

            @HandleGet("/wildcardOptions/value")
            String wildcardOptions() {
                return "getWildcardOptions";
            }

            @HandleGet("/anyOptions")
            String anyOptions() {
                return "getAnyOptions";
            }
        }

        private static class ExplicitOptionsHandler {
            @HandleOptions("/sharedOptions")
            String sharedOptions() {
                return "explicitOptions";
            }
        }

        private static class ExplicitWildcardOptionsHandler {
            @HandleOptions("/wildcardOptions/*")
            String wildcardOptions() {
                return "explicitWildcardOptions";
            }
        }

        private static class ExplicitAnyHandler {
            @HandleWeb(value = "/anyHead", method = ANY)
            WebResponse anyHead() {
                return WebResponse.builder().status(211).header("X-Handler", "anyHead")
                        .payload("anyHead").build();
            }

            @HandleWeb(value = "/anyOptions", method = ANY)
            String anyOptions() {
                return "anyOptions";
            }
        }

        @Path("/classDocs")
        @ApiDocInfo(title = "Class Docs", version = "1.0.0", serveOpenApi = true, serveApiReference = true)
        @ApiDoc(tags = "class-docs")
        @RequiresUser
        private static class OpenApiEndpointHandler {
            @HandleGet("/meters")
            String meters() {
                return "meters";
            }
        }

        @Path("/absoluteDocs")
        @ApiDocInfo(title = "Absolute Docs", version = "1.0.0", serveOpenApi = true,
                openApiPath = "/docs/openapi.json")
        @ApiDoc(tags = "absolute-docs")
        private static class AbsoluteOpenApiEndpointHandler {
            @HandleGet("/items")
            String items() {
                return "items";
            }
        }

        @Path("/scalarDocs")
        @ApiDocInfo(title = "Scalar Docs", version = "1.0.0", serveApiReference = true,
                apiReferenceRenderer = ApiReferenceRenderer.SCALAR, apiReferencePath = "reference")
        @ApiDoc(tags = "scalar-docs")
        private static class ScalarReferenceEndpointHandler {
            @HandleGet("/items")
            String items() {
                return "items";
            }
        }

        @Path("/swaggerDocs")
        @ApiDocInfo(title = "Swagger UI Docs", version = "1.0.0", serveApiReference = true,
                apiReferenceRenderer = ApiReferenceRenderer.SWAGGER_UI, apiReferencePath = "/swagger-ui")
        @ApiDoc(tags = "swagger-docs")
        private static class SwaggerUiReferenceEndpointHandler {
            @HandleGet("/items")
            String items() {
                return "items";
            }
        }

        @Path("/customDocs")
        @ApiDocInfo(title = "Custom Docs", version = "1.0.0", serveApiReference = true,
                apiReferenceRenderer = ApiReferenceRenderer.SWAGGER_UI,
                apiReferenceScriptUrl = "/assets/swagger-ui.js",
                apiReferenceStylesheetUrl = "/assets/swagger-ui.css")
        @ApiDoc(tags = "custom-docs")
        private static class CustomReferenceAssetEndpointHandler {
            @HandleGet("/items")
            String items() {
                return "items";
            }
        }

        private byte[] multipartBody(String boundary) {
            return ("""
                    --%s\r
                    Content-Disposition: form-data; name="description"\r
                    \r
                    meter readings\r
                    --%s\r
                    Content-Disposition: form-data; name="tag"\r
                    \r
                    alpha\r
                    --%s\r
                    Content-Disposition: form-data; name="tag"\r
                    \r
                    beta\r
                    --%s\r
                    Content-Disposition: form-data; name="upload"; filename="readings.csv"\r
                    Content-Type: text/csv\r
                    X-Part-Id: upload-1\r
                    \r
                    meterId,reading
                    123,42
                    \r
                    --%s--\r
                    """).formatted(boundary, boundary, boundary, boundary, boundary)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @Value
    static class JsonResponse {
        String userId;
    }

    @Value
    static class Payload {
    }

    @Value
    static class GenericPayload {
        String value;
    }

    @RequiresUser
    @Value
    static class PayloadRequiringUser {
    }

    @Nested
    class PathTests {

        final TestFixture testFixture = TestFixture.create(
                new ClassPathHandler(),
                new PackagePathHandler(),
                new SubPathHandler(),
                new ResetPathHandler(),
                new ExternalUrlHandler(),
                new ExternalUrlPathHandler(),
                new PrefixedExternalUrlHandler());

        @Test
        void classPathTest() {
            testFixture.whenGet("/class/get").expectResult("get");
        }

        @Test
        void packagePathTest() {
            testFixture.whenGet("/package/get").expectResult("get");
        }

        @Test
        void subPathTest() {
            testFixture.whenGet("/package/sub/class/get").expectResult("get");
        }

        @Test
        void resetPathTest() {
            testFixture.whenGet("/get-reset").expectResult("get-reset");
        }

        @Test
        void externalUrlTest() {
            testFixture.whenGet("https://example.com/get-external").expectResult("get-external");
        }

        @Test
        void externalUrlPathResetsHierarchyTest() {
            testFixture.whenGet("https://example.com/from-path/get-via-path").expectResult("get-via-path");
        }

        @Test
        void externalUrlHandlerValueOverridesPathPrefixTest() {
            testFixture.whenGet("https://example.com/get-prefixed-external").expectResult("get-prefixed-external");
        }

        @Test
        void methodPathTest() {
            testFixture.whenGet("/method/get").expectResult("get");
        }
    }

    @Nested
    class AnnotationOverrides {
        private final TestFixture testFixture = TestFixture.create(new Handler());

        @Test
        void testGet() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("/get").build()).expectResult("get");
        }

        @Test
        void testGetWithOrigin() {
            testFixture.whenWebRequest(WebRequest.builder().method(GET).url("http://localhost:8080/get").build()).expectResult("getWithOrigin");
        }

        @Test
        void testPutOrPost() {
            testFixture
                    .whenWebRequest(WebRequest.builder().method(POST).url("/string").payload("payload").build())
                    .expectResult("payload")
                    .andThen()
                    .whenPut("/string", "payload")
                    .expectResult("payload")
                    .andThen()
                    .whenPost("/string", "payload")
                    .expectResult("payload");
        }

        @Test
        void testCustomMethod() {
            testFixture
                    .whenWebRequest(WebRequest.builder().method("CUSTOM").url("/string3").payload("payload").build())
                    .expectResult("payload");
        }

        @Test
        void testAnyMethod() {
            testFixture
                    .whenPut("/string4", "payload")
                    .expectResult("payload")
                    .andThen()
                    .whenWebRequest(WebRequest.builder().method(TRACE).url("/string4").payload("payload").build())
                    .expectResult("payload");
        }

        @Test
        void testPutOrPost2() {
            testFixture
                    .whenPost("/string2", "payload")
                    .expectResult("payload")
                    .andThen()
                    .whenPut("/string2", "payload")
                    .expectResult("payload")
                    .andThen()
                    .whenPost("/string2", "payload")
                    .expectResult("payload");
        }

        private static class Handler {
            @HandleGet("get")
            String get() {
                return "get";
            }

            @HandleGet("http://localhost:8080/get")
            String getWithOrigin() {
                return "getWithOrigin";
            }

            @HandleWeb(value = "/string", method = {PUT, POST})
            String putOrPost(String body) {
                return body;
            }

            @HandlePost("/string2")
            @HandlePut("/string2")
            String putOrPost2(String body) {
                return body;
            }

            @HandleWeb(value = "/string3", method = "CUSTOM")
            String customMethod(String body) {
                return body;
            }

            @HandleWeb(value = "/string4")
            String anyMethod(String body) {
                return body;
            }
        }
    }

    @Nested
    class StaticFileTests {
        private final TestFixture testFixture = TestFixture.create(new ClasspathHandler(), new FileSystemHandler());

        @Test
        void normalGet() {
            testFixture.whenGet("/web/get").expectResult("dynamicGet");
        }

        @Test
        void serveHtmlFile() {
            testFixture.whenGet("/static/index.html")
                    .expectResult(testContents("<!DOCTYPE html>"));
        }

        @Test
        void serveLogo() {
            testFixture.whenGet("/static/assets/logo.svg")
                    .expectResult(testContents("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        }

        @Test
        void serveLogo_relative() {
            TestFixture.create(new RelativeClasspathHandler()).whenGet("/web/static/assets/logo.svg")
                    .expectResult(testContents("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        }

        @Test
        void serveLogo_fs() {
            testFixture.whenGet("/file/assets/logo.svg")
                    .expectResult(testContents("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        }

        @Test
        void serveFallback() {
            testFixture.whenGet("/static/whatever/bla")
                    .expectResult(testContents("<!DOCTYPE html>"));
        }

        @Test
        void dontServeFallbackIfIgnorePath() {
            testFixture.whenGet("/static/api/whatever")
                    .expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void serveRoot() {
            testFixture.whenGet("/static")
                    .expectWebResult(testContents("<!DOCTYPE html>"))
                    .andThen()
                    .whenGet("/static/")
                    .expectWebResult(testContents("<!DOCTYPE html>"))
                    .andThen()
                    .whenGet("/file")
                    .expectWebResult(testContents("<!DOCTYPE html>"))
                    .andThen()
                    .whenGet("/file/")
                    .expectWebResult(testContents("<!DOCTYPE html>"));
        }

        @Test
        void serveWithOrigin() {
            testFixture
                    .whenGet("http://localhost:8080/static")
                    .expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void serveUsingBasicStaticHandler() {
            TestFixture.create(new BasicStaticHandler()).whenGet("/abc.txt")
                    .expectWebResult(testContents("123"))
                    .andThen()
                    .whenGet("")
                    .expectWebResult(testContents("<!DOCTYPE html>"));
        }

        @Path
        @ServeStatic(value = "/static", ignorePaths = "/static/api/*", resourcePath = "classpath:/web/static")
        static class ClasspathHandler {
            @HandleGet("/get")
            String get() {
                return "dynamicGet";
            }
        }

        @Path
        @ServeStatic(value = "static", resourcePath = "classpath:/web/static")
        static class RelativeClasspathHandler {
        }

        @ServeStatic
        static class BasicStaticHandler {
        }

        static class FileSystemHandler extends StaticFileHandler {
            public FileSystemHandler() {
                super("/file", Paths.get(FileUtils.getFile(
                        FileSystemHandler.class, "/web/static/index.html")).toAbsolutePath().toString()
                        .replace("/index.html", ""), "index.html");
            }
        }

        static ThrowingPredicate<WebResponse> testContents(String string) {
            return r -> {
                try (InputStream input = r.getPayload()) {
                    var contents = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    return contents.contains(string);
                }
            };
        }

    }

    @Nested
    class StaticAndWebSocketTests {
        private final TestFixture testFixture = TestFixture.create(new CombinedHandler());

        @Test
        void serveStaticAndHandleSocketOpenFromSameClass() {
            testFixture.whenGet("/combined")
                    .expectWebResult(r -> {
                        try (InputStream input = r.getPayload()) {
                            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                                    .contains("<!DOCTYPE html>");
                        }
                    })
                    .andThen()
                    .whenWebRequest(WebRequest.builder()
                                            .method(WS_OPEN)
                                            .url("/combined/socket")
                                            .build())
                    .expectResult(WebResponse.builder().payload("socket-open").build());
        }

        @Path("/combined")
        @ServeStatic(resourcePath = "classpath:/web/static")
        static class CombinedHandler {
            @HandleSocketOpen("/socket")
            String onOpen() {
                return "socket-open";
            }
        }
    }

    @Nested
    class WebSocketTests {

        @Nested
        class Sync {
            private final TestFixture testFixture = TestFixture.create(new Handler());

            @Test
            void testOpen() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_OPEN).url("/auto").build())
                        .expectResult(WebResponse.builder().payload("open").build());
            }

            @Test
            void testResponse() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_MESSAGE).url("/response").build())
                        .expectResult(WebResponse.builder().payload("response").build());
            }

            @Test
            void testNoResponse() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_MESSAGE).url("/noResponse").build())
                        .expectNoResult();
            }

            @Test
            void testViaSession() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_MESSAGE).url("/viaSession").build())
                        .expectWebResponses("viaSession");
            }
        }

        @Nested
        class Async {
            private final TestFixture testFixture = TestFixture.createAsync(new Handler())
                    .resultTimeout(Duration.ofSeconds(1)).consumerTimeout(Duration.ofSeconds(1)).spy();

            @Test
            void testAutoHandshake() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/auto").build())
                        .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.WEBRESPONSE)).append(
                                any(), any(SerializedMessage.class)))
                        .expectNoErrors();
            }

            @Test
            void testOpen() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_OPEN).url("/auto").build())
                        .expectWebResponses(WebResponse.builder().payload("open").build());
            }

            @Test
            void testResponse() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_MESSAGE).url("/response").build())
                        .expectWebResponses(WebResponse.builder().payload("response").build());
            }

            @Test
            void testNoResponse() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_MESSAGE).url("/noResponse").build())
                        .expectNoWebResponses();
            }

            @Test
            void testViaSession() {
                testFixture.whenWebRequest(WebRequest.builder().method(WS_MESSAGE).url("/viaSession").build())
                        .expectWebResponses("viaSession");
            }
        }

        @Nested
        class SocketEndpointTests {
            static final String endpointUrl = "/endpoint";
            private final TestFixture testFixture = TestFixture.create();

            @Nested
            class ConstructorTests {

                @BeforeEach
                void setUp() {
                    testFixture.registerHandlers(Endpoint.class);
                }

                @Test
                void testOpen() {
                    testFixture.whenWebRequest(toWebRequest(WS_OPEN))
                            .expectWebResponses("open: testSession");
                }

                @Test
                void testMessageWithoutOpenNotPossible() {
                    testFixture.whenWebRequest(toWebRequest(WS_MESSAGE))
                            .expectError();
                }

                @Test
                void testMessageWithOpen() {
                    testFixture
                            .givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_MESSAGE))
                            .expectWebResponses("response: testSession");
                }

                @Test
                void testMessageOtherUrl() {
                    testFixture
                            .givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_MESSAGE).toBuilder()
                                                    .metadata(Metadata.of("sessionId", "otherSession"))
                                                    .url("/other").build())
                            .expectExceptionalResult();
                }

                @SocketEndpoint
                @Path(endpointUrl)
                static class Endpoint {
                    private final SocketSession session;

                    @HandleSocketOpen
                    Endpoint(SocketSession session) {
                        this.session = session;
                        session.sendMessage("open: " + session.sessionId());
                    }

                    @HandleSocketMessage
                    String response() {
                        return "response: " + session.sessionId();
                    }
                }
            }

            @Nested
            @Path(endpointUrl)
            class DefaultConstructorTests {

                @BeforeEach
                void setUp() {
                    testFixture.registerHandlers(Endpoint.class);
                }

                @Test
                void testOpen() {
                    testFixture.whenWebRequest(toWebRequest(WS_OPEN))
                            .expectWebResponses("open: testSession");
                }

                @Test
                void testMessage() {
                    testFixture.whenWebRequest(toWebRequest(WS_MESSAGE))
                            .expectWebResponses("response: testSession");
                }

                @Test
                void testEventAfterOpen() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenEvent(123)
                            .expectEvents("123");
                }

                @Test
                void testEventBeforeOpen() {
                    testFixture
                            .whenEvent(123)
                            .expectEvents("not open")
                            .expectNoErrors();
                }

                @SocketEndpoint
                static class Endpoint {
                    @HandleEvent
                    static void handleBefore(Integer event) {
                        Fluxzero.publishEvent("not open");
                    }

                    @HandleSocketOpen
                    Object onOpen(SocketSession session) {
                        return "open: " + session.sessionId();
                    }

                    @HandleSocketMessage
                    String onMessage(SocketSession session) {
                        return "response: " + session.sessionId();
                    }

                    @HandleEvent
                    void handle(Integer event) {
                        Fluxzero.publishEvent(event.toString());
                    }
                }
            }

            @Nested
            class AssociationTests {

                @BeforeEach
                void setUp() {
                    testFixture.registerHandlers(Endpoint.class);
                }

                @Test
                void testEventOnlyReachesAssociatedSession() {
                    testFixture
                            .givenWebRequest(toWebRequest(WS_OPEN, "session-a", new OpenOrderEndpoint("order-1")))
                            .givenWebRequest(toWebRequest(WS_OPEN, "session-b", new OpenOrderEndpoint("order-2")))
                            .whenEvent(new OrderFinished("order-2"))
                            .expectEvents("matched: order-2")
                            .expectNoWebResponses();
                }

                @Test
                void testEventWithoutAssociationStillReachesAllSessions() {
                    testFixture
                            .givenWebRequest(toWebRequest(WS_OPEN, "session-a", new OpenOrderEndpoint("order-1")))
                            .givenWebRequest(toWebRequest(WS_OPEN, "session-b", new OpenOrderEndpoint("order-2")))
                            .whenEvent(123)
                            .expectEvents("all: order-1", "all: order-2")
                            .expectNoWebResponses();
                }

                @Value
                static class OpenOrderEndpoint {
                    String orderId;
                }

                @Value
                static class OrderFinished {
                    String orderId;
                }

                @SocketEndpoint
                @Path(endpointUrl)
                @Value
                static class Endpoint {
                    @Association
                    String orderId;

                    @HandleSocketOpen
                    static Endpoint onOpen(SocketSession session, OpenOrderEndpoint event) {
                        session.sendMessage("open: " + session.sessionId());
                        return new Endpoint(event.getOrderId());
                    }

                    @HandleEvent
                    void onOrderFinished(@Association("orderId") OrderFinished event) {
                        Fluxzero.publishEvent("matched: " + orderId);
                    }

                    @HandleEvent
                    void onAnyInteger(Integer event) {
                        Fluxzero.publishEvent("all: " + orderId);
                    }
                }
            }

            @Nested
            class PingTests {

                static final int pingDelay = 30, pingTimeout = 10;

                @BeforeEach
                void setUp() {
                    testFixture.registerHandlers(Endpoint.class);
                }

                @Test
                void testSendPing() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenTimeElapses(Duration.ofSeconds(pingDelay))
                            .expectWebResponse(r -> "ping".equals(r.getMetadata().get("function")));
                }

                @Test
                void testRescheduleAfterPong() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .givenElapsedTime(Duration.ofSeconds(pingDelay))
                            .givenWebRequest(toWebRequest(WS_PONG))
                            .whenTimeElapses(Duration.ofSeconds(pingDelay))
                            .expectWebResponse(r -> "ping".equals(r.getMetadata().get("function")))
                            .expectNoEvents();
                }

                @Test
                void closeAfterPingTimeout() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenTimeElapses(Duration.ofSeconds(pingDelay))
                            .andThen()
                            .whenTimeElapses(Duration.ofSeconds(pingTimeout))
                            .expectWebResponse(r -> "close".equals(r.getMetadata().get("function")))
                            .expectEvents("close: testSession");
                }

                @Test
                void testMessage() {
                    testFixture.whenWebRequest(toWebRequest(WS_MESSAGE))
                            .expectWebResponses("response: testSession");
                }

                @SocketEndpoint(aliveCheck = @AliveCheck(pingDelay = pingDelay, pingTimeout = pingTimeout))
                @Path(endpointUrl)
                static class Endpoint {

                    @HandleSocketOpen
                    Object onOpen(SocketSession session) {
                        return "open: " + session.sessionId();
                    }

                    @HandleSocketMessage
                    String onMessage(SocketSession session) {
                        return "response: " + session.sessionId();
                    }

                    @HandleSocketClose
                    void onClose(SocketSession session) {
                        Fluxzero.publishEvent("close: " + session.sessionId());
                    }
                }
            }

            @Nested
            class RequestTests {

                @BeforeEach
                void setUp() {
                    testFixture.registerHandlers(Endpoint.class);
                }

                @Test
                void testSendingRequest() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_MESSAGE, SocketResponse.success(SocketRequest.counter.get(), new MyResponse("out"))))
                            .expectEvents("out");
                }

                @Test
                void testRequestTimeout() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenTimeElapses(Endpoint.requestTimeout)
                            .expectEvents("timeout");
                }

                @Test
                void testReceivingFailedResponse() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_MESSAGE, SocketResponse.error(SocketRequest.counter.get(), "failed")))
                            .expectEvents("failed");
                }

                @Test
                void testDeserializeRequest() {
                    testFixture.whenApplying(fc -> JsonUtils.convertValue(
                            SocketRequest.valueOf("hello"), SocketRequest.class).getRequest())
                            .expectResult(TextNode.valueOf("hello"));
                }

                @Test
                void testReceivingRequest() {
                    SocketRequest request = SocketRequest.valueOf("hello");
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_MESSAGE, request))
                            .mapResult(r -> ((WebResponse) r).getPayload())
                            .expectResult(SocketResponse.success(request.getRequestId(), "hello world"));
                }

                @Test
                void closeOpenRequestsOnClose() {
                    testFixture.givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_CLOSE))
                            .expectEvents("Websocket session testSession has closed");
                }

                @SocketEndpoint
                @Path(endpointUrl)
                static class Endpoint {

                    static final Duration requestTimeout = Duration.ofSeconds(10);

                    @HandleSocketOpen
                    void onOpen(SocketSession session) {
                        session.sendRequest(new MyRequest("in"), requestTimeout)
                                .whenComplete((r, e) -> {
                            if (e == null) {
                                Fluxzero.publishEvent(r.getOutput());
                            } else {
                                switch (e) {
                                    case TimeoutException te -> Fluxzero.publishEvent("timeout");
                                    default -> Fluxzero.publishEvent(e.getMessage());
                                }
                            }
                        });
                    }

                    @HandleSocketMessage
                    String onMessage(String question) {
                        return question + " world";
                    }
                }

                @Value
                static class MyRequest implements Request<MyResponse> {
                    String input;
                }

                @Value
                static class MyResponse {
                    String output;
                }
            }

            @Nested
            class FactoryMethodTests {

                @BeforeEach
                void setUp() {
                    testFixture.registerHandlers(Endpoint.class);
                }

                @Test
                void testOpen() {
                    testFixture.whenWebRequest(toWebRequest(WS_OPEN))
                            .expectWebResponses("open: testSession");
                }

                @Test
                void testCloseOnThrow() {
                    testFixture.whenWebRequest(toWebRequest(WS_OPEN).toBuilder().url(endpointUrl + "/throw").build())
                            .expectWebResponse(r -> r.getMetadata().get("function").equals("close"));
                }

                @Test
                void testOpenRequiresUser_withUser() {
                    TestFixture.create(DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(new MockUser())), Endpoint.class)
                            .whenWebRequest(toWebRequest(WS_OPEN).toBuilder().url(endpointUrl + "/user").build())
                            .expectSuccessfulResult()
                            .expectWebResponses("open with user: testSession");
                }

                @Test
                void testOpenRequiresUser_withoutUser() {
                    testFixture.withProductionUserProvider()
                            .whenWebRequest(toWebRequest(WS_OPEN).toBuilder().url(endpointUrl + "/user").build())
                            .expectExceptionalResult(UnauthenticatedException.class)
                            .expectWebResponse(r -> "close".equals(r.getMetadata().get("function")));
                }

                @Test
                void testMessage() {
                    testFixture
                            .givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_MESSAGE))
                            .expectWebResponses("foo: testSession");
                }

                @Test
                void testClose() {
                    testFixture
                            .givenWebRequest(toWebRequest(WS_OPEN))
                            .whenWebRequest(toWebRequest(WS_CLOSE))
                            .expectNoResult()
                            .expectNoWebResponses()
                            .expectEvents("close: testSession")
                            .andThen()
                            .whenWebRequest(toWebRequest(WS_MESSAGE))
                            .expectError();
                }

                @SocketEndpoint
                @Path(endpointUrl)
                @AllArgsConstructor(access = AccessLevel.PRIVATE)
                static class Endpoint {

                    private final String name;

                    @HandleSocketOpen("throw")
                    static Endpoint throwOnOpen(SocketSession session) {
                        throw new MockException("error");
                    }

                    @HandleSocketOpen("user")
                    @RequiresUser
                    static Endpoint onOpenWithUser(SocketSession session) {
                        session.sendMessage("open with user: " + session.sessionId());
                        return new Endpoint("withUser");
                    }

                    @HandleSocketOpen
                    static Endpoint onOpen(SocketSession session) {
                        session.sendMessage("open: " + session.sessionId());
                        return new Endpoint("foo");
                    }

                    @HandleSocketMessage
                    String onMessage(SocketSession session) {
                        return name + ": " + session.sessionId();
                    }

                    @HandleSocketClose
                    void onClose(SocketSession session) {
                        Fluxzero.publishEvent("close: " + session.sessionId());
                    }
                }
            }

            private WebRequest toWebRequest(String method) {
                return toWebRequest(method, "hello");
            }

            private WebRequest toWebRequest(String method, Object payload) {
                return toWebRequest(method, "testSession", payload);
            }

            private WebRequest toWebRequest(String method, String sessionId, Object payload) {
                return WebRequest.builder().method(method).url(endpointUrl)
                        .metadata(Metadata.of("sessionId", sessionId))
                        .payload(payload).build();
            }
        }

        @Nested
        class AutoHandshakeAuthorizationTests {

            @Test
            void testAutoHandshakeRequiresUserFromOpenHandler() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/secured/open").build())
                        .expectExceptionalResult(UnauthenticatedException.class);
            }

            @Test
            void testAutoHandshakeRequiresUserFromMessageHandlerWhenOpenIsMissing() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/secured/message").build())
                        .expectExceptionalResult(UnauthenticatedException.class);
            }

            @Test
            void testAutoHandshakeSilentlySkipsWhenRequirementIsSilent() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/secured/silent").build())
                        .expectExceptionalResult(TimeoutException.class);
            }

            @Test
            void testAutoHandshakeRequiresUserFromPongHandlerWhenOpenAndMessageAreMissing() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/secured/pong").build())
                        .expectExceptionalResult(UnauthenticatedException.class);
            }

            @Test
            void testAutoHandshakeRequiresUserFromCloseHandlerWhenOtherLifecycleHandlersAreMissing() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/secured/close").build())
                        .expectExceptionalResult(UnauthenticatedException.class);
            }

            @Test
            void testAutoHandshakeUsesOpenBeforeMoreRestrictiveLifecycleHandlers() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE)
                                                .url("/secured/openPriority").build())
                        .expectSuccessfulResult();
            }

            @Test
            void testAutoHandshakeUsesMessageBeforeMoreRestrictiveLifecycleHandlersWhenOpenIsMissing() {
                TestFixture.create(new SecuredHandshakeHandler())
                        .withProductionUserProvider()
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE)
                                                .url("/secured/messagePriority").build())
                        .expectSuccessfulResult();
            }

            @Test
            void testAutoHandshakeAllowedWithUser() {
                TestFixture.create(DefaultFluxzero.builder().registerUserProvider(
                                new FixedUserProvider(new MockUser())), new SecuredHandshakeHandler())
                        .whenWebRequest(WebRequest.builder().method(WS_HANDSHAKE).url("/secured/open").build())
                        .expectSuccessfulResult();
            }

            static class SecuredHandshakeHandler {

                @HandleSocketOpen("/secured/open")
                @RequiresUser
                void open() {
                }

                @HandleSocketMessage("/secured/message")
                @RequiresUser
                void message() {
                }

                @HandleSocketOpen("/secured/silent")
                @RequiresUser(throwIfUnauthorized = false)
                void silent() {
                }

                @HandleSocketPong("/secured/pong")
                @RequiresUser
                void pong() {
                }

                @HandleSocketClose("/secured/close")
                @RequiresUser
                void close() {
                }

                @HandleSocketOpen("/secured/openPriority")
                void openPriority() {
                }

                @HandleSocketMessage("/secured/openPriority")
                @RequiresUser
                void messageAfterOpen() {
                }

                @HandleSocketPong("/secured/openPriority")
                @RequiresUser
                void pongAfterOpen() {
                }

                @HandleSocketClose("/secured/openPriority")
                @RequiresUser
                void closeAfterOpen() {
                }

                @HandleSocketMessage("/secured/messagePriority")
                void messagePriority() {
                }

                @HandleSocketPong("/secured/messagePriority")
                @RequiresUser
                void pongAfterMessage() {
                }

                @HandleSocketClose("/secured/messagePriority")
                @RequiresUser
                void closeAfterMessage() {
                }
            }
        }


        static class Handler {
            @HandleSocketHandshake("manual")
            String handshake() {
                return "handshake";
            }

            @HandleSocketOpen("auto")
            String open() {
                return "open";
            }

            @HandleSocketMessage("response")
            String response() {
                return "response";
            }

            @HandleSocketMessage("noResponse")
            void noResponse() {
            }

            @HandleSocketMessage("viaSession")
            @SneakyThrows
            void viaSession(SocketSession session) {
                session.sendMessage("viaSession");
            }
        }
    }

    @Nested
    class HeaderTests {

        @Test
        void testWithHeader() {
            TestFixture.create(new Object() {
                @HandleGet("/checkHeader")
                String check(WebRequest request) {
                    return Optional.ofNullable(request.getHeader("foo")).orElseThrow();
                }
            }).withHeader("foo", "bar").whenGet("/checkHeader").expectResult("bar");
        }

        @Test
        void testValidCookieHeader() {
            TestFixture.create(new Object() {
                        @HandleGet("/checkHeader")
                        String check(WebRequest request) {
                            return request.getCookie("foo").map(HttpCookie::getValue).orElse(null);
                        }
                    }).withHeader("cookie", "foo=bar=bar").whenGet("/checkHeader")
                    .expectResult("bar=bar");
        }

        @Test
        void testInvalidCookieHeader() {
            TestFixture.create(new Object() {
                        @HandleGet("/checkHeader")
                        String check(WebRequest request) {
                            return request.getCookie("").map(HttpCookie::getValue).orElse(null);
                        }
                    }).withHeader("cookie", "bar").whenGet("/checkHeader")
                    .expectNoResult().expectNoErrors();
        }

        @Test
        void testWithoutHeader() {
            TestFixture.create(new Object() {
                        @HandleGet("/checkHeader")
                        String check(WebRequest request) {
                            return Optional.ofNullable(request.getHeader("foo")).orElseThrow();
                        }
                    }).withHeader("foo", "bar")
                    .withoutHeader("foo").whenGet("/checkHeader").expectExceptionalResult();
        }

        @Test
        void testWithCookie() {
            TestFixture.create(new Object() {
                @HandleGet("/checkCookie")
                String check(WebRequest request) {
                    return request.getCookie("foo").orElseThrow().getValue();
                }
            }).withCookie("foo", "bar").whenGet("/checkCookie").expectResult("bar");
        }

        @Test
        void returnedCookieIsUsed() {
            TestFixture.create(new Object() {
                @HandlePost("/signIn")
                WebResponse signIn(String userName) {
                    return WebResponse.builder().cookie(new HttpCookie("user", userName)).build();
                }

                @HandleGet("/getUser")
                String getUser(WebRequest request) {
                    return request.getCookie("user").orElseThrow().getValue();
                }
            }).givenPost("signIn", "testUser").whenGet("getUser").expectResult("testUser");
        }
    }

    @Value
    @Searchable(timestampPath = "timestamp")
    static class StreamingSearchDocument {
        String id;
        Instant timestamp;
    }

    static class StreamingSearchHandler {
        @HandleGet("/searchStream")
        WebResponse searchStream() {
            return WebResponse.ok(
                    Fluxzero.search(StreamingSearchDocument.class)
                            .sortByTimestamp()
                            .toNdjsonInputStream(),
                    Map.of("Content-Type", "text/plain"));
        }
    }
}
