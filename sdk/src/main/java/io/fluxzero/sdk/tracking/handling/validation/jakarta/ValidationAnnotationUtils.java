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
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.GroupSequence;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ValidationAnnotationUtils {
    static final Class<?>[] DEFAULT_GROUPS = new Class<?>[]{Default.class};

    private ValidationAnnotationUtils() {
    }

    static Class<?>[] normalizeGroups(Class<?>[] groups) {
        return groups == null || groups.length == 0 ? DEFAULT_GROUPS : groups;
    }

    static boolean hasValidationAnnotations(AnnotatedElement element) {
        return !constraintAnnotations(ReflectionUtils.getAnnotations(element)).isEmpty()
               || ReflectionUtils.getAnnotation(element, Valid.class).isPresent()
               || ReflectionUtils.getAnnotation(element, ConvertGroup.class).isPresent()
               || ReflectionUtils.getAnnotation(element, ConvertGroup.List.class).isPresent();
    }

    static boolean hasValidationAnnotations(AnnotatedType type) {
        return type != null && (hasValidationAnnotations((AnnotatedElement) type)
                                || type instanceof AnnotatedParameterizedType parameterizedType
                                   && Arrays.stream(parameterizedType.getAnnotatedActualTypeArguments())
                                           .anyMatch(ValidationAnnotationUtils::hasValidationAnnotations)
                                || type instanceof AnnotatedArrayType arrayType
                                   && hasValidationAnnotations(arrayType.getAnnotatedGenericComponentType()));
    }

    static List<ConstraintMeta> constraintMetas(AnnotatedElement element) {
        return constraintAnnotations(ReflectionUtils.getAnnotations(element)).stream().map(ConstraintMeta::new)
                .toList();
    }

    static List<Annotation> constraintAnnotations(Collection<? extends Annotation> annotations) {
        List<Annotation> result = new ArrayList<>();
        for (Annotation annotation : annotations) {
            addConstraintAnnotation(annotation, result);
        }
        return result;
    }

    private static void addConstraintAnnotation(Annotation annotation, List<Annotation> result) {
        if (ReflectionUtils.getAnnotation(annotation.annotationType(), Constraint.class).isPresent()) {
            result.add(annotation);
            return;
        }
        Optional<Method> valueMethod = ReflectionUtils.getTypeMetadata(annotation.annotationType()).method("value")
                .filter(method -> method.getParameterCount() == 0);
        if (valueMethod.isEmpty()) {
            return;
        }
        Class<?> returnType = valueMethod.get().getReturnType();
        if (!returnType.isArray() || !returnType.getComponentType().isAnnotation()
            || ReflectionUtils.getAnnotation(returnType.getComponentType(), Constraint.class).isEmpty()) {
            return;
        }
        Object value = ReflectionUtils.getAnnotationAttribute(annotation, valueMethod.get().getName(), Object.class)
                .orElse(null);
        for (int i = 0; value != null && i < Array.getLength(value); i++) {
            result.add((Annotation) Array.get(value, i));
        }
    }

    static Set<Class<?>> groups(Annotation annotation) {
        Class<?>[] groups = annotationValue(annotation, "groups", Class[].class).orElse(null);
        if (groups == null || groups.length == 0) {
            return Set.of(Default.class);
        }
        return new LinkedHashSet<>(Arrays.asList(groups));
    }

    @SuppressWarnings("unchecked")
    static Set<Class<? extends Payload>> payload(Annotation annotation) {
        Class<? extends Payload>[] payload = annotationValue(annotation, "payload", Class[].class).orElse(null);
        if (payload == null || payload.length == 0) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(payload));
    }

    static String messageTemplate(Annotation annotation) {
        return annotationValue(annotation, "message", String.class)
                .orElseThrow(() -> new ConstraintDefinitionException(
                        "Constraint annotation " + annotation.annotationType().getName()
                        + " does not declare a message() element"));
    }

    static boolean customMessage(Annotation annotation) {
        return ReflectionUtils.hasNonDefaultAnnotationAttribute(annotation, "message");
    }

    static Optional<ConstraintTarget> validationAppliesTo(Annotation annotation) {
        return annotationValue(annotation, "validationAppliesTo", ConstraintTarget.class);
    }

    @SuppressWarnings("unchecked")
    static <T> Optional<T> annotationValue(Annotation annotation, String name, Class<T> expectedType) {
        return ReflectionUtils.getAnnotationAttribute(annotation, name, expectedType);
    }

    static boolean appliesToGroup(ConstraintMeta meta, Class<?> requestedGroup) {
        for (Class<?> constraintGroup : meta.groups()) {
            if (constraintGroup.isAssignableFrom(requestedGroup)) {
                return true;
            }
        }
        return false;
    }

    static Class<?>[] validationGroups(Class<?> group, Class<?> beanType) {
        if (group == Default.class) {
            GroupSequence sequence = ReflectionUtils.getAnnotation(beanType, GroupSequence.class).orElse(null);
            if (sequence != null) {
                return sequence.value();
            }
        }
        GroupSequence sequence = ReflectionUtils.getAnnotation(group, GroupSequence.class).orElse(null);
        return sequence == null ? new Class<?>[]{group} : sequence.value();
    }

    static Class<?> effectiveGroup(Class<?> group, Class<?> beanType) {
        return group == beanType ? Default.class : group;
    }

    static Class<?>[] convertGroups(Class<?>[] groups, List<GroupConversion> conversions) {
        if (conversions.isEmpty()) {
            return groups;
        }
        Class<?>[] result = groups.clone();
        for (int i = 0; i < result.length; i++) {
            for (GroupConversion conversion : conversions) {
                if (conversion.from().equals(result[i])) {
                    result[i] = conversion.to();
                    break;
                }
            }
        }
        return result;
    }

    static Class<?> convertGroup(Class<?> group, List<GroupConversion> conversions) {
        for (GroupConversion conversion : conversions) {
            if (conversion.from().equals(group)) {
                return conversion.to();
            }
        }
        return group;
    }

    static List<GroupConversion> groupConversions(AnnotatedElement element) {
        List<GroupConversion> result = new ArrayList<>();
        ConvertGroup single = ReflectionUtils.getAnnotation(element, ConvertGroup.class).orElse(null);
        if (single != null) {
            result.add(new GroupConversion(single.from(), single.to()));
        }
        ConvertGroup.List list = ReflectionUtils.getAnnotation(element, ConvertGroup.List.class).orElse(null);
        if (list != null) {
            for (ConvertGroup conversion : list.value()) {
                result.add(new GroupConversion(conversion.from(), conversion.to()));
            }
        }
        return List.copyOf(result);
    }

    record GroupConversion(Class<?> from, Class<?> to) {
    }
}
