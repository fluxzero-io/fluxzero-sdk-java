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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.web.WebRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reference {@link UserProvider} for Fluxzero-compatible OIDC providers.
 *
 * <p>The provider resolves incoming {@link WebRequest} bearer tokens by calling the configured OIDC
 * {@code userinfo_endpoint}. This intentionally keeps the SDK implementation dependency-free while supporting both the
 * real Fluxzero IDP and the local dev/test IDP, because both expose the same OIDC userinfo contract.</p>
 *
 * <p>Typical application setup:</p>
 * <pre>{@code
 * Fluxzero fluxzero = DefaultFluxzero.builder()
 *     .registerUserProvider(OidcUserProvider.fromProperties())
 *     .build();
 * }</pre>
 *
 * <p>Configuration is read from {@code fluxzero.auth.oidc.issuer}; optionally
 * {@code fluxzero.auth.oidc.userinfo-endpoint} can override discovery.</p>
 */
public class OidcUserProvider extends AbstractUserProvider {
    public static final String ISSUER_PROPERTY = "fluxzero.auth.oidc.issuer";
    public static final String USERINFO_ENDPOINT_PROPERTY = "fluxzero.auth.oidc.userinfo-endpoint";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    private final String issuer;
    private final String configuredUserInfoEndpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private volatile String discoveredUserInfoEndpoint;

    /**
     * Creates a provider from application properties.
     */
    public static OidcUserProvider fromProperties() {
        return new OidcUserProvider(
                requiredProperty(ISSUER_PROPERTY),
                ApplicationProperties.getProperty(USERINFO_ENDPOINT_PROPERTY));
    }

    public OidcUserProvider(String issuer) {
        this(issuer, null);
    }

    public OidcUserProvider(String issuer, String userInfoEndpoint) {
        this(issuer, userInfoEndpoint, DEFAULT_TIMEOUT);
    }

    public OidcUserProvider(String issuer, String userInfoEndpoint, Duration timeout) {
        this(issuer, userInfoEndpoint, timeout, HttpClient.newHttpClient());
    }

    OidcUserProvider(String issuer, String userInfoEndpoint, Duration timeout, HttpClient httpClient) {
        super(OidcUser.class);
        this.issuer = normalizeIssuer(issuer);
        this.configuredUserInfoEndpoint = blankToNull(userInfoEndpoint);
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
    }

    @Override
    public User getUserById(Object userId) {
        return null;
    }

    @Override
    public User getSystemUser() {
        return null;
    }

    @Override
    public User fromMessage(HasMessage message) {
        User metadataUser = super.fromMessage(message);
        if (metadataUser != null) {
            return metadataUser;
        }
        if (!(message.toMessage() instanceof WebRequest request)) {
            return null;
        }
        return bearerToken(request).map(this::resolveUser).orElse(null);
    }

    protected OidcUser resolveUser(String bearerToken) {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(userInfoEndpoint()))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + bearerToken)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OIDC userinfo failed with status " + response.statusCode());
            }
            JsonNode claims = JsonUtils.writer.readTree(response.body());
            String subject = text(claims, "sub");
            if (subject == null) {
                return null;
            }
            return new OidcUser(subject, text(claims, "email"), text(claims, "tenant_id"), text(claims, "name"),
                                roles(claims), JsonUtils.writer.convertValue(claims, CLAIMS_TYPE));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve OIDC userinfo", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resolving OIDC userinfo", e);
        }
    }

    private String userInfoEndpoint() throws IOException, InterruptedException {
        if (configuredUserInfoEndpoint != null) {
            return configuredUserInfoEndpoint;
        }
        String endpoint = discoveredUserInfoEndpoint;
        if (endpoint == null) {
            synchronized (this) {
                endpoint = discoveredUserInfoEndpoint;
                if (endpoint == null) {
                    endpoint = discoverUserInfoEndpoint();
                    discoveredUserInfoEndpoint = endpoint;
                }
            }
        }
        return endpoint;
    }

    private String discoverUserInfoEndpoint() throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(
                        URI.create(issuer + "/.well-known/openid-configuration"))
                .timeout(timeout)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String endpoint = text(JsonUtils.writer.readTree(response.body()), "userinfo_endpoint");
            if (endpoint != null) {
                return endpoint;
            }
        }
        return issuer + "/userinfo";
    }

    private static Optional<String> bearerToken(WebRequest request) {
        return Optional.ofNullable(request.getHeader("Authorization"))
                .map(String::trim)
                .filter(value -> value.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(value -> value.substring(7).trim())
                .filter(value -> !value.isBlank());
    }

    private static Set<String> roles(JsonNode claims) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add("authenticated");
        addClaimValues(roles, claims.path("role"));
        addClaimValues(roles, claims.path("roles"));
        addClaimValues(roles, claims.path("groups"));
        Optional.ofNullable(text(claims, "scope"))
                .stream()
                .flatMap(scope -> List.of(scope.split("\\s+")).stream())
                .filter(role -> !role.isBlank())
                .forEach(roles::add);
        return roles;
    }

    private static void addClaimValues(Set<String> roles, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> addClaimValues(roles, value));
            return;
        }
        String value = node.asText(null);
        if (value != null && !value.isBlank()) {
            roles.add(value);
        }
    }

    private static String text(JsonNode node, String name) {
        String value = node.path(name).asText(null);
        return blankToNull(value);
    }

    private static String normalizeIssuer(String issuer) {
        String value = blankToNull(issuer);
        if (value == null) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        return value.replaceAll("/+$", "");
    }

    private static String requiredProperty(String name) {
        return Optional.ofNullable(ApplicationProperties.getProperty(name))
                .map(OidcUserProvider::blankToNull)
                .orElseThrow(() -> new IllegalStateException("Missing " + name));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
