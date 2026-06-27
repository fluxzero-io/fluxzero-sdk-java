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
        if (annotation.isOrHas("NoUserRequired", NO_USER_REQUIRED)) {
            return Stream.of(AuthorizationRule.NO_USER_REQUIRED);
        }
        Optional<AnnotationDescriptor> forbidsUser = annotation.find("ForbidsUser", FORBIDS_USER);
        if (forbidsUser.isPresent()) {
            return Stream.of(AuthorizationRule.forbidsUser(
                    booleanValue(annotation, forbidsUser.get(), "throwIfUnauthorized", true)));
        }
        Optional<AnnotationDescriptor> requiresUser = annotation.find("RequiresUser", REQUIRES_USER);
        if (requiresUser.isPresent()) {
            return Stream.of(AuthorizationRule.requiresUser(
                    booleanValue(annotation, requiresUser.get(), "throwIfUnauthorized", true)));
        }
        Optional<AnnotationDescriptor> requiresAnyRole = annotation.find("RequiresAnyRole", REQUIRES_ANY_ROLE);
        if (requiresAnyRole.isPresent()) {
            AnnotationDescriptor rule = requiresAnyRole.get();
            List<String> values = rule.values("value").isEmpty() ? annotation.values("value") : rule.values("value");
            return values.stream()
                    .map(role -> AuthorizationRule.requiresRole(
                            role, booleanValue(annotation, rule, "throwIfUnauthorized", true)));
        }
        Optional<AnnotationDescriptor> forbidsAnyRole = annotation.find("ForbidsAnyRole", FORBIDS_ANY_ROLE);
        if (forbidsAnyRole.isPresent()) {
            AnnotationDescriptor rule = forbidsAnyRole.get();
            List<String> values = rule.values("value").isEmpty() ? annotation.values("value") : rule.values("value");
            return values.stream()
                    .map(role -> AuthorizationRule.forbidsRole(
                            role, booleanValue(annotation, rule, "throwIfUnauthorized", true)));
        }
        return Stream.empty();
    }

    private static boolean authAnnotation(AnnotationDescriptor annotation) {
        return annotation.isOrHas("RequiresAnyRole", REQUIRES_ANY_ROLE)
               || annotation.isOrHas("RequiresUser", REQUIRES_USER)
               || annotation.isOrHas("NoUserRequired", NO_USER_REQUIRED)
               || annotation.isOrHas("ForbidsUser", FORBIDS_USER)
               || annotation.isOrHas("ForbidsAnyRole", FORBIDS_ANY_ROLE);
    }

    private static boolean booleanValue(
            AnnotationDescriptor annotation, AnnotationDescriptor metaAnnotation, String attribute, boolean defaultValue) {
        return annotation.firstValue(attribute)
                .map(Boolean::parseBoolean)
                .orElseGet(() -> metaAnnotation.booleanValue(attribute, defaultValue));
    }
}
