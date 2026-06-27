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

package io.fluxzero.sdk.tracking.handling.authentication;

/**
 * Browser-safe view of an authenticated Fluxzero subject.
 * <p>
 * JVM runtimes can adapt their richer {@code User} implementation to this contract, while generated browser runtimes
 * can use a small in-memory user object. Authorization policy code should depend on this interface rather than on
 * runtime-specific user providers or thread locals.
 */
public interface AuthorizationSubject {
    /**
     * Returns the human-readable subject name used in authorization diagnostics.
     */
    String getName();

    /**
     * Returns whether this subject has the supplied role.
     */
    boolean hasRole(String role);
}
