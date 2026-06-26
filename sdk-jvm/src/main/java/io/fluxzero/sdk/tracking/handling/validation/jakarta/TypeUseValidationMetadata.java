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
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record TypeUseValidationMetadata(List<ConstraintMeta> constraints, boolean cascaded,
                       List<ValidationAnnotationUtils.GroupConversion> conversions, List<TypeArgumentValidationMetadata> typeArguments,
                       @Nullable TypeUseValidationMetadata componentType) {
    private static final TypeUseValidationMetadata empty = new TypeUseValidationMetadata(List.of(), false, List.of(), List.of(), null);

    static TypeUseValidationMetadata of(AnnotatedType type) {
        return of(type, List.of(), false, List.of());
    }

    static TypeUseValidationMetadata of(AnnotatedType type, Collection<ConstraintMeta> ignoredConstraints,
                              boolean ignoreCascaded,
                              Collection<ValidationAnnotationUtils.GroupConversion> ignoredConversions) {
        if (type == null || !ValidationAnnotationUtils.hasValidationAnnotations(type)) {
            return empty;
        }
        List<TypeArgumentValidationMetadata> typeArguments = new ArrayList<>();
        TypeUseValidationMetadata component = null;
        if (type instanceof AnnotatedParameterizedType parameterizedType) {
            AnnotatedType[] arguments = parameterizedType.getAnnotatedActualTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                TypeUseValidationMetadata metadata = of(arguments[i]);
                if (metadata.hasValidation()) {
                    typeArguments.add(new TypeArgumentValidationMetadata(i, metadata));
                }
            }
        } else if (type instanceof AnnotatedArrayType arrayType) {
            component = of(arrayType.getAnnotatedGenericComponentType(), ignoredConstraints, ignoreCascaded,
                           ignoredConversions);
        }
        List<ConstraintMeta> constraints = ValidationAnnotationUtils.constraintMetas(type).stream()
                .filter(meta -> !ignoredConstraints.contains(meta))
                .toList();
        List<ValidationAnnotationUtils.GroupConversion> conversions = ValidationAnnotationUtils.groupConversions(type).stream()
                .filter(conversion -> !ignoredConversions.contains(conversion))
                .toList();
        return new TypeUseValidationMetadata(constraints,
                                   !ignoreCascaded && ReflectionUtils.getAnnotation(type, Valid.class).isPresent(),
                                   conversions, List.copyOf(typeArguments), component);
    }

    boolean hasValidation() {
        return !constraints.isEmpty() || cascaded || !typeArguments.isEmpty()
               || componentType != null && componentType.hasValidation();
    }

    boolean appliesToGroup(Class<?> group) {
        for (ConstraintMeta constraint : constraints) {
            if (constraint.appliesToGroup(group)) {
                return true;
            }
        }
        if (cascaded) {
            return true;
        }
        for (TypeArgumentValidationMetadata typeArgument : typeArguments) {
            if (typeArgument.appliesToGroup(group)) {
                return true;
            }
        }
        return componentType != null && componentType.appliesToGroup(group);
    }

    void validate(ValidationRun run, Object owner, Object value, Class<?> group, ValidationPath path) {
        for (ConstraintMeta constraint : constraints) {
            run.validateValue(owner, constraint, value, group, path);
        }
        for (TypeArgumentValidationMetadata typeArgument : typeArguments) {
            typeArgument.validate(run, owner, value, group, path);
        }
        if (componentType != null && value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                componentType.validate(run, owner, Array.get(value, i), group,
                                       path.appendContainerElement("<array element>", i, null));
            }
        }
        if (cascaded) {
            cascade(run, value, group, path);
        }
    }

    void cascade(ValidationRun run, Object value, Class<?> group, ValidationPath path) {
        Class<?> convertedGroup = ValidationAnnotationUtils.convertGroup(group, conversions);
        if (value == null) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(v -> run.validateBean(v, convertedGroup, path));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object element : iterable) {
                run.validateBean(element, convertedGroup, path.iterableElement(index++, null));
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                run.validateBean(entry.getValue(), convertedGroup, path.iterableElement(null, entry.getKey()));
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                run.validateBean(Array.get(value, i), convertedGroup, path.iterableElement(i, null));
            }
            return;
        }
        Optional<List<ValueExtractorRegistry.ExtractedContainerValue>> extractedValues = run.valueExtractors().extract(value, 0);
        if (extractedValues.isPresent()) {
            for (ValueExtractorRegistry.ExtractedContainerValue extractedValue : extractedValues.get()) {
                run.validateBean(extractedValue.value(), convertedGroup,
                                 extractedValue.appendTo(path, "<container element>"));
            }
            return;
        }
        run.validateBean(value, convertedGroup, path);
    }

    void cascade(ValidationRun run, Object value, Class<?>[] groups, ValidationPath path) {
        Class<?>[] convertedGroups = ValidationAnnotationUtils.convertGroups(groups, conversions);
        if (value == null) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(v -> run.validateBean(v, convertedGroups, path));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object element : iterable) {
                run.validateBean(element, convertedGroups, path.iterableElement(index++, null));
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                run.validateBean(entry.getValue(), convertedGroups, path.iterableElement(null, entry.getKey()));
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                run.validateBean(Array.get(value, i), convertedGroups, path.iterableElement(i, null));
            }
            return;
        }
        Optional<List<ValueExtractorRegistry.ExtractedContainerValue>> extractedValues = run.valueExtractors().extract(value, 0);
        if (extractedValues.isPresent()) {
            for (ValueExtractorRegistry.ExtractedContainerValue extractedValue : extractedValues.get()) {
                run.validateBean(extractedValue.value(), convertedGroups,
                                 extractedValue.appendTo(path, "<container element>"));
            }
            return;
        }
        run.validateBean(value, convertedGroups, path);
    }

record TypeArgumentValidationMetadata(int index, TypeUseValidationMetadata metadata) {
    boolean appliesToGroup(Class<?> group) {
        return metadata.appliesToGroup(group);
    }

    void validate(ValidationRun run, Object owner, Object container, Class<?> group, ValidationPath path) {
        if (container == null) {
            return;
        }
        if (container instanceof Optional<?> optional) {
            optional.ifPresent(value -> metadata.validate(run, owner, value, group, path));
            return;
        }
        if (container instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = index == 0 ? entry.getKey() : entry.getValue();
                metadata.validate(run, owner, value, group,
                                  path.appendContainerElement(index == 0 ? "<map key>" : "<map value>",
                                                              null, entry.getKey()));
            }
            return;
        }
        if (container instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object value : iterable) {
                metadata.validate(run, owner, value, group,
                                  path.appendContainerElement("<list element>", i++, null));
            }
            return;
        }
        if (container.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(container); i++) {
                metadata.validate(run, owner, Array.get(container, i), group,
                                  path.appendContainerElement("<array element>", i, null));
            }
            return;
        }
        Optional<List<ValueExtractorRegistry.ExtractedContainerValue>> extractedValues = run.valueExtractors().extract(container, index);
        if (extractedValues.isPresent()) {
            for (ValueExtractorRegistry.ExtractedContainerValue extractedValue : extractedValues.get()) {
                metadata.validate(run, owner, extractedValue.value(), group,
                                  extractedValue.appendTo(path, "<container element>"));
            }
        }
    }
}
}
