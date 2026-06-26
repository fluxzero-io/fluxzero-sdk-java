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
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.valueextraction.ValueExtractorDefinitionException;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class ValueExtractorRegistry {
    private static final ValueExtractorRegistry EMPTY = new ValueExtractorRegistry(List.of());

    private final List<ValueExtractorDescriptor> extractors;

    ValueExtractorRegistry(Collection<ValueExtractor<?>> extractors) {
        this.extractors = extractors.stream().map(ValueExtractorDescriptor::of).toList();
    }

    static ValueExtractorRegistry empty() {
        return EMPTY;
    }

    ValueExtractorRegistry plus(ValueExtractor<?> extractor) {
        List<ValueExtractor<?>> values = new ArrayList<>(extractors.stream().map(ValueExtractorDescriptor::extractor)
                                                           .toList());
        values.add(extractor);
        return new ValueExtractorRegistry(values);
    }

    Optional<List<ExtractedContainerValue>> extract(Object container, int typeArgumentIndex) {
        if (container == null || extractors.isEmpty()) {
            return Optional.empty();
        }
        return extractor(container.getClass(), typeArgumentIndex).map(extractor -> extractor.extract(container));
    }

    private Optional<ValueExtractorDescriptor> extractor(Class<?> containerType, int typeArgumentIndex) {
        return extractors.stream()
                .filter(extractor -> extractor.typeArgumentIndex() == typeArgumentIndex)
                .filter(extractor -> extractor.containerClass().isAssignableFrom(containerType))
                .min(Comparator.comparing(ValueExtractorDescriptor::containerClass,
                                          ReflectionUtils.getClassSpecificityComparator()));
    }

record ValueExtractorDescriptor(ValueExtractor<?> extractor, Class<?> containerClass, int typeArgumentIndex) {
    static ValueExtractorDescriptor of(ValueExtractor<?> extractor) {
        AnnotatedType extractedType = valueExtractorType(extractor.getClass()).orElseThrow(
                () -> new ValueExtractorDefinitionException("Could not determine extracted type for "
                                                            + extractor.getClass().getName()));
        Type containerType = extractedType.getType();
        Class<?> containerClass = ReflectionUtils.rawClass(containerType);
        int index = extractedValueIndex(extractedType);
        return new ValueExtractorDescriptor(extractor, containerClass, index);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    List<ExtractedContainerValue> extract(Object container) {
        CapturingValueReceiver receiver = new CapturingValueReceiver();
        ((ValueExtractor) extractor).extractValues(container, receiver);
        return List.copyOf(receiver.values);
    }

    private static Optional<AnnotatedType> valueExtractorType(Class<?> type) {
        for (AnnotatedType candidate : type.getAnnotatedInterfaces()) {
            Optional<AnnotatedType> match = valueExtractorType(candidate);
            if (match.isPresent()) {
                return match;
            }
        }
        AnnotatedType superclass = type.getAnnotatedSuperclass();
        if (superclass != null) {
            Optional<AnnotatedType> match = valueExtractorType(superclass);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private static Optional<AnnotatedType> valueExtractorType(AnnotatedType type) {
        if (type.getType() instanceof ParameterizedType parameterizedType
            && parameterizedType.getRawType() == ValueExtractor.class
            && type instanceof AnnotatedParameterizedType parameterized) {
            return Optional.of(parameterized.getAnnotatedActualTypeArguments()[0]);
        }
        Class<?> rawClass = ReflectionUtils.rawClass(type.getType());
        return rawClass == Object.class ? Optional.empty() : valueExtractorType(rawClass);
    }

    private static int extractedValueIndex(AnnotatedType extractedType) {
        if (extractedType instanceof AnnotatedParameterizedType parameterizedType) {
            AnnotatedType[] arguments = parameterizedType.getAnnotatedActualTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (ReflectionUtils.getAnnotation(arguments[i], ExtractedValue.class).isPresent()) {
                    return i;
                }
            }
        }
        if (ReflectionUtils.getAnnotation(extractedType, ExtractedValue.class).isPresent()) {
            return 0;
        }
        throw new ValueExtractorDefinitionException("ValueExtractor must mark one extracted value with @ExtractedValue");
    }
}

record ExtractedContainerValue(@Nullable String nodeName, Object value,
                               @Nullable Integer index, @Nullable Object key, boolean iterable) {
    ValidationPath appendTo(ValidationPath path, String defaultNodeName) {
        String name = nodeName == null ? defaultNodeName : nodeName;
        return iterable ? path.appendContainerElement(name, index, key) : path.appendContainerValue(name);
    }
}

static final class CapturingValueReceiver implements ValueExtractor.ValueReceiver {
    final List<ExtractedContainerValue> values = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public void value(String nodeName, Object object) {
        values.add(new ExtractedContainerValue(nodeName, object, null, null, false));
    }

    /** {@inheritDoc} */
    @Override
    public void iterableValue(String nodeName, Object object) {
        values.add(new ExtractedContainerValue(nodeName, object, null, null, true));
    }

    /** {@inheritDoc} */
    @Override
    public void indexedValue(String nodeName, int index, Object object) {
        values.add(new ExtractedContainerValue(nodeName, object, index, null, true));
    }

    /** {@inheritDoc} */
    @Override
    public void keyedValue(String nodeName, Object key, Object object) {
        values.add(new ExtractedContainerValue(nodeName, object, null, key, true));
    }
}
}
