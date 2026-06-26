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

package io.fluxzero.sdk.common.exception;

/**
 * Stable identifiers for Fluxzero SDK errors.
 * <p>
 * The textual message may evolve to become more helpful, but the code should remain stable so application logs,
 * support playbooks, and Fluxzero documentation can refer to the same problem over time.
 * <p>
 * Published codes are part of the SDK support contract: do not renumber, rename, or reuse them for a different meaning.
 * Add a new code when introducing a distinct error category.
 */
public enum FluxzeroErrorCode {

    /**
     * No {@link io.fluxzero.sdk.Fluxzero} instance is available for the current call.
     */
    FLUXZERO_INSTANCE_MISSING("FZ-SDK-0001"),

    /**
     * A request was sent, but no matching response arrived before the timeout elapsed.
     */
    REQUEST_TIMED_OUT("FZ-SDK-0002"),

    /**
     * A handler failed while processing a message.
     */
    HANDLER_INVOCATION_FAILED("FZ-SDK-0003"),

    /**
     * A response could not be published back to the requester.
     */
    RESPONSE_DISPATCH_FAILED("FZ-SDK-0004"),

    /**
     * A blocking wait was interrupted before Fluxzero could complete it.
     */
    THREAD_INTERRUPTED("FZ-SDK-0005"),

    /**
     * One or more messages could not be dispatched.
     */
    MESSAGE_DISPATCH_FAILED("FZ-SDK-0006"),

    /**
     * A user was attached to a message without a configured user provider.
     */
    USER_PROVIDER_MISSING("FZ-SDK-0007"),

    /**
     * Tracking could not start because consumer configuration is incomplete or conflicting.
     */
    TRACKING_CONFIGURATION_INVALID("FZ-SDK-0008"),

    /**
     * A periodic schedule annotation is missing the required timing configuration.
     */
    PERIODIC_SCHEDULE_INVALID("FZ-SDK-0009"),

    /**
     * Tracking failed while storing position or managing tracker state.
     */
    TRACKING_RUNTIME_FAILED("FZ-SDK-0010");

    /**
     * Default documentation root for SDK error pages.
     */
    public static final String DOCUMENTATION_BASE_URL = "https://fluxzero.io/docs/errors#";

    private final String code;

    FluxzeroErrorCode(String code) {
        this.code = code;
    }

    /**
     * Returns the stable machine-readable error code.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the canonical documentation URL for this error.
     */
    public String getDocumentationUrl() {
        return DOCUMENTATION_BASE_URL + code;
    }
}
