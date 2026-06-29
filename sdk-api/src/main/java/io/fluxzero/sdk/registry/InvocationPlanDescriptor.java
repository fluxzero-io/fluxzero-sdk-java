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

import java.util.List;
import java.util.Objects;

/**
 * Metadata-only invocation plan for a component executable.
 * <p>
 * This descriptor names the executable and the data needed by generated runtimes to bind parameters and call a
 * generated invoker. It intentionally contains no JVM reflection objects.
 *
 * @param targetComponentName fully-qualified component class name
 * @param executableId stable executable id shared with generated invocation registries
 * @param kind executable kind
 * @param name method or constructor name from component metadata
 * @param returnTypeName erased return type name, or {@code void}
 * @param parameters parameter binding metadata
 * @param propertyAccesses available property access metadata for the target component
 * @param codecHookNames generated codec hook names needed by this executable, if any
 */
public record InvocationPlanDescriptor(
        String targetComponentName,
        String executableId,
        ExecutableKind kind,
        String name,
        String returnTypeName,
        List<ParameterBindingDescriptor> parameters,
        List<PropertyAccessPlanDescriptor> propertyAccesses,
        List<String> codecHookNames) {

    public InvocationPlanDescriptor {
        Objects.requireNonNull(targetComponentName, "targetComponentName");
        Objects.requireNonNull(executableId, "executableId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnTypeName, "returnTypeName");
        parameters = RegistryCollections.immutableList(Objects.requireNonNull(parameters, "parameters"));
        propertyAccesses = RegistryCollections.immutableList(Objects.requireNonNull(propertyAccesses, "propertyAccesses"));
        codecHookNames = RegistryCollections.immutableList(Objects.requireNonNull(codecHookNames, "codecHookNames"));
    }

    /**
     * Returns the stable executable id used by generated invocation registries.
     */
    public static String executableId(
            ExecutableKind kind, String executableName, List<String> parameterTypeNames) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(executableName, "executableName");
        Objects.requireNonNull(parameterTypeNames, "parameterTypeNames");
        parameterTypeNames.forEach(parameterTypeName -> Objects.requireNonNull(
                parameterTypeName, "parameterTypeName"));
        String name = kind == ExecutableKind.CONSTRUCTOR ? "<init>" : executableName;
        return kind.name() + ":" + name + "(" + String.join(",", parameterTypeNames) + ")";
    }
}
