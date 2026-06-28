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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JVM runtime helper for resolving the response type declared by {@link Request}.
 */
public final class RequestTypeResolver {

    private RequestTypeResolver() {
    }

    /**
     * Resolves the declared response type for a request instance.
     *
     * @param request request instance
     * @return declared response type, or {@link Object} when it cannot be resolved
     */
    public static Type responseType(Request<?> request) {
        Optional<Type> metadataType = metadataResponseType(request);
        if (metadataType.isPresent()) {
            return metadataType.orElseThrow();
        }
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            return Object.class;
        }
        Type genericType = JvmCompatibilityBackend.introspector().getGenericType(request.getClass(), Request.class);
        if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
            return pt.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private static Optional<Type> metadataResponseType(Request<?> request) {
        Class<?> requestClass = request.getClass();
        return ComponentMetadataLookups.lookup(requestClass)
                .flatMap(lookup -> typeNames(requestClass).stream()
                        .map(lookup::component)
                        .flatMap(Optional::stream)
                        .findFirst())
                .flatMap(component -> component.superTypeNames().stream()
                        .flatMap(superType -> requestResponseType(superType, requestClass.getClassLoader()).stream())
                        .findFirst());
    }

    private static Optional<Type> requestResponseType(String superTypeName, ClassLoader classLoader) {
        String requestTypeName = Request.class.getName();
        if (!eraseGeneric(superTypeName).equals(requestTypeName)) {
            return Optional.empty();
        }
        int genericStart = superTypeName.indexOf('<');
        int genericEnd = superTypeName.lastIndexOf('>');
        if (genericStart < 0 || genericEnd <= genericStart) {
            return Optional.empty();
        }
        return metadataType(superTypeName.substring(genericStart + 1, genericEnd), classLoader);
    }

    private static Optional<Type> metadataType(String typeName, ClassLoader classLoader) {
        String text = typeName.trim()
                .replace("...", "[]")
                .replaceFirst("^\\?\\s+extends\\s+", "")
                .replaceFirst("^\\?\\s+super\\s+", "");
        if (text.endsWith("[]")) {
            return metadataType(text.substring(0, text.length() - 2), classLoader)
                    .map(type -> type instanceof Class<?> componentType
                            ? Array.newInstance(componentType, 0).getClass()
                            : Object[].class);
        }
        int genericStart = text.indexOf('<');
        if (genericStart < 0) {
            return JvmComponentMetadataLookup.classForMetadataName(text, classLoader).map(Type.class::cast);
        }
        int genericEnd = matchingGeneric(text, genericStart);
        if (genericEnd < 0) {
            return Optional.empty();
        }
        Optional<Class<?>> rawType = JvmComponentMetadataLookup.classForMetadataName(
                text.substring(0, genericStart).trim(), classLoader);
        if (rawType.isEmpty()) {
            return Optional.empty();
        }
        List<Type> arguments = new ArrayList<>();
        for (String argument : splitTopLevel(text.substring(genericStart + 1, genericEnd), ',')) {
            arguments.add(metadataType(argument, classLoader).orElse(Object.class));
        }
        return Optional.of(new MetadataParameterizedType(rawType.orElseThrow(), arguments));
    }

    private static List<String> typeNames(Class<?> type) {
        List<String> result = new ArrayList<>();
        result.add(type.getName());
        if (type.getCanonicalName() != null && !type.getCanonicalName().equals(type.getName())) {
            result.add(type.getCanonicalName());
        }
        return List.copyOf(result);
    }

    private static String eraseGeneric(String typeName) {
        int genericStart = typeName.indexOf('<');
        return genericStart < 0 ? typeName : typeName.substring(0, genericStart);
    }

    private static int matchingGeneric(String source, int start) {
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String source, char separator) {
        List<String> result = new ArrayList<>();
        int generic = 0;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == separator && generic == 0) {
                result.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = source.substring(start).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private record MetadataParameterizedType(Class<?> rawType, List<Type> arguments) implements ParameterizedType {
        private MetadataParameterizedType {
            arguments = List.copyOf(arguments);
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.toArray(Type[]::new);
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
