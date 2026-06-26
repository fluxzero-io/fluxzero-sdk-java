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
 * Validates that a character sequence is a canonical UUID string.
 */
@Documented
@Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Repeatable(UUID.List.class)
@Constraint(validatedBy = UUIDValidator.class)
public @interface UUID {
    /**
     * @return violation message template
     */
    String message() default "must be a valid UUID";

    /**
     * @return validation groups for which this constraint applies
     */
    Class<?>[] groups() default {};

    /**
     * @return payload metadata associated with this constraint
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * @return required UUID version, or {@code -1} to allow any version
     */
    int version() default -1;

    /**
     * Container annotation for repeatable {@link UUID} constraints.
     */
    @Documented
    @Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface List {
        /**
         * @return repeated UUID constraints
         */
        UUID[] value();
    }
}

final class UUIDValidator implements ConstraintValidator<UUID, Object> {
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private int version;

    /** {@inheritDoc} */
    @Override
    public void initialize(UUID annotation) {
        version = annotation.version();
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
        String text = sequence.toString();
        return UUID_PATTERN.matcher(text).matches()
               && (version < 0 || Character.digit(text.charAt(14), 16) == version);
    }
}
