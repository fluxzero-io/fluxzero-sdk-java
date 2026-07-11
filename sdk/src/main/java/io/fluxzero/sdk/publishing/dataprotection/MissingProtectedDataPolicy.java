/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.publishing.dataprotection;

/**
 * Determines how a handler responds when a message references protected data that is no longer available.
 */
public enum MissingProtectedDataPolicy {
    /**
     * Inherit the policy from the consumer, application configuration, or SDK default.
     */
    DEFAULT,

    /**
     * Invoke the handler with the unavailable protected fields set to {@code null}.
     */
    HANDLE,

    /**
     * Log a warning and invoke the handler with the unavailable protected fields set to {@code null}.
     */
    WARN,

    /**
     * Skip the handler invocation and continue message tracking normally.
     */
    SKIP,

    /**
     * Fail the handler invocation with a {@link MissingProtectedDataException}.
     */
    FAIL;

    /**
     * Application property that configures the default missing protected data policy.
     */
    public static final String PROPERTY = "fluxzero.dataProtection.onMissingProtectedData";

    /**
     * Parses a configured policy value case-insensitively.
     *
     * @param value configured value
     * @return parsed policy
     */
    public static MissingProtectedDataPolicy parse(String value) {
        for (MissingProtectedDataPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(value.trim())) {
                return policy;
            }
        }
        throw new IllegalArgumentException(
                "Missing protected data policy must be `default`, `handle`, `warn`, `skip`, or `fail`, but found `%s`."
                        .formatted(value));
    }
}
