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

package io.fluxzero.sdk.tracking.handling.authentication;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcUserProviderTest {

    @Test
    void resolvesBearerTokenThroughDiscoveredUserInfoEndpoint() throws Exception {
        try (StubOidcServer server = StubOidcServer.start()) {
            OidcUserProvider provider = new OidcUserProvider(server.issuer());

            User user = provider.fromMessage(WebRequest.get("/secure/me")
                                                     .header("authorization", "Bearer valid-token")
                                                     .build());

            OidcUser oidcUser = assertInstanceOf(OidcUser.class, user);
            assertEquals("rene@example.com", oidcUser.subject());
            assertEquals("rene@example.com", oidcUser.email());
            assertEquals("local-auth", oidcUser.tenantId());
            assertEquals("rene@example.com", oidcUser.getName());
            assertEquals("Rene", oidcUser.displayName());
            assertTrue(oidcUser.hasRole("authenticated"));
            assertTrue(oidcUser.hasRole("admin"));
            assertTrue(oidcUser.hasRole("profile"));
            assertEquals("Rene", oidcUser.claim("name"));
            assertNull(oidcUser.claim("optional_claim"));
        }
    }

    @Test
    void returnsNullWhenBearerTokenIsMissingOrRejected() throws Exception {
        try (StubOidcServer server = StubOidcServer.start()) {
            OidcUserProvider provider = new OidcUserProvider(server.issuer());

            assertNull(provider.fromMessage(WebRequest.get("/secure/me").build()));
            assertNull(provider.fromMessage(WebRequest.get("/secure/me")
                                                 .header("Authorization", "Bearer invalid-token")
                                                 .build()));
        }
    }

    @Test
    void metadataUserWinsBeforeUserInfoLookup() throws Exception {
        try (StubOidcServer server = StubOidcServer.start()) {
            OidcUserProvider provider = new OidcUserProvider(server.issuer());
            OidcUser metadataUser = new OidcUser(
                    "metadata-user", "metadata@example.com", "metadata-tenant", "Metadata User",
                    Set.of("metadata"), Map.of());
            WebRequest request = WebRequest.get("/secure/me").build();

            User user = provider.fromMessage(request.withMetadata(
                    provider.addToMetadata(request.getMetadata(), metadataUser)));

            assertEquals(metadataUser, user);
            assertFalse(server.userInfoCalled);
        }
    }

    private static final class StubOidcServer implements AutoCloseable {
        private final HttpServer server;
        private volatile boolean userInfoCalled;

        private StubOidcServer(HttpServer server) {
            this.server = server;
        }

        static StubOidcServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            StubOidcServer stub = new StubOidcServer(server);
            server.createContext("/.well-known/openid-configuration", stub::discovery);
            server.createContext("/userinfo", stub::userInfo);
            server.start();
            return stub;
        }

        String issuer() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void discovery(HttpExchange exchange) throws IOException {
            write(exchange, 200, """
                    {
                      "issuer": "%s",
                      "userinfo_endpoint": "%s/userinfo"
                    }
                    """.formatted(issuer(), issuer()));
        }

        private void userInfo(HttpExchange exchange) throws IOException {
            userInfoCalled = true;
            if (!"Bearer valid-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                write(exchange, 401, "Unauthorized");
                return;
            }
            write(exchange, 200, """
                    {
                      "sub": "rene@example.com",
                      "email": "rene@example.com",
                      "tenant_id": "local-auth",
                      "name": "Rene",
                      "roles": ["admin"],
                      "scope": "openid profile email",
                      "optional_claim": null
                    }
                    """);
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
