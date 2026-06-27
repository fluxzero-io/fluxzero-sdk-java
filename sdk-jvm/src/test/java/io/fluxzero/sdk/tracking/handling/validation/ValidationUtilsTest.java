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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthenticatedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.isValid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void generatedOnlyModeDoesNotUseReflectionFallbackForValidateWith() {
        GeneratedOnlyMetadataMode.run(() -> assertTrue(isValid(new UnregisteredGeneratedOnlyValidateWith(""))));
    }

    @Test
    void generatedOnlyModeUsesRegisteredValidateWithMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyValidateWith.class).registry());
            GeneratedOnlyMetadataMode.run(() -> assertFalse(isValid(new RegisteredGeneratedOnlyValidateWith(""))));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForRequiresUser() {
        GeneratedOnlyMetadataMode.run(() -> assertDoesNotThrow(
                () -> ValidationUtils.assertAuthorized(UnregisteredGeneratedOnlyRequiresUser.class, null)));
    }

    @Test
    void generatedOnlyModeUsesRegisteredRequiresUserMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyRequiresUser.class).registry());
            GeneratedOnlyMetadataMode.run(() -> assertThrows(UnauthenticatedException.class,
                    () -> ValidationUtils.assertAuthorized(RegisteredGeneratedOnlyRequiresUser.class, null)));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
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

    @Test
    void convenienceMethodsUseCurrentFluxzeroValidator() {
        Validator validator = new Validator() {
            @Override
            public <T> Optional<ValidationException> checkValidity(T object, Class<?>... groups) {
                return Optional.of(new ValidationException("configured validator", Set.of("configured validator")));
            }
        };
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .replaceValidator(current -> validator)
                .build(LocalClient.newInstance(null));

        try {
            fluxzero.execute(fc -> {
                ValidationException exception = assertThrows(
                        ValidationException.class, () -> ValidationUtils.assertValid(new Object()));
                assertEquals("configured validator", exception.getMessage());
            });
        } finally {
            fluxzero.close(true);
        }
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

    @ValidateWith(GeneratedOnlyGroup.class)
    private record UnregisteredGeneratedOnlyValidateWith(
            @NotBlank(groups = GeneratedOnlyGroup.class) String value) {
    }

    @ValidateWith(GeneratedOnlyGroup.class)
    private record RegisteredGeneratedOnlyValidateWith(
            @NotBlank(groups = GeneratedOnlyGroup.class) String value) {
    }

    @RequiresUser
    private static class UnregisteredGeneratedOnlyRequiresUser {
    }

    @RequiresUser
    private static class RegisteredGeneratedOnlyRequiresUser {
    }

    private interface GeneratedOnlyGroup {
    }

}
