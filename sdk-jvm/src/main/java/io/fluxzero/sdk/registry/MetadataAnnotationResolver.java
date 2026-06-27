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

package io.fluxzero.sdk.registry;

import io.fluxzero.common.serialization.JsonUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class MetadataAnnotationResolver {
    private MetadataAnnotationResolver() {
    }

    static Optional<? extends Annotation> annotation(
            List<AnnotationDescriptor> annotations, Class<? extends Annotation> annotationType,
            Class<?> declaringClass) {
        return descriptors(annotations, annotationType).stream()
                .findFirst()
                .map(descriptor -> annotationView(annotationType, descriptor, declaringClass));
    }

    static <T> Optional<T> annotationAs(
            List<AnnotationDescriptor> annotations, Class<? extends Annotation> annotationType,
            Class<T> projectionType, Class<?> declaringClass) {
        return descriptors(annotations, annotationType).stream()
                .findFirst()
                .map(descriptor -> annotationAs(annotationType, projectionType, descriptor, declaringClass));
    }

    static List<AnnotationDescriptor> descriptors(
            List<AnnotationDescriptor> annotations, Class<? extends Annotation> annotationType) {
        String simpleName = annotationType.getSimpleName();
        String qualifiedName = annotationType.getName();
        return annotations.stream()
                .filter(annotation -> annotation.isOrHas(simpleName, qualifiedName))
                .toList();
    }

    static Annotation annotationView(
            Class<? extends Annotation> requestedAnnotationType, AnnotationDescriptor descriptor,
            Class<?> declaringClass) {
        Class<? extends Annotation> annotationType =
                annotationType(requestedAnnotationType, descriptor, declaringClass.getClassLoader());
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "annotationType" -> annotationType;
            case "toString" -> descriptor.toString();
            case "hashCode" -> descriptor.hashCode();
            case "equals" -> proxy == (args == null ? null : args[0]);
            default -> attributeValue(descriptor, annotationType, method, declaringClass);
        };
        return annotationType.cast(Proxy.newProxyInstance(
                annotationType.getClassLoader(), new Class<?>[]{annotationType}, handler));
    }

    @SuppressWarnings("unchecked")
    private static <T> T annotationAs(
            Class<? extends Annotation> annotationType, Class<T> projectionType,
            AnnotationDescriptor descriptor, Class<?> declaringClass) {
        if (annotationType.equals(projectionType)) {
            return (T) annotationView(annotationType, descriptor, declaringClass);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Method method : annotationType.getDeclaredMethods()) {
            attributes.put(method.getName(), attributeValue(descriptor, annotationType, method, declaringClass));
        }
        if (Map.class.equals(projectionType)) {
            return (T) attributes;
        }
        return JsonUtils.convertValue(attributes, projectionType);
    }

    private static Class<? extends Annotation> annotationType(
            Class<? extends Annotation> requestedAnnotationType, AnnotationDescriptor descriptor,
            ClassLoader classLoader) {
        Optional<Class<?>> loadedType = loadClass(descriptor.qualifiedName(), classLoader)
                .filter(Annotation.class::isAssignableFrom);
        if (loadedType.isPresent()) {
            return loadedType.get().asSubclass(Annotation.class);
        }
        return requestedAnnotationType;
    }

    private static Object attributeValue(AnnotationDescriptor descriptor, Class<? extends Annotation> annotationType,
                                         Method method, Class<?> declaringClass) {
        String attributeName = method.getName();
        AnnotationDescriptor source = descriptor.attributes().containsKey(attributeName) ? descriptor
                : metaAnnotation(descriptor, annotationType).orElse(descriptor);
        if (!source.attributes().containsKey(attributeName)) {
            return method.getDefaultValue();
        }
        List<String> values = source.values(attributeName);
        Class<?> returnType = method.getReturnType();
        if (!returnType.isArray()) {
            if (values.isEmpty()) {
                return method.getDefaultValue();
            }
            if (returnType.isAnnotation()) {
                return nestedAnnotationValue(source, attributeName, returnType.asSubclass(Annotation.class),
                                             values.getFirst(), declaringClass)
                        .orElseGet(() -> (Annotation) method.getDefaultValue());
            }
            return value(values.getFirst(), returnType, declaringClass);
        }
        Class<?> componentType = returnType.getComponentType();
        if (componentType.isAnnotation()) {
            List<AnnotationDescriptor> nestedAnnotations = source.nestedAnnotations(attributeName);
            Object result = Array.newInstance(componentType, nestedAnnotations.size());
            for (int i = 0; i < nestedAnnotations.size(); i++) {
                Array.set(result, i, annotationView(
                        componentType.asSubclass(Annotation.class), nestedAnnotations.get(i), declaringClass));
            }
            return result;
        }
        Object result = Array.newInstance(componentType, values.size());
        for (int i = 0; i < values.size(); i++) {
            Array.set(result, i, value(values.get(i), componentType, declaringClass));
        }
        return result;
    }

    private static Optional<AnnotationDescriptor> metaAnnotation(
            AnnotationDescriptor descriptor, Class<? extends Annotation> annotationType) {
        String simpleName = annotationType.getSimpleName();
        String qualifiedName = annotationType.getName();
        return descriptor.metaAnnotations().stream()
                .map(annotation -> annotation.find(simpleName, qualifiedName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Annotation> nestedAnnotationValue(
            AnnotationDescriptor descriptor, String attributeName, Class<? extends Annotation> annotationType,
            String value, Class<?> declaringClass) {
        return descriptor.nestedAnnotations(attributeName).stream()
                .findFirst()
                .or(() -> annotationReference(value))
                .map(nested -> annotationView(annotationType, nested, declaringClass));
    }

    private static Optional<AnnotationDescriptor> annotationReference(String value) {
        if (value == null || !value.startsWith("@")) {
            return Optional.empty();
        }
        String qualifiedName = value.substring(1);
        int attributesStart = qualifiedName.indexOf('(');
        if (attributesStart >= 0) {
            qualifiedName = qualifiedName.substring(0, attributesStart);
        }
        if (qualifiedName.isBlank()) {
            return Optional.empty();
        }
        int simpleNameStart = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('$')) + 1;
        return Optional.of(new AnnotationDescriptor(
                qualifiedName.substring(simpleNameStart), qualifiedName, java.util.Map.of()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(String value, Class<?> targetType, Class<?> declaringClass) {
        if (String.class.equals(targetType)) {
            return value;
        }
        if (Class.class.equals(targetType)) {
            return classValue(value, declaringClass);
        }
        if (boolean.class.equals(targetType) || Boolean.class.equals(targetType)) {
            return Boolean.parseBoolean(value);
        }
        if (int.class.equals(targetType) || Integer.class.equals(targetType)) {
            return Integer.parseInt(value);
        }
        if (long.class.equals(targetType) || Long.class.equals(targetType)) {
            return Long.parseLong(value);
        }
        if (short.class.equals(targetType) || Short.class.equals(targetType)) {
            return Short.parseShort(value);
        }
        if (byte.class.equals(targetType) || Byte.class.equals(targetType)) {
            return Byte.parseByte(value);
        }
        if (double.class.equals(targetType) || Double.class.equals(targetType)) {
            return Double.parseDouble(value);
        }
        if (float.class.equals(targetType) || Float.class.equals(targetType)) {
            return Float.parseFloat(value);
        }
        if (char.class.equals(targetType) || Character.class.equals(targetType)) {
            return value.charAt(0);
        }
        if (targetType.isEnum()) {
            return Enum.valueOf((Class<Enum>) targetType, value);
        }
        return value;
    }

    private static Class<?> classValue(String value, Class<?> declaringClass) {
        return JvmComponentMetadataLookup.classForMetadataName(value)
                .or(() -> loadClass(value, declaringClass.getClassLoader()))
                .orElseThrow(() -> new ComponentRegistryException("Failed to load annotation class value: " + value));
    }

    private static Optional<Class<?>> loadClass(String value, ClassLoader classLoader) {
        try {
            return Optional.of(Class.forName(value, false, classLoader));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
