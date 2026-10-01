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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DefaultWebRequestContextTest {

    @Test
    void missingAndNullBodyParametersFallBackToQueryParameters() {
        Metadata metadata = WebRequest.post("/test?missing=fallback&nil=fallback&present=query")
                .contentType("application/json").build().getMetadata();
        DefaultWebRequestContext context = context(metadata,
                "{\"nil\":null,\"present\":\"body\"}".getBytes(StandardCharsets.UTF_8));
        for (String name : List.of("missing", "nil")) {
            assertEquals("fallback", context.getParameter(name, WebParameterSource.BODY, WebParameterSource.QUERY)
                    .as(String.class));
        }
        assertEquals("body", context.getParameter("present", WebParameterSource.BODY, WebParameterSource.QUERY)
                .as(String.class));
    }

    @Test
    void readsCaseInsensitiveCookiesAndPrefersFirstForwardedAddress() {
        DefaultWebRequestContext context = context(Map.of(
                "cOoKiE", List.of("session=first; theme=dark", "session=second"),
                "x-FoRwArDeD-fOr", List.of("203.0.113.1, 203.0.113.2", "203.0.113.3"),
                "Forwarded", List.of("198.51.100.1"),
                "X-Real-IP", List.of("192.0.2.1")));

        assertEquals(Map.of("session", "first", "theme", "dark"), context.getCookieMap());
        assertEquals("203.0.113.1", context.getRemoteAddress());
    }

    @Test
    void onlyConsidersFirstValueOfEachAddressHeader() {
        DefaultWebRequestContext context = context(Map.of(
                "X-Forwarded-For", List.of("xyz", "203.0.113.1"),
                "Forwarded", List.of("198.51.100.1"),
                "X-Real-IP", List.of("192.0.2.1")));

        assertEquals("198.51.100.1", context.getRemoteAddress());
    }

    @Test
    void fallsBackPastEmptyAddressHeaders() {
        DefaultWebRequestContext context = context(Map.of(
                "X-Forwarded-For", List.of(), "Forwarded", List.of(),
                "x-real-ip", List.of("2001:db8::1")));

        assertEquals("2001:db8::1", context.getRemoteAddress());
    }

    @Test
    void missingHeadersProduceEmptyCookiesAndAddress() {
        DefaultWebRequestContext context = context(Map.of());

        assertEquals(Map.of(), context.getCookieMap());
        assertEquals("", context.getRemoteAddress());
    }

    @Test
    @SneakyThrows
    void multipartFormDataExposesTextAndFileParts() {
        String boundary = "FluxzeroBoundary";
        byte[] fileContent = "A\0B".getBytes(StandardCharsets.ISO_8859_1);
        DefaultWebRequestContext context = context(
                "multipart/form-data; boundary=\"" + boundary + "\"",
                multipartBody(boundary, "caf\u00e9", new String(fileContent, StandardCharsets.ISO_8859_1)));

        assertEquals("caf\u00e9", context.getFormParameter("description").as(String.class));
        assertEquals(Map.of("description", List.of("caf\u00e9")), context.getFormParameters());

        WebFormPart upload = context.getFormParameter("upload").as(WebFormPart.class);
        assertEquals("meter;readings.csv", upload.getFileName());
        assertEquals("application/octet-stream", upload.getHeader("content-type").orElseThrow());
        assertArrayEquals(fileContent, upload.getContent());
        assertArrayEquals(fileContent, context.getFormParameter("upload").as(byte[].class));
        assertArrayEquals(fileContent, context.getFormParameter("upload").as(InputStream.class).readAllBytes());
        assertEquals(List.of(upload), context.getFormParts().get("upload"));
    }

    @Test
    void webFormPartFallsBackToUtf8ForUnknownCharset() {
        WebFormPart part = new WebFormPart(
                "field",
                null,
                "text/plain; charset=does-not-exist",
                Map.of("Content-Type", List.of("text/plain; charset=does-not-exist")),
                "test".getBytes(StandardCharsets.UTF_8));

        assertEquals("test", part.asString());
    }

    private static DefaultWebRequestContext context(String contentType, byte[] body) {
        Metadata metadata = WebRequest.post("/upload").contentType(contentType).build().getMetadata();
        return context(metadata, body);
    }

    private static DefaultWebRequestContext context(Map<String, List<String>> headers) {
        return context(WebRequest.get("/headers").build().getMetadata().with(WebRequest.headersKey, headers),
                       new byte[0]);
    }

    private static DefaultWebRequestContext context(Metadata metadata, byte[] body) {
        SerializedMessage message = new SerializedMessage(
                new Data<>(body, byte[].class.getName(), 0, "application/octet-stream"),
                metadata, "message-id", 0L);
        return new DefaultWebRequestContext(new DeserializingMessage(
                message, __ -> body, WEBREQUEST, null, mock(Serializer.class)));
    }

    private static byte[] multipartBody(String boundary, String description, String upload) {
        return String.join("\r\n",
                "--" + boundary,
                "Content-Disposition: form-data; name=\"description\"",
                "Content-Type: text/plain; charset=ISO-8859-1",
                "",
                description,
                "--" + boundary,
                "Content-Disposition: form-data; name=\"upload\"; filename=\"meter;readings.csv\"",
                "Content-Type: application/octet-stream",
                "",
                upload,
                "--" + boundary + "--",
                "").getBytes(StandardCharsets.ISO_8859_1);
    }
}
