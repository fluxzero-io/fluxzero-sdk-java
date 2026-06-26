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
import jakarta.validation.ElementKind;
import jakarta.validation.Valid;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

record ExecutableValidationMetadata(List<ConstraintMeta> crossParameterConstraints, ReturnValueMetadata returnValue,
                          List<ParameterMetadata> parameters, String name, ElementKind kind) {
    private static final ConcurrentHashMap<Executable, ExecutableValidationMetadata> cache = new ConcurrentHashMap<>();

    static ExecutableValidationMetadata of(Executable executable) {
        return cache.computeIfAbsent(executable, ExecutableValidationMetadata::new);
    }

    private ExecutableValidationMetadata(Executable executable) {
        this(executableConstraints(executable), ReturnValueMetadata.of(executable), parameters(executable),
             executableName(executable),
             executable instanceof Constructor<?> ? ElementKind.CONSTRUCTOR : ElementKind.METHOD);
    }

    void validate(ValidationRun run, Object target, Executable executable, Object[] arguments,
                  Class<?>[] groups) {
        ValidationPath executablePath = ValidationPath.root().appendExecutable(name, kind);
        List<String> parameterNames = run.parameterNames(executable);
        for (Class<?> group : groups) {
            for (ConstraintMeta constraint : crossParameterConstraints) {
                run.validateValue(target == null ? executable : target, constraint, arguments, group,
                                  executablePath.appendCrossParameter());
            }
            for (int i = 0; i < parameters.size(); i++) {
                Object value = i < arguments.length ? arguments[i] : null;
                String parameterName = i < parameterNames.size() ? parameterNames.get(i) : parameters.get(i).name();
                parameters.get(i).validate(run, target == null ? executable : target, value, group, executablePath,
                                           parameterName);
            }
        }
    }

    boolean hasReturnValueValidation() {
        return returnValue.hasValidation();
    }

    void validateReturnValue(ValidationRun run, Object target, Executable executable,
                             Object value, Class<?>[] groups) {
        ValidationPath executablePath = ValidationPath.root().appendExecutable(name, kind);
        Object owner = target == null ? executable : target;
        for (Class<?> group : groups) {
            returnValue.validate(run, owner, value, group, executablePath);
        }
    }

    private static List<ConstraintMeta> executableConstraints(Executable executable) {
        if (executable instanceof Method method) {
            return ReflectionUtils.getMethodOverrideHierarchy(method)
                    .flatMap(m -> ValidationAnnotationUtils.constraintMetas(m).stream())
                    .filter(ConstraintMeta::crossParameter)
                    .distinct()
                    .toList();
        }
        return ValidationAnnotationUtils.constraintMetas(executable).stream()
                .filter(ConstraintMeta::crossParameter)
                .toList();
    }

    private static List<ParameterMetadata> parameters(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        List<ParameterMetadata> result = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            result.add(ParameterMetadata.of(parameters[i], i));
        }
        return List.copyOf(result);
    }

    private static String executableName(Executable executable) {
        return executable instanceof Constructor<?> ? executable.getDeclaringClass().getSimpleName()
                : executable.getName();
    }

record ReturnValueMetadata(List<ConstraintMeta> constraints, TypeUseValidationMetadata typeUse, boolean cascaded,
                           List<ValidationAnnotationUtils.GroupConversion> conversions) {
    static ReturnValueMetadata of(Executable executable) {
        if (executable instanceof Method method) {
            List<Method> methods = ReflectionUtils.getMethodOverrideHierarchy(method).toList();
            List<ConstraintMeta> constraints = methods.stream()
                    .flatMap(m -> ValidationAnnotationUtils.constraintMetas(m).stream())
                    .filter(meta -> !meta.crossParameter())
                    .distinct()
                    .toList();
            boolean cascaded = methods.stream()
                    .anyMatch(m -> ReflectionUtils.getAnnotation(m, Valid.class).isPresent());
            List<ValidationAnnotationUtils.GroupConversion> conversions = methods.stream()
                    .flatMap(m -> ValidationAnnotationUtils.groupConversions(m).stream())
                    .toList();
            return new ReturnValueMetadata(constraints,
                                           TypeUseValidationMetadata.of(method.getAnnotatedReturnType(), constraints,
                                                              cascaded, conversions),
                                           cascaded, conversions);
        }
        List<ConstraintMeta> constraints = ValidationAnnotationUtils.constraintMetas(executable).stream()
                .filter(meta -> !meta.crossParameter())
                .toList();
        boolean cascaded = ReflectionUtils.getAnnotation(executable, Valid.class).isPresent();
        List<ValidationAnnotationUtils.GroupConversion> conversions = ValidationAnnotationUtils.groupConversions(executable);
        return new ReturnValueMetadata(constraints,
                                       TypeUseValidationMetadata.of(executable.getAnnotatedReturnType(), constraints,
                                                          cascaded, conversions),
                                       cascaded, conversions);
    }

    boolean hasValidation() {
        return !constraints.isEmpty() || cascaded || typeUse.hasValidation();
    }

    void validate(ValidationRun run, Object owner, Object value, Class<?> group,
                  ValidationPath executablePath) {
        ValidationPath path = executablePath.appendReturnValue();
        for (ConstraintMeta constraint : constraints) {
            run.validateValue(owner, constraint, value, group, path);
        }
        typeUse.validate(run, owner, value, group, path);
        if (cascaded) {
            typeUse.cascade(run, value, ValidationAnnotationUtils.convertGroup(group, conversions), path);
        }
    }
}

record ParameterMetadata(String name, int index, List<ConstraintMeta> constraints,
                         TypeUseValidationMetadata typeUse, boolean cascaded,
                         List<ValidationAnnotationUtils.GroupConversion> conversions) {
    static ParameterMetadata of(Parameter parameter, int index) {
        Stream<Parameter> hierarchy = ReflectionUtils.getParameterOverrideHierarchy(parameter);
        List<Parameter> parameters = hierarchy.toList();
        List<ConstraintMeta> constraints = parameters.stream()
                .flatMap(p -> ValidationAnnotationUtils.constraintMetas(p).stream())
                .distinct()
                .toList();
        boolean cascaded = parameters.stream()
                .anyMatch(p -> ReflectionUtils.getAnnotation(p, Valid.class).isPresent());
        List<ValidationAnnotationUtils.GroupConversion> conversions = parameters.stream()
                .flatMap(p -> ValidationAnnotationUtils.groupConversions(p).stream())
                .toList();
        return new ParameterMetadata(parameter.isNamePresent() ? parameter.getName() : "arg" + index, index,
                                     constraints, TypeUseValidationMetadata.of(parameter.getAnnotatedType(), constraints,
                                                                    cascaded, conversions),
                                     cascaded,
                                     conversions);
    }

    void validate(ValidationRun run, Object owner, Object value, Class<?> group,
                  ValidationPath executablePath, String parameterName) {
        ValidationPath path = executablePath.appendParameter(parameterName, index);
        for (ConstraintMeta constraint : constraints) {
            run.validateValue(owner, constraint, value, group, path);
        }
        typeUse.validate(run, owner, value, group, path);
        if (cascaded) {
            typeUse.cascade(run, value, ValidationAnnotationUtils.convertGroup(group, conversions), path);
        }
    }
}
}
