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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime-neutral authorization evaluator for Fluxzero authentication metadata.
 */
public final class AuthorizationPolicy {

    private AuthorizationPolicy() {
    }

    /**
     * Evaluates the supplied action against the applicable authorization rules.
     *
     * @param action human-readable action used in diagnostics
     * @param user current user, or {@code null} for unauthenticated execution
     * @param rules applicable rules; {@code null} means no authorization metadata was declared
     */
    public static AuthorizationDecision evaluate(
            String action, AuthorizationSubject user, Collection<AuthorizationRule> rules) {
        Objects.requireNonNull(action, "action");
        if (rules == null || rules.isEmpty() || rules.contains(AuthorizationRule.NO_USER_REQUIRED)) {
            return AuthorizationDecision.allow();
        }
        Optional<AuthorizationRule> forbidsUser = rules.stream().filter(AuthorizationRule::forbidsUser).findFirst();
        if (forbidsUser.isPresent()) {
            if (user != null) {
                if (forbidsUser.get().throwIfUnauthorized()) {
                    return AuthorizationDecision.rejected(
                            AuthorizationFailure.UNAUTHORIZED, "Not allowed for authenticated users");
                }
                return AuthorizationDecision.skipped();
            }
            return AuthorizationDecision.allow();
        }
        if (user == null) {
            if (rules.stream().anyMatch(AuthorizationRule::throwIfUnauthorized)) {
                return AuthorizationDecision.rejected(
                        AuthorizationFailure.UNAUTHENTICATED, "%s requires authentication".formatted(action));
            }
            return AuthorizationDecision.skipped();
        }
        List<AuthorizationRule> remainingRoles = new ArrayList<>();
        List<AuthorizationRule> forbiddenRoles = rules.stream().filter(rule -> {
            if (rule.value() == null) {
                return false;
            }
            if (rule.value().startsWith("!")) {
                return true;
            }
            remainingRoles.add(rule);
            return false;
        }).toList();
        for (AuthorizationRule rule : forbiddenRoles) {
            if (rule.value() != null && user.hasRole(rule.value().substring(1))) {
                if (rule.throwIfUnauthorized()) {
                    return AuthorizationDecision.rejected(
                            AuthorizationFailure.UNAUTHORIZED,
                            "User %s is unauthorized to execute %s".formatted(user.getName(), action));
                }
                return AuthorizationDecision.skipped();
            }
        }
        if (!remainingRoles.isEmpty()) {
            boolean throwIfUnauthorized = false;
            for (AuthorizationRule remainingRole : remainingRoles) {
                if (user.hasRole(remainingRole.value())) {
                    return AuthorizationDecision.allow();
                }
                if (remainingRole.throwIfUnauthorized()) {
                    throwIfUnauthorized = true;
                }
            }
            if (throwIfUnauthorized) {
                return AuthorizationDecision.rejected(
                        AuthorizationFailure.UNAUTHORIZED,
                        "User %s is unauthorized to execute %s".formatted(user.getName(), action));
            }
            return AuthorizationDecision.skipped();
        }
        return AuthorizationDecision.allow();
    }
}
