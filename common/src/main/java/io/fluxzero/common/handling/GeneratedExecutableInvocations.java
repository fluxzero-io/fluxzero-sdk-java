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

package io.fluxzero.common.handling;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for generated executable invocations.
 * <p>
 * Generated JVM or browser-adjacent code can register a direct invocation function for a handler/model/caster
 * executable. JVM runtime code can then prefer that function over reflective invocation while preserving the same
 * handler matching and result/error semantics.
 */
public final class GeneratedExecutableInvocations {
    private static final ConcurrentMap<Key, Deque<ExecutableInvocation>> INVOCATIONS = new ConcurrentHashMap<>();

    private GeneratedExecutableInvocations() {
    }

    /**
     * Registers a generated invocation for a target class and executable id.
     */
    public static synchronized Registration register(
            Class<?> targetClass, String executableId, ExecutableInvocation invocation) {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(executableId, "executableId");
        Objects.requireNonNull(invocation, "invocation");
        Key key = new Key(targetClass, executableId);
        INVOCATIONS.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(invocation);
        return new Registration(key, invocation);
    }

    /**
     * Finds a generated invocation for the supplied JVM executable.
     */
    public static Optional<ExecutableInvocation> find(Executable executable) {
        Objects.requireNonNull(executable, "executable");
        return find(executable.getDeclaringClass(), executableId(executable));
    }

    /**
     * Finds a generated invocation by target class and executable id.
     */
    public static Optional<ExecutableInvocation> find(Class<?> targetClass, String executableId) {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(executableId, "executableId");
        return find(new Key(targetClass, executableId));
    }

    private static synchronized Optional<ExecutableInvocation> find(Key key) {
        Deque<ExecutableInvocation> invocations = INVOCATIONS.get(key);
        return invocations == null || invocations.isEmpty()
                ? Optional.empty() : Optional.of(invocations.peekLast());
    }

    /**
     * Returns the stable executable id used by generated invocation plans.
     */
    public static String executableId(Executable executable) {
        Objects.requireNonNull(executable, "executable");
        String kind = executable instanceof Constructor<?> ? "CONSTRUCTOR" : "METHOD";
        String name = executable instanceof Constructor<?> ? "<init>" : executable.getName();
        String parameters = Arrays.stream(executable.getParameterTypes())
                .map(GeneratedExecutableInvocations::typeName)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return kind + ":" + name + "(" + parameters + ")";
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private record Key(Class<?> targetClass, String executableId) {
    }

    /**
     * Registration handle for a generated invocation.
     */
    public static final class Registration implements AutoCloseable {
        private final Key key;
        private final ExecutableInvocation invocation;

        private Registration(Key key, ExecutableInvocation invocation) {
            this.key = Objects.requireNonNull(key, "key");
            this.invocation = Objects.requireNonNull(invocation, "invocation");
        }

        @Override
        public synchronized void close() {
            Deque<ExecutableInvocation> invocations = INVOCATIONS.get(key);
            if (invocations == null) {
                return;
            }
            for (Iterator<ExecutableInvocation> iterator = invocations.descendingIterator(); iterator.hasNext(); ) {
                if (iterator.next() == invocation) {
                    iterator.remove();
                    break;
                }
            }
            if (invocations.isEmpty()) {
                INVOCATIONS.remove(key, invocations);
            }
        }
    }
}
