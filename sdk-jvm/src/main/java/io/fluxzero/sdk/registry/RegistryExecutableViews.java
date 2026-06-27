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

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterView;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Adapters from component-registry descriptors to common handler metadata views.
 */
public final class RegistryExecutableViews {
    private RegistryExecutableViews() {
    }

    /**
     * Creates an executable metadata view from registry metadata.
     */
    public static ExecutableView executableView(Class<?> targetClass, ExecutableDescriptor descriptor) {
        return new RegistryExecutableView(targetClass, descriptor);
    }

    private static final class RegistryExecutableView implements ExecutableView {
        private final Class<?> targetClass;
        private final ExecutableDescriptor descriptor;
        private final List<ParameterView> parameters;

        private RegistryExecutableView(Class<?> targetClass, ExecutableDescriptor descriptor) {
            this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.parameters = IntStream.range(0, descriptor.parameters().size())
                    .mapToObj(index -> new RegistryParameterView(targetClass, descriptor.parameters().get(index), index))
                    .<ParameterView>map(parameter -> parameter)
                    .toList();
        }

        @Override
        public Kind kind() {
            return descriptor.kind() == ExecutableKind.CONSTRUCTOR ? Kind.CONSTRUCTOR : Kind.METHOD;
        }

        @Override
        public String targetTypeName() {
            return targetClass.getName();
        }

        @Override
        public String name() {
            return kind() == Kind.CONSTRUCTOR ? "<init>" : descriptor.name();
        }

        @Override
        public String returnTypeName() {
            return descriptor.returnTypeName();
        }

        @Override
        public List<? extends ParameterView> parameters() {
            return parameters;
        }

        @Override
        public Optional<Class<?>> targetClass() {
            return Optional.of(targetClass);
        }

        @Override
        public Optional<Executable> executable() {
            return Optional.empty();
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return MetadataAnnotationResolver.annotation(descriptor.annotations(), annotationType, targetClass)
                    .filter(annotationType::isInstance)
                    .map(annotationType::cast);
        }

        @Override
        public boolean isStatic() {
            return descriptor.isStatic();
        }
    }

    private static final class RegistryParameterView implements ParameterView {
        private final Class<?> targetClass;
        private final ParameterDescriptor descriptor;
        private final int index;

        private RegistryParameterView(Class<?> targetClass, ParameterDescriptor descriptor, int index) {
            this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.index = index;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public String name() {
            return descriptor.name();
        }

        @Override
        public String typeName() {
            return descriptor.typeName();
        }

        @Override
        public Optional<Class<?>> type() {
            return JvmComponentMetadataLookup.classForMetadataName(descriptor.typeName());
        }

        @Override
        public Optional<Parameter> parameter() {
            return Optional.empty();
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return MetadataAnnotationResolver.annotation(descriptor.annotations(), annotationType, targetClass)
                    .filter(annotationType::isInstance)
                    .map(annotationType::cast);
        }
    }
}
