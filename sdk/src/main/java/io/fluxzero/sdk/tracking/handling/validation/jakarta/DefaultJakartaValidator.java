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
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import io.fluxzero.sdk.tracking.handling.validation.Validator;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ClockProvider;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static java.lang.String.CASE_INSENSITIVE_ORDER;
import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;

/**
 * Default reflection based Jakarta Validation implementation used by the SDK.
 * <p>
 * This class lives in the Jakarta implementation package; the public SDK entry point remains
 * {@link io.fluxzero.sdk.tracking.handling.validation.DefaultValidator}.
 */
public class DefaultJakartaValidator implements Validator {
    private final ValidationSettings settings;

    /**
     * Creates a validator that uses the current Fluxzero clock for temporal constraints.
     */
    protected DefaultJakartaValidator() {
        this(ValidationSettings.createDefault());
    }

    /**
     * Creates a validator that uses the supplied clock for temporal constraints such as {@code @Past} and
     * {@code @Future}.
     *
     * @param clock the clock to use while validating temporal constraints
     */
    protected DefaultJakartaValidator(Clock clock) {
        this(ValidationSettings.createDefault().withClock(clock));
    }

    /**
     * Creates a validator that obtains its clock from the supplied Jakarta {@link ClockProvider}.
     *
     * @param clockProvider provider used for temporal constraints
     */
    protected DefaultJakartaValidator(ClockProvider clockProvider) {
        this(ValidationSettings.createDefault().withClockProvider(clockProvider));
    }

