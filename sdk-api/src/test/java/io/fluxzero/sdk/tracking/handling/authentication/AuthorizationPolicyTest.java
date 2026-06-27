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

import io.fluxzero.sdk.registry.AnnotationDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationPolicyTest {

    @Test
    void evaluatesSharedAuthorizationRulesWithoutRuntimeReflection() {
        AuthorizationSubject admin = new TestSubject("admin", List.of("admin"));

        AuthorizationDecision missingUser = AuthorizationPolicy.evaluate(
                "DeleteOrder", null, List.of(AuthorizationRule.requiresUser(true)));
        AuthorizationDecision allowedRole = AuthorizationPolicy.evaluate(
                "DeleteOrder", admin, List.of(AuthorizationRule.requiresRole("admin", true)));
        AuthorizationDecision forbiddenUser = AuthorizationPolicy.evaluate(
                "Register", admin, List.of(AuthorizationRule.forbidsUser(false)));
        AuthorizationDecision publicAction = AuthorizationPolicy.evaluate(
                "Health", null, List.of(AuthorizationRule.NO_USER_REQUIRED));

        assertFalse(missingUser.allowed());
        assertEquals(AuthorizationFailure.UNAUTHENTICATED, missingUser.failure());
        assertTrue(allowedRole.allowed());
        assertFalse(forbiddenUser.allowed());
        assertFalse(forbiddenUser.rejected());
        assertTrue(publicAction.allowed());
    }

    @Test
    void lowersRegistryAnnotationsWithMethodTypePackagePrecedence() {
        Optional<List<AuthorizationRule>> methodRules = AuthorizationMetadata.effectiveRules(
                List.of(annotation("NoUserRequired")),
                List.of(annotation("RequiresAnyRole", "admin")),
                List.of(annotation("RequiresUser")));
        Optional<List<AuthorizationRule>> typeRules = AuthorizationMetadata.effectiveRules(
                List.of(),
                List.of(annotation("RequiresAnyRole", "admin")),
                List.of(annotation("RequiresUser")));
        Optional<List<AuthorizationRule>> noRules = AuthorizationMetadata.effectiveRules(List.of(), List.of());

        assertEquals(List.of(AuthorizationRule.NO_USER_REQUIRED), methodRules.orElseThrow());
        assertEquals(List.of(AuthorizationRule.requiresRole("admin", true)), typeRules.orElseThrow());
        assertTrue(noRules.isEmpty());
    }

    private static AnnotationDescriptor annotation(String name) {
        return new AnnotationDescriptor(name, "io.fluxzero.sdk.tracking.handling.authentication." + name, Map.of());
    }

    private static AnnotationDescriptor annotation(String name, String value) {
        return new AnnotationDescriptor(
                name, "io.fluxzero.sdk.tracking.handling.authentication." + name,
                Map.of("value", List.of(value)));
    }

    private record TestSubject(String getName, List<String> roles) implements AuthorizationSubject {
        @Override
        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }
}
