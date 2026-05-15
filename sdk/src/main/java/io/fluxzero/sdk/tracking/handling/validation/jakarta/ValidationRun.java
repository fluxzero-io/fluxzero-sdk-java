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
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintViolation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class ValidationRun {
    private final Object rootBean;
    private final Class<?> rootBeanClass;
    private final ValidationSettings settings;
    private final boolean fieldOnly;
    private final boolean skipMethodsAfterFieldViolations;
    private final boolean beanPropertyMethodNamesOnly;
    private final List<DefaultValidationMetadata.DefaultConstraintViolation<?>> violations = new ArrayList<>();
    private final Set<Object> validationStack = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ProcessedConstraint> processedConstraints = new HashSet<>();
    private boolean deduplicateConstraints;
    private boolean skippedMethodsAfterFieldViolations;

    ValidationRun(Object rootBean, boolean deduplicateConstraints, ClockProvider clockProvider) {
        this(rootBean, deduplicateConstraints, ValidationSettings.createDefault().withClockProvider(clockProvider));
    }

    ValidationRun(Object rootBean, boolean deduplicateConstraints, ClockProvider clockProvider, boolean fieldOnly) {
        this(rootBean, deduplicateConstraints, ValidationSettings.createDefault().withClockProvider(clockProvider),
             fieldOnly, true);
    }

    ValidationRun(Object rootBean, boolean deduplicateConstraints, ClockProvider clockProvider, boolean fieldOnly,
                  boolean skipMethodsAfterFieldViolations) {
        this(rootBean, deduplicateConstraints, ValidationSettings.createDefault().withClockProvider(clockProvider),
             fieldOnly, skipMethodsAfterFieldViolations);
    }

    ValidationRun(Object rootBean, boolean deduplicateConstraints, ValidationSettings settings) {
        this(rootBean, deduplicateConstraints, settings, false, true);
    }

    ValidationRun(Object rootBean, boolean deduplicateConstraints, ValidationSettings settings, boolean fieldOnly,
                  boolean skipMethodsAfterFieldViolations) {
        this(rootBean, rootBean == null ? Object.class : rootBean.getClass(), deduplicateConstraints, settings,
             fieldOnly, skipMethodsAfterFieldViolations);
    }

    ValidationRun(Object rootBean, Class<?> rootBeanClass, boolean deduplicateConstraints, ValidationSettings settings,
                  boolean fieldOnly, boolean skipMethodsAfterFieldViolations) {
        this.rootBean = rootBean;
        this.rootBeanClass = rootBeanClass;
        this.settings = settings;
        this.fieldOnly = fieldOnly;
        this.skipMethodsAfterFieldViolations = skipMethodsAfterFieldViolations;
        this.beanPropertyMethodNamesOnly = settings.beanPropertyMethodNamesOnly();
        this.deduplicateConstraints = deduplicateConstraints;
    }

    boolean hasViolations() {
        return !violations.isEmpty();
    }

    Collection<? extends ConstraintViolation<?>> violations() {
        return violations;
    }

    boolean fieldOnly() {
        return fieldOnly;
    }

    boolean skipMethodsAfterFieldViolations() {
        return skipMethodsAfterFieldViolations;
    }

    boolean beanPropertyMethodNamesOnly() {
        return beanPropertyMethodNamesOnly;
    }

    boolean skippedMethodsAfterFieldViolations() {
        return skippedMethodsAfterFieldViolations;
    }

    void markSkippedMethodsAfterFieldViolations() {
        skippedMethodsAfterFieldViolations = true;
    }

    ValueExtractorRegistry valueExtractors() {
        return settings.valueExtractors();
    }

    List<String> parameterNames(Executable executable) {
        return switch (executable) {
            case Constructor<?> constructor -> settings.parameterNameProvider().getParameterNames(constructor);
            case Method method -> settings.parameterNameProvider().getParameterNames(method);
            default -> List.of();
        };
    }

    void validateBean(Object object, Class<?>[] requestedGroups, ValidationPath path) {
        if (object == null || ReflectionUtils.isLeafValue(object)) {
            return;
        }
        if (!validationStack.add(object)) {
            return;
        }
        try {
            BeanValidationMetadata metadata = BeanValidationMetadata.of(object.getClass());
            for (Class<?> requestedGroup : requestedGroups) {
                validateGroup(metadata, object, requestedGroup, path);
            }
        } finally {
            validationStack.remove(object);
        }
    }

    void validateBean(Object object, Class<?> requestedGroup, ValidationPath path) {
        if (object == null || ReflectionUtils.isLeafValue(object)) {
            return;
        }
        if (!validationStack.add(object)) {
            return;
        }
        try {
            validateGroup(BeanValidationMetadata.of(object.getClass()), object, requestedGroup, path);
        } finally {
            validationStack.remove(object);
        }
    }

    private void validateGroup(BeanValidationMetadata metadata, Object object, Class<?> requestedGroup, ValidationPath path) {
        Class<?>[] sequence = ValidationAnnotationUtils.validationGroups(requestedGroup, metadata.type());
        if (sequence.length == 1 && sequence[0] == requestedGroup) {
            metadata.validate(this, object, ValidationAnnotationUtils.effectiveGroup(requestedGroup, metadata.type()),
                              path);
            return;
        }
        boolean previousDeduplicate = deduplicateConstraints;
        deduplicateConstraints = true;
        for (Class<?> group : sequence) {
            int before = violations.size();
            metadata.validate(this, object, ValidationAnnotationUtils.effectiveGroup(group, metadata.type()), path);
            if (violations.size() > before) {
                deduplicateConstraints = previousDeduplicate;
                return;
            }
        }
        deduplicateConstraints = previousDeduplicate;
    }

    void validateValue(Object owner, ConstraintMeta meta, Object value, Class<?> group, ValidationPath path) {
        if (!meta.appliesToGroup(group)) {
            return;
        }
        if (deduplicateConstraints) {
            ProcessedConstraint processed = new ProcessedConstraint(new IdentityKey(owner), path, meta);
            if (!processedConstraints.add(processed)) {
                return;
            }
        }
        if (!meta.composingConstraints().isEmpty()) {
            int before = violations.size();
            for (ConstraintMeta composingConstraint : meta.composingConstraints()) {
                validateValue(owner, composingConstraint, value, group, path);
            }
            if (violations.size() > before && meta.reportAsSingleViolation()) {
                violations.subList(before, violations.size()).clear();
                addViolation(meta, value, path, meta.messageTemplate());
                return;
            }
        }
        Boolean builtIn = ConstraintValidators.validateBuiltIn(meta, value, settings);
        if (builtIn != null) {
            if (!builtIn) {
                addViolation(meta, value, path, meta.messageTemplate());
            }
            return;
        }
        DefaultValidationMetadata.DefaultConstraintValidatorContext context = new DefaultValidationMetadata.DefaultConstraintValidatorContext(
                meta, path, settings.clockProvider());
        if (!ConstraintValidators.isValid(meta, value, context, settings)) {
            if (context.defaultConstraintViolationEnabled() || context.violationTemplates().isEmpty()) {
                addViolation(meta, value, path, meta.messageTemplate());
            }
            for (DefaultValidationMetadata.DefaultConstraintValidatorContext.ContextViolation contextViolation
                    : context.violationTemplates()) {
                addViolation(meta, value, contextViolation.path(), contextViolation.template());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addViolation(ConstraintMeta meta, Object invalidValue, ValidationPath path, String template) {
        DefaultValidationMetadata.DefaultConstraintDescriptor<?> descriptor = new DefaultValidationMetadata.DefaultConstraintDescriptor<>(meta);
        violations.add(new DefaultValidationMetadata.DefaultConstraintViolation<>(
                ValidationMessages.interpolate(meta, template, invalidValue, descriptor,
                                               settings.messageInterpolator()), template, rootBean,
                (Class<Object>) rootBeanClass, invalidValue, path, meta.customMessage(),
                descriptor));
    }

    private record ProcessedConstraint(IdentityKey owner, ValidationPath path, ConstraintMeta meta) {
    }

    private static final class IdentityKey {
        private final Object value;

        private IdentityKey(Object value) {
            this.value = value;
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && value == key.value;
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }
}
