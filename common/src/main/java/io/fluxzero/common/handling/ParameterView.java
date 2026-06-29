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

package io.fluxzero.common.handling;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata view of one executable parameter.
 * <p>
 * This lets handler matching and parameter binding talk about parameters without requiring every runtime backend to
 * expose a JVM {@link Parameter}.
 */
public interface ParameterView {

    /**
     * Returns a reflection-backed parameter view.
     */
    static ParameterView of(Parameter parameter) {
        return new ReflectionParameterView(parameter);
    }

    /**
     * Zero-based parameter index.
     */
    int index();

    /**
     * Parameter name.
     */
    String name();

    /**
     * Erased parameter type name.
     */
    String typeName();

    /**
     * JVM parameter type when available.
     */
    Optional<Class<?>> type();

    /**
     * Generic JVM parameter type when available.
     */
    default Optional<Type> genericType() {
        return type().map(type -> type);
    }

    /**
     * JVM parameter when this view is backed by reflection.
     */
    Optional<Parameter> parameter();

    /**
     * Direct or projected annotation view for the parameter.
     */
    <A extends Annotation> Optional<A> annotation(Class<A> annotationType);

    /**
     * Direct or projected annotation views for the parameter.
     */
    default List<Annotation> annotationViews() {
        return parameter().map(parameter -> List.of(parameter.getAnnotations()))
                .orElseGet(List::of);
    }

    /**
     * Returns whether a runtime value can be assigned to this parameter.
     */
    default boolean isAssignableFrom(Object value) {
        return value == null || type().map(type -> type.isAssignableFrom(value.getClass())).orElse(true);
    }

    /**
     * Reflection-backed implementation.
     */
    final class ReflectionParameterView implements ParameterView {
        private final Parameter parameter;
        private final int index;

        private ReflectionParameterView(Parameter parameter) {
            this.parameter = Objects.requireNonNull(parameter, "parameter");
            this.index = parameterIndex(parameter);
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public String name() {
            return parameter.getName();
        }

        @Override
        public String typeName() {
            return typeName(parameter.getType());
        }

        @Override
        public Optional<Class<?>> type() {
            return Optional.of(parameter.getType());
        }

        @Override
        public Optional<Type> genericType() {
            return Optional.of(parameter.getParameterizedType());
        }

        @Override
        public Optional<Parameter> parameter() {
            return Optional.of(parameter);
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return Optional.ofNullable(parameter.getAnnotation(annotationType));
        }

        @Override
        public List<Annotation> annotationViews() {
            return List.of(parameter.getAnnotations());
        }

        private static int parameterIndex(Parameter parameter) {
            Executable executable = parameter.getDeclaringExecutable();
            Parameter[] parameters = executable.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] == parameter || parameters[i].equals(parameter)) {
                    return i;
                }
            }
            return Arrays.asList(parameters).indexOf(parameter);
        }

        private static String typeName(Class<?> type) {
            return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
        }
    }
}
