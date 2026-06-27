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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.registry.PropertyAccess;
import io.fluxzero.sdk.registry.PropertyDescriptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ModelMetadata {
    private static final PropertyAccess<Class<?>, AccessibleObject> PROPERTIES = JvmComponentIntrospector.getInstance();

    private ModelMetadata() {
    }

    static List<AccessibleObject> annotatedPropertyLocations(
            Class<?> ownerType, Class<? extends Annotation> annotationType) {
        if (ownerType == null) {
            return List.of();
        }
        Map<String, AccessibleObject> result = new LinkedHashMap<>();
        ComponentMetadataLookups.lookup(ownerType)
                .ifPresent(lookup -> lookup.properties(ownerType.getName()).stream()
                        .filter(property -> annotation(property.annotations(), annotationType).isPresent())
                        .forEach(property -> annotatedPropertyLocation(ownerType, property.name(), annotationType)
                                .ifPresent(location -> result.putIfAbsent(property.name(), location))));
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            return List.copyOf(result.values());
        }
        PROPERTIES.annotatedProperties(ownerType, annotationType)
                .forEach(location -> result.putIfAbsent(
                        PROPERTIES.propertyName(location), location));
        return List.copyOf(result.values());
    }

    static Optional<String> annotatedPropertyName(
            Class<?> ownerType, Class<? extends Annotation> annotationType) {
        if (ownerType == null) {
            return Optional.empty();
        }
        return ComponentMetadataLookups.lookup(ownerType)
                .flatMap(lookup -> lookup.properties(ownerType.getName()).stream()
                        .filter(property -> annotation(property.annotations(), annotationType).isPresent())
                        .map(PropertyDescriptor::name)
                        .findFirst())
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty() : PROPERTIES.annotatedPropertyName(ownerType, annotationType));
    }

    static Optional<Object> annotatedPropertyValue(
            Object target, Class<? extends Annotation> annotationType) {
        if (target == null) {
            return Optional.empty();
        }
        return annotatedPropertyName(target.getClass(), annotationType)
                .flatMap(propertyName -> PROPERTIES.readProperty(propertyName, target))
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty() : PROPERTIES.annotatedPropertyValue(target, annotationType));
    }

    static Collection<Object> annotatedPropertyValues(
            Object target, Class<? extends Annotation> annotationType) {
        if (target == null) {
            return List.of();
        }
        return annotatedPropertyLocations(target.getClass(), annotationType).stream()
                .map(location -> PROPERTIES.propertyValue(location, target, false))
                .toList();
    }

    static <T> Optional<T> readProperty(String propertyPath, Object target) {
        return PROPERTIES.readProperty(propertyPath, target);
    }

    static boolean hasProperty(String propertyPath, Object target) {
        return PROPERTIES.hasProperty(propertyPath, target);
    }

    static void writeProperty(String propertyPath, Object target, Object value) {
        PROPERTIES.writeProperty(propertyPath, target, value);
    }

    static Object propertyValue(AccessibleObject property, Object target, boolean forceAccess) {
        return PROPERTIES.propertyValue(property, target, forceAccess);
    }

    static String propertyName(AccessibleObject property) {
        return PROPERTIES.propertyName(property);
    }

    static Class<?> propertyType(AccessibleObject property) {
        return PROPERTIES.propertyType(property);
    }

    static Optional<Class<?>> collectionElementType(AccessibleObject property) {
        return PROPERTIES.collectionElementType(property);
    }

    static Optional<MemberConfig> member(AccessibleObject property) {
        return propertyMetadata(property, Member.class)
                .map(annotation -> new MemberConfig(
                        annotation.firstValue("idProperty").orElse(""),
                        annotation.firstValue("wither").orElse("")))
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty() : JvmComponentIntrospector.getInstance().getAnnotation(property, Member.class)
                        .map(annotation -> new MemberConfig(annotation.idProperty(), annotation.wither())));
    }

    static Optional<AliasConfig> alias(AccessibleObject property) {
        return propertyMetadata(property, Alias.class)
                .map(annotation -> new AliasConfig(
                        annotation.firstValue("prefix").orElse(""),
                        annotation.firstValue("postfix").orElse("")))
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty() : JvmComponentIntrospector.getInstance().getAnnotation(property, Alias.class)
                        .map(annotation -> new AliasConfig(annotation.prefix(), annotation.postfix())));
    }

    static Optional<ApplyConfig> apply(Executable executable) {
        if (executable == null) {
            return Optional.empty();
        }
        return MetadataExecutableAnnotationResolver.create().getAnnotation(executable, Apply.class)
                .map(Apply.class::cast)
                .map(annotation -> new ApplyConfig(annotation.disableCompatibilityCheck()));
    }

    static boolean hasAnnotatedProperty(Class<?> ownerType, Class<? extends Annotation> annotationType) {
        if (ownerType == null) {
            return false;
        }
        return ComponentMetadataLookups.lookup(ownerType)
                       .map(lookup -> lookup.properties(ownerType.getName()).stream()
                               .anyMatch(property -> annotation(property.annotations(), annotationType).isPresent()))
                       .orElse(false)
               || (!ComponentMetadataLookups.generatedOnlyMode()
                   && !PROPERTIES.annotatedProperties(ownerType, annotationType).isEmpty());
    }

    private static Optional<AccessibleObject> annotatedPropertyLocation(
            Class<?> ownerType, String propertyName, Class<? extends Annotation> annotationType) {
        return JvmComponentIntrospector.getInstance().getProperty(ownerType, propertyName)
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty()
                        : PROPERTIES.annotatedProperties(ownerType, annotationType).stream()
                        .filter(location -> PROPERTIES.propertyName(location).equals(propertyName))
                        .findFirst());
    }

    private static Optional<AnnotationDescriptor> propertyMetadata(
            AccessibleObject property, Class<? extends Annotation> annotationType) {
        return declaringClass(property).flatMap(ownerType -> ComponentMetadataLookups.lookup(ownerType)
                .flatMap(lookup -> lookup.property(ownerType.getName(), PROPERTIES.propertyName(property))
                        .flatMap(descriptor -> annotation(descriptor.annotations(), annotationType))));
    }

    private static Optional<AnnotationDescriptor> annotation(
            List<AnnotationDescriptor> annotations, Class<? extends Annotation> annotationType) {
        return annotations.stream()
                .filter(annotation -> annotation.isOrHas(annotationType.getSimpleName(), annotationType.getName()))
                .findFirst();
    }

    private static Optional<Class<?>> declaringClass(AccessibleObject property) {
        return switch (property) {
            case Field field -> Optional.of(field.getDeclaringClass());
            case Method method -> Optional.of(method.getDeclaringClass());
            default -> Optional.empty();
        };
    }

    record MemberConfig(String idProperty, String wither) {
    }

    record AliasConfig(String prefix, String postfix) {
    }

    record ApplyConfig(boolean disableCompatibilityCheck) {
    }
}
