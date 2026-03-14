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

import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebPayloadConversionTest {

    @Test
    void webResponseReturnsExistingObjectWithoutUnnecessaryConversion() {
        Payload payload = new Payload("test");
        WebResponse response = WebResponse.builder()
                .status(200)
                .header("Content-Type", "application/json")
                .payload(payload)
                .build();

        assertSame(payload, response.getPayloadAs(Payload.class));
    }

    @Test
    void webResponseParsesJsonBytesWhenContentTypeHasCharset() {
        Payload payload = new Payload("test");
        WebResponse response = WebResponse.builder()
                .status(200)
                .header("Content-Type", "application/json; charset=UTF-8")
                .payload(JsonUtils.asBytes(payload))
                .build();

        assertEquals(payload, response.getPayloadAs(Payload.class));
    }

    @Test
    void webResponseConvertsObjectPayloadToStringAndBytes() {
        Map<String, String> payload = Map.of("foo", "bar");
        WebResponse response = WebResponse.builder().status(200).payload(payload).build();

        assertEquals(JsonUtils.asJson(payload), response.getPayloadAs(String.class));
        assertArrayEquals(JsonUtils.asBytes(payload), response.getPayloadAs(byte[].class));
    }

    @Test
    void webRequestSupportsLowLevelStringAndByteArrayConversions() {
        String payload = "{\"foo\":\"bar\"}";
        WebRequest request = WebRequest.builder()
                .method(HttpRequestMethod.POST)
                .url("/test")
                .header("Content-Type", "application/problem+json")
                .payload(payload)
                .build();

        assertArrayEquals(payload.getBytes(), request.getPayloadAs(byte[].class));
        assertEquals(Map.of("foo", "bar"), request.getPayloadAs(Map.class));
    }

    private record Payload(String value) {
    }
}
