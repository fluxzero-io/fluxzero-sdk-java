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

import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRouteMatcherTest {

    @Test
    void wildcardSegmentInMiddleMatchesOneSegmentOnly() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("/a/*/b", GET), "middleWildcard");

        assertEquals("middleWildcard", matcher.match(GET, null, "/a/value/b").orElseThrow().value());
        assertTrue(matcher.match(GET, null, "/a/value/extra/b").isEmpty());
    }

    @Test
    void nonRootTrailingSlashIsIgnored() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("/users", GET), "users");
        matcher.add(new WebPattern("/projects/", GET), "projects");

        assertEquals("users", matcher.match(GET, null, "/users/").orElseThrow().value());
        assertEquals("projects", matcher.match(GET, null, "/projects").orElseThrow().value());
        assertEquals("projects", matcher.match(GET, null, "/projects/").orElseThrow().value());
    }

    @Test
    void literalBeatsPathParameterAndPathParameterBeatsWildcard() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("/a/*/c", GET), "wildcard");
        matcher.add(new WebPattern("/a/{value}/c", GET), "parameter");
        matcher.add(new WebPattern("/a/b/c", GET), "literal");

        assertEquals("literal", matcher.match(GET, null, "/a/b/c").orElseThrow().value());
        assertEquals("parameter", matcher.match(GET, null, "/a/other/c").orElseThrow().value());
    }

    @Test
    void constrainedPathParameterBeatsPlainPathParameter() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("/orders/{id}", GET), "plain");
        matcher.add(new WebPattern("/orders/{id:[0-9]{2,4}}", GET), "constrained");

        assertEquals("constrained", matcher.match(GET, null, "/orders/123").orElseThrow().value());
        assertEquals("plain", matcher.match(GET, null, "/orders/abc").orElseThrow().value());
    }

    @Test
    void catchAllMatchesNestedPathButLosesToLiteralRoute() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("/assets/*", GET), "catchAll");
        matcher.add(new WebPattern("/assets/app.js", GET), "literal");

        assertEquals("literal", matcher.match(GET, null, "/assets/app.js").orElseThrow().value());
        assertEquals("catchAll", matcher.match(GET, null, "/assets/css/app.css").orElseThrow().value());
    }

    @Test
    void legacyStaticFilePatternExtractsNestedPathParameter() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("/static/{filePath:.+}*", GET), "staticFile");

        var match = matcher.match(GET, null, "/static/assets/logo.svg").orElseThrow();

        assertEquals("staticFile", match.value());
        assertEquals("assets/logo.svg", match.pathParameters().get("filePath"));
    }

    @Test
    void originAndMethodMustMatch() {
        WebRouteMatcher<String> matcher = new WebRouteMatcher<>();
        matcher.add(new WebPattern("https://example.com/a", GET), "originGet");

        assertEquals("originGet", matcher.match(GET, "https://example.com", "/a").orElseThrow().value());
        assertTrue(matcher.match(GET, null, "/a").isEmpty());
        assertTrue(matcher.match(POST, "https://example.com", "/a").isEmpty());
    }
}
