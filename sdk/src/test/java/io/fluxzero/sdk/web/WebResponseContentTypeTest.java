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
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class WebResponseContentTypeTest {
    private static final String SCRIPT = "export function example() {}";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitTypeWinsRegardlessOfPayloadOrder(boolean payloadFirst) {
        var builder = WebResponse.builder();
        var response = (payloadFirst ? builder.payload(SCRIPT).contentType("text/javascript")
                : builder.contentType("text/javascript").payload(SCRIPT)).build();
        assertEquals(List.of("text/javascript"), response.getHeaders("Content-Type"));
        var serialized = response.serialize(new JacksonSerializer());
        assertEquals("text/javascript", serialized.getData().getFormat());
        assertArrayEquals(SCRIPT.getBytes(UTF_8), serialized.getData().getValue());
        assertEquals(List.of("text/javascript"), WebResponse.getHeaders(serialized.getMetadata()).get("Content-Type"));
    }

    @Test
    void replacementIsCaseInsensitiveAndDoesNotChangeSourceResponse() {
        var source = WebResponse.builder().headers(Map.of("content-type", List.of("text/plain")))
                .payload(SCRIPT).build();
        var changed = source.toBuilder().contentType("text/html").contentType("text/javascript").build();
        assertEquals(List.of("text/javascript"), changed.getHeaders("Content-Type"));
        assertEquals(List.of("text/plain"), source.getHeaders("Content-Type"));
    }

    @Test
    void preservesInferredTypesNullOverridesAndMultivaluedHeaders() {
        assertEquals("text/plain", WebResponse.builder().payload(SCRIPT).contentType(null).build().getContentType());
        assertEquals("application/octet-stream", WebResponse.builder().payload(new byte[0]).build().getContentType());
        assertEquals("application/octet-stream", WebResponse.builder().payload(new ByteArrayInputStream(new byte[0]))
                .build().getContentType());
        assertNull(WebResponse.builder().payload(Map.of("hello", "world")).build().getContentType());
        var response = WebResponse.builder().payload(SCRIPT).contentType("text/javascript")
                .header("Set-Cookie", "first=1").header("Set-Cookie", "second=2").build();
        assertEquals(List.of("first=1", "second=2"), response.getHeaders("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void servesJavaScriptThroughFixture(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Endpoint()) : TestFixture.create(new Endpoint());
        try {
            fixture.whenGet("/module.js").expectWebResult(r -> r.getStatus() == 200
                    && r.getHeaders("Content-Type").equals(List.of("text/javascript"))
                    && SCRIPT.equals(r.getPayloadAs(String.class))).expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    static class Endpoint {
        @HandleGet("/module.js")
        WebResponse get() {
            return WebResponse.builder().payload(SCRIPT).contentType("text/javascript").build();
        }
    }
}