    DefaultJakartaValidator(ValidationSettings settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    /**
     * Validates a bean instance using Jakarta validation annotations.
     *
     * @param object the object to validate
     * @param groups optional validation groups
     * @param <T>    object type
     * @return an optional validation exception when constraints fail
     */
    @Override
    public <T> Optional<ValidationException> checkValidity(T object, Class<?>... groups) {
        if (object == null) {
            return Optional.empty();
        }
        Class<?>[] effectiveGroups = ValidationAnnotationUtils.normalizeGroups(groups);
        ValidationRun run = new ValidationRun(object, effectiveGroups.length > 1, settings);
        try {
            run.validateBean(object, effectiveGroups, ValidationPath.root());
        } catch (RuntimeException e) {
            ValidationRun fieldRun = new ValidationRun(object, effectiveGroups.length > 1, settings, true, true);
            fieldRun.validateBean(object, effectiveGroups, ValidationPath.root());
            if (fieldRun.hasViolations()) {
                return Optional.of(newValidationException(fieldRun.violations()));
            }
            throw e;
        }
        if (run.hasViolations() && run.skippedMethodsAfterFieldViolations()) {
            Set<ConstraintViolation<?>> violations = new LinkedHashSet<>(run.violations());
            try {
                ValidationRun fullRun = new ValidationRun(object, effectiveGroups.length > 1, settings, false, false);
                fullRun.validateBean(object, effectiveGroups, ValidationPath.root());
                violations.addAll(fullRun.violations());
            } catch (Exception ignored) {
            }
            return Optional.of(newValidationException(violations));
        }
        return run.hasViolations() ? Optional.of(newValidationException(run.violations())) : Optional.empty();
    }

    /**
     * Validates a bean instance and returns raw constraint violations.
     * <p>
     * This method mirrors Jakarta Validator's raw validation style: it returns structured violations and does not
     * suppress exceptions from getter validation after field constraints have failed.
     *
     * @param object the object to validate
     * @param groups optional validation groups
     * @param <T>    object type
     * @return raw constraint violations, including property paths and descriptors
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Set<io.fluxzero.sdk.tracking.handling.validation.ConstraintViolation<T>> getConstraintViolations(
            T object, Class<?>... groups) {
        return toFluxzeroViolations(getJakartaConstraintViolations(object, groups));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> getJakartaConstraintViolations(T object, Class<?>... groups) {
        if (object == null) {
            return Set.of();
        }
        Class<?>[] effectiveGroups = ValidationAnnotationUtils.normalizeGroups(groups);
        ValidationRun run = new ValidationRun(object, effectiveGroups.length > 1, settings, false, false);
        run.validateBean(object, effectiveGroups, ValidationPath.root());
        return new LinkedHashSet<>((Collection) run.violations());
    }

    private static <T> Set<io.fluxzero.sdk.tracking.handling.validation.ConstraintViolation<T>> toFluxzeroViolations(
            Set<ConstraintViolation<T>> violations) {
        Set<io.fluxzero.sdk.tracking.handling.validation.ConstraintViolation<T>> result = new LinkedHashSet<>();
        for (ConstraintViolation<T> violation : violations) {
            result.add(toFluxzeroViolation(violation));
        }
        return result;
    }

    private static <T> io.fluxzero.sdk.tracking.handling.validation.ConstraintViolation<T> toFluxzeroViolation(
            ConstraintViolation<T> violation) {
        ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
        return new io.fluxzero.sdk.tracking.handling.validation.ConstraintViolation<>(
                violation.getMessage(),
                violation.getMessageTemplate(),
                getPropertyPath(violation, true),
                getPropertyPath(violation, false),
                violation.getInvalidValue(),
                violation.getRootBean(),
                violation.getRootBeanClass(),
                descriptor == null || descriptor.getAnnotation() == null
                        ? null : descriptor.getAnnotation().annotationType().getName(),
                descriptor == null ? Map.of() : descriptor.getAttributes());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> getPropertyConstraintViolations(
            T object, String propertyName, Class<?>... groups) {
        Objects.requireNonNull(object, "object");
        BeanValidationMetadata.MemberMetadata member = BeanValidationMetadata.of(object.getClass()).member(propertyName)
                .orElseThrow(() -> new IllegalArgumentException("No constrained property " + propertyName
                                                                + " on " + object.getClass().getName()));
        Class<?>[] effectiveGroups = ValidationAnnotationUtils.normalizeGroups(groups);
        ValidationRun run = new ValidationRun(object, effectiveGroups.length > 1, settings, false, false);
        member.validate(run, object, effectiveGroups, ValidationPath.root(), false);
        return new LinkedHashSet<>((Collection) run.violations());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> getValueConstraintViolations(
            Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
        Objects.requireNonNull(beanType, "beanType");
        BeanValidationMetadata.MemberMetadata member = BeanValidationMetadata.of(beanType).member(propertyName)
                .orElseThrow(() -> new IllegalArgumentException("No constrained property " + propertyName
                                                                + " on " + beanType.getName()));
        Class<?>[] effectiveGroups = ValidationAnnotationUtils.normalizeGroups(groups);
        ValidationRun run = new ValidationRun(null, beanType, effectiveGroups.length > 1, settings, false, false);
        member.validateValue(run, null, value, effectiveGroups, ValidationPath.root(), false);
        return new LinkedHashSet<>((Collection) run.violations());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> getParameterConstraintViolations(
            @Nullable T target, Executable executable, Object[] arguments, Class<?>... groups) {
        Object[] args = arguments == null ? new Object[0] : arguments;
        Class<?>[] effectiveGroups = ValidationAnnotationUtils.normalizeGroups(groups);
        ValidationRun run = new ValidationRun(target == null ? executable : target, false, settings);
        ExecutableValidationMetadata.of(executable).validate(run, target, executable, args, effectiveGroups);
        return new LinkedHashSet<>((Collection) run.violations());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> getReturnValueConstraintViolations(
            @Nullable T target, Executable executable, @Nullable Object returnValue, Class<?>... groups) {
        Class<?>[] effectiveGroups = ValidationAnnotationUtils.normalizeGroups(groups);
        ValidationRun run = new ValidationRun(target == null ? executable : target, false, settings);
        ExecutableValidationMetadata.of(executable).validateReturnValue(run, target, executable, returnValue, effectiveGroups);
        return new LinkedHashSet<>((Collection) run.violations());
    }

    /**
     * Validates method or constructor arguments against executable parameter constraints.
     *
     * @param target     target instance for methods, or {@code null} for constructors/static methods
     * @param executable the method or constructor declaring the constraints
     * @param arguments  argument values in declaration order
     * @return an optional validation exception when constraints fail
     */
    @Override
    public Optional<ValidationException> checkParameterValidity(
            @Nullable Object target, Executable executable, Object[] arguments) {
        if (executable instanceof Method && target == null && !Modifier.isStatic(executable.getModifiers())) {
            return Optional.empty();
        }
        Object[] args = arguments == null ? new Object[0] : arguments;
        ValidationRun run = new ValidationRun(target, false, settings);
        ExecutableValidationMetadata.of(executable).validate(run, target, executable, args,
                                                   ValidationAnnotationUtils.DEFAULT_GROUPS);
        return run.hasViolations() ? Optional.of(newValidationException(run.violations())) : Optional.empty();
    }

    /**
     * Returns whether the executable declares return value constraints or cascaded return value validation.
     *
     * @param executable the executable to inspect
     * @return {@code true} when return value validation should run
     */
    @Override
    public boolean hasReturnValueValidation(Executable executable) {
        return ExecutableValidationMetadata.of(executable).hasReturnValueValidation();
    }

    /**
     * Validates a method or constructor return value against return value constraints.
     *
     * @param target      target instance for methods, or {@code null} for constructors/static methods
     * @param executable  the executable that produced the value
     * @param returnValue returned value
     * @return an optional validation exception when constraints fail
     */
    @Override
    public Optional<ValidationException> checkReturnValueValidity(
            @Nullable Object target, Executable executable, @Nullable Object returnValue) {
        ExecutableValidationMetadata metadata = ExecutableValidationMetadata.of(executable);
        if (!metadata.hasReturnValueValidation()) {
            return Optional.empty();
        }
        ValidationRun run = new ValidationRun(target == null ? executable : target, false, settings);
        metadata.validateReturnValue(run, target, executable, returnValue,
                                     ValidationAnnotationUtils.DEFAULT_GROUPS);
        return run.hasViolations() ? Optional.of(newValidationException(run.violations())) : Optional.empty();
    }

    protected ValidationException newValidationException(Collection<? extends ConstraintViolation<?>> violations) {
        return new ValidationException(format(violations, false).stream().collect(joining(lineSeparator())),
                                       format(violations, true), violationSummaries(violations));
    }

    protected SortedSet<String> format(Collection<? extends ConstraintViolation<?>> violations,
                                       boolean fullPath) {
        return violations.stream().map(v -> format(v, fullPath))
                .collect(toCollection(() -> new TreeSet<>(CASE_INSENSITIVE_ORDER)));
    }

    protected List<ValidationException.ViolationSummary> violationSummaries(
            Collection<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(violation -> new ValidationException.ViolationSummary(
                        getPropertyPath(violation, true), violation.getMessage()))
                .sorted((a, b) -> {
                    int path = CASE_INSENSITIVE_ORDER.compare(a.path(), b.path());
                    return path == 0 ? CASE_INSENSITIVE_ORDER.compare(a.message(), b.message()) : path;
                }).toList();
    }

    protected static String format(ConstraintViolation<?> v, boolean fullPath) {
        if (v instanceof DefaultValidationMetadata.DefaultConstraintViolation<?> violation) {
            return violation.format(fullPath);
        }
        ConstraintDescriptor<?> constraintDescriptor = v.getConstraintDescriptor();
        if (constraintDescriptor != null
            && ReflectionUtils.hasNonDefaultAnnotationAttribute(constraintDescriptor.getAnnotation(), "message")) {
            return v.getMessage();
        }
        return String.format("%s %s", getPropertyPath(v, fullPath), v.getMessage()).trim();
    }

    protected static String getPropertyPath(ConstraintViolation<?> v, boolean full) {
        return ValidationPath.propertyPath(v, full);
    }
}
