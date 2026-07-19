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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Default {@link User} representation for identities resolved from an OIDC userinfo endpoint.
 *
 * @param subject  stable subject identifier from the {@code sub} claim
 * @param email    optional email address
 * @param tenantId optional Fluxzero tenant identifier from the {@code tenant_id} claim
 * @param displayName optional display name from the {@code name} claim
 * @param roles    resolved roles and scopes; always includes {@code authenticated}
 * @param claims   raw userinfo claims
 */
public record OidcUser(
        String subject,
        String email,
        String tenantId,
        String displayName,
        Set<String> roles,
        Map<String, Object> claims
) implements User {

    public OidcUser {
        roles = roles == null ? Set.of("authenticated") : Set.copyOf(roles);
        claims = claims == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    @Override
    public String getName() {
        return subject;
    }

    @Override
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * Returns a raw claim by name, or {@code null} when the claim is absent.
     */
    public Object claim(String name) {
        return claims.get(name);
    }
}
