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

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.GeneratedPropertyAccesses;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.registry.PropertyAccess;
import io.fluxzero.sdk.registry.PropertyDescriptor;
import io.fluxzero.sdk.registry.TypeUseDescriptor;

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
        properties().annotatedProperties(ownerType, annotationType)
                .forEach(location -> result.putIfAbsent(
                        propertyName(location), location));
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
                        ? Optional.empty() : properties().annotatedPropertyName(ownerType, annotationType));
    }

    static List<PropertyDescriptor> annotatedProperties(
            Class<?> ownerType, Class<? extends Annotation> annotationType) {
        if (ownerType == null) {
            return List.of();
        }
        List<PropertyDescriptor> metadataProperties = ComponentMetadataLookups.lookup(ownerType)
                .map(lookup -> lookup.properties(ownerType.getName()).stream()
                        .filter(property -> annotation(property.annotations(), annotationType).isPresent())
                        .toList())
                .orElseGet(List::of);
        if (!metadataProperties.isEmpty() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadataProperties;
        }
        return properties().annotatedProperties(ownerType, annotationType).stream()
                .map(location -> new PropertyDescriptor(
                        propertyName(location),
                        propertyType(location).getName(),
                        propertyType(location).getTypeName(),
                        List.of()))
                .toList();
    }

    static Optional<Object> annotatedPropertyValue(
            Object target, Class<? extends Annotation> annotationType) {
        if (target == null) {
            return Optional.empty();
        }
        return annotatedPropertyName(target.getClass(), annotationType)
                .flatMap(propertyName -> readProperty(propertyName, target))
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty() : properties().annotatedPropertyValue(target, annotationType));
    }

    static Collection<Object> annotatedPropertyValues(
            Object target, Class<? extends Annotation> annotationType) {
        if (target == null) {
            return List.of();
        }
        return annotatedPropertyLocations(target.getClass(), annotationType).stream()
                .map(location -> propertyValue(location, target, false))
                .toList();
    }

    static <T> Optional<T> readProperty(String propertyPath, Object target) {
        Optional<Object> generatedValue = readGeneratedProperty(propertyPath, target);
        if (generatedValue.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            @SuppressWarnings("unchecked")
            Optional<T> result = (Optional<T>) generatedValue;
            return result;
        }
        return properties().readProperty(propertyPath, target);
    }

    static boolean hasProperty(String propertyPath, Object target) {
        if (hasGeneratedProperty(propertyPath, target) || ComponentMetadataLookups.generatedOnlyMode()) {
            return hasGeneratedProperty(propertyPath, target);
        }
        return properties().hasProperty(propertyPath, target);
    }

    static void writeProperty(String propertyPath, Object target, Object value) {
        if (writeGeneratedProperty(propertyPath, target, value) || ComponentMetadataLookups.generatedOnlyMode()) {
            return;
        }
        properties().writeProperty(propertyPath, target, value);
    }

    static Object propertyValue(AccessibleObject property, Object target, boolean forceAccess) {
        try {
            return switch (property) {
                case Field field -> {
                    if (forceAccess) {
                        field.setAccessible(true);
                    }
                    yield field.get(target);
                }
                case Method method -> {
                    if (forceAccess) {
                        method.setAccessible(true);
                    }
                    yield method.invoke(target);
                }
                default -> properties().propertyValue(property, target, forceAccess);
            };
        } catch (ReflectiveOperationException e) {
            if (ComponentMetadataLookups.generatedOnlyMode()) {
                throw new IllegalStateException("Could not read generated metadata property location " + property, e);
            }
            return properties().propertyValue(property, target, forceAccess);
        }
    }

    static Object propertyValue(PropertyDescriptor property, Object target) {
        if (property == null || target == null) {
            return null;
        }
        return readProperty(property.name(), target).orElse(null);
    }

    static String propertyName(AccessibleObject property) {
        return switch (property) {
            case Field field -> field.getName();
            case Method method -> propertyName(method);
            default -> properties().propertyName(property);
        };
    }

    static Class<?> propertyType(AccessibleObject property) {
        return switch (property) {
            case Field field -> field.getType();
            case Method method -> method.getReturnType();
            default -> properties().propertyType(property);
        };
    }

    static Class<?> propertyType(Class<?> ownerType, PropertyDescriptor property) {
        if (ownerType == null || property == null) {
            return Object.class;
        }
        return JvmComponentMetadataLookup.classForMetadataName(property.typeName(), ownerType.getClassLoader())
                .orElse(Object.class);
    }

    static Optional<Class<?>> collectionElementType(AccessibleObject property) {
        return ComponentMetadataLookups.generatedOnlyMode()
               ? Optional.empty()
               : properties().collectionElementType(property);
    }

    static Optional<Class<?>> collectionElementType(Class<?> ownerType, PropertyDescriptor property) {
        if (ownerType == null || property == null) {
            return Optional.empty();
        }
        TypeUseDescriptor typeUse = property.typeUse();
        int elementIndex = Map.class.isAssignableFrom(propertyType(ownerType, property)) ? 1 : 0;
        if (typeUse.typeArguments().size() <= elementIndex) {
            return Optional.empty();
        }
        String elementTypeName = typeUse.typeArguments().get(elementIndex).typeName();
        return JvmComponentMetadataLookup.classForMetadataName(elementTypeName, ownerType.getClassLoader())
                .or(() -> Optional.of(Object.class));
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

    static Optional<MemberConfig> member(PropertyDescriptor property) {
        return property == null ? Optional.empty()
                : annotation(property.annotations(), Member.class)
                        .map(annotation -> new MemberConfig(
                                annotation.firstValue("idProperty").orElse(""),
                                annotation.firstValue("wither").orElse("")));
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

    static Optional<AliasConfig> alias(PropertyDescriptor property) {
        return property == null ? Optional.empty()
                : annotation(property.annotations(), Alias.class)
                        .map(annotation -> new AliasConfig(
                                annotation.firstValue("prefix").orElse(""),
                                annotation.firstValue("postfix").orElse("")));
    }

    static Optional<ApplyConfig> apply(Executable executable) {
        if (executable == null) {
            return Optional.empty();
        }
        return MetadataExecutableAnnotationResolver.create().getAnnotation(executable, Apply.class)
                .map(Apply.class::cast)
                .map(annotation -> new ApplyConfig(annotation.disableCompatibilityCheck()));
    }

    static Optional<ApplyConfig> apply(ExecutableView executable) {
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
                   && !properties().annotatedProperties(ownerType, annotationType).isEmpty());
    }

    private static Optional<AccessibleObject> annotatedPropertyLocation(
            Class<?> ownerType, String propertyName, Class<? extends Annotation> annotationType) {
        return propertyLocation(ownerType, propertyName)
                .or(() -> ComponentMetadataLookups.generatedOnlyMode()
                        ? Optional.empty()
                        : properties().annotatedProperties(ownerType, annotationType).stream()
                        .filter(location -> propertyName(location).equals(propertyName))
                        .findFirst());
    }

    private static Optional<AnnotationDescriptor> propertyMetadata(
            AccessibleObject property, Class<? extends Annotation> annotationType) {
        return declaringClass(property).flatMap(ownerType -> ComponentMetadataLookups.lookup(ownerType)
                .flatMap(lookup -> lookup.property(ownerType.getName(), propertyName(property))
                        .flatMap(descriptor -> annotation(descriptor.annotations(), annotationType))));
    }

    private static Optional<Object> readGeneratedProperty(String propertyPath, Object target) {
        if (target == null || propertyPath == null || propertyPath.isBlank()) {
            return Optional.empty();
        }
        Object current = target;
        for (String segment : propertyPath.split("\\.")) {
            if (segment.isBlank() || current == null) {
                return Optional.empty();
            }
            ComponentMetadataLookups.ensureGeneratedExecutions(current.getClass());
            Optional<GeneratedPropertyAccesses.PropertyReader> reader =
                    GeneratedPropertyAccesses.findReader(current.getClass(), segment);
            if (reader.isEmpty()) {
                return Optional.empty();
            }
            current = reader.orElseThrow().read(current);
        }
        return Optional.ofNullable(current);
    }

    private static boolean hasGeneratedProperty(String propertyPath, Object target) {
        if (target == null || propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        Object current = target;
        for (String segment : propertyPath.split("\\.")) {
            if (segment.isBlank() || current == null) {
                return false;
            }
            ComponentMetadataLookups.ensureGeneratedExecutions(current.getClass());
            Optional<GeneratedPropertyAccesses.PropertyReader> reader =
                    GeneratedPropertyAccesses.findReader(current.getClass(), segment);
            if (reader.isEmpty()) {
                return false;
            }
            current = reader.orElseThrow().read(current);
        }
        return true;
    }

    private static boolean writeGeneratedProperty(String propertyPath, Object target, Object value) {
        if (target == null || propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        int separator = propertyPath.lastIndexOf('.');
        Object owner = target;
        String propertyName = propertyPath;
        if (separator >= 0) {
            Optional<Object> nestedOwner = readGeneratedProperty(propertyPath.substring(0, separator), target);
            if (nestedOwner.isEmpty()) {
                return false;
            }
            owner = nestedOwner.orElseThrow();
            propertyName = propertyPath.substring(separator + 1);
        }
        Object writeOwner = owner;
        String writePropertyName = propertyName;
        ComponentMetadataLookups.ensureGeneratedExecutions(writeOwner.getClass());
        return GeneratedPropertyAccesses.findWriter(writeOwner.getClass(), writePropertyName)
                .map(writer -> {
                    writer.write(writeOwner, value);
                    return true;
                })
                .orElse(false);
    }

    private static Optional<AccessibleObject> propertyLocation(Class<?> ownerType, String propertyName) {
        for (Class<?> current = ownerType; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(propertyName);
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (Class<?> current = ownerType; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && propertyName(method).equals(propertyName)) {
                    method.setAccessible(true);
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    private static String propertyName(Method method) {
        String name = method.getName();
        if (method.getParameterCount() == 0 && name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (method.getParameterCount() == 0 && name.startsWith("is") && name.length() > 2) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return name;
    }

    private static PropertyAccess<Class<?>, AccessibleObject> properties() {
        return JvmComponentIntrospector.getInstance();
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
