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

import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
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
        JvmComponentMetadataLookup.scanIfScannable(ownerType)
                .ifPresent(lookup -> lookup.annotatedProperties(ownerType, annotationType)
                        .forEach(property -> annotatedPropertyLocation(ownerType, property.name(), annotationType)
                                .ifPresent(location -> result.putIfAbsent(property.name(), location))));
        JvmComponentIntrospector.getInstance().getAnnotatedProperties(ownerType, annotationType)
                .forEach(location -> result.putIfAbsent(
                        JvmComponentIntrospector.getInstance().getPropertyName(location),
                        (AccessibleObject) location));
        return List.copyOf(result.values());
    }

    static Optional<String> annotatedPropertyName(
            Class<?> ownerType, Class<? extends Annotation> annotationType) {
        if (ownerType == null) {
            return Optional.empty();
        }
        return JvmComponentMetadataLookup.scanIfScannable(ownerType)
                .flatMap(lookup -> lookup.annotatedPropertyName(ownerType, annotationType))
                .or(() -> JvmComponentIntrospector.getInstance().getAnnotatedProperty(ownerType, annotationType)
                        .map(property -> JvmComponentIntrospector.getInstance().getPropertyName(property)));
    }

    static Optional<Object> annotatedPropertyValue(
            Object target, Class<? extends Annotation> annotationType) {
        if (target == null) {
            return Optional.empty();
        }
        return annotatedPropertyName(target.getClass(), annotationType)
                .flatMap(propertyName -> JvmComponentIntrospector.getInstance().readProperty(propertyName, target))
                .or(() -> JvmComponentIntrospector.getInstance().getAnnotatedPropertyValue(target, annotationType));
    }

    static Collection<Object> annotatedPropertyValues(
            Object target, Class<? extends Annotation> annotationType) {
        if (target == null) {
            return List.of();
        }
        return annotatedPropertyLocations(target.getClass(), annotationType).stream()
                .map(location -> JvmComponentIntrospector.getInstance().getValue(location, target, false))
                .toList();
    }

    static boolean hasAnnotatedProperty(Class<?> ownerType, Class<? extends Annotation> annotationType) {
        if (ownerType == null) {
            return false;
        }
        return JvmComponentMetadataLookup.scanIfScannable(ownerType)
                       .map(lookup -> !lookup.annotatedProperties(ownerType, annotationType).isEmpty())
                       .orElse(false)
               || !JvmComponentIntrospector.getInstance().getAnnotatedProperties(ownerType, annotationType).isEmpty();
    }

    private static Optional<AccessibleObject> annotatedPropertyLocation(
            Class<?> ownerType, String propertyName, Class<? extends Annotation> annotationType) {
        return JvmComponentIntrospector.getInstance().getAnnotatedProperties(ownerType, annotationType).stream()
                .filter(location -> JvmComponentIntrospector.getInstance().getPropertyName(location)
                        .equals(propertyName))
                .map(AccessibleObject.class::cast)
                .findFirst();
    }
}
