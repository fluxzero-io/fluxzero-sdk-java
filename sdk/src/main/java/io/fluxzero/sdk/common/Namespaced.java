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
     * Returns the resource scoped to the namespace configured for this application.
     *
     * @return the resource associated with the application namespace
     */
    default T forApplicationNamespace() {
        return forNamespace(null);
    }

    /**
     * Returns the resource scoped to the namespace configured for this application.
     *
     * @return the resource associated with the application namespace
     * @deprecated use {@link #forApplicationNamespace()}; {@code null} selects the application namespace, which need
     * not be the Fluxzero default namespace
     */
    @Deprecated(forRemoval = false)
    default T forDefaultNamespace() {
        return forApplicationNamespace();
    }

    /**
     * Returns the resource scoped to the specified namespace. Passing {@code null} selects the namespace configured
     * for this application.
     *
     * @param namespace the namespace to which the returned resource is scoped, or {@code null} for the application
     * namespace
     * @return the resource associated with the specified namespace
     */
    T forNamespace(String namespace);

}
