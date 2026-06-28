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

/**
 * Explicit boundary for normal JVM compatibility fallbacks.
 * <p>
 * Runtime code should reach this backend only after generated metadata/invocation/access paths have been exhausted
 * and only when generated-only mode is disabled.
 */
public final class JvmCompatibilityBackend {
    private JvmCompatibilityBackend() {
    }

    public static JvmComponentIntrospector introspector() {
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            throw new ComponentRegistryException("""
                    Generated-only metadata mode forbids JVM compatibility backend access.
                    Replace this runtime semantic path with generated metadata, invocation, or access plans.
                    """);
        }
        return JvmComponentIntrospector.getInstance();
    }
}
