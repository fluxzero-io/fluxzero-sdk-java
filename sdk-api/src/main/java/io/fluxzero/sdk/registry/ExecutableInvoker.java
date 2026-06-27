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

/**
 * Incubating abstraction for invoking executable component handles.
 * <p>
 * JVM execution can invoke reflection handles. Browser/native execution can invoke generated entrypoints. Fluxzero
 * semantics should live above this boundary.
 *
 * @param <E> executable handle
 */
public interface ExecutableInvoker<E> {

    /**
     * Invokes the executable with the supplied target and arguments.
     */
    Object invoke(E executable, Object target, List<?> arguments);
}
