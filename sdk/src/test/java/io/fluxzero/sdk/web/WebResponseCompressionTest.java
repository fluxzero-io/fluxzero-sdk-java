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
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class WebResponseCompressionTest {
    @Test
    void gzipStreamAccessPreservesOwnershipAndDoesNotConsumeBeforeConversion() throws Exception {
        byte[] encoded = CompressionAlgorithm.GZIP.compress("hello".getBytes(UTF_8));
        var closed = new AtomicBoolean();
        try (var source = new ByteArrayInputStream(encoded) {
            @Override
            public void close() {
                closed.set(true);
            }
        }) {
            var response = WebResponse.builder().payload(source).header("Content-Encoding", "gzip").build();
            assertSame(source, response.getPayload());
            assertSame(source, response.getPayloadAs(Object.class));
            assertSame(source, response.getPayloadAs(InputStream.class));
            assertEquals(encoded.length, source.available());
            assertEquals("hello", response.getPayloadAs(String.class));
            assertFalse(closed.get());
        }
        assertTrue(closed.get());
    }

    @ParameterizedTest
    @CsvSource({"false,gzip", "true,gzip", "false,identity", "true,identity"})
    void readsTypedResponse(boolean async, String encoding) {
        TestFixture fixture = async ? TestFixture.createAsync(new Endpoint()) : TestFixture.create(new Endpoint());
        try {
            fixture.whenWebRequest(WebRequest.get("/compression-proof").header("Accept-Encoding", encoding).build())
                    .expectWebResult(r -> r.<Reply>getPayloadAs(Reply.class).text().equals("a".repeat(3000)))
                    .expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    public record Reply(String text) {}

    @NoUserRequired
    static class Endpoint {
        @HandleGet("/compression-proof")
        Reply get() {
            return new Reply("a".repeat(3000));
        }
    }
}
