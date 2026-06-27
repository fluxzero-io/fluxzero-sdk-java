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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.registry.ParameterDescriptor;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

final class PeriodicMetadata {
    private static final MetadataExecutableAnnotationResolver EXECUTABLE_ANNOTATIONS =
            MetadataExecutableAnnotationResolver.create();

    private PeriodicMetadata() {
    }

    static List<Method> scheduleMethods(Class<?> targetClass) {
        Optional<List<Method>> metadataMethods = ComponentMetadataLookups.lookup(targetClass)
                .map(lookup -> scheduleMethods(lookup, targetClass));
        if (metadataMethods.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadataMethods.orElseGet(List::of);
        }
        return JvmComponentIntrospector.getInstance().getAnnotatedMethods(targetClass, HandleSchedule.class);
    }

    static Optional<Periodic> executable(Executable executable) {
        return EXECUTABLE_ANNOTATIONS.getAnnotation(executable, Periodic.class).map(Periodic.class::cast);
    }

    static Optional<Periodic> type(Class<?> type) {
        return ComponentMetadataLookups.typeAnnotation(type, Periodic.class);
    }

    private static List<Method> scheduleMethods(ComponentMetadataLookup lookup, Class<?> targetClass) {
        List<ExecutableDescriptor> descriptors = lookup.executables(targetClass.getName()).stream()
                .filter(descriptor -> descriptor.kind() == ExecutableKind.METHOD)
                .filter(descriptor -> ComponentMetadataLookups.hasAnnotation(
                        descriptor.annotations(), HandleSchedule.class))
                .toList();
        if (descriptors.isEmpty()) {
            return List.of();
        }
        return JvmComponentIntrospector.getInstance().getAllMethods(targetClass).stream()
                .filter(method -> descriptors.stream().anyMatch(descriptor -> matches(descriptor, method)))
                .toList();
    }

    private static boolean matches(ExecutableDescriptor descriptor, Method method) {
        return descriptor.name().equals(method.getName())
               && descriptor.parameters().stream().map(ParameterDescriptor::typeName).toList()
                       .equals(List.of(method.getParameterTypes()).stream().map(PeriodicMetadata::typeName).toList());
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }
}
