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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Converts registry annotation metadata into runtime-neutral authorization rules.
 */
public final class AuthorizationMetadata {
    private static final String REQUIRES_ANY_ROLE = RequiresAnyRole.class.getName();
    private static final String REQUIRES_USER = RequiresUser.class.getName();
    private static final String NO_USER_REQUIRED = NoUserRequired.class.getName();
    private static final String FORBIDS_USER = ForbidsUser.class.getName();
    private static final String FORBIDS_ANY_ROLE = ForbidsAnyRole.class.getName();

    private AuthorizationMetadata() {
    }

    /**
     * Returns rules for the first metadata level that declares auth annotations, preserving Fluxzero precedence:
     * method metadata overrides type metadata, which overrides package metadata.
     */
    @SafeVarargs
    public static Optional<List<AuthorizationRule>> effectiveRules(List<AnnotationDescriptor>... levels) {
        Objects.requireNonNull(levels, "levels");
        for (List<AnnotationDescriptor> level : levels) {
            Optional<List<AuthorizationRule>> rules = rules(level);
            if (rules.isPresent()) {
                return rules;
            }
        }
        return Optional.empty();
    }

    /**
     * Converts annotations on one metadata level into authorization rules.
     */
    public static Optional<List<AuthorizationRule>> rules(Collection<AnnotationDescriptor> annotations) {
        Objects.requireNonNull(annotations, "annotations");
        List<AuthorizationRule> result = annotations.stream()
                .flatMap(AuthorizationMetadata::rules)
                .toList();
        return annotations.stream().anyMatch(AuthorizationMetadata::authAnnotation)
                ? Optional.of(result) : Optional.empty();
    }

    private static Stream<AuthorizationRule> rules(AnnotationDescriptor annotation) {
        if (matches(annotation, REQUIRES_ANY_ROLE, "RequiresAnyRole")) {
            return annotation.values("value").stream()
                    .map(role -> AuthorizationRule.requiresRole(
                            role, annotation.booleanValue("throwIfUnauthorized", true)));
        }
        if (matches(annotation, NO_USER_REQUIRED, "NoUserRequired")) {
            return Stream.of(AuthorizationRule.NO_USER_REQUIRED);
        }
        if (matches(annotation, FORBIDS_USER, "ForbidsUser")) {
            return Stream.of(AuthorizationRule.forbidsUser(annotation.booleanValue("throwIfUnauthorized", true)));
        }
        if (matches(annotation, REQUIRES_USER, "RequiresUser")) {
            return Stream.of(AuthorizationRule.requiresUser(annotation.booleanValue("throwIfUnauthorized", true)));
        }
        if (matches(annotation, FORBIDS_ANY_ROLE, "ForbidsAnyRole")) {
            return annotation.values("value").stream()
                    .map(role -> AuthorizationRule.forbidsRole(
                            role, annotation.booleanValue("throwIfUnauthorized", true)));
        }
        return Stream.empty();
    }

    private static boolean authAnnotation(AnnotationDescriptor annotation) {
        return matches(annotation, REQUIRES_ANY_ROLE, "RequiresAnyRole")
               || matches(annotation, REQUIRES_USER, "RequiresUser")
               || matches(annotation, NO_USER_REQUIRED, "NoUserRequired")
               || matches(annotation, FORBIDS_USER, "ForbidsUser")
               || matches(annotation, FORBIDS_ANY_ROLE, "ForbidsAnyRole");
    }

    private static boolean matches(AnnotationDescriptor annotation, String qualifiedName, String simpleName) {
        return annotation.qualifiedName().equals(qualifiedName)
               || annotation.name().equals(simpleName)
               || Optional.of(annotation.qualifiedName())
                       .filter(name -> name.endsWith("." + simpleName))
                       .isPresent();
    }
}
