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

import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathParameterDecodingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodesOnceAfterMatching(boolean async) {
        TestFixture fixture = async ? TestFixture.createAsync(new Endpoint()) : TestFixture.create(new Endpoint());
        try {
            Map.ofEntries(
                    Map.entry("order:1", "order:1"), Map.entry("order%3A1", "order:1"),
                    Map.entry("order%3a1", "order:1"), Map.entry("a+b", "a+b"),
                    Map.entry("a%2Bb", "a+b"), Map.entry("a+b%20c", "a+b c"),
                    Map.entry("caf%C3%A9", "café"), Map.entry("%F0%9F%8E%AB", "🎫"),
                    Map.entry("a%2Fb", "a/b"), Map.entry("a%252Fb", "a%2Fb"),
                    Map.entry("a%25", "a%"), Map.entry("%23%3F%26", "#?&"))
                    .forEach((encoded, decoded) -> fixture.whenGet("/items/" + encoded + "?q=a+b%2Bc")
                            .expectWebResult(r -> decoded.equals(r.getPayloadAs(String.class))).expectNoErrors());
            fixture.whenGet("/items/a/b").expectWebResult(r -> "literal".equals(r.getPayloadAs(String.class)))
                    .expectNoErrors();
            fixture.whenGet("/regex/%31").expectWebResult(r -> "encoded:1".equals(r.getPayloadAs(String.class)))
                    .expectNoErrors();
            fixture.whenGet("/number/%34%32").expectWebResult(r -> Integer.valueOf(42).equals(r.getPayloadAs(Integer.class)))
                    .expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    @NoUserRequired
    static class Endpoint {
        @HandlePost("/sources/{id}")
        String sources(@PathParam("id") String id, @QueryParam("q") String query,
                       @HeaderParam("X-Value") String header, @CookieParam("value") String cookie,
                       @FormParam("form") String form) {
            assertEquals("a+b/c%2F", id);
            assertEquals("a b+c%2F", query);
            assertEquals("a+b%2Fc", header);
            assertEquals("a+b%2Fc", cookie);
            return form;
        }

        @HandlePost("/body/{id}")
        String body(@PathParam("id") String id, @QueryParam("q") String query,
                    @HeaderParam("X-Value") String header, @CookieParam("value") String cookie,
                    @BodyParam("body") String body) {
            return sources(id, query, header, cookie, body);
        }

        @HandleGet("/items/{id}")
        String get(@PathParam("id") String id, @QueryParam("q") String q, WebRequest request) {
            var context = DefaultWebRequestContext.getCurrentWebRequestContext();
            assertEquals(id, context.getPathParameter("id").as(String.class));
            assertEquals(id, context.getPathParameter("id").as(String.class));
            assertEquals(id, context.pathMap().get("id"));
            assertEquals("a b+c", q);
            assertEquals(WebRequest.getUrl(request.getMetadata()), context.getUri().toString());
            return id;
        }

        @HandleGet("/items/a/b")
        String literal() { return "literal"; }

        @HandleGet("/regex/{id:%[0-9]+}")
        String encoded(@PathParam("id") String id) { return "encoded:" + id; }

        @HandleGet("/regex/{id:[0-9]+}")
        String numeric(@PathParam("id") String id) { return "numeric:" + id; }

        @HandleGet("/number/{id}")
        int number(@PathParam("id") int id) { return id; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void otherParameterSourcesRetainTheirOwnEncodingRules(boolean async) {
        TestFixture fixture = async ? TestFixture.createAsync(new Endpoint()) : TestFixture.create(new Endpoint());
        try {
            java.util.function.Supplier<WebRequest.Builder> request = () -> WebRequest.post("/sources/a+b%2Fc%252F?q=a+b%2Bc%252F")
                    .header("X-Value", "a+b%2Fc").header("Cookie", "value=a+b%2Fc");
            fixture.whenWebRequest(request.get().contentType("application/x-www-form-urlencoded")
                            .payload("form=a+b%2Bc%252F").build())
                    .expectWebResult(r -> "a b+c%2F".equals(r.getPayloadAs(String.class))).expectNoErrors();
            fixture.whenWebRequest(request.get().url("/body/a+b%2Fc%252F?q=a+b%2Bc%252F").contentType("application/json")
                            .payload(Map.of("body", "a+b%2Fc")).build())
                    .expectWebResult(r -> "a+b%2Fc".equals(r.getPayloadAs(String.class))).expectNoErrors();
            String boundary = "path-param-boundary";
            fixture.whenWebRequest(request.get()
                            .contentType("multipart/form-data; boundary=" + boundary)
                            .payload(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"form\"\r\n\r\n"
                                      + "a+b%2Fc\r\n--" + boundary + "--\r\n")
                                             .getBytes(java.nio.charset.StandardCharsets.UTF_8)).build())
                    .expectWebResult(r -> "a+b%2Fc".equals(r.getPayloadAs(String.class))).expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }
}
