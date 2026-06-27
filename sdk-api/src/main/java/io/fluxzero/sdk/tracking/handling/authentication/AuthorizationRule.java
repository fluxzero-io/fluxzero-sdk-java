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

import java.util.Objects;

/**
 * Runtime-neutral authorization requirement derived from Fluxzero authentication annotations.
 *
 * @param value role value, or {@code null} for user-presence rules
 * @param throwIfUnauthorized whether a failed check should be surfaced as an error instead of a silent skip
 * @param requiresUser whether this rule requires an authenticated user
 * @param forbidsUser whether this rule forbids an authenticated user
 */
public record AuthorizationRule(
        String value,
        boolean throwIfUnauthorized,
        boolean requiresUser,
        boolean forbidsUser) {

    /**
     * Rule that explicitly clears broader authentication requirements.
     */
    public static final AuthorizationRule NO_USER_REQUIRED =
            new AuthorizationRule(null, false, false, false);

    /**
     * Returns a role requirement.
     */
    public static AuthorizationRule requiresRole(String role, boolean throwIfUnauthorized) {
        return new AuthorizationRule(Objects.requireNonNull(role, "role"), throwIfUnauthorized, true, false);
    }

    /**
     * Returns a negative role requirement.
     */
    public static AuthorizationRule forbidsRole(String role, boolean throwIfUnauthorized) {
        return new AuthorizationRule("!" + Objects.requireNonNull(role, "role"), throwIfUnauthorized, true, false);
    }

    /**
     * Returns a rule that requires any authenticated user.
     */
    public static AuthorizationRule requiresUser(boolean throwIfUnauthorized) {
        return new AuthorizationRule(null, throwIfUnauthorized, true, false);
    }

    /**
     * Returns a rule that rejects authenticated users.
     */
    public static AuthorizationRule forbidsUser(boolean throwIfUnauthorized) {
        return new AuthorizationRule(null, throwIfUnauthorized, false, true);
    }
}
