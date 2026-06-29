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

import io.fluxzero.common.Registration;

import java.util.Collection;
import java.util.List;

/**
 * Registers source-generated executable invocation functions.
 * <p>
 * Generated JVM and browser-compatible sources can implement this contract to bind stable executable ids to direct
 * Java functions without preparing reflective or method-handle based invocation handles at runtime.
 */
@FunctionalInterface
public interface GeneratedExecutableInvocationRegistrar {

    /**
     * Registers generated invocations for the requested component names.
     * <p>
     * An empty collection means all invocations known by the registrar should be registered.
     *
     * @param componentNames binary component names to register, or an empty collection for all components
     * @return lifecycle handle for the registered invocations
     */
    Registration register(Collection<String> componentNames);

    /**
     * Registers all invocations known by this registrar.
     */
    default Registration register() {
        return register(List.of());
    }
}
