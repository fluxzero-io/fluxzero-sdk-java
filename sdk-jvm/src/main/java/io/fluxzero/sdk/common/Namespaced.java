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
 *
 */

package io.fluxzero.sdk.common;

public interface Namespaced<T> {

    /**
     * Returns the resource scoped to the default namespace.
     *
     * @return the resource which is associated with the default namespace
     */
    default T forDefaultNamespace() {
        return forNamespace(null);
    }

    /**
     * Creates and returns the resource scoped to the specified namespace.
     *
     * @param namespace the namespace to which the returned resource is scoped
     * @return the resource associated with the specified namespace
     */
    T forNamespace(String namespace);

}
