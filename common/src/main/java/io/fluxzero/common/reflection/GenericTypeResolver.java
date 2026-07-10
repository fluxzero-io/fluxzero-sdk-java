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

package io.fluxzero.common.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for resolving generic type information in class hierarchies. This class provides methods to resolve and
 * extract generic type details, including parameterized and array types, from a given class type in relation to a
 * target type.
 */
public class GenericTypeResolver {

    /**
     * Creates a parameterized type for a raw class and its actual type arguments.
     */
    public static ParameterizedType parameterize(Class<?> rawType, Type... actualTypeArguments) {
        Objects.requireNonNull(rawType, "rawType");
        Objects.requireNonNull(actualTypeArguments, "actualTypeArguments");
        if (rawType.getTypeParameters().length != actualTypeArguments.length) {
            throw new IllegalArgumentException("Expected %d type arguments for %s but got %d".formatted(
                    rawType.getTypeParameters().length, rawType.getTypeName(), actualTypeArguments.length));
        }
        if (Arrays.stream(actualTypeArguments).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Type arguments must not contain null");
        }
        return new ParameterizedTypeImpl(rawType, actualTypeArguments, rawType.getEnclosingClass());
    }

    public static Type getGenericType(Class<?> clazz, Class<?> target) {
        return resolveType(clazz, target, new HashMap<>());
    }

