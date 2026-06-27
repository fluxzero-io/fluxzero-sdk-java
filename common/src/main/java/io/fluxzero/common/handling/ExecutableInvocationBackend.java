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

import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.MemberInvoker;

import java.lang.reflect.Executable;

/**
 * Backend that prepares invocations for handler executables.
 * <p>
 * This keeps Fluxzero handler semantics above the platform-specific invocation mechanism. The default JVM backend uses
 * the existing optimized reflection machinery.
 */
@FunctionalInterface
public interface ExecutableInvocationBackend {

    /**
     * Prepares an invocation handle for the supplied executable.
     */
    ExecutableInvocation prepare(Executable executable);

    /**
     * Returns the default JVM reflection-backed invocation backend.
     */
    static ExecutableInvocationBackend reflection() {
        return executable -> {
            MemberInvoker invoker = DefaultMemberInvoker.asInvoker(executable);
            return invoker::invoke;
        };
    }
}
