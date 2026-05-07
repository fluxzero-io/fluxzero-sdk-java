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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

public class WebRequestForwardingTest {

    private static final int port = TestUtils.getAvailablePort();
    private static HttpServer server;

    @BeforeAll
    static void beforeAll() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/get", WebRequestForwardingTest::handleGet);
        server.createContext("/string", WebRequestForwardingTest::handleString);
        server.createContext("/object", WebRequestForwardingTest::handleObject);
        server.start();
    }

    @AfterAll
    static void afterAll() {
        server.stop(0);
    }

    private final TestFixture testFixture = TestFixture.createAsync(
            DefaultFluxzero.builder().forwardWebRequestsToLocalServer(port)).spy();

    @Test
    void testGet() {
        testFixture.whenWebRequest(WebRequest.builder().method(HttpRequestMethod.GET).url("/get").build())
                .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.WEBRESPONSE)).append(any(), any()))
                .expectResult("get".getBytes());
    }

    @Test
    void testPostString() {
        testFixture.whenWebRequest(WebRequest.builder().method(HttpRequestMethod.POST).url("/string").payload("test").build())
                .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.WEBRESPONSE)).append(
                        any(), ArgumentMatchers.<SerializedMessage>argThat(message -> "200".equals(message.getMetadata().get(WebResponse.statusKey)))))
                .expectResult("test".getBytes());
    }

    @Test
    void testPostObject() {
        testFixture.whenWebRequest(WebRequest.builder().method(HttpRequestMethod.POST).url("/object").payload(new Foo("bar")).build())
                .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.WEBRESPONSE)).append(
                        any(), ArgumentMatchers.<SerializedMessage>argThat(message -> "200".equals(message.getMetadata().get(WebResponse.statusKey)))))
                .expectResult("object".getBytes());
    }

    private static void handleGet(HttpExchange exchange) throws IOException {
        if (methodIs(exchange, "GET")) {
            respond(exchange, "get".getBytes(UTF_8));
        }
    }

    private static void handleString(HttpExchange exchange) throws IOException {
        if (methodIs(exchange, "POST")) {
            respond(exchange, readBody(exchange));
        }
    }

    private static void handleObject(HttpExchange exchange) throws IOException {
        if (!methodIs(exchange, "POST")) {
            return;
        }
        Foo payload = JacksonSerializer.defaultObjectMapper.readValue(readBody(exchange), Foo.class);
        respond(exchange, "bar".equals(payload.bar()) ? 200 : 400, "object".getBytes(UTF_8));
    }

    private static boolean methodIs(HttpExchange exchange, String method) throws IOException {
        if (method.equals(exchange.getRequestMethod())) {
            return true;
        }
        respond(exchange, 405, new byte[0]);
        return false;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return body.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var response = exchange.getResponseBody()) {
            if (body.length > 0) {
                response.write(body);
            }
        }
    }

    private record Foo(String bar) {
    }
}
