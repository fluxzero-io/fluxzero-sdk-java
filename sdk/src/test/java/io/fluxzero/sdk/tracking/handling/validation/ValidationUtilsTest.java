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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.isValid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationUtilsTest {

    @Test
    void testValidation() {
        assertTrue(isValid(new Foo("bar")));
        assertFalse(isValid(new Foo("")));
        assertTrue(ValidationUtils.checkValidity(new Foo("")).isPresent());
        assertThrows(ValidationException.class, () -> ValidationUtils.assertValid(new Foo("")));
    }

    @Test
    void testValidateWith() {
        assertTrue(isValid(ValidateWithExample.builder().foo(new Foo("bar")).stringInGroup1("bla").build()));
        assertFalse(isValid(ValidateWithExample.builder().foo(new Foo("bar")).stringInGroup1("").build()));
        assertTrue(isValid(ValidateWithExample.builder().foo(new Foo("")).stringInGroup1("bla").build()));
    }

    @Test
    void assertValidReturnValueUsesDefaultValidator() throws Exception {
        Method method = ReturnValueExample.class.getDeclaredMethod("handle");

        ValidationUtils.assertValidReturnValue(new ReturnValueExample(), method, "ok");

        assertThrows(ValidationException.class,
                     () -> ValidationUtils.assertValidReturnValue(new ReturnValueExample(), method, ""));
    }

    @Test
    void getsRawConstraintViolationsWithPropertyPaths() {
        Set<ConstraintViolation<RawViolationExample>> violations =
                ValidationUtils.getConstraintViolations(new RawViolationExample("x", "y", "123"));

        assertEquals(Map.of(
                             "first", "custom message",
                             "second", "custom message",
                             "code", "must match \"[A-Z]{4}[0-9]{7}\""),
                     violations.stream().collect(Collectors.toMap(
                             v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)));
    }

    @Value
    public static class Foo {
        @NotBlank String bar;
    }

    private record RawViolationExample(@Pattern(regexp = "[0-9]+", message = "custom message") String first,
                                       @Pattern(regexp = "[0-9]+", message = "custom message") String second,
                                       String code) {
        @Pattern(regexp = "[A-Z]{4}[0-9]{7}")
        String getCode() {
            return code;
        }
    }

    private static class ReturnValueExample {
        @NotBlank
        String handle() {
            return "ok";
        }
    }

    @Value
    @Builder
    @ValidateWith(Group1.class)
    public static class ValidateWithExample {
        @Valid Foo foo;
        @NotBlank(groups = Group1.class) String stringInGroup1;
    }

    public interface Group1 {}

}
