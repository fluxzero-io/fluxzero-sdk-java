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

import io.fluxzero.common.reflection.KotlinReflectionUtils;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record BeanValidationMetadata(Class<?> type, List<ConstraintMeta> classConstraints, List<MemberMetadata> members) {
    private static final ClassValue<BeanValidationMetadata> cache = new ClassValue<>() {
        @Override
        protected BeanValidationMetadata computeValue(Class<?> type) {
            return new BeanValidationMetadata(type);
        }
    };

    static BeanValidationMetadata of(Class<?> type) {
        return cache.get(type);
    }

    private BeanValidationMetadata(Class<?> type) {
        this(type, classConstraints(type), members(type));
    }

    Optional<MemberMetadata> member(String propertyName) {
        return members.stream().filter(member -> member.propertyName().equals(propertyName)).findFirst();
    }

    void validate(ValidationRun run, Object object, Class<?> group, ValidationPath path) {
        int violationsBeforeBean = run.violations().size();
        int violationsBeforeMethods = -1;
        for (MemberMetadata member : members) {
            if (run.fieldOnly() && member.method()) {
                break;
            }
            if (run.skipMethodsAfterFieldViolations() && member.method() && violationsBeforeMethods < 0) {
                violationsBeforeMethods = run.violations().size();
                if (violationsBeforeMethods > violationsBeforeBean || run.hasViolations()) {
                    run.markSkippedMethodsAfterFieldViolations();
                    break;
                }
            }
            try {
                member.validate(run, object, group, path);
            } catch (Throwable e) {
                throw sneakyThrow(e);
            }
        }
        if (run.fieldOnly()) {
            return;
        }
        for (ConstraintMeta classConstraint : classConstraints) {
            run.validateValue(object, classConstraint, object, group, path);
        }
    }

    private static List<ConstraintMeta> classConstraints(Class<?> type) {
        LinkedHashMap<String, ConstraintMeta> result = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            addClassConstraints(current, result);
        }
        for (Class<?> interfaceType : ReflectionUtils.getAllInterfaces(type)) {
            addClassConstraints(interfaceType, result);
        }
        return List.copyOf(result.values());
    }

    private static void addClassConstraints(Class<?> type, Map<String, ConstraintMeta> result) {
        for (ConstraintMeta meta : ValidationAnnotationUtils.constraintMetas(type)) {
            result.putIfAbsent(meta.annotation().toString(), meta);
        }
    }

    private static List<MemberMetadata> members(Class<?> type) {
        List<MemberMetadata> result = new ArrayList<>();
        Map<String, MemberMetadata> fieldMembers = new HashMap<>();
        for (Field field : ReflectionUtils.getTypeMetadata(type).fields()) {
            if (MemberMetadata.fieldHasValidation(field)) {
                MemberMetadata metadata = MemberMetadata.field(field);
                result.add(metadata);
                fieldMembers.putIfAbsent(metadata.propertyName(), metadata);
            }
        }
        for (MemberMetadata metadata : KotlinSupport.constructorParameterMembers(type)) {
            if (!metadata.duplicates(fieldMembers.get(metadata.propertyName()))) {
                result.add(metadata);
                fieldMembers.putIfAbsent(metadata.propertyName(), metadata);
            }
        }
        for (Method method : ReflectionUtils.getAllMethods(type)) {
            if (isBeanPropertyMethod(method) && MemberMetadata.methodHasValidation(method)) {
                MemberMetadata metadata = MemberMetadata.method(method);
                if (!metadata.duplicates(fieldMembers.get(metadata.propertyName()))) {
                    result.add(metadata);
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean isBeanPropertyMethod(Method method) {
        return method.getParameterCount() == 0
               && ReflectionUtils.hasReturnType(method)
               && !Modifier.isStatic(method.getModifiers())
               && !method.getDeclaringClass().isAssignableFrom(method.getReturnType())
               && hasBeanPropertyName(method);
    }

    private static boolean hasBeanPropertyName(Method method) {
        String name = method.getName();
        return hasPrefixedPropertyName(name, "get")
               || (isBooleanType(method.getReturnType())
                   && (hasPrefixedPropertyName(name, "is") || hasPrefixedPropertyName(name, "has")));
    }

    private static boolean hasPrefixedPropertyName(String name, String prefix) {
        return name.length() > prefix.length()
               && name.startsWith(prefix)
               && Character.isUpperCase(name.charAt(prefix.length()));
    }

    private static boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

static final class KotlinSupport {
    private static final String KOTLIN_METADATA = "kotlin.Metadata";

    private KotlinSupport() {
    }

    static List<MemberMetadata> constructorParameterMembers(Class<?> type) {
        if (!ReflectionUtils.isKotlinReflectionSupported() || !isKotlinType(type)) {
            return List.of();
        }
        Constructor<?> constructor = primaryConstructor(type);
        if (constructor == null || constructor.isSynthetic()) {
            return List.of();
        }
        List<MemberMetadata> result = new ArrayList<>();
        for (Parameter parameter : constructor.getParameters()) {
            if (!MemberMetadata.parameterHasValidation(parameter)) {
                continue;
            }
            String propertyName = propertyName(parameter);
            if (propertyName == null) {
                continue;
            }
            propertyInvoker(type, propertyName)
                    .map(invoker -> MemberMetadata.parameter(propertyName, parameter, invoker))
                    .ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private static boolean isKotlinType(Class<?> type) {
        for (Annotation annotation : ReflectionUtils.getTypeMetadata(type).typeAnnotations()) {
            if (annotation.annotationType().getName().equals(KOTLIN_METADATA)) {
                return true;
            }
        }
        return false;
    }

    private static Constructor<?> primaryConstructor(Class<?> type) {
        try {
            return KotlinReflectionUtils.getPrimaryConstructor(type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String propertyName(Parameter parameter) {
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        try {
            return KotlinReflectionUtils.getKotlinParameterName(parameter);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Optional<MemberInvoker> propertyInvoker(Class<?> type, String propertyName) {
        Optional<Field> field = ReflectionUtils.getTypeMetadata(type).field(propertyName)
                .filter(f -> !Modifier.isStatic(f.getModifiers()));
        if (field.isPresent()) {
            return field.map(f -> ReflectionUtils.getTypeMetadata(f.getDeclaringClass()).invoker(f, true));
        }
        for (Method method : ReflectionUtils.getAllMethods(type)) {
            if (!Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0
                && ReflectionUtils.hasReturnType(method)
                && propertyName.equals(ReflectionUtils.getPropertyName(method))) {
                return Optional.of(ReflectionUtils.getTypeMetadata(method.getDeclaringClass())
                                           .invoker(method, true));
            }
        }
        return Optional.empty();
    }
}

record MemberMetadata(String propertyName, AnnotatedElement member, MemberInvoker invoker, boolean method,
                      List<ConstraintMeta> constraints, TypeUseValidationMetadata typeUse,
                      boolean cascaded, List<ValidationAnnotationUtils.GroupConversion> conversions) {
    static boolean fieldHasValidation(Field field) {
        return ValidationAnnotationUtils.hasValidationAnnotations(field)
               || ValidationAnnotationUtils.hasValidationAnnotations(field.getAnnotatedType());
    }

    static MemberMetadata field(Field field) {
        List<ConstraintMeta> constraints = ValidationAnnotationUtils.constraintMetas(field);
        boolean cascaded = ReflectionUtils.getAnnotation(field, Valid.class).isPresent();
        List<ValidationAnnotationUtils.GroupConversion> conversions = ValidationAnnotationUtils.groupConversions(field);
        return new MemberMetadata(field.getName(), field,
                                  ReflectionUtils.getTypeMetadata(field.getDeclaringClass()).invoker(field, true),
                                  false, constraints,
                                  TypeUseValidationMetadata.of(field.getAnnotatedType(), constraints, cascaded, conversions),
                                  cascaded, conversions);
    }

    static boolean parameterHasValidation(Parameter parameter) {
        return ValidationAnnotationUtils.hasValidationAnnotations(parameter)
               || ValidationAnnotationUtils.hasValidationAnnotations(parameter.getAnnotatedType());
    }

    static MemberMetadata parameter(String propertyName, Parameter parameter, MemberInvoker invoker) {
        List<ConstraintMeta> constraints = ValidationAnnotationUtils.constraintMetas(parameter);
        boolean cascaded = ReflectionUtils.getAnnotation(parameter, Valid.class).isPresent();
        List<ValidationAnnotationUtils.GroupConversion> conversions = ValidationAnnotationUtils.groupConversions(parameter);
        return new MemberMetadata(propertyName, parameter, invoker, false, constraints,
                                  TypeUseValidationMetadata.of(parameter.getAnnotatedType(), constraints, cascaded, conversions),
                                  cascaded, conversions);
    }

    static boolean methodHasValidation(Method method) {
        return ReflectionUtils.getMethodOverrideHierarchy(method)
                       .anyMatch(ValidationAnnotationUtils::hasValidationAnnotations)
               || ValidationAnnotationUtils.hasValidationAnnotations(method.getAnnotatedReturnType());
    }

    static MemberMetadata method(Method method) {
        List<ConstraintMeta> constraints = ReflectionUtils.getMethodOverrideHierarchy(method)
                .flatMap(m -> ValidationAnnotationUtils.constraintMetas(m).stream())
                .distinct()
                .toList();
        boolean cascaded = ReflectionUtils.getMethodOverrideHierarchy(method)
                .anyMatch(m -> ReflectionUtils.getAnnotation(m, Valid.class).isPresent());
        List<ValidationAnnotationUtils.GroupConversion> conversions = ReflectionUtils.getMethodOverrideHierarchy(method)
                .flatMap(m -> ValidationAnnotationUtils.groupConversions(m).stream())
                .toList();
        return new MemberMetadata(propertyName(method), method,
                                  ReflectionUtils.getTypeMetadata(method.getDeclaringClass()).invoker(method, true),
                                  true, constraints,
                                  TypeUseValidationMetadata.of(method.getAnnotatedReturnType(), constraints, cascaded,
                                                     conversions),
                                  cascaded, conversions);
    }

    private static String propertyName(Method method) {
        String name = method.getName();
        if (hasPrefixedPropertyName(name, "get")) {
            return decapitalize(name, 3);
        }
        if (isBooleanType(method.getReturnType()) && hasPrefixedPropertyName(name, "is")) {
            return decapitalize(name, 2);
        }
        if (isBooleanType(method.getReturnType()) && hasPrefixedPropertyName(name, "has")) {
            return decapitalize(name, 3);
        }
        return ReflectionUtils.getPropertyName(method);
    }

    private static boolean hasPrefixedPropertyName(String name, String prefix) {
        return name.length() > prefix.length()
               && name.startsWith(prefix)
               && Character.isUpperCase(name.charAt(prefix.length()));
    }

    private static boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static String decapitalize(String name, int offset) {
        char[] c = name.toCharArray();
        c[offset] = Character.toLowerCase(c[offset]);
        return String.valueOf(c, offset, c.length - offset);
    }

    private boolean hasValidation() {
        return !constraints.isEmpty() || cascaded || typeUse.hasValidation();
    }

    private boolean appliesToGroup(Class<?> group) {
        for (ConstraintMeta constraint : constraints) {
            if (constraint.appliesToGroup(group)) {
                return true;
            }
        }
        return cascaded || typeUse.appliesToGroup(group);
    }

    boolean duplicates(@Nullable MemberMetadata other) {
        return other != null
               && other.constraints().containsAll(constraints)
               && (!cascaded || other.cascaded())
               && !typeUse.hasValidation();
    }

    void validate(ValidationRun run, Object owner, Class<?> group, ValidationPath parentPath) {
        if (!appliesToGroup(group)) {
            return;
        }
        validateValue(run, owner, invoker.invoke(owner), group, parentPath, true);
    }

    void validate(ValidationRun run, Object owner, Class<?>[] groups, ValidationPath parentPath, boolean cascade) {
        if (!appliesToAnyGroup(groups)) {
            return;
        }
        validateValue(run, owner, invoker.invoke(owner), groups, parentPath, cascade);
    }

    void validateValue(ValidationRun run, @Nullable Object owner, Object value, Class<?>[] groups,
                       ValidationPath parentPath, boolean cascade) {
        ValidationPath path = parentPath.appendProperty(propertyName);
        for (Class<?> group : groups) {
            if (!appliesToGroup(group)) {
                continue;
            }
            for (ConstraintMeta constraint : constraints) {
                run.validateValue(owner, constraint, value, group, path);
            }
            typeUse.validate(run, owner, value, group, path);
            if (cascade && cascaded) {
                typeUse.cascade(run, value, ValidationAnnotationUtils.convertGroup(group, conversions), path);
            }
        }
    }

    void validateValue(ValidationRun run, @Nullable Object owner, Object value, Class<?> group,
                       ValidationPath parentPath, boolean cascade) {
        ValidationPath path = parentPath.appendProperty(propertyName);
        for (ConstraintMeta constraint : constraints) {
            run.validateValue(owner, constraint, value, group, path);
        }
        typeUse.validate(run, owner, value, group, path);
        if (cascade && cascaded) {
            typeUse.cascade(run, value, ValidationAnnotationUtils.convertGroup(group, conversions), path);
        }
    }

    private boolean appliesToAnyGroup(Class<?>[] groups) {
        for (Class<?> group : groups) {
            if (appliesToGroup(group)) {
                return true;
            }
        }
        return false;
    }
}
}
