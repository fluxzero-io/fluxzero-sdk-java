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

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that a character sequence is a credit card number according to the Luhn checksum.
 */
@Documented
@Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Repeatable(CreditCardNumber.List.class)
@Constraint(validatedBy = CreditCardNumberValidator.class)
public @interface CreditCardNumber {
    /**
     * @return violation message template
     */
    String message() default "must be a valid credit card number";

    /**
     * @return validation groups for which this constraint applies
     */
    Class<?>[] groups() default {};

    /**
     * @return payload metadata associated with this constraint
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * @return whether spaces, dashes, and other non-digit characters are ignored before validation
     */
    boolean ignoreNonDigitCharacters() default false;

    /**
     * Container annotation for repeatable {@link CreditCardNumber} constraints.
     */
    @Documented
    @Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface List {
        /**
         * @return repeated credit card number constraints
         */
        CreditCardNumber[] value();
    }
}

final class CreditCardNumberValidator implements ConstraintValidator<CreditCardNumber, Object> {
    private boolean ignoreNonDigitCharacters;

    /** {@inheritDoc} */
    @Override
    public void initialize(CreditCardNumber annotation) {
        ignoreNonDigitCharacters = annotation.ignoreNonDigitCharacters();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof CharSequence sequence)) {
            return false;
        }
        int sum = 0;
        int digits = 0;
        boolean doubleDigit = false;
        for (int i = sequence.length() - 1; i >= 0; i--) {
            char c = sequence.charAt(i);
            if (!Character.isDigit(c)) {
                if (ignoreNonDigitCharacters) {
                    continue;
                }
                return false;
            }
            int digit = Character.digit(c, 10);
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            digits++;
            doubleDigit = !doubleDigit;
        }
        return digits > 0 && sum % 10 == 0;
    }
}
