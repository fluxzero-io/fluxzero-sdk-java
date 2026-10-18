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

import io.fluxzero.common.Decimal;
import io.fluxzero.sdk.tracking.handling.validation.constraints.Range;
import io.fluxzero.sdk.tracking.handling.validation.jakarta.FluxzeroValidationConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DecimalValidationTest {
    record Bounds(@NotNull @Min(1) @Max(10) @DecimalMin(value="1.1", inclusive=false)
                  @DecimalMax(value="9.9", inclusive=false) @Positive @PositiveOrZero
                  @Digits(integer=1, fraction=2) @Range(min=1,max=10) Decimal value) { }
    record NegativeAmount(@Negative @NegativeOrZero Decimal value) { }
    record Small(@Digits(integer=2, fraction=2) BigDecimal legacy,
                 @Digits(integer=2, fraction=2) Decimal decimal) { }
    record Exact(@DecimalMin(value="9007199254740993.01", inclusive=false) Decimal value) { }
    interface Checked { }
    record LegacyGrouped(@Positive(groups=Checked.class) BigDecimal value) { }
    record Grouped(@Positive(groups=Checked.class) Decimal value) { }
    record Elements(List<@Positive Decimal> values, @Valid Bounds nested) { }
    static class Executable {
        public @Positive Decimal amount(@Positive Decimal input) { return input; }
    }

    @Test
    void validatesAllApplicableNumericConstraintsWithoutPrecisionLoss() {
        try (var factory = new FluxzeroValidationConfiguration().buildValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new Bounds(new Decimal("5.25"))).isEmpty());
            assertEquals(Set.of("Min", "DecimalMin", "Positive", "PositiveOrZero", "Digits", "Range"),
                         validator.validate(new Bounds(new Decimal("-10.123"))).stream()
                                 .map(v -> v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                                 .collect(Collectors.toSet()));
            assertEquals(4, validator.validate(new Bounds(new Decimal("10.123"))).size());
            assertEquals(1, validator.validate(new Bounds(null)).size());
            assertTrue(validator.validate(new NegativeAmount(new Decimal("-0.1"))).isEmpty());
            assertEquals(2, validator.validate(new NegativeAmount(Decimal.ONE)).size());
            assertEquals(1, validator.validate(new NegativeAmount(Decimal.ZERO)).size());
            assertTrue(validator.validate(new Exact(new Decimal("9007199254740993.010000000000000001"))).isEmpty());
            assertEquals(1, validator.validate(new Exact(new Decimal("9007199254740993.01"))).size());
        }
    }

    @Test
    void digitsCountsNegativeScaleAndRetainsExistingTrailingZeroSemantics() {
        try (var factory = new FluxzeroValidationConfiguration().buildValidatorFactory()) {
            var validator = factory.getValidator();
            for (String text : List.of("1000", "-1000", "1E+3", "1E+2147483647")) {
                assertEquals(2, validator.validate(new Small(new BigDecimal(text), new Decimal(text))).size(), text);
            }
            var extreme = new BigDecimal(java.math.BigInteger.TEN, Integer.MIN_VALUE);
            assertEquals(2, validator.validate(new Small(extreme, new Decimal(extreme))).size());
            for (String text : List.of("99.99", "1.000", "0.01", "0")) {
                assertTrue(validator.validate(new Small(new BigDecimal(text), new Decimal(text))).isEmpty(), text);
            }
            assertEquals(2, validator.validate(new Small(new BigDecimal("0.001"), new Decimal("0.001"))).size());
        }
    }

    @Test
    void groupsContainerPathsCascadesAndExecutablesKeepWorking() throws Exception {
        try (var factory = new FluxzeroValidationConfiguration().buildValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new Grouped(Decimal.ZERO)).isEmpty());
            assertEquals(1, validator.validate(new Grouped(Decimal.ZERO), Checked.class).size());
            assertEquals(1, validator.validate(new LegacyGrouped(BigDecimal.ZERO), Checked.class).size());
            var violations = validator.validate(new Elements(List.of(Decimal.ZERO), new Bounds(null)));
            assertEquals(2, violations.size());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("nested.value")));
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("values[0]")));
            var method = Executable.class.getMethod("amount", Decimal.class);
            assertEquals(1, validator.forExecutables().validateParameters(new Executable(), method,
                                                                         new Object[]{Decimal.ZERO}).size());
            assertEquals(1, validator.forExecutables().validateReturnValue(new Executable(), method, Decimal.ZERO).size());
        }
    }
}
