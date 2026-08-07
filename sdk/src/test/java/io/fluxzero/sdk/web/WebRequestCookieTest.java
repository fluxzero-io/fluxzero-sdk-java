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

import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.sdk.web.CookieValueConflictPolicy.ALLOW_CONFLICTING_VALUES;
import static io.fluxzero.sdk.web.CookieValueConflictPolicy.DEFAULT;
import static io.fluxzero.sdk.web.CookieValueConflictPolicy.REJECT_CONFLICTING_VALUES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRequestCookieTest {

    @Test
    void returnsAllCookieHeaderValuesInLogicalWireOrder() {
        WebRequest request = WebRequest.get("/test")
                .header("cOoKiE", "theme=dark; session=abc")
                .header("COOKIE", "csrf=xyz; session=abc")
                .build();

        assertEquals(List.of("theme=dark", "session=abc", "csrf=xyz", "session=abc"), describe(request.getCookies()));
        assertEquals(List.of("session=abc", "session=abc"), describe(request.getCookies("session")));
        assertTrue(request.getCookies("Session").isEmpty());
        assertEquals("xyz", request.getCookie("csrf").orElseThrow().getValue());
        assertEquals("xyz", WebRequest.getCookie(request.getMetadata(), "csrf").orElseThrow().getValue());
    }

    @Test
    void defaultAndAllowPoliciesRetainFirstMatchBehavior() {
        WebRequest request = WebRequest.get("/test")
                .header("Cookie", "theme=first")
                .header("Cookie", "theme=second")
                .build();

        assertEquals("first", request.getCookie("theme").orElseThrow().getValue());
        assertEquals("first", request.getCookie("theme", DEFAULT).orElseThrow().getValue());
        assertEquals("first", request.getCookie("theme", ALLOW_CONFLICTING_VALUES).orElseThrow().getValue());
        assertTrue(request.getCookie("missing", ALLOW_CONFLICTING_VALUES).isEmpty());
    }

    @Test
    void rejectPolicyAllowsIdenticalValues() {
        WebRequest request = WebRequest.get("/test")
                .header("Cookie", "session=abc")
                .header("Cookie", "session=abc")
                .build();

        assertEquals("abc", request.getCookie("session", REJECT_CONFLICTING_VALUES).orElseThrow().getValue());
        assertTrue(request.getCookie("missing", REJECT_CONFLICTING_VALUES).isEmpty());
    }

    @Test
    void rejectPolicyRejectsDifferentValuesWithoutSensitiveData() {
        WebRequest request = WebRequest.get("/test")
                .header("Cookie", "private-session=secret-first")
                .header("Cookie", "private-session=secret-second")
                .build();

        CookieConflictException exception = assertThrows(
                CookieConflictException.class,
                () -> request.getCookie("private-session", REJECT_CONFLICTING_VALUES));

        assertFalse(exception.getMessage().contains("private-session"));
        assertFalse(exception.getMessage().contains("secret-first"));
        assertFalse(exception.getMessage().contains("secret-second"));
    }

    @Test
    void cookieValuesAreComparedExactlyAndCommaIsNotASeparator() {
        WebRequest conflicting = WebRequest.get("/test")
                .header("Cookie", "session=value")
                .header("Cookie", "session=Value")
                .build();
        assertThrows(CookieConflictException.class,
                     () -> conflicting.getCookie("session", REJECT_CONFLICTING_VALUES));

        WebRequest comma = WebRequest.get("/test").header("Cookie", "one=first,two=second").build();
        assertEquals(List.of("one=first,two=second"), describe(comma.getCookies()));
    }

    @Test
    void builderMetadataAndSerializationRoundTripsPreserveCookieHeaders() {
        WebRequest input = WebRequest.get("/test")
                .header("Cookie", "first=one; shared=same")
                .header("Cookie", "shared=same; last=three")
                .payload(Map.of("body", "value"))
                .build();

        WebRequest builderCopy = input.toBuilder().build();
        assertEquals(input.getHeaders("Cookie"), builderCopy.getHeaders("cookie"));
        assertEquals(describe(input.getCookies()), describe(builderCopy.getCookies()));

        WebRequest.Builder mutableCopy = input.toBuilder();
        assertEquals(describe(input.getCookies()), describe(mutableCopy.cookies()));
        mutableCopy.cookie(new HttpCookie("added", "four"));
        assertEquals(List.of("first=one", "shared=same", "shared=same", "last=three", "added=four"),
                     describe(mutableCopy.build().getCookies()));

        JacksonSerializer serializer = new JacksonSerializer();
        WebRequest serializedCopy = (WebRequest) serializer.deserializeMessage(input.serialize(serializer), WEBREQUEST)
                .toMessage();
        assertEquals(input.getHeaders("Cookie"), serializedCopy.getHeaders("cookie"));
        assertEquals(describe(input.getCookies()), describe(serializedCopy.getCookies()));
    }

    @Test
    void requestAndBuilderToStringRedactCookieData() {
        WebRequest request = WebRequest.get("/test")
                .header("Cookie", "private-cookie=secret-cookie-value")
                .build();

        assertFalse(request.toString().contains("private-cookie"));
        assertFalse(request.toString().contains("secret-cookie-value"));
        assertFalse(request.toBuilder().toString().contains("private-cookie"));
        assertFalse(request.toBuilder().toString().contains("secret-cookie-value"));
    }

    private static List<String> describe(List<HttpCookie> cookies) {
        return cookies.stream().map(c -> c.getName() + "=" + c.getValue()).toList();
    }
}
