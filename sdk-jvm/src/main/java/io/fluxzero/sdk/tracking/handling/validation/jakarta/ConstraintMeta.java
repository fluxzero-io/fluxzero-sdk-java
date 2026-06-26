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
 *
 */

package io.fluxzero.sdk.tracking.handling.validation.jakarta;

import io.fluxzero.common.reflection.ReflectionUtils;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ConstraintMeta(Annotation annotation, String messageTemplate, boolean customMessage,
                      Set<Class<?>> groups,
                      Set<Class<? extends Payload>> payload,
                      List<Class<? extends ConstraintValidator<Annotation, Object>>> validatorClasses,
                      List<ConstraintMeta> composingConstraints, boolean reportAsSingleViolation,
                      Map<String, Object> attributes, ConstraintTarget validationAppliesTo) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    ConstraintMeta(Annotation annotation) {
        this(annotation, ValidationAnnotationUtils.groups(annotation), ValidationAnnotationUtils.payload(annotation),
             ValidationAnnotationUtils.validationAppliesTo(annotation).orElse(ConstraintTarget.IMPLICIT));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConstraintMeta(Annotation annotation, Set<Class<?>> groups,
                           Set<Class<? extends Payload>> payload, ConstraintTarget validationAppliesTo) {
        this(annotation, ValidationAnnotationUtils.messageTemplate(annotation),
             ValidationAnnotationUtils.customMessage(annotation), groups, payload,
             (List) Arrays.asList(ReflectionUtils.getAnnotation(annotation.annotationType(), Constraint.class)
                                          .orElseThrow().validatedBy()),
             composingConstraints(annotation, groups, payload, validationAppliesTo),
             ReflectionUtils.getAnnotation(annotation.annotationType(), ReportAsSingleViolation.class).isPresent(),
             attributes(annotation), validationAppliesTo);
    }

    boolean crossParameter() {
        if (validationAppliesTo == ConstraintTarget.PARAMETERS) {
            return true;
        }
        for (Class<? extends ConstraintValidator<Annotation, Object>> validatorClass : validatorClasses) {
            if (ConstraintValidators.descriptor(ConstraintValidators.validatorClass(validatorClass))
                    .crossParameter()) {
                return true;
            }
        }
        return false;
    }

    boolean appliesToGroup(Class<?> requestedGroup) {
        return ValidationAnnotationUtils.appliesToGroup(this, requestedGroup);
    }

    private static List<ConstraintMeta> composingConstraints(Annotation annotation, Set<Class<?>> groups,
                                                            Set<Class<? extends Payload>> payload,
                                                            ConstraintTarget validationAppliesTo) {
        return ValidationAnnotationUtils.constraintAnnotations(ReflectionUtils.getAnnotations(annotation.annotationType()))
                .stream()
                .filter(a -> !a.annotationType().equals(annotation.annotationType()))
                .map(a -> new ConstraintMeta(a, groups, payload, validationAppliesTo))
                .toList();
    }

    private static Map<String, Object> attributes(Annotation annotation) {
        return ReflectionUtils.getAnnotationAttributes(annotation);
    }
}
