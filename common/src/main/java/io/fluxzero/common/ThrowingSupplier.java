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

package io.fluxzero.common;

/**
 * Equivalent of {@link java.util.function.Supplier} whose {@link #get()} method may throw a checked
 * {@link Exception}.
 * <p>
 * This interface helps to avoid depending on framework-specific throwing functional interfaces in Fluxzero APIs.
 *
 * @param <T> the type of supplied results
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {
    /**
     * Gets a result.
     *
     * @return a result
     * @throws Exception if unable to supply a result
     */
    T get() throws Exception;
}
