/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import java.util.function.IntFunction;

/**
 * Prepared invocation handle for one handler executable.
 * <p>
 * The JVM backend can implement this with method handles. Generated runtimes can implement it by calling generated
 * entrypoints while keeping handler matching and parameter resolution semantics unchanged.
 */
@FunctionalInterface
public interface ExecutableInvocation {

    /**
     * Invokes the executable on the supplied target with lazily supplied arguments.
     */
    Object invoke(Object target, int parameterCount, IntFunction<?> parameterProvider);

    /**
     * Invokes the executable without arguments.
     */
    default Object invoke(Object target) {
        return invoke(target, 0, i -> null);
    }

    /**
     * Invokes the executable with one argument.
     */
    default Object invoke(Object target, Object argument) {
        return invoke(target, 1, i -> argument);
    }
}
