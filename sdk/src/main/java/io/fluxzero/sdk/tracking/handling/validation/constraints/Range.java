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

package io.fluxzero.sdk.tracking.handling.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.math.BigDecimal;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that a numeric value is between {@link #min()} and {@link #max()}, inclusive.
 */
@Documented
@Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Repeatable(Range.List.class)
@Constraint(validatedBy = RangeValidator.class)
public @interface Range {
    /**
     * @return violation message template
     */
    String message() default "must be between {min} and {max}";

    /**
     * @return validation groups for which this constraint applies
     */
    Class<?>[] groups() default {};

    /**
     * @return payload metadata associated with this constraint
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * @return inclusive lower bound
     */
    long min() default 0;

    /**
     * @return inclusive upper bound
     */
    long max() default Long.MAX_VALUE;

    /**
     * Container annotation for repeatable {@link Range} constraints.
     */
    @Documented
    @Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface List {
        /**
         * @return repeated range constraints
         */
        Range[] value();
    }
}

final class RangeValidator implements ConstraintValidator<Range, Object> {
    private BigDecimal min;
    private BigDecimal max;

    /** {@inheritDoc} */
    @Override
    public void initialize(Range annotation) {
        min = BigDecimal.valueOf(annotation.min());
        max = BigDecimal.valueOf(annotation.max());
    }

    /** {@inheritDoc} */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return ConstraintSupport.asBigDecimal(value)
                .map(number -> number.compareTo(min) >= 0 && number.compareTo(max) <= 0)
                .orElse(false);
    }
}
