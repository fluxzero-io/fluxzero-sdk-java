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
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM runtime helper for resolving the response type declared by {@link Request}.
 */
public final class RequestTypeResolver {
    private static final Pattern TYPE_TOKEN = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\b");

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
                        .findFirst()
                        .flatMap(component -> metadataResponseType(
                                lookup, component, requestClass.getClassLoader(), Map.of(), new HashSet<>())));
    }

    private static Optional<Type> metadataResponseType(ComponentMetadataLookup lookup, ComponentDescriptor component,
                                                       ClassLoader classLoader, Map<String, String> substitutions,
                                                       Set<String> visiting) {
        String visitKey = component.fullClassName() + substitutions;
        if (!visiting.add(visitKey)) {
            return Optional.empty();
        }
        try {
            for (String superTypeName : component.superTypeNames()) {
                String substitutedSuperType = substituteTypeVariables(superTypeName, substitutions);
                Optional<Type> responseType = requestResponseType(substitutedSuperType, classLoader);
                if (responseType.isPresent()) {
                    return responseType;
                }
                Optional<ComponentDescriptor> superComponent = component(lookup, eraseGeneric(superTypeName), classLoader);
                if (superComponent.isEmpty()) {
                    continue;
                }
                Optional<Type> nested = metadataResponseType(
                        lookup, superComponent.orElseThrow(), classLoader,
                        superTypeSubstitutions(superComponent.orElseThrow(), substitutedSuperType), visiting);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        } finally {
            visiting.remove(visitKey);
        }
    }

    private static Optional<ComponentDescriptor> component(
            ComponentMetadataLookup lookup, String typeName, ClassLoader classLoader) {
        return lookup.component(typeName)
                .or(() -> JvmComponentMetadataLookup.classForMetadataName(typeName, classLoader)
                        .flatMap(type -> typeNames(type).stream()
                                .map(lookup::component)
                                .flatMap(Optional::stream)
                                .findFirst()));
    }

    private static Map<String, String> superTypeSubstitutions(
            ComponentDescriptor superComponent, String superTypeName) {
        List<String> arguments = genericArguments(superTypeName);
        if (arguments.isEmpty()) {
            return Map.of();
        }
        List<String> parameters = inferredTypeParameters(superComponent);
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(parameters.size(), arguments.size()); i++) {
            result.put(parameters.get(i), arguments.get(i));
        }
        return Map.copyOf(result);
    }

    private static List<String> inferredTypeParameters(ComponentDescriptor component) {
        Set<String> result = new LinkedHashSet<>();
        component.superTypeNames().forEach(typeName -> collectTypeVariables(typeName, result));
        return List.copyOf(result);
    }

    private static void collectTypeVariables(String typeName, Set<String> sink) {
        for (String argument : genericArguments(typeName)) {
            String normalized = argument.trim()
                    .replaceFirst("^\\?\\s+extends\\s+", "")
                    .replaceFirst("^\\?\\s+super\\s+", "");
            if (isTypeVariable(normalized)) {
                sink.add(normalized);
            }
            collectTypeVariables(normalized, sink);
        }
    }

    private static boolean isTypeVariable(String typeName) {
        return typeName.matches("[A-Z][A-Za-z0-9_$]*");
    }

    private static String substituteTypeVariables(String typeName, Map<String, String> substitutions) {
        if (substitutions.isEmpty()) {
            return typeName;
        }
        Matcher matcher = TYPE_TOKEN.matcher(typeName);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    substitutions.getOrDefault(matcher.group(), matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
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

    private static List<String> genericArguments(String typeName) {
        int genericStart = typeName.indexOf('<');
        if (genericStart < 0) {
            return List.of();
        }
        int genericEnd = matchingGeneric(typeName, genericStart);
        if (genericEnd < 0) {
            return List.of();
        }
        return splitTopLevel(typeName.substring(genericStart + 1, genericEnd), ',');
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
