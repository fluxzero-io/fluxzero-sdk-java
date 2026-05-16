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

import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.tracking.handling.validation.constraints.CreditCardNumber;
import io.fluxzero.sdk.tracking.handling.validation.constraints.Length;
import io.fluxzero.sdk.tracking.handling.validation.constraints.Range;
import io.fluxzero.sdk.tracking.handling.validation.constraints.URL;
import io.fluxzero.sdk.tracking.handling.validation.constraints.UUID;
import io.fluxzero.sdk.tracking.handling.validation.constraints.UniqueElements;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultValidatorTest {

    private final DefaultValidator subject = DefaultValidator.createDefault();

    @Test
    void testObjectWithoutAnnotations() {
        subject.assertValid(new Object());
    }

    @Test
    void testValidObject() {
        Object object = ConstrainedObject.builder()
                .aString("foo").aNumber(5).aCustomString("bar")
                .member(new ConstrainedObjectMember(true))
                .build();
        subject.assertValid(object);
    }

    @Test
    void testInvalidObject() {
        Object object = ConstrainedObject.builder()
                .aString(null).aNumber(3).aCustomString(null)
                .member(new ConstrainedObjectMember(false))
                .aList("")
                .anotherList(new ConstrainedObjectMember(false))
                .build();
        ValidationException e = assertThrows(ValidationException.class, () -> subject.assertValid(object));

        assertEquals(6, e.getViolations().size());
        assertTrue(e.getViolations().stream().anyMatch(v -> v.equals("member.aBoolean must be true")));
        assertTrue(e.getViolations().contains("custom message"));
        assertTrue(e.getViolationSummaries().contains(
                new ValidationException.ViolationSummary("aCustomString", "custom message")));
        assertTrue(e.getViolationSummaries().contains(
                new ValidationException.ViolationSummary("member.aBoolean", "must be true")));
        assertEquals("""
                             aBoolean must be true
                             aList element must not be blank
                             aNumber must be greater than or equal to 5
                             aString must not be null
                             custom message
                             member aBoolean must be true""", e.getMessage());
    }

    @Test
    void testGroupConstraints() {
        Object object = ConstrainedObject.builder()
                .aString("foo").aNumber(5).aCustomString("bar")
                .member(new ConstrainedObjectMember(true))
                .aNumberWithGroupConstraint(2)
                .build();
        ValidationException e =
                assertThrows(ValidationException.class, () -> subject.assertValid(object, Group.class));
        assertEquals(1, e.getViolations().size());
        assertEquals("aNumberWithGroupConstraint must be greater than or equal to 5", e.getMessage());
    }

    @Test
    void validationExceptionFailedConstraintsRoundTripAsStrings() throws Exception {
        ValidationException exception = new ValidationException(
                "custom message", Set.of("custom message"),
                List.of(new ValidationException.ViolationSummary("aCustomString", "custom message")));

        ValidationException restored = JacksonSerializer.defaultObjectMapper.readValue(
                JacksonSerializer.defaultObjectMapper.writeValueAsString(exception), ValidationException.class);

        assertEquals(exception.getViolations(), restored.getViolations());
        assertEquals(exception.getViolationSummaries(), restored.getViolationSummaries());
    }

    @Test
    void inactiveMethodConstraintsAreNotInvoked() {
        subject.assertValid(new InactiveMethodConstraint(null), Group.class);
    }

    @Test
    void invalidFieldSuppressesDependentGetterFailure() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new DependentGetter(null)));

        assertEquals(1, e.getViolations().size());
        assertEquals("value must not be null", e.getMessage());
    }

    @Test
    void invalidFieldStillReportsSafeMethodConstraintViolations() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new InvalidFieldAndSafeMethodConstraint(null)));

        assertEquals(2, e.getViolations().size());
        assertTrue(e.getViolations().contains("methodConstraint must be true"));
        assertTrue(e.getViolations().contains("value must not be null"));
    }

    @Test
    void invalidFieldStillReportsSafeGetterPatternViolation() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(
                                                    new InvalidFieldAndPatternGetter(null, "123")));

        assertEquals(2, e.getViolations().size());
        assertTrue(e.getViolations().contains("code must match \"[A-Z]{4}[0-9]{7}\""));
        assertTrue(e.getViolations().contains("value must not be null"));
    }

    @Test
    void fieldViolationAnywhereIgnoresFailingMethodValidation() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(
                                                    new FieldViolationAndDangerousMethod(
                                                            new InvalidChild(null), new DangerousMethod())));

        assertEquals(1, e.getViolations().size());
        assertEquals("child value must not be null", e.getMessage());
    }

    @Test
    void fieldViolationAfterFailingMethodStillSuppressesMethodFailure() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(
                                                    new DangerousMethodBeforeInvalidSibling(
                                                            new DangerousGroupedMethod(),
                                                            new InvalidGroupedChild(null)), Group.class));

        assertEquals(1, e.getViolations().size());
        assertEquals("invalidSibling value must not be null", e.getMessage());
    }

    @Test
    void cascadedMethodFieldViolationSkipsMethodValidationOnReturnedBean() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(
                                                    new CascadedMethodWithInvalidReturnedBean()));

        assertEquals(1, e.getViolations().size());
        assertEquals("validatedChild value must not be null", e.getMessage());
    }

    @Test
    void getterMethodConstraintsAreValidated() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new GetterMethodConstraint()));

        assertEquals("anythingGoes must be true", e.getMessage());
    }

    @Test
    void nonGetterMethodConstraintsAreValidatedByDefault() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new ArbitraryMethodConstraint()));

        assertEquals("anythingGoes must be true", e.getMessage());
    }

    @Test
    void beanPropertyMethodNameCompatibilityIgnoresNonGetterMethodConstraints() {
        String property = DefaultValidator.BEAN_PROPERTY_METHOD_NAMES_ONLY_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "true");
            subject.assertValid(new ArbitraryMethodConstraint());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void supportsCommonBuiltInConstraints() {
        ValidationException e = assertThrows(ValidationException.class, () -> subject.assertValid(
                new BuiltIns("", List.of(), "abc", "not-an-email", "5")));

        assertTrue(e.getViolations().contains("items must not be empty"));
        assertTrue(e.getViolations().contains("code must match \"[A-Z]{2}\""));
        assertTrue(e.getViolations().contains("email must be a well-formed email address"));
        assertTrue(e.getViolations().contains("amount must be greater than 10"));
    }

    @Test
    void supportsAllJakartaBuiltInConstraints() {
        ValidationException e = assertThrows(ValidationException.class, () -> subject.assertValid(
                new AllBuiltInConstraints("present", null, false, true, 4, 6, "1.5", "2.5",
                                          0, -1, 0, 1, new BigDecimal("123.45"), "a", "",
                                          " ", "abc", "not-an-email",
                                          Instant.parse("2999-01-01T00:00:00Z"),
                                          Instant.parse("2999-01-01T00:00:00Z"),
                                          Instant.parse("2000-01-01T00:00:00Z"),
                                          Instant.parse("2000-01-01T00:00:00Z"))));

        assertEquals(22, e.getViolations().size());
        assertTrue(e.getViolations().contains("nullValue must be null"));
        assertTrue(e.getViolations().contains("notNullValue must not be null"));
        assertTrue(e.getViolations().contains("assertTrueValue must be true"));
        assertTrue(e.getViolations().contains("assertFalseValue must be false"));
        assertTrue(e.getViolations().contains("minValue must be greater than or equal to 5"));
        assertTrue(e.getViolations().contains("maxValue must be less than or equal to 5"));
        assertTrue(e.getViolations().contains("decimalMinValue must be greater than 1.5"));
        assertTrue(e.getViolations().contains("decimalMaxValue must be less than 2.5"));
        assertTrue(e.getViolations().contains("positiveValue must be greater than 0"));
        assertTrue(e.getViolations().contains("positiveOrZeroValue must be greater than or equal to 0"));
        assertTrue(e.getViolations().contains("negativeValue must be less than 0"));
        assertTrue(e.getViolations().contains("negativeOrZeroValue must be less than or equal to 0"));
        assertTrue(e.getViolations().contains("digitsValue numeric value out of bounds (<2 digits>.<1 digits> expected)"));
        assertTrue(e.getViolations().contains("sizeValue size must be between 2 and 3"));
        assertTrue(e.getViolations().contains("notEmptyValue must not be empty"));
        assertTrue(e.getViolations().contains("notBlankValue must not be blank"));
        assertTrue(e.getViolations().contains("patternValue must match \"[A-Z]+\""));
        assertTrue(e.getViolations().contains("emailValue must be a well-formed email address"));
        assertTrue(e.getViolations().contains("pastValue must be a past date"));
        assertTrue(e.getViolations().contains("pastOrPresentValue must be a date in the past or in the present"));
        assertTrue(e.getViolations().contains("futureValue must be a future date"));
        assertTrue(e.getViolations().contains("futureOrPresentValue must be a date in the present or in the future"));
    }

    @Test
    void nullableConstraintsIgnoreNullValues() {
        subject.assertValid(new NullableConstraints(null, null, null, null, null, null, null, null, null,
                                                    null, null, null, null, null, null, null, null, null));
        subject.assertValid(new NullableMethodConstraints());
    }

    @Test
    void supportsElStyleValidatedValueMessageToken() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new ElStyleMessage("actual")));

        assertEquals("invalid actual", e.getMessage());
    }

    @Test
    void supportsFluxzeroConstraints() {
        subject.assertValid(new FluxzeroConstraints(7, "fz", "https://fluxzero.io/docs",
                                                    "123e4567-e89b-12d3-a456-426614174000",
                                                    "4111 1111 1111 1111", List.of("a", "b")));

        ValidationException e = assertThrows(ValidationException.class, () -> subject.assertValid(
                new FluxzeroConstraints(3, "f", "ftp://example.com", "nope", "1234", List.of("a", "a"))));

        assertTrue(e.getViolations().contains("amount must be between 5 and 10"));
        assertTrue(e.getViolations().contains("name length must be between 2 and 4"));
        assertTrue(e.getViolations().contains("url must be a valid URL"));
        assertTrue(e.getViolations().contains("id must be a valid UUID"));
        assertTrue(e.getViolations().contains("cardNumber must be a valid credit card number"));
        assertTrue(e.getViolations().contains("tags must only contain unique elements"));
    }

    @Test
    void arraySizeConstraintValidatesArrayInsteadOfElements() {
        subject.assertValid(new SizedArray(new int[]{1, 2}));

        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new SizedArray(new int[]{1})));

        assertEquals("values size must be between 2 and 2", e.getMessage());
    }

    @Test
    void listSizeConstraintValidatesListInsteadOfElements() {
        subject.assertValid(new SizedList(List.of("a", "bbb")));

        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new SizedList(List.of("aa"))));

        assertEquals("values size must be between 2 and 2", e.getMessage());
    }

    @Test
    void listElementSizeConstraintValidatesElements() {
        subject.assertValid(new SizedListElements(List.of("aa", "bb")));

        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new SizedListElements(List.of("a", "bb"))));

        assertEquals("values element size must be between 2 and 2", e.getMessage());
    }

    @Test
    void supportsCustomConstraintValidators() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new CustomConstraintObject("bar")));

        assertEquals("code must start with fz", e.getMessage());
    }

    @Test
    void supportsCustomConstraintValidatorContextViolations() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new DateRange(10, 5)));

        assertEquals("end must be after start", e.getMessage());
    }

    @Test
    void groupSequenceStopsAtFirstFailingGroup() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new SequencedObject(null, false)));

        assertEquals(1, e.getViolations().size());
        assertEquals("first must not be null", e.getMessage());
    }

    @Test
    void groupConversionAppliesToCascadedValidation() {
        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValid(new Parent(new Child(null))));

        assertEquals("child value must not be null", e.getMessage());
    }

    @Test
    void validatesExecutableParameters() throws Exception {
        Method method = ParameterTarget.class.getDeclaredMethod("handle", String.class, Child.class);

        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValidParameters(
                                                    new ParameterTarget(), method, new Object[]{"", new Child(null)}));

        assertEquals(2, e.getViolations().size());
        assertTrue(e.getViolations().stream().anyMatch(v -> v.endsWith("arg0 must not be blank")));
        assertTrue(e.getViolations().stream().anyMatch(v -> v.endsWith("value must not be null")));
    }

    @Test
    void validatesCrossParameterConstraints() throws Exception {
        Method method = CrossParameterTarget.class.getDeclaredMethod("handle", String.class, String.class);

        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValidParameters(
                                                    new CrossParameterTarget(), method, new Object[]{"left", "right"}));

        assertEquals("handle <cross-parameter> arguments must match", e.getMessage());
        assertTrue(e.getViolations().contains("handle.<cross-parameter> arguments must match"));
    }

    @Test
    void validatesExecutableReturnValue() throws Exception {
        Method method = ReturnTarget.class.getDeclaredMethod("handle");

        ValidationException e = assertThrows(ValidationException.class,
                                            () -> subject.assertValidReturnValue(
                                                    new ReturnTarget(), method, new ReturnChild(null)));

        assertEquals("value must not be null", e.getMessage());
    }

    @Test
    void temporalConstraintsUseBuilderClock() {
        DefaultFluxzero.Builder builder = DefaultFluxzero.builder();
        builder.clock().setDelegate(Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC));

        builder.validator().assertValid(new FutureEvent(Instant.parse("2025-01-01T00:00:00Z")));
    }

    @Value
    @Builder
    private static class ConstrainedObject {
        @NotNull String aString;
        @Min(5) long aNumber;
        @NotNull(message = "custom message") String aCustomString;
        @Valid
        ConstrainedObjectMember member;
        @Singular("aList")
        List<@NotBlank String> aList;
        @Singular("anotherList")
        List<@Valid ConstrainedObjectMember> anotherList;
        @Min(value = 5, groups = Group.class) long aNumberWithGroupConstraint;
    }

    @Value
    private static class ConstrainedObjectMember {
        @AssertTrue boolean aBoolean;
    }

    private interface Group {
    }

    private record DependentGetter(@NotNull String value) {
        @AssertTrue
        boolean isDerived() {
            return value.length() > 0;
        }
    }

    private record InactiveMethodConstraint(@NotNull String value) {
        @AssertTrue
        boolean hasValue() {
            return value.length() > 0;
        }
    }

    private record InvalidFieldAndSafeMethodConstraint(@NotNull String value) {
        @AssertTrue
        boolean hasMethodConstraint() {
            return false;
        }
    }

    private record InvalidFieldAndPatternGetter(@NotNull String value, String code) {
        @Pattern(regexp = "[A-Z]{4}[0-9]{7}")
        String getCode() {
            return code;
        }
    }

    private record FieldViolationAndDangerousMethod(@Valid InvalidChild child, @Valid DangerousMethod method) {
    }

    private record InvalidChild(@NotNull String value) {
    }

    private record DangerousMethodBeforeInvalidSibling(@Valid DangerousGroupedMethod dangerous,
                                                       @Valid InvalidGroupedChild invalidSibling) {
    }

    private static class DangerousGroupedMethod {
        @AssertTrue(message = "method should be skipped", groups = Group.class)
        boolean hasMethodConstraint() {
            throw new IllegalStateException("method should be skipped");
        }
    }

    private record InvalidGroupedChild(@NotNull(groups = Group.class) String value) {
    }

    private static class DangerousMethod {
        @AssertTrue(message = "method should be skipped")
        boolean hasMethodConstraint() {
            throw new IllegalStateException("method should be skipped");
        }
    }

    private static class CascadedMethodWithInvalidReturnedBean {
        @Valid
        InvalidReturnedBean getValidatedChild() {
            return new InvalidReturnedBean(null);
        }
    }

    private record InvalidReturnedBean(@NotNull String value) {
        @AssertTrue(message = "method should be skipped")
        boolean hasMethodConstraint() {
            throw new IllegalStateException("method should be skipped");
        }
    }

    private static class ArbitraryMethodConstraint {
        @AssertTrue
        boolean anythingGoes() {
            return false;
        }
    }

    private static class GetterMethodConstraint {
        @AssertTrue
        boolean hasAnythingGoes() {
            return false;
        }
    }

    private record BuiltIns(@NotBlank String name,
                            @NotEmpty List<String> items,
                            @Pattern(regexp = "[A-Z]{2}") String code,
                            @Email String email,
                            @DecimalMin(value = "10", inclusive = false) String amount) {
    }

    private record AllBuiltInConstraints(@Null String nullValue,
                                         @NotNull String notNullValue,
                                         @AssertTrue Boolean assertTrueValue,
                                         @AssertFalse Boolean assertFalseValue,
                                         @Min(5) int minValue,
                                         @Max(5) int maxValue,
                                         @DecimalMin(value = "1.5", inclusive = false) String decimalMinValue,
                                         @DecimalMax(value = "2.5", inclusive = false) String decimalMaxValue,
                                         @Positive int positiveValue,
                                         @PositiveOrZero int positiveOrZeroValue,
                                         @Negative int negativeValue,
                                         @NegativeOrZero int negativeOrZeroValue,
                                         @Digits(integer = 2, fraction = 1) BigDecimal digitsValue,
                                         @Size(min = 2, max = 3) String sizeValue,
                                         @NotEmpty String notEmptyValue,
                                         @NotBlank String notBlankValue,
                                         @Pattern(regexp = "[A-Z]+") String patternValue,
                                         @Email String emailValue,
                                         @Past Instant pastValue,
                                         @PastOrPresent Instant pastOrPresentValue,
                                         @Future Instant futureValue,
                                         @FutureOrPresent Instant futureOrPresentValue) {
    }

    private record NullableConstraints(@AssertTrue Boolean trueValue,
                                       @AssertFalse Boolean falseValue,
                                       @Min(5) Integer minValue,
                                       @Max(10) Integer maxValue,
                                       @DecimalMin("10") String decimalValue,
                                       @Size(min = 1) List<String> sizedValue,
                                       List<@Max(10) Integer> containerValues,
                                       @Pattern(regexp = "[A-Z]{2}") String code,
                                       @Email String email,
                                       @Future Instant deadline,
                                       @Range(min = 5, max = 10) Integer range,
                                       @URL String url,
                                       @UUID String id,
                                       @CreditCardNumber String cardNumber,
                                       @UniqueElements List<String> tags,
                                       @Length String length,
                                       @Valid Child child,
                                       @StartsWith("fz") String custom) {
    }

    private static class NullableMethodConstraints {
        @AssertTrue
        Boolean isFlag() {
            return null;
        }

        @Valid
        Child getChild() {
            return null;
        }
    }

    private record ElStyleMessage(@Null(message = "invalid ${validatedValue}") String value) {
    }

    private record FluxzeroConstraints(@Range(min = 5, max = 10) int amount,
                                       @Length(min = 2, max = 4) String name,
                                       @URL(protocol = "https", host = "fluxzero.io") String url,
                                       @UUID String id,
                                       @CreditCardNumber(ignoreNonDigitCharacters = true) String cardNumber,
                                       @UniqueElements List<String> tags) {
    }

    private record SizedArray(@Size(min = 2, max = 2) int[] values) {
    }

    private record SizedList(@Size(min = 2, max = 2) List<String> values) {
    }

    private record SizedListElements(List<@Size(min = 2, max = 2) String> values) {
    }

    private record FutureEvent(@Future Instant deadline) {
    }

    private record CustomConstraintObject(@StartsWith("fz") String code) {
    }

    @ValidDateRange
    private record DateRange(int start, int end) {
    }

    @Target({FIELD, METHOD, PARAMETER, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @Constraint(validatedBy = StartsWithValidator.class)
    private @interface StartsWith {
        String message() default "must start with {value}";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};

        String value();
    }

    private static class StartsWithValidator implements ConstraintValidator<StartsWith, CharSequence> {
        private String prefix;

        @Override
        public void initialize(StartsWith annotation) {
            prefix = annotation.value();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            return value == null || value.toString().startsWith(prefix);
        }
    }

    @Target({TYPE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @Constraint(validatedBy = ValidDateRangeValidator.class)
    private @interface ValidDateRange {
        String message() default "invalid date range";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    private static class ValidDateRangeValidator implements ConstraintValidator<ValidDateRange, DateRange> {
        @Override
        public boolean isValid(DateRange value, ConstraintValidatorContext context) {
            if (value == null || value.end() >= value.start()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("must be after start")
                    .addPropertyNode("end")
                    .addConstraintViolation();
            return false;
        }
    }

    @GroupSequence({SequencedObject.class, SecondGroup.class})
    private record SequencedObject(@NotNull String first,
                                   @AssertTrue(groups = SecondGroup.class) boolean second) {
    }

    private interface SecondGroup {
    }

    private record Parent(@Valid @ConvertGroup(from = Default.class, to = ChildChecks.class) Child child) {
    }

    private record Child(@NotNull(groups = ChildChecks.class) String value) {
    }

    private record ReturnChild(@NotNull String value) {
    }

    private interface ChildChecks {
    }

    private static class ParameterTarget {
        void handle(@NotBlank String name, @Valid @ConvertGroup(from = Default.class, to = ChildChecks.class)
                    Child child) {
        }
    }

    private static class CrossParameterTarget {
        @MatchingArguments
        void handle(String left, String right) {
        }
    }

    private static class ReturnTarget {
        @Valid
        ReturnChild handle() {
            return new ReturnChild("value");
        }
    }

    @Target({METHOD, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @Constraint(validatedBy = MatchingArgumentsValidator.class)
    private @interface MatchingArguments {
        String message() default "arguments must match";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    private static class MatchingArgumentsValidator implements ConstraintValidator<MatchingArguments, Object[]> {
        @Override
        public boolean isValid(Object[] value, ConstraintValidatorContext context) {
            return value == null || value.length < 2 || Objects.equals(value[0], value[1]);
        }
    }
}
