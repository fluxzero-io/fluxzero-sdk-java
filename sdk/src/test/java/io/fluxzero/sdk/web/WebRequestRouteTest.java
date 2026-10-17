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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebRequestRouteTest {
    @Test
    void projectionKeepsRawAddressingAndIgnoresQueryAndFragment() {
        for (String path : List.of("/a%2Fb", "/a%252Fb", "/a+b", "/%E2%82%AC", "/東京", "/a/")) {
            assertEquals("GET " + path, WebRequestRoute.describe(Metadata.of("method", "GET", "url", path + "?x=1#f")));
        }
        assertEquals("GET https://[::1]:8443/", WebRequestRoute.describe(
                Metadata.of("method", "GET", "url", "https://user:pass@[::1]:8443?x=y#f")));
        assertEquals("GET /", WebRequestRoute.describe(Metadata.of("method", "GET", "url", "?x=y")));
        assertNull(WebRequestRoute.describe(Metadata.of("method", "GET", "url", "/" + (char) 0xd800)));
        assertNull(WebRequestRoute.describe(Metadata.of("method", "GET", "url", "/" + (char) 0xdc00)));
        assertEquals("GET /relative", WebRequestRoute.describe(Metadata.of("method", "GET", "url", "relative")));
    }

    @Test
    void absentOrMalformedAddressingClearsStaleRoute() {
        var request = WebRequest.builder().method("GET").url("/before").build().serialize(new JacksonSerializer());
        assertEquals("GET /before", request.getRoute());
        for (Metadata metadata : List.of(Metadata.empty(), Metadata.of("method", "GET", "url", "/bad%xx"),
                                          Metadata.of("method", "bad method", "url", "/"))) {
            request.setMetadata(metadata);
            WebRequestRoute.update(request);
            assertNull(request.getRoute());
        }
    }

    @Test
    void exportedRegexKeepsItsOwnAnchorsLookaroundsAndBackreferences() {
        for (String regex : List.of("^/a$", "\\A/(?<=\\A/)a\\z", "/(?<!GET /)a", "/(a)\\1")) {
            var predicate = ConsumerFilter.select(null, 1, ConsumerFilter.VERSION, null, null,
                    List.of(WebRequestRoute.pattern("GET", null, regex)), List.of()).compile();
            String path = regex.contains("1") ? "/aa" : "/a";
            var message = WebRequest.builder().method("GET").url(path).build().serialize(new JacksonSerializer());
            assertTrue(predicate.test(message), regex);
            message.setRoute("POST " + path);
            assertFalse(predicate.test(message), regex);
        }
    }
}
