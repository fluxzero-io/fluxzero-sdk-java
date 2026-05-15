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
import java.util.HashSet;
import java.util.Set;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that an array or iterable value does not contain duplicate elements.
 */
@Documented
@Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Repeatable(UniqueElements.List.class)
@Constraint(validatedBy = UniqueElementsValidator.class)
public @interface UniqueElements {
    /**
     * @return violation message template
     */
    String message() default "must only contain unique elements";

    /**
     * @return validation groups for which this constraint applies
     */
    Class<?>[] groups() default {};

    /**
     * @return payload metadata associated with this constraint
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * Container annotation for repeatable {@link UniqueElements} constraints.
     */
    @Documented
    @Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface List {
        /**
         * @return repeated unique element constraints
         */
        UniqueElements[] value();
    }
}

final class UniqueElementsValidator implements ConstraintValidator<UniqueElements, Object> {
    /** {@inheritDoc} */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Iterable<?> iterable = ConstraintSupport.iterable(value);
        if (iterable == null) {
            return false;
        }
        Set<Object> seen = new HashSet<>();
        for (Object element : iterable) {
            if (!seen.add(element)) {
                return false;
            }
        }
        return true;
    }
}
