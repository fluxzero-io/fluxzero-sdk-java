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

package io.fluxzero.sdk.tracking.handling.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaValidationTckSubsetTest {
    private final DefaultValidator subject = DefaultValidator.createDefault();

    @Test
    void composedConstraintsInheritGroupsAndReportAsSingleViolation() {
        assertTrue(subject.checkValidity(new ComposedBean("x")).isEmpty());

        ValidationException exception = subject.checkValidity(new ComposedBean("x"), TckGroup.class).orElseThrow();

        assertEquals(Set.of("code invalid composed code"), exception.getViolations());
    }

    @Test
    void mapKeysValuesAndNestedContainerElementsAreValidated() {
        Map<String, List<CatalogLine>> lines = Map.of("", List.of(new CatalogLine(null)));

        ValidationException exception = subject.checkValidity(new Catalog(lines)).orElseThrow();

        assertEquals(2, exception.getViolations().size());
        assertTrue(exception.getViolations().stream().anyMatch(violation -> violation.contains("must not be blank")));
        assertTrue(exception.getViolations().stream().anyMatch(violation -> violation.contains("sku must not be null")));
    }

    @Test
    void optionalTypeArgumentConstraintsAreValidatedOnPropertiesParametersAndReturnValues() throws Exception {
        assertEquals(Set.of("nickname must not be blank"),
                     violations(new OptionalProperty(Optional.of(""))));

        Method method = OptionalService.class.getDeclaredMethod("rename", Optional.class);
        assertEquals(Set.of("rename.arg0 must not be blank"),
                     subject.checkParameterValidity(new OptionalService(), method, new Object[]{Optional.of("")})
                             .orElseThrow().getViolations());
        assertEquals(Set.of("rename.<return value> must not be blank"),
                     subject.checkReturnValueValidity(new OptionalService(), method, Optional.of(""))
                             .orElseThrow().getViolations());
    }

    @Test
    void sameInvalidInstanceInCollectionIsReportedForEveryPath() {
        CatalogLine line = new CatalogLine(null);

        ValidationException exception = subject.checkValidity(new Lines(List.of(line, line))).orElseThrow();

        assertEquals(2, exception.getViolations().size());
        assertTrue(exception.getViolations().contains("lines[0].sku must not be null"));
        assertTrue(exception.getViolations().contains("lines[1].sku must not be null"));
    }

    @Test
    void groupInheritanceActivatesParentGroupConstraints() {
        ValidationException exception = subject.checkValidity(new GroupedPart(null), ExtendedChecks.class)
                .orElseThrow();

        assertEquals(Set.of("partNumber must not be null"), exception.getViolations());
    }

    private static Set<String> violations(Object value) {
        return DefaultValidator.createDefault().checkValidity(value).orElseThrow().getViolations();
    }

    private interface TckGroup {
    }

    private interface BasicChecks {
    }

    private interface ExtendedChecks extends BasicChecks {
    }

    private record ComposedBean(@ComposedCode(groups = TckGroup.class) String code) {
    }

    private record Catalog(Map<@NotBlank String, List<@Valid CatalogLine>> linesByCode) {
    }

    private record CatalogLine(@NotNull String sku) {
    }

    private record OptionalProperty(Optional<@NotBlank String> nickname) {
    }

    private record Lines(@Valid List<CatalogLine> lines) {
    }

    private record GroupedPart(@NotNull(groups = BasicChecks.class) String partNumber) {
    }

    private static class OptionalService {
        Optional<@NotBlank String> rename(Optional<@NotBlank String> nickname) {
            return nickname;
        }
    }

    @Target({FIELD, METHOD, PARAMETER, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @Constraint(validatedBy = {})
    @NotNull
    @Size(min = 2)
    @ReportAsSingleViolation
    private @interface ComposedCode {
        String message() default "invalid composed code";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }
}