    static Type resolve(Type type, Class<?> contextType, Class<?> declaringType) {
        if (contextType.equals(declaringType)) {
            return type;
        }
        Type resolvedDeclaringType = getGenericType(contextType, declaringType);
        if (resolvedDeclaringType instanceof ParameterizedType parameterizedType) {
            Map<TypeVariable<?>, Type> typeVariables = new HashMap<>();
            TypeVariable<?>[] parameters = declaringType.getTypeParameters();
            Type[] arguments = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < parameters.length; i++) {
                typeVariables.put(parameters[i], arguments[i]);
            }
            return reifyType(type, typeVariables);
        }
        return type;
    }

    private static Type resolveType(Class<?> clazz, Class<?> target, Map<TypeVariable<?>, Type> typeVarMap) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }

        // Direct superclass
        Type genericSuperclass = clazz.getGenericSuperclass();
        Type resolved = matchAndResolve(genericSuperclass, target, typeVarMap);
        if (resolved != null) {
            return resolved;
        }

        // Interfaces
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            resolved = matchAndResolve(genericInterface, target, typeVarMap);
            if (resolved != null) {
                return resolved;
            }
        }

        // Recurse upward
        return resolveType(clazz.getSuperclass(), target, typeVarMap);
    }

    private static Type matchAndResolve(Type candidate, Class<?> target, Map<TypeVariable<?>, Type> typeVarMap) {
        if (candidate instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) candidate;
            Class<?> rawType = (Class<?>) pt.getRawType();

            if (rawType.equals(target)) {
                return reifyType(pt, typeVarMap);
            }

            Map<TypeVariable<?>, Type> newMap = new HashMap<>(typeVarMap);
            TypeVariable<?>[] vars = rawType.getTypeParameters();
            Type[] args = pt.getActualTypeArguments();
            for (int i = 0; i < vars.length; i++) {
                newMap.put(vars[i], resolveTypeVariable(args[i], typeVarMap));
            }

            return resolveType(rawType, target, newMap);
        } else if (candidate instanceof Class) {
            Class<?> raw = (Class<?>) candidate;
            if (raw.equals(target)) {
                return raw;
            }
            return resolveType(raw, target, typeVarMap);
        }
        return null;
    }

    private static Type reifyType(Type type, Map<TypeVariable<?>, Type> typeVarMap) {
        return reifyType(type, typeVarMap, new HashSet<>());
    }

    private static Type reifyType(Type type, Map<TypeVariable<?>, Type> typeVarMap,
                                  Set<TypeVariable<?>> resolving) {
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type raw = pt.getRawType();
            Type[] args = Arrays.stream(pt.getActualTypeArguments())
                    .map(t -> resolveTypeVariable(t, typeVarMap, new HashSet<>(resolving)))
                    .toArray(Type[]::new);
            return new ParameterizedTypeImpl((Class<?>) raw, args, pt.getOwnerType());
        } else if (type instanceof GenericArrayType) {
            Type component = reifyType(((GenericArrayType) type).getGenericComponentType(), typeVarMap, resolving);
            return Array.newInstance((Class<?>) erase(component), 0).getClass();
        } else if (type instanceof TypeVariable<?>) {
            return resolveTypeVariable(type, typeVarMap, resolving);
        } else {
            return type;
        }
    }

    private static Type resolveTypeVariable(Type type, Map<TypeVariable<?>, Type> typeVarMap) {
        return resolveTypeVariable(type, typeVarMap, new HashSet<>());
    }

    private static Type resolveTypeVariable(Type type, Map<TypeVariable<?>, Type> typeVarMap,
                                            Set<TypeVariable<?>> resolving) {
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> variable = (TypeVariable<?>) type;
            if (!resolving.add(variable)) {
                return variable;
            }
            Type resolved = typeVarMap.get(type);
            if (resolved != null) {
                return resolveTypeVariable(resolved, typeVarMap, resolving);
            } else {
                // Use the bound or Object as fallback
                Type[] bounds = variable.getBounds();
                return bounds.length > 0 ? resolveTypeVariable(bounds[0], typeVarMap, resolving) : Object.class;
            }
        } else if (type instanceof ParameterizedType) {
            return reifyType(type, typeVarMap, resolving);
        } else if (type instanceof GenericArrayType) {
            return reifyType(type, typeVarMap, resolving);
        } else {
            return type;
        }
    }

    private static Type erase(Type type) {
        if (type instanceof Class<?>) {
            return type;
        } else if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getRawType();
        } else if (type instanceof GenericArrayType) {
            return Array.newInstance((Class<?>) erase(((GenericArrayType) type).getGenericComponentType()), 0)
                    .getClass();
        } else if (type instanceof TypeVariable<?>) {
            return Object.class;
        } else {
            throw new IllegalArgumentException("Cannot erase type: " + type);
        }
    }

    private static final class ParameterizedTypeImpl implements ParameterizedType {
        private final Class<?> rawType;
        private final Type[] actualTypeArguments;
        private final Type ownerType;

        private ParameterizedTypeImpl(Class<?> rawType, Type[] actualTypeArguments, Type ownerType) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments.clone();
            this.ownerType = ownerType;
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
            return ownerType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType type
                   && Objects.equals(rawType, type.getRawType())
                   && Objects.equals(ownerType, type.getOwnerType())
                   && Arrays.equals(actualTypeArguments, type.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            // Keep equal reflective types interchangeable with the JDK's ParameterizedType implementations.
            return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(ownerType) ^ Objects.hashCode(rawType);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (ownerType == null) {
                sb.append(rawType.getTypeName());
            } else {
                sb.append(typeName(ownerType)).append('.').append(rawType.getSimpleName());
            }
            if (actualTypeArguments.length > 0) {
                sb.append("<");
                for (int i = 0; i < actualTypeArguments.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(typeName(actualTypeArguments[i]));
                }
                sb.append(">");
            }
            return sb.toString();
        }

        private static String typeName(Type type) {
            return typeName(type, new HashSet<>());
        }

        private static String typeName(Type type, Set<TypeVariable<?>> resolving) {
            if (type instanceof TypeVariable<?> variable) {
                if (!resolving.add(variable)) {
                    return variable.getName();
                }
                Type[] bounds = variable.getBounds();
                if (bounds.length == 0 || bounds.length == 1 && bounds[0].equals(Object.class)) {
                    return variable.getName();
                }
                return variable.getName() + " extends " + Arrays.stream(bounds)
                        .map(bound -> typeName(bound, new HashSet<>(resolving))).collect(Collectors.joining(" & "));
            }
            if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> rawType) {
                String rawName = parameterizedType.getOwnerType() == null
                        ? rawType.getTypeName()
                        : typeName(parameterizedType.getOwnerType(), new HashSet<>(resolving))
                          + "." + rawType.getSimpleName();
                return rawName + Arrays.stream(parameterizedType.getActualTypeArguments())
                        .map(argument -> typeName(argument, new HashSet<>(resolving)))
                        .collect(Collectors.joining(", ", "<", ">"));
            }
            if (type instanceof WildcardType wildcardType) {
                Type[] lowerBounds = wildcardType.getLowerBounds();
                if (lowerBounds.length > 0) {
                    return "? super " + Arrays.stream(lowerBounds)
                            .map(bound -> typeName(bound, new HashSet<>(resolving)))
                            .collect(Collectors.joining(" & "));
                }
                Type[] upperBounds = wildcardType.getUpperBounds();
                if (upperBounds.length == 0 || upperBounds.length == 1 && upperBounds[0].equals(Object.class)) {
                    return "?";
                }
                return "? extends " + Arrays.stream(upperBounds)
                        .map(bound -> typeName(bound, new HashSet<>(resolving)))
                        .collect(Collectors.joining(" & "));
            }
            if (type instanceof GenericArrayType arrayType) {
                return typeName(arrayType.getGenericComponentType(), resolving) + "[]";
            }
            return type.getTypeName();
        }
    }
}
