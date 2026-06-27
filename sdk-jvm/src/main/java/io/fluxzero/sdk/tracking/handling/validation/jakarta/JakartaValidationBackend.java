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

package io.fluxzero.sdk.tracking.handling.validation.jakarta;

import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import jakarta.validation.ConstraintValidator;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JVM-only backend for Jakarta validation provider mechanics.
 * <p>
 * Fluxzero validation policy should be driven by component metadata. Jakarta provider behavior still needs JVM
 * mechanics for validator construction, provider descriptor views, composed constraint annotations, value-extractor
 * introspection, and method override hierarchy compatibility. Keeping those calls here prevents them from looking like
 * generic Fluxzero application semantics.
 */
final class JakartaValidationBackend {
    private static final JakartaValidationBackend INSTANCE = new JakartaValidationBackend();

    private JakartaValidationBackend() {
    }

    static JakartaValidationBackend getInstance() {
        return INSTANCE;
    }

    <T extends ConstraintValidator<?, ?>> T instantiateConstraintValidator(Class<T> key) {
        try {
            Constructor<T> constructor = key.getDeclaredConstructor();
            introspector().ensureAccessible(constructor);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new jakarta.validation.ValidationException(
                    "Could not instantiate constraint validator " + key.getName(), e);
        }
    }

    List<Class<?>> getAllInterfaces(Class<?> type) {
        return introspector().getAllInterfaces(type);
    }

    List<Method> getAllMethods(Class<?> type) {
        return introspector().getAllMethods(type);
    }

    List<Field> fields(Class<?> type) {
        return introspector().getTypeMetadata(type).fields();
    }

    Optional<Field> field(Class<?> type, String name) {
        return introspector().getTypeMetadata(type).field(name);
    }

    Optional<Method> method(Class<?> type, String name) {
        return introspector().getTypeMetadata(type).method(name);
    }

    List<Annotation> typeAnnotations(Class<?> type) {
        return List.copyOf(introspector().getTypeMetadata(type).typeAnnotations());
    }

    MemberInvoker invoker(Class<?> type, Member member, boolean forceAccess) {
        return introspector().getTypeMetadata(type).invoker(member, forceAccess);
    }

    boolean hasReturnType(Executable executable) {
        return introspector().hasReturnType(executable);
    }

    boolean isKotlinReflectionSupported() {
        return introspector().isKotlinReflectionSupported();
    }

    Stream<Method> getMethodOverrideHierarchy(Method method) {
        return introspector().getMethodOverrideHierarchy(method);
    }

    Stream<Parameter> getParameterOverrideHierarchy(Parameter parameter) {
        return introspector().getParameterOverrideHierarchy(parameter);
    }

    String getPropertyName(AccessibleObject property) {
        return introspector().getPropertyName(property);
    }

    Comparator<Class<?>> getClassSpecificityComparator() {
        return introspector().getClassSpecificityComparator();
    }

    Class<?> rawClass(Type type) {
        return introspector().rawClass(type);
    }

    Class<?> box(Class<?> type) {
        return introspector().box(type);
    }

    boolean isLeafValue(Object value) {
        return introspector().isLeafValue(value);
    }

    List<Annotation> getAnnotations(AnnotatedElement element) {
        return introspector().getAnnotations(element);
    }

    <A extends Annotation> Optional<A> getAnnotation(AnnotatedElement element, Class<A> annotationType) {
        return introspector().getAnnotation(element, annotationType);
    }

    <T> Optional<T> getAnnotationAttribute(Annotation annotation, String name, Class<T> expectedType) {
        return introspector().getAnnotationAttribute(annotation, name, expectedType);
    }

    boolean hasNonDefaultAnnotationAttribute(Annotation annotation, String name) {
        return introspector().hasNonDefaultAnnotationAttribute(annotation, name);
    }

    Map<String, Object> getAnnotationAttributes(Annotation annotation) {
        return introspector().getAnnotationAttributes(annotation);
    }

    private static JvmComponentIntrospector introspector() {
        return JvmComponentIntrospector.getInstance();
    }
}
