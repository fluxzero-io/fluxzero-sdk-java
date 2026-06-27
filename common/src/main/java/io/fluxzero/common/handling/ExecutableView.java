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
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata view of a handler executable.
 * <p>
 * JVM code can back this view with a {@link java.lang.reflect.Method} or {@link Constructor}, while generated
 * runtimes can provide the same shape from build-time metadata. Existing reflection APIs remain available as an
 * optional compatibility bridge.
 */
public interface ExecutableView {

    /**
     * Returns a reflection-backed executable view.
     */
    static ExecutableView of(Executable executable) {
        return new ReflectionExecutableView(executable);
    }

    /**
     * Kind of executable.
     */
    Kind kind();

    /**
     * Declaring/target type name.
     */
    String targetTypeName();

    /**
     * Method name, or {@code <init>} for constructors.
     */
    String name();

    /**
     * Return type name, or {@code void}.
     */
    String returnTypeName();

    /**
     * Parameters in declaration order.
     */
    List<? extends ParameterView> parameters();

    /**
     * JVM target class when this view is backed by a loaded class.
     */
    Optional<Class<?>> targetClass();

    /**
     * JVM executable when this view is backed by reflection.
     */
    Optional<Executable> executable();

    /**
     * Direct or projected annotation view for the executable.
     */
    <A extends Annotation> Optional<A> annotation(Class<A> annotationType);

    /**
     * Whether this executable returns a value.
     */
    default boolean hasReturnType() {
        return !"void".equals(returnTypeName());
    }

    /**
     * Returns whether this executable is static.
     */
    default boolean isStatic() {
        return false;
    }

    /**
     * Stable executable id shared with generated invocation registries.
     */
    default String executableId() {
        String parameterTypes = parameters().stream()
                .map(ParameterView::typeName)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return kind().name() + ":" + name() + "(" + parameterTypes + ")";
    }

    /**
     * Executable kind.
     */
    enum Kind {
        METHOD,
        CONSTRUCTOR
    }

    /**
     * Reflection-backed implementation.
     */
    final class ReflectionExecutableView implements ExecutableView {
        private final Executable executable;
        private final List<ParameterView> parameters;

        private ReflectionExecutableView(Executable executable) {
            this.executable = Objects.requireNonNull(executable, "executable");
            Parameter[] reflectionParameters = executable.getParameters();
            this.parameters = Arrays.stream(reflectionParameters)
                    .map(ParameterView::of)
                    .toList();
        }

        @Override
        public Kind kind() {
            return executable instanceof Constructor<?> ? Kind.CONSTRUCTOR : Kind.METHOD;
        }

        @Override
        public String targetTypeName() {
            return typeName(executable.getDeclaringClass());
        }

        @Override
        public String name() {
            return executable instanceof Constructor<?> ? "<init>" : executable.getName();
        }

        @Override
        public String returnTypeName() {
            if (executable instanceof Constructor<?>) {
                return "void";
            }
            return typeName(((Method) executable).getReturnType());
        }

        @Override
        public List<? extends ParameterView> parameters() {
            return parameters;
        }

        @Override
        public Optional<Class<?>> targetClass() {
            return Optional.of(executable.getDeclaringClass());
        }

        @Override
        public Optional<Executable> executable() {
            return Optional.of(executable);
        }

        @Override
        public boolean isStatic() {
            return Modifier.isStatic(executable.getModifiers());
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return Optional.ofNullable(executable.getAnnotation(annotationType));
        }

        private static String typeName(Class<?> type) {
            return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
        }
    }
}
