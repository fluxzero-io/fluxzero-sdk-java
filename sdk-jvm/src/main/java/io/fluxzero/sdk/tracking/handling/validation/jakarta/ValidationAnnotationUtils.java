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

import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.ParameterDescriptor;
import io.fluxzero.sdk.registry.PropertyDescriptor;
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
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
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
        List<Annotation> annotations = annotations(element);
        return !constraintAnnotations(annotations).isEmpty()
               || hasAnnotation(annotations, Valid.class)
               || hasAnnotation(annotations, ConvertGroup.class)
               || hasAnnotation(annotations, ConvertGroup.List.class);
    }

    static boolean hasValidationAnnotations(AnnotatedType type) {
        if (type == null || ComponentMetadataLookups.generatedOnlyMode()) {
            return false;
        }
        return type != null && (hasValidationAnnotations((AnnotatedElement) type)
                                || type instanceof AnnotatedParameterizedType parameterizedType
                                   && Arrays.stream(parameterizedType.getAnnotatedActualTypeArguments())
                                           .anyMatch(ValidationAnnotationUtils::hasValidationAnnotations)
                                || type instanceof AnnotatedArrayType arrayType
                                   && hasValidationAnnotations(arrayType.getAnnotatedGenericComponentType()));
    }

    static List<ConstraintMeta> constraintMetas(AnnotatedElement element) {
        return constraintAnnotations(annotations(element)).stream().map(ConstraintMeta::new)
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
        if (JvmComponentIntrospector.getInstance().getAnnotation(annotation.annotationType(), Constraint.class).isPresent()) {
            result.add(annotation);
            return;
        }
        Optional<Method> valueMethod = JvmComponentIntrospector.getInstance().getTypeMetadata(annotation.annotationType()).method("value")
                .filter(method -> method.getParameterCount() == 0);
        if (valueMethod.isEmpty()) {
            return;
        }
        Class<?> returnType = valueMethod.get().getReturnType();
        if (!returnType.isArray() || !returnType.getComponentType().isAnnotation()
            || JvmComponentIntrospector.getInstance().getAnnotation(returnType.getComponentType(), Constraint.class).isEmpty()) {
            return;
        }
        Object value = JvmComponentIntrospector.getInstance().getAnnotationAttribute(annotation, valueMethod.get().getName(), Object.class)
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
        return JvmComponentIntrospector.getInstance().hasNonDefaultAnnotationAttribute(annotation, "message");
    }

    static Optional<ConstraintTarget> validationAppliesTo(Annotation annotation) {
        return annotationValue(annotation, "validationAppliesTo", ConstraintTarget.class);
    }

    @SuppressWarnings("unchecked")
    static <T> Optional<T> annotationValue(Annotation annotation, String name, Class<T> expectedType) {
        return JvmComponentIntrospector.getInstance().getAnnotationAttribute(annotation, name, expectedType);
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
            GroupSequence sequence = annotation(beanType, GroupSequence.class).orElse(null);
            if (sequence != null) {
                return sequence.value();
            }
        }
        GroupSequence sequence = annotation(group, GroupSequence.class).orElse(null);
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
        ConvertGroup single = annotation(element, ConvertGroup.class).orElse(null);
        if (single != null) {
            result.add(new GroupConversion(single.from(), single.to()));
        }
        ConvertGroup.List list = annotation(element, ConvertGroup.List.class).orElse(null);
        if (list != null) {
            for (ConvertGroup conversion : list.value()) {
                result.add(new GroupConversion(conversion.from(), conversion.to()));
            }
        }
        return List.copyOf(result);
    }

    static boolean hasAnnotation(AnnotatedElement element, Class<? extends Annotation> annotationType) {
        return annotation(element, annotationType).isPresent();
    }

    static <A extends Annotation> Optional<A> annotation(AnnotatedElement element, Class<A> annotationType) {
        List<Annotation> annotations = annotations(element);
        Optional<A> annotation = annotations.stream()
                .filter(candidate -> annotationType.isAssignableFrom(candidate.annotationType()))
                .map(annotationType::cast)
                .findFirst();
        if (annotation.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return annotation;
        }
        return JvmComponentIntrospector.getInstance().getAnnotation(element, annotationType);
    }

    static List<Annotation> annotations(AnnotatedElement element) {
        Optional<Class<?>> declaringClass = metadataDeclaringClass(element);
        if (declaringClass.isPresent()) {
            List<AnnotationDescriptor> descriptors = metadataAnnotations(element);
            if (!descriptors.isEmpty() || ComponentMetadataLookups.generatedOnlyMode()) {
                return ComponentMetadataLookups.annotationViews(descriptors, declaringClass.get());
            }
        }
        return ComponentMetadataLookups.generatedOnlyMode()
                ? List.of() : JvmComponentIntrospector.getInstance().getAnnotations(element);
    }

    private static boolean hasAnnotation(Collection<? extends Annotation> annotations,
                                         Class<? extends Annotation> annotationType) {
        return annotations.stream().anyMatch(annotation -> annotationType.isAssignableFrom(annotation.annotationType()));
    }

    private static List<AnnotationDescriptor> metadataAnnotations(AnnotatedElement element) {
        return metadataDeclaringClass(element)
                .flatMap(type -> ComponentMetadataLookups.registeredLookup(type)
                        .map(lookup -> metadataAnnotations(lookup, element)))
                .orElseGet(List::of);
    }

    private static List<AnnotationDescriptor> metadataAnnotations(
            ComponentMetadataLookup lookup, AnnotatedElement element) {
        if (element instanceof Parameter parameter) {
            return ComponentMetadataLookups.parameter(lookup, parameter)
                    .map(ParameterDescriptor::annotations)
                    .orElseGet(List::of);
        }
        if (element instanceof RecordComponent component) {
            return lookup.property(component.getDeclaringRecord().getName(), component.getName())
                    .map(PropertyDescriptor::annotations)
                    .orElseGet(List::of);
        }
        if (element instanceof Field field) {
            return lookup.property(field.getDeclaringClass().getName(), field.getName())
                    .map(PropertyDescriptor::annotations)
                    .orElseGet(List::of);
        }
        if (element instanceof Executable executable) {
            return ComponentMetadataLookups.executable(lookup, executable)
                    .map(ExecutableDescriptor::annotations)
                    .orElseGet(List::of);
        }
        if (element instanceof Class<?> type) {
            return lookup.typeAnnotations(type.getName());
        }
        return List.of();
    }

    private static Optional<Class<?>> metadataDeclaringClass(AnnotatedElement element) {
        if (element instanceof Parameter parameter) {
            return Optional.of(parameter.getDeclaringExecutable().getDeclaringClass());
        }
        if (element instanceof RecordComponent component) {
            return Optional.of(component.getDeclaringRecord());
        }
        if (element instanceof Field field) {
            return Optional.of(field.getDeclaringClass());
        }
        if (element instanceof Executable executable) {
            return Optional.of(executable.getDeclaringClass());
        }
        if (element instanceof Class<?> type) {
            return Optional.of(type);
        }
        return Optional.empty();
    }

    record GroupConversion(Class<?> from, Class<?> to) {
    }
}
