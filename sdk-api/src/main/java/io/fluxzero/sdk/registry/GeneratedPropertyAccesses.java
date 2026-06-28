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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for generated property read/write functions.
 * <p>
 * Generated JVM, browser, or source-lowered code can register direct property accessors by stable component type and
 * property name. Runtime code can then consume property access plans without depending on reflective fields or getters
 * for Fluxzero app semantics.
 */
public final class GeneratedPropertyAccesses {
    private static final ConcurrentMap<Key, Deque<PropertyReader>> READERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Key, Deque<PropertyWriter>> WRITERS = new ConcurrentHashMap<>();

    private GeneratedPropertyAccesses() {
    }

    /**
     * Registers a generated property reader for a target class.
     */
    public static Registration registerReader(Class<?> targetClass, String propertyName, PropertyReader reader) {
        Objects.requireNonNull(targetClass, "targetClass");
        return registerReader(typeName(targetClass), propertyName, reader);
    }

    /**
     * Registers a generated property reader for a target type name.
     */
    public static synchronized Registration registerReader(
            String targetTypeName, String propertyName, PropertyReader reader) {
        Key key = key(targetTypeName, propertyName);
        Objects.requireNonNull(reader, "reader");
        READERS.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(reader);
        return new Registration(key, reader, null);
    }

    /**
     * Registers a generated property writer for a target class.
     */
    public static Registration registerWriter(Class<?> targetClass, String propertyName, PropertyWriter writer) {
        Objects.requireNonNull(targetClass, "targetClass");
        return registerWriter(typeName(targetClass), propertyName, writer);
    }

    /**
     * Registers a generated property writer for a target type name.
     */
    public static synchronized Registration registerWriter(
            String targetTypeName, String propertyName, PropertyWriter writer) {
        Key key = key(targetTypeName, propertyName);
        Objects.requireNonNull(writer, "writer");
        WRITERS.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(writer);
        return new Registration(key, null, writer);
    }

    /**
     * Finds a generated property reader for a target class.
     */
    public static Optional<PropertyReader> findReader(Class<?> targetClass, String propertyName) {
        Objects.requireNonNull(targetClass, "targetClass");
        return typeNames(targetClass).stream()
                .flatMap(typeName -> findReader(typeName, propertyName).stream())
                .findFirst();
    }

    /**
     * Finds a generated property reader for a target type name.
     */
    public static Optional<PropertyReader> findReader(String targetTypeName, String propertyName) {
        return findReader(key(targetTypeName, propertyName));
    }

    /**
     * Finds a generated property writer for a target class.
     */
    public static Optional<PropertyWriter> findWriter(Class<?> targetClass, String propertyName) {
        Objects.requireNonNull(targetClass, "targetClass");
        return typeNames(targetClass).stream()
                .flatMap(typeName -> findWriter(typeName, propertyName).stream())
                .findFirst();
    }

    /**
     * Finds a generated property writer for a target type name.
     */
    public static Optional<PropertyWriter> findWriter(String targetTypeName, String propertyName) {
        return findWriter(key(targetTypeName, propertyName));
    }

    private static synchronized Optional<PropertyReader> findReader(Key key) {
        Deque<PropertyReader> readers = READERS.get(key);
        return readers == null || readers.isEmpty() ? Optional.empty() : Optional.of(readers.peekLast());
    }

    private static synchronized Optional<PropertyWriter> findWriter(Key key) {
        Deque<PropertyWriter> writers = WRITERS.get(key);
        return writers == null || writers.isEmpty() ? Optional.empty() : Optional.of(writers.peekLast());
    }

    private static Key key(String targetTypeName, String propertyName) {
        Objects.requireNonNull(targetTypeName, "targetTypeName");
        Objects.requireNonNull(propertyName, "propertyName");
        if (targetTypeName.isBlank()) {
            throw new IllegalArgumentException("Target type name must not be blank");
        }
        if (propertyName.isBlank()) {
            throw new IllegalArgumentException("Property name must not be blank");
        }
        return new Key(targetTypeName, propertyName);
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private static LinkedHashSet<String> typeNames(Class<?> type) {
        LinkedHashSet<Class<?>> candidates = new LinkedHashSet<>();
        collectTypeCandidates(type, candidates);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        candidates.forEach(candidate -> {
            result.add(typeName(candidate));
            result.add(candidate.getName());
        });
        return result;
    }

    private static void collectTypeCandidates(Class<?> type, LinkedHashSet<Class<?>> result) {
        if (type == null || Object.class.equals(type) || !result.add(type)) {
            return;
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectTypeCandidates(interfaceType, result);
        }
        collectTypeCandidates(type.getSuperclass(), result);
    }

    private record Key(String targetTypeName, String propertyName) {
    }

    /**
     * Generated property reader.
     */
    @FunctionalInterface
    public interface PropertyReader {
        Object read(Object target);
    }

    /**
     * Generated property writer.
     */
    @FunctionalInterface
    public interface PropertyWriter {
        void write(Object target, Object value);
    }

    /**
     * Registration handle for generated property access.
     */
    public static final class Registration implements AutoCloseable {
        private final Key key;
        private final PropertyReader reader;
        private final PropertyWriter writer;

        private Registration(Key key, PropertyReader reader, PropertyWriter writer) {
            this.key = Objects.requireNonNull(key, "key");
            this.reader = reader;
            this.writer = writer;
        }

        @Override
        public synchronized void close() {
            if (reader != null) {
                remove(READERS, key, reader);
            }
            if (writer != null) {
                remove(WRITERS, key, writer);
            }
        }

        private static <T> void remove(ConcurrentMap<Key, Deque<T>> map, Key key, T value) {
            Deque<T> values = map.get(key);
            if (values == null) {
                return;
            }
            for (Iterator<T> iterator = values.descendingIterator(); iterator.hasNext(); ) {
                if (iterator.next() == value) {
                    iterator.remove();
                    break;
                }
            }
            if (values.isEmpty()) {
                map.remove(key, values);
            }
        }
    }
}
