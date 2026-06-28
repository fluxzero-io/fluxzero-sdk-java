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

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;

final class PeriodicMetadata {
    private static final MetadataExecutableAnnotationResolver EXECUTABLE_ANNOTATIONS =
            MetadataExecutableAnnotationResolver.create();

    private PeriodicMetadata() {
    }

    static List<ScheduleMethod> scheduleMethods(Class<?> targetClass) {
        Optional<List<ScheduleMethod>> metadataMethods = ComponentMetadataLookups.lookup(targetClass)
                .map(lookup -> scheduleMethods(lookup, targetClass));
        if (metadataMethods.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadataMethods.orElseGet(List::of);
        }
        return JvmComponentIntrospector.getInstance().getAnnotatedMethods(targetClass, HandleSchedule.class).stream()
                .filter(method -> method.getParameterCount() > 0)
                .map(method -> new ScheduleMethod(
                        method.toString(), method.getParameters()[0].getType(), executable(method)))
                .toList();
    }

    static Optional<Periodic> executable(Executable executable) {
        return EXECUTABLE_ANNOTATIONS.getAnnotation(executable, Periodic.class).map(Periodic.class::cast);
    }

    static Optional<Periodic> executable(ExecutableView executable) {
        return EXECUTABLE_ANNOTATIONS.getAnnotation(executable, Periodic.class).map(Periodic.class::cast);
    }

    static Optional<Periodic> type(Class<?> type) {
        return ComponentMetadataLookups.typeAnnotation(type, Periodic.class);
    }

    private static List<ScheduleMethod> scheduleMethods(ComponentMetadataLookup lookup, Class<?> targetClass) {
        return lookup.executables(targetClass.getName()).stream()
                .filter(descriptor -> descriptor.kind() == ExecutableKind.METHOD)
                .filter(descriptor -> ComponentMetadataLookups.hasAnnotation(
                        descriptor.annotations(), HandleSchedule.class))
                .filter(descriptor -> !descriptor.parameters().isEmpty())
                .flatMap(descriptor -> scheduleMethod(targetClass, descriptor).stream())
                .toList();
    }

    private static Optional<ScheduleMethod> scheduleMethod(Class<?> targetClass, ExecutableDescriptor descriptor) {
        String payloadTypeName = descriptor.parameters().getFirst().typeName();
        return JvmComponentMetadataLookup.classForMetadataName(payloadTypeName)
                .map(payloadType -> new ScheduleMethod(
                        descriptor.name() + "(" + payloadTypeName + ")",
                        payloadType,
                        periodic(descriptor.annotations(), targetClass)));
    }

    private static Optional<Periodic> periodic(List<AnnotationDescriptor> annotations, Class<?> targetClass) {
        return ComponentMetadataLookups.annotations(annotations, Periodic.class, targetClass).stream().findFirst();
    }

    record ScheduleMethod(String description, Class<?> payloadType, Optional<Periodic> periodic) {
    }
}
