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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

/**
 * Adapters from component-registry descriptors to common handler metadata views.
 */
public final class RegistryExecutableViews {
    private static final ConcurrentMap<ExecutableViewKey, ExecutableView> executableViews = new ConcurrentHashMap<>();

    private RegistryExecutableViews() {
    }

    /**
     * Creates an executable metadata view from registry metadata.
     */
    public static ExecutableView executableView(Class<?> targetClass, ExecutableDescriptor descriptor) {
        return executableViews.computeIfAbsent(
                new ExecutableViewKey(targetClass, descriptor),
                key -> new RegistryExecutableView(key.targetClass(), key.descriptor()));
    }

    private static final class ExecutableViewKey {
        private final Class<?> targetClass;
        private final ExecutableDescriptor descriptor;
        private final int hashCode;

        private ExecutableViewKey(Class<?> targetClass, ExecutableDescriptor descriptor) {
            this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.hashCode = 31 * System.identityHashCode(targetClass) + System.identityHashCode(descriptor);
        }

        private Class<?> targetClass() {
            return targetClass;
        }

        private ExecutableDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof ExecutableViewKey that
                                  && targetClass == that.targetClass
                                  && descriptor == that.descriptor;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class RegistryExecutableView implements ExecutableView {
        private final Class<?> targetClass;
        private final ExecutableDescriptor descriptor;
        private final List<ParameterView> parameters;
        private final ConcurrentMap<Class<? extends Annotation>, Optional<? extends Annotation>> annotationCache =
                new ConcurrentHashMap<>();

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
        @SuppressWarnings("unchecked")
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return (Optional<A>) annotationCache.computeIfAbsent(
                    annotationType,
                    type -> MetadataAnnotationResolver.annotationProjection(
                            descriptor.annotations(), type, targetClass));
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
        private final Optional<Class<?>> type;
        private final Optional<Type> genericType;
        private final ConcurrentMap<Class<? extends Annotation>, Optional<? extends Annotation>> annotationCache =
                new ConcurrentHashMap<>();
        private final List<Annotation> annotations;

        private RegistryParameterView(Class<?> targetClass, ParameterDescriptor descriptor, int index) {
            this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.index = index;
            this.type = JvmComponentMetadataLookup.classForMetadataName(descriptor.typeName());
            this.genericType = genericType(descriptor.typeUse(), type);
            this.annotations = ComponentMetadataLookups.annotationViews(descriptor.annotations(), targetClass);
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
            return type;
        }

        @Override
        public Optional<Type> genericType() {
            return genericType;
        }

        @Override
        public Optional<Parameter> parameter() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return (Optional<A>) annotationCache.computeIfAbsent(
                    annotationType,
                    type -> MetadataAnnotationResolver.annotationProjection(
                            descriptor.annotations(), type, targetClass));
        }

        @Override
        public List<Annotation> annotationViews() {
            return annotations;
        }

        private static Optional<Type> genericType(TypeUseDescriptor typeUse, Optional<Class<?>> type) {
            if (typeUse.typeArguments().isEmpty() && typeUse.componentType() == null) {
                return type.map(resolvedType -> resolvedType);
            }
            return typeFrom(typeUse);
        }

        private static Optional<Type> typeFrom(TypeUseDescriptor typeUse) {
            Optional<Class<?>> rawType = JvmComponentMetadataLookup.classForMetadataName(typeUse.typeName());
            if (rawType.isEmpty()) {
                return Optional.empty();
            }
            if (typeUse.typeArguments().isEmpty()) {
                return rawType.map(type -> type);
            }
            Type[] arguments = typeUse.typeArguments().stream()
                    .map(RegistryParameterView::typeFrom)
                    .flatMap(Optional::stream)
                    .toArray(Type[]::new);
            if (arguments.length != typeUse.typeArguments().size()) {
                return rawType.map(type -> type);
            }
            return Optional.of(new RegistryParameterizedType(rawType.orElseThrow(), arguments));
        }
    }

    private record RegistryParameterizedType(Class<?> rawType, Type[] actualTypeArguments)
            implements ParameterizedType {
        private RegistryParameterizedType {
            Objects.requireNonNull(rawType, "rawType");
            actualTypeArguments = actualTypeArguments.clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return rawType.getDeclaringClass();
        }
    }
}
