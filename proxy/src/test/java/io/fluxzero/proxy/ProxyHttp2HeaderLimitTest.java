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
 *
 */

package io.fluxzero.proxy;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.fluxzero.proxy.ProxyHeaderBufferTest.*;
import static io.fluxzero.proxy.ProxyServer.HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY;
import static io.fluxzero.proxy.ProxyServer.USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULTS_VERSION_PROPERTY;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("Proxy startup properties")
class ProxyHttp2HeaderLimitTest {
    @ParameterizedTest
    @CsvSource(value = {
            "null,null,16384", "2026.10.02,null,16384", "2026.10.03,null,16384", "2026.10.04,null,16384",
            "null,1048576,1048576", "2026.10.02,1048576,1048576", "2026.10.03,1048576,1048576",
            "null,4096,4096", "null,2097152,1048576"
    }, nullValues = "null")
    void responseLimitIgnoresDefaultsVersionAndRespectsExplicitOverrides(String version, String maximum, int expected) {
        var http1 = new HttpConfiguration();
        http1.setRequestHeaderSize(12345);
        ProxyServer.configureResponseHeaders(http1, 1048576, 32768);
        var http2 = ProxyServer.http2Configuration(http1, key -> switch (key) {
            case DEFAULTS_VERSION_PROPERTY -> version;
            case HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY -> maximum;
            default -> null;
        });
        assertEquals(expected, http2.getMaxResponseHeaderSize());
        assertEquals(12345, http2.getRequestHeaderSize());
        assertEquals(1048576, http1.getMaxResponseHeaderSize());
        assertEquals(32768, http1.getResponseHeaderSize());
        assertEquals(Math.min(32768, expected), http2.getResponseHeaderSize());
    }

    @Test
    void configurationIsLocalToEachProxyAndKeepsTheSharedCeiling() {
        var http1 = new HttpConfiguration();
        ProxyServer.configureResponseHeaders(http1, 32768, null);
        var small = Map.of(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, "4096");
        var large = Map.of(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, "65536");
        assertEquals(4096, ProxyServer.http2Configuration(http1, small::get).getMaxResponseHeaderSize());
        assertEquals(32768, ProxyServer.http2Configuration(http1, large::get).getMaxResponseHeaderSize());
        assertEquals(4096, ProxyServer.http2Configuration(http1, small::get).getMaxResponseHeaderSize());
        assertEquals(32768, http1.getMaxResponseHeaderSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", ""})
    void invalidLimitsFailConfiguration(String value) {
        var properties = Map.of(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, value);
        assertThrows(IllegalArgumentException.class,
                     () -> ProxyServer.http2Configuration(new HttpConfiguration(), properties::get));
    }

    @Test
    void headerConfigurationNeverReadsTheDefaultsVersion() {
        var http2 = ProxyServer.http2Configuration(new HttpConfiguration(), key -> {
            assertEquals(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, key);
            return null;
        });
        assertEquals(16384, http2.getMaxResponseHeaderSize());
    }

    @Test
    void explicitHttp2LimitAllowsLargeResponseHeaders() throws Exception {
        String previous = System.getProperty(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY);
        try {
            System.setProperty(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, "1048576");
            try (var support = new HeaderBufferTestSupport(false, true);
                 var h2 = client(HttpClient.Version.HTTP_2)) {
                var response = send(h2, support, 128 * 1024, false);
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                assertEquals(200, response.statusCode());
                assertEquals("h".repeat(128 * 1024), response.headers().firstValue("X-Padding").orElseThrow());
            }
        } finally {
            if (previous == null) { System.clearProperty(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY); }
            else { System.setProperty(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, previous); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void smallHttp2ResponsesReuseBoundedBuffersAndRecoverAfterOversizedHeaders(boolean direct) throws Exception {
        var saved = new java.util.HashMap<String, String>();
        List.of(DEFAULTS_VERSION_PROPERTY, HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY, USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY)
                .forEach(k -> saved.put(k, System.getProperty(k)));
        HeaderBufferPool pool;
        try {
            System.clearProperty(DEFAULTS_VERSION_PROPERTY);
            System.clearProperty(HTTP2_MAX_RESPONSE_HEADER_SIZE_PROPERTY);
            System.setProperty(USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY, Boolean.toString(direct));
            try (var support = new HeaderBufferTestSupport(true, true);
                 var h2 = client(HttpClient.Version.HTTP_2);
                 var h1 = client(HttpClient.Version.HTTP_1_1)) {
                pool = (HeaderBufferPool) support.pool;
                pool.trackHttp2BufferReuse();
                for (int i = 0; i < 64; i++) {
                    var response = send(h2, support, 100, false);
                    assertEquals(HttpClient.Version.HTTP_2, response.version());
                    assertEquals(200, response.statusCode());
                    assertArrayEquals(new byte[]{'o', 'k'}, response.body());
                }
                assertEquals(64, pool.http2Headers(16384, direct));
                assertEquals(0, pool.http2Headers(16384, !direct));
                assertEquals(0, pool.http2HeadersAbove(16384));
                assertTrue(pool.distinctHttp2Buffers() < 8, "Sequential responses must reuse actual pooled buffers");

                var responses = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
                for (int i = 0; i < 12; i++) {
                    responses.add(h2.sendAsync(request(support, 9000, false), HttpResponse.BodyHandlers.ofByteArray()));
                }
                for (var future : responses) {
                    var response = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(HttpClient.Version.HTTP_2, response.version());
                    assertEquals(200, response.statusCode());
                    assertEquals(9000, response.headers().firstValue("X-Padding").orElseThrow().length());
                }
                assertEquals(200, send(h2, support, 16000, false).statusCode());
                var connector = (ServerConnector) support.server.getConnectors()[0];
                var connections = List.copyOf(connector.getConnectedEndPoints());
                assertEquals(1, connections.size());
                var rejected = assertThrows(IOException.class, () -> send(h2, support, 17000, false));
                assertTrue(rejected.getMessage().contains("RST_STREAM"), rejected.toString());
                // Jetty reports an HPACK SessionException after encoding an oversized header list.
                // It sends GOAWAY as well as RST_STREAM: the client must recover on a fresh connection.
                var recovered = send(h2, support, 100, false);
                assertEquals(HttpClient.Version.HTTP_2, recovered.version());
                assertEquals(200, recovered.statusCode());
                assertArrayEquals(new byte[]{'o', 'k'}, recovered.body());
                assertTrue(connector.getConnectedEndPoints().stream().anyMatch(e -> !connections.contains(e)),
                           "The client must recover after Jetty closes the oversized response's HTTP/2 session");
                assertEquals(0, pool.http2HeadersAbove(16384));
                assertEquals(200, send(h1, support, 65000, false).statusCode());
                assertEquals(200, h2.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(support.url("/buffer")))
                        .header("X-Header-Bytes", "100").header("X-Request-Padding", "r".repeat(20000)).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode(), "Request header limits must not shrink");
                System.out.printf("HTTP2 headers direct=%s acquisitions=%d distinctBuffers=%d oversizedAcquisitions=%d%n",
                                  direct, pool.http2Headers(16384, direct), pool.distinctHttp2Buffers(), pool.http2HeadersAbove(16384));
            }
            assertEquals(0, pool.outstanding(), "All buffers must be released on shutdown");
        } finally {
            saved.forEach((key, value) -> {
                if (value == null) { System.clearProperty(key); }
                else { System.setProperty(key, value); }
            });
        }
    }
}
