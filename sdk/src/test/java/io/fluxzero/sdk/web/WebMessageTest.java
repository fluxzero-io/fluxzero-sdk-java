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
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.net.HttpCookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebMessageTest {

    @Test
    void testConvertComplexResponseViaBuilder() {
        HttpCookie cookie = new HttpCookie("foo-cookie", "bar-cookie");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        WebResponse input = WebResponse.builder().header("foo", "bar").header("foo", "bar2")
                .cookie(cookie).status(200).payload("test").build();
        WebResponse converted = input.toBuilder().build();
        assertEquals((Object) input.getPayload(), converted.getPayload());
        assertEquals(input.getMetadata(), converted.getMetadata());
        assertFalse(input.getHeaders("Set-Cookie").isEmpty());
    }

    @Test
    void testConvertComplexRequestViaBuilder() {
        HttpCookie cookie = new HttpCookie("foo-cookie", "bar-cookie");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        WebRequest input = WebRequest.builder().method(HttpRequestMethod.POST)
                .header("foo", "bar").header("foo", "bar2")
                .cookie(cookie).url("/test").payload("test").build();
        WebRequest converted = input.toBuilder().build();
        assertEquals((Object) input.getPayload(), converted.getPayload());
        assertEquals(input.getMetadata(), converted.getMetadata());
        assertFalse(input.getHeaders("Cookie").isEmpty());
    }

    @Test
    void existingCookieContractIsCaseSensitiveAndFirstMatch() {
        WebRequest request = WebRequest.get("/test")
                .header("cOoKiE", "Session=upper; session=first; session=second")
                .build();

        assertEquals(List.of("Session=upper", "session=first", "session=second"),
                     request.getCookies().stream().map(c -> c.getName() + "=" + c.getValue()).toList());
        assertEquals("upper", request.getCookie("Session").orElseThrow().getValue());
        assertEquals("first", request.getCookie("session").orElseThrow().getValue());
        assertEquals(List.of("Session=upper; session=first; session=second"), request.getHeaders("COOKIE"));
    }

    @Test
    void repeatedCookieHeaderValuesSurviveMetadataSerialization() {
        WebRequest input = WebRequest.get("/test")
                .header("Cookie", "first=one")
                .header("Cookie", "second=two")
                .payload(Map.of("body", "value"))
                .build();

        assertEquals(List.of("first=one", "second=two"), input.getHeaders("cookie"));
        assertEquals(input.getHeaders("Cookie"), WebRequest.getHeaders(input.getMetadata()).get("COOKIE"));

        JacksonSerializer serializer = new JacksonSerializer();
        WebRequest copy = (WebRequest) serializer.deserializeMessage(input.serialize(serializer), WEBREQUEST)
                .toMessage();
        assertEquals(input.getHeaders("Cookie"), copy.getHeaders("cookie"));
    }

    @Test
    void rawHeaderMapRetainsCaseInsensitiveReplacementSemantics() {
        Map<String, List<String>> rawHeaders = new LinkedHashMap<>();
        rawHeaders.put("cookie", List.of("first=one"));
        rawHeaders.put("COOKIE", List.of("second=two"));
        rawHeaders.put("X-Test", List.of("old"));
        rawHeaders.put("x-test", List.of("new"));
        Metadata metadata = Metadata.empty().with(
                WebRequest.urlKey, "/test", WebRequest.methodKey, HttpRequestMethod.GET,
                WebRequest.headersKey, rawHeaders);

        assertEquals(List.of("second=two"), WebRequest.getHeaders(metadata).get("Cookie"));
        assertEquals(List.of("new"), WebRequest.getHeaders(metadata).get("X-Test"));
    }

    @Test
    void builderCookieListRetainsMutableContract() {
        WebRequest.Builder builder = WebRequest.get("/test").cookie(new HttpCookie("first", "one"));
        List<HttpCookie> cookies = builder.cookies();
        assertSame(cookies, builder.cookies());

        cookies.add(new HttpCookie("second", "two"));
        cookies.getFirst().setValue("updated");

        WebRequest request = builder.build();
        assertEquals(List.of("first=updated; second=two"), request.getHeaders("Cookie"));
        assertEquals(List.of("updated", "two"),
                     request.getCookies().stream().map(HttpCookie::getValue).toList());
    }
}
